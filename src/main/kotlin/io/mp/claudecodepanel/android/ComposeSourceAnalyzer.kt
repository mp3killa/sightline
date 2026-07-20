package io.mp.claudecodepanel.android

/** A `@Composable` function found in source. */
data class ComposableFunction(
    val name: String,
    val line: Int,
    /** `@Preview`-annotated functions that call this one, or annotate it directly. */
    val hasPreview: Boolean = false,
    /** True when the name and signature suggest a screen-level entry point rather than a leaf. */
    val isScreenLevel: Boolean = false,
    val parameters: List<String> = emptyList(),
)

/** A `@Preview` function, and which variants it covers. */
data class ComposePreview(
    val name: String,
    val line: Int,
    /** `uiMode = UI_MODE_NIGHT_YES` and friends, verbatim as written. */
    val arguments: String? = null,
    val coversDarkMode: Boolean = false,
    val coversFontScale: Boolean = false,
    val coversDeviceSize: Boolean = false,
)

/** Something worth flagging about a composable, with the line to open. */
data class ComposeFinding(
    val kind: Kind,
    val message: String,
    val line: Int,
    val symbol: String? = null,
) {
    enum class Kind(val label: String) {
        MISSING_PREVIEW("No preview"),
        PREVIEW_MISSING_DARK("Preview covers only one theme"),
        PREVIEW_MISSING_FONT_SCALE("No large-font preview"),
        MISSING_CONTENT_DESCRIPTION("Image without a content description"),
        STATE_WITHOUT_REMEMBER("State created without remember"),
        SIDE_EFFECT_IN_COMPOSITION("Side effect run directly in composition"),
    }
}

data class ComposeAnalysis(
    val composables: List<ComposableFunction>,
    val previews: List<ComposePreview>,
    val findings: List<ComposeFinding>,
) {
    val isCompose: Boolean get() = composables.isNotEmpty() || previews.isNotEmpty()
}

/**
 * Reads what a Kotlin source file says about its composables — previews, and a small set of issues that
 * are visible in the text with no ambiguity.
 *
 * **Scope is deliberately narrow, and the boundary is the interesting part.** Most of proposal §4's
 * Compose analysis — unnecessary recomposition, unstable parameters, expensive work in composition — is
 * not decidable from source text. Answering those requires the Compose compiler's own stability
 * inference or a runtime trace, and a regex that *approximates* them would produce exactly the
 * confidently-wrong output the rest of this codebase is built to avoid. So they are not attempted here.
 *
 * What is offered is what a text scan can establish beyond doubt: which functions are `@Composable`,
 * which have previews and what those previews cover, and a few syntactic mistakes that are unambiguous
 * — `mutableStateOf` without `remember`, an `Image` with no `contentDescription`. Each finding names a
 * line, so it can be opened rather than merely asserted.
 *
 * The deeper analysis stays where it belongs: Claude reading the file, better done with this as a
 * starting point than replaced by a heuristic here.
 */
object ComposeSourceAnalyzer {

    fun analyze(source: String): ComposeAnalysis {
        val lines = source.lines()
        val previews = findPreviews(lines)
        val composables = findComposables(lines, previews)
        return ComposeAnalysis(composables, previews, findFindings(lines, composables, previews))
    }

    // ---- composables and previews ----

    private fun findComposables(lines: List<String>, previews: List<ComposePreview>): List<ComposableFunction> {
        val out = mutableListOf<ComposableFunction>()
        val previewedNames = previews.map { it.name.removeSuffix("Preview") }.toSet()

        for ((i, line) in lines.withIndex()) {
            if (!line.trimStart().startsWith("@Composable")) continue
            // The declaration may sit a few lines below the annotation, past other annotations.
            val declIndex = (i + 1..minOf(i + 6, lines.lastIndex)).firstOrNull { FUN_DECL.containsMatchIn(lines[it]) }
                ?: continue
            val m = FUN_DECL.find(lines[declIndex]) ?: continue
            val name = m.groupValues[1]
            // A preview function is itself @Composable; listing it as one would double-count.
            if (previews.any { it.name == name }) continue

            val params = m.groupValues[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }
            out += ComposableFunction(
                name = name,
                line = declIndex + 1,
                hasPreview = name in previewedNames || previews.any { it.name.contains(name) },
                isScreenLevel = looksScreenLevel(name),
                parameters = params,
            )
        }
        return out
    }

    private fun findPreviews(lines: List<String>): List<ComposePreview> {
        val out = mutableListOf<ComposePreview>()
        for ((i, line) in lines.withIndex()) {
            val trimmed = line.trimStart()
            if (!trimmed.startsWith("@Preview")) continue
            val args = Regex("""@Preview\s*\((.*)\)""").find(trimmed)?.groupValues?.get(1)
            val declIndex = (i + 1..minOf(i + 6, lines.lastIndex)).firstOrNull { FUN_DECL.containsMatchIn(lines[it]) }
                ?: continue
            val name = FUN_DECL.find(lines[declIndex])?.groupValues?.get(1) ?: continue

            // Multiple @Preview annotations can stack on one function; merge their coverage.
            val existing = out.indexOfFirst { it.name == name }
            val preview = ComposePreview(
                name = name,
                line = declIndex + 1,
                arguments = args,
                coversDarkMode = args?.contains("UI_MODE_NIGHT_YES") == true,
                coversFontScale = args?.contains("fontScale") == true,
                coversDeviceSize = args?.let { it.contains("device") || it.contains("widthDp") } == true,
            )
            if (existing >= 0) {
                val prior = out[existing]
                out[existing] = prior.copy(
                    coversDarkMode = prior.coversDarkMode || preview.coversDarkMode,
                    coversFontScale = prior.coversFontScale || preview.coversFontScale,
                    coversDeviceSize = prior.coversDeviceSize || preview.coversDeviceSize,
                )
            } else {
                out += preview
            }
        }
        return out
    }

    /** A screen-level composable is worth a preview; a one-line leaf usually isn't. */
    private fun looksScreenLevel(name: String): Boolean =
        name.endsWith("Screen") || name.endsWith("Page") || name.endsWith("Route") || name.endsWith("Content")

    // ---- findings: only what the text establishes beyond doubt ----

    private fun findFindings(
        lines: List<String>,
        composables: List<ComposableFunction>,
        previews: List<ComposePreview>,
    ): List<ComposeFinding> {
        val out = mutableListOf<ComposeFinding>()

        for (c in composables) {
            // Only screen-level composables are flagged. Demanding a preview for every leaf composable
            // would bury the one that matters under thirty that don't.
            if (c.isScreenLevel && !c.hasPreview) {
                out += ComposeFinding(
                    ComposeFinding.Kind.MISSING_PREVIEW,
                    "${c.name} looks screen-level but has no @Preview.",
                    c.line,
                    c.name,
                )
            }
        }
        for (p in previews) {
            if (!p.coversDarkMode) {
                out += ComposeFinding(
                    ComposeFinding.Kind.PREVIEW_MISSING_DARK,
                    "${p.name} has no dark-theme preview (uiMode = UI_MODE_NIGHT_YES).",
                    p.line,
                    p.name,
                )
            }
            if (!p.coversFontScale) {
                out += ComposeFinding(
                    ComposeFinding.Kind.PREVIEW_MISSING_FONT_SCALE,
                    "${p.name} has no large-font preview (fontScale = 1.5f) — the condition that " +
                        "surfaces text clipping.",
                    p.line,
                    p.name,
                )
            }
        }

        for ((i, raw) in lines.withIndex()) {
            val line = raw.trim()
            if (line.startsWith("//") || line.startsWith("*")) continue

            // `val x = mutableStateOf(…)` inside a composable is recreated on every recomposition. The
            // `remember`-wrapped form is the correct one, and the difference is textual and unambiguous.
            if (MUTABLE_STATE_NO_REMEMBER.containsMatchIn(line)) {
                out += ComposeFinding(
                    ComposeFinding.Kind.STATE_WITHOUT_REMEMBER,
                    "mutableStateOf without remember — this is recreated on every recomposition.",
                    i + 1,
                )
            }
            // `Image(...)` / `Icon(...)` with no contentDescription argument at all. Only flagged when
            // the call closes on the same line, so a multi-line call isn't accused of omitting an
            // argument that is simply further down.
            if (IMAGE_CALL.containsMatchIn(line) && line.contains(')') && !line.contains("contentDescription")) {
                out += ComposeFinding(
                    ComposeFinding.Kind.MISSING_CONTENT_DESCRIPTION,
                    "Image/Icon with no contentDescription — screen readers will skip it. " +
                        "Pass null explicitly if it is decorative.",
                    i + 1,
                )
            }
        }
        return out
    }

    private val FUN_DECL = Regex("""^\s*(?:private\s+|internal\s+|public\s+)?fun\s+([A-Za-z_][\w]*)\s*\(([^)]*)""")
    private val MUTABLE_STATE_NO_REMEMBER = Regex("""\bval\s+\w+\s*=\s*mutableStateOf\w*\(""")
    private val IMAGE_CALL = Regex("""\b(?:Image|Icon)\s*\(""")
}
