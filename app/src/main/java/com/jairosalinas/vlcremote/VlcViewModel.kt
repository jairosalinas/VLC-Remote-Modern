package com.jairosalinas.vlcremote

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

class VlcViewModel(application: Application) : AndroidViewModel(application) {
    data class PendingSshHostKey(
        val host: String,
        val port: Int,
        val fingerprint: String,
        val changed: Boolean,
        val previousFingerprint: String = ""
    )

    data class UiState(
        val settings: SettingsRepository.Settings = SettingsRepository.Settings(),
        val connected: Boolean = false,
        val connectionLabel: String = "Sin configurar",
        val state: String = "unknown",
        val title: String = "Nada reproduciéndose",
        val timeSeconds: Int = 0,
        val lengthSeconds: Int = 0,
        val position: Float = 0f,
        val volume: Int = 0,
        val muted: Boolean = false,
        val currentPlaylistId: Int = -1,
        val playlist: List<VlcHttpClient.PlaylistItem> = emptyList(),
        val loadingPlaylist: Boolean = false,
        val browserUri: String = "file://~",
        val browserEntries: List<VlcHttpClient.BrowserEntry> = emptyList(),
        val loadingBrowser: Boolean = false,
        val phoneShareStarting: Boolean = false,
        val phoneShareRunning: Boolean = false,
        val phoneShareUrl: String? = null,
        val phoneShareFileName: String? = null,
        val remotePowerBusy: Boolean = false,
        val sshStatusLabel: String? = null,
        val pendingSshHostKey: PendingSshHostKey? = null,
        val lastError: String? = null
    )

    private enum class PendingSshAction { TEST, START, STOP }

    private val settingsRepository = SettingsRepository(application)
    private val remotePowerController = RemotePowerController(application)
    private val _uiState = MutableStateFlow(UiState(settings = settingsRepository.load()))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    @Volatile private var client: VlcHttpClient? = null
    private var pollingJob: Job? = null
    private var lastNonZeroVolume = 256
    private var pendingSshAction: PendingSshAction? = null

    init {
        val saved = _uiState.value.settings
        if (saved.host.isNotBlank()) {
            createClient(saved)
            startPolling()
        }

        viewModelScope.launch {
            PhoneMediaShareService.state.collect { share ->
                _uiState.value = _uiState.value.copy(
                    phoneShareStarting = share.starting,
                    phoneShareRunning = share.running,
                    phoneShareUrl = share.url,
                    phoneShareFileName = share.fileName,
                    lastError = share.error ?: _uiState.value.lastError
                )
            }
        }
    }

    fun saveSettings(settings: SettingsRepository.Settings, testConnection: Boolean = true) {
        val old = _uiState.value.settings
        var normalized = settings.copy(
            host = settings.host.trim(),
            sshHost = settings.sshHost.trim(),
            sshUsername = settings.sshUsername.trim(),
            sshHostFingerprint = settings.sshHostFingerprint.trim()
        )

        val validation = validateSettings(normalized)
        if (validation != null) {
            setError(validation)
            return
        }

        val oldSshIdentity = "${old.resolvedSshHost()}:${old.sshPort}"
        val newSshIdentity = "${normalized.resolvedSshHost()}:${normalized.sshPort}"
        if (oldSshIdentity != newSshIdentity) {
            normalized = normalized.copy(sshHostFingerprint = "")
        }

        settingsRepository.save(normalized)
        _uiState.value = _uiState.value.copy(
            settings = normalized,
            sshStatusLabel = if (oldSshIdentity != newSshIdentity) null else _uiState.value.sshStatusLabel,
            pendingSshHostKey = null,
            lastError = null
        )
        pendingSshAction = null
        createClient(normalized)
        startPolling()
        if (testConnection) refreshStatus(announce = true)
    }

    private fun validateSettings(settings: SettingsRepository.Settings): String? {
        if (settings.host.isBlank()) return "Escribe la IP o hostname del equipo que ejecuta VLC"
        if (settings.port !in 1..65535) return "Puerto HTTP inválido"
        if (!settings.remotePowerEnabled) return null
        if (settings.resolvedSshHost().isBlank()) return "Configura el servidor SSH"
        if (settings.sshPort !in 1..65535) return "Puerto SSH inválido"
        if (settings.sshUsername.isBlank()) return "Configura el usuario SSH"
        if (settings.sshStartCommand.isBlank()) return "Configura el comando de inicio remoto"
        if (settings.sshStopCommand.isBlank()) return "Configura el comando de cierre remoto"
        if (settings.sshCheckCommand.isBlank()) return "Configura el comando de detección de VLC"
        return when (settings.sshAuthMode) {
            SshAuthMode.PASSWORD -> if (settings.sshPassword.isEmpty()) "Configura la contraseña SSH" else null
            SshAuthMode.PRIVATE_KEY -> if (settings.sshPrivateKeyUri.isBlank()) "Selecciona una clave privada SSH" else null
        }
    }

    fun reconnect() {
        createClient(_uiState.value.settings)
        startPolling()
        refreshStatus(announce = true)
        refreshPlaylist()
    }

    private fun createClient(settings: SettingsRepository.Settings) {
        if (settings.host.isBlank()) return
        client = VlcHttpClient(settings.host, settings.port, settings.password)
        _uiState.value = _uiState.value.copy(
            connectionLabel = "Configurado: ${settings.host}:${settings.port}",
            connected = false
        )
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            var ticks = 0
            while (isActive) {
                refreshStatus()
                if (ticks % 4 == 0) refreshPlaylist(silent = true)
                ticks++
                delay(2500)
            }
        }
    }

    fun refreshStatus(announce: Boolean = false) {
        val active = client ?: return
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { active.getStatus() } }
                .onSuccess { status ->
                    if (status.volume > 0) lastNonZeroVolume = status.volume
                    _uiState.value = _uiState.value.copy(
                        connected = true,
                        connectionLabel = if (announce) "Conexión correcta" else "Conectado • ${status.state}",
                        state = status.state,
                        title = status.title,
                        timeSeconds = status.timeSeconds,
                        lengthSeconds = status.lengthSeconds,
                        position = status.position.toFloat().coerceIn(0f, 1f),
                        volume = status.volume.coerceIn(0, 512),
                        muted = status.volume == 0,
                        currentPlaylistId = status.currentPlaylistId,
                        lastError = null
                    )
                }
                .onFailure { error ->
                    if (announce) handleFailure(error) else markVlcUnavailable()
                }
        }
    }

    private fun markVlcUnavailable(label: String = "VLC no disponible") {
        _uiState.value = _uiState.value.copy(
            connected = false,
            connectionLabel = label,
            state = "unknown",
            title = "Nada reproduciéndose",
            timeSeconds = 0,
            lengthSeconds = 0,
            position = 0f,
            currentPlaylistId = -1
        )
    }

    fun refreshPlaylist(silent: Boolean = false) {
        val active = client ?: return
        if (!silent) _uiState.value = _uiState.value.copy(loadingPlaylist = true)

        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { active.getPlaylist() } }
                .onSuccess { received ->
                    _uiState.value = _uiState.value.copy(
                        playlist = received,
                        loadingPlaylist = false,
                        lastError = null
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(loadingPlaylist = false)
                    if (!silent) handleFailure(it)
                }
        }
    }

    fun testSsh(settings: SettingsRepository.Settings) {
        saveSettings(settings, testConnection = false)
        if (_uiState.value.lastError != null) return
        executeRemotePowerAction(PendingSshAction.TEST)
    }

    fun toggleRemotePower() {
        if (_uiState.value.remotePowerBusy) return
        val settings = _uiState.value.settings
        if (!settings.remotePowerEnabled) {
            setError("Activa el control Power en Configuración")
            return
        }
        executeRemotePowerAction(if (_uiState.value.connected) PendingSshAction.STOP else PendingSshAction.START)
    }

    private fun executeRemotePowerAction(action: PendingSshAction) {
        val settings = _uiState.value.settings
        val validation = validateSettings(settings)
        if (validation != null) {
            setError(validation)
            return
        }

        pendingSshAction = action
        _uiState.value = _uiState.value.copy(
            remotePowerBusy = true,
            sshStatusLabel = when (action) {
                PendingSshAction.TEST -> "Probando conexión SSH…"
                PendingSshAction.START -> "Iniciando VLC…"
                PendingSshAction.STOP -> "Cerrando VLC…"
            },
            lastError = null
        )

        viewModelScope.launch {
            runCatching {
                when (action) {
                    PendingSshAction.TEST -> {
                        val result = withContext(Dispatchers.IO) { remotePowerController.test(settings) }
                        _uiState.value = _uiState.value.copy(
                            remotePowerBusy = false,
                            sshStatusLabel = if (result.processRunning) {
                                "SSH correcto • VLC está ejecutándose"
                            } else {
                                "SSH correcto • VLC no está ejecutándose"
                            }
                        )
                    }

                    PendingSshAction.START -> {
                        val result = withContext(Dispatchers.IO) { remotePowerController.start(settings) }
                        _uiState.value = _uiState.value.copy(
                            sshStatusLabel = if (result.alreadyRunning) {
                                "VLC ya estaba abierto • esperando HTTP…"
                            } else {
                                "VLC iniciado • esperando HTTP…"
                            }
                        )
                        if (!waitForVlcAvailability(expected = true, timeoutMs = 20_000L)) {
                            error("VLC se inició por SSH, pero la interfaz HTTP no respondió en 20 segundos")
                        }
                        _uiState.value = _uiState.value.copy(
                            remotePowerBusy = false,
                            sshStatusLabel = "VLC iniciado correctamente"
                        )
                        refreshStatus()
                        refreshPlaylist()
                    }

                    PendingSshAction.STOP -> {
                        withContext(Dispatchers.IO) { remotePowerController.stop(settings) }
                        if (!waitForVlcAvailability(expected = false, timeoutMs = 15_000L)) {
                            error("Se envió el cierre por SSH, pero VLC sigue respondiendo")
                        }
                        markVlcUnavailable("VLC cerrado")
                        _uiState.value = _uiState.value.copy(
                            remotePowerBusy = false,
                            sshStatusLabel = "VLC cerrado correctamente"
                        )
                    }
                }
            }.onFailure { error -> handleRemotePowerFailure(error, action) }
        }
    }

    private suspend fun waitForVlcAvailability(expected: Boolean, timeoutMs: Long): Boolean {
        val active = client ?: return !expected
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val available = runCatching { withContext(Dispatchers.IO) { active.getStatus() } }.isSuccess
            if (available == expected) return true
            delay(600)
        }
        return false
    }

    private fun handleRemotePowerFailure(error: Throwable, action: PendingSshAction) {
        when (error) {
            is RemotePowerController.UnknownHostKeyException -> {
                pendingSshAction = action
                _uiState.value = _uiState.value.copy(
                    remotePowerBusy = false,
                    pendingSshHostKey = PendingSshHostKey(
                        host = error.host,
                        port = error.port,
                        fingerprint = error.fingerprint,
                        changed = false
                    ),
                    sshStatusLabel = "Confirma la identidad del servidor SSH"
                )
            }

            is RemotePowerController.HostKeyChangedException -> {
                pendingSshAction = action
                _uiState.value = _uiState.value.copy(
                    remotePowerBusy = false,
                    pendingSshHostKey = PendingSshHostKey(
                        host = error.host,
                        port = error.port,
                        fingerprint = error.observed,
                        changed = true,
                        previousFingerprint = error.expected
                    ),
                    sshStatusLabel = "La identidad SSH del servidor cambió"
                )
            }

            else -> {
                pendingSshAction = null
                _uiState.value = _uiState.value.copy(
                    remotePowerBusy = false,
                    sshStatusLabel = "Error SSH",
                    lastError = friendlySshError(error)
                )
            }
        }
    }

    private fun friendlySshError(error: Throwable): String {
        val message = error.message?.trim().orEmpty()
        return when {
            message.contains("Exhausted available authentication methods", ignoreCase = true) ->
                "Autenticación SSH rechazada. Revisa usuario, contraseña o clave privada."
            message.contains("Connection refused", ignoreCase = true) ->
                "El servidor rechazó la conexión SSH. Revisa host y puerto."
            message.contains("timed out", ignoreCase = true) || message.contains("timeout", ignoreCase = true) ->
                "Tiempo de espera agotado al conectar por SSH."
            message.isNotBlank() -> message.take(350)
            else -> "Error SSH: ${error.javaClass.simpleName}"
        }
    }

    fun trustPendingSshHostKey() {
        val pending = _uiState.value.pendingSshHostKey ?: return
        val action = pendingSshAction ?: return
        val trusted = _uiState.value.settings.copy(sshHostFingerprint = pending.fingerprint)
        settingsRepository.save(trusted)
        _uiState.value = _uiState.value.copy(
            settings = trusted,
            pendingSshHostKey = null,
            sshStatusLabel = "Identidad SSH guardada"
        )
        pendingSshAction = null
        executeRemotePowerAction(action)
    }

    fun cancelPendingSshHostKey() {
        pendingSshAction = null
        _uiState.value = _uiState.value.copy(
            pendingSshHostKey = null,
            remotePowerBusy = false,
            sshStatusLabel = "Verificación SSH cancelada"
        )
    }

    fun clearTrustedSshHostKey() {
        val updated = _uiState.value.settings.copy(sshHostFingerprint = "")
        settingsRepository.save(updated)
        settingsRepository.clearSshHostFingerprint()
        _uiState.value = _uiState.value.copy(
            settings = updated,
            sshStatusLabel = "Identidad SSH olvidada"
        )
    }

    fun browseHome() = browse("file://~")

    fun browse(uri: String) {
        val active = requireClient() ?: return
        val target = uri.ifBlank { "file://~" }
        _uiState.value = _uiState.value.copy(loadingBrowser = true)

        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { active.browse(target) } }
                .onSuccess { entries ->
                    _uiState.value = _uiState.value.copy(
                        browserUri = target,
                        browserEntries = entries,
                        loadingBrowser = false,
                        lastError = null
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(loadingBrowser = false)
                    handleFailure(it)
                }
        }
    }

    fun openBrowserEntry(entry: VlcHttpClient.BrowserEntry) {
        val target = entry.uri.ifBlank { entry.path }
        if (entry.directory) browse(target) else playInput(target)
    }

    fun enqueueBrowserEntry(entry: VlcHttpClient.BrowserEntry) {
        playInput(entry.uri.ifBlank { entry.path }, enqueue = true)
    }

    fun startPhoneShare(uri: Uri) {
        val settings = _uiState.value.settings
        if (settings.host.isBlank()) {
            setError("Configura primero el servidor VLC")
            return
        }

        _uiState.value = _uiState.value.copy(phoneShareStarting = true, lastError = null)
        val application = getApplication<Application>()
        val intent = Intent(application, PhoneMediaShareService::class.java).apply {
            action = PhoneMediaShareService.ACTION_START
            putExtra(PhoneMediaShareService.EXTRA_URI, uri.toString())
            putExtra(PhoneMediaShareService.EXTRA_VLC_HOST, settings.host)
            putExtra(PhoneMediaShareService.EXTRA_VLC_PORT, settings.port)
        }
        application.startForegroundService(intent)

        viewModelScope.launch {
            val result = PhoneMediaShareService.state
                .dropWhile { it.error != null || it.running }
                .first { it.running || it.error != null }

            if (result.error != null) {
                setError(result.error)
            } else {
                result.url?.let { url -> playInput(url) }
            }
        }
    }

    fun stopPhoneShare() {
        val application = getApplication<Application>()
        application.stopService(Intent(application, PhoneMediaShareService::class.java))
        _uiState.value = _uiState.value.copy(
            phoneShareStarting = false,
            phoneShareRunning = false,
            phoneShareUrl = null,
            phoneShareFileName = null
        )
    }

    fun togglePlay() = runCommand(refresh = true) { it.togglePlay() }
    fun stop() = runCommand(refresh = true) { it.stop() }
    fun previous() = runCommand(refresh = true) { it.previous() }
    fun next() = runCommand(refresh = true) { it.next() }
    fun seekRelative(seconds: Int) = runCommand(refresh = true) { it.seekSeconds(seconds) }
    fun fullscreen() = runCommand(refresh = false) { it.toggleFullscreen() }

    fun seekTo(fraction: Float) {
        runCommand(refresh = true) { it.seekPercent(fraction.coerceIn(0f, 1f) * 100.0) }
    }

    fun setVolume(volume: Int) {
        val value = volume.coerceIn(0, 512)
        if (value > 0) lastNonZeroVolume = value
        _uiState.value = _uiState.value.copy(volume = value, muted = value == 0)
        runCommand(refresh = false) { it.setVolume(value) }
    }

    fun toggleMute() {
        val current = _uiState.value.volume
        if (current > 0) {
            lastNonZeroVolume = current
            setVolume(0)
        } else {
            setVolume(lastNonZeroVolume.coerceIn(1, 512))
        }
    }

    fun clearPlaylist() = runCommand(refresh = true, after = { refreshPlaylist() }) { it.clearPlaylist() }

    fun playPlaylistItem(item: VlcHttpClient.PlaylistItem) =
        runCommand(refresh = true) { it.playItem(item.id) }

    fun playInput(input: String, enqueue: Boolean = false) {
        val value = input.trim()
        if (value.isBlank()) {
            setError("Escribe una URL o ruta válida")
            return
        }

        runCommand(refresh = true, after = { refreshPlaylist() }) {
            if (enqueue) it.enqueueInput(value) else it.playInput(value)
        }
    }

    fun loadLocalPlaylist(uri: Uri) {
        val active = requireClient() ?: return

        viewModelScope.launch {
            runCatching {
                val entries = withContext(Dispatchers.IO) { readPlaylistEntries(uri) }
                require(entries.isNotEmpty()) { "La lista no contiene elementos reproducibles" }

                withContext(Dispatchers.IO) {
                    active.playInput(entries.first())
                    for (entry in entries.drop(1)) active.enqueueInput(entry)
                }
            }.onSuccess {
                refreshStatus()
                refreshPlaylist()
            }.onFailure(::handleFailure)
        }
    }

    private fun readPlaylistEntries(uri: Uri): List<String> {
        val resolver = getApplication<Application>().contentResolver
        val stream = resolver.openInputStream(uri) ?: error("No se pudo abrir la lista seleccionada")
        return BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
            PlaylistTextParser.parse(reader.lineSequence())
        }
    }

    private fun runCommand(
        refresh: Boolean,
        after: (() -> Unit)? = null,
        block: (VlcHttpClient) -> Unit
    ) {
        val active = requireClient() ?: return
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { block(active) } }
                .onSuccess {
                    if (refresh) refreshStatus()
                    after?.invoke()
                }
                .onFailure(::handleFailure)
        }
    }

    private fun requireClient(): VlcHttpClient? {
        return client ?: run {
            setError("Configura primero el servidor VLC")
            null
        }
    }

    fun consumeError() {
        _uiState.value = _uiState.value.copy(lastError = null)
    }

    private fun setError(message: String) {
        _uiState.value = _uiState.value.copy(lastError = message)
    }

    private fun handleFailure(error: Throwable) {
        val message = error.message ?: error.javaClass.simpleName
        _uiState.value = _uiState.value.copy(
            connected = false,
            connectionLabel = "Error de conexión",
            lastError = message
        )
    }
}
