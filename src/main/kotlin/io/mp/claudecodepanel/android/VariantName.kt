package io.mp.claudecodepanel.android

/** A variant name taken apart: `demoStagingDebug` → flavours `[demo, staging]`, build type `debug`. */
data class VariantParts(
    val variant: String,
    val flavors: List<String>,
    val buildType: String?,
)

/**
 * Pure decomposition of an AGP variant name.
 *
 * Worth its own file because the same string arrives from three rungs of the fact ladder and has to mean
 * the same thing at each: Android Studio's `getSelectedVariantName()`, a `merged_manifest/<variant>/`
 * directory name, and a Gradle task name like `assembleDemoStagingDebug`. Doing it once, purely, keeps
 * the tiers consistent — and keeps this testable, which matters because the naming rule has a genuine
 * ambiguity in it (see [decompose]).
 */
object VariantName {

    /**
     * Build types every AGP project has unless they were explicitly removed. Used only by
     * [guessBuildType], which is allowed to return null and never to be wrong.
     */
    private val CONVENTIONAL_BUILD_TYPES = listOf("debug", "release")

    /**
     * Split [variant] using the flavours actually declared for the module.
     *
     * The flavour list must be in **dimension order** — the order AGP concatenates them in — because the
     * variant name alone is genuinely ambiguous otherwise: with flavours `staging` and `stagingDebug`
     * declared, the name `stagingDebug` could decompose two ways, and only the declaration order settles
     * it. When the flavours don't match the name at all we return them empty rather than force a fit;
     * a wrong split is worse than an incomplete one, because it silently renames the build type.
     */
    fun decompose(variant: String, declaredFlavors: List<String>): VariantParts {
        if (variant.isBlank()) return VariantParts(variant, emptyList(), null)

        var rest = variant
        val matched = mutableListOf<String>()
        for (flavor in declaredFlavors) {
            if (flavor.isBlank()) continue
            val head = if (matched.isEmpty()) flavor else flavor.replaceFirstChar { it.uppercase() }
            if (!rest.startsWith(head)) continue
            matched += flavor
            rest = rest.removePrefix(head)
        }

        val buildType = rest.takeIf { it.isNotEmpty() }?.replaceFirstChar { it.lowercase() }
        return if (matched.isEmpty() && declaredFlavors.isNotEmpty()) {
            // Declared flavours exist but none matched — we don't understand this name; say so.
            VariantParts(variant, emptyList(), guessBuildType(variant))
        } else {
            VariantParts(variant, matched, buildType)
        }
    }

    /**
     * Build type from the name alone, for when the declared flavours aren't known yet (a variant read off
     * a build-output directory before anything has been parsed). Returns null unless the name ends in a
     * conventional build type, so an unusual project gets "unknown" rather than a confident wrong answer.
     */
    fun guessBuildType(variant: String): String? {
        val v = variant.trim()
        if (v.isEmpty()) return null
        for (bt in CONVENTIONAL_BUILD_TYPES) {
            if (v.equals(bt, ignoreCase = true)) return bt
            if (v.length > bt.length && v.endsWith(bt.replaceFirstChar { it.uppercase() })) return bt
        }
        return null
    }

    /** `demoStagingDebug` → `DemoStagingDebug`, for building `assemble<Variant>` task names. */
    fun capitalized(variant: String): String = variant.replaceFirstChar { it.uppercase() }

    /**
     * A human rendering: `demo · staging · debug`. Falls back to the raw name when we couldn't split it,
     * which reads honestly rather than pretending to a structure we didn't find.
     */
    fun describe(parts: VariantParts): String {
        val bits = parts.flavors + listOfNotNull(parts.buildType)
        return if (bits.isEmpty()) parts.variant else bits.joinToString(" · ")
    }
}
