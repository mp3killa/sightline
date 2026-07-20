package io.mp.claudecodepanel.android

/** A module as the context strip understands it. Every derived fact carries the tier it came from. */
data class ModuleContext(
    /** Display name — `app`, `core`, `wear`. */
    val name: String,
    /** Gradle path — `:app`. What a task name is built from. */
    val gradlePath: String,
    val variant: Fact<String> = Fact.unknown(),
    val flavors: Fact<List<String>> = Fact.unknown(),
    val buildType: Fact<String> = Fact.unknown(),
    val applicationId: Fact<String> = Fact.unknown(),
    /**
     * The module's `namespace` — the package root the *code* lives in. Distinct from [applicationId],
     * and often different: a flavour's `applicationIdSuffix` makes the installed id
     * `com.example.driver.staging` while classes stay in `com.example.compose`. Both are needed to decide
     * whether a stack frame belongs to this app.
     */
    val namespace: Fact<String> = Fact.unknown(),
    val minSdk: Fact<Int> = Fact.unknown(),
    val targetSdk: Fact<Int> = Fact.unknown(),
    val compileSdk: Fact<String> = Fact.unknown(),
    val versionName: Fact<String> = Fact.unknown(),
    val versionCode: Fact<Int> = Fact.unknown(),
    val usesCompose: Fact<Boolean> = Fact.unknown(),
    /** Variants with a build output on disk — what tier 2 could see, useful for "which can I pick?". */
    val builtVariants: List<String> = emptyList(),
)

/** The device or emulator in play. */
data class DeviceContext(
    val serial: String,
    val name: String,
    val state: DeviceState,
    val apiLevel: Fact<Int> = Fact.unknown(),
    val androidRelease: Fact<String> = Fact.unknown(),
    /** Whether the module's applicationId has a live process. Unknown when we couldn't ask. */
    val appRunning: Fact<Boolean> = Fact.unknown(),
) {
    /** `Pixel 8 · API 35 · ready` — the middle segment of the strip. */
    val summary: String
        get() = buildList {
            add(name)
            apiLevel.value?.let { add("API $it") }
            add(state.label)
        }.joinToString(" · ")
}

/** What the user is looking at. Paths are **project-relative** — an absolute path is noise on every row. */
data class EditorContext(
    val relativePath: String? = null,
    val symbol: String? = null,
    val selectionLines: IntRange? = null,
)

/**
 * Everything Sightline knows about the Android state of this project right now — the model behind the
 * context strip and behind `android.getContext`.
 *
 * The point of the whole thing (docs/ANDROID.md §0): Claude can already run `adb` and read
 * `build.gradle.kts`. What it cannot do is know *which* variant, device and module apply without
 * rediscovering them every turn, at token cost, with a fresh chance of getting them wrong. This carries
 * the answer, with provenance, so it doesn't have to.
 */
data class AndroidContext(
    val modules: List<ModuleContext> = emptyList(),
    /** The module the strip and prompt lead with — the one owning the open file, else the application. */
    val activeModuleName: String? = null,
    val device: DeviceContext? = null,
    val editor: EditorContext? = null,
    /** Project-relative paths changed recently, newest first. */
    val recentlyChanged: List<String> = emptyList(),
    /** `[versions]` from `gradle/libs.versions.toml`, if there is one. */
    val versionCatalog: Map<String, String> = emptyMap(),
    /** True when the project isn't Android at all — the strip hides itself entirely. */
    val notAndroid: Boolean = false,
) {
    val activeModule: ModuleContext?
        get() = modules.firstOrNull { it.name == activeModuleName } ?: modules.firstOrNull()

    val isEmpty: Boolean get() = notAndroid || (modules.isEmpty() && device == null)

    companion object {
        val NOT_ANDROID = AndroidContext(notAndroid = true)
    }
}

/**
 * The removable pieces of context, as chips above the composer.
 *
 * Each is a **separately removable** unit because the reasons to drop one differ: a logcat attachment is
 * large, a selection is private, a device is irrelevant when the question is about a build file. Removing
 * a chip removes it from the prompt — the chips *are* the control, not a decoration describing one.
 */
enum class ContextChipKind(val id: String, val label: String) {
    MODULE("module", "Module"),
    VARIANT("variant", "Variant"),
    DEVICE("device", "Device"),
    CURRENT_FILE("file", "Current file"),
    SELECTION("selection", "Selected code"),
    RECENT_CHANGES("changes", "Recent changes"),
    ;

    companion object {
        fun byId(id: String): ContextChipKind? = entries.firstOrNull { it.id == id }

        /**
         * On by default. Selection and recent changes are **off**: a selection may be private and is
         * usually already the subject of the sentence, and a change list is only occasionally the point.
         * Cheap, always-relevant facts default on; bulky or situational ones default off.
         */
        val DEFAULT_ENABLED: Set<ContextChipKind> = setOf(MODULE, VARIANT, DEVICE, CURRENT_FILE)
    }
}
