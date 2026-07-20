package io.mp.sightline.ide.android.studio

import com.android.tools.idea.gradle.project.model.GradleAndroidModel
import com.android.tools.idea.model.AndroidModel
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import io.mp.sightline.android.VariantName
import io.mp.sightline.ide.android.AndroidStudioFactProvider
import io.mp.sightline.ide.android.StudioFacts
import io.mp.sightline.ide.android.StudioModule

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
            // minSdk/targetSdk/compileSdk are deliberately NOT read here.
            //
            // `AndroidModel.getMinSdkVersion()` returns `com.android.sdklib.AndroidVersion`, and
            // referencing that type made the Plugin Verifier report an unresolved package when checking
            // against a plain IntelliJ IDEA — correctly, since `com.android.sdklib` ships with the
            // Android plugin. The code path never runs there (this class only loads via
            // sightline-android.xml), but a reported compatibility problem on a Marketplace submission
            // is a real cost for a fact we already have: tier 3 parses all three straight out of
            // `build.gradle(.kts)`, and they rarely differ from what is declared.
            //
            // This is the narrow-interface rule in practice: the AS model earns its place for the
            // *selected variant* and the flavour-suffixed applicationId, which have no other source.
            // Everything else belongs to a tier that costs nothing to depend on.
            flavors = parts?.flavors.orEmpty(),
            buildType = parts?.buildType,
        )
    }
}
