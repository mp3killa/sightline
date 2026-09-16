package io.mp.sightline.ui

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import io.mp.sightline.settings.ClaudeSettings
import io.mp.sightline.ui.state.SessionPersistence
import java.time.LocalDate

/**
 * The one place that reads or writes a remembered session id.
 *
 * Deliberately small and deliberately the *only* door: the no-persistence standing decision survives
 * exactly as long as this stays the single writer, so anything wanting to remember something about a
 * conversation has to come through here and be argued about.
 *
 * Storage is the project's own `PropertiesComponent` (its `workspace.xml`) rather than the plugin's
 * application settings, because a session id belongs to one project and an application-level map of
 * project → id would outlive projects the user has deleted.
 */
class SessionMemory(private val project: Project) {

    private val props get() = PropertiesComponent.getInstance(project)

    /** Whether remembering is switched on at all. The consent dialog is what turns it on. */
    val enabled: Boolean
        get() = ClaudeSettings.getInstance().state.rememberSessions

    val savedId: String?
        get() = props.getValue(KEY_ID)?.takeIf { SessionPersistence.isValidId(it) }

    val savedDate: String?
        get() = props.getValue(KEY_DATE)

    /**
     * Records [sessionId] for this project, if remembering is on. Silently does nothing when it is off
     * — the caller is the panel, on every turn, and making it check first would be one more place the
     * rule could be forgotten.
     */
    fun remember(sessionId: String?) {
        if (!enabled) return
        if (!SessionPersistence.isValidId(sessionId)) return
        props.setValue(KEY_ID, sessionId)
        props.setValue(KEY_DATE, LocalDate.now().toString())
    }

    /**
     * Forgets whatever was stored. Called when the user turns the setting off and when they clear the
     * conversation, so "off" means the id is gone rather than merely unread — a stored identifier the
     * user believes they deleted is the failure that would make the consent dialog a lie.
     */
    fun forget() {
        props.unsetValue(KEY_ID)
        props.unsetValue(KEY_DATE)
    }

    private companion object {
        const val KEY_ID = "sightline.session.id"
        const val KEY_DATE = "sightline.session.savedOn"
    }
}
