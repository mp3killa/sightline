package io.mp.sightline.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GradleModuleParserTest {

    /** Modelled on the real sample-android-app app module: two flavour dimensions, five flavours. */
    private val realish = """
        plugins {
            alias(libs.plugins.android.application)
            alias(libs.plugins.kotlin.android)
            id("org.jetbrains.kotlin.plugin.compose")
        }

        android {
            namespace = "com.example.compose"
            compileSdk = 36

            defaultConfig {
                applicationId = "com.example.driver"
                minSdk = 24
                targetSdk = 36
                versionCode = 1
                versionName = "1.0"
            }

            buildTypes {
                release {
                    isMinifyEnabled = true
                }
                debug {
                    applicationIdSuffix = ".debug"
                }
            }

            flavorDimensions += listOf("brand", "environment")

            productFlavors {
                create("demo") { dimension = "brand" }
                create("waltons") {
                    dimension = "brand"
                    applicationIdSuffix = ".waltons"
                }
                create("bash") {
                    dimension = "brand"
                    applicationIdSuffix = ".bash"
                }
                create("prod") { dimension = "environment" }
                create("staging") {
                    dimension = "environment"
                    applicationIdSuffix = ".staging"
                }
            }

            buildFeatures {
                compose = true
            }
        }
    """.trimIndent()

    @Test
    fun `the core declarations parse`() {
        val info = GradleModuleParser.parse(realish)
        assertEquals("com.example.compose", info.namespace)
        assertEquals("com.example.driver", info.applicationId)
        assertEquals(24, info.minSdk)
        assertEquals(36, info.targetSdk)
        assertEquals("36", info.compileSdk)
        assertEquals(true, info.usesCompose)
        assertEquals(true, info.isApplication)
    }

    @Test
    fun `flavour dimensions keep their declared order`() {
        assertEquals(listOf("brand", "environment"), GradleModuleParser.parse(realish).flavorDimensions)
    }

    /**
     * The one that matters most: a regex matching to the first `}` would stop at the first flavour and
     * silently report one where there are five.
     */
    @Test
    fun `every flavour is found, with its dimension`() {
        val flavors = GradleModuleParser.parse(realish).flavors
        assertEquals(5, flavors.size)
        assertEquals("brand", flavors.first { it.name == "demo" }.dimension)
        assertEquals("environment", flavors.first { it.name == "staging" }.dimension)
    }

    /**
     * Dimension order is what makes VariantName.decompose correct — brands before environments, so
     * `demoStagingDebug` splits the way AGP concatenated it.
     */
    @Test
    fun `flavours flatten into dimension order`() {
        val ordered = GradleModuleParser.parse(realish).flavorsInDimensionOrder
        assertEquals(listOf("demo", "waltons", "bash", "prod", "staging"), ordered)

        // And that order actually decomposes the real variant name.
        val parts = VariantName.decompose("demoStagingDebug", ordered)
        assertEquals(listOf("demo", "staging"), parts.flavors)
        assertEquals("debug", parts.buildType)
    }

    @Test
    fun `build types are found`() {
        assertEquals(setOf("release", "debug"), GradleModuleParser.parse(realish).buildTypes.toSet())
    }

    /** A flavour's own `dimension = "brand"` line must not look like a nested block declaration. */
    @Test
    fun `flavour properties are not mistaken for flavours`() {
        val names = GradleModuleParser.parse(realish).flavors.map { it.name }
        assertTrue(names.none { it == "dimension" || it == "applicationIdSuffix" })
    }

    // ---- Groovy and alternative syntaxes ----

    @Test
    fun `the Groovy DSL parses`() {
        val groovy = """
            android {
                namespace 'com.example.app'
                compileSdkVersion 34
                defaultConfig {
                    applicationId "com.example.app"
                    minSdkVersion 21
                    targetSdkVersion 34
                }
                flavorDimensions "environment"
                productFlavors {
                    dev { dimension "environment" }
                    prod { dimension "environment" }
                }
            }
        """.trimIndent()
        val info = GradleModuleParser.parse(groovy)
        assertEquals("com.example.app", info.namespace)
        assertEquals("com.example.app", info.applicationId)
        assertEquals(21, info.minSdk)
        assertEquals(34, info.targetSdk)
        assertEquals(listOf("environment"), info.flavorDimensions)
        assertEquals(listOf("dev", "prod"), info.flavors.map { it.name })
    }

    @Test
    fun `getByName and the equals form of flavorDimensions parse`() {
        val src = """
            android {
                flavorDimensions = listOf("tier")
                productFlavors {
                    getByName("free") { dimension = "tier" }
                    register("paid") { dimension = "tier" }
                }
            }
        """.trimIndent()
        val info = GradleModuleParser.parse(src)
        assertEquals(listOf("tier"), info.flavorDimensions)
        assertEquals(listOf("free", "paid"), info.flavors.map { it.name })
    }

    // ---- honest failure ----

    @Test
    fun `a library module is not an application`() {
        val src = """
            plugins { id("com.android.library") }
            android { namespace = "com.example.core" }
        """.trimIndent()
        val info = GradleModuleParser.parse(src)
        assertEquals(false, info.isApplication)
        assertNull(info.applicationId)
    }

    @Test
    fun `a non-Android build file yields nothing rather than guesses`() {
        val info = GradleModuleParser.parse("plugins { id(\"java-library\") }\ndependencies { }")
        assertNull(info.namespace)
        assertNull(info.minSdk)
        assertNull(info.isApplication)
        assertTrue(info.flavors.isEmpty())
    }

    @Test
    fun `an empty or malformed file does not throw`() {
        assertEquals(GradleModuleInfo(), GradleModuleParser.parse(""))
        // Unbalanced braces: say nothing rather than return a truncated body.
        assertTrue(GradleModuleParser.parse("android { productFlavors { create(\"x\") {").flavors.isEmpty())
    }

    /** A commented-out flavour is not a flavour. */
    @Test
    fun `comments are stripped before parsing`() {
        val src = """
            android {
                // namespace = "com.wrong.one"
                namespace = "com.right.one"
                productFlavors {
                    create("live") { dimension = "env" }
                    // create("deleted") { dimension = "env" }
                }
            }
        """.trimIndent()
        val info = GradleModuleParser.parse(src)
        assertEquals("com.right.one", info.namespace)
        assertEquals(listOf("live"), info.flavors.map { it.name })
    }

    @Test
    fun `a url in a string is not treated as a comment`() {
        val src = """android { namespace = "com.example" }
            |repositories { maven { url = uri("https://example.com/repo") } }
        """.trimMargin()
        assertEquals("com.example", GradleModuleParser.parse(src).namespace)
    }

    /** Depending on Compose is not the same claim as compiling Composables. */
    @Test
    fun `compose is only asserted when actually enabled`() {
        assertEquals(
            true,
            GradleModuleParser.parse("android { buildFeatures { compose = true } }").usesCompose,
        )
        assertEquals(
            false,
            GradleModuleParser.parse("android { buildFeatures { compose = false } }").usesCompose,
        )
        assertNull(
            GradleModuleParser.parse("dependencies { implementation(\"androidx.compose.ui:ui\") }").usesCompose,
        )
    }

    // ---- settings.gradle ----

    @Test
    fun `includes parse from both DSLs`() {
        assertEquals(
            listOf(":app", ":core", ":wear"),
            GradleModuleParser.parseIncludes(
                """
                rootProject.name = "sample-android-app"
                include(":app")
                include(":core")
                include(":wear")
                """.trimIndent(),
            ),
        )
        assertEquals(
            listOf(":app", ":core"),
            GradleModuleParser.parseIncludes("include ':app', ':core'"),
        )
        assertEquals(
            listOf(":app", ":feature:route"),
            GradleModuleParser.parseIncludes("""include(":app", ":feature:route")"""),
        )
    }

    /** A composite build is not a module of this project. */
    @Test
    fun `includeBuild is not an include`() {
        assertTrue(GradleModuleParser.parseIncludes("""includeBuild("build-logic")""").isEmpty())
    }

    @Test
    fun `a commented-out include is not a module`() {
        assertEquals(listOf(":app"), GradleModuleParser.parseIncludes("include(\":app\")\n// include(\":old\")"))
    }

    @Test
    fun `gradle paths map to directories and names`() {
        assertEquals("app", GradleModuleParser.gradlePathToDirectory(":app"))
        assertEquals("feature/route", GradleModuleParser.gradlePathToDirectory(":feature:route"))
        assertEquals("app", GradleModuleParser.gradlePathToName(":app"))
        assertEquals("route", GradleModuleParser.gradlePathToName(":feature:route"))
    }
}
