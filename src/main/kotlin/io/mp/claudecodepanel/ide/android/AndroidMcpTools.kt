package io.mp.claudecodepanel.ide.android

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import io.mp.claudecodepanel.android.AndroidContext
import io.mp.claudecodepanel.android.ContextChipKind
import io.mp.claudecodepanel.android.Fact
import io.mp.claudecodepanel.android.ModuleContext
import io.mp.claudecodepanel.settings.ClaudeSettings

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

    private val names = setOf("android.getContext")

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
    }

    /** Called off the EDT by `IdeServer`'s WebSocket thread — [AndroidContextResolver] requires that. */
    fun call(name: String, args: JsonObject): String = when (name) {
        "android.getContext" -> getContext(args)
        else -> """{"success":false,"error":"Unknown tool: $name"}"""
    }

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
