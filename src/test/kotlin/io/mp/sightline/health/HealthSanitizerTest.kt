package io.mp.sightline.health

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sanitiser guards a copy-to-public-tracker path, so these lean paranoid: the invariant under test is
 * almost always "the secret does NOT survive", checked with a raw substring so a clever partial redaction
 * can't pass. Uses literal secrets rather than the live user's real name/home (which the code reads from
 * system properties) so the assertions are stable on any machine.
 */
class HealthSanitizerTest {

    private fun clean(s: String) = HealthSanitizer.sanitizeText(s)

    @Test fun stripsAuthorizationHeaderValues() {
        val out = clean("""headers: {"x-claude-code-ide-authorization":"a1b2c3d4e5f6a7b8"}""")
        assertFalse(out.contains("a1b2c3d4e5f6a7b8"))
        assertTrue("keeps the key so the line still means something", out.contains("authorization"))
    }

    @Test fun stripsAuthTokenAssignments() {
        assertFalse(clean("""{"authToken":"deadBEEFdeadBEEF1234"}""").contains("deadBEEFdeadBEEF1234"))
        assertFalse(clean("token=9f8e7d6c5b4a3f2e1d0c").contains("9f8e7d6c5b4a3f2e1d0c"))
        assertFalse(clean("api_key: MY-SECRET-VALUE-123456").contains("MY-SECRET-VALUE-123456"))
    }

    @Test fun stripsBearerAndBasicCredentials() {
        assertFalse(clean("Authorization: Bearer abcDEF123456ghiJKL").contains("abcDEF123456ghiJKL"))
        assertFalse(clean("Proxy: Basic dXNlcjpwYXNzd29yZA==").contains("dXNlcjpwYXNzd29yZA"))
    }

    @Test fun stripsProviderStyleKeys() {
        assertFalse(clean("using sk-ant-api03-abcdefghijklmnopqrstuvwx").contains("abcdefghijklmnopqrstuvwx"))
        assertFalse(clean("token ghp_16CharsMinimumHere00").contains("ghp_16CharsMinimumHere00"))
    }

    @Test fun stripsLongHexBlobs() {
        val hash = "0123456789abcdef0123456789abcdef"
        assertFalse(clean("session $hash").contains(hash))
    }

    @Test fun keepsShortHexThatIsProbablyAVersionOrCount() {
        // A 24+ run is token-shaped; a short hex-ish value (a build number, a colour) must survive.
        assertTrue(clean("build 261 / abc123").contains("abc123"))
        assertTrue(clean("Everything checks out").contains("Everything checks out"))
    }

    @Test fun redactsEmailsAndIps() {
        assertFalse(clean("git user someone@example.com").contains("someone@example.com"))
        assertFalse(clean("connected to 192.168.1.42").contains("192.168.1.42"))
    }

    @Test fun collapsesForeignUserPathsToTheirTail() {
        val out = clean("found at /Users/someoneelse/.local/bin/claude")
        assertFalse("the other user's name must go", out.contains("someoneelse"))
        assertTrue("but the useful tail survives", out.contains("claude"))
    }

    @Test fun redactsTheCurrentUsersHomeAndName() {
        // These come from system properties, so build the input from them to prove the live user is scrubbed.
        val home = System.getProperty("user.home")
        val name = System.getProperty("user.name")
        if (!home.isNullOrBlank()) {
            val out = clean("path is $home/Documents/project")
            assertFalse(out.contains(home))
            assertTrue(out.contains("~"))
        }
        if (!name.isNullOrBlank() && name.length >= 3) {
            assertFalse(clean("hello $name there").contains(Regex("\\b${Regex.escape(name)}\\b").pattern.let { name }))
        }
    }

    @Test fun leavesBenignDiagnosticTextAlone() {
        val benign = "Claude CLI: Found. Permission mode: auto. 12 events observed."
        assertTrue(clean(benign).contains("Permission mode: auto"))
        assertTrue(clean(benign).contains("12 events observed"))
    }

    @Test fun fullReportTextIsSanitisedEndToEnd() {
        val report = HealthReport(
            listOf(
                HealthCheck("cli", "Claude CLI", HealthStatus.OK, "Found at /Users/realperson/.local/bin/claude"),
                HealthCheck(
                    "bridge", "IDE integration", HealthStatus.FAIL,
                    """authToken":"cafebabecafebabe9999" not listening""",
                    hint = "email me at dev@secret.org",
                ),
            ),
        )
        val text = HealthSanitizer.toReportText(report)
        assertFalse(text.contains("realperson"))
        assertFalse(text.contains("cafebabecafebabe9999"))
        assertFalse(text.contains("dev@secret.org"))
        // Structure is preserved: statuses and names still present.
        assertTrue(text.contains("Claude CLI"))
        assertTrue(text.contains("[FAIL]"))
        assertTrue("the actionable hint's arrow survives", text.contains("→"))
    }

    @Test fun sanitizingIsIdempotent() {
        val once = clean("""token=abcdef0123456789abcdef /Users/someone/x""")
        assertTrue(clean(once) == once)
    }
}
