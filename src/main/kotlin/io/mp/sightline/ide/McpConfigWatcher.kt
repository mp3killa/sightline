package io.mp.sightline.ide

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import io.mp.sightline.mcp.DeclaredServer
import io.mp.sightline.mcp.McpConfigReader
import java.io.File

/**
 * Notices when the MCP servers declared for this project change on disk.
 *
 * The platform half of live MCP sync, and deliberately the *only* part that touches the filesystem —
 * everything that decides anything lives in [McpConfigReader] and friends, where it is unit-tested.
 *
 * **Why polling a stamp rather than a VFS listener.** `~/.claude.json` is outside the project, so the
 * IDE does not watch it; adding a root watch to observe one file the CLI rewrites on almost every
 * interaction is a lot of machinery for a `File.lastModified()`. The stamp check is two stats, and the
 * file is only read and parsed when the stamp actually moves — which matters, because that file is
 * ~64KB and is rewritten for reasons that have nothing to do with MCP (start counts, tips, caches).
 *
 * **Never call [pollDeclared] on the EDT.** It stats and may parse two files; the caller runs it on a
 * pooled thread and comes back to the EDT with the answer.
 */
@Service(Service.Level.PROJECT)
class McpConfigWatcher(private val project: Project) {

    private data class Stamp(val userModified: Long, val userSize: Long, val projectModified: Long, val projectSize: Long)

    private var lastStamp: Stamp? = null

    /** `~/.claude.json` — where `claude mcp add` writes, for both user and per-project scope. */
    private val userConfig: File get() = File(System.getProperty("user.home"), ".claude.json")

    /** The project's own checked-in `.mcp.json`, if it has one. */
    private val projectConfig: File? get() = project.basePath?.let { File(it, ".mcp.json") }

    /**
     * The declared servers, but **only when something changed** since the last poll.
     *
     * Returns null for "nothing new to say", which covers both an unchanged stamp and a file that could
     * not be parsed. Those are different situations and only one is worth a log line, but neither is
     * something to act on: acting on an unreadable config would mean treating a torn read of a file
     * another process is mid-write on as a declaration that servers had been removed.
     */
    fun pollDeclared(): List<DeclaredServer>? {
        val stamp = currentStamp()
        if (stamp == lastStamp) return null
        lastStamp = stamp

        val userText = userConfig.readTextOrNull()
        val projectText = projectConfig?.readTextOrNull()
        val keys = projectKeys()

        val user = McpConfigReader.readUserConfig(userText, keys)
        val disabled = McpConfigReader.disabledProjectServers(userText, keys)
        val fromProject = McpConfigReader.readProjectConfig(projectText, disabled)

        if (user == null && userText != null) {
            // Worth saying once per change: a config this plugin cannot read is a config whose servers
            // it will never offer to load, and silence would make that look like the feature is dead.
            thisLogger().info("Sightline: ~/.claude.json could not be parsed; MCP sync is standing by")
            return null
        }
        if (fromProject == null) {
            thisLogger().info("Sightline: .mcp.json could not be parsed; its servers are not being considered")
        }
        return McpConfigReader.merge(user, fromProject)
    }

    /** Forces the next [pollDeclared] to re-read, e.g. after a relaunch reset the managed set. */
    fun invalidate() {
        lastStamp = null
    }

    /**
     * The keys this project might be filed under in `~/.claude.json`'s `projects` map.
     *
     * The CLI keys by its own working directory string. That is the same path Sightline launches it
     * with, but a path can still reach us in more than one spelling — a symlinked checkout, or macOS's
     * `/private` prefix on temp locations — and matching only one would report "no local servers" for a
     * project that has them. Cheap to try several; wrong to guess one.
     */
    private fun projectKeys(): List<String> {
        val base = project.basePath ?: return emptyList()
        val f = File(base)
        val canonical = try { f.canonicalPath } catch (_: Exception) { null }
        return listOfNotNull(base, canonical, base.trimEnd('/'), base.removeSuffix("/")).distinct()
    }

    private fun currentStamp(): Stamp {
        val u = userConfig
        val p = projectConfig
        return Stamp(
            userModified = u.lastModifiedOrZero(),
            userSize = u.lengthOrZero(),
            projectModified = p?.lastModifiedOrZero() ?: 0L,
            projectSize = p?.lengthOrZero() ?: 0L,
        )
    }

    private fun File.lastModifiedOrZero(): Long = try { if (exists()) lastModified() else 0L } catch (_: Exception) { 0L }
    private fun File.lengthOrZero(): Long = try { if (exists()) length() else 0L } catch (_: Exception) { 0L }

    private fun File.readTextOrNull(): String? = try {
        if (exists() && isFile) readText() else null
    } catch (e: Exception) {
        thisLogger().debug("Sightline: could not read $name", e)
        null
    }
}
