package com.jairosalinas.vlcremote

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun ServerBrowserScreen(ui: VlcViewModel.UiState, vm: VlcViewModel, padding: PaddingValues) {
    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Ubicación", style = MaterialTheme.typography.labelMedium)
                Text(
                    ui.browserUri,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = vm::browseHome) {
                Icon(Icons.Default.Home, contentDescription = "Carpeta inicial")
            }
            IconButton(onClick = { vm.browse(ui.browserUri) }) {
                Icon(Icons.Default.Refresh, contentDescription = "Actualizar carpeta")
            }
        }
        HorizontalDivider()

        when {
            ui.loadingBrowser -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            ui.browserEntries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Esta carpeta está vacía")
            }
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(
                    items = ui.browserEntries,
                    key = { index, entry -> "${entry.uri}|${entry.path}|$index" }
                ) { _, entry ->
                    BrowserRow(entry, vm)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun BrowserRow(entry: VlcHttpClient.BrowserEntry, vm: VlcViewModel) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { vm.openBrowserEntry(entry) }
            .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (entry.directory) Icons.Default.Folder else Icons.Default.VideoFile,
            contentDescription = null,
            modifier = Modifier.size(30.dp),
            tint = if (entry.directory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(14.dp))
        Text(
            entry.name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        if (entry.directory) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Abrir carpeta")
        } else {
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Opciones de ${entry.name}")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Reproducir ahora") },
                        leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            vm.openBrowserEntry(entry)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Añadir a playlist") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            vm.enqueueBrowserEntry(entry)
                        }
                    )
                }
            }
        }
    }
}
