package io.mp.sightline.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidContextFormatterTest {

    private val allChips = ContextChipKind.entries.toSet()

    private fun module(
        name: String = "app",
        variant: Fact<String> = Fact.known("demoStagingDebug", FactTier.IDE),
        applicationId: Fact<String> = Fact.known("com.example.driver.staging", FactTier.BUILD_OUTPUT),
    ) = ModuleContext(
        name = name,
        gradlePath = ":$name",
        variant = variant,
        // Derived from the variant, so they share its tier — and vanish with it when it is unknown.
        // (Building them unconditionally is rejected by Fact's constructor, which is the invariant
        // working: there is no such thing as a flavour list with no provenance.)
        flavors = if (variant.isKnown) Fact.known(listOf("demo", "staging"), variant.tier) else Fact.unknown(),
        buildType = if (variant.isKnown) Fact.known("debug", variant.tier) else Fact.unknown(),
        applicationId = applicationId,
        minSdk = Fact.known(24, FactTier.STATIC_PARSE),
        targetSdk = Fact.known(36, FactTier.STATIC_PARSE),
        compileSdk = Fact.known("36", FactTier.STATIC_PARSE),
        usesCompose = Fact.known(true, FactTier.STATIC_PARSE),
    )

    private fun device(state: DeviceState = DeviceState.ONLINE) = DeviceContext(
        serial = "emulator-5554",
        name = "Pixel 8",
        state = state,
        apiLevel = Fact.known(35, FactTier.DEVICE),
        androidRelease = Fact.known("15", FactTier.DEVICE),
        appRunning = Fact.known(true, FactTier.DEVICE),
    )

    private fun context(
        modules: List<ModuleContext> = listOf(module()),
        device: DeviceContext? = device(),
        editor: EditorContext? = EditorContext("app/src/main/java/RouteDetailsScreen.kt"),
    ) = AndroidContext(modules = modules, activeModuleName = modules.firstOrNull()?.name, device = device, editor = editor)

    // ---- the strip ----

    @Test
    fun `the strip reads as module, device and file`() {
        assertEquals(
            "app · demoStagingDebug  |  Pixel 8 · API 35 · ready  |  RouteDetailsScreen.kt",
            AndroidContextFormatter.strip(context()),
        )
    }

    /** A strip reading "unknown | no device" spends a line telling the user nothing. */
    @Test
    fun `unknown segments are dropped, not padded`() {
        val bare = context(
            modules = listOf(module(variant = Fact.unknown("nothing built yet"))),
            device = null,
            editor = null,
        )
        assertEquals("app", AndroidContextFormatter.strip(bare))
    }

    @Test
    fun `a non-Android project produces no strip at all`() {
        assertEquals("", AndroidContextFormatter.strip(AndroidContext.NOT_ANDROID))
        assertEquals("", AndroidContextFormatter.strip(AndroidContext()))
    }

    // ---- the width budget: drop whole segments, never cut one in half ----

    /**
     * A narrow tool window sheds the file segment rather than clipping the line. Nothing is lost —
     * the file is still on its chip and in the tooltip — and a half-rendered word reads as damage.
     */
    @Test
    fun `a tight budget drops the lowest-priority segment whole`() {
        val full = AndroidContextFormatter.strip(context())
        assertEquals(
            "app · demoStagingDebug  |  Pixel 8 · API 35 · ready",
            AndroidContextFormatter.strip(context(), full.length - 1),
        )
    }

    @Test
    fun `a tighter budget keeps dropping, module last`() {
        assertEquals("app · demoStagingDebug", AndroidContextFormatter.strip(context(), 30))
    }

    /** The last segment is kept even when it doesn't fit: a truncated module name is worse than a clip. */
    @Test
    fun `the final segment is never cut in half`() {
        assertEquals("app · demoStagingDebug", AndroidContextFormatter.strip(context(), 1))
        assertEquals("app · demoStagingDebug", AndroidContextFormatter.strip(context(), 0))
    }

    @Test
    fun `a generous budget changes nothing`() {
        assertEquals(AndroidContextFormatter.strip(context()), AndroidContextFormatter.strip(context(), 500))
    }

    /** The budget is presentation only — it must never reach what gets sent. */
    @Test
    fun `the prompt block is unaffected by the strip budget`() {
        val block = AndroidContextFormatter.promptBlock(context(), allChips)
        assertTrue(block.contains("Open file: app/src/main/java/RouteDetailsScreen.kt"))
        assertTrue(block.contains("Device: Pixel 8"))
    }

    @Test
    fun `the tooltip spells out provenance the strip omits`() {
        val tip = AndroidContextFormatter.stripTooltip(
            context(modules = listOf(module(variant = Fact.known("demoStagingDebug", FactTier.BUILD_OUTPUT)))),
        )
        assertTrue(tip.contains("demoStagingDebug (last build)"))
        assertTrue(tip.contains("emulator-5554"))
    }

    // ---- the prompt block ----

    @Test
    fun `the prompt block carries the facts with their sources`() {
        val block = AndroidContextFormatter.promptBlock(context(), allChips)
        assertTrue(block.startsWith("<android-context>"))
        assertTrue(block.trimEnd().endsWith("</android-context>"))
        assertTrue(block.contains("Module: app (:app)"))
        assertTrue(block.contains("Build variant: demoStagingDebug"))
        assertTrue(block.contains("Product flavours: demo, staging"))
        assertTrue(block.contains("SDK: min 24, target 36, compile 36"))
        assertTrue(block.contains("UI toolkit: Jetpack Compose"))
        assertTrue(block.contains("Device: Pixel 8 (emulator-5554)"))
        assertTrue(block.contains("API 35"))
        assertTrue(block.contains("app running"))
        assertTrue(block.contains("Open file: app/src/main/java/RouteDetailsScreen.kt"))
    }

    /**
     * The whole point of the ladder reaching the prompt: a stale variant must *say* it is stale, or the
     * model reasons confidently about a build the user isn't on.
     */
    @Test
    fun `a stale variant is labelled in the prompt`() {
        val stale = context(modules = listOf(module(variant = Fact.known("demoStagingDebug", FactTier.BUILD_OUTPUT))))
        val block = AndroidContextFormatter.promptBlock(stale, allChips)
        assertTrue(block.contains("Build variant: demoStagingDebug (last build)"))
    }

    @Test
    fun `an IDE-sourced variant carries no qualifier`() {
        val block = AndroidContextFormatter.promptBlock(context(), allChips)
        assertTrue(block.contains("Build variant: demoStagingDebug\n"))
    }

    /** An applicationId parsed from defaultConfig hasn't had the flavour suffix applied — say so. */
    @Test
    fun `a declared applicationId warns that the suffix is missing`() {
        val declared = context(
            modules = listOf(
                module(
                    applicationId = Fact.known(
                        "com.example.driver", FactTier.STATIC_PARSE, "defaultConfig, before any flavour suffix",
                    ),
                ),
            ),
        )
        val block = AndroidContextFormatter.promptBlock(declared, allChips)
        assertTrue(block.contains("before any flavour suffix"))
    }

    // ---- the chips genuinely control the payload ----

    @Test
    fun `removing the device chip removes the device from the prompt`() {
        val without = AndroidContextFormatter.promptBlock(context(), allChips - ContextChipKind.DEVICE)
        assertFalse(without.contains("Device:"))
        assertTrue(without.contains("Build variant:"))
    }

    @Test
    fun `removing the variant chip removes variant and version, not the module`() {
        val without = AndroidContextFormatter.promptBlock(context(), allChips - ContextChipKind.VARIANT)
        assertFalse(without.contains("Build variant:"))
        assertFalse(without.contains("Product flavours:"))
        assertTrue(without.contains("Module: app"))
    }

    @Test
    fun `no chips means no block`() {
        assertEquals("", AndroidContextFormatter.promptBlock(context(), emptySet()))
    }

    @Test
    fun `a non-Android project contributes no block`() {
        assertEquals("", AndroidContextFormatter.promptBlock(AndroidContext.NOT_ANDROID, allChips))
    }

    /** "No device" is a fact worth sending — it explains why a run request is about to fail. */
    @Test
    fun `an absent device is stated rather than omitted`() {
        val block = AndroidContextFormatter.promptBlock(context(device = null), allChips)
        assertTrue(block.contains("Device: none connected"))
    }

    @Test
    fun `other modules are named so the block is not mistaken for the whole project`() {
        val multi = context(modules = listOf(module("app"), module("core"), module("wear")))
        val block = AndroidContextFormatter.promptBlock(multi, allChips)
        assertTrue(block.contains("Other modules: core, wear"))
        assertTrue(block.contains("not every module"))
    }

    @Test
    fun `the footer warns that stale values need re-checking`() {
        val block = AndroidContextFormatter.promptBlock(context(), allChips)
        assertTrue(block.contains("may be stale"))
        assertTrue(block.contains("you don't need to run adb"))
    }

    @Test
    fun `a context block is detectable, so it is never injected twice`() {
        assertTrue(AndroidContextFormatter.containsContextBlock(AndroidContextFormatter.promptBlock(context(), allChips)))
        assertFalse(AndroidContextFormatter.containsContextBlock("just a message"))
    }

    // ---- chip availability ----

    @Test
    fun `only chips that would carry something are offered`() {
        val available = AndroidContextFormatter.availableChips(context())
        assertTrue(ContextChipKind.MODULE in available)
        assertTrue(ContextChipKind.VARIANT in available)
        assertTrue(ContextChipKind.DEVICE in available)
        assertTrue(ContextChipKind.CURRENT_FILE in available)
        // Nothing selected and nothing changed — offering these would make the row a menu of maybes.
        assertFalse(ContextChipKind.SELECTION in available)
        assertFalse(ContextChipKind.RECENT_CHANGES in available)
    }

    @Test
    fun `a selection makes the selection chip available`() {
        val withSelection = context(editor = EditorContext("app/Main.kt", selectionLines = 10..24))
        assertTrue(ContextChipKind.SELECTION in AndroidContextFormatter.availableChips(withSelection))
    }

    @Test
    fun `an unknown variant offers no variant chip`() {
        val noVariant = context(modules = listOf(module(variant = Fact.unknown("nothing built yet"))))
        assertFalse(ContextChipKind.VARIANT in AndroidContextFormatter.availableChips(noVariant))
    }

    @Test
    fun `a non-Android project offers no chips`() {
        assertTrue(AndroidContextFormatter.availableChips(AndroidContext.NOT_ANDROID).isEmpty())
    }

    /** A chip shows its value; the category alone would tell the user nothing they couldn't guess. */
    @Test
    fun `chips are labelled with the value, not the category`() {
        val c = context()
        assertEquals("app", AndroidContextFormatter.chipLabel(ContextChipKind.MODULE, c))
        assertEquals("demoStagingDebug", AndroidContextFormatter.chipLabel(ContextChipKind.VARIANT, c))
        assertEquals("Pixel 8", AndroidContextFormatter.chipLabel(ContextChipKind.DEVICE, c))
        assertEquals("RouteDetailsScreen.kt", AndroidContextFormatter.chipLabel(ContextChipKind.CURRENT_FILE, c))
        assertEquals("No device", AndroidContextFormatter.chipLabel(ContextChipKind.DEVICE, context(device = null)))
    }

    @Test
    fun `a chip tooltip says how to remove it`() {
        val tip = AndroidContextFormatter.chipTooltip(ContextChipKind.VARIANT, context())
        assertTrue(tip.contains("Remove to leave it out"))
    }

    @Test
    fun `chip ids round-trip so the Swing layer can key on them`() {
        for (kind in ContextChipKind.entries) {
            assertEquals(kind, ContextChipKind.byId(kind.id))
        }
        assertEquals(null, ContextChipKind.byId("nope"))
    }

    /** Bulky or private facts should not go out until asked for. */
    @Test
    fun `selection and recent changes are off by default`() {
        assertFalse(ContextChipKind.SELECTION in ContextChipKind.DEFAULT_ENABLED)
        assertFalse(ContextChipKind.RECENT_CHANGES in ContextChipKind.DEFAULT_ENABLED)
        assertTrue(ContextChipKind.VARIANT in ContextChipKind.DEFAULT_ENABLED)
        assertTrue(ContextChipKind.DEVICE in ContextChipKind.DEFAULT_ENABLED)
    }
}
