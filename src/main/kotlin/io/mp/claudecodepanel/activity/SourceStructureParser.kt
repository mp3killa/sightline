package io.mp.claudecodepanel.activity

/**
 * Parses the structural header of a Kotlin/Java source file — package, **type** imports, the primary
 * type name, and (if it looks like a test) the production class it targets. Platform-free and
 * unit-tested; the platform-side `ProjectStructureEnricher` resolves these short names to real project
 * files via the IDE index, so an edge is only drawn when the target actually exists in the project.
 *
 * Deliberately conservative: only well-formed `package`/`import`/type-declaration syntax is recognised,
 * wildcard and lowercase (function/property) imports are dropped, so it can't invent relationships.
 * Supertype (extends/implements) parsing is intentionally omitted here — it needs real PSI to be
 * reliable across Kotlin/Java, and a bad hierarchy edge would undermine the trust Phase 2a is about.
 */
object SourceStructureParser {

    data class Parsed(
        val packageName: String?,
        val primaryType: String?,
        val importedTypes: List<String>,  // short type names (last FQN segment)
        val testTargetType: String?,      // production type name if this file looks like a test
    )

    private val PACKAGE = Regex("""(?m)^\s*package\s+([A-Za-z_][\w.]*)""")
    private val IMPORT = Regex("""(?m)^\s*import\s+(?:static\s+)?([A-Za-z_][\w.]*)(\.\*)?(?:\s+as\s+\w+)?\s*;?\s*$""")
    private val TYPE_DECL = Regex("""(?m)^\s*(?:[\w@]+\s+)*(?:class|object|interface|enum(?:\s+class)?)\s+([A-Z]\w*)""")

    fun parse(text: String, path: String): Parsed = parse(text, isKotlin = path.endsWith(".kt") || path.endsWith(".kts"))

    fun parse(text: String, isKotlin: Boolean): Parsed {
        val pkg = PACKAGE.find(text)?.groupValues?.get(1)
        val imports = IMPORT.findAll(text).mapNotNull { m ->
            if (m.groupValues[2] == ".*") return@mapNotNull null // wildcard import: no single target
            val short = m.groupValues[1].substringAfterLast('.')
            // Types only (PascalCase). Kotlin function/property imports are lowercase and don't map to a file.
            short.takeIf { it.isNotEmpty() && it[0].isUpperCase() }
        }.distinct().toList()
        val primary = TYPE_DECL.find(text)?.groupValues?.get(1)
        return Parsed(pkg, primary, imports, primary?.let { testTargetOf(it) })
    }

    /** The production type a test type targets (`FooTest` → `Foo`), or null if not obviously a test. */
    fun testTargetOf(typeName: String): String? {
        for (suffix in listOf("Test", "Tests", "Spec")) {
            if (typeName.length > suffix.length && typeName.endsWith(suffix)) return typeName.removeSuffix(suffix)
        }
        return null
    }
}
