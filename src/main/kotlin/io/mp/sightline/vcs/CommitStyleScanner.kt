package io.mp.sightline.vcs

import java.io.File

/**
 * Reads a project's stated commit-message style from a small, fixed set of well-known doc locations and
 * returns the first match ([CommitStyleExtractor] does the per-file extraction). "First" follows the
 * candidate order below — the places a project is most likely to *state* a house style come first — so
 * one clear source wins rather than stitching together conflicting guidance.
 *
 * Plain `java.io` reads under the project base dir (call off the EDT). Size-capped; returns "" when the
 * project says nothing about commits, in which case the generator falls back to its default style.
 */
object CommitStyleScanner {

    private val CANDIDATES = listOf(
        "CONTRIBUTING.md", "CONTRIBUTING", ".github/CONTRIBUTING.md", "docs/CONTRIBUTING.md",
        "docs/commit-messages.md", ".github/COMMIT_CONVENTION.md",
        "CLAUDE.md", "AGENTS.md",
        ".gitmessage", ".gitmessage.txt",
        ".commitlintrc", ".commitlintrc.json", ".commitlintrc.yml", ".commitlintrc.yaml",
        ".commitlintrc.js", "commitlint.config.js", "commitlint.config.mjs", "commitlint.config.ts",
    )

    private const val MAX_FILE_BYTES = 256_000L

    fun scan(baseDir: File?): String {
        if (baseDir == null || !baseDir.isDirectory) return ""
        for (rel in CANDIDATES) {
            val f = File(baseDir, rel)
            if (!f.isFile || f.length() > MAX_FILE_BYTES) continue
            val content = try { f.readText() } catch (e: Exception) { continue }
            CommitStyleExtractor.extract(f.name, content)?.let { return it }
        }
        return ""
    }
}
