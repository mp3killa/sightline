package io.mp.sightline.activity

/**
 * Parses the structural header of a Kotlin/Java source file — package, the primary type name, and (if
 * it looks like a test) the production class it targets. Platform-free and unit-tested. Imports and the
 * class hierarchy are resolved from **real PSI** in `ProjectStructureEnricher` (precise + package-aware);
 * this parser handles only the name-based facts that need no resolution.
 *
 * Deliberately conservative: only well-formed `package`/type-declaration syntax is recognised, so it
 * can't invent relationships.
 */
object SourceStructureParser {

    data class Parsed(
        val packageName: String?,
        val primaryType: String?,
        val testTargetType: String?,  // production type name if this file looks like a test
    )

    private val PACKAGE = Regex("""(?m)^\s*package\s+([A-Za-z_][\w.]*)""")
    private val TYPE_DECL = Regex("""(?m)^\s*(?:[\w@]+\s+)*(?:class|object|interface|enum(?:\s+class)?)\s+([A-Z]\w*)""")

    fun parse(text: String, path: String): Parsed = parse(text, isKotlin = path.endsWith(".kt") || path.endsWith(".kts"))

    @Suppress("UNUSED_PARAMETER")
    fun parse(text: String, isKotlin: Boolean): Parsed {
        val pkg = PACKAGE.find(text)?.groupValues?.get(1)
        val primary = TYPE_DECL.find(text)?.groupValues?.get(1)
        return Parsed(pkg, primary, primary?.let { testTargetOf(it) })
    }

    /** The production type a test type targets (`FooTest` → `Foo`), or null if not obviously a test. */
    fun testTargetOf(typeName: String): String? {
        for (suffix in listOf("Test", "Tests", "Spec")) {
            if (typeName.length > suffix.length && typeName.endsWith(suffix)) return typeName.removeSuffix(suffix)
        }
        return null
    }
}
