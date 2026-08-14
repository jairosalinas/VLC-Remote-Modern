package com.jairosalinas.vlcremote

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

@Composable
internal fun LibraryScreen(
    ui: VlcViewModel.UiState,
    vm: VlcViewModel,
    padding: PaddingValues,
    openServerBrowser: () -> Unit
) {
    val context = LocalContext.current
    var url by rememberSaveable { mutableStateOf("") }

    val playlistPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.loadLocalPlaylist(uri)
    }
    val phonePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.startPhoneShare(uri)
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        phonePicker.launch(arrayOf("video/*", "audio/*", "application/octet-stream"))
    }

    val choosePhoneFile = {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            phonePicker.launch(arrayOf("video/*", "audio/*", "application/octet-stream"))
        }
    }

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
                enabled = ui.settings.host.isNotBlank(),
                onClick = openServerBrowser
            )
        }
        item {
            if (ui.phoneShareStarting || ui.phoneShareRunning) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Smartphone, contentDescription = null, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.size(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Este teléfono", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (ui.phoneShareStarting) "Preparando archivo…" else "Compartiendo con VLC",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                ui.phoneShareFileName?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (ui.phoneShareStarting) CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        }
                        if (ui.phoneShareRunning) {
                            OutlinedButton(onClick = vm::stopPhoneShare, modifier = Modifier.fillMaxWidth()) {
                                Text("Dejar de compartir")
                            }
                        }
                    }
                }
            } else {
                FeatureCard(
                    icon = Icons.Default.Smartphone,
                    title = "Este teléfono",
                    subtitle = "Reproducir en VLC un vídeo o audio guardado en el teléfono",
                    enabled = ui.settings.host.isNotBlank(),
                    onClick = choosePhoneFile
                )
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Link, contentDescription = null)
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
                        onClick = {
                            playlistPicker.launch(
                                arrayOf("audio/x-mpegurl", "application/vnd.apple.mpegurl", "text/plain", "*/*")
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Abrir M3U / M3U8 del teléfono")
                    }
                }
            }
        }
    }
}
