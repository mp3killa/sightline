package io.mp.sightline.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSdkLocatorTest {

    private fun sources(vararg args: Pair<String, String>) = args.toMap()

    @Test
    fun `an explicit setting outranks everything`() {
        val c = AndroidSdkLocator.candidates(
            configured = "/opt/my-sdk",
            env = sources("ANDROID_HOME" to "/env/sdk"),
            localProperties = "sdk.dir=/props/sdk",
            userHome = "/Users/me",
            osName = "Mac OS X",
        )
        assertEquals("/opt/my-sdk", c.first().path)
        assertEquals("configured in Settings", c.first().source)
    }

    @Test
    fun `the environment outranks local properties, which outranks the default`() {
        val c = AndroidSdkLocator.candidates(
            env = sources("ANDROID_HOME" to "/env/sdk"),
            localProperties = "sdk.dir=/props/sdk",
            userHome = "/Users/me",
            osName = "Mac OS X",
        ).map { it.path }
        assertEquals(listOf("/env/sdk", "/props/sdk", "/Users/me/Library/Android/sdk"), c)
    }

    @Test
    fun `ANDROID_HOME outranks the deprecated ANDROID_SDK_ROOT`() {
        val c = AndroidSdkLocator.candidates(
            env = sources("ANDROID_SDK_ROOT" to "/b", "ANDROID_HOME" to "/a"),
            userHome = null,
        ).map { it.path }
        assertEquals(listOf("/a", "/b"), c)
    }

    @Test
    fun `the same path is not offered twice`() {
        val c = AndroidSdkLocator.candidates(
            env = sources("ANDROID_HOME" to "/same", "ANDROID_SDK_ROOT" to "/same"),
            localProperties = "sdk.dir=/same",
            userHome = null,
        )
        assertEquals(1, c.size)
        assertEquals("ANDROID_HOME", c.first().source) // strongest source wins the dedupe
    }

    @Test
    fun `blank and missing sources are skipped`() {
        val c = AndroidSdkLocator.candidates(
            configured = "  ",
            env = sources("ANDROID_HOME" to ""),
            localProperties = null,
            userHome = null,
        )
        assertTrue(c.isEmpty())
    }

    @Test
    fun `trailing slashes are normalised so they don't defeat dedupe`() {
        val c = AndroidSdkLocator.candidates(
            env = sources("ANDROID_HOME" to "/sdk/"),
            localProperties = "sdk.dir=/sdk",
            userHome = null,
        )
        assertEquals(1, c.size)
    }

    // ---- per-OS defaults ----

    @Test
    fun `default locations follow the platform`() {
        fun defaultFor(os: String, env: Map<String, String> = emptyMap()) =
            AndroidSdkLocator.candidates(env = env, userHome = "/Users/me", osName = os).map { it.path }

        assertEquals(listOf("/Users/me/Library/Android/sdk"), defaultFor("Mac OS X"))
        assertEquals(listOf("/Users/me/Android/Sdk"), defaultFor("Linux"))
        assertTrue(defaultFor("Windows 11").contains("/Users/me/AppData/Local/Android/Sdk"))
        assertTrue(
            defaultFor("Windows 11", sources("LOCALAPPDATA" to "C:/Users/me/AppData/Local"))
                .contains("C:/Users/me/AppData/Local/Android/Sdk"),
        )
    }

    // ---- local.properties: the escaping is the whole reason this isn't java.util.Properties ----

    @Test
    fun `sdk dir is read from local properties`() {
        assertEquals("/Users/me/Library/Android/sdk", AndroidSdkLocator.sdkDirFrom("sdk.dir=/Users/me/Library/Android/sdk"))
    }

    @Test
    fun `a Gradle-escaped Windows path is unescaped`() {
        // Exactly what Gradle writes on Windows; a naive read leaves a path that exists nowhere.
        assertEquals("C:/Users/me/AppData/Local/Android/Sdk", AndroidSdkLocator.sdkDirFrom("""sdk.dir=C\:\\Users\\me\\AppData\\Local\\Android\\Sdk"""))
    }

    @Test
    fun `comments and other keys are ignored`() {
        val props = """
            # Auto-generated file. Do not modify.
            ! another comment style
            ndk.dir=/Users/me/ndk
            sdk.dir=/Users/me/sdk
        """.trimIndent()
        assertEquals("/Users/me/sdk", AndroidSdkLocator.sdkDirFrom(props))
    }

    @Test
    fun `a key that merely contains sdk dir is not matched`() {
        assertNull(AndroidSdkLocator.sdkDirFrom("my.sdk.dir=/nope"))
        assertNull(AndroidSdkLocator.sdkDirFrom("sdk.dirs=/nope"))
    }

    @Test
    fun `whitespace around the assignment is tolerated`() {
        assertEquals("/Users/me/sdk", AndroidSdkLocator.sdkDirFrom("  sdk.dir  =  /Users/me/sdk  "))
    }

    @Test
    fun `an empty or absent value yields null`() {
        assertNull(AndroidSdkLocator.sdkDirFrom("sdk.dir="))
        assertNull(AndroidSdkLocator.sdkDirFrom(""))
        assertNull(AndroidSdkLocator.sdkDirFrom("no keys here"))
    }

    // ---- resolve ----

    @Test
    fun `resolve takes the first candidate that exists`() {
        val present = setOf("/b", "/b/platform-tools/adb", "/b/emulator/emulator")
        val sdk = AndroidSdkLocator.resolve(
            listOf(SdkCandidate("/a", "ANDROID_HOME"), SdkCandidate("/b", "local.properties")),
        ) { it in present }
        assertEquals("/b", sdk!!.root)
        assertEquals("local.properties", sdk.source)
        assertEquals("/b/platform-tools/adb", sdk.adb)
        assertEquals("/b/emulator/emulator", sdk.emulator)
        assertTrue(sdk.hasAdb)
    }

    /**
     * The state that matters: an SDK root without platform-tools must resolve, so the Health panel can
     * say "SDK found, adb missing" — a different problem with a different fix from "no SDK".
     */
    @Test
    fun `an SDK without platform-tools still resolves`() {
        val sdk = AndroidSdkLocator.resolve(listOf(SdkCandidate("/sdk", "ANDROID_HOME"))) { it == "/sdk" }
        assertEquals("/sdk", sdk!!.root)
        assertNull(sdk.adb)
        assertNull(sdk.emulator)
        assertFalse(sdk.hasAdb)
    }

    @Test
    fun `no existing candidate resolves to null`() {
        assertNull(AndroidSdkLocator.resolve(listOf(SdkCandidate("/a", "x"))) { false })
        assertNull(AndroidSdkLocator.resolve(emptyList()) { true })
    }
}
