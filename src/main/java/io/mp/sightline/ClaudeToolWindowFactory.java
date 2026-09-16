package io.mp.sightline;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import io.mp.sightline.ui.ClaudePanel;
import org.jetbrains.annotations.NotNull;

/**
 * Registers the right-dock "Sightline" tool window. Kept intentionally small: it builds {@link
 * ClaudePanel}, wraps it in a single non-closeable content, and points keyboard focus at the composer.
 * The tool window already carries the "Sightline" display name, so the content title is left empty
 * (no duplicate tab).
 *
 * <p><b>Java, deliberately — this is the one file in the plugin that is.</b> Kotlin materialises a
 * delegating member for every default method of a Java interface it implements, so a Kotlin
 * {@code ToolWindowFactory} emits bridges calling {@code isApplicable}, {@code isDoNotActivateOnStart},
 * {@code getAnchor}, {@code getIcon} and {@code manage} — deprecated and experimental platform API we
 * never call. The Plugin Verifier reports each one, and a deprecated method the platform eventually
 * deletes would break those bridges at runtime even though nothing here uses it. Java inherits the
 * defaults instead of re-declaring them, so the references simply do not exist.
 */
public final class ClaudeToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ClaudePanel panel = new ClaudePanel(project, toolWindow.getDisposable());
        Content content = ContentFactory.getInstance().createContent(panel.getComponent(), "", false);
        content.setCloseable(false);
        content.setPreferredFocusableComponent(panel.preferredFocusComponent());
        toolWindow.getContentManager().addContent(content);
    }
}
