package io.mp.sightline.vcs

/**
 * Platform-free, unit-tested extraction of a project's **stated commit-message style** from one doc file.
 * The point is to honour what a project already says about its commits — a "Commit messages" section in
 * CONTRIBUTING, a `.gitmessage` template, a commitlint config — rather than inventing a house style.
 *
 * It returns only genuinely commit-relevant text (a section under a heading that mentions "commit", the
 * template body, or a commitlint note), never a whole document, and never a guess when there's nothing
 * to find.
 */
object CommitStyleExtractor {

    const val MAX_SNIPPET = 1500

    /** Commit guidance found in [fileName]/[content], or null if it says nothing about commits. */
    fun extract(fileName: String, content: String): String? {
        val name = fileName.lowercase()
        return when {
            name == ".gitmessage" || name == ".gitmessage.txt" -> gitmessage(content)
            name.startsWith(".commitlintrc") || name.startsWith("commitlint.config.") -> COMMITLINT
            name.endsWith(".md") || name == "contributing" -> commitSection(content)?.take(MAX_SNIPPET)
            else -> null
        }
    }

    private const val COMMITLINT =
        "This project uses commitlint — follow Conventional Commits: `type(optional scope): subject` " +
            "(feat, fix, docs, refactor, test, chore, …), imperative subject, no trailing period."

    /** A `.gitmessage` template, minus its comment lines (git strips `#` lines); null if only comments. */
    private fun gitmessage(content: String): String? {
        val body = content.lines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n").trim()
        return if (body.isEmpty()) null else "Commit template (.gitmessage):\n$body".take(MAX_SNIPPET)
    }

    /** The first markdown section whose heading mentions "commit", heading included, up to the next
     *  heading of the same or higher level. */
    private fun commitSection(md: String): String? {
        val lines = md.lines()
        var i = 0
        while (i < lines.size) {
            val h = HEADING.matchEntire(lines[i])
            if (h != null && h.groupValues[2].contains("commit", ignoreCase = true)) {
                val level = h.groupValues[1].length
                val body = StringBuilder(lines[i].trim())
                var j = i + 1
                while (j < lines.size) {
                    val next = HEADING.matchEntire(lines[j])
                    if (next != null && next.groupValues[1].length <= level) break
                    body.append('\n').append(lines[j])
                    j++
                }
                val text = body.toString().trim()
                if (text.length > lines[i].trim().length) return text // heading + real body, not a bare heading
            }
            i++
        }
        return null
    }

    private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
}
