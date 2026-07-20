package io.mp.sightline.android

/**
 * Renders an [AndroidContext] two ways: the one-line strip a human reads above the composer, and the
 * block Claude receives with the message.
 *
 * They are deliberately different documents. The strip is glanceable and drops anything that would make
 * it wrap; the prompt block is complete and states provenance on every line, because the whole reason
 * for the fact ladder is that Claude must be able to tell a current variant from a stale one. Rendering
 * both from one model is what keeps them from disagreeing.
 */
object AndroidContextFormatter {

    // ---- the strip ----

    private const val SEPARATOR = "  |  "

    /**
     * `app · demoStagingDebug | Pixel 8 · API 35 · ready | RouteDetailsScreen.kt`
     *
     * Segments are dropped rather than padded when unknown: a strip reading `app · unknown | no device`
     * spends a line of the user's screen telling them nothing.
     *
     * [maxChars] is a soft width budget. Past it, **whole segments are dropped lowest-priority first**
     * rather than the line being ellipsized — the same instinct as `ui/state/PathDisplay`, which cuts a
     * path at segment boundaries because a mid-segment cut renders as damage. Half a device name tells
     * you nothing and looks broken; no device name tells you nothing and looks deliberate. Nothing is
     * lost either way: every dropped segment is still on its chip and in the tooltip.
     */
    fun strip(context: AndroidContext, maxChars: Int = Int.MAX_VALUE): String {
        if (context.isEmpty) return ""

        // Highest priority last, so dropping from the end removes the least important first.
        val segments = mutableListOf<String>()
        context.activeModule?.let { m ->
            val variant = m.variant.value
            segments += if (variant != null) "${m.name} · $variant" else m.name
        }
        context.device?.let { segments += it.summary }
        context.editor?.relativePath?.let { segments += it.substringAfterLast('/') }

        var kept: List<String> = segments
        while (kept.size > 1 && length(kept) > maxChars) {
            kept = kept.dropLast(1)
        }
        // One segment left and still over budget: keep it. A truncated module name is worse than a
        // slightly clipped one, and the tooltip has the whole story.
        return kept.joinToString(SEPARATOR)
    }

    private fun length(segments: List<String>): Int =
        segments.sumOf { it.length } + SEPARATOR.length * (segments.size - 1).coerceAtLeast(0)

    /**
     * The strip's tooltip — the same facts with their provenance spelled out, so a user who wonders why
     * a variant looks wrong can find out by hovering rather than by being wrong for an hour.
     */
    fun stripTooltip(context: AndroidContext): String {
        if (context.isEmpty) return "No Android context"
        val lines = mutableListOf<String>()
        context.activeModule?.let { m ->
            lines += "Module: ${m.name} (${m.gradlePath})"
            if (m.variant.isKnown) lines += "Variant: ${m.variant.describe()}"
            if (m.applicationId.isKnown) lines += "Application ID: ${m.applicationId.describe()}"
        }
        context.device?.let { d ->
            lines += "Device: ${d.name} (${d.serial}) · ${d.state.label}"
        }
        context.editor?.relativePath?.let { lines += "File: $it" }
        return lines.joinToString("\n")
    }

    // ---- the prompt block ----

    private const val OPEN = "<android-context>"
    private const val CLOSE = "</android-context>"

    /**
     * The block prepended to a user message, or "" when there is nothing worth sending.
     *
     * Only [enabled] chips contribute, so removing a chip genuinely removes it from the prompt. The
     * closing note is not decoration: without it a model reasonably assumes this block is a complete
     * description of the project, and confidently answers questions about modules it was never told about.
     */
    fun promptBlock(context: AndroidContext, enabled: Set<ContextChipKind>): String {
        if (context.isEmpty || enabled.isEmpty()) return ""
        val lines = mutableListOf<String>()
        val module = context.activeModule

        if (module != null && ContextChipKind.MODULE in enabled) {
            lines += "Module: ${module.name} (${module.gradlePath})"
            module.applicationId.ifKnown { lines += "Application ID: ${it.describe()}" }
            sdkLine(module)?.let { lines += it }
            module.usesCompose.value?.let { lines += "UI toolkit: ${if (it) "Jetpack Compose" else "Android Views (XML)"}" }
            if (context.modules.size > 1) {
                lines += "Other modules: " + context.modules.filter { it.name != module.name }
                    .joinToString(", ") { it.name }
            }
        }

        if (module != null && ContextChipKind.VARIANT in enabled) {
            module.variant.ifKnown { v ->
                lines += "Build variant: ${v.describe()}"
                val flavors = module.flavors.value.orEmpty()
                if (flavors.isNotEmpty()) lines += "Product flavours: ${flavors.joinToString(", ")}"
                module.buildType.ifKnown { lines += "Build type: ${it.describe()}" }
            }
            module.versionName.ifKnown { vn ->
                val code = module.versionCode.value?.let { " (code $it)" }.orEmpty()
                lines += "Version: ${vn.value}$code"
            }
            if (module.builtVariants.size > 1) {
                lines += "Variants built: ${module.builtVariants.joinToString(", ")}"
            }
        }

        if (ContextChipKind.DEVICE in enabled) {
            lines += context.device?.let { d ->
                buildString {
                    append("Device: ${d.name} (${d.serial}) · ${d.state.label}")
                    d.apiLevel.value?.let { append(" · API $it") }
                    d.androidRelease.value?.let { append(" · Android $it") }
                    d.appRunning.value?.let { append(if (it) " · app running" else " · app not running") }
                }
            } ?: "Device: none connected"
        }

        if (ContextChipKind.CURRENT_FILE in enabled) {
            context.editor?.relativePath?.let { path ->
                val symbol = context.editor.symbol?.let { " · $it" }.orEmpty()
                lines += "Open file: $path$symbol"
            }
        }

        if (ContextChipKind.SELECTION in enabled) {
            context.editor?.selectionLines?.let { r ->
                lines += "Selection: lines ${r.first}–${r.last} of ${context.editor.relativePath ?: "the open file"}"
            }
        }

        if (ContextChipKind.RECENT_CHANGES in enabled && context.recentlyChanged.isNotEmpty()) {
            lines += "Recently changed: ${context.recentlyChanged.take(8).joinToString(", ")}"
        }

        if (lines.isEmpty()) return ""
        return buildString {
            append(OPEN).append('\n')
            lines.forEach { append(it).append('\n') }
            append(FOOTER).append('\n')
            append(CLOSE)
        }
    }

    /**
     * The honesty clause. It says the block is a summary rather than a survey, and — the part that earns
     * its tokens — that a value marked `(last build)` or `(declared)` may not be what is selected now.
     */
    private const val FOOTER =
        "(Supplied by the IDE, so you don't need to run adb or read build files to find these. " +
            "A value marked (last build) or (declared) may be stale — re-check it before relying on it " +
            "for something destructive. This lists the active module, not every module.)"

    private fun sdkLine(m: ModuleContext): String? {
        val bits = buildList {
            m.minSdk.value?.let { add("min $it") }
            m.targetSdk.value?.let { add("target $it") }
            m.compileSdk.value?.let { add("compile $it") }
        }
        return bits.takeIf { it.isNotEmpty() }?.let { "SDK: ${it.joinToString(", ")}" }
    }

    private inline fun <T : Any> Fact<T>.ifKnown(block: (Fact<T>) -> Unit) {
        if (isKnown) block(this)
    }

    /** True if [text] already carries a context block — the guard against double-injection on requeue. */
    fun containsContextBlock(text: String): Boolean = text.contains(OPEN)

    // ---- chips ----

    /**
     * Which chips are worth offering for this context, in strip order.
     *
     * A chip is offered only when it would actually carry something. Showing a "Selected code" chip with
     * nothing selected makes the row a menu of possibilities rather than a description of what is being
     * sent — and the point of the chips is that they say what is being sent.
     */
    fun availableChips(context: AndroidContext): List<ContextChipKind> {
        if (context.isEmpty) return emptyList()
        return ContextChipKind.entries.filter { kind ->
            when (kind) {
                ContextChipKind.MODULE -> context.activeModule != null
                ContextChipKind.VARIANT -> context.activeModule?.variant?.isKnown == true
                // Offered even with nothing connected: "no device" is a fact worth sending, and it is
                // the one that explains why a run request is about to fail.
                ContextChipKind.DEVICE -> true
                ContextChipKind.CURRENT_FILE -> context.editor?.relativePath != null
                ContextChipKind.SELECTION -> context.editor?.selectionLines != null
                ContextChipKind.RECENT_CHANGES -> context.recentlyChanged.isNotEmpty()
            }
        }
    }

    /**
     * The chip's own text — the *value*, not the category. `stagingDebug` tells you what will be sent;
     * `Variant` only tells you a variant will be, which you could have guessed.
     */
    fun chipLabel(kind: ContextChipKind, context: AndroidContext): String {
        val module = context.activeModule
        return when (kind) {
            ContextChipKind.MODULE -> module?.name ?: kind.label
            ContextChipKind.VARIANT -> module?.variant?.value ?: kind.label
            ContextChipKind.DEVICE -> context.device?.name ?: "No device"
            ContextChipKind.CURRENT_FILE -> context.editor?.relativePath?.substringAfterLast('/') ?: kind.label
            ContextChipKind.SELECTION -> context.editor?.selectionLines
                ?.let { "Lines ${it.first}–${it.last}" } ?: kind.label
            ContextChipKind.RECENT_CHANGES -> "${context.recentlyChanged.size} changed"
        }
    }

    /** The chip's tooltip: what it is, plus where the value came from. */
    fun chipTooltip(kind: ContextChipKind, context: AndroidContext): String {
        val module = context.activeModule
        return when (kind) {
            ContextChipKind.MODULE -> "Module ${module?.name} (${module?.gradlePath})"
            ContextChipKind.VARIANT -> "Build variant: " + (module?.variant?.describe() ?: "unknown")
            ContextChipKind.DEVICE -> context.device?.let { "${it.name} (${it.serial}) · ${it.state.label}" }
                ?: "No device or emulator connected"
            ContextChipKind.CURRENT_FILE -> context.editor?.relativePath ?: kind.label
            ContextChipKind.SELECTION -> "The selected lines of ${context.editor?.relativePath ?: "the open file"}"
            ContextChipKind.RECENT_CHANGES -> context.recentlyChanged.take(8).joinToString("\n")
        } + "\n\nRemove to leave it out of the next message."
    }
}
