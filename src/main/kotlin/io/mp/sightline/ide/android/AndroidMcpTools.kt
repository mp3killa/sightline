package io.mp.sightline.ide.android

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import io.mp.sightline.android.AndroidContext
import io.mp.sightline.android.BuildFailureClassifier
import io.mp.sightline.android.BuildIntent
import io.mp.sightline.android.ContextChipKind
import io.mp.sightline.android.Fact
import io.mp.sightline.android.GradleTasks
import io.mp.sightline.android.ModuleContext
import io.mp.sightline.android.TaskResolution
import io.mp.sightline.android.TestSelection
import io.mp.sightline.settings.ClaudeSettings

/**
 * The `android.*` tools on the existing `ide` MCP server.
 *
 * Deliberately hung off that server rather than a second one: `--mcp-config` in `ClaudeSession` is a
 * single hardcoded inline JSON string with no merging layer, so one server means one place to change.
 *
 * The context is already pushed with every message (see `ui/state/ComposerModel.buildMessage`), so these
 * tools exist for the cases pushing can't cover: a long turn where the device changed underneath, a chip
 * the user switched off but Claude has a specific reason to want, or a question about a module other
 * than the active one.
 */
class AndroidMcpTools(private val project: Project) {

    private val names = setOf("android.getContext", "android.resolveTask", "android.selectTests", "android.diagnoseBuild")

    fun handles(name: String): Boolean = name in names

    fun addToolDefs(tools: JsonArray) {
        if (!enabled()) return
        tools.add(
            tool(
                "android.getContext",
                "Get the current Android state of this project: modules, the selected build variant and " +
                    "product flavours, applicationId, min/target/compile SDK, the connected device and " +
                    "whether the app is running, and the open file. Every value carries the source it " +
                    "came from — `ide` is current, `last build` and `declared` may be stale. " +
                    "The active module's context is already included with each user message, so call " +
                    "this only to refresh after something changed, or to see a different module. " +
                    "Pass `refresh: true` to bypass the short cache.",
                JsonObject().apply {
                    add("refresh", JsonObject().apply {
                        addProperty("type", "boolean")
                        addProperty("description", "Re-read instead of using the cached answer (costs an adb round-trip).")
                    })
                },
            ),
        )
        tools.add(
            tool(
                "android.resolveTask",
                "Get the exact Gradle task for an intent, resolved against the selected build variant — " +
                    "`assemble`, `bundle`, `install`, `compile`, `unit_test`, `instrumentation_test`, " +
                    "`lint`, `clean`. **Use this instead of composing a task name yourself.** AGP names " +
                    "tasks per variant (`assembleDemoStagingDebug`), and the aggregate `assemble` builds " +
                    "*every* variant — twenty of them on a two-dimension project. If the variant isn't " +
                    "known this refuses and says why rather than returning the aggregate.",
                JsonObject().apply {
                    add("intent", JsonObject().apply { addProperty("type", "string") })
                    add("module", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "Module name or Gradle path. Defaults to the active module.")
                    })
                    add("variant", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "Override the selected variant.")
                    })
                },
            ),
        )
        tools.add(
            tool(
                "android.selectTests",
                "Given changed files (or the open file), return the tests worth running and the Gradle " +
                    "commands to run them, grouped per module. Also returns `uncovered`: changed " +
                    "production files with no matching test, which those commands will NOT exercise.",
                JsonObject().apply {
                    add("files", JsonObject().apply {
                        addProperty("type", "array")
                        add("items", JsonObject().apply { addProperty("type", "string") })
                        addProperty("description", "Project-relative paths. Defaults to the open file.")
                    })
                },
            ),
        )
        tools.add(
            tool(
                "android.diagnoseBuild",
                "Classify raw Gradle output into a typed Android build failure — KSP/KAPT, manifest " +
                    "merge, duplicate class, unresolved dependency, R8, Compose/Kotlin mismatch, JVM " +
                    "target mismatch, version catalogue, configuration cache, SDK, OOM. Returns " +
                    "`recognised: false` with the raw excerpt and **no suggestion** when the cause " +
                    "isn't one it knows — treat that as 'read the output', not as a diagnosis.",
                JsonObject().apply {
                    add("output", JsonObject().apply { addProperty("type", "string") })
                },
            ),
        )
    }

    /** Called off the EDT by `IdeServer`'s WebSocket thread — [AndroidContextResolver] requires that. */
    fun call(name: String, args: JsonObject): String = when (name) {
        "android.getContext" -> getContext(args)
        "android.resolveTask" -> resolveTask(args)
        "android.selectTests" -> selectTests(args)
        "android.diagnoseBuild" -> diagnoseBuild(args)
        else -> """{"success":false,"error":"Unknown tool: $name"}"""
    }

    /**
     * Gradle task name for an intent, resolved against the selected variant.
     *
     * Exists because the *failure* it prevents is expensive and silent: `assemble` builds every variant,
     * which on two flavour dimensions is twenty builds. Guessing a task name is the single easiest way
     * to burn an hour here, so the answer either names the exact task or refuses and says why.
     */
    private fun resolveTask(args: JsonObject): String {
        val intentName = args.str("intent")?.uppercase() ?: return err("`intent` is required")
        val intent = BuildIntent.entries.firstOrNull { it.name == intentName }
            ?: return err("Unknown intent '$intentName'. One of: ${BuildIntent.entries.joinToString(", ") { it.name.lowercase() }}")

        val context = resolver().resolve()
        if (context.notAndroid) return notAndroid()
        val module = args.str("module")?.let { m -> context.modules.firstOrNull { it.name == m || it.gradlePath == m } }
            ?: context.activeModule
            ?: return err("No Android module found.")
        val variant = args.str("variant") ?: module.variant.value

        return when (val r = GradleTasks.resolve(intent, module.gradlePath, variant)) {
            is TaskResolution.Refused -> json {
                addProperty("resolved", false)
                addProperty("reason", r.refusal.reason)
                addProperty("hint", r.refusal.hint)
                add("candidateVariants", JsonArray().apply { module.builtVariants.forEach { add(it) } })
            }
            is TaskResolution.Resolved -> json {
                addProperty("resolved", true)
                addProperty("task", r.task.task)
                addProperty("command", GradleTasks.command(r.task).joinToString(" "))
                addProperty("module", module.gradlePath)
                addProperty("variant", variant ?: "")
                addProperty("variantSource", module.variant.tier.label)
                r.task.note?.let { addProperty("note", it) }
            }
        }
    }

    /**
     * Which tests to run for a set of changed files.
     *
     * Reports `uncovered` as well as targets, because a green run that quietly skipped the relevant test
     * is worse than no run at all — the caller needs to know what was changed but not exercised.
     */
    private fun selectTests(args: JsonObject): String {
        val context = resolver().resolve()
        if (context.notAndroid) return notAndroid()

        val changed = args.stringList("files").ifEmpty {
            listOfNotNull(context.editor?.relativePath)
        }
        if (changed.isEmpty()) return err("No files given and no file is open.")

        val modules = context.modules.map { it.gradlePath }
        val plan = TestSelection.planFor(changed, resolver().projectSourceFiles(), modules)
        val variantByModule = context.modules.associate { it.gradlePath to it.variant.value }

        return json {
            addProperty("needsDevice", plan.needsDevice)
            add("tests", JsonArray().apply {
                plan.targets.forEach { t ->
                    add(JsonObject().apply {
                        addProperty("fqcn", t.fqcn)
                        addProperty("file", t.relativePath)
                        addProperty("kind", t.kind.label)
                        addProperty("module", t.gradlePath)
                    })
                }
            })
            add("commands", JsonArray().apply {
                TestSelection.commands(plan) { variantByModule[it] }.forEach { r ->
                    when (r) {
                        is TaskResolution.Resolved -> add(GradleTasks.command(r.task).joinToString(" "))
                        is TaskResolution.Refused -> add("(unresolved: ${r.refusal.reason})")
                    }
                }
            })
            add("uncovered", JsonArray().apply { plan.uncovered.forEach { add(it) } })
            addProperty(
                "note",
                "`uncovered` lists changed production files with no matching test — running the commands " +
                    "above will not exercise them.",
            )
        }
    }

    /** Classify raw Gradle output. Unrecognised output is reported as such, never given a cause. */
    private fun diagnoseBuild(args: JsonObject): String {
        val output = args.str("output") ?: return err("`output` is required")
        val failure = BuildFailureClassifier.classify(output)
            ?: return json {
                addProperty("failed", false)
                addProperty("note", "This output does not report a build failure.")
            }
        return json {
            addProperty("failed", true)
            addProperty("kind", failure.kind.name.lowercase())
            addProperty("kindLabel", failure.kind.label)
            addProperty("recognised", failure.isRecognised)
            addProperty("headline", failure.headline)
            failure.failedTask?.let { addProperty("failedTask", it) }
            failure.detail?.let { addProperty("detail", it) }
            failure.suggestion?.let { addProperty("suggestion", it) }
            add("affectedFiles", JsonArray().apply { failure.affectedFiles.forEach { add(it) } })
            addProperty("rawExcerpt", failure.rawExcerpt)
            if (!failure.isRecognised) {
                addProperty(
                    "note",
                    "The cause was not recognised, so no suggestion is offered — read `rawExcerpt` " +
                        "rather than treating `headline` as a diagnosis.",
                )
            }
        }
    }

    private fun resolver() = AndroidContextResolver.getInstance(project)

    private fun err(message: String) = json { addProperty("error", message) }

    private fun notAndroid() = json {
        addProperty("available", false)
        addProperty("reason", "This project doesn't look like an Android project.")
    }

    private fun JsonObject.str(key: String): String? =
        runCatching { get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() } }.getOrNull()

    private fun JsonObject.stringList(key: String): List<String> = runCatching {
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
            ?.filter { it.isNotBlank() }
            .orEmpty()
    }.getOrDefault(emptyList())

    private fun getContext(args: JsonObject): String {
        if (!enabled()) return json { addProperty("available", false); addProperty("reason", "Android features are turned off in Sightline's settings.") }
        val refresh = runCatching { args.get("refresh")?.asBoolean }.getOrNull() ?: false
        val context = AndroidContextResolver.getInstance(project).resolve(force = refresh)

        if (context.notAndroid) {
            return json {
                addProperty("available", false)
                addProperty("reason", "This project doesn't look like an Android project.")
            }
        }
        return json {
            addProperty("available", true)
            add("modules", JsonArray().apply { context.modules.forEach { add(moduleJson(it)) } })
            addProperty("activeModule", context.activeModuleName ?: "")
            add("device", deviceJson(context))
            add("editor", JsonObject().apply {
                addProperty("file", context.editor?.relativePath ?: "")
                context.editor?.selectionLines?.let {
                    addProperty("selectionStartLine", it.first)
                    addProperty("selectionEndLine", it.last)
                }
            })
            add("versionCatalog", JsonObject().apply {
                context.versionCatalog.forEach { (k, v) -> addProperty(k, v) }
            })
            // Says what the payload is *not*, so an absent field reads as "not determined" rather than
            // as "determined to be absent" — the same reason getDiagnostics reports `available:false`.
            addProperty(
                "note",
                "Each fact carries a `source`: `ide` is the IDE's current selection; `last build` came " +
                    "from AGP build output and may be stale; `declared` was parsed from a build file and " +
                    "may be overridden per-variant; `device` was read from the running device. A missing " +
                    "value means it could not be determined, not that it is unset.",
            )
        }
    }

    private fun moduleJson(m: ModuleContext): JsonObject = JsonObject().apply {
        addProperty("name", m.name)
        addProperty("gradlePath", m.gradlePath)
        add("variant", factJson(m.variant))
        add("flavors", factJson(m.flavors) { list -> JsonArray().apply { list.forEach { add(it) } } })
        add("buildType", factJson(m.buildType))
        add("applicationId", factJson(m.applicationId))
        add("minSdk", factJson(m.minSdk))
        add("targetSdk", factJson(m.targetSdk))
        add("compileSdk", factJson(m.compileSdk))
        add("versionName", factJson(m.versionName))
        add("versionCode", factJson(m.versionCode))
        add("usesCompose", factJson(m.usesCompose))
        add("builtVariants", JsonArray().apply { m.builtVariants.forEach { add(it) } })
    }

    private fun deviceJson(context: AndroidContext): JsonObject = JsonObject().apply {
        val d = context.device
        if (d == null) {
            addProperty("connected", false)
            return@apply
        }
        addProperty("connected", true)
        addProperty("serial", d.serial)
        addProperty("name", d.name)
        addProperty("state", d.state.label)
        addProperty("ready", d.state.isUsable)
        add("apiLevel", factJson(d.apiLevel))
        add("androidRelease", factJson(d.androidRelease))
        add("appRunning", factJson(d.appRunning))
    }

    /**
     * A fact as `{value, source}` — or `{source: "unknown", note}` when it isn't known. Omitting the
     * value entirely rather than sending null is deliberate: it makes an unknown fact structurally
     * different from a known-empty one, which is the distinction the whole ladder exists to preserve.
     */
    private fun <T : Any> factJson(
        fact: Fact<T>,
        render: (T) -> com.google.gson.JsonElement = { com.google.gson.JsonPrimitive(it.toString()) },
    ): JsonObject = JsonObject().apply {
        val v = fact.value
        if (v == null) {
            addProperty("source", "unknown")
            fact.note?.let { addProperty("note", it) }
        } else {
            when (v) {
                is Int -> addProperty("value", v)
                is Boolean -> addProperty("value", v)
                is String -> addProperty("value", v)
                else -> add("value", render(v))
            }
            addProperty("source", fact.tier.label)
            fact.note?.let { addProperty("note", it) }
        }
    }

    private fun enabled(): Boolean = ClaudeSettings.getInstance().state.androidFeatures

    private fun tool(name: String, desc: String, props: JsonObject?): JsonObject = JsonObject().apply {
        addProperty("name", name)
        addProperty("description", desc)
        add("inputSchema", JsonObject().apply {
            addProperty("type", "object")
            add("properties", props ?: JsonObject())
        })
    }

    private inline fun json(build: JsonObject.() -> Unit): String = JsonObject().apply(build).toString()

    companion object {
        /** The chips a pushed context block covers — used by the panel to keep tool and push in step. */
        val PUSHED_CHIPS: Set<ContextChipKind> = ContextChipKind.DEFAULT_ENABLED
    }
}
