package io.mp.sightline.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeSourceAnalyzerTest {

    private val screen = """
        package com.example.ui

        import androidx.compose.runtime.Composable

        @Composable
        fun RouteDetailsScreen(viewModel: RouteViewModel, modifier: Modifier = Modifier) {
            val state by viewModel.state.collectAsState()
            RouteDetailsContent(state)
        }

        @Composable
        private fun RouteRow(route: Route) {
            Text(route.name)
        }

        @Preview(showBackground = true)
        @Composable
        fun RouteDetailsScreenPreview() {
            RouteDetailsScreen(previewViewModel())
        }
    """.trimIndent()

    @Test
    fun `composables are found with their lines and parameters`() {
        val a = ComposeSourceAnalyzer.analyze(screen)
        val names = a.composables.map { it.name }
        assertTrue(names.contains("RouteDetailsScreen"))
        assertTrue(names.contains("RouteRow"))
        assertTrue(a.isCompose)

        val fn = a.composables.first { it.name == "RouteDetailsScreen" }
        assertEquals(2, fn.parameters.size)
        assertTrue(fn.line > 0)
    }

    /** A preview function is itself @Composable; listing it as one would double-count. */
    @Test
    fun `a preview function is not also listed as a composable`() {
        val a = ComposeSourceAnalyzer.analyze(screen)
        assertFalse(a.composables.any { it.name == "RouteDetailsScreenPreview" })
        assertEquals(1, a.previews.size)
    }

    @Test
    fun `a composable with a matching preview is marked as previewed`() {
        val a = ComposeSourceAnalyzer.analyze(screen)
        assertTrue(a.composables.first { it.name == "RouteDetailsScreen" }.hasPreview)
    }

    @Test
    fun `a non-compose file analyses to nothing`() {
        val a = ComposeSourceAnalyzer.analyze("class RouteViewModel { fun load() {} }")
        assertFalse(a.isCompose)
        assertTrue(a.findings.isEmpty())
    }

    // ---- preview coverage ----

    @Test
    fun `preview coverage is read from the annotation arguments`() {
        val src = """
            @Preview(uiMode = Configuration.UI_MODE_NIGHT_YES, fontScale = 1.5f, device = "id:pixel_8")
            @Composable
            fun ThoroughPreview() {}
        """.trimIndent()
        val p = ComposeSourceAnalyzer.analyze(src).previews.single()
        assertTrue(p.coversDarkMode)
        assertTrue(p.coversFontScale)
        assertTrue(p.coversDeviceSize)
    }

    /** Stacked @Preview annotations are how variants are usually declared; their coverage must merge. */
    @Test
    fun `stacked previews merge their coverage`() {
        val src = """
            @Preview(name = "light")
            @Preview(name = "dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
            @Preview(name = "big", fontScale = 1.5f)
            @Composable
            fun StackedPreview() {}
        """.trimIndent()
        val previews = ComposeSourceAnalyzer.analyze(src).previews
        assertEquals("stacked annotations are one preview function", 1, previews.size)
        assertTrue(previews.single().coversDarkMode)
        assertTrue(previews.single().coversFontScale)
    }

    // ---- findings ----

    /** Flagging every leaf composable would bury the one that matters under thirty that don't. */
    @Test
    fun `only screen-level composables are flagged for a missing preview`() {
        val src = """
            @Composable
            fun StatementScreen(state: State) {}

            @Composable
            fun StatementRow(item: Item) {}
        """.trimIndent()
        val missing = ComposeSourceAnalyzer.analyze(src).findings
            .filter { it.kind == ComposeFinding.Kind.MISSING_PREVIEW }
        assertEquals(1, missing.size)
        assertEquals("StatementScreen", missing.single().symbol)
    }

    @Test
    fun `a preview covering only one theme is flagged`() {
        val findings = ComposeSourceAnalyzer.analyze(screen).findings
        assertTrue(findings.any { it.kind == ComposeFinding.Kind.PREVIEW_MISSING_DARK })
        assertTrue(findings.any { it.kind == ComposeFinding.Kind.PREVIEW_MISSING_FONT_SCALE })
    }

    @Test
    fun `a thorough preview is not flagged`() {
        val src = """
            @Preview(uiMode = Configuration.UI_MODE_NIGHT_YES, fontScale = 1.5f)
            @Composable
            fun GoodPreview() {}
        """.trimIndent()
        val findings = ComposeSourceAnalyzer.analyze(src).findings
        assertFalse(findings.any { it.kind == ComposeFinding.Kind.PREVIEW_MISSING_DARK })
        assertFalse(findings.any { it.kind == ComposeFinding.Kind.PREVIEW_MISSING_FONT_SCALE })
    }

    @Test
    fun `mutableStateOf without remember is flagged with its line`() {
        val src = """
            @Composable
            fun Counter() {
                val count = mutableStateOf(0)
                Text(count.value.toString())
            }
        """.trimIndent()
        val f = ComposeSourceAnalyzer.analyze(src).findings
            .single { it.kind == ComposeFinding.Kind.STATE_WITHOUT_REMEMBER }
        assertEquals(3, f.line)
    }

    @Test
    fun `the remembered form is not flagged`() {
        val src = """
            @Composable
            fun Counter() {
                val count = remember { mutableStateOf(0) }
            }
        """.trimIndent()
        assertTrue(
            ComposeSourceAnalyzer.analyze(src).findings
                .none { it.kind == ComposeFinding.Kind.STATE_WITHOUT_REMEMBER },
        )
    }

    @Test
    fun `an image with no content description is flagged`() {
        val src = """
            @Composable
            fun Avatar() {
                Image(painter = painterResource(R.drawable.avatar))
            }
        """.trimIndent()
        assertTrue(
            ComposeSourceAnalyzer.analyze(src).findings
                .any { it.kind == ComposeFinding.Kind.MISSING_CONTENT_DESCRIPTION },
        )
    }

    @Test
    fun `an explicit null content description is accepted as deliberate`() {
        val src = """
            @Composable
            fun Decoration() {
                Image(painter = painterResource(R.drawable.x), contentDescription = null)
            }
        """.trimIndent()
        assertTrue(
            ComposeSourceAnalyzer.analyze(src).findings
                .none { it.kind == ComposeFinding.Kind.MISSING_CONTENT_DESCRIPTION },
        )
    }

    /**
     * A multi-line call may supply the argument further down; accusing it of omitting one would be a
     * false positive on a very common formatting style.
     */
    @Test
    fun `a multi-line image call is not accused`() {
        val src = """
            @Composable
            fun Avatar() {
                Image(
                    painter = painterResource(R.drawable.avatar),
                    contentDescription = "User avatar",
                )
            }
        """.trimIndent()
        assertTrue(
            ComposeSourceAnalyzer.analyze(src).findings
                .none { it.kind == ComposeFinding.Kind.MISSING_CONTENT_DESCRIPTION },
        )
    }

    @Test
    fun `commented-out code is not flagged`() {
        val src = """
            @Composable
            fun Counter() {
                // val count = mutableStateOf(0)
            }
        """.trimIndent()
        assertTrue(ComposeSourceAnalyzer.analyze(src).findings.isEmpty())
    }

    /**
     * The boundary that matters: recomposition counts and parameter stability are not decidable from
     * source text, so nothing here claims them. A regex approximating those is exactly the
     * confidently-wrong output this codebase avoids.
     */
    @Test
    fun `no finding claims anything about recomposition or stability`() {
        val kinds = ComposeFinding.Kind.entries.map { it.name }
        assertFalse(kinds.any { it.contains("RECOMPOS") || it.contains("STABIL") })
    }

    @Test
    fun `every finding carries a line so it can be opened`() {
        assertTrue(ComposeSourceAnalyzer.analyze(screen).findings.all { it.line > 0 })
    }
}
