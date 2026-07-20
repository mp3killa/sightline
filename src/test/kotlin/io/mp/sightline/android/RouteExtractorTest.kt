package io.mp.sightline.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteExtractorTest {

    private val navHost = """
        @Composable
        fun AppNavHost(navController: NavHostController) {
            NavHost(navController, startDestination = "routes") {
                composable("routes") {
                    RoutesScreen(onRoute = { id -> navController.navigate("delivery/${'$'}id") })
                }
                composable(
                    route = "delivery/{orderId}",
                    deepLinks = listOf(navDeepLink { uriPattern = "demo://delivery/{orderId}" }),
                ) {
                    DeliveryScreen()
                }
                composable("earnings/statements") {
                    StatementScreen()
                }
                composable("orphan") {
                    OrphanScreen()
                }
            }
        }
    """.trimIndent()

    private val analysis = RouteExtractor.analyze(navHost)

    @Test
    fun `routes are found with their lines`() {
        val routes = analysis.routes.map { it.route }
        assertTrue(routes.contains("routes"))
        assertTrue(routes.contains("delivery/{orderId}"))
        assertTrue(routes.contains("earnings/statements"))
        assertTrue(analysis.routes.all { it.line > 0 })
    }

    @Test
    fun `both the positional and named route forms parse`() {
        assertTrue(analysis.routes.any { it.route == "routes" })
        assertTrue(analysis.routes.any { it.route == "delivery/{orderId}" })
    }

    @Test
    fun `route arguments are extracted`() {
        val delivery = analysis.routes.first { it.route.startsWith("delivery") }
        assertEquals(listOf("orderId"), delivery.arguments)
        assertTrue(delivery.isParameterised)
    }

    @Test
    fun `deep links are found on their route`() {
        val delivery = analysis.routes.first { it.route.startsWith("delivery") }
        assertTrue(delivery.deepLinks.contains("demo://delivery/{orderId}"))
    }

    @Test
    fun `the start destination is read`() {
        assertEquals("routes", analysis.startDestination)
    }

    @Test
    fun `the destination composable is identified where the source names one`() {
        assertEquals("StatementScreen", analysis.routes.first { it.route == "earnings/statements" }.destination)
    }

    // ---- the checks that catch real runtime crashes ----

    /**
     * Compose routes are strings, so a renamed route and a stale navigate() compile cleanly and crash at
     * runtime. That is the entire reason this class exists.
     */
    @Test
    fun `a navigate call with no declared route is dangling`() {
        val broken = navHost + """
            fun goStale(nav: NavController) { nav.navigate("earnings/old-statements") }
        """.trimIndent()
        val dangling = RouteExtractor.analyze(broken).danglingCalls()
        assertTrue(dangling.any { it.route.startsWith("earnings/old") })
    }

    @Test
    fun `an unreferenced route is reported unreachable`() {
        assertTrue(analysis.unreachable().any { it.route == "orphan" })
    }

    /** The start destination is reached by being the start, not by a call. */
    @Test
    fun `the start destination is never called unreachable`() {
        assertTrue(analysis.unreachable().none { it.route == "routes" })
    }

    @Test
    fun `a route reached by a parameterised navigate is reachable`() {
        assertTrue(analysis.unreachable().none { it.route.startsWith("delivery") })
    }

    @Test
    fun `duplicate deep links across destinations are reported`() {
        val duplicated = """
            composable("a", deepLinks = listOf(navDeepLink { uriPattern = "demo://x" })) { A() }
            composable("b", deepLinks = listOf(navDeepLink { uriPattern = "demo://x" })) { B() }
        """.trimIndent()
        val dupes = RouteExtractor.analyze(duplicated).duplicateDeepLinks()
        assertEquals(1, dupes.size)
        assertEquals(2, dupes.values.single().size)
    }

    // ---- launching ----

    @Test
    fun `arguments substitute into a launchable route`() {
        val delivery = analysis.routes.first { it.route.startsWith("delivery") }
        assertEquals("delivery/ORD-12345", delivery.withArguments(mapOf("orderId" to "ORD-12345")))
    }

    @Test
    fun `an unsupplied argument stays as its placeholder rather than becoming blank`() {
        val delivery = analysis.routes.first { it.route.startsWith("delivery") }
        assertEquals("delivery/{orderId}", delivery.withArguments(emptyMap()))
    }

    @Test
    fun `a deep link becomes a runnable adb action`() {
        val action = RouteExtractor.deepLinkCommand("demo://delivery/ORD-12345", "emulator-5554", "com.example.driver")
        assertTrue(action.args.contains("demo://delivery/ORD-12345"))
        assertTrue(action.args.containsAll(listOf("-p", "com.example.driver")))
        assertTrue("launching a deep link is reversible", !action.needsConfirmation)
    }

    // ---- honest limits ----

    /**
     * A route built by concatenation isn't resolved and isn't guessed at — a false "unreachable" trains
     * people to ignore the check, which costs more than the finding is worth.
     */
    @Test
    fun `a non-literal route is not invented`() {
        val computed = """
            composable(Routes.DELIVERY) { DeliveryScreen() }
            composable("prefix" + suffix) { Other() }
        """.trimIndent()
        assertTrue(RouteExtractor.analyze(computed).routes.isEmpty())
    }

    @Test
    fun `commented-out routes are ignored`() {
        val src = """
            composable("live") { Live() }
            // composable("removed") { Removed() }
        """.trimIndent()
        assertEquals(listOf("live"), RouteExtractor.analyze(src).routes.map { it.route })
    }

    @Test
    fun `a file with no navigation analyses to nothing`() {
        val a = RouteExtractor.analyze("class RouteViewModel { fun load() {} }")
        assertTrue(a.routes.isEmpty())
        assertTrue(a.navigateCalls.isEmpty())
        assertTrue(a.unreachable().isEmpty())
    }
}
