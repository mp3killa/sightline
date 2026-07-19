package io.mp.claudecodepanel.ide

import java.io.File
import java.net.URI

/** How a requested filesystem path relates to the open project — drives the ide bridge's guard. */
enum class PathAccess { INSIDE_PROJECT, OUTSIDE_PROJECT, SENSITIVE }

/**
 * Platform-free policy deciding whether the `ide` bridge may open/diff/write a path. It canonicalises
 * the request (stripping `file://`, resolving `.`/`..` and — where the target exists — symlinks) and
 * classifies it against the project content roots and a denylist of sensitive locations (SSH/cloud
 * credentials, IDE config, shell dotfiles, `.env`, key material). No IntelliJ imports, so it's covered
 * by plain JUnit tests. The caller decides what each class means (inside → follow permission mode;
 * outside → confirm; sensitive → refuse).
 *
 * Paths are compared with `/` separators (the plugin targets macOS/Linux Android Studio).
 */
class PathAccessPolicy(
    projectRoots: List<String>,
    userHome: String? = System.getProperty("user.home"),
) {
    private val roots: List<String> = projectRoots.map { canonicalize(it) }.filter { it.isNotEmpty() }
    private val home: String = canonicalize(userHome.orEmpty())

    /** Directory subtrees under $HOME that must never be touched by default. */
    private val sensitiveHomeSubdirs = listOf(
        ".ssh", ".aws", ".gnupg", ".gcloud", ".config/gcloud", ".kube", ".docker", ".claude",
        ".config/git", ".password-store", ".keychain",
    )

    /** File names that are sensitive wherever they live. */
    private val sensitiveNames = setOf(
        ".netrc", ".git-credentials", "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519",
        "credentials", ".npmrc", ".pypirc",
    )

    fun classify(rawPath: String): PathAccess {
        val path = canonicalize(rawPath)
        if (path.isEmpty()) return PathAccess.SENSITIVE // unresolvable → treat as unsafe
        if (isSensitive(path)) return PathAccess.SENSITIVE
        if (roots.any { isWithin(path, it) }) return PathAccess.INSIDE_PROJECT
        return PathAccess.OUTSIDE_PROJECT
    }

    fun isInsideProject(rawPath: String): Boolean = classify(rawPath) == PathAccess.INSIDE_PROJECT

    private fun isSensitive(path: String): Boolean {
        val name = path.substringAfterLast('/')
        if (name in sensitiveNames) return true
        if (name == ".env" || name.startsWith(".env.")) return true
        // IDE / VCS internals
        if (path.endsWith("/.idea") || path.contains("/.idea/")) return true
        if (path.endsWith("/.git") || path.contains("/.git/")) return true
        if (home.isNotEmpty()) {
            for (sub in sensitiveHomeSubdirs) {
                val full = "$home/$sub"
                if (path == full || isWithin(path, full)) return true
            }
            // A dotfile sitting directly in $HOME (e.g. ~/.zshrc, ~/.gitconfig).
            if (path.substringBeforeLast('/') == home && name.startsWith(".")) return true
        }
        return false
    }

    /** True if [path] is [ancestor] itself or lives beneath it (segment-aware, not a prefix trick). */
    private fun isWithin(path: String, ancestor: String): Boolean =
        ancestor.isNotEmpty() && (path == ancestor || path.startsWith("$ancestor/"))

    private fun canonicalize(raw: String): String {
        val stripped = stripFileUri(raw).trim()
        if (stripped.isEmpty()) return ""
        val f = File(stripped)
        return try {
            f.canonicalPath
        } catch (e: Exception) {
            try { f.absoluteFile.toPath().normalize().toString() } catch (e2: Exception) { "" }
        }
    }

    // Accepts both the IntelliJ `file:///path` (empty authority) and Java's `file:/path` (no authority)
    // forms; falls back to lexical stripping if the URI can't be parsed (e.g. an authority component).
    private fun stripFileUri(raw: String): String =
        if (raw.startsWith("file:")) {
            try { File(URI(raw)).path } catch (e: Exception) { raw.removePrefix("file://").removePrefix("file:") }
        } else raw
}
