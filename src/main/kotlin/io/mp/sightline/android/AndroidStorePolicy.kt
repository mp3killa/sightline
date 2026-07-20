package io.mp.sightline.android

/** What to do with a store file we just opened. */
enum class StoreVerdict {
    /** Schema matches — read it. */
    READ,

    /** Written by a different schema version. Discarded, never migrated on a guess. */
    DISCARD_VERSION,

    /** Unreadable or missing its header. Discarded. */
    DISCARD_MALFORMED,
}

/**
 * The guardrails on the one place Sightline is allowed to write to disk beyond settings — the opt-in
 * Android cache of docs/ANDROID.md §1.3.
 *
 * `CLAUDE.md`'s standing decision was "nothing is persisted except settings", and it specified in advance
 * what any future persistence would have to look like. This is that specification turned into code, so
 * the carve-out stays a decision rather than eroding one feature at a time. The rules, all enforced here:
 *
 *  - **Workspace-relative paths only.** Never an absolute path, never source contents, prompts, or
 *    reasoning. An absolute path in a cache file leaks the user's directory layout — and their username —
 *    into something that outlives the session and may be attached to a bug report.
 *  - **Versioned.** A file from an unknown schema is discarded, not migrated by guesswork.
 *  - **Capped.** Every store has a retention limit and drops oldest-first.
 *  - **Off by default**, one setting per store.
 *
 * Pure, so all of it is unit-tested. The thin IO half lives in `ide/android/AndroidStore`.
 */
object AndroidStorePolicy {

    /** The single directory Sightline may write to inside a project. */
    const val DIR = ".sightline"

    /**
     * Bumped whenever a store's on-disk shape changes. There is deliberately one version for all stores:
     * with no migration path, a shared version means one stale-cache class of bug, not four.
     */
    const val SCHEMA_VERSION = 1

    /** JSON key every store file must carry as its first field. */
    const val VERSION_KEY = "sightlineSchema"

    private val SAFE_SEGMENT = Regex("[a-z0-9][a-z0-9-]{0,63}")

    /** Store names are ours, not user input — but validating them keeps a typo from escaping the dir. */
    fun isSafeStoreName(name: String): Boolean = SAFE_SEGMENT.matches(name)

    /**
     * Keys can derive from user data (a test name, a device serial), so they are validated, not trusted.
     * Rejects traversal, separators, and anything that isn't a plain lowercase segment.
     */
    fun isSafeKey(key: String): Boolean = SAFE_SEGMENT.matches(key)

    /** Project-relative path for a store entry, or null if either component is unsafe. */
    fun relativePath(store: String, key: String, extension: String): String? {
        if (!isSafeStoreName(store) || !isSafeKey(key)) return null
        val ext = extension.removePrefix(".")
        if (!SAFE_SEGMENT.matches(ext)) return null
        return "$DIR/$store/$key.$ext"
    }

    fun accepts(version: Int?): StoreVerdict = when (version) {
        null -> StoreVerdict.DISCARD_MALFORMED
        SCHEMA_VERSION -> StoreVerdict.READ
        else -> StoreVerdict.DISCARD_VERSION
    }

    /**
     * Apply a retention cap, newest kept. Ties break toward whatever came later in the input, so a stable
     * sort of same-stamp entries keeps insertion order rather than shuffling on every write.
     */
    fun <T> retain(entries: List<T>, cap: Int, stampOf: (T) -> Long): List<T> {
        if (cap <= 0) return emptyList()
        if (entries.size <= cap) return entries
        return entries.sortedByDescending(stampOf).take(cap)
    }

    /**
     * Turn an absolute path into something safe to persist, or null if it must not be persisted at all.
     *
     * Null is the common, correct answer for anything outside the project: a path under the user's home,
     * an SDK location, or a temp dir tells a reader about the machine, not the project. Callers record
     * the null as "outside project" rather than substituting the absolute path.
     */
    fun toRelative(absolutePath: String, projectRoot: String): String? {
        val path = normalise(absolutePath)
        val root = normalise(projectRoot).trimEnd('/')
        if (root.isEmpty() || path.isEmpty()) return null
        if (path == root) return ""
        if (!path.startsWith("$root/")) return null
        val rel = path.removePrefix("$root/")
        // A relative path that still climbs out is not relative in any useful sense.
        return if (rel.split('/').any { it == ".." }) null else rel
    }

    /**
     * Does this text carry something no cache should hold? A backstop, not the primary defence — the
     * primary defence is only ever writing fields we chose. Mirrors `health/HealthSanitizer`'s posture:
     * cheap, allow-nothing-sensitive, and it fails toward refusing to write.
     */
    fun looksSensitive(text: String): Boolean {
        if (text.length > 4000) return true // a cache entry this big is not a name or a path
        val lower = text.lowercase()
        return SENSITIVE_MARKERS.any { it in lower } || ABSOLUTE_HOME.containsMatchIn(text)
    }

    private val SENSITIVE_MARKERS = listOf(
        "bearer ", "authorization:", "api_key", "apikey", "secret", "password", "private key",
        "-----begin",
    )

    /** `/Users/<name>` and `/home/<name>` — the two shapes that leak a username. */
    private val ABSOLUTE_HOME = Regex("""(?:/Users/|/home/|[A-Za-z]:\\Users\\)[^/\\\s]+""")

    private fun normalise(raw: String): String = raw.trim().replace('\\', '/').trimEnd('/')
}
