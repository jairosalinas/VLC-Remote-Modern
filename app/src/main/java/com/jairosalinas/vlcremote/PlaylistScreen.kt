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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun PlaylistScreen(ui: VlcViewModel.UiState, vm: VlcViewModel, padding: PaddingValues) {
    var query by rememberSaveable { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val filtered = remember(ui.playlist, query) {
        if (query.isBlank()) ui.playlist else ui.playlist.filter { it.name.contains(query, ignoreCase = true) }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val currentIndex = remember(ui.playlist, ui.currentPlaylistId) {
        ui.playlist.indexOfFirst { it.id == ui.currentPlaylistId || it.current }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Vaciar playlist") },
            text = { Text("Se eliminarán todos los elementos de la playlist de VLC.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    vm.clearPlaylist()
                }) { Text("Vaciar") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancelar") }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 16.dp),
            label = { Text("Buscar en ${ui.playlist.size} elementos") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotBlank()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Borrar búsqueda")
                    }
                }
            },
            singleLine = true
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (currentIndex >= 0 && query.isBlank()) {
                OutlinedButton(onClick = {
                    scope.launch { listState.animateScrollToItem(currentIndex) }
                }) {
                    Icon(Icons.Default.MyLocation, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("Ir al actual")
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = vm::refreshPlaylist) {
                Icon(Icons.Default.Refresh, contentDescription = "Actualizar playlist")
            }
            IconButton(
                onClick = { confirmClear = true },
                enabled = ui.playlist.isNotEmpty()
            ) {
                Icon(Icons.Default.DeleteSweep, contentDescription = "Vaciar playlist")
            }
        }
        HorizontalDivider()

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
                    val isCurrent = item.id == ui.currentPlaylistId || item.current
                    Surface(
                        color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { vm.playPlaylistItem(item) }
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${index + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(end = 14.dp)
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.name,
                                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (isCurrent) {
                                    Text(
                                        "Reproduciendo",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = "Reproducir ${item.name}",
                                tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
