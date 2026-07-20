package io.mp.sightline.android

/** A device configuration to apply, plus exactly how to put the device back. */
data class RecipePlan(
    val recipe: DeviceRecipe,
    val apply: List<DeviceAction>,
    /**
     * Commands that restore the values captured before [apply]. Empty when the current state could not
     * be read — in which case the recipe **must not be applied**, see [DeviceRecipes.plan].
     */
    val revert: List<DeviceAction>,
    /** Settings whose prior value we failed to read. Non-empty means the plan is not safely revertible. */
    val unreadable: List<String> = emptyList(),
) {
    val isRevertible: Boolean get() = unreadable.isEmpty()
    val needsConfirmation: Boolean get() = apply.any { it.needsConfirmation }
}

/** A named set of device conditions worth testing under. */
enum class DeviceRecipe(val label: String, val description: String) {
    LARGE_FONT("Large font", "Font scale 1.5× — the condition that surfaces text clipping"),
    ACCESSIBILITY(
        "Accessibility conditions",
        "Font scale 1.5×, large display, dark mode and TalkBack — the combination most often skipped",
    ),
    DARK_MODE("Dark mode", "Night mode on"),
    LANDSCAPE("Landscape", "Rotated, with auto-rotate off so it stays put"),
    ;
}

/**
 * Builds a [RecipePlan]: the commands to apply a device configuration, and the commands to undo it.
 *
 * The revert half is the whole reason this class exists rather than a list of adb one-liners. Changing
 * font scale and dark mode on someone's device — often their actual phone — is only acceptable if
 * Sightline can put it back, and "put it back" means restoring **the values that were there**, not
 * resetting to a default. Setting font scale back to 1.0 is not a revert if the user runs at 1.15.
 *
 * So the flow is read → apply → restore-captured-values, and a plan whose prior state could not be read
 * is reported as **not revertible** and must be refused rather than applied. Failing to change a setting
 * costs a test run; failing to restore one silently reconfigures a device the developer then uses all day.
 */
object DeviceRecipes {

    /** The settings each recipe touches, and therefore the ones that must be captured first. */
    fun touchedSettings(recipe: DeviceRecipe): List<String> = when (recipe) {
        DeviceRecipe.LARGE_FONT -> listOf("font_scale")
        DeviceRecipe.DARK_MODE -> listOf("night_mode")
        DeviceRecipe.LANDSCAPE -> listOf("user_rotation", "accelerometer_rotation")
        DeviceRecipe.ACCESSIBILITY -> listOf("font_scale", "night_mode", "enabled_accessibility_services")
    }

    /**
     * Build the plan. [currentState] is what the probes read, keyed as in [touchedSettings]; a missing
     * or null value means "couldn't read", which lands in [RecipePlan.unreadable].
     */
    fun plan(
        recipe: DeviceRecipe,
        serial: String?,
        currentState: Map<String, String?>,
    ): RecipePlan {
        val apply = mutableListOf<DeviceAction>()
        val revert = mutableListOf<DeviceAction>()
        val unreadable = mutableListOf<String>()

        /**
         * [blankIsAValue] matters more than it looks. For `enabled_accessibility_services`, empty means
         * "no services enabled" — the normal state of almost every device — so treating blank as a
         * failed read made the accessibility recipe refuse to run on the machines it most applies to.
         * For a numeric setting like `font_scale`, blank really is a failed read.
         */
        fun restoreSetting(key: String, blankIsAValue: Boolean = false, restore: (String) -> DeviceAction) {
            val raw = currentState[key]
            val readable = raw != null && raw != "null" && (blankIsAValue || raw.isNotBlank())
            if (!readable) unreadable += key else revert += restore(raw!!)
        }

        when (recipe) {
            DeviceRecipe.LARGE_FONT -> {
                apply += DeviceActions.setFontScale(serial, 1.5f)
                restoreSetting("font_scale") { DeviceActions.setFontScale(serial, it.toFloatOrNull() ?: 1.0f) }
            }
            DeviceRecipe.DARK_MODE -> {
                apply += DeviceActions.setDarkMode(serial, true)
                restoreSetting("night_mode") { DeviceActions.setDarkMode(serial, wasNight(it)) }
            }
            DeviceRecipe.LANDSCAPE -> {
                // Auto-rotate off first, or the device rotates straight back and the test is meaningless.
                apply += DeviceActions.setAutoRotate(serial, false)
                apply += DeviceActions.setRotation(serial, landscape = true)
                restoreSetting("accelerometer_rotation") { DeviceActions.setAutoRotate(serial, it == "1") }
                restoreSetting("user_rotation") { DeviceActions.setRotation(serial, landscape = it == "1") }
            }
            DeviceRecipe.ACCESSIBILITY -> {
                apply += DeviceActions.setFontScale(serial, 1.5f)
                apply += DeviceActions.setDarkMode(serial, true)
                apply += DeviceActions.setTalkBack(serial, true)
                restoreSetting("font_scale") { DeviceActions.setFontScale(serial, it.toFloatOrNull() ?: 1.0f) }
                restoreSetting("night_mode") { DeviceActions.setDarkMode(serial, wasNight(it)) }
                restoreSetting("enabled_accessibility_services", blankIsAValue = true) {
                    // Restoring the exact prior service list, not "off" — the user may run TalkBack, or
                    // a switch-access service, and turning it off would be a change, not a revert.
                    DeviceActions.setTalkBack(serial, it.contains("talkback", ignoreCase = true))
                }
            }
        }
        // Revert in reverse application order, so a setting that depends on another is restored after it.
        return RecipePlan(recipe, apply, revert.reversed(), unreadable)
    }

    /** `cmd uimode night` prints `Night mode: yes|no|auto`; `settings get` prints a raw value. */
    private fun wasNight(value: String): Boolean =
        value.contains("yes", ignoreCase = true) || value.trim() == "2"

    /**
     * A human sentence for the confirmation card — what will change, and the promise that it can be put
     * back (or the warning that it can't).
     */
    fun describe(plan: RecipePlan): String {
        val what = "${plan.recipe.label}: ${plan.recipe.description}."
        return if (plan.isRevertible) {
            "$what The current values were captured, so this can be undone."
        } else {
            "$what The current value of ${plan.unreadable.joinToString(", ")} could not be read, " +
                "so this could not be undone reliably."
        }
    }
}
