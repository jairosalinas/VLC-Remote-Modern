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
        val lastError: String? = null
    )

    private val settingsRepository = SettingsRepository(application)
    private val _uiState = MutableStateFlow(UiState(settings = settingsRepository.load()))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    @Volatile private var client: VlcHttpClient? = null
    private var pollingJob: Job? = null
    private var lastNonZeroVolume = 256

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
        val normalized = settings.copy(host = settings.host.trim())
        if (normalized.host.isBlank()) {
            setError("Escribe la IP o hostname del equipo que ejecuta VLC")
            return
        }
        if (normalized.port !in 1..65535) {
            setError("Puerto inválido")
            return
        }

        settingsRepository.save(normalized)
        _uiState.value = _uiState.value.copy(settings = normalized, lastError = null)
        createClient(normalized)
        startPolling()
        if (testConnection) refreshStatus(announce = true)
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
                .onFailure(::handleFailure)
        }
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
