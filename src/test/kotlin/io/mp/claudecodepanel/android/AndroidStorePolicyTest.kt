package io.mp.claudecodepanel.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidStorePolicyTest {

    // ---- names and keys: keys can derive from user data, so they are validated, not trusted ----

    @Test
    fun `plain lowercase segments are safe`() {
        assertTrue(AndroidStorePolicy.isSafeStoreName("flaky-tests"))
        assertTrue(AndroidStorePolicy.isSafeStoreName("baselines"))
        assertTrue(AndroidStorePolicy.isSafeKey("com-example-mainactivitytest"))
    }

    @Test
    fun `traversal and separators are rejected`() {
        for (bad in listOf("..", "../etc", "a/b", "a\\b", "/abs", "a b", ".hidden", "-leading", "", "UPPER")) {
            assertFalse("should reject '$bad'", AndroidStorePolicy.isSafeKey(bad))
            assertFalse("should reject '$bad'", AndroidStorePolicy.isSafeStoreName(bad))
        }
    }

    @Test
    fun `an over-long key is rejected`() {
        assertFalse(AndroidStorePolicy.isSafeKey("a".repeat(65)))
        assertTrue(AndroidStorePolicy.isSafeKey("a".repeat(64)))
    }

    @Test
    fun `a path is built only from safe components`() {
        assertEquals(".sightline/flaky/mytest.json", AndroidStorePolicy.relativePath("flaky", "mytest", "json"))
        assertEquals(".sightline/flaky/mytest.json", AndroidStorePolicy.relativePath("flaky", "mytest", ".json"))
        assertNull(AndroidStorePolicy.relativePath("../etc", "mytest", "json"))
        assertNull(AndroidStorePolicy.relativePath("flaky", "../../etc/passwd", "json"))
        assertNull(AndroidStorePolicy.relativePath("flaky", "mytest", "../sh"))
    }

    // ---- schema versioning: discard, never migrate on a guess ----

    @Test
    fun `the current schema reads`() {
        assertEquals(StoreVerdict.READ, AndroidStorePolicy.accepts(AndroidStorePolicy.SCHEMA_VERSION))
    }

    @Test
    fun `any other version is discarded rather than migrated`() {
        assertEquals(StoreVerdict.DISCARD_VERSION, AndroidStorePolicy.accepts(AndroidStorePolicy.SCHEMA_VERSION + 1))
        assertEquals(StoreVerdict.DISCARD_VERSION, AndroidStorePolicy.accepts(0))
        assertEquals(StoreVerdict.DISCARD_MALFORMED, AndroidStorePolicy.accepts(null))
    }

    // ---- retention ----

    @Test
    fun `retention keeps the newest and drops the rest`() {
        val entries = (1..10).map { it.toLong() }
        assertEquals(listOf(10L, 9L, 8L), AndroidStorePolicy.retain(entries, 3) { it })
    }

    @Test
    fun `an under-cap list is returned untouched`() {
        val entries = listOf(3L, 1L, 2L)
        assertEquals(entries, AndroidStorePolicy.retain(entries, 5) { it })
    }

    @Test
    fun `a zero or negative cap keeps nothing`() {
        assertTrue(AndroidStorePolicy.retain(listOf(1L, 2L), 0) { it }.isEmpty())
        assertTrue(AndroidStorePolicy.retain(listOf(1L, 2L), -1) { it }.isEmpty())
    }

    // ---- the standing-decision rule: workspace-relative paths only ----

    @Test
    fun `a path inside the project becomes relative`() {
        assertEquals(
            "app/src/main/java/Main.kt",
            AndroidStorePolicy.toRelative("/Users/me/proj/app/src/main/java/Main.kt", "/Users/me/proj"),
        )
        assertEquals("", AndroidStorePolicy.toRelative("/Users/me/proj", "/Users/me/proj"))
    }

    /**
     * The rule that matters. An absolute path outside the project leaks the machine's layout — and the
     * username — into a file that outlives the session, so it is refused rather than stored.
     */
    @Test
    fun `a path outside the project is refused, not stored absolute`() {
        assertNull(AndroidStorePolicy.toRelative("/Users/me/other/file.kt", "/Users/me/proj"))
        assertNull(AndroidStorePolicy.toRelative("/Users/me/Library/Android/sdk/adb", "/Users/me/proj"))
        assertNull(AndroidStorePolicy.toRelative("/tmp/scratch", "/Users/me/proj"))
    }

    @Test
    fun `a sibling directory sharing a name prefix is not inside the project`() {
        assertNull(AndroidStorePolicy.toRelative("/Users/me/proj-other/file.kt", "/Users/me/proj"))
    }

    @Test
    fun `a relative result that climbs out is refused`() {
        assertNull(AndroidStorePolicy.toRelative("/Users/me/proj/../secret", "/Users/me/proj"))
    }

    @Test
    fun `trailing slashes and separators are normalised`() {
        assertEquals("app/Main.kt", AndroidStorePolicy.toRelative("/Users/me/proj/app/Main.kt", "/Users/me/proj/"))
        assertEquals("app/Main.kt", AndroidStorePolicy.toRelative("""C:\proj\app\Main.kt""", """C:\proj"""))
    }

    @Test
    fun `a blank root refuses everything`() {
        assertNull(AndroidStorePolicy.toRelative("/Users/me/proj/x", ""))
        assertNull(AndroidStorePolicy.toRelative("", "/Users/me/proj"))
    }

    // ---- the backstop ----

    @Test
    fun `credential-shaped text is refused`() {
        assertTrue(AndroidStorePolicy.looksSensitive("Authorization: Bearer eyJhbGciOi"))
        assertTrue(AndroidStorePolicy.looksSensitive("api_key=abc123"))
        assertTrue(AndroidStorePolicy.looksSensitive("-----BEGIN RSA PRIVATE KEY-----"))
        assertTrue(AndroidStorePolicy.looksSensitive("password: hunter2"))
    }

    @Test
    fun `a home path is refused because it carries a username`() {
        assertTrue(AndroidStorePolicy.looksSensitive("/Users/devuser/proj/app"))
        assertTrue(AndroidStorePolicy.looksSensitive("/home/devuser/proj"))
        assertTrue(AndroidStorePolicy.looksSensitive("""C:\Users\devuser\proj"""))
    }

    @Test
    fun `ordinary cache content is allowed`() {
        assertFalse(AndroidStorePolicy.looksSensitive("app/src/test/java/RouteViewModelTest.kt"))
        assertFalse(AndroidStorePolicy.looksSensitive("demoStagingDebug"))
        assertFalse(AndroidStorePolicy.looksSensitive("emulator-5554"))
    }

    @Test
    fun `an oversized entry is refused on size alone`() {
        assertTrue(AndroidStorePolicy.looksSensitive("a".repeat(4001)))
        assertFalse(AndroidStorePolicy.looksSensitive("a".repeat(100)))
    }
}
