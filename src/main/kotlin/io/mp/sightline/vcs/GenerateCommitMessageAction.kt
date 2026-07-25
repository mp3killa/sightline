package io.mp.sightline.vcs

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.diff.impl.patch.FilePatch
import com.intellij.openapi.diff.impl.patch.IdeaTextPatchBuilder
import com.intellij.openapi.diff.impl.patch.UnifiedDiffWriter
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import java.io.StringWriter
import java.nio.file.Path

/**
 * "Generate commit message" in the commit tool window's message toolbar (`Vcs.MessageActionGroup`):
 * reads the changes being committed, builds their unified diff, and asks a fast model to draft the
 * message — then fills the commit field. The generation runs on a background task; the field is only
 * touched on the EDT. Failures surface as a balloon, never a silent no-op.
 */
class GenerateCommitMessageAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val ready = e.project != null && e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) != null
        e.presentation.isVisible = ready
        e.presentation.isEnabled = ready && !e.getData(VcsDataKeys.CHANGES).isNullOrEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val control = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) ?: return
        val changes = e.getData(VcsDataKeys.CHANGES)?.toList().orEmpty()
        if (changes.isEmpty()) {
            notify(project, "Select at least one change to describe.", NotificationType.WARNING)
            return
        }

        object : Task.Backgroundable(project, "Generating commit message…", true) {
            override fun run(indicator: ProgressIndicator) {
                val diff = buildDiff(project, changes)
                if (diff.isBlank()) {
                    notify(project, "Couldn't read a diff for the selected changes.", NotificationType.WARNING)
                    return
                }
                when (val r = CommitMessageGenerator.generate(project.basePath, diff)) {
                    is CommitMessageGenerator.Result.Ok ->
                        ApplicationManager.getApplication().invokeLater { control.setCommitMessage(r.message) }
                    is CommitMessageGenerator.Result.Err ->
                        notify(project, r.reason, NotificationType.ERROR)
                }
            }
        }.queue()
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
