package com.jairosalinas.vlcremote

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: VlcViewModel = viewModel()
            val ui by vm.uiState.collectAsState()
            VlcRemoteTheme(ui.settings.theme) {
                VlcRemoteApp(vm, ui)
            }
        }
    }
}

private enum class MainSection(val label: String) {
    CONTROL("Control"), LIBRARY("Biblioteca"), PLAYLIST("Playlist"), SETTINGS("Configuración")
}

@Composable
private fun VlcRemoteTheme(themeMode: SettingsRepository.ThemeMode, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = when (themeMode) {
        SettingsRepository.ThemeMode.SYSTEM -> isSystemInDarkTheme()
        SettingsRepository.ThemeMode.DARK -> true
        SettingsRepository.ThemeMode.LIGHT -> false
    }
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VlcRemoteApp(vm: VlcViewModel, ui: VlcViewModel.UiState) {
    var section by rememberSaveable { mutableStateOf(MainSection.CONTROL) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(ui.lastError) {
        ui.lastError?.let {
            snackbar.showSnackbar(it)
            vm.consumeError()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        if (section == MainSection.SETTINGS) "Configuración" else "VLC Remote Modern",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    if (section == MainSection.SETTINGS) {
                        IconButton(onClick = { section = MainSection.CONTROL }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
                        }
                    }
                },
                actions = {
                    if (section != MainSection.SETTINGS) {
                        IconButton(onClick = { section = MainSection.SETTINGS }) {
                            Icon(Icons.Default.Settings, contentDescription = "Configuración")
                        }
                    }
                },
                scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
            )
        },
        bottomBar = {
            if (section != MainSection.SETTINGS) {
                NavigationBar {
                    NavigationBarItem(
                        selected = section == MainSection.CONTROL,
                        onClick = { section = MainSection.CONTROL },
                        icon = { Icon(Icons.Default.Home, null) },
                        label = { Text("Control") }
                    )
                    NavigationBarItem(
                        selected = section == MainSection.LIBRARY,
                        onClick = { section = MainSection.LIBRARY },
                        icon = { Icon(Icons.Default.VideoLibrary, null) },
                        label = { Text("Biblioteca") }
                    )
                    NavigationBarItem(
                        selected = section == MainSection.PLAYLIST,
                        onClick = { section = MainSection.PLAYLIST },
                        icon = { Icon(Icons.Default.QueueMusic, null) },
                        label = { Text("Playlist") }
                    )
                }
            }
        }
    ) { innerPadding ->
        when (section) {
            MainSection.CONTROL -> ControlScreen(ui, vm, innerPadding) { section = MainSection.PLAYLIST }
            MainSection.LIBRARY -> LibraryScreen(ui, vm, innerPadding)
            MainSection.PLAYLIST -> PlaylistScreen(ui, vm, innerPadding)
            MainSection.SETTINGS -> SettingsScreen(ui, vm, innerPadding) { section = MainSection.CONTROL }
        }
    }
}

@Composable
private fun ConnectionPill(ui: VlcViewModel.UiState) {
    val color = if (ui.connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
    Surface(color = color, shape = RoundedCornerShape(20.dp)) {
        Text(
            ui.connectionLabel,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun ControlScreen(
    ui: VlcViewModel.UiState,
    vm: VlcViewModel,
    padding: PaddingValues,
    openPlaylist: () -> Unit
) {
    var sliderPosition by remember(ui.position) { mutableFloatStateOf(ui.position) }
    var sliderVolume by remember(ui.volume) { mutableFloatStateOf(ui.volume.toFloat()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { ConnectionPill(ui) }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Reproduciendo ahora", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        ui.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(18.dp))
                    Slider(
                        value = sliderPosition,
                        onValueChange = { sliderPosition = it },
                        onValueChangeFinished = { vm.seekTo(sliderPosition) },
                        valueRange = 0f..1f
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatTime(ui.timeSeconds), style = MaterialTheme.typography.bodySmall)
                        Text(formatTime(ui.lengthSeconds), style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TransportButton(Icons.Default.SkipPrevious, "Anterior", vm::previous)
                        TransportButton(Icons.Default.Replay10, "Retroceder 10 segundos") { vm.seekRelative(-10) }
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(72.dp)
                        ) {
                            IconButton(onClick = vm::togglePlay) {
                                Icon(
                                    if (ui.state == "playing") Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (ui.state == "playing") "Pausar" else "Reproducir",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(38.dp)
                                )
                            }
                        }
                        TransportButton(Icons.Default.Forward10, "Avanzar 10 segundos") { vm.seekRelative(10) }
                        TransportButton(Icons.Default.SkipNext, "Siguiente", vm::next)
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = vm::stop) {
                            Icon(Icons.Default.Stop, null)
                            Spacer(Modifier.size(6.dp))
                            Text("Detener")
                        }
                        OutlinedButton(onClick = vm::fullscreen) {
                            Icon(Icons.Default.Fullscreen, null)
                            Spacer(Modifier.size(6.dp))
                            Text("Pantalla completa")
                        }
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text("Volumen", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = vm::toggleMute) {
                            Icon(
                                if (ui.muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                contentDescription = if (ui.muted) "Restaurar sonido" else "Silenciar"
                            )
                        }
                        Slider(
                            value = sliderVolume,
                            onValueChange = {
                                sliderVolume = it
                                vm.setVolume(it.toInt())
                            },
                            valueRange = 0f..512f,
                            modifier = Modifier.weight(1f)
                        )
                        Text("${(sliderVolume / 5.12f).toInt()}%", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth().clickable(onClick = openPlaylist),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Playlist", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("${ui.playlist.size} elementos", style = MaterialTheme.typography.bodyMedium)
                    }
                    Icon(Icons.Default.QueueMusic, contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun TransportButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun LibraryScreen(ui: VlcViewModel.UiState, vm: VlcViewModel, padding: PaddingValues) {
    val context = LocalContext.current
    var url by rememberSaveable { mutableStateOf("") }
    val playlistPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.loadLocalPlaylist(uri) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { ConnectionPill(ui) }
        item {
            Text("Biblioteca", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Elige de dónde quieres reproducir contenido.", style = MaterialTheme.typography.bodyMedium)
        }
        item {
            FeatureCard(
                icon = Icons.Default.Folder,
                title = "Archivos del servidor",
                subtitle = "Explorar carpetas accesibles por el VLC remoto",
                enabled = false,
                badge = "En integración"
            ) { }
        }
        item {
            FeatureCard(
                icon = Icons.Default.Smartphone,
                title = "Este teléfono",
                subtitle = "Transmitir un archivo del teléfono al VLC remoto",
                enabled = false,
                badge = "En integración"
            ) { }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Link, null)
                        Spacer(Modifier.size(10.dp))
                        Text("URL o stream", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("URL") },
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { vm.playInput(url) }, modifier = Modifier.weight(1f)) {
                            Text("Reproducir")
                        }
                        OutlinedButton(onClick = { vm.playInput(url, enqueue = true) }, modifier = Modifier.weight(1f)) {
                            Text("Añadir")
                        }
                    }
                    OutlinedButton(
                        onClick = { playlistPicker.launch(arrayOf("audio/x-mpegurl", "application/vnd.apple.mpegurl", "text/plain", "*/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Abrir M3U / M3U8 del teléfono")
                    }
                }
            }
        }
    }
}

@Composable
private fun FeatureCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    badge: String? = null,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        )
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, modifier = Modifier.size(32.dp))
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium)
                if (badge != null) Text(badge, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun PlaylistScreen(ui: VlcViewModel.UiState, vm: VlcViewModel, padding: PaddingValues) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(ui.playlist, query) {
        if (query.isBlank()) ui.playlist else ui.playlist.filter { it.name.contains(query, ignoreCase = true) }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                label = { Text("Buscar en ${ui.playlist.size} elementos") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotBlank()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, "Borrar búsqueda") }
                },
                singleLine = true
            )
            IconButton(onClick = vm::refreshPlaylist) { Icon(Icons.Default.Refresh, "Actualizar playlist") }
            IconButton(onClick = vm::clearPlaylist) { Icon(Icons.Default.DeleteSweep, "Vaciar playlist") }
        }

        if (ui.loadingPlaylist) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        if (filtered.isEmpty() && !ui.loadingPlaylist) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (query.isBlank()) "La playlist está vacía" else "No hay coincidencias")
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(filtered, key = { _, item -> item.id }) { index, item ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { vm.playPlaylistItem(item) }.padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${index + 1}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 14.dp))
                        Text(item.name, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Default.PlayArrow, contentDescription = "Reproducir ${item.name}")
                    }
                    Divider()
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    ui: VlcViewModel.UiState,
    vm: VlcViewModel,
    padding: PaddingValues,
    done: () -> Unit
) {
    var host by rememberSaveable(ui.settings.host) { mutableStateOf(ui.settings.host) }
    var port by rememberSaveable(ui.settings.port) { mutableStateOf(ui.settings.port.toString()) }
    var password by rememberSaveable(ui.settings.password) { mutableStateOf(ui.settings.password) }
    var theme by rememberSaveable(ui.settings.theme) { mutableStateOf(ui.settings.theme) }
    var themeMenu by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Conexión VLC", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        item {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Servidor") },
                supportingText = { Text("IP o hostname del equipo que ejecuta VLC") },
                singleLine = true
            )
        }
        item {
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Puerto HTTP") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
        }
        item {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Contraseña VLC") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        vm.saveSettings(
                            SettingsRepository.Settings(host, port.toIntOrNull() ?: 0, password, theme),
                            testConnection = true
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Probar") }
                Button(
                    onClick = {
                        vm.saveSettings(SettingsRepository.Settings(host, port.toIntOrNull() ?: 0, password, theme), true)
                        done()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Guardar") }
            }
        }
        item { Divider() }
        item {
            Text("Apariencia", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        item {
            Box {
                OutlinedButton(onClick = { themeMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Tema: ${themeLabel(theme)}")
                }
                DropdownMenu(expanded = themeMenu, onDismissRequest = { themeMenu = false }) {
                    SettingsRepository.ThemeMode.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(themeLabel(option)) },
                            onClick = {
                                theme = option
                                themeMenu = false
                            }
                        )
                    }
                }
            }
        }
        item { Divider() }
        item {
            Text("Acerca de", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("VLC Remote Modern 1.0.0-rc2")
            Text("GPL-3.0 · basado en VlcFreemote")
            Text("Sin telemetría", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun themeLabel(mode: SettingsRepository.ThemeMode): String = when (mode) {
    SettingsRepository.ThemeMode.SYSTEM -> "Seguir sistema"
    SettingsRepository.ThemeMode.LIGHT -> "Claro"
    SettingsRepository.ThemeMode.DARK -> "Oscuro"
}

private fun formatTime(totalSeconds: Int): String {
    val safe = totalSeconds.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val seconds = safe % 60
    return if (hours > 0) String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.US, "%02d:%02d", minutes, seconds)
}
