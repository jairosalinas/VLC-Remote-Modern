package com.jairosalinas.vlcremote

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel

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

internal enum class MainSection {
    CONTROL, LIBRARY, BROWSER, PLAYLIST, SETTINGS
}

@Composable
private fun VlcRemoteTheme(
    themeMode: SettingsRepository.ThemeMode,
    content: @Composable () -> Unit
) {
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
    var section by rememberSaveable {
        mutableStateOf(
            if (ui.settings.host.isBlank()) MainSection.SETTINGS else MainSection.CONTROL
        )
    }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(ui.lastError) {
        ui.lastError?.let {
            snackbar.showSnackbar(it)
            vm.consumeError()
        }
    }

    val secondaryScreen = section == MainSection.SETTINGS || section == MainSection.BROWSER
    val title = when (section) {
        MainSection.SETTINGS -> "Configuración"
        MainSection.BROWSER -> "Archivos del servidor"
        else -> "VLC Remote Modern"
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (secondaryScreen && ui.settings.host.isNotBlank()) {
                        IconButton(onClick = {
                            section = if (section == MainSection.BROWSER) MainSection.LIBRARY else MainSection.CONTROL
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
                        }
                    }
                },
                actions = {
                    if (!secondaryScreen) {
                        IconButton(onClick = { section = MainSection.SETTINGS }) {
                            Icon(Icons.Default.Settings, contentDescription = "Configuración")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (!secondaryScreen) {
                NavigationBar {
                    NavigationBarItem(
                        selected = section == MainSection.CONTROL,
                        onClick = { section = MainSection.CONTROL },
                        icon = { Icon(Icons.Default.Home, contentDescription = null) },
                        label = { Text("Control") }
                    )
                    NavigationBarItem(
                        selected = section == MainSection.LIBRARY,
                        onClick = { section = MainSection.LIBRARY },
                        icon = { Icon(Icons.Default.VideoLibrary, contentDescription = null) },
                        label = { Text("Biblioteca") }
                    )
                    NavigationBarItem(
                        selected = section == MainSection.PLAYLIST,
                        onClick = { section = MainSection.PLAYLIST },
                        icon = { Icon(Icons.Default.QueueMusic, contentDescription = null) },
                        label = { Text("Playlist") }
                    )
                }
            }
        }
    ) { innerPadding ->
        when (section) {
            MainSection.CONTROL -> ControlScreen(ui, vm, innerPadding) {
                section = MainSection.PLAYLIST
            }
            MainSection.LIBRARY -> LibraryScreen(
                ui = ui,
                vm = vm,
                padding = innerPadding,
                openServerBrowser = {
                    vm.browseHome()
                    section = MainSection.BROWSER
                }
            )
            MainSection.BROWSER -> ServerBrowserScreen(ui, vm, innerPadding)
            MainSection.PLAYLIST -> PlaylistScreen(ui, vm, innerPadding)
            MainSection.SETTINGS -> SettingsScreen(ui, vm, innerPadding) {
                section = MainSection.CONTROL
            }
        }
    }
}
