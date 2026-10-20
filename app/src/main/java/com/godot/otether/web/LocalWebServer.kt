package com.godot.otether.web

import android.content.Context
import com.godot.otether.jni.NativeEngine
import com.godot.otether.service.TetherService
import com.godot.otether.wifi.HotspotManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class LocalWebServer(private val context: Context, private val port: Int = 8081) {
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var isRunning = false
    private val executor = Executors.newCachedThreadPool()

    fun start() {
        if (isRunning) return
        try {
            serverSocket = ServerSocket(port)
            isRunning = true
            executor.execute {
                while (isRunning && serverSocket?.isClosed == false) {
                    try {
                        val client = serverSocket!!.accept()
                        executor.execute { handleClient(client) }
                    } catch (e: Exception) {
                        break
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        serverSocket = null
    }

    private fun handleClient(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output: OutputStream = socket.getOutputStream()

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val fullPath = if (parts.size > 1) parts[1] else "/"
            val path = fullPath.split("?")[0]
            val queryString = if (fullPath.contains("?")) fullPath.split("?")[1] else ""

            var requestedClientIp = ""
            if (queryString.contains("clientIp=")) {
                requestedClientIp = queryString.split("clientIp=")[1].split("&")[0].trim()
            }

            when {
                path == "/api/status" -> {
                    val bytesTransferred = NativeEngine.getBytesTransferred()
                    val clients = NativeEngine.getConnectedClients()
                    var packets = NativeEngine.getRecentPackets()
                    val destinations = if (requestedClientIp.isNotEmpty()) {
                        NativeEngine.getDeviceDestinationStats(requestedClientIp)
                    } else {
                        NativeEngine.getDestinationStats()
                    }

                    if (requestedClientIp.isNotEmpty()) {
                        packets = packets.filter { it.clientIp == requestedClientIp || it.src.contains(requestedClientIp) }
                    }

                    val ssid = TetherService.currentSsid
                    val password = TetherService.currentPassword
                    val hotspotError = TetherService.hotspotError ?: ""
                    val wifiSharingSupported = HotspotManager.isWifiSharingSupported(context)
                    val netShareMode = TetherService.activeNetShareMode.name

                    val clientsJson = clients.joinToString(",") {
                        "{\"ip\":\"${it.ip}\",\"mac\":\"${it.mac}\",\"iface\":\"${it.deviceInterface}\",\"totalBytes\":${it.totalBytes}}"
                    }
                    val packetsJson = packets.joinToString(",") {
                        "{\"id\":${it.id},\"time\":\"${it.time}\",\"src\":\"${it.src}\",\"dst\":\"${it.dst}\",\"protocol\":\"${it.protocol}\",\"length\":${it.length},\"info\":\"${escapeJson(it.info)}\",\"clientIp\":\"${it.clientIp}\"}"
                    }
                    val destinationsJson = destinations.joinToString(",") {
                        "{\"domain\":\"${escapeJson(it.domain)}\",\"bytes\":${it.bytes}}"
                    }

                    val p2pIp = HotspotManager.getP2pIpAddress()
                    val json = "{" +
                            "\"status\":\"running\"," +
                            "\"bytesTransferred\":$bytesTransferred," +
                            "\"ssid\":\"${escapeJson(ssid)}\"," +
                            "\"password\":\"${escapeJson(password)}\"," +
                            "\"proxyIp\":\"${escapeJson(p2pIp)}\"," +
                            "\"proxyPort\":8080," +
                            "\"hotspotError\":\"${escapeJson(hotspotError)}\"," +
                            "\"wifiSharingSupported\":$wifiSharingSupported," +
                            "\"netShareMode\":\"$netShareMode\"," +
                            "\"clients\":[$clientsJson]," +
                            "\"packets\":[$packetsJson]," +
                            "\"destinations\":[$destinationsJson]" +
                            "}"

                    sendResponse(output, "200 OK", "application/json; charset=UTF-8", json.toByteArray(Charsets.UTF_8))
                }
                path == "/api/clear-logs" -> {
                    NativeEngine.clearLogs()
                    val json = "{\"status\":\"cleared\"}"
                    sendResponse(output, "200 OK", "application/json; charset=UTF-8", json.toByteArray(Charsets.UTF_8))
                }
                path == "/style.css" -> {
                    val css = loadAssetFile("web/style.css") ?: ""
                    sendResponse(output, "200 OK", "text/css; charset=UTF-8", css.toByteArray(Charsets.UTF_8))
                }
                path == "/app.js" -> {
                    val js = loadAssetFile("web/app.js") ?: ""
                    sendResponse(output, "200 OK", "application/javascript; charset=UTF-8", js.toByteArray(Charsets.UTF_8))
                }
                else -> {
                    val html = loadAssetFile("web/index.html") ?: "<h1>Otether Dashboard Not Found</h1>"
                    sendResponse(output, "200 OK", "text/html; charset=UTF-8", html.toByteArray(Charsets.UTF_8))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun sendResponse(output: OutputStream, status: String, contentType: String, content: ByteArray) {
        val header = "HTTP/1.1 $status\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: *\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${content.size}\r\n" +
                "Connection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(content)
        output.flush()
    }

    private fun escapeJson(str: String): String {
        return str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
            .replace("\r", " ")
    }

    private fun loadAssetFile(fileName: String): String? {
        return try {
            context.assets.open(fileName).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }
}
