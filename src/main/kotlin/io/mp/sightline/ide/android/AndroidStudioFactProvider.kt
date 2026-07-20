package io.mp.sightline.ide.android

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/** One Android module as Android Studio's own model sees it. Every field is nullable — the model is
 *  populated by a Gradle sync, so mid-sync or after a failed sync these are legitimately unknown. */
data class StudioModule(
    val name: String,
    val selectedVariant: String? = null,
    val applicationId: String? = null,
    val minSdk: Int? = null,
    val targetSdk: Int? = null,
    val compileSdk: String? = null,
    val flavors: List<String> = emptyList(),
    val buildType: String? = null,
)

data class StudioFacts(val modules: List<StudioModule>)

/**
 * The tier-1 rung of the fact ladder (docs/ANDROID.md §1.2): facts only Android Studio's own project
 * model knows, chiefly **which build variant is currently selected**. That is the one fact with no
 * fallback — `.idea/` does not persist it and no build output records the *current* selection, only the
 * last one built.
 *
 * Implemented behind an extension point registered from `sightline-android.xml`, which loads only when
 * `org.jetbrains.android` is present. The implementation class therefore never loads in a plain IntelliJ
 * IDEA, so its Android-Studio-internal imports can't produce a `NoClassDefFoundError` there. Everything
 * else in Sightline treats tier 1 as an *upgrade*: absent, the ladder falls through to the AGP build
 * output and static parsing, and the user sees "last build" instead of a bare variant name.
 *
 * Keep this interface narrow. Every method added here is another piece of `com.android.tools.idea.*`
 * internal API to re-verify when Android Studio moves.
 */
interface AndroidStudioFactProvider {

    /** Null when the model isn't usable right now (no sync yet, not a Gradle project, sync failed). */
    fun facts(project: Project): StudioFacts?

    companion object {
        private val EP = ExtensionPointName<AndroidStudioFactProvider>("io.mp.sightline.androidFactProvider")

        /** True when Android Studio's model layer is present — reported by the Health panel. */
        fun isAvailable(): Boolean = runCatching { EP.extensionList.isNotEmpty() }.getOrDefault(false)

        /**
         * Tier-1 facts, or null. Failures are swallowed to null on purpose: this whole rung is optional,
         * and an internal-API change in a future Android Studio must degrade Sightline to tier 2, not
         * break the panel.
         */
        fun facts(project: Project): StudioFacts? = try {
            EP.extensionList.firstNotNullOfOrNull { it.facts(project) }
        } catch (e: Throwable) {
            thisLogger().info("android: Studio fact provider unavailable (${e.javaClass.simpleName})")
            null
        }
    }
}
