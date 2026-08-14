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
        val theme: ThemeMode = ThemeMode.SYSTEM,
        val remotePowerEnabled: Boolean = false,
        val remoteServerPlatform: RemoteServerPlatform = RemoteServerPlatform.LINUX,
        val sshUseVlcHost: Boolean = true,
        val sshHost: String = "",
        val sshPort: Int = 22,
        val sshUsername: String = "",
        val sshAuthMode: SshAuthMode = SshAuthMode.PASSWORD,
        val sshPassword: String = "",
        val sshPrivateKeyUri: String = "",
        val sshPrivateKeyPassphrase: String = "",
        val sshStartCommand: String = RemoteLaunchProfiles.linux.startCommand,
        val sshStopCommand: String = RemoteLaunchProfiles.linux.stopCommand,
        val sshCheckCommand: String = RemoteLaunchProfiles.linux.checkCommand,
        val sshHostFingerprint: String = ""
    ) {
        fun resolvedSshHost(): String = if (sshUseVlcHost) host.trim() else sshHost.trim()
    }

    enum class ThemeMode { SYSTEM, LIGHT, DARK }

    fun load(): Settings {
        val theme = enumValueOrDefault(KEY_THEME, ThemeMode.SYSTEM)
        val platform = enumValueOrDefault(KEY_REMOTE_PLATFORM, RemoteServerPlatform.LINUX)
        val authMode = enumValueOrDefault(KEY_SSH_AUTH_MODE, SshAuthMode.PASSWORD)
        val defaults = RemoteLaunchProfiles.forPlatform(platform)

        return Settings(
            host = prefs.getString(KEY_HOST, "") ?: "",
            port = prefs.getInt(KEY_PORT, 8080),
            password = loadVlcPassword(),
            theme = theme,
            remotePowerEnabled = prefs.getBoolean(KEY_REMOTE_POWER_ENABLED, false),
            remoteServerPlatform = platform,
            sshUseVlcHost = prefs.getBoolean(KEY_SSH_USE_VLC_HOST, true),
            sshHost = prefs.getString(KEY_SSH_HOST, "") ?: "",
            sshPort = prefs.getInt(KEY_SSH_PORT, 22),
            sshUsername = prefs.getString(KEY_SSH_USERNAME, "") ?: "",
            sshAuthMode = authMode,
            sshPassword = loadSecret(KEY_SSH_PASSWORD_CIPHERTEXT, KEY_SSH_PASSWORD_IV),
            sshPrivateKeyUri = prefs.getString(KEY_SSH_PRIVATE_KEY_URI, "") ?: "",
            sshPrivateKeyPassphrase = loadSecret(KEY_SSH_KEY_PASSPHRASE_CIPHERTEXT, KEY_SSH_KEY_PASSPHRASE_IV),
            sshStartCommand = prefs.getString(KEY_SSH_START_COMMAND, defaults.startCommand) ?: defaults.startCommand,
            sshStopCommand = prefs.getString(KEY_SSH_STOP_COMMAND, defaults.stopCommand) ?: defaults.stopCommand,
            sshCheckCommand = prefs.getString(KEY_SSH_CHECK_COMMAND, defaults.checkCommand) ?: defaults.checkCommand,
            sshHostFingerprint = prefs.getString(KEY_SSH_HOST_FINGERPRINT, "") ?: ""
        )
    }

    fun save(settings: Settings) {
        prefs.edit {
            putString(KEY_HOST, settings.host.trim())
            putInt(KEY_PORT, settings.port)
            putString(KEY_THEME, settings.theme.name)
            putBoolean(KEY_REMOTE_POWER_ENABLED, settings.remotePowerEnabled)
            putString(KEY_REMOTE_PLATFORM, settings.remoteServerPlatform.name)
            putBoolean(KEY_SSH_USE_VLC_HOST, settings.sshUseVlcHost)
            putString(KEY_SSH_HOST, settings.sshHost.trim())
            putInt(KEY_SSH_PORT, settings.sshPort)
            putString(KEY_SSH_USERNAME, settings.sshUsername.trim())
            putString(KEY_SSH_AUTH_MODE, settings.sshAuthMode.name)
            putString(KEY_SSH_PRIVATE_KEY_URI, settings.sshPrivateKeyUri)
            putString(KEY_SSH_START_COMMAND, settings.sshStartCommand)
            putString(KEY_SSH_STOP_COMMAND, settings.sshStopCommand)
            putString(KEY_SSH_CHECK_COMMAND, settings.sshCheckCommand)
            putString(KEY_SSH_HOST_FINGERPRINT, settings.sshHostFingerprint.trim())
        }
        saveSecret(KEY_PASSWORD_CIPHERTEXT, KEY_PASSWORD_IV, settings.password)
        saveSecret(KEY_SSH_PASSWORD_CIPHERTEXT, KEY_SSH_PASSWORD_IV, settings.sshPassword)
        saveSecret(
            KEY_SSH_KEY_PASSPHRASE_CIPHERTEXT,
            KEY_SSH_KEY_PASSPHRASE_IV,
            settings.sshPrivateKeyPassphrase
        )
        prefs.edit { remove(KEY_LEGACY_PASSWORD) }
    }

    fun clearSshHostFingerprint() {
        prefs.edit { remove(KEY_SSH_HOST_FINGERPRINT) }
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(key: String, default: T): T {
        return runCatching {
            enumValueOf<T>(prefs.getString(key, default.name) ?: default.name)
        }.getOrDefault(default)
    }

    private fun loadVlcPassword(): String {
        val encrypted = loadSecret(KEY_PASSWORD_CIPHERTEXT, KEY_PASSWORD_IV)
        if (encrypted.isNotEmpty()) return encrypted

        // Transparent migration from alpha/RC1, where the VLC password was plain SharedPreferences.
        val legacy = prefs.getString(KEY_LEGACY_PASSWORD, "") ?: ""
        if (legacy.isNotEmpty()) {
            runCatching { saveSecret(KEY_PASSWORD_CIPHERTEXT, KEY_PASSWORD_IV, legacy) }
            prefs.edit { remove(KEY_LEGACY_PASSWORD) }
            return legacy
        }
        return ""
    }

    private fun loadSecret(ciphertextKey: String, ivKey: String): String {
        val ciphertext = prefs.getString(ciphertextKey, null)
        val iv = prefs.getString(ivKey, null)
        if (ciphertext.isNullOrEmpty() || iv.isNullOrEmpty()) return ""
        return runCatching { decrypt(ciphertext, iv) }.getOrDefault("")
    }

    private fun saveSecret(ciphertextKey: String, ivKey: String, value: String) {
        if (value.isEmpty()) {
            prefs.edit {
                remove(ciphertextKey)
                remove(ivKey)
            }
            return
        }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit {
            putString(ciphertextKey, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            putString(ivKey, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
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

        const val KEY_REMOTE_POWER_ENABLED = "remote_power_enabled"
        const val KEY_REMOTE_PLATFORM = "remote_platform"
        const val KEY_SSH_USE_VLC_HOST = "ssh_use_vlc_host"
        const val KEY_SSH_HOST = "ssh_host"
        const val KEY_SSH_PORT = "ssh_port"
        const val KEY_SSH_USERNAME = "ssh_username"
        const val KEY_SSH_AUTH_MODE = "ssh_auth_mode"
        const val KEY_SSH_PASSWORD_CIPHERTEXT = "ssh_password_ciphertext_v1"
        const val KEY_SSH_PASSWORD_IV = "ssh_password_iv_v1"
        const val KEY_SSH_PRIVATE_KEY_URI = "ssh_private_key_uri"
        const val KEY_SSH_KEY_PASSPHRASE_CIPHERTEXT = "ssh_key_passphrase_ciphertext_v1"
        const val KEY_SSH_KEY_PASSPHRASE_IV = "ssh_key_passphrase_iv_v1"
        const val KEY_SSH_START_COMMAND = "ssh_start_command"
        const val KEY_SSH_STOP_COMMAND = "ssh_stop_command"
        const val KEY_SSH_CHECK_COMMAND = "ssh_check_command"
        const val KEY_SSH_HOST_FINGERPRINT = "ssh_host_fingerprint"

        const val KEY_ALIAS = "vlc_remote_settings_aes_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
