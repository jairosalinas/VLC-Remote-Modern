package com.jairosalinas.vlcremote

import android.content.Context

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("vlc_remote_settings", Context.MODE_PRIVATE)

    data class Settings(
        val host: String = "",
        val port: Int = 8080,
        val password: String = "",
        val theme: ThemeMode = ThemeMode.SYSTEM
    )

    enum class ThemeMode { SYSTEM, LIGHT, DARK }

    fun load(): Settings {
        val theme = runCatching {
            ThemeMode.valueOf(prefs.getString("theme", ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
        }.getOrDefault(ThemeMode.SYSTEM)
        return Settings(
            host = prefs.getString("host", "") ?: "",
            port = prefs.getInt("port", 8080),
            password = prefs.getString("password", "") ?: "",
            theme = theme
        )
    }

    fun save(settings: Settings) {
        prefs.edit()
            .putString("host", settings.host.trim())
            .putInt("port", settings.port)
            .putString("password", settings.password)
            .putString("theme", settings.theme.name)
            .apply()
    }
}
