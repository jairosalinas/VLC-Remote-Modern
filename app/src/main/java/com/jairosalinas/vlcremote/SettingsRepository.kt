package com.jairosalinas.vlcremote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Settings(
        val host: String = "",
        val port: Int = 8080,
        val password: String = "",
        val theme: ThemeMode = ThemeMode.SYSTEM
    )

    enum class ThemeMode { SYSTEM, LIGHT, DARK }

    fun load(): Settings {
        val theme = runCatching {
            ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
        }.getOrDefault(ThemeMode.SYSTEM)

        return Settings(
            host = prefs.getString(KEY_HOST, "") ?: "",
            port = prefs.getInt(KEY_PORT, 8080),
            password = loadPassword(),
            theme = theme
        )
    }

    fun save(settings: Settings) {
        prefs.edit {
            putString(KEY_HOST, settings.host.trim())
            putInt(KEY_PORT, settings.port)
            putString(KEY_THEME, settings.theme.name)
        }
        savePassword(settings.password)
    }

    private fun loadPassword(): String {
        val ciphertext = prefs.getString(KEY_PASSWORD_CIPHERTEXT, null)
        val iv = prefs.getString(KEY_PASSWORD_IV, null)
        if (!ciphertext.isNullOrEmpty() && !iv.isNullOrEmpty()) {
            return runCatching { decrypt(ciphertext, iv) }.getOrDefault("")
        }

        // Transparent migration from alpha/RC1, where the password was plain SharedPreferences.
        val legacy = prefs.getString(KEY_LEGACY_PASSWORD, "") ?: ""
        if (legacy.isNotEmpty()) {
            runCatching { savePassword(legacy) }
            return legacy
        }
        return ""
    }

    private fun savePassword(password: String) {
        if (password.isEmpty()) {
            prefs.edit {
                remove(KEY_PASSWORD_CIPHERTEXT)
                remove(KEY_PASSWORD_IV)
                remove(KEY_LEGACY_PASSWORD)
            }
            return
        }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        prefs.edit {
            putString(KEY_PASSWORD_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            putString(KEY_PASSWORD_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            remove(KEY_LEGACY_PASSWORD)
        }
    }

    private fun decrypt(ciphertext: String, iv: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
        )
        val plaintext = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP))
        return plaintext.toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "vlc_remote_settings"
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_THEME = "theme"
        const val KEY_LEGACY_PASSWORD = "password"
        const val KEY_PASSWORD_CIPHERTEXT = "password_ciphertext_v2"
        const val KEY_PASSWORD_IV = "password_iv_v2"
        const val KEY_ALIAS = "vlc_remote_settings_aes_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
