package io.mp.claudecodepanel.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import java.awt.Component
import javax.swing.DefaultListCellRenderer
import javax.swing.JComponent
import javax.swing.JList

class ClaudeSettingsConfigurable : Configurable {

    private var dialog: DialogPanel? = null
    private val state get() = ClaudeSettings.getInstance().state

    override fun getDisplayName(): String = "Claude Code Panel"

    override fun createComponent(): JComponent {
        val models = listOf("", "opus", "sonnet", "haiku")
        val modes = listOf("default", "acceptEdits", "plan", "auto", "bypassPermissions")

        val p = panel {
            row("Claude command:") {
                textField()
                    .bindText({ state.claudeCommand ?: "claude" }, { state.claudeCommand = it })
                    .columns(COLUMNS_LARGE)
                    .comment(
                        "e.g. <code>claude</code>, <code>/usr/local/bin/claude</code>, or " +
                            "<code>npx @anthropic-ai/claude-code</code>. Keep <code>claude</code> to auto-detect the binary."
                    )
            }
            row("Model:") {
                comboBox(models)
                    .bindItem({ state.model ?: "" }, { state.model = it ?: "" })
                    .applyToComponent {
                        renderer = object : DefaultListCellRenderer() {
                            override fun getListCellRendererComponent(
                                list: JList<*>?, value: Any?, index: Int, sel: Boolean, focus: Boolean,
                            ): Component {
                                val label = if ((value as? String).isNullOrEmpty()) "Default (use CLI config)" else value.toString()
                                return super.getListCellRendererComponent(list, label, index, sel, focus)
                            }
                        }
                    }
                    .comment("Model for new sessions. \"Default\" keeps whatever the CLI is configured to use.")
            }
            row("Permission mode:") {
                comboBox(modes)
                    .bindItem({ state.permissionMode ?: "auto" }, { state.permissionMode = it ?: "auto" })
                    .comment(
                        "<b>auto</b> (default) approves safe actions and pauses for risky ones (needs Sonnet/Opus) · " +
                            "<b>default</b> asks before each tool · <b>acceptEdits</b> auto-accepts edits, asks for commands · " +
                            "<b>plan</b> read-only planning · <b>bypassPermissions</b> approves everything (use with care)."
                    )
            }
            row {
                checkBox("Stream partial messages (live typing effect)")
                    .bindSelected({ state.includePartialMessages }, { state.includePartialMessages = it })
            }
            row {
                checkBox("Show thinking & tool-call details by default (off = compact summary view)")
                    .bindSelected({ state.showDetails }, { state.showDetails = it })
            }
            row {
                checkBox("Ask for approval before running tools (inline Allow / Deny)")
                    .bindSelected({ state.interactiveApproval }, { state.interactiveApproval = it })
                    .comment("Uses the CLI permission protocol. Combine with a permission mode: Manual prompts for everything, Auto-accept edits only prompts for commands, Bypass never prompts.")
            }
            row {
                checkBox("IDE integration (share editor selection, open diffs in the native viewer)")
                    .bindSelected({ state.ideIntegration }, { state.ideIntegration = it })
                    .comment("Runs a local <code>ide</code> MCP server the CLI connects to. Restart a conversation (New) after changing this.")
            }
            group("Agent Activity Map") {
                row {
                    checkBox("Show the live Agent Activity Map while Claude works")
                        .bindSelected({ state.showActivityMap }, { state.showActivityMap = it })
                        .comment(
                            "A graph of what Claude is <i>observably</i> touching — files, commands, tests, errors — " +
                                "not the model's private reasoning.",
                        )
                }
                row {
                    checkBox("Reduce motion (static layout, no pulsing)")
                        .bindSelected({ state.activityReduceMotion }, { state.activityReduceMotion = it })
                }
                row("Max visible nodes:") {
                    textField()
                        .bindIntText({ state.activityMaxNodes }, { state.activityMaxNodes = it })
                        .columns(6)
                        .comment("Nodes rendered at once. Older nodes stay in the session but are hidden past this cap.")
                }
                row("Max retained nodes:") {
                    textField()
                        .bindIntText({ state.activityMaxRetained }, { state.activityMaxRetained = it })
                        .columns(6)
                        .comment("Session history size before the oldest non-pinned nodes are evicted.")
                }
            }
            row("Extra CLI args:") {
                textField()
                    .bindText({ state.extraArgs ?: "" }, { state.extraArgs = it })
                    .columns(COLUMNS_LARGE)
                    .comment("Advanced: appended to every <code>claude</code> invocation, space-separated.")
            }
        }
        dialog = p
        return p
    }

    override fun isModified(): Boolean = dialog?.isModified() ?: false
    override fun apply() { dialog?.apply() }
    override fun reset() { dialog?.reset() }
    override fun disposeUIResources() { dialog = null }
}
