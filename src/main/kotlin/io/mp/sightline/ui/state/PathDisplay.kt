package io.mp.sightline.ui.state

/**
 * Turns an absolute file path into something a transcript row can actually be read from. Platform-free
 * and deterministic so it is unit-tested; the panel supplies the project and home directories.
 *
 * Two things make a raw path unreadable in a narrow tool window. The first is that nearly all of it is
 * *shared* — every file Claude touches in one project repeats the same
 * `/Users/…/MyApplication/app/src/main/java/com/…/` prefix, so the part that identifies the file gets
 * crowded out by the part that never varies. [relativize] and [homeRelative] drop the prefix the reader
 * already knows. The second is that cutting to a character budget lands mid-segment and produces text
 * like `…s/devuser/AndroidStudioProjects/…`, which reads as damage rather than as abbreviation.
 * [elide] therefore cuts only at separators, and never cuts the filename.
 */
object PathDisplay {

    /** Character budget for a transcript row's path summary. */
    const val DEFAULT_MAX = 72

    private const val ELLIPSIS = "…"

    /**
     * The path as written relative to [basePath] when it lies inside the project, else unchanged. An
     * in-project path is the common case and the one where the prefix carries no information at all.
     */
    fun relativize(path: String, basePath: String?): String {
        val base = basePath?.trimEnd('/').orEmpty()
        if (base.isEmpty()) return path
        return if (path.startsWith("$base/")) path.removePrefix("$base/") else path
    }

    /** `/Users/someone/notes.txt` → `~/notes.txt`, for paths outside the project. */
    fun homeRelative(path: String, home: String?): String {
        val h = home?.trimEnd('/').orEmpty()
        if (h.isEmpty()) return path
        return if (path.startsWith("$h/")) "~/" + path.removePrefix("$h/") else path
    }

    /**
     * Shortens [path] to at most [max] characters by dropping whole leading segments, so what survives
     * is always a real path suffix. The filename is never cut — it is the part being identified — unless
     * it alone exceeds the budget, in which case it is elided *inside itself*, keeping the head that
     * names it and the tail that carries the extension.
     */
    fun elide(path: String, max: Int = DEFAULT_MAX): String {
        if (path.length <= max) return path
        if (max <= ELLIPSIS.length) return ELLIPSIS

        val segments = path.split('/')
        val name = segments.last()
        // "…/" costs two characters on top of whatever we keep.
        if (name.length + ELLIPSIS.length + 1 > max) return elideWithin(name, max)

        var first = segments.size - 1
        var kept = name.length
        while (first > 0) {
            val next = segments[first - 1]
            // The empty leading segment of an absolute path is not worth a line of text.
            if (next.isEmpty()) break
            val grown = kept + next.length + 1
            if (grown + ELLIPSIS.length + 1 > max) break
            kept = grown
            first--
        }
        return ELLIPSIS + "/" + segments.subList(first, segments.size).joinToString("/")
    }

    /** Both ends of a filename that cannot fit whole; the middle is what identifies it least. */
    private fun elideWithin(name: String, max: Int): String {
        val room = max - ELLIPSIS.length
        val head = (room + 1) / 2
        return name.take(head) + ELLIPSIS + name.takeLast(room - head)
    }

    /** [relativize] or [homeRelative], then [elide] — what a tool row should show for a file path. */
    fun display(path: String?, basePath: String?, home: String? = null, max: Int = DEFAULT_MAX): String {
        if (path.isNullOrEmpty()) return ""
        val relative = relativize(path, basePath)
        return elide(if (relative != path) relative else homeRelative(path, home), max)
    }
}
