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
version = "0.1.0-beta"

// The Marketplace channel is derived from the version, never chosen by hand. A pre-release suffix
// publishes to its own channel, which users opt into by adding a repository URL; only a bare version
// reaches the default (stable) channel that everyone browsing the Marketplace sees.
//
// This is a safety property, not a convenience: Sightline runs commands and edits code, and the
// interactive paths that stop it doing so are not yet human-tested. A release must not reach stable
// because someone forgot a flag. To ship stable, drop the suffix from `version` above — a deliberate,
// reviewable edit.
val releaseChannel: String = when {
    version.toString().contains("-eap") -> "eap"
    version.toString().contains("-alpha") -> "alpha"
    version.toString().contains("-beta") -> "beta"
    version.toString().contains("-rc") -> "rc"
    else -> "default"
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Default: build against your installed Android Studio — exact API match, no multi-GB
        // download, and `runIde` launches that same build.
        //
        // CI has no IDE installed and the local one is a preview build that is not downloadable, so
        // the target is switchable without editing this file:
        //
        //   ./gradlew build -PplatformType=IC -PplatformVersion=2025.3
        //
        // Passing both properties selects a downloadable platform; passing neither uses the local
        // install. `-PlocalIde=/path` overrides where that install is, for a non-default location.
        val platformType = providers.gradleProperty("platformType").orNull
        val platformVersion = providers.gradleProperty("platformVersion").orNull
        if (platformType != null && platformVersion != null) {
            create(platformType, platformVersion)
        } else {
            local(providers.gradleProperty("localIde").orNull ?: "/Applications/Android Studio.app")
        }

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

        // Android Studio's project model, for `ide/android/studio/StudioFactProvider` only. Declared in
        // plugin.xml as an OPTIONAL <depends> (config-file="sightline-android.xml"), so the artifact
        // still installs and runs on a plain IntelliJ IDEA — this is a compile-time dependency, not a
        // runtime requirement. See docs/ANDROID.md §1.1 for why the rest of the Android work
        // deliberately avoids it.
        bundledPlugin("org.jetbrains.android")
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

    // Marketplace publishing. Both credentials come from the environment and neither has a default:
    // an absent token must fail the publish, not silently produce an unauthenticated attempt.
    //
    // PUBLISH_TOKEN — a Marketplace permanent token (Profile → My Tokens).
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = listOf(releaseChannel)
    }

    // Plugin signing. JetBrains recommends it and the Marketplace shows a signed badge; it is optional,
    // and `signPlugin` is simply skipped when the key material is absent, so a fork or a local build
    // needs no secrets. See docs/RELEASING.md for generating the chain and key.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    pluginConfiguration {
        // Single source of truth for the version: the Gradle `version` above. `patchPluginXml` writes it
        // into the artifact's descriptor, so plugin.xml deliberately carries no <version> element —
        // when it did, the two could disagree and the descriptor silently won.
        version = project.version.toString()

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

    // Marketplace gate: the IntelliJ Plugin Verifier (internal/experimental API usage — a common
    // rejection cause — and binary compatibility), against the released 253 floor.
    //
    // >>> RUN `tools/verify-plugin.sh`, NOT `./gradlew verifyPlugin`. <<<
    //
    // IPGP 2.6.0 resolves the IDE under `idea:ideaIC:<v>` (group "idea"), which does not exist; the ZIP
    // lives at `com.jetbrains.intellij.idea:ideaIC:<v>`. Both `select { }` and the explicit `ide(...)`
    // form hit the same wrong group, so the task fails before the verifier starts. The script downloads
    // from the correct coordinate and runs the same verifier CLI directly.
    //
    // Last run 2026-07-20 against IC-253.28294.334: **Compatible** — 0 compatibility problems.
    // The 10 remaining findings are all `ToolWindowFactory` interface members that Kotlin materialises
    // for any implementor (isApplicable, isDoNotActivateOnStart, getIcon, getAnchor, manage); they are
    // informational and not avoidable without abandoning the interface.
    //
    // The config below is kept correct so it starts working the moment the Gradle plugin is fixed.
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

// Read by the release workflow so CI never re-derives either fact with its own string handling — the
// build is the single source, and a second parser is a second thing that can disagree with it.
tasks.register("printVersion") {
    val v = project.version.toString()
    doLast { println(v) }
}

tasks.register("printReleaseChannel") {
    val c = releaseChannel
    doLast { println(c) }
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
