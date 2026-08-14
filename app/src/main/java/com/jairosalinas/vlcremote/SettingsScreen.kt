package com.jairosalinas.vlcremote

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current
    var host by rememberSaveable(ui.settings.host) { mutableStateOf(ui.settings.host) }
    var port by rememberSaveable(ui.settings.port) { mutableStateOf(ui.settings.port.toString()) }
    var password by rememberSaveable(ui.settings.password) { mutableStateOf(ui.settings.password) }
    var theme by rememberSaveable(ui.settings.theme) { mutableStateOf(ui.settings.theme) }
    var themeMenu by remember { mutableStateOf(false) }

    var remotePowerEnabled by rememberSaveable(ui.settings.remotePowerEnabled) { mutableStateOf(ui.settings.remotePowerEnabled) }
    var platform by rememberSaveable(ui.settings.remoteServerPlatform) { mutableStateOf(ui.settings.remoteServerPlatform) }
    var platformMenu by remember { mutableStateOf(false) }
    var sshUseVlcHost by rememberSaveable(ui.settings.sshUseVlcHost) { mutableStateOf(ui.settings.sshUseVlcHost) }
    var sshHost by rememberSaveable(ui.settings.sshHost) { mutableStateOf(ui.settings.sshHost) }
    var sshPort by rememberSaveable(ui.settings.sshPort) { mutableStateOf(ui.settings.sshPort.toString()) }
    var sshUsername by rememberSaveable(ui.settings.sshUsername) { mutableStateOf(ui.settings.sshUsername) }
    var sshAuthMode by rememberSaveable(ui.settings.sshAuthMode) { mutableStateOf(ui.settings.sshAuthMode) }
    var authMenu by remember { mutableStateOf(false) }
    var sshPassword by rememberSaveable(ui.settings.sshPassword) { mutableStateOf(ui.settings.sshPassword) }
    var sshPrivateKeyUri by rememberSaveable(ui.settings.sshPrivateKeyUri) { mutableStateOf(ui.settings.sshPrivateKeyUri) }
    var sshPrivateKeyPassphrase by rememberSaveable(ui.settings.sshPrivateKeyPassphrase) {
        mutableStateOf(ui.settings.sshPrivateKeyPassphrase)
    }
    var sshStartCommand by rememberSaveable(ui.settings.sshStartCommand) { mutableStateOf(ui.settings.sshStartCommand) }
    var sshStopCommand by rememberSaveable(ui.settings.sshStopCommand) { mutableStateOf(ui.settings.sshStopCommand) }
    var sshCheckCommand by rememberSaveable(ui.settings.sshCheckCommand) { mutableStateOf(ui.settings.sshCheckCommand) }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }

    val parsedPort = port.toIntOrNull()
    val parsedSshPort = sshPort.toIntOrNull()
    val httpValid = host.trim().isNotEmpty() && parsedPort != null && parsedPort in 1..65535
    val resolvedSshHost = if (sshUseVlcHost) host.trim() else sshHost.trim()
    val authValid = when (sshAuthMode) {
        SshAuthMode.PASSWORD -> sshPassword.isNotEmpty()
        SshAuthMode.PRIVATE_KEY -> sshPrivateKeyUri.isNotBlank()
    }
    val sshValid = !remotePowerEnabled || (
        resolvedSshHost.isNotBlank() &&
            parsedSshPort != null && parsedSshPort in 1..65535 &&
            sshUsername.trim().isNotEmpty() &&
            authValid &&
            sshStartCommand.isNotBlank() &&
            sshStopCommand.isNotBlank() &&
            sshCheckCommand.isNotBlank()
        )
    val valid = httpValid && sshValid

    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            sshPrivateKeyUri = uri.toString()
        }
    }

    fun currentSettings(): SettingsRepository.Settings = SettingsRepository.Settings(
        host = host,
        port = parsedPort ?: 0,
        password = password,
        theme = theme,
        remotePowerEnabled = remotePowerEnabled,
        remoteServerPlatform = platform,
        sshUseVlcHost = sshUseVlcHost,
        sshHost = sshHost,
        sshPort = parsedSshPort ?: 0,
        sshUsername = sshUsername,
        sshAuthMode = sshAuthMode,
        sshPassword = sshPassword,
        sshPrivateKeyUri = sshPrivateKeyUri,
        sshPrivateKeyPassphrase = sshPrivateKeyPassphrase,
        sshStartCommand = sshStartCommand,
        sshStopCommand = sshStopCommand,
        sshCheckCommand = sshCheckCommand,
        sshHostFingerprint = ui.settings.sshHostFingerprint
    )

    androidx.compose.foundation.lazy.LazyColumn(
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
                    onClick = { vm.saveSettings(currentSettings(), testConnection = true) },
                    enabled = valid,
                    modifier = Modifier.weight(1f)
                ) { Text("Probar VLC") }
                Button(
                    onClick = {
                        vm.saveSettings(currentSettings(), testConnection = false)
                        done()
                    },
                    enabled = valid,
                    modifier = Modifier.weight(1f)
                ) { Text("Guardar") }
            }
        }

        item { HorizontalDivider() }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Control Power", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Abre y cierra VLC por SSH. No enciende ni apaga el computador.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Switch(checked = remotePowerEnabled, onCheckedChange = { remotePowerEnabled = it })
            }
        }

        if (remotePowerEnabled) {
            item {
                Box {
                    OutlinedButton(onClick = { platformMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Sistema: ${platform.label}")
                    }
                    DropdownMenu(expanded = platformMenu, onDismissRequest = { platformMenu = false }) {
                        RemoteServerPlatform.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    platform = option
                                    val defaults = RemoteLaunchProfiles.forPlatform(option)
                                    sshStartCommand = defaults.startCommand
                                    sshStopCommand = defaults.stopCommand
                                    sshCheckCommand = defaults.checkCommand
                                    platformMenu = false
                                }
                            )
                        }
                    }
                }
            }
            if (platform.experimental) {
                item {
                    Text(
                        "Este perfil es configurable/experimental. Linux es el perfil validado para esta versión.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("Usar el mismo servidor que VLC", fontWeight = FontWeight.Medium)
                        Text(
                            if (sshUseVlcHost) "SSH usará ${host.ifBlank { "el host VLC" }}" else "Usar un host SSH diferente",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(checked = sshUseVlcHost, onCheckedChange = { sshUseVlcHost = it })
                }
            }
            if (!sshUseVlcHost) {
                item {
                    OutlinedTextField(
                        value = sshHost,
                        onValueChange = { sshHost = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Servidor SSH") },
                        singleLine = true,
                        isError = sshHost.isBlank()
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = sshPort,
                    onValueChange = { sshPort = it.filter(Char::isDigit) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Puerto SSH") },
                    supportingText = { Text("22 es el puerto estándar") },
                    isError = parsedSshPort == null || parsedSshPort !in 1..65535,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }
            item {
                OutlinedTextField(
                    value = sshUsername,
                    onValueChange = { sshUsername = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Usuario SSH") },
                    supportingText = { Text("Usa una cuenta normal; no es necesario root") },
                    singleLine = true,
                    isError = sshUsername.isBlank()
                )
            }
            item {
                Box {
                    OutlinedButton(onClick = { authMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Autenticación: ${sshAuthMode.label}")
                    }
                    DropdownMenu(expanded = authMenu, onDismissRequest = { authMenu = false }) {
                        SshAuthMode.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    sshAuthMode = option
                                    authMenu = false
                                }
                            )
                        }
                    }
                }
            }
            if (sshAuthMode == SshAuthMode.PASSWORD) {
                item {
                    OutlinedTextField(
                        value = sshPassword,
                        onValueChange = { sshPassword = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Contraseña SSH") },
                        supportingText = { Text("Cifrada mediante Android Keystore") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                }
            } else {
                item {
                    OutlinedButton(
                        onClick = { keyPicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (sshPrivateKeyUri.isBlank()) "Seleccionar clave privada" else "Cambiar clave privada")
                    }
                }
                if (sshPrivateKeyUri.isNotBlank()) {
                    item {
                        Text(
                            "Clave seleccionada: ${sshPrivateKeyUri.substringAfterLast('/').take(60)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                item {
                    OutlinedTextField(
                        value = sshPrivateKeyPassphrase,
                        onValueChange = { sshPrivateKeyPassphrase = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Frase de paso de la clave (opcional)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                }
            }
            item {
                OutlinedButton(
                    onClick = { vm.testSsh(currentSettings()) },
                    enabled = httpValid && sshValid && !ui.remotePowerBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (ui.remotePowerBusy) "Probando…" else "Probar conexión SSH")
                }
            }
            ui.sshStatusLabel?.let { status ->
                item { Text(status, style = MaterialTheme.typography.bodyMedium) }
            }
            if (ui.settings.sshHostFingerprint.isNotBlank()) {
                item {
                    Column {
                        Text("Servidor SSH verificado", fontWeight = FontWeight.Medium)
                        Text(ui.settings.sshHostFingerprint, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = vm::clearTrustedSshHostKey) {
                            Text("Olvidar identidad SSH")
                        }
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = { advancedExpanded = !advancedExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (advancedExpanded) "Ocultar opciones avanzadas" else "Opciones avanzadas")
                }
            }
            if (advancedExpanded) {
                item {
                    OutlinedTextField(
                        value = sshStartCommand,
                        onValueChange = { sshStartCommand = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Comando de inicio") },
                        minLines = 2
                    )
                }
                item {
                    OutlinedTextField(
                        value = sshStopCommand,
                        onValueChange = { sshStopCommand = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Comando de cierre") },
                        minLines = 2
                    )
                }
                item {
                    OutlinedTextField(
                        value = sshCheckCommand,
                        onValueChange = { sshCheckCommand = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Comando de detección") },
                        minLines = 2
                    )
                }
                item {
                    OutlinedButton(
                        onClick = {
                            val defaults = RemoteLaunchProfiles.forPlatform(platform)
                            sshStartCommand = defaults.startCommand
                            sshStopCommand = defaults.stopCommand
                            sshCheckCommand = defaults.checkCommand
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Restablecer comandos de ${platform.label}") }
                }
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
                Text("VLC Remote Modern 1.0.0-rc3")
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
