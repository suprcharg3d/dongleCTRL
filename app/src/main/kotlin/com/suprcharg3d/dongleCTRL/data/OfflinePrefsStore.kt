package com.suprcharg3d.dongleCTRL.data

import android.content.Context
import com.suprcharg3d.dongleCTRL.protocol.ProtocolFamily

enum class ThemeMode(val label: String) {
    SYSTEM("Follow System"),
    DARK("Dark"),
    LIGHT("Light")
}

data class AppPrefs(
    val autoApplyEq: Boolean = true,
    val autoOpenOnUsb: Boolean = false,
    val muteOnWrite: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.DARK
)

class OfflinePrefsStore(context: Context) {

    private val prefs = context.getSharedPreferences("donglectrl_prefs", Context.MODE_PRIVATE)

    fun load(): AppPrefs = AppPrefs(
        autoApplyEq = prefs.getBoolean(KEY_AUTO_APPLY, true),
        autoOpenOnUsb = prefs.getBoolean(KEY_AUTO_OPEN_USB, false),
        muteOnWrite = prefs.getBoolean(KEY_MUTE_ON_WRITE, false),
        themeMode = ThemeMode.entries.firstOrNull {
            it.name == prefs.getString(KEY_THEME, ThemeMode.DARK.name)
        } ?: ThemeMode.DARK
    )

    fun setAutoApplyEq(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_APPLY, enabled).apply()
    }

    fun setAutoOpenOnUsb(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_OPEN_USB, enabled).apply()
    }

    fun setMuteOnWrite(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MUTE_ON_WRITE, enabled).apply()
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
    }

    fun getProtocolOverride(vid: Int, pid: Int): ProtocolFamily? {
        val raw = prefs.getString("override_${vid}_$pid", null) ?: return null
        return ProtocolFamily.entries.firstOrNull { it.name == raw }
    }

    fun setProtocolOverride(vid: Int, pid: Int, family: ProtocolFamily?) {
        val editor = prefs.edit()
        if (family == null) {
            editor.remove("override_${vid}_$pid")
        } else {
            editor.putString("override_${vid}_$pid", family.name)
        }
        editor.apply()
    }

    companion object {
        private const val KEY_AUTO_APPLY = "auto_apply_eq"
        private const val KEY_AUTO_OPEN_USB = "auto_open_on_usb"
        private const val KEY_MUTE_ON_WRITE = "mute_on_write"
        private const val KEY_THEME = "theme_mode"
    }
}
