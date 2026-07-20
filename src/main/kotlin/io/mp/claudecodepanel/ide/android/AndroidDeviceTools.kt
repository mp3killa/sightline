package io.mp.claudecodepanel.ide.android

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import io.mp.claudecodepanel.android.AdbOutputParsers
import io.mp.claudecodepanel.android.CrashInvestigator
import io.mp.claudecodepanel.android.DeviceAction
import io.mp.claudecodepanel.android.DeviceActions
import io.mp.claudecodepanel.android.DeviceRecipe
import io.mp.claudecodepanel.android.DeviceRecipes
import io.mp.claudecodepanel.android.LogLevel
import io.mp.claudecodepanel.android.LogcatParser
import io.mp.claudecodepanel.android.LogcatRedactor
import io.mp.claudecodepanel.android.StackTraceResolver
import io.mp.claudecodepanel.ide.ApprovalCoordinator
import io.mp.claudecodepanel.settings.ClaudeSettings

/**
 * The device half of the `android.*` MCP tools: listing devices, capturing a **redacted** logcat, and
 * investigating a crash.
 *
 * Two rules run through all of it.
 *
 * **Logcat is redacted before it leaves this class, unconditionally.** Not as a setting, not as a flag
 * the caller can clear — a device log carries auth tokens, the user's email and their coordinates, and
 * this text is going to a third-party API. The redaction summary is returned alongside so the model knows
 * values were removed rather than absent (see `android/LogcatRedactor`).
 *
 * **Destructive actions are not exposed here at all.** Anything that erases data goes through the UI's
 * confirmation path via [ApprovalCoordinator], because a permission mode that stops prompting is right
 * for `adb devices` and emphatically wrong for `pm clear`. What this class offers is observation.
 */
class AndroidDeviceTools(private val project: Project) {

    private val names = setOf(
        "android.listDevices",
        "android.captureLogcat",
        "android.investigateCrash",
        "android.deviceRecipe",
    )

    fun handles(name: String): Boolean = name in names

    fun addToolDefs(tools: JsonArray) {
        if (!enabled()) return
        tools.add(
            tool(
                "android.listDevices",
                "List connected devices and emulators, and the AVDs that could be started. Each device " +
                    "reports whether it is actually usable — `offline` and `unauthorised` are distinct " +
                    "states with different fixes.",
                JsonObject(),
            ),
        )
        tools.add(
            tool(
                "android.captureLogcat",
                "Capture recent logcat, **already redacted**: auth tokens, emails, coordinates, device " +
                    "identifiers and home paths are removed before you see it, and the response says how " +
                    "many values were removed. A redacted value was present in the log — treat it as " +
                    "'a token was here', not as an empty field. Repeated lines are folded and framework " +
                    "noise can be hidden. Also returns detected crashes, ANRs, StrictMode violations and " +
                    "process restarts.",
                JsonObject().apply {
                    add("packageName", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "Filter to this app's process. Defaults to the resolved applicationId.")
                    })
                    add("minLevel", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "V, D, I, W, E or F. Default V.")
                    })
                    add("maxLines", JsonObject().apply { addProperty("type", "integer") })
                    add("hideNoise", JsonObject().apply {
                        addProperty("type", "boolean")
                        addProperty("description", "Drop known framework-chatter tags (Choreographer, OpenGLRenderer, …).")
                    })
                },
            ),
        )
        tools.add(
            tool(
                "android.investigateCrash",
                "Capture logcat and explain the most recent crash or ANR, grouped by how well each " +
                    "statement is evidenced: **Confirmed** (read off the trace), **Likely cause**, " +
                    "**Contributing factors**, and **Missing evidence** (what could not be determined, " +
                    "and what would settle it). Do not promote a Likely or Possible finding to a cause " +
                    "in your answer — the grouping is the point.",
                JsonObject().apply {
                    add("packageName", JsonObject().apply { addProperty("type", "string") })
                },
            ),
        )
        tools.add(
            tool(
                "android.deviceRecipe",
                "Plan a device-state recipe (`large_font`, `accessibility`, `dark_mode`, `landscape`) — " +
                    "the conditions developers usually skip. Returns the commands to apply it **and the " +
                    "commands to put the device back on the values it currently has**. Reports " +
                    "`revertible: false` when a current value could not be read; do not apply a recipe " +
                    "in that state — it could not be undone.",
                JsonObject().apply {
                    add("recipe", JsonObject().apply { addProperty("type", "string") })
                },
            ),
        )
    }

    /** Off the EDT by contract (the WebSocket thread) — every path here shells out to adb. */
    fun call(name: String, args: JsonObject): String = when (name) {
        "android.listDevices" -> listDevices()
        "android.captureLogcat" -> captureLogcat(args)
        "android.investigateCrash" -> investigateCrash(args)
        "android.deviceRecipe" -> deviceRecipe(args)
        else -> err("Unknown tool: $name")
    }

    // ---- devices ----

    private fun listDevices(): String {
        val env = AndroidEnvironment.getInstance(project)
        val sdk = env.sdk() ?: return json {
            addProperty("available", false)
            addProperty("reason", "No Android SDK found. Set it in Settings → Sightline or via ANDROID_HOME.")
        }
        if (sdk.adb == null) {
            return json {
                addProperty("available", false)
                addProperty("reason", "The SDK has no platform-tools/adb, so no device can be reached.")
            }
        }

        val result = env.adb("devices", "-l", timeoutMs = 8000)
        // Null devices and an empty list are different answers with different fixes — see HealthChecker.
        if (result == null || !result.ok) {
            return json {
                addProperty("available", false)
                addProperty("reason", "adb did not answer, so the device list is unknown (not empty).")
                addProperty("hint", "Try `adb kill-server` and retry.")
            }
        }
        val devices = AdbOutputParsers.parseDevices(result.stdout)
        val avds = sdk.emulator?.let { env.emulator("-list-avds", timeoutMs = 8000) }
            ?.takeIf { it.ok }?.let { AdbOutputParsers.parseAvdNames(it.stdout) }
            .orEmpty()

        return json {
            addProperty("available", true)
            add("devices", JsonArray().apply {
                devices.forEach { d ->
                    add(JsonObject().apply {
                        addProperty("serial", d.serial)
                        addProperty("name", d.displayName)
                        addProperty("state", d.state.label)
                        addProperty("usable", d.state.isUsable)
                        addProperty("emulator", d.isEmulator)
                    })
                }
            })
            add("startableAvds", JsonArray().apply { avds.forEach { add(it) } })
            if (devices.isEmpty()) {
                addProperty(
                    "note",
                    if (avds.isEmpty()) "Nothing is connected and no AVD is defined."
                    else "Nothing is connected. One of `startableAvds` can be launched with `emulator -avd <name>`.",
                )
            }
        }
    }

    // ---- logcat ----

    private fun captureLogcat(args: JsonObject): String {
        val env = AndroidEnvironment.getInstance(project)
        val context = AndroidContextResolver.getInstance(project).resolve()
        val device = context.device
            ?: return err("No device is connected, so there is no log to read.")
        if (!device.state.isUsable) {
            return err("The device ${device.name} is ${device.state.label}, so logcat can't be read.")
        }

        val packageName = args.str("packageName") ?: context.activeModule?.applicationId?.value
        val minLevel = args.str("minLevel")?.firstOrNull()?.let { LogLevel.of(it) } ?: LogLevel.VERBOSE
        val maxLines = args.int("maxLines")?.coerceIn(50, 5000) ?: 1500
        val hideNoise = args.bool("hideNoise") ?: false

        val pid = packageName?.let { pidOf(env, device.serial, it) }
        val action = DeviceActions.logcat(device.serial, pid, minLevel, maxLines)
        val result = run(env, action, timeoutMs = 15_000)
            ?: return err("adb could not be run.")
        if (!result.ok) return err("logcat failed: ${result.output.take(200)}")

        val entries = LogcatParser.filter(LogcatParser.parse(result.stdout), minLevel, pid, hideNoise)
        val grouped = LogcatParser.group(entries)
        val signals = LogcatParser.signals(entries)

        // Redact the rendered text, never the raw — the raw never leaves this method.
        val redaction = LogcatRedactor.redact(grouped.joinToString("\n") { it.display })

        return json {
            addProperty("lines", grouped.size)
            packageName?.let { addProperty("filteredTo", it) }
            if (packageName != null && pid == null) {
                addProperty(
                    "scopeWarning",
                    "$packageName has no running process, so this is the whole device log, not just that app.",
                )
            }
            addProperty("log", redaction.text)
            add("signals", JsonArray().apply {
                signals.forEach { s ->
                    add(JsonObject().apply {
                        addProperty("kind", s.kind.label)
                        addProperty("summary", LogcatRedactor.redactLine(s.summary))
                        s.process?.let { addProperty("process", it) }
                    })
                }
            })
            addProperty("redactions", redaction.totalRedactions)
            addProperty("droppedLines", redaction.droppedLines)
            addProperty(
                "privacyNote",
                if (redaction.changedAnything)
                    "Redacted before you saw it: ${redaction.summary()}. `[redacted]` means a value WAS " +
                        "present — treat it as 'a token/email/coordinate was here', not as an empty field."
                else "Nothing needed redacting in this capture.",
            )
        }
    }

    private fun investigateCrash(args: JsonObject): String {
        val env = AndroidEnvironment.getInstance(project)
        val context = AndroidContextResolver.getInstance(project).resolve()
        val device = context.device?.takeIf { it.state.isUsable }
            ?: return err("No usable device is connected.")

        val module = context.activeModule
        val packageName = args.str("packageName") ?: module?.applicationId?.value
        val pid = packageName?.let { pidOf(env, device.serial, it) }
        val result = run(env, DeviceActions.logcat(device.serial, pid, LogLevel.VERBOSE, 3000), 15_000)
            ?: return err("adb could not be run.")
        if (!result.ok) return err("logcat failed: ${result.output.take(200)}")

        val entries = LogcatParser.parse(result.stdout)
        val signal = LogcatParser.signals(entries)
            .lastOrNull { it.kind == io.mp.claudecodepanel.android.LogSignal.Kind.CRASH ||
                it.kind == io.mp.claudecodepanel.android.LogSignal.Kind.ANR }
            ?: return json {
                addProperty("found", false)
                addProperty("note", "No crash or ANR is present in the current log window.")
            }

        val trace = StackTraceResolver.parse(result.stdout)
        val prefixes = StackTraceResolver.appPrefixes(
            module?.applicationId?.value,
            module?.namespace?.value,
        )
        val investigation = CrashInvestigator.investigate(
            signal = signal,
            logcat = entries,
            trace = trace,
            appPrefixes = prefixes,
            recentlyChanged = context.recentlyChanged,
            deviceApiLevel = device.apiLevel.value,
            variant = module?.variant?.value,
        )

        return json {
            addProperty("found", true)
            addProperty("kind", investigation.signalKind.label)
            addProperty("headline", LogcatRedactor.redactLine(investigation.headline))
            addProperty("report", LogcatRedactor.redact(CrashInvestigator.format(investigation)).text)
            add("findings", JsonArray().apply {
                investigation.findings.forEach { f ->
                    add(JsonObject().apply {
                        addProperty("confidence", f.confidence.heading)
                        addProperty("statement", LogcatRedactor.redactLine(f.statement))
                        f.evidence?.let { addProperty("evidence", it) }
                        f.filePath?.let { addProperty("file", it) }
                        f.line?.let { addProperty("line", it) }
                    })
                }
            })
            addProperty(
                "note",
                "Findings are grouped by how well they are evidenced. Do not restate a 'Likely cause' " +
                    "or 'Contributing factor' as the cause — and read 'Missing evidence' before concluding.",
            )
        }
    }

    // ---- recipes ----

    private fun deviceRecipe(args: JsonObject): String {
        val name = args.str("recipe")?.uppercase()?.replace('-', '_') ?: return err("`recipe` is required")
        val recipe = DeviceRecipe.entries.firstOrNull { it.name == name }
            ?: return err("Unknown recipe. One of: ${DeviceRecipe.entries.joinToString(", ") { it.name.lowercase() }}")

        val env = AndroidEnvironment.getInstance(project)
        val device = AndroidContextResolver.getInstance(project).resolve().device?.takeIf { it.state.isUsable }
            ?: return err("No usable device is connected.")

        // Read the current values *first*; without them the change cannot be undone.
        val state = DeviceActions.stateProbes(device.serial).associate { (key, probe) ->
            key to run(env, probe, 5000)?.takeIf { it.ok }?.stdout?.trim()
        }
        val plan = DeviceRecipes.plan(recipe, device.serial, state)

        return json {
            addProperty("recipe", recipe.label)
            addProperty("description", DeviceRecipes.describe(plan))
            addProperty("revertible", plan.isRevertible)
            add("apply", JsonArray().apply { plan.apply.forEach { add(commandOf(it)) } })
            add("revert", JsonArray().apply { plan.revert.forEach { add(commandOf(it)) } })
            add("unreadable", JsonArray().apply { plan.unreadable.forEach { add(it) } })
            if (!plan.isRevertible) {
                addProperty(
                    "warning",
                    "Do not apply this: the current value of ${plan.unreadable.joinToString(", ")} " +
                        "could not be read, so the device could not be put back.",
                )
            }
        }
    }

    // ---- helpers ----

    private fun pidOf(env: AndroidEnvironment, serial: String, packageName: String): Int? {
        val r = run(env, DeviceActions.isRunning(serial, packageName), 4000) ?: return null
        return r.stdout.trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull()
    }

    private fun run(env: AndroidEnvironment, action: DeviceAction, timeoutMs: Int) =
        env.adb(*action.args.toTypedArray(), timeoutMs = timeoutMs)

    private fun commandOf(action: DeviceAction) = "adb " + action.args.joinToString(" ")

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

    private fun JsonObject.int(key: String): Int? =
        runCatching { get(key)?.takeIf { it.isJsonPrimitive }?.asInt }.getOrNull()

    private fun JsonObject.bool(key: String): Boolean? =
        runCatching { get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean }.getOrNull()
}
