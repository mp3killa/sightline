package io.mp.claudecodepanel.android

/** A navigation destination found in source. */
data class NavRoute(
    val route: String,
    val line: Int,
    /** Argument names declared in the route pattern — `{orderId}` → `orderId`. */
    val arguments: List<String> = emptyList(),
    val deepLinks: List<String> = emptyList(),
    /** The composable/fragment the route renders, where the source names one. */
    val destination: String? = null,
) {
    /** `delivery/{orderId}` → `delivery/ORD-12345` given a value per argument. */
    fun withArguments(values: Map<String, String>): String =
        arguments.fold(route) { acc, arg -> acc.replace("{$arg}", values[arg] ?: "{$arg}") }

    val isParameterised: Boolean get() = arguments.isNotEmpty()
}

/** A route referenced by a `navigate(...)` call. */
data class NavCall(val route: String, val line: Int)

data class RouteAnalysis(
    val routes: List<NavRoute>,
    val navigateCalls: List<NavCall>,
    val startDestination: String? = null,
) {
    /**
     * Declared routes nothing navigates to. Start destinations are excluded — they are reached by being
     * the start, not by a call, and flagging them would make the check noise on every screen.
     */
    fun unreachable(): List<NavRoute> = routes.filter { r ->
        r.route != startDestination && navigateCalls.none { matches(it.route, r.route) }
    }

    /** Navigate calls with no matching declared route — a runtime crash waiting to happen. */
    fun danglingCalls(): List<NavCall> =
        navigateCalls.filter { call -> routes.none { matches(call.route, it.route) } }

    /** Two destinations declaring the same deep link — whichever wins is undefined. */
    fun duplicateDeepLinks(): Map<String, List<NavRoute>> =
        routes.flatMap { r -> r.deepLinks.map { it to r } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }

    /**
     * Does a `navigate(…)` literal reach [declared]?
     *
     * Segment-wise rather than by first segment alone. Comparing only the base would call
     * `navigate("earnings/old-statements")` a match for `earnings/statements` — which is exactly the
     * renamed-route crash this check exists to catch, waved through.
     *
     * A declared `{arg}` segment matches anything, and a call ending in an empty segment is a template
     * prefix (`"delivery/$id"` reaching `delivery/{orderId}`): everything before it must match, and the
     * remainder is whatever the template interpolates.
     */
    private fun matches(call: String, declared: String): Boolean {
        val callParts = call.substringBefore('?').split('/')
        val declaredParts = declared.substringBefore('?').split('/')
        val isPrefix = callParts.lastOrNull()?.isEmpty() == true

        if (isPrefix) {
            val complete = callParts.dropLast(1)
            if (complete.size > declaredParts.size) return false
            return complete.indices.all { segmentMatches(complete[it], declaredParts[it]) }
        }
        if (callParts.size != declaredParts.size) return false
        return callParts.indices.all { segmentMatches(callParts[it], declaredParts[it]) }
    }

    private fun segmentMatches(call: String, declared: String): Boolean =
        declared.startsWith("{") && declared.endsWith("}") || call == declared
}

/**
 * Finds navigation routes in Compose `NavHost` source and in Fragment nav-graph XML.
 *
 * Compose Navigation routes are **strings**, which is exactly why this is worth having and why it is
 * hard: nothing checks them at compile time, so a renamed route and a stale `navigate("…")` compile
 * cleanly and crash at runtime. `activity/NavGraphParser` already handles the XML nav graph; this covers
 * the Compose half, which had nothing.
 *
 * It reads only string literals. A route built by string concatenation or held in a constant elsewhere
 * is not resolved and not guessed at — the reachability checks would produce false "unreachable"
 * findings, and a false positive in a check like this trains people to ignore it.
 */
object RouteExtractor {

    fun analyze(source: String): RouteAnalysis {
        val lines = source.lines()
        return RouteAnalysis(
            routes = findComposableRoutes(lines),
            navigateCalls = findNavigateCalls(lines),
            startDestination = START_DESTINATION.find(source)?.groupValues?.get(1),
        )
    }

    private fun findComposableRoutes(lines: List<String>): List<NavRoute> {
        val out = mutableListOf<NavRoute>()
        for ((i, raw) in lines.withIndex()) {
            if (raw.trimStart().startsWith("//")) continue
            // Matched against the RAW line, not a trimmed copy: the paren index is used to index back
            // into `lines`, and computing it on trimmed text shifted it by the indentation — which
            // started the argument scan mid-token and quietly lost every indented single-line route.
            val open = COMPOSABLE_OPEN.find(raw) ?: continue

            // The argument list, not the line: the multi-line form
            //     composable(
            //         route = "delivery/{orderId}",
            //         deepLinks = …,
            //     ) { … }
            // is completely invisible to a line-by-line scan, and it is the form anything with
            // arguments or deep links is actually written in. Reading to the matching `)` also bounds
            // the search, so a string literal further down the file can't be mistaken for a route.
            val args = argumentList(lines, i, raw.indexOf('(', open.range.first)) ?: continue
            val route = ROUTE_NAMED.find(args)?.groupValues?.get(1)
                ?: FIRST_LITERAL.find(args)?.groupValues?.get(1)
                ?: continue // a computed route — not resolved, and not guessed at

            val window = lines.subList(i, minOf(i + 12, lines.size)).joinToString("\n")
            out += NavRoute(
                route = route,
                line = i + 1,
                arguments = ARGUMENT.findAll(route).map { it.groupValues[1] }.toList(),
                deepLinks = DEEP_LINK.findAll(args + "\n" + window).map { it.groupValues[1] }
                    .filter { it != route }
                    .distinct().toList(),
                destination = DESTINATION_CALL.find(window)?.groupValues?.get(1),
            )
        }
        return out.distinctBy { it.route }
    }

    /**
     * The text between `(` at [openIndex] on line [startLine] and its matching `)`, across lines.
     * Null when the parentheses don't balance within a reasonable window — in which case we say nothing.
     */
    private fun argumentList(lines: List<String>, startLine: Int, openIndex: Int): String? {
        if (openIndex < 0) return null
        val sb = StringBuilder()
        var depth = 0
        var inString = false
        for (li in startLine until minOf(startLine + 20, lines.size)) {
            val text = lines[li]
            val from = if (li == startLine) openIndex else 0
            for (ci in from until text.length) {
                val c = text[ci]
                when {
                    inString -> {
                        if (c == '\\') continue
                        if (c == '"') inString = false
                    }
                    c == '"' -> inString = true
                    c == '(' -> depth++
                    c == ')' -> {
                        depth--
                        if (depth == 0) return sb.toString()
                    }
                }
                if (depth > 0 && !(li == startLine && ci == openIndex)) sb.append(c)
            }
            sb.append('\n')
        }
        return null
    }

    private fun findNavigateCalls(lines: List<String>): List<NavCall> {
        val out = mutableListOf<NavCall>()
        for ((i, raw) in lines.withIndex()) {
            val line = raw.trim()
            if (line.startsWith("//")) continue
            for (m in NAVIGATE_CALL.findAll(line)) {
                out += NavCall(m.groupValues[1], i + 1)
            }
        }
        return out
    }

    /**
     * The `adb` command to launch a route's deep link — what makes a route testable in one click rather
     * than by navigating there by hand.
     */
    fun deepLinkCommand(uri: String, serial: String?, applicationId: String? = null): DeviceAction =
        DeviceActions.deepLink(serial, uri, applicationId)

    private val COMPOSABLE_OPEN = Regex("""\bcomposable\s*(?:<[^>]*>)?\s*\(""")
    private val ROUTE_NAMED = Regex("""\broute\s*=\s*"([^"]+)"""")

    /**
     * The first positional argument, and only when the literal is the **whole** argument — followed by a
     * comma or the end of the list, never by a `+`. `composable("prefix" + suffix)` resolves at runtime
     * to something that is not `prefix`, so capturing the prefix would declare a route that does not
     * exist and mark the real one unreachable. A false "unreachable" trains people to ignore the check.
     */
    private val FIRST_LITERAL = Regex("""^\s*"([^"]+)"\s*(?:,|$)""")
    private val NAVIGATE_CALL = Regex("""navigate\s*\(\s*"([^"]+)"""")
    private val START_DESTINATION = Regex("""startDestination\s*=\s*"([^"]+)"""")
    private val ARGUMENT = Regex("""\{(\w+)}""")
    private val DEEP_LINK = Regex("""(?:uriPattern\s*=\s*)?"([a-z][a-z0-9+.-]*://[^"]+)"""")
    private val DESTINATION_CALL = Regex("""^\s*(\w+Screen|\w+Route|\w+Page)\s*\(""", RegexOption.MULTILINE)
}
