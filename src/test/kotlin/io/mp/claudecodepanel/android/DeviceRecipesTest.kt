package io.mp.claudecodepanel.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The M3 gate: a recipe applied and then reverted must leave the device on the values it started with.
 */
class DeviceRecipesTest {

    private val serial = "emulator-5554"

    private fun state(vararg pairs: Pair<String, String?>) = pairs.toMap()

    private fun argsOf(actions: List<DeviceAction>) = actions.map { it.args.joinToString(" ") }

    // ---- the revert contract ----

    /**
     * The rule that matters. Resetting font scale to 1.0 is not a revert if the user runs at 1.15 —
     * it is a different change, silently applied to a device they will use all day.
     */
    @Test
    fun `revert restores the captured value, not a default`() {
        val plan = DeviceRecipes.plan(DeviceRecipe.LARGE_FONT, serial, state("font_scale" to "1.15"))
        assertTrue(plan.isRevertible)
        assertTrue(argsOf(plan.apply).single().endsWith("font_scale 1.5"))
        assertTrue(
            "should restore 1.15, got ${argsOf(plan.revert)}",
            argsOf(plan.revert).single().endsWith("font_scale 1.15"),
        )
    }

    @Test
    fun `a device already in the target state still reverts to that state`() {
        val plan = DeviceRecipes.plan(DeviceRecipe.LARGE_FONT, serial, state("font_scale" to "1.5"))
        assertTrue(argsOf(plan.revert).single().endsWith("font_scale 1.5"))
    }

    /**
     * If the prior value couldn't be read, the change cannot be undone — so the plan says so and the
     * caller must refuse. Failing to apply costs a test run; failing to restore reconfigures a device.
     */
    @Test
    fun `an unreadable setting makes the plan not revertible`() {
        val plan = DeviceRecipes.plan(DeviceRecipe.LARGE_FONT, serial, state("font_scale" to null))
        assertFalse(plan.isRevertible)
        assertEquals(listOf("font_scale"), plan.unreadable)
        assertTrue(DeviceRecipes.describe(plan).contains("could not be undone"))
    }

    @Test
    fun `settings get returning the literal null string counts as unreadable`() {
        val plan = DeviceRecipes.plan(DeviceRecipe.LARGE_FONT, serial, state("font_scale" to "null"))
        assertFalse(plan.isRevertible)
    }

    @Test
    fun `a missing probe result counts as unreadable`() {
        val plan = DeviceRecipes.plan(DeviceRecipe.LARGE_FONT, serial, emptyMap())
        assertFalse(plan.isRevertible)
    }

    @Test
    fun `a revertible plan says so`() {
        val plan = DeviceRecipes.plan(DeviceRecipe.LARGE_FONT, serial, state("font_scale" to "1.0"))
        assertTrue(DeviceRecipes.describe(plan).contains("can be undone"))
    }

    // ---- per-recipe ----

    @Test
    fun `dark mode restores the prior night setting`() {
        val wasOn = DeviceRecipes.plan(DeviceRecipe.DARK_MODE, serial, state("night_mode" to "Night mode: yes"))
        assertTrue(argsOf(wasOn.revert).single().endsWith("night yes"))

        val wasOff = DeviceRecipes.plan(DeviceRecipe.DARK_MODE, serial, state("night_mode" to "Night mode: no"))
        assertTrue(argsOf(wasOff.revert).single().endsWith("night no"))
    }

    /** Auto-rotate must go off first, or the device rotates straight back and the test is meaningless. */
    @Test
    fun `landscape disables auto-rotate before rotating`() {
        val plan = DeviceRecipes.plan(
            DeviceRecipe.LANDSCAPE, serial,
            state("user_rotation" to "0", "accelerometer_rotation" to "1"),
        )
        val applied = argsOf(plan.apply)
        assertTrue(applied[0].contains("accelerometer_rotation 0"))
        assertTrue(applied[1].contains("user_rotation 1"))
    }

    /** Revert runs in reverse application order, so a dependent setting is restored after its dependency. */
    @Test
    fun `revert order is the reverse of apply order`() {
        val plan = DeviceRecipes.plan(
            DeviceRecipe.LANDSCAPE, serial,
            state("user_rotation" to "0", "accelerometer_rotation" to "1"),
        )
        val reverted = argsOf(plan.revert)
        assertTrue(reverted[0].contains("user_rotation 0"))
        assertTrue(reverted[1].contains("accelerometer_rotation 1"))
    }

    @Test
    fun `the accessibility recipe applies all three conditions`() {
        val plan = DeviceRecipes.plan(
            DeviceRecipe.ACCESSIBILITY, serial,
            state(
                "font_scale" to "1.0",
                "night_mode" to "Night mode: no",
                "enabled_accessibility_services" to "",
            ),
        )
        val applied = argsOf(plan.apply)
        assertTrue(applied.any { it.contains("font_scale 1.5") })
        assertTrue(applied.any { it.contains("night yes") })
        assertTrue(applied.any { it.contains("enabled_accessibility_services") })
    }

    /**
     * The user may already run an accessibility service. Turning it *off* on revert would be a change,
     * not a restoration — so the prior value decides.
     */
    @Test
    fun `accessibility revert respects a pre-existing screen reader`() {
        val hadTalkBack = DeviceRecipes.plan(
            DeviceRecipe.ACCESSIBILITY, serial,
            state(
                "font_scale" to "1.0",
                "night_mode" to "Night mode: no",
                "enabled_accessibility_services" to
                    "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService",
            ),
        )
        assertTrue(
            "TalkBack was already on; revert must leave it on",
            argsOf(hadTalkBack.revert).any { it.contains("talkback") },
        )
    }

    @Test
    fun `an empty accessibility service list reverts to empty`() {
        val plan = DeviceRecipes.plan(
            DeviceRecipe.ACCESSIBILITY, serial,
            state("font_scale" to "1.0", "night_mode" to "Night mode: no", "enabled_accessibility_services" to "none"),
        )
        assertTrue(plan.isRevertible)
        assertFalse(argsOf(plan.revert).any { it.endsWith("TalkBackService") })
    }

    // ---- round trip ----

    /**
     * The gate, stated directly: apply then revert leaves every touched setting on its original value.
     * Simulated over the argv, which is what would actually run.
     */
    @Test
    fun `apply then revert returns every touched setting to its original value`() {
        val original = mapOf(
            "font_scale" to "1.15",
            "night_mode" to "Night mode: no",
            "enabled_accessibility_services" to "",
            "user_rotation" to "0",
            "accelerometer_rotation" to "1",
        )
        for (recipe in DeviceRecipe.entries) {
            val plan = DeviceRecipes.plan(recipe, serial, original)
            assertTrue("$recipe should be revertible from a full state read", plan.isRevertible)

            // Every setting the recipe touches must appear in the revert commands.
            for (setting in DeviceRecipes.touchedSettings(recipe)) {
                val mentioned = argsOf(plan.revert).any { cmd ->
                    cmd.contains(setting) ||
                        (setting == "night_mode" && cmd.contains("night")) ||
                        (setting == "enabled_accessibility_services" && cmd.contains("accessibility"))
                }
                assertTrue("$recipe never restores '$setting': ${argsOf(plan.revert)}", mentioned)
            }
        }
    }

    @Test
    fun `no recipe requires a destructive confirmation`() {
        val full = mapOf(
            "font_scale" to "1.0", "night_mode" to "Night mode: no",
            "enabled_accessibility_services" to "", "user_rotation" to "0", "accelerometer_rotation" to "1",
        )
        for (recipe in DeviceRecipe.entries) {
            assertFalse(
                "$recipe should be reversible, not destructive",
                DeviceRecipes.plan(recipe, serial, full).needsConfirmation,
            )
        }
    }
}
