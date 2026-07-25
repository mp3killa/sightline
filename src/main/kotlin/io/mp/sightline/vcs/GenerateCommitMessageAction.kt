package io.mp.sightline.vcs

import com.intellij.ide.ActivityTracker
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Key
import com.intellij.openapi.diff.impl.patch.FilePatch
import com.intellij.openapi.diff.impl.patch.IdeaTextPatchBuilder
import com.intellij.openapi.diff.impl.patch.UnifiedDiffWriter
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import java.io.File
import java.io.StringWriter
import java.nio.file.Path

/**
 * "Generate commit message" in the commit tool window's message toolbar (`Vcs.MessageActionGroup`):
 * reads the changes being committed, builds their unified diff, and asks a fast model to draft the
 * message — then fills the commit field. The generation runs on a background task; the field is only
 * touched on the EDT. Failures surface as a balloon, never a silent no-op.
 */
class GenerateCommitMessageAction : AnAction() {

    private companion object {
        /** Per-project "a draft is generating" flag, so the button disables until it finishes. */
        val BUSY = Key.create<Boolean>("sightline.commitMessage.generating")
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val ready = project != null && e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) != null
        val busy = project?.getUserData(BUSY) == true
        e.presentation.isVisible = ready
        // Disabled while a draft is in flight, so a second click can't race the first.
        e.presentation.isEnabled = ready && !busy && !e.getData(VcsDataKeys.CHANGES).isNullOrEmpty()
        e.presentation.text = if (busy) "Generating Commit Message…" else "Generate Commit Message"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (project.getUserData(BUSY) == true) return
        val control = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) ?: return
        val changes = e.getData(VcsDataKeys.CHANGES)?.toList().orEmpty()
        if (changes.isEmpty()) {
            notify(project, "Select at least one change to describe.", NotificationType.WARNING)
            return
        }

        setBusy(project, true)
        object : Task.Backgroundable(project, "Generating commit message…", true) {
            override fun run(indicator: ProgressIndicator) {
                var message: String? = null
                var error: String? = null
                try {
                    val diff = buildDiff(project, changes)
                    if (diff.isBlank()) {
                        error = "Couldn't read a diff for the selected changes."
                    } else {
                        val style = CommitStyleScanner.scan(project.basePath?.let { File(it) })
                        when (val r = CommitMessageGenerator.generate(project.basePath, diff, style)) {
                            is CommitMessageGenerator.Result.Ok -> message = r.message
                            is CommitMessageGenerator.Result.Err -> error = r.reason
                        }
                    }
                } catch (t: Exception) {
                    error = t.message ?: "Failed to generate the commit message."
                }
                val finalMessage = message
                val finalError = error
                ApplicationManager.getApplication().invokeLater {
                    if (finalMessage != null) control.setCommitMessage(finalMessage)
                    else notify(project, finalError ?: "Failed to generate the commit message.", NotificationType.ERROR)
                    setBusy(project, false) // re-enable only now the field is populated (or the error shown)
                }
            }
        }.queue()
    }

    private fun setBusy(project: Project, busy: Boolean) {
        project.putUserData(BUSY, if (busy) true else null)
        ActivityTracker.getInstance().inc() // nudge the toolbar to re-run update() promptly
    }

    /** The unified diff of exactly the changes being committed (handles add/modify/delete uniformly). */
    private fun buildDiff(project: Project, changes: List<Change>): String = try {
        ApplicationManager.getApplication().runReadAction(
            Computable {
                val base = project.basePath ?: return@Computable ""
                val patches: List<FilePatch> = IdeaTextPatchBuilder.buildPatch(project, changes, Path.of(base), false)
                val sw = StringWriter()
                UnifiedDiffWriter.write(project, patches, sw, "\n", null)
                sw.toString()
            },
        )
    } catch (e: Exception) {
        ""
    }

    private fun notify(project: Project, text: String, type: NotificationType) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Sightline")
                .createNotification("Commit message", text, type)
                .notify(project)
        }
    }
}
