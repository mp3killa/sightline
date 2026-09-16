package io.mp.sightline.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Editor-side entry points into Sightline, so a question about the code you are looking at does not
 * start with copying it into a chat box.
 *
 * Both actions are registered with **no default keyboard shortcut**. An IntelliJ keymap is dense and
 * already personal; a plugin that claims a chord it likes will collide with somebody's binding on some
 * fraction of installs, and the user gets a broken shortcut they did not ask for. These are
 * discoverable through Find Action and bindable in Settings → Keymap, which is where a user expects to
 * decide this.
 *
 * The reference syntax is the CLI's own, and it is **verified, not assumed**: against 2.1.235,
 * `@sample.txt` and `@r.txt#L2-3` were both expanded by the CLI before the model saw them — the reply
 * quoted the exact lines with no `Read` tool call. So the text inserted here is a real reference, not a
 * decorative string the model has to go and resolve.
 */
sealed class SightlineEditorAction(text: String, description: String) : AnAction(text, description, null) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    protected fun openPanelThen(project: Project, insert: (String) -> Unit, text: String) {
        val ui = project.getService(SightlineUiState::class.java)
        val window = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
        // Activating creates the panel on first use, so the callback that receives the text may not
        // exist until after this runs — hence the insert inside the activation callback rather than
        // before it. Without that, the first invocation on a fresh IDE silently did nothing.
        if (window == null) { insert(text); return }
        window.activate({ (ui?.insertIntoComposer ?: insert)(text) }, true, true)
    }

    companion object {
        const val TOOL_WINDOW_ID = "Sightline"

        /**
         * `@path#L<start>-<end>` for a selection, `@path` for none. Line numbers are 1-based because
         * that is what the editor shows and what the CLI expects; the document is 0-based.
         */
        fun referenceFor(project: Project, file: VirtualFile, editor: Editor?): String {
            val base = project.basePath
            val path = file.path.let { p ->
                if (base != null && p.startsWith(base)) p.removePrefix(base).trimStart('/') else p
            }
            val selection = editor?.selectionModel?.takeIf { it.hasSelection() } ?: return "@$path"
            val doc = editor.document
            val start = doc.getLineNumber(selection.selectionStart) + 1
            val end = doc.getLineNumber(selection.selectionEnd) + 1
            return if (start == end) "@$path#L$start" else "@$path#L$start-$end"
        }
    }
}

/** Opens the Sightline tool window and puts the caret in the composer. */
class OpenSightlineAction : SightlineEditorAction(
    "Open Sightline",
    "Open the Sightline tool window and focus its composer",
) {
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.project != null }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        openPanelThen(project, { }, "")
    }
}

/**
 * Adds the current file — or the selected lines — to the composer as a reference, then focuses it.
 *
 * Deliberately a *reference*, not the code itself: pasting the lines into the prompt would send a
 * snapshot that is stale the moment the file changes and costs context proportional to the selection,
 * while `@path#L10-20` lets the CLI read the current file at send time.
 */
class AddSelectionToSightlineAction : SightlineEditorAction(
    "Add Selection to Sightline",
    "Reference the selected lines (or the whole file) in the Sightline composer",
) {
    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = e.project != null && file != null && !file.isDirectory
        val hasSelection = e.getData(CommonDataKeys.EDITOR)?.selectionModel?.hasSelection() == true
        e.presentation.text = if (hasSelection) "Add Selection to Sightline" else "Add File to Sightline"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val reference = referenceFor(project, file, e.getData(CommonDataKeys.EDITOR))
        openPanelThen(project, { }, reference)
    }
}
