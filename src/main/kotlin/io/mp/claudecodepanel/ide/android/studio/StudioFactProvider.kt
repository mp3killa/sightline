package io.mp.claudecodepanel.ide.android.studio

import com.android.tools.idea.gradle.project.model.GradleAndroidModel
import com.android.tools.idea.model.AndroidModel
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import io.mp.claudecodepanel.android.VariantName
import io.mp.claudecodepanel.ide.android.AndroidStudioFactProvider
import io.mp.claudecodepanel.ide.android.StudioFacts
import io.mp.claudecodepanel.ide.android.StudioModule

/**
 * Tier-1 fact provider, reading Android Studio's own Gradle project model.
 *
 * **This class only loads when `org.jetbrains.android` is present** — it is registered from
 * `sightline-android.xml`, which the platform ignores when the optional dependency is missing. That is
 * the whole isolation mechanism: the `com.android.tools.idea.*` imports below are unresolvable in a plain
 * IntelliJ IDEA, and because the class is never loaded there, they never need to resolve.
 *
 * Everything here is Android-Studio-internal API, so it is written defensively and to one rule: **any
 * failure degrades to null**, which drops the caller to tier 2 (the AGP build output). A future Android
 * Studio that renames `getSelectedVariantName` must cost the user a "(last build)" qualifier on a chip,
 * not a broken panel. That is also why nothing here throws and why the surface stays as small as it can:
 * every accessor used is one more thing to re-verify when Android Studio moves.
 */
class StudioFactProvider : AndroidStudioFactProvider {

    override fun facts(project: Project): StudioFacts? = try {
        val modules = ModuleManager.getInstance(project).modules.mapNotNull { read(it) }
        // No Android modules is a real answer for a non-Android project, but it is indistinguishable
        // from "sync hasn't populated the model yet" — so report null and let the ladder fall through
        // rather than assert an empty truth.
        modules.takeIf { it.isNotEmpty() }?.let { StudioFacts(it) }
    } catch (e: Throwable) {
        null
    }

    private fun read(module: Module): StudioModule? {
        val gradleModel = runCatching { GradleAndroidModel.get(module) }.getOrNull() ?: return null
        val androidModel = runCatching { AndroidModel.get(module) }.getOrNull()

        val variant = runCatching { gradleModel.selectedVariantName }.getOrNull()
        // Dimension order matters for decomposition — see VariantName.decompose. The model exposes
        // flavours grouped by dimension, which is exactly the order AGP concatenates them in.
        val flavorsInOrder = runCatching {
            gradleModel.productFlavorNamesByFlavorDimension.values.flatten()
        }.getOrNull().orEmpty()

        val parts = variant?.let { VariantName.decompose(it, flavorsInOrder) }

        return StudioModule(
            name = module.name.substringAfterLast('.'),
            selectedVariant = variant,
            applicationId = runCatching { androidModel?.applicationId }.getOrNull()
                ?.takeIf { it.isNotBlank() && it != AndroidModel.UNINITIALIZED_APPLICATION_ID },
            minSdk = runCatching { androidModel?.minSdkVersion?.apiLevel }.getOrNull(),
            targetSdk = runCatching { androidModel?.targetSdkVersion?.apiLevel }.getOrNull(),
            compileSdk = null, // not on the stable part of this model; tier 3 parses it from the build file
            flavors = parts?.flavors.orEmpty(),
            buildType = parts?.buildType,
        )
    }
}
