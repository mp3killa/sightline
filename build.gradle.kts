import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    // Match the Kotlin bundled in the target IDE (Android Studio 2026.1 ships Kotlin 2.3.10);
    // an older compiler can't read the platform's newer class metadata.
    id("org.jetbrains.kotlin.jvm") version "2.3.10"
    id("org.jetbrains.intellij.platform") version "2.6.0"
}

group = "io.mp"
version = "0.6.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Build against your installed Android Studio: exact API match, no multi-GB download,
        // and `runIde` launches that same build. See README for the alternatives below.
        local("/Applications/Android Studio.app")

        // --- Alternatives: comment out local() above and uncomment ONE of these ---
        // androidStudio("2026.1.4")        // download a specific Android Studio build
        // create("IC", "2026.1")           // or IntelliJ IDEA Community

        // The platform test framework: needed so the `test` task's JVM (which the plugin decorates
        // with a platform file-system bootstrap arg) can start. Our tests are plain JUnit4 unit
        // tests over the activity/graph logic and don't spin up an IDE fixture.
        testFramework(TestFrameworkType.Platform)

        // Java PSI (com.intellij.psi.PsiClass) + Java UAST provider — used by ProjectStructureEnricher
        // for precise class-hierarchy (extends/implements) resolution across Kotlin & Java via UAST.
        // Always present in Android Studio; the plugin declares <depends>com.intellij.modules.java</depends>.
        bundledPlugin("com.intellij.java")

        // The Kotlin plugin puts the Kotlin UAST provider on the classpath so the enricher's Kotlin path
        // is covered by a BasePlatformTestCase (Kotlin uses the same UAST code as Java). The main plugin
        // does NOT declare a runtime <depends> on it — it only calls language-neutral UAST — so this is
        // effectively test-scoped. Always bundled in Android Studio / IntelliJ IDEA.
        bundledPlugin("org.jetbrains.kotlin")
    }

    // Bundled into the plugin: used to parse the CLI's streaming-JSON events.
    // (The IntelliJ platform doesn't expose Gson on the plugin classpath.)
    implementation("com.google.code.gson:gson:2.11.0")

    // WebSocket server for the `ide` MCP integration the CLI connects to.
    // slf4j-api is already provided by the IntelliJ platform at runtime.
    implementation("org.java-websocket:Java-WebSocket:1.5.7") {
        exclude(group = "org.slf4j")
    }

    // Unit tests for the platform-free activity/graph logic (JUnit 4; no IDE fixture needed).
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

intellijPlatform {
    // Skip the headless "searchable options" indexing pass: it launches a second IDE
    // instance and is flaky against a local() Android Studio. Settings search still works.
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            // Floor = the latest *released* IntelliJ platform (2025.3 / build 253) rather than the local
            // Android Studio's preview build 261: 253 is downloadable for the Plugin Verifier and gives the
            // listing real reach (installable on 253+, which includes AS 2026.1). The code avoids 261-only
            // APIs — `verifyPlugin` (below) confirms the 261-compiled artifact is clean against 253.
            sinceBuild = "253"
            // untilBuild is intentionally omitted: it is ignored on platform 243+,
            // so the plugin stays compatible with future Android Studio builds.
        }
    }

    // Marketplace gate: `./gradlew verifyPlugin` runs the IntelliJ Plugin Verifier (internal/experimental
    // API usage — a common rejection cause — and binary compatibility). It verifies against the released
    // 253 floor (IntelliJ IDEA Community 2025.3). The plugin uses only platform APIs + com.intellij.java
    // (no Android-plugin APIs), so IC is a valid, portable target.
    //
    // NOTE (2026-07): the verifier could not be *run* in the dev environment here — IPGP 2.6.0 mis-resolves
    // the IC distribution coordinate (`idea:ideaIC:<v>`, group "idea") while the ZIP actually lives at
    // `com.jetbrains.intellij.idea:ideaIC:<v>` (the ZIP exists + downloads), and `local(AndroidStudio)`
    // can't be used because that install is already the compile-time platform dependency. Run this on CI /
    // an environment where the IC distribution resolves (or a newer IPGP). The config below is correct.
    pluginVerification {
        ides {
            select {
                types = listOf(IntelliJPlatformType.IntellijIdeaCommunity)
                channels = listOf(ProductRelease.Channel.RELEASE)
                sinceBuild = "253"
                untilBuild = "253.*"
            }
        }
    }
}

tasks.withType<RunIdeTask>().configureEach {
    // WORKAROUND (dev only, never affects the built plugin): gradle-plugin 2.6.0 doesn't add AS 2026.1's
    // required boot-classpath entry for nio-fs.jar, so the platform's
    // `-Djava.nio.file.spi.DefaultFileSystemProvider=…MultiRoutingFileSystemProvider` can't be loaded and
    // VM init dies (ClassNotFound; "Failure when starting JFR on_create_vm"). AS's own product-info adds
    // `-Xbootclasspath/a:$APP/Contents/lib/nio-fs.jar` — mirror that.
    val nioFs = file("/Applications/Android Studio.app/Contents/lib/nio-fs.jar")
    if (nioFs.exists()) {
        jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Xbootclasspath/a:${nioFs.absolutePath}") })
    }
    // Enable the sandbox-only test bridge with `-PtestBridge` (passed as an env var; TestBridgeGuard
    // reads SIGHTLINE_TEST_BRIDGE or -Dsightline.testBridge). Never set in the Marketplace build.
    if (providers.gradleProperty("testBridge").isPresent) {
        environment("SIGHTLINE_TEST_BRIDGE", "true")
    }
}
