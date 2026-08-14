package com.jairosalinas.vlcremote

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsScreen(
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

    val parsedPort = port.toIntOrNull()
    val valid = host.trim().isNotEmpty() && parsedPort != null && parsedPort in 1..65535

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Text("Conexión VLC", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        item {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Servidor") },
                supportingText = { Text("IP o hostname del equipo que ejecuta VLC") },
                singleLine = true,
                isError = host.isBlank()
            )
        }
        item {
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Puerto HTTP") },
                supportingText = {
                    if (parsedPort == null || parsedPort !in 1..65535) Text("Usa un puerto entre 1 y 65535")
                },
                isError = parsedPort == null || parsedPort !in 1..65535,
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
                supportingText = { Text("Se guarda cifrada con Android Keystore") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        vm.saveSettings(
                            SettingsRepository.Settings(host, parsedPort ?: 0, password, theme),
                            testConnection = true
                        )
                    },
                    enabled = valid,
                    modifier = Modifier.weight(1f)
                ) { Text("Probar") }
                Button(
                    onClick = {
                        vm.saveSettings(
                            SettingsRepository.Settings(host, parsedPort ?: 0, password, theme),
                            testConnection = false
                        )
                        done()
                    },
                    enabled = valid,
                    modifier = Modifier.weight(1f)
                ) { Text("Guardar") }
            }
        }
        item { HorizontalDivider() }
        item { Text("Apariencia", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
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
        item { HorizontalDivider() }
        item {
            Column {
                Text("Acerca de", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("VLC Remote Modern 1.0.0-rc2")
                Text("GPL-3.0 · basado en VlcFreemote")
                Text("Sin telemetría", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun themeLabel(mode: SettingsRepository.ThemeMode): String = when (mode) {
    SettingsRepository.ThemeMode.SYSTEM -> "Seguir sistema"
    SettingsRepository.ThemeMode.LIGHT -> "Claro"
    SettingsRepository.ThemeMode.DARK -> "Oscuro"
}
