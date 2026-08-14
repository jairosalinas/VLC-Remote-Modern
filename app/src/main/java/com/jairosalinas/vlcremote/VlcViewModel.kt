package com.jairosalinas.vlcremote

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        val playlist: List<VlcHttpClient.PlaylistItem> = emptyList(),
        val loadingPlaylist: Boolean = false,
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
                        lastError = null
                    )
                }
                .onFailure { handleFailure(it) }
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

    fun togglePlay() = runCommand(refresh = true) { it.togglePlay() }
    fun stop() = runCommand(refresh = true) { it.stop() }
    fun previous() = runCommand(refresh = true) { it.previous() }
    fun next() = runCommand(refresh = true) { it.next() }
    fun seekRelative(seconds: Int) = runCommand(refresh = true) { it.seekSeconds(seconds) }
    fun fullscreen() = runCommand(refresh = false) { it.toggleFullscreen() }

    fun seekTo(fraction: Float) {
        val percent = (fraction.coerceIn(0f, 1f) * 100.0)
        runCommand(refresh = true) { it.seekPercent(percent) }
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
        val active = client ?: run {
            setError("Configura primero el servidor VLC")
            return
        }
        viewModelScope.launch {
            runCatching {
                val entries = withContext(Dispatchers.IO) { readPlaylistEntries(uri) }
                require(entries.isNotEmpty()) { "La lista no contiene elementos reproducibles" }
                withContext(Dispatchers.IO) {
                    active.playInput(entries.first())
                    entries.drop(1).forEach(active::enqueueInput)
                }
                entries.size
            }.onSuccess {
                refreshStatus()
                refreshPlaylist()
            }.onFailure { handleFailure(it) }
        }
    }

    private fun readPlaylistEntries(uri: Uri): List<String> {
        val resolver = getApplication<Application>().contentResolver
        val stream = resolver.openInputStream(uri) ?: error("No se pudo abrir la lista seleccionada")
        return BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).useLines { lines ->
            lines.map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { value ->
                    val equals = value.indexOf('=')
                    if (equals > 0 && value.substring(0, equals).matches(Regex("(?i)File\\d+"))) {
                        value.substring(equals + 1).trim()
                    } else value
                }
                .filter { it.isNotEmpty() }
                .toList()
        }
    }

    private fun runCommand(
        refresh: Boolean,
        after: (() -> Unit)? = null,
        block: (VlcHttpClient) -> Unit
    ) {
        val active = client ?: run {
            setError("Configura primero el servidor VLC")
            return
        }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { block(active) } }
                .onSuccess {
                    if (refresh) refreshStatus()
                    after?.invoke()
                }
                .onFailure { handleFailure(it) }
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
