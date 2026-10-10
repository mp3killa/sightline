package io.mp.sightline.ui

import javax.swing.UIManager

/**
 * The third half of switching theme in a headless render, alongside the editor scheme and
 * `JBColor.setDark` (see `ChatGalleryPreviewTest.applyTheme`).
 *
 * Text colours such as `UIUtil.getLabelForeground()` are *named* colours: they read the Swing UI
 * defaults first and use their own light/dark pair only when the key is absent. The test platform
 * installs the light theme's defaults, so from build 262 a "dark" render drew light-theme text (and a
 * light panel) on a
 * dark surface — near-invisible, in every preview and every Marketplace screenshot. Clearing the
 * foreground keys for a dark render lets each named colour fall back to its own pair, which
 * `JBColor.setDark` does switch. The originals are put back for a light render.
 */
internal object HeadlessTheme {
    private val FOREGROUND_KEYS = listOf(
        "Label.foreground", "Label.disabledForeground", "Label.infoForeground",
        "ContextHelp.foreground", "Component.infoForeground",
        "TextArea.foreground", "TextPane.foreground", "EditorPane.foreground", "TextField.foreground",
        "Button.foreground", "CheckBox.foreground", "RadioButton.foreground",
        "Panel.background", "Panel.foreground",
    )
    private val saved = HashMap<String, Any?>()

    fun applyUiDefaults(dark: Boolean) {
        val defaults = UIManager.getDefaults()
        if (dark) {
            for (k in FOREGROUND_KEYS) {
                if (k !in saved) saved[k] = defaults[k]
                defaults.remove(k)
            }
        } else {
            for ((k, v) in saved) if (v != null) defaults[k] = v
            saved.clear()
        }
    }
}
