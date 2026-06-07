package com.lab.bracketlab

import android.content.SharedPreferences
import android.widget.TextView

object SettingKeys {
    const val PREFS_NAME = "hdr_last_settings"
    const val EXPOSURES = "exposures"
    const val CAMERA_ID = "camera_id"
    const val ISO = "iso"
    const val INTERVAL = "interval"
    const val FOCUS = "focus"
    const val DELAY = "delay"
    const val HIGHLIGHTS = "highlights"
    const val SHADOWS = "shadows"
    const val EV = "ev"
    const val OIS = "ois"
    const val FOLDER_MODE = "folder_mode"
    const val FOCUS_FRAMES = "focus_frames"
    const val WB_MODE = "wb_mode"
}

class UiSettingsStore(private val prefs: SharedPreferences) {
    /** Restores saved text values into their matching UI fields. */
    fun loadFields(fields: List<Pair<String, TextView>>) {
        fields.forEach { (key, field) ->
            prefs.getString(key, null)?.let { field.setText(it) }
        }
    }

    /** Saves the current text fields and toggle states in SharedPreferences. */
    fun saveFieldsAndToggles(
        fields: List<Pair<String, TextView>>,
        folderModeEnabled: Boolean,
        oisEnabled: Boolean
    ) {
        prefs.edit().apply {
            fields.forEach { (key, field) ->
                putString(key, field.text.toString())
            }
            putBoolean(SettingKeys.FOLDER_MODE, folderModeEnabled)
            putBoolean(SettingKeys.OIS, oisEnabled)
            apply()
        }
    }

    /** Loads whether captures should be grouped into per-sequence folders. */
    fun loadFolderMode(defaultValue: Boolean): Boolean =
        prefs.getBoolean(SettingKeys.FOLDER_MODE, defaultValue)

    /** Loads the persisted optical image stabilization preference. */
    fun loadOis(defaultValue: Boolean): Boolean =
        prefs.getBoolean(SettingKeys.OIS, defaultValue)

    /** Loads the persisted white-balance mode. */
    fun loadWbMode(defaultValue: Int): Int =
        prefs.getInt(SettingKeys.WB_MODE, defaultValue)

    /** Saves the selected white-balance mode immediately. */
    fun saveWbMode(mode: Int) {
        prefs.edit().putInt(SettingKeys.WB_MODE, mode).apply()
    }
}
