package com.jairosalinas.vlcremote

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
internal fun ControlScreen(
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

        if (ui.settings.remotePowerEnabled) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Button(
                            onClick = vm::toggleRemotePower,
                            enabled = !ui.remotePowerBusy,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (ui.connected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                contentColor = if (ui.connected) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            ),
                            modifier = Modifier.size(64.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(
                                Icons.Default.PowerSettingsNew,
                                contentDescription = if (ui.connected) "Cerrar VLC" else "Iniciar VLC",
                                modifier = Modifier.size(30.dp)
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                when {
                                    ui.remotePowerBusy -> if (ui.connected) "Cerrando VLC…" else "Iniciando VLC…"
                                    ui.connected -> "VLC encendido"
                                    else -> "VLC apagado"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                ui.sshStatusLabel ?: if (ui.connected) {
                                    "Toca Power para cerrar VLC"
                                } else {
                                    "Toca Power para abrir VLC por SSH"
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }

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
                        valueRange = 0f..1f,
                        enabled = ui.connected
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
                            IconButton(onClick = vm::togglePlay, enabled = ui.connected) {
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
                        OutlinedButton(onClick = vm::stop, enabled = ui.connected) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text("Detener")
                        }
                        OutlinedButton(onClick = vm::fullscreen, enabled = ui.connected) {
                            Icon(Icons.Default.Fullscreen, contentDescription = null)
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
                        IconButton(onClick = vm::toggleMute, enabled = ui.connected) {
                            Icon(
                                if (ui.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = if (ui.muted) "Restaurar sonido" else "Silenciar"
                            )
                        }
                        Slider(
                            value = sliderVolume,
                            onValueChange = { sliderVolume = it },
                            onValueChangeFinished = { vm.setVolume(sliderVolume.toInt()) },
                            valueRange = 0f..512f,
                            modifier = Modifier.weight(1f),
                            enabled = ui.connected
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
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Abrir playlist")
                }
            }
        }
    }
}

private fun formatTime(totalSeconds: Int): String {
    val safe = totalSeconds.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val seconds = safe % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
