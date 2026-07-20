package io.mp.claudecodepanel.android

/**
 * Where an Android fact came from — the "fact ladder" of docs/ANDROID.md §1.2, strongest source first.
 *
 * This is the Android analogue of `activity/EvidenceSource` and `health/HealthStatus.UNKNOWN`, and it
 * exists for the same reason: a fact that is quietly stale is worse than no fact at all. A variant read
 * from the IDE's own selection is current by construction; the same string recovered from the last build
 * output may be hours out of date, and the user needs to be able to tell those apart at a glance.
 *
 * Declaration order is confidence order — [IDE] is strongest, [UNKNOWN] weakest — so [strongerOf] and
 * sorting both work off the ordinal.
 */
enum class FactTier(val label: String) {
    /** Android Studio's own model (optional dependency). Current by construction. */
    IDE("IDE"),

    /**
     * A machine-readable artifact AGP wrote during a build (`output-metadata.json`, the merged manifest
     * tree). Accurate for the variant it describes, but describes the *last build*, not necessarily the
     * current selection.
     */
    BUILD_OUTPUT("last build"),

    /**
     * Parsed from source: `build.gradle(.kts)`, `libs.versions.toml`, the source manifest. Reflects what
     * is declared, which is not always what resolves — a flavour's override or a manifest merge can
     * change it.
     */
    STATIC_PARSE("declared"),

    /** Read off a running device or emulator with adb. True of the device, right now. */
    DEVICE("device"),

    /** Not determinable. Never a stand-in for a plausible default. */
    UNKNOWN("unknown"),
    ;

    fun strongerOf(other: FactTier): FactTier = if (other.ordinal < ordinal) other else this
}

/**
 * One Android fact plus its provenance. A null [value] always pairs with [FactTier.UNKNOWN]; the
 * constructor enforces it, so "we have a value but don't know where from" cannot be represented.
 *
 * [note] carries the qualification a user needs to act on the tier — "built 20 minutes ago", "flavour
 * `staging` overrides this" — and is rendered alongside the value wherever the fact is shown.
 */
data class Fact<out T : Any>(
    val value: T?,
    val tier: FactTier,
    val note: String? = null,
) {
    init {
        require((value == null) == (tier == FactTier.UNKNOWN)) {
            "a Fact is UNKNOWN if and only if it has no value (value=$value, tier=$tier)"
        }
    }

    val isKnown: Boolean get() = value != null

    /** The value with its provenance, for display: `demoStagingDebug (last build)`. */
    fun describe(render: (T) -> String = { it.toString() }): String {
        val v = value ?: return note?.let { "unknown — $it" } ?: "unknown"
        val qualifier = note ?: tier.label
        // The IDE tier is the one a user assumes by default, so labelling it adds noise, not information.
        return if (tier == FactTier.IDE && note == null) render(v) else "${render(v)} ($qualifier)"
    }

    companion object {
        fun <T : Any> known(value: T, tier: FactTier, note: String? = null): Fact<T> {
            require(tier != FactTier.UNKNOWN) { "a known fact cannot be UNKNOWN-tier" }
            return Fact(value, tier, note)
        }

        fun <T : Any> unknown(note: String? = null): Fact<T> = Fact(null, FactTier.UNKNOWN, note)

        /**
         * The first known candidate, in ladder order. Candidates are supplied strongest-first and
         * evaluated lazily, so a cheap IDE lookup short-circuits an expensive device probe.
         */
        fun <T : Any> ladder(vararg candidates: () -> Fact<T>): Fact<T> {
            var lastNote: String? = null
            for (c in candidates) {
                val f = c()
                if (f.isKnown) return f
                lastNote = f.note ?: lastNote
            }
            return unknown(lastNote)
        }
    }
}
