package io.mp.claudecodepanel.android

/** A product flavour as declared, with the dimension it belongs to (null for a single-dimension setup). */
data class DeclaredFlavor(val name: String, val dimension: String?)

/** What a module's build file declares. All nullable — this is a text parse of a program, not a model. */
data class GradleModuleInfo(
    val namespace: String? = null,
    val applicationId: String? = null,
    val minSdk: Int? = null,
    val targetSdk: Int? = null,
    val compileSdk: String? = null,
    val flavorDimensions: List<String> = emptyList(),
    val flavors: List<DeclaredFlavor> = emptyList(),
    val buildTypes: List<String> = emptyList(),
    val usesCompose: Boolean? = null,
    val isApplication: Boolean? = null,
) {
    /**
     * Declared flavours flattened into **dimension order** — the order AGP concatenates them into a
     * variant name, and therefore the order [VariantName.decompose] needs to split one correctly.
     * Flavours with no dimension keep their declaration order at the end.
     */
    val flavorsInDimensionOrder: List<String>
        get() {
            if (flavorDimensions.isEmpty()) return flavors.map { it.name }
            val byDimension = flavorDimensions.flatMap { d -> flavors.filter { it.dimension == d }.map { it.name } }
            val undimensioned = flavors.filter { it.dimension == null || it.dimension !in flavorDimensions }
            return byDimension + undimensioned.map { it.name }
        }
}

/**
 * Reads a module's `build.gradle.kts` / `build.gradle` — the tier-3 rung of the fact ladder.
 *
 * This is a **text parse of a program**, and it is honest about that. A build file can compute its
 * values, read them from a property, or override them per-variant, so nothing here is authoritative;
 * it is the fallback for when the IDE model (tier 1) and a build output (tier 2) both have nothing to
 * say. Callers tag results [FactTier.STATIC_PARSE], which renders as "declared" — deliberately different
 * wording from a resolved value.
 *
 * The rule throughout: **extract only what is unambiguous, and return null otherwise.** A regex that
 * usually works is exactly how a fact ladder gets poisoned at its base, because tier 3 is the rung with
 * nothing below it but UNKNOWN — and UNKNOWN is a better answer than a plausible wrong one.
 */
object GradleModuleParser {

    fun parse(buildFile: String): GradleModuleInfo {
        val text = stripComments(buildFile)
        val android = blockBody(text, "android") ?: ""
        val defaultConfig = blockBody(android, "defaultConfig") ?: ""

        return GradleModuleInfo(
            namespace = assignedString(android, "namespace"),
            applicationId = assignedString(defaultConfig, "applicationId"),
            minSdk = assignedInt(defaultConfig, "minSdk") ?: assignedInt(defaultConfig, "minSdkVersion"),
            targetSdk = assignedInt(defaultConfig, "targetSdk") ?: assignedInt(defaultConfig, "targetSdkVersion"),
            compileSdk = assignedInt(android, "compileSdk")?.toString()
                ?: assignedString(android, "compileSdk")
                ?: assignedString(android, "compileSdkVersion"),
            flavorDimensions = parseFlavorDimensions(android),
            flavors = parseFlavors(android),
            buildTypes = blockBody(android, "buildTypes")?.let { topLevelNames(it) }.orEmpty(),
            usesCompose = parseUsesCompose(text, android),
            isApplication = parseIsApplication(text),
        )
    }

    /**
     * Application or library, from the applied plugins.
     *
     * Has to understand the version-catalogue form as well as the literal id, because
     * `alias(libs.plugins.android.application)` is how a modern Android project declares this and the
     * string `com.android.application` never appears in the module's own build file. Matching only the
     * literal made every catalogue-based project — which is most of them now — report "unknown".
     */
    private fun parseIsApplication(text: String): Boolean? {
        val plugins = blockBody(text, "plugins") ?: text
        return when {
            plugins.contains("com.android.application") -> true
            plugins.contains("com.android.library") -> false
            // `alias(libs.plugins.android.application)`; the alias segments are separated by `.` or `-`
            // depending on how the catalogue names them.
            ALIAS_APPLICATION.containsMatchIn(plugins) -> true
            ALIAS_LIBRARY.containsMatchIn(plugins) -> false
            else -> null
        }
    }

    private val ALIAS_APPLICATION = Regex("""alias\s*\(\s*[\w.]*plugins[.\-][\w.\-]*android[.\-]application""")
    private val ALIAS_LIBRARY = Regex("""alias\s*\(\s*[\w.]*plugins[.\-][\w.\-]*android[.\-]library""")

    /**
     * Gradle paths from `settings.gradle(.kts)` — `include(":app")`, `include ':app', ':core'`, and the
     * multi-argument Kotlin form. Comments are stripped first, so a commented-out module doesn't appear
     * as a real one, and `includeBuild` is excluded because a composite build is not a module here.
     */
    fun parseIncludes(settingsFile: String): List<String> {
        val text = stripComments(settingsFile)
        return Regex("""(?m)^\s*include\s*(?!Build)\(?([^)\n]*)\)?""")
            .findAll(text)
            .flatMap { m -> QUOTED.findAll(m.groupValues[1]).map { it.quotedValue() } }
            .map { it.trim() }
            .filter { it.startsWith(":") && it.length > 1 }
            .distinct()
            .toList()
    }

    /** `:feature:route` → `feature/route`, the directory Gradle maps it to by default. */
    fun gradlePathToDirectory(gradlePath: String): String =
        gradlePath.removePrefix(":").replace(':', '/')

    /** `:feature:route` → `route`; `:app` → `app`. The name a strip should show. */
    fun gradlePathToName(gradlePath: String): String =
        gradlePath.trimEnd(':').substringAfterLast(':').ifEmpty { gradlePath }

    // ---- flavours ----

    /**
     * `flavorDimensions += listOf("brand", "environment")`, `flavorDimensions "brand", "environment"`,
     * and the `=` form. Order is preserved because it is load-bearing — see [GradleModuleInfo].
     */
    private fun parseFlavorDimensions(android: String): List<String> {
        val m = Regex("""flavorDimensions\s*(?:\+=|=)?\s*(?:listOf|setOf)?\s*\(?([^)\n]*)\)?""")
            .find(android) ?: return emptyList()
        return QUOTED.findAll(m.groupValues[1]).map { it.quotedValue() }.filter { it.isNotBlank() }.toList()
    }

    private fun parseFlavors(android: String): List<DeclaredFlavor> {
        val body = blockBody(android, "productFlavors") ?: return emptyList()
        return topLevelNames(body).map { name ->
            val flavorBody = blockBody(body, name) ?: ""
            DeclaredFlavor(name, assignedString(flavorBody, "dimension"))
        }
    }

    /**
     * Compose is on when `buildFeatures { compose = true }` says so. A Compose *dependency* is not the
     * same claim — a module can depend on a Compose library without compiling any Composables — so a
     * dependency alone yields null ("don't know") rather than true.
     */
    private fun parseUsesCompose(text: String, android: String): Boolean? {
        val features = blockBody(android, "buildFeatures")
        if (features != null) {
            if (Regex("""\bcompose\s*(?:=|true)""").containsMatchIn(features)) {
                return !Regex("""\bcompose\s*=\s*false""").containsMatchIn(features)
            }
        }
        // The Compose compiler plugin is applied only by modules that actually compile Composables.
        if (text.contains("org.jetbrains.kotlin.plugin.compose") || text.contains("kotlin(\"plugin.compose\")")) {
            return true
        }
        return null
    }

    // ---- primitives ----

    /** Both quote styles: the Kotlin DSL uses double, and Groovy build files routinely use single. */
    private val QUOTED = Regex(""""([^"\\]*)"|'([^'\\]*)'""")

    private fun MatchResult.quotedValue(): String =
        groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: ""

    /** `name = "value"`, `name "value"`, `name 'value'`, and `name.set("value")`, at a statement start. */
    private fun assignedString(scope: String, property: String): String? {
        val m = Regex(
            """(?m)^\s*(?:\w+\.)?${Regex.escape(property)}\s*(?:=\s*|\.set\(\s*|\s+)(?:"([^"\n]*)"|'([^'\n]*)')""",
        ).find(scope) ?: return null
        return m.quotedValue().takeIf { it.isNotBlank() }
    }

    private fun assignedInt(scope: String, property: String): Int? {
        val m = Regex("""(?m)^\s*(?:\w+\.)?${Regex.escape(property)}\s*(?:=\s*|\.set\(\s*|\s+)(\d+)""")
            .find(scope) ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    /**
     * The body of `name { … }`, brace-matched rather than regexed. A regex cannot find the closing brace
     * of a nested block, and `productFlavors` is always nested — matching to the first `}` would truncate
     * at the first flavour and silently report one flavour where there are five.
     */
    fun blockBody(text: String, name: String): String? {
        val header = Regex("""(?m)^\s*(?:create\(["'])?${Regex.escape(name)}(["']\))?\s*\{""").find(text)
            ?: Regex("""\b${Regex.escape(name)}\s*\{""").find(text)
            ?: return null
        val open = text.indexOf('{', header.range.first)
        if (open < 0) return null

        var depth = 0
        var i = open
        var inString = false
        var quote = ' '
        while (i < text.length) {
            val c = text[i]
            when {
                inString -> {
                    if (c == '\\') i++ else if (c == quote) inString = false
                }
                c == '"' || c == '\'' -> { inString = true; quote = c }
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open + 1, i)
                }
            }
            i++
        }
        return null // unbalanced braces — say nothing rather than return a truncated body
    }

    /**
     * Names of the blocks declared directly inside [body] — `debug { … }`, `create("staging") { … }`,
     * `getByName("release") { … }`, and the bare Groovy `staging { … }`. Nested blocks are skipped by
     * tracking depth, so a flavour's own `dimension`/`applicationIdSuffix` lines never look like flavours.
     */
    fun topLevelNames(body: String): List<String> {
        val names = mutableListOf<String>()
        var depth = 0
        var i = 0
        var lineStart = 0
        var inString = false
        var quote = ' '

        while (i < body.length) {
            val c = body[i]
            when {
                inString -> if (c == '\\') i++ else if (c == quote) inString = false
                c == '"' || c == '\'' -> { inString = true; quote = c }
                c == '\n' -> lineStart = i + 1
                c == '{' -> {
                    if (depth == 0) {
                        val head = body.substring(lineStart, i).trim()
                        declaredName(head)?.let { names += it }
                    }
                    depth++
                }
                c == '}' -> if (depth > 0) depth--
            }
            i++
        }
        return names.distinct()
    }

    private fun declaredName(head: String): String? {
        Regex("""(?:create|getByName|maybeCreate|register)\s*\(\s*(?:"([^"]+)"|'([^']+)')""").find(head)
            ?.let { return it.quotedValue() }
        // A bare `staging {` — the Groovy form and Kotlin's accessor form.
        val bare = Regex("""^(\w+)$""").find(head) ?: return null
        return bare.groupValues[1].takeIf { it !in NOT_A_NAME }
    }

    /** Block heads that are structure, not a declared name. */
    private val NOT_A_NAME = setOf("android", "buildTypes", "productFlavors", "buildFeatures", "dependencies", "else", "try")

    // Strip line and block comments so a commented-out flavour never becomes a real one. String literals
    // are respected, so a "https://…" in the file doesn't eat the rest of the line.
    // (Deliberately a line comment: Kotlin *nests* block comments, so writing the delimiters inside a
    // KDoc opens a nested comment — a gotcha this repo has hit twice. See CLAUDE.md.)
    fun stripComments(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        var inString = false
        var quote = ' '
        while (i < text.length) {
            val c = text[i]
            val next = if (i + 1 < text.length) text[i + 1] else ' '
            when {
                inString -> {
                    sb.append(c)
                    if (c == '\\' && i + 1 < text.length) { sb.append(next); i++ } else if (c == quote) inString = false
                }
                c == '"' || c == '\'' -> { inString = true; quote = c; sb.append(c) }
                c == '/' && next == '/' -> {
                    while (i < text.length && text[i] != '\n') i++
                    continue
                }
                c == '/' && next == '*' -> {
                    i += 2
                    while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i += 2
                    continue
                }
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }
}
