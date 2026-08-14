package com.jairosalinas.vlcremote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLConnection
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min

class PhoneMediaShareService : Service() {
    data class ShareState(
        val running: Boolean = false,
        val starting: Boolean = false,
        val url: String? = null,
        val fileName: String? = null,
        val error: String? = null
    )

    companion object {
        const val ACTION_START = "com.jairosalinas.vlcremote.PHONE_SHARE_START"
        const val ACTION_STOP = "com.jairosalinas.vlcremote.PHONE_SHARE_STOP"
        const val EXTRA_URI = "uri"
        const val EXTRA_VLC_HOST = "vlc_host"
        const val EXTRA_VLC_PORT = "vlc_port"

        private const val CHANNEL_ID = "phone_media_share"
        private const val NOTIFICATION_ID = 4301

        private val mutableState = MutableStateFlow(ShareState())
        val state: StateFlow<ShareState> = mutableState.asStateFlow()
    }

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val clients: ExecutorService = Executors.newCachedThreadPool()
    @Volatile private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSharing()
            ACTION_START -> {
                val uri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
                val host = intent.getStringExtra(EXTRA_VLC_HOST).orEmpty()
                val port = intent.getIntExtra(EXTRA_VLC_PORT, 8080)
                if (uri == null || host.isBlank()) {
                    mutableState.value = ShareState(error = "Falta el archivo o el servidor VLC")
                    stopSelf()
                } else {
                    startSharing(uri, host, port)
                }
            }
        }
        return START_NOT_STICKY
    }

    @RequiresApi(35)
    override fun onTimeout(startId: Int, fgsType: Int) {
        mutableState.value = ShareState(error = "Android detuvo la transferencia por alcanzar el límite del servicio en segundo plano")
        stopSharing()
    }

    private fun startSharing(uri: Uri, vlcHost: String, vlcPort: Int) {
        stopServerOnly()
        stopping = false
        mutableState.value = ShareState(starting = true)

        val metadata = try {
            readMetadata(uri)
        } catch (e: Exception) {
            fail(e.message ?: "No se pudo leer el archivo seleccionado")
            return
        }

        val initialNotification = buildNotification("Preparando ${metadata.name}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        acceptThread = Thread({
            try {
                val localAddress = resolveLocalAddress(vlcHost, vlcPort)
                val server = ServerSocket(0)
                server.reuseAddress = true
                serverSocket = server
                val token = randomToken()
                val displayHost = if (localAddress.contains(':')) "[$localAddress]" else localAddress
                val url = "http://$displayHost:${server.localPort}/$token/media"

                mutableState.value = ShareState(
                    running = true,
                    url = url,
                    fileName = metadata.name
                )
                updateNotification("Compartiendo ${metadata.name} con VLC")

                while (!stopping && !server.isClosed) {
                    try {
                        val socket = server.accept()
                        clients.execute { handleClient(socket, token, uri, metadata) }
                    } catch (e: IOException) {
                        if (!stopping) throw e
                    }
                }
            } catch (e: Exception) {
                if (!stopping) fail(e.message ?: e.javaClass.simpleName)
            }
        }, "PhoneMediaShare-Accept").apply {
            isDaemon = true
            start()
        }
    }

    private data class FileMetadata(val name: String, val mime: String, val size: Long)

    private fun readMetadata(uri: Uri): FileMetadata {
        var name: String? = null
        var size = -1L
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) name = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
        val finalName = name ?: "media"
        val mime = contentResolver.getType(uri)
            ?: URLConnection.guessContentTypeFromName(finalName)
            ?: "application/octet-stream"
        if (size < 0) {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                if (afd.length >= 0) size = afd.length
            }
        }
        require(size >= 0) { "El proveedor del archivo no informa su tamaño; selecciona un archivo local descargado" }
        return FileMetadata(finalName, mime, size)
    }

    private fun resolveLocalAddress(vlcHost: String, vlcPort: Int): String {
        DatagramSocket().use { socket ->
            socket.connect(InetSocketAddress(vlcHost, vlcPort))
            val address: InetAddress = socket.localAddress
            val host = address.hostAddress
            require(!host.isNullOrBlank() && !address.isAnyLocalAddress && !address.isLoopbackAddress) {
                "No se pudo determinar una IP del teléfono alcanzable por VLC"
            }
            return host.substringBefore('%')
        }
    }

    private fun randomToken(): String {
        val bytes = ByteArray(18)
        SecureRandom().nextBytes(bytes)
        return android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
    }

    private fun handleClient(socket: Socket, token: String, uri: Uri, metadata: FileMetadata) {
        socket.use { client ->
            client.soTimeout = 15000
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            try {
                val requestLine = readAsciiLine(input) ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) {
                    writeSimpleResponse(output, 400, "Bad Request")
                    return
                }
                val method = parts[0].uppercase()
                val path = parts[1].substringBefore('?')
                val headers = linkedMapOf<String, String>()
                while (true) {
                    val line = readAsciiLine(input) ?: break
                    if (line.isEmpty()) break
                    val separator = line.indexOf(':')
                    if (separator > 0) {
                        headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
                    }
                }

                if (path != "/$token/media") {
                    writeSimpleResponse(output, 404, "Not Found")
                    return
                }
                if (method != "GET" && method != "HEAD") {
                    writeSimpleResponse(output, 405, "Method Not Allowed", mapOf("Allow" to "GET, HEAD"))
                    return
                }

                val range = HttpRangeParser.parse(headers["range"], metadata.size)
                if (range == null && headers.containsKey("range")) {
                    writeSimpleResponse(
                        output,
                        416,
                        "Range Not Satisfiable",
                        mapOf("Content-Range" to "bytes */${metadata.size}")
                    )
                    return
                }

                val start = range?.first ?: 0L
                val end = range?.last ?: (metadata.size - 1L)
                val contentLength = end - start + 1L
                val status = if (range != null) "HTTP/1.1 206 Partial Content" else "HTTP/1.1 200 OK"
                val responseHeaders = buildString {
                    append(status).append("\r\n")
                    append("Content-Type: ${metadata.mime}\r\n")
                    append("Content-Length: $contentLength\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    append("Cache-Control: no-store\r\n")
                    append("Connection: close\r\n")
                    if (range != null) append("Content-Range: bytes $start-$end/${metadata.size}\r\n")
                    append("\r\n")
                }
                output.write(responseHeaders.toByteArray(StandardCharsets.US_ASCII))
                output.flush()
                if (method == "HEAD") return

                contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    afd.createInputStream().use { mediaInput ->
                        skipFully(mediaInput, start)
                        val buffer = ByteArray(64 * 1024)
                        var remaining = contentLength
                        while (remaining > 0L && !stopping) {
                            val read = mediaInput.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            remaining -= read.toLong()
                        }
                        output.flush()
                    }
                } ?: throw IOException("No se pudo volver a abrir el archivo")
            } catch (_: IOException) {
                // VLC closes obsolete range requests while seeking; that is normal.
            }
        }
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
                continue
            }
            if (input.read() < 0) throw IOException("El archivo terminó antes del rango solicitado")
            remaining--
        }
    }

    private fun readAsciiLine(input: BufferedInputStream): String? {
        val out = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (out.isEmpty()) null else out.toString()
            if (b == '\n'.code) return out.toString().trimEnd('\r')
            if (out.length > 8192) throw IOException("Cabecera HTTP demasiado larga")
            out.append(b.toChar())
        }
    }

    private fun writeSimpleResponse(
        output: BufferedOutputStream,
        code: Int,
        message: String,
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        val body = "$code $message\n".toByteArray(StandardCharsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $code $message\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            extraHeaders.forEach { (key, value) -> append("$key: $value\r\n") }
            append("\r\n")
        }
        output.write(header.toByteArray(StandardCharsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Reproducción desde el teléfono",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantiene disponible temporalmente el archivo que VLC está reproduciendo"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): android.app.Notification {
        val stopIntent = Intent(this, PhoneMediaShareService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("VLC Remote Modern")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener", stopPendingIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun fail(message: String) {
        mutableState.value = ShareState(error = message)
        stopServerOnly()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopSharing() {
        stopping = true
        mutableState.value = ShareState()
        stopServerOnly()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopServerOnly() {
        stopping = true
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
    }

    override fun onDestroy() {
        stopping = true
        stopServerOnly()
        clients.shutdownNow()
        mutableState.value = ShareState()
        super.onDestroy()
    }
}
