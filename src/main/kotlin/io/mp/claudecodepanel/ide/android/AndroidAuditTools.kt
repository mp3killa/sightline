package io.mp.claudecodepanel.ide.android

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import io.mp.claudecodepanel.android.GradleModuleParser
import io.mp.claudecodepanel.android.ManifestAudit
import io.mp.claudecodepanel.android.RouteExtractor
import io.mp.claudecodepanel.settings.ClaudeSettings
import java.io.File

/**
 * Static audits: the manifest, and navigation routes. Both purely file-based, so both work with no
 * device, no build running, and during indexing.
 *
 * The manifest audit deliberately prefers the **merged** manifest from
 * `build/intermediates/merged_manifest/<variant>/`. That is where the findings actually live: a library
 * contributes an exported receiver, or a permission arrives from a transitive dependency that nothing in
 * your own manifest mentions. Auditing the source manifest returns a clean report on an app that ships
 * an open component — so when only the source manifest is available, the response says which one it read.
 */
class AndroidAuditTools(private val project: Project) {

    private val names = setOf("android.auditManifest", "android.analyzeRoutes")

    fun handles(name: String): Boolean = name in names

    fun addToolDefs(tools: JsonArray) {
        if (!enabled()) return
        tools.add(
            tool(
                "android.auditManifest",
                "Audit the Android manifest: components exported without a permission, intent filters " +
                    "missing android:exported, cleartext traffic, backup with no extraction rules, " +
                    "foreground-service types, and the code-vs-manifest permission cross-check. Reads " +
                    "the **merged** manifest where one exists — the source manifest hides findings that " +
                    "libraries contribute — and says which it read. Every finding carries a line and a " +
                    "remedy.",
                JsonObject().apply {
                    add("module", JsonObject().apply { addProperty("type", "string") })
                    add("codePermissions", JsonObject().apply {
                        addProperty("type", "array")
                        add("items", JsonObject().apply { addProperty("type", "string") })
                        addProperty(
                            "description",
                            "Permissions the source requests at runtime, if you have grepped for them. " +
                                "Enables the cross-check in both directions; omitted means that check stays silent.",
                        )
                    })
                },
            ),
        )
        tools.add(
            tool(
                "android.analyzeRoutes",
                "Analyse Compose Navigation in a file: declared routes and their arguments, deep links, " +
                    "`navigate()` calls with no matching route (a runtime crash — routes are strings, so " +
                    "nothing catches this at compile time), unreachable routes, and duplicate deep links. " +
                    "Routes built by concatenation are **not** resolved and not guessed at, so a file " +
                    "using constants will report fewer routes rather than wrong ones.",
                JsonObject().apply {
                    add("file", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "Project-relative path. Defaults to the open file.")
                    })
                },
            ),
        )
    }

    fun call(name: String, args: JsonObject): String = when (name) {
        "android.auditManifest" -> auditManifest(args)
        "android.analyzeRoutes" -> analyzeRoutes(args)
        else -> err("Unknown tool: $name")
    }

    // ---- manifest ----

    private fun auditManifest(args: JsonObject): String {
        val context = AndroidContextResolver.getInstance(project).resolve()
        if (context.notAndroid) return err("This project doesn't look like an Android project.")
        val base = project.basePath?.let { File(it) } ?: return err("No project directory.")

        val module = args.str("module")?.let { m -> context.modules.firstOrNull { it.name == m || it.gradlePath == m } }
            ?: context.activeModule
            ?: return err("No Android module found.")
        val moduleDir = File(base, GradleModuleParser.gradlePathToDirectory(module.gradlePath))

        val (file, source) = findManifest(moduleDir, module.variant.value)
            ?: return err("No AndroidManifest.xml found for ${module.name}.")

        val model = ManifestAudit.parse(source)
        val codePermissions = args.stringList("codePermissions").toSet()
        val findings = ManifestAudit.audit(model, codePermissions)
        val merged = file.path.contains("merged_manifest") || file.path.contains("merged_manifests")

        return json {
            addProperty("module", module.gradlePath)
            addProperty("manifest", file.path.removePrefix(base.path).trimStart('/'))
            addProperty("merged", merged)
            addProperty("summary", ManifestAudit.summarise(findings))
            addProperty("permissions", model.permissions.joinToString(", "))
            add("findings", JsonArray().apply {
                findings.forEach { f ->
                    add(JsonObject().apply {
                        addProperty("severity", f.severity.name.lowercase())
                        addProperty("title", f.title)
                        addProperty("detail", f.detail)
                        addProperty("remedy", f.remedy)
                        f.component?.let { addProperty("component", it) }
                        addProperty("line", f.line)
                    })
                }
            })
            add("deepLinks", JsonArray().apply {
                model.components.flatMap { it.deepLinks }.distinct().forEach { add(it) }
            })
            if (!merged) {
                addProperty(
                    "warning",
                    "This is the SOURCE manifest — nothing has been built for this variant yet. " +
                        "Components and permissions contributed by libraries are not visible here, so " +
                        "the audit is incomplete. Build once, then re-run for the merged manifest.",
                )
            }
            if (codePermissions.isEmpty()) {
                addProperty(
                    "note",
                    "No `codePermissions` were supplied, so the code-vs-manifest cross-check did not run.",
                )
            }
        }
    }

    /** Prefer the merged manifest for the selected variant; fall back to the source one. */
    private fun findManifest(moduleDir: File, variant: String?): Pair<File, String>? {
        val candidates = buildList {
            for (root in listOf("merged_manifest", "merged_manifests")) {
                val dir = File(moduleDir, "build/intermediates/$root")
                if (!dir.isDirectory) continue
                val variantDir = variant?.let { File(dir, it) }?.takeIf { it.isDirectory }
                    ?: dir.listFiles()?.firstOrNull { it.isDirectory }
                variantDir?.walkTopDown()?.maxDepth(3)
                    ?.firstOrNull { it.isFile && it.name == "AndroidManifest.xml" }
                    ?.let { add(it) }
            }
            add(File(moduleDir, "src/main/AndroidManifest.xml"))
        }
        return candidates.firstOrNull { it.isFile }
            ?.let { f -> runCatching { f to f.readText(Charsets.UTF_8) }.getOrNull() }
    }

    // ---- routes ----

    private fun analyzeRoutes(args: JsonObject): String {
        val context = AndroidContextResolver.getInstance(project).resolve()
        val relative = args.str("file") ?: context.editor?.relativePath
            ?: return err("No file given and no file is open.")
        val base = project.basePath ?: return err("No project directory.")
        val source = runCatching { File(base, relative).takeIf { it.isFile }?.readText(Charsets.UTF_8) }
            .getOrNull() ?: return err("Could not read $relative.")

        val analysis = RouteExtractor.analyze(source)
        if (analysis.routes.isEmpty() && analysis.navigateCalls.isEmpty()) {
            return json {
                addProperty("file", relative)
                addProperty("hasNavigation", false)
                addProperty("note", "No Compose Navigation routes or navigate() calls in this file.")
            }
        }
        val device = context.device?.takeIf { it.state.isUsable }
        val applicationId = context.activeModule?.applicationId?.value

        return json {
            addProperty("file", relative)
            addProperty("hasNavigation", true)
            analysis.startDestination?.let { addProperty("startDestination", it) }
            add("routes", JsonArray().apply {
                analysis.routes.forEach { r ->
                    add(JsonObject().apply {
                        addProperty("route", r.route)
                        addProperty("line", r.line)
                        r.destination?.let { addProperty("destination", it) }
                        add("arguments", JsonArray().apply { r.arguments.forEach { add(it) } })
                        add("deepLinks", JsonArray().apply { r.deepLinks.forEach { add(it) } })
                        // The one-click bit: a route is only testable if you can launch it.
                        r.deepLinks.firstOrNull()?.let { link ->
                            addProperty(
                                "launchCommand",
                                "adb " + RouteExtractor.deepLinkCommand(link, device?.serial, applicationId)
                                    .args.joinToString(" "),
                            )
                        }
                    })
                }
            })
            add("danglingCalls", JsonArray().apply {
                analysis.danglingCalls().forEach { c ->
                    add(JsonObject().apply {
                        addProperty("route", c.route)
                        addProperty("line", c.line)
                    })
                }
            })
            add("unreachableRoutes", JsonArray().apply {
                analysis.unreachable().forEach { add(it.route) }
            })
            add("duplicateDeepLinks", JsonArray().apply {
                analysis.duplicateDeepLinks().forEach { (link, routes) ->
                    add(JsonObject().apply {
                        addProperty("deepLink", link)
                        add("routes", JsonArray().apply { routes.forEach { add(it.route) } })
                    })
                }
            })
            addProperty(
                "limits",
                "This file only, and string literals only. A route held in a constant or built by " +
                    "concatenation is not resolved — so `unreachableRoutes` can list a route that is " +
                    "reached from elsewhere, and it is a prompt to check rather than a defect.",
            )
        }
    }

    private fun enabled(): Boolean = ClaudeSettings.getInstance().state.androidFeatures

    private fun err(message: String) = json { addProperty("error", message) }

    private fun tool(name: String, desc: String, props: JsonObject): JsonObject = JsonObject().apply {
        addProperty("name", name)
        addProperty("description", desc)
        add("inputSchema", JsonObject().apply {
            addProperty("type", "object")
            add("properties", props)
        })
    }

    private inline fun json(build: JsonObject.() -> Unit): String = JsonObject().apply(build).toString()

    private fun JsonObject.str(key: String): String? =
        runCatching { get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() } }.getOrNull()

    private fun JsonObject.stringList(key: String): List<String> = runCatching {
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
            ?.filter { it.isNotBlank() }
            .orEmpty()
    }.getOrDefault(emptyList())
}
