package io.mp.claudecodepanel.ide

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import io.mp.claudecodepanel.interaction.AskUserQuestionResponseBuilder
import io.mp.claudecodepanel.ui.SightlineUiState
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.swing.JComponent

/**
 * Guards the sandbox-only test bridge. Enabled ONLY when `-Dsightline.testBridge=true` is passed to the
 * IDE JVM — which no normal install or Marketplace build ever sets — so the interaction-mutation and
 * screenshot tools are never present in production `tools/list`.
 */
object TestBridgeGuard {
    // Accept either the JVM system property or a process env var. The env var is how `runIde -PtestBridge`
    // passes it, because adding a JVM arg to that task drops the 2026.1 platform's bootclasspath.
    fun isEnabled(): Boolean =
        java.lang.Boolean.getBoolean("sightline.testBridge") || System.getenv("SIGHTLINE_TEST_BRIDGE") == "true"
}

/** A tool result that may carry a PNG image in addition to text. */
class BridgeResult(val text: String, val imagePng: ByteArray? = null)

/**
 * Sandbox-only MCP tools that let an automated driver **inspect and resolve pending interactions and
 * capture the tool window without clicking pixels** — by driving the same [ApprovalCoordinator] /
 * [DiffReviewCoordinator] the real UI uses. This is deliberately NOT a bypass: it runs the identical
 * production handlers. Every simulated decision is audit-logged (ids/decisions only, never content).
 *
 * Exposed only when [TestBridgeGuard.isEnabled]; see docs/TESTING.md.
 */
class SightlineTestBridge(private val project: Project) {

    private val approvals get() = project.getService(ApprovalCoordinator::class.java)
    private val diffs get() = project.getService(DiffReviewCoordinator::class.java)
    private val questions get() = project.getService(QuestionCoordinator::class.java)
    private val ui get() = project.getService(SightlineUiState::class.java)

    private val toolNames = setOf(
        "sightline.test.get_ui_state",
        "sightline.test.list_pending_interactions",
        "sightline.test.respond_permission",
        "sightline.test.respond_diff",
        "sightline.test.simulate_question",
        "sightline.test.respond_question",
        "sightline.test.capture_tool_window",
    )

    fun handles(name: String): Boolean = TestBridgeGuard.isEnabled() && name in toolNames

    /** Appends the test tool definitions to [tools] when the bridge is enabled. */
    fun addToolDefs(tools: JsonArray) {
        if (!TestBridgeGuard.isEnabled()) return
        thisLogger().warn("Sightline TEST BRIDGE enabled (-Dsightline.testBridge=true) — sandbox use only")
        tools.add(def("sightline.test.get_ui_state", "TEST-ONLY: structured tool-window state (workspace, session, pending counts)."))
        tools.add(def("sightline.test.list_pending_interactions", "TEST-ONLY: pending approvals and diff reviews with opaque ids + available actions."))
        tools.add(def("sightline.test.respond_permission", "TEST-ONLY: resolve a pending approval. args {interactionId, decision: ALLOW|ALLOW_ALWAYS|DENY}.",
            props("interactionId" to "string", "decision" to "string")))
        tools.add(def("sightline.test.respond_diff", "TEST-ONLY: resolve a pending diff review. args {interactionId, decision: ACCEPT|REJECT}.",
            props("interactionId" to "string", "decision" to "string")))
        tools.add(def("sightline.test.simulate_question", "TEST-ONLY: render a synthetic AskUserQuestion (drives the real UI path). args {input: <AskUserQuestion tool input>}.",
            props("input" to "object")))
        tools.add(def("sightline.test.respond_question", "TEST-ONLY: answer/cancel a pending AskUserQuestion. args {interactionId, answers?: {\"<question text>\": [\"<label>\"]}, cancel?: bool}.",
            props("interactionId" to "string", "answers" to "object", "cancel" to "boolean")))
        tools.add(def("sightline.test.capture_tool_window", "TEST-ONLY: PNG screenshot of the tool window (image content + dimensions)."))
    }

    fun call(name: String, args: JsonObject): BridgeResult = when (name) {
        "sightline.test.get_ui_state" -> BridgeResult(getUiState())
        "sightline.test.list_pending_interactions" -> BridgeResult(listPending())
        "sightline.test.respond_permission" -> BridgeResult(respondPermission(args))
        "sightline.test.respond_diff" -> BridgeResult(respondDiff(args))
        "sightline.test.simulate_question" -> BridgeResult(simulateQuestion(args))
        "sightline.test.respond_question" -> BridgeResult(respondQuestion(args))
        "sightline.test.capture_tool_window" -> captureToolWindow()
        else -> BridgeResult(err("unknown test tool: $name"))
    }

    // ---- tool implementations ----

    private fun getUiState(): String = JsonObject().apply {
        val s = ui
        addProperty("toolWindowVisible", s.toolWindowVisible)
        addProperty("workspace", s.workspace)
        addProperty("sessionState", s.sessionState)
        addProperty("pendingApprovals", approvals.listPending().size)
        addProperty("pendingDiffs", diffs.listPending().size)
    }.toString()

    private fun listPending(): String {
        val arr = JsonArray()
        for (a in approvals.listPending()) {
            arr.add(JsonObject().apply {
                addProperty("id", a.id)
                addProperty("type", "TOOL_PERMISSION")
                addProperty("toolName", a.toolName)
                addProperty("title", a.title)
                a.targetPath?.let { addProperty("targetPath", it) }
                add("availableActions", JsonArray().apply {
                    add("ALLOW"); if (a.canAllowAlways) add("ALLOW_ALWAYS"); add("DENY")
                })
            })
        }
        for (d in diffs.listPending()) {
            arr.add(JsonObject().apply {
                addProperty("id", d.id)
                addProperty("type", "DIFF_REVIEW")
                addProperty("targetPath", d.path)
                add("availableActions", JsonArray().apply { add("ACCEPT"); add("REJECT") })
            })
        }
        for (q in questions.listPending()) {
            arr.add(JsonObject().apply {
                addProperty("id", q.id)
                addProperty("type", "QUESTION")
                add("questions", JsonArray().apply {
                    q.request.questions.forEach { question ->
                        add(JsonObject().apply {
                            addProperty("question", question.question)
                            question.header?.let { addProperty("header", it) }
                            addProperty("multiSelect", question.multiSelect)
                            add("options", JsonArray().apply { question.options.forEach { add(it.label) } })
                        })
                    }
                })
                add("availableActions", JsonArray().apply { add("ANSWER"); add("CANCEL") })
            })
        }
        return JsonObject().apply { add("interactions", arr) }.toString()
    }

    /** Inject a synthetic AskUserQuestion so the full UI path can be driven without a live Claude session. */
    private fun simulateQuestion(args: JsonObject): String {
        val inputEl = args.get("input")
        if (inputEl == null || !inputEl.isJsonObject) return err("input object required")
        val input = inputEl.asJsonObject
        val simulate = ui.askQuestionSimulator ?: return err("question simulator not wired (is the tool window open?)")
        simulate(input)
        thisLogger().warn("TEST BRIDGE simulate_question")
        return JsonObject().apply { addProperty("ok", true) }.toString()
    }

    /** Resolve a pending question through the SAME production builder + coordinator the real UI uses. */
    private fun respondQuestion(args: JsonObject): String {
        val id = args.str("interactionId") ?: return err("interactionId required")
        val pending = questions.get(id) ?: return err("no pending question with id=$id")
        if (args.has("cancel") && args.get("cancel").let { it.isJsonPrimitive && it.asBoolean }) {
            val ok = questions.respond(id, QuestionResolution.Cancelled)
            thisLogger().warn("TEST BRIDGE respond_question id=$id CANCEL -> ok=$ok")
            return JsonObject().apply { addProperty("ok", ok) }.toString()
        }
        val answersEl = args.get("answers")
        if (answersEl == null || !answersEl.isJsonObject) return err("answers object required (or cancel:true)")
        val answersArg = answersEl.asJsonObject
        val answers = LinkedHashMap<String, List<String>>()
        for ((q, v) in answersArg.entrySet()) {
            answers[q] = when {
                v.isJsonArray -> v.asJsonArray.mapNotNull { if (it.isJsonPrimitive) it.asString else null }
                v.isJsonPrimitive -> listOf(v.asString)
                else -> emptyList()
            }
        }
        val originalInput = runCatching { JsonParser.parseString(pending.originalInputJson).asJsonObject }.getOrNull()
            ?: return err("could not parse original question input")
        val updated = runCatching { AskUserQuestionResponseBuilder.build(originalInput, pending.request, answers).toString() }
            .getOrElse { return err("invalid answers: ${it.message}") }
        val ok = questions.respond(id, QuestionResolution.Answered(updated))
        thisLogger().warn("TEST BRIDGE respond_question id=$id ANSWER -> ok=$ok")
        return JsonObject().apply { addProperty("ok", ok); add("updatedInput", JsonParser.parseString(updated)) }.toString()
    }

    private fun respondPermission(args: JsonObject): String {
        val id = args.str("interactionId") ?: return err("interactionId required")
        val decision = args.str("decision")?.let { runCatching { ApprovalDecision.valueOf(it) }.getOrNull() }
            ?: return err("decision must be ALLOW | ALLOW_ALWAYS | DENY")
        val outcome = approvals.respond(id, decision)
        thisLogger().warn("TEST BRIDGE respond_permission id=$id decision=$decision -> ${outcome::class.simpleName}")
        return JsonObject().apply {
            addProperty("ok", outcome is ApprovalOutcome.Applied)
            addProperty("outcome", outcome::class.simpleName ?: "?")
        }.toString()
    }

    private fun respondDiff(args: JsonObject): String {
        val id = args.str("interactionId") ?: return err("interactionId required")
        val decision = args.str("decision")?.let { runCatching { DiffDecision.valueOf(it) }.getOrNull() }
            ?: return err("decision must be ACCEPT | REJECT")
        val ok = diffs.respond(id, decision)
        thisLogger().warn("TEST BRIDGE respond_diff id=$id decision=$decision -> ok=$ok")
        return JsonObject().apply { addProperty("ok", ok); addProperty("found", ok) }.toString()
    }

    private fun captureToolWindow(): BridgeResult {
        val component = ui.rootComponent ?: return BridgeResult(err("tool window not available"))
        val image = captureComponent(component) ?: return BridgeResult(err("capture failed"))
        val png = ByteArrayOutputStream().use { ImageIO.write(image, "png", it); it.toByteArray() }
        val meta = JsonObject().apply {
            addProperty("width", image.width); addProperty("height", image.height)
            addProperty("workspace", ui.workspace); addProperty("sessionState", ui.sessionState)
            addProperty("bytes", png.size)
        }.toString()
        return BridgeResult(meta, png)
    }

    private fun captureComponent(component: JComponent): BufferedImage? {
        var img: BufferedImage? = null
        ApplicationManager.getApplication().invokeAndWait {
            val w = component.width.coerceAtLeast(component.preferredSize.width).coerceAtLeast(1)
            val h = component.height.coerceAtLeast(component.preferredSize.height).coerceAtLeast(1)
            val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            try { component.printAll(g) } finally { g.dispose() }
            img = image
        }
        return img
    }

    // ---- helpers ----

    private fun def(name: String, desc: String, props: JsonObject? = null): JsonObject = JsonObject().apply {
        addProperty("name", name)
        addProperty("description", desc)
        add("inputSchema", JsonObject().apply {
            addProperty("type", "object"); add("properties", props ?: JsonObject())
        })
    }

    private fun props(vararg pairs: Pair<String, String>): JsonObject = JsonObject().apply {
        pairs.forEach { (k, type) -> add(k, JsonObject().apply { addProperty("type", type) }) }
    }

    private fun err(message: String): String =
        JsonObject().apply { addProperty("ok", false); addProperty("error", message) }.toString()

    private fun JsonObject.str(k: String): String? = if (has(k) && get(k).isJsonPrimitive) get(k).asString else null
}
