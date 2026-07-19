import org.jetbrains.intellij.platform.gradle.TestFrameworkType
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
            sinceBuild = "261"
            // untilBuild is intentionally omitted: it is ignored on platform 243+,
            // so the plugin stays compatible with future Android Studio builds.
        }
    }

    // Marketplace gate: `./gradlew verifyPlugin` runs the IntelliJ Plugin Verifier against the
    // recommended IDE builds for our since/until range (checks for internal-API usage and binary
    // incompatibilities). Downloads those IDEs on first run, so it is not part of the default build.
    pluginVerification {
        ides {
            recommended()
        }
    }
}
