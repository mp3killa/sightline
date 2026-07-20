package io.mp.claudecodepanel.ui.markdown

/**
 * Maps a fence's info string (```` ```kotlin ````) to the file extension whose IDE file type should
 * highlight it. Platform-free so it is unit-tested; [CodeHighlighting] does the actual lexing.
 *
 * Deliberately a fixed table rather than "guess an extension from the tag": handing an arbitrary word to
 * the file-type registry would occasionally pick a wildly wrong language and colour the block confidently
 * wrong. An unknown tag returns null and the block renders as plain monospace, which is honest.
 */
object CodeLanguages {

    private val EXTENSIONS: Map<String, String> = mapOf(
        "kotlin" to "kt", "kt" to "kt", "kts" to "kts",
        "java" to "java",
        "groovy" to "groovy", "gradle" to "gradle",
        "json" to "json", "json5" to "json",
        "xml" to "xml", "svg" to "svg",
        "html" to "html", "htm" to "html",
        "css" to "css", "scss" to "scss",
        "javascript" to "js", "js" to "js", "mjs" to "js", "jsx" to "jsx",
        "typescript" to "ts", "ts" to "ts", "tsx" to "tsx",
        "python" to "py", "py" to "py",
        "ruby" to "rb", "rb" to "rb",
        "go" to "go", "golang" to "go",
        "rust" to "rs", "rs" to "rs",
        "sql" to "sql",
        "yaml" to "yaml", "yml" to "yaml",
        "toml" to "toml",
        "properties" to "properties",
        "shell" to "sh", "sh" to "sh", "bash" to "sh", "zsh" to "sh", "console" to "sh",
        "markdown" to "md", "md" to "md",
        "c" to "c", "h" to "c",
        "cpp" to "cpp", "c++" to "cpp", "cxx" to "cpp",
        "csharp" to "cs", "cs" to "cs",
        "php" to "php",
        "swift" to "swift",
        "diff" to "diff", "patch" to "diff",
    )

    /** Fence info can carry extras (`kotlin title=Foo.kt`); only the first word names the language. */
    fun normalize(info: String?): String? {
        val first = info?.trim()?.split(' ', '\t', ',', ';')?.firstOrNull()?.lowercase()
        return if (first.isNullOrEmpty()) null else first
    }

    /** The extension to look the IDE file type up by, or null when the tag is unknown/absent. */
    fun extensionFor(info: String?): String? = normalize(info)?.let { EXTENSIONS[it] }
}
