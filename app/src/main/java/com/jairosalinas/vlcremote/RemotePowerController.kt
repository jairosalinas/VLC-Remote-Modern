package com.jairosalinas.vlcremote

import android.content.Context
import android.net.Uri
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.File
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.concurrent.TimeUnit

class RemotePowerController(private val context: Context) {
    data class TestResult(val processRunning: Boolean)
    data class StartResult(val alreadyRunning: Boolean)

    class UnknownHostKeyException(
        val host: String,
        val port: Int,
        val fingerprint: String
    ) : Exception("Confirma la identidad SSH de $host:$port")

    class HostKeyChangedException(
        val host: String,
        val port: Int,
        val expected: String,
        val observed: String
    ) : Exception("La clave SSH de $host:$port cambió. No se realizó ninguna acción.")

    fun test(settings: SettingsRepository.Settings): TestResult {
        validate(settings)
        return withAuthenticatedClient(settings) { ssh ->
            val command = settings.sshCheckCommand.trim()
            TestResult(processRunning = command.isNotEmpty() && execute(ssh, command).exitStatus == 0)
        }
    }

    fun start(settings: SettingsRepository.Settings): StartResult {
        validate(settings)
        return withAuthenticatedClient(settings) { ssh ->
            val check = settings.sshCheckCommand.trim()
            val alreadyRunning = check.isNotEmpty() && execute(ssh, check).exitStatus == 0
            if (!alreadyRunning) {
                val start = settings.sshStartCommand.trim()
                require(start.isNotEmpty()) { "Configura el comando de inicio remoto" }
                executeChecked(ssh, start, "No se pudo iniciar VLC")
            }
            StartResult(alreadyRunning)
        }
    }

    fun stop(settings: SettingsRepository.Settings) {
        validate(settings)
        withAuthenticatedClient(settings) { ssh ->
            val check = settings.sshCheckCommand.trim()
            val running = check.isEmpty() || execute(ssh, check).exitStatus == 0
            if (running) {
                val stop = settings.sshStopCommand.trim()
                require(stop.isNotEmpty()) { "Configura el comando de cierre remoto" }
                executeChecked(ssh, stop, "No se pudo cerrar VLC")
            }
        }
    }

    private fun validate(settings: SettingsRepository.Settings) {
        require(settings.remotePowerEnabled) { "Activa el control Power en Configuración" }
        require(settings.resolvedSshHost().isNotBlank()) { "Configura el servidor SSH" }
        require(settings.sshPort in 1..65535) { "Puerto SSH inválido" }
        require(settings.sshUsername.isNotBlank()) { "Configura el usuario SSH" }
        when (settings.sshAuthMode) {
            SshAuthMode.PASSWORD -> require(settings.sshPassword.isNotEmpty()) { "Configura la contraseña SSH" }
            SshAuthMode.PRIVATE_KEY -> require(settings.sshPrivateKeyUri.isNotBlank()) { "Selecciona una clave privada SSH" }
        }
    }

    private fun <T> withAuthenticatedClient(
        settings: SettingsRepository.Settings,
        block: (SSHClient) -> T
    ): T {
        val host = settings.resolvedSshHost()
        val verifier = CapturingFingerprintVerifier(settings.sshHostFingerprint)
        val ssh = SSHClient()
        ssh.setConnectTimeout(CONNECT_TIMEOUT_MS)
        ssh.setTimeout(SOCKET_TIMEOUT_MS)
        ssh.addHostKeyVerifier(verifier)

        try {
            try {
                ssh.connect(host, settings.sshPort)
            } catch (error: Exception) {
                val observed = verifier.observedFingerprint
                if (!observed.isNullOrBlank()) {
                    if (settings.sshHostFingerprint.isBlank()) {
                        throw UnknownHostKeyException(host, settings.sshPort, observed)
                    }
                    if (observed != settings.sshHostFingerprint) {
                        throw HostKeyChangedException(
                            host,
                            settings.sshPort,
                            settings.sshHostFingerprint,
                            observed
                        )
                    }
                }
                throw error
            }

            authenticate(ssh, settings)
            return block(ssh)
        } finally {
            runCatching { ssh.disconnect() }
            runCatching { ssh.close() }
        }
    }

    private fun authenticate(ssh: SSHClient, settings: SettingsRepository.Settings) {
        when (settings.sshAuthMode) {
            SshAuthMode.PASSWORD -> {
                val password = settings.sshPassword.toCharArray()
                try {
                    ssh.authPassword(settings.sshUsername.trim(), password)
                } finally {
                    password.fill('\u0000')
                }
            }

            SshAuthMode.PRIVATE_KEY -> authenticatePrivateKey(ssh, settings)
        }
    }

    private fun authenticatePrivateKey(ssh: SSHClient, settings: SettingsRepository.Settings) {
        val uri = Uri.parse(settings.sshPrivateKeyUri)
        val temp = File.createTempFile("vlc_remote_key_", ".key", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            } ?: error("No se pudo abrir la clave privada SSH")

            val keyProvider = if (settings.sshPrivateKeyPassphrase.isEmpty()) {
                ssh.loadKeys(temp.absolutePath)
            } else {
                val passphrase = settings.sshPrivateKeyPassphrase.toCharArray()
                try {
                    ssh.loadKeys(temp.absolutePath, passphrase)
                } finally {
                    passphrase.fill('\u0000')
                }
            }
            ssh.authPublickey(settings.sshUsername.trim(), keyProvider)
        } finally {
            runCatching {
                if (temp.exists()) {
                    temp.writeBytes(ByteArray(temp.length().coerceAtMost(MAX_KEY_WIPE_BYTES).toInt()))
                    temp.delete()
                }
            }
        }
    }

    private fun executeChecked(ssh: SSHClient, command: String, message: String): CommandResult {
        val result = execute(ssh, command)
        if (result.exitStatus != 0) {
            val detail = result.stderr.ifBlank { result.stdout }.trim().take(300)
            error(if (detail.isBlank()) "$message (código ${result.exitStatus})" else "$message: $detail")
        }
        return result
    }

    private fun execute(ssh: SSHClient, command: String): CommandResult {
        ssh.startSession().use { session ->
            val remote = session.exec(command)
            remote.join(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val exit = remote.exitStatus ?: error("El comando SSH excedió ${COMMAND_TIMEOUT_SECONDS}s")
            val stdout = remote.inputStream.bufferedReader().use { it.readText() }
            val stderr = remote.errorStream.bufferedReader().use { it.readText() }
            return CommandResult(exit, stdout, stderr)
        }
    }

    private data class CommandResult(
        val exitStatus: Int,
        val stdout: String,
        val stderr: String
    )

    private class CapturingFingerprintVerifier(
        private val expected: String
    ) : HostKeyVerifier {
        @Volatile var observedFingerprint: String? = null
            private set

        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
            val actual = sha256Fingerprint(key)
            observedFingerprint = actual
            return expected.isNotBlank() && constantTimeEquals(expected, actual)
        }

        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val SOCKET_TIMEOUT_MS = 12_000
        private const val COMMAND_TIMEOUT_SECONDS = 12L
        private const val MAX_KEY_WIPE_BYTES = 1_048_576L

        internal fun sha256Fingerprint(key: PublicKey): String {
            val wireKey = Buffer.PlainBuffer().putPublicKey(key).getCompactData()
            val digest = MessageDigest.getInstance("SHA-256").digest(wireKey)
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        }

        internal fun constantTimeEquals(expected: String, actual: String): Boolean {
            return MessageDigest.isEqual(
                expected.toByteArray(Charsets.UTF_8),
                actual.toByteArray(Charsets.UTF_8)
            )
        }
    }
}
