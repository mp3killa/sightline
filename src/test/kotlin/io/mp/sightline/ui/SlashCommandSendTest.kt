package io.mp.sightline.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.mp.sightline.android.AndroidContext
import io.mp.sightline.android.DeviceContext
import io.mp.sightline.android.DeviceState
import io.mp.sightline.android.EditorContext
import io.mp.sightline.android.Fact
import io.mp.sightline.android.FactTier
import io.mp.sightline.android.ModuleContext
import io.mp.sightline.ui.state.SlashCommands

/**
 * End-to-end spot check of what actually leaves the composer when a slash command is sent from a
 * project that has Android context chips — the configuration in which every slash command silently
 * stopped being one.
 *
 * Driven through `buildMessageForPreview`, which is the production `ComposerModel.buildMessage` the
 * send path uses, so this fails if the context block ever creeps back in front of a command.
 */
class SlashCommandSendTest : BasePlatformTestCase() {

    private fun androidContext() = AndroidContext(
        modules = listOf(
            ModuleContext(
                name = "app",
                gradlePath = ":app",
                variant = Fact.known("demoStagingDebug", FactTier.BUILD_OUTPUT),
                applicationId = Fact.known("com.example.driver.staging", FactTier.BUILD_OUTPUT),
            ),
        ),
        activeModuleName = "app",
        device = DeviceContext(
            serial = "emulator-5554",
            name = "Pixel 8",
            state = DeviceState.ONLINE,
            apiLevel = Fact.known(35, FactTier.DEVICE),
        ),
        editor = EditorContext("app/src/main/java/com/example/ui/RouteDetailsScreen.kt"),
    )

    fun `test a slash command leaves the composer with nothing in front of it`() {
        val p = ClaudePanel(project, testRootDisposable)
        p.setAndroidContextForPreview(androidContext())

        val sent = p.buildMessageForPreview("/context")
        assertEquals("a slash command must go out alone or it is billed as a prompt", "/context", sent)
        assertTrue(SlashCommands.isCommand(sent))
    }

    fun `test an ordinary prompt still carries the Android context`() {
        val p = ClaudePanel(project, testRootDisposable)
        p.setAndroidContextForPreview(androidContext())

        val sent = p.buildMessageForPreview("why does this crash?")
        assertTrue("the context block is the whole point of the chips: $sent", sent.contains("demoStagingDebug"))
        assertTrue(sent, sent.trimEnd().endsWith("why does this crash?"))
    }
}
