package com.jairosalinas.vlcremote

enum class RemoteServerPlatform(
    val label: String,
    val experimental: Boolean
) {
    LINUX("Linux", false),
    WINDOWS("Windows (experimental)", true),
    MACOS("macOS (experimental)", true),
    CUSTOM("Personalizado / Android", true)
}

enum class SshAuthMode(val label: String) {
    PASSWORD("Contraseña"),
    PRIVATE_KEY("Clave privada")
}

data class RemoteLaunchProfile(
    val startCommand: String,
    val stopCommand: String,
    val checkCommand: String
)

object RemoteLaunchProfiles {
    val linux = RemoteLaunchProfile(
        startCommand = "export DISPLAY=:0\nnohup vlc >/dev/null 2>&1 </dev/null &",
        stopCommand = "pkill -TERM -x vlc",
        checkCommand = "pgrep -x vlc >/dev/null"
    )

    val windows = RemoteLaunchProfile(
        startCommand = "powershell.exe -NoProfile -Command \"Start-Process 'C:\\\\Program Files\\\\VideoLAN\\\\VLC\\\\vlc.exe'\"",
        stopCommand = "taskkill /IM vlc.exe /T",
        checkCommand = "tasklist /FI \"IMAGENAME eq vlc.exe\" | find /I \"vlc.exe\" >NUL"
    )

    val macos = RemoteLaunchProfile(
        startCommand = "open -a VLC",
        stopCommand = "pkill -TERM -x VLC",
        checkCommand = "pgrep -x VLC >/dev/null"
    )

    val custom = RemoteLaunchProfile(
        startCommand = "",
        stopCommand = "",
        checkCommand = ""
    )

    fun forPlatform(platform: RemoteServerPlatform): RemoteLaunchProfile = when (platform) {
        RemoteServerPlatform.LINUX -> linux
        RemoteServerPlatform.WINDOWS -> windows
        RemoteServerPlatform.MACOS -> macos
        RemoteServerPlatform.CUSTOM -> custom
    }
}
