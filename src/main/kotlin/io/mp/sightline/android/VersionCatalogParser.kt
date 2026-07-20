package io.mp.sightline.android

/**
 * Reads the `[versions]` table out of `gradle/libs.versions.toml`.
 *
 * Scope is deliberately just that table. Answering "what version of Compose / AGP / Kotlin is this
 * project on?" is the question that actually comes up, and it is the one a model otherwise burns a file
 * read on every time. The `[libraries]` and `[plugins]` tables are a dependency graph, not a fact about
 * the project, and belong to the dependency work in a later milestone.
 *
 * A hand-rolled subset parser rather than a TOML library: the platform bundles no TOML parser, adding a
 * dependency to read one flat table of string values is disproportionate, and the shapes that appear
 * here in practice are few. Anything it doesn't recognise is skipped, never guessed.
 */
object VersionCatalogParser {

    /** `agp = "8.7.0"` → `agp` to `8.7.0`. Table headers other than `[versions]` are ignored. */
    fun parseVersions(toml: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var inVersions = false

        for (raw in toml.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue

            if (line.startsWith("[")) {
                // `[versions]` only. A dotted or array-of-tables header is a different table.
                inVersions = line.removeSurrounding("[", "]").trim() == "versions"
                continue
            }
            if (!inVersions) continue

            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim().trim('"')
            if (key.isEmpty() || !KEY.matches(key)) continue

            // Only plain string values. A rich version (`{ strictly = "…" }`) is a constraint, not a
            // version, and rendering it as one would misreport what the project resolves to.
            val value = line.substring(eq + 1).trim().substringBefore('#').trim()
            val quoted = STRING.matchEntire(value) ?: continue
            out[key] = quoted.groupValues[1]
        }
        return out
    }

    private val KEY = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    private val STRING = Regex(""""([^"]*)"""")

    /**
     * The handful worth putting in a prompt, in a stable order. A full catalogue can run to eighty
     * entries; that is a file to read on request, not a block to prepend to every message.
     */
    fun highlights(versions: Map<String, String>, limit: Int = 6): Map<String, String> {
        if (versions.isEmpty()) return emptyMap()
        val interesting = listOf("agp", "androidgradleplugin", "kotlin", "compose", "composebom", "ksp", "hilt")
        val picked = LinkedHashMap<String, String>()
        for (want in interesting) {
            versions.entries.firstOrNull { it.key.replace("-", "").replace("_", "").equals(want, true) }
                ?.let { picked[it.key] = it.value }
            if (picked.size >= limit) break
        }
        return picked
    }
}
