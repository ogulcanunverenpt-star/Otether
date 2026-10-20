package com.godot.otether.jni

import android.net.Network
import android.util.Log
import com.godot.otether.service.TetherService
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class PacketLog(
    val id: Int,
    val timestamp: Long,
    val time: String,
    val src: String,
    val dst: String,
    val protocol: String,
    val length: Int,
    val info: String,
    val clientIp: String = ""
)

data class ClientDevice(
    val ip: String,
    val mac: String,
    val deviceInterface: String,
    val totalBytes: Long = 0L
)

data class DestinationStat(
    val domain: String,
    val bytes: Long
)

object NativeEngine {
    private var serverSocket: ServerSocket? = null
    private var dnsSocket: DatagramSocket? = null
    @Volatile
    private var isRunning = false
    private val totalBytes = AtomicLong(0)
    private val packetIdCounter = AtomicInteger(1)
    private val packetLogs = ConcurrentLinkedQueue<PacketLog>()
    
    private val destinationStats = ConcurrentHashMap<String, AtomicLong>()
    private val deviceDestinationStats = ConcurrentHashMap<String, ConcurrentHashMap<String, AtomicLong>>()
    private val deviceTotalBytes = ConcurrentHashMap<String, AtomicLong>()

    private val dnsCache = ConcurrentHashMap<String, String>()
    private val executor = ThreadPoolExecutor(
        16,
        128,
        60L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        ThreadPoolExecutor.CallerRunsPolicy()
    )
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private const val MAX_PACKET_COUNT = 150
    private const val PACKET_TTL_MILLIS = 180_000L

    fun startNativeProxy(port: Int): Boolean {
        if (isRunning) return true
        return try {
            serverSocket = ServerSocket(port)
            isRunning = true
            packetLogs.clear()
            destinationStats.clear()
            deviceDestinationStats.clear()
            deviceTotalBytes.clear()
            
            executor.execute {
                while (isRunning && serverSocket?.isClosed == false) {
                    try {
                        val clientSocket = serverSocket!!.accept()
                        executor.execute { 
                            try {
                                handleProxyConnection(clientSocket) 
                            } catch (t: Throwable) {
                                Log.e("NativeEngine", "Handled background proxy thread error", t)
                            }
                        }
                    } catch (e: Exception) {
                        break
                    }
                }
            }

            startDnsRelay()
            true
        } catch (e: Exception) {
            Log.e("NativeEngine", "Failed to start proxy server", e)
            false
        }
    }

    fun stopNativeProxy() {
        isRunning = false
        try {
            serverSocket?.close()
            dnsSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        serverSocket = null
        dnsSocket = null
    }

    fun getBytesTransferred(): Long {
        return totalBytes.get()
    }

    fun clearLogs() {
        packetLogs.clear()
        destinationStats.clear()
        deviceDestinationStats.clear()
        deviceTotalBytes.clear()
    }

    fun getRecentPackets(): List<PacketLog> {
        pruneOldPackets()
        return packetLogs.toList()
    }

    fun getDestinationStats(): List<DestinationStat> {
        return destinationStats.entries.map { (domain, bytes) ->
            DestinationStat(domain, bytes.get())
        }.sortedByDescending { it.bytes }
    }

    fun getDeviceDestinationStats(clientIp: String): List<DestinationStat> {
        val devMap = deviceDestinationStats[clientIp] ?: return emptyList()
        return devMap.entries.map { (domain, bytes) ->
            DestinationStat(domain, bytes.get())
        }.sortedByDescending { it.bytes }
    }

    fun getConnectedClients(): List<ClientDevice> {
        val clients = mutableListOf<ClientDevice>()
        try {
            val file = File("/proc/net/arp")
            if (file.exists()) {
                val reader = BufferedReader(FileReader(file))
                reader.readLine()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val parts = line!!.split("\\s+".toRegex())
                    if (parts.size >= 6) {
                        val ip = parts[0]
                        val mac = parts[3]
                        val iface = parts[5]
                        if (mac != "00:00:00:00:00:00" && !mac.contains("0x")) {
                            val bytes = deviceTotalBytes[ip]?.get() ?: 0L
                            clients.add(ClientDevice(ip, mac, iface, bytes))
                        }
                    }
                }
                reader.close()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return clients
    }

    private fun addDestinationBytes(clientIp: String, domain: String, bytes: Long) {
        if (domain.isBlank() || bytes <= 0) return
        val cleanDomain = extractRootDomain(domain)

        destinationStats.computeIfAbsent(cleanDomain) { AtomicLong(0) }.addAndGet(bytes)
        val devMap = deviceDestinationStats.computeIfAbsent(clientIp) { ConcurrentHashMap() }
        devMap.computeIfAbsent(cleanDomain) { AtomicLong(0) }.addAndGet(bytes)
        deviceTotalBytes.computeIfAbsent(clientIp) { AtomicLong(0) }.addAndGet(bytes)
    }

    private fun extractRootDomain(host: String): String {
        val cleanHost = host.split(":")[0].trim().lowercase(Locale.US)
        val parts = cleanHost.split(".")
        return if (parts.size >= 2) {
            "${parts[parts.size - 2]}.${parts[parts.size - 1]}"
        } else {
            cleanHost
        }
    }

    private fun resolveHostName(hostOrIp: String): String {
        val hostOnly = hostOrIp.split(":")[0].trim()
        if (hostOnly.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+$".toRegex())) {
            dnsCache[hostOnly]?.let { return it }
            executor.execute {
                try {
                    val resolved = InetAddress.getByName(hostOnly).canonicalHostName
                    if (resolved != hostOnly) {
                        dnsCache[hostOnly] = resolved
                    }
                } catch (_: Exception) {}
            }
        }
        return hostOrIp
    }

    private fun createBoundSocket(targetHost: String, targetPort: Int): Socket? {
        return try {
            val socket = Socket()
            val upstreamNetwork: Network? = TetherService.upstreamNetwork

            if (upstreamNetwork != null) {
                try {
                    upstreamNetwork.bindSocket(socket)
                } catch (e: Exception) {
                    Log.w("NativeEngine", "Failed to bind socket to upstream network: ${e.message}")
                }
            }

            socket.connect(InetSocketAddress(targetHost, targetPort), 10000)
            socket.soTimeout = 30000
            socket
        } catch (e: Exception) {
            Log.e("NativeEngine", "Failed to connect to $targetHost:$targetPort - ${e.message}")
            null
        }
    }

    private fun handleProxyConnection(clientSocket: Socket) {
        val clientIp = clientSocket.inetAddress?.hostAddress ?: "127.0.0.1"
        val clientPort = clientSocket.port
        var targetSocket: Socket? = null

        try {
            clientSocket.soTimeout = 30000
            val clientIn: InputStream = clientSocket.getInputStream()
            val clientOut: OutputStream = clientSocket.getOutputStream()

            val headerBuffer = ByteArray(8192)
            val bytesRead = clientIn.read(headerBuffer)
            if (bytesRead <= 0) {
                clientSocket.close()
                return
            }

            val requestHeader = String(headerBuffer, 0, bytesRead, Charsets.UTF_8)
            val firstLine = requestHeader.lines().firstOrNull() ?: ""
            val parts = firstLine.split(" ")

            if (parts.size < 2) {
                clientSocket.close()
                return
            }

            val method = parts[0]
            val urlOrHost = parts[1]

            var targetHost = ""
            var targetPort = 80

            if (method.equals("CONNECT", ignoreCase = true)) {
                val hostPort = urlOrHost.split(":")
                targetHost = hostPort[0]
                targetPort = if (hostPort.size > 1) hostPort[1].toIntOrDefault(443) else 443

                targetSocket = createBoundSocket(targetHost, targetPort)
                if (targetSocket == null) {
                    val failResp = "HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray(Charsets.UTF_8)
                    try { clientOut.write(failResp) } catch (_: Exception) {}
                    clientSocket.close()
                    return
                }

                val response = "HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.UTF_8)
                clientOut.write(response)
                clientOut.flush()

                val resolvedDst = resolveHostName("$targetHost:$targetPort")
                logPacket(
                    src = "$clientIp:$clientPort",
                    dst = resolvedDst,
                    protocol = "HTTPS/TLS",
                    length = bytesRead,
                    info = "CONNECT $targetHost:$targetPort",
                    clientIp = clientIp
                )

                relayBidirectional(clientSocket, targetSocket, clientIp, targetHost)

            } else {
                if (urlOrHost.startsWith("http://", ignoreCase = true)) {
                    val uriWithoutScheme = urlOrHost.substring(7)
                    val hostAndPath = uriWithoutScheme.split("/", limit = 2)
                    val hostAndPort = hostAndPath[0].split(":")
                    targetHost = hostAndPort[0].substringAfterLast('@')
                    targetPort = if (hostAndPort.size > 1) hostAndPort[1].toIntOrDefault(80) else 80
                } else {
                    val hostLine = requestHeader.lines().find { it.startsWith("Host:", ignoreCase = true) }
                    if (hostLine != null) {
                        val hostVal = hostLine.substring(5).trim()
                        val hostAndPort = hostVal.split(":")
                        targetHost = hostAndPort[0]
                        targetPort = if (hostAndPort.size > 1) hostAndPort[1].toIntOrDefault(80) else 80
                    }
                }

                if (targetHost.isNotEmpty()) {
                    targetSocket = createBoundSocket(targetHost, targetPort)
                    if (targetSocket != null) {
                        val targetOut = targetSocket.getOutputStream()
                        targetOut.write(headerBuffer, 0, bytesRead)
                        targetOut.flush()

                        val resolvedDst = resolveHostName("$targetHost:$targetPort")
                        logPacket(
                            src = "$clientIp:$clientPort",
                            dst = resolvedDst,
                            protocol = "HTTP",
                            length = bytesRead,
                            info = "$method $targetHost:$targetPort",
                            clientIp = clientIp
                        )

                        relayBidirectional(clientSocket, targetSocket, clientIp, targetHost)
                    }
                }
            }

        } catch (t: Throwable) {
            Log.e("NativeEngine", "Error handling proxy connection", t)
        } finally {
            try { targetSocket?.close() } catch (_: Exception) {}
            try { clientSocket.close() } catch (_: Exception) {}
        }
    }

    private fun relayBidirectional(clientSocket: Socket, targetSocket: Socket, clientIp: String, domainHost: String) {
        val clientIn = clientSocket.getInputStream()
        val clientOut = clientSocket.getOutputStream()
        val targetIn = targetSocket.getInputStream()
        val targetOut = targetSocket.getOutputStream()

        val latch = CountDownLatch(2)

        fun copyStream(inStream: InputStream, outStream: OutputStream) {
            try {
                val buffer = ByteArray(8192)
                var bytes: Int
                while (inStream.read(buffer).also { bytes = it } != -1) {
                    outStream.write(buffer, 0, bytes)
                    outStream.flush()
                    totalBytes.addAndGet(bytes.toLong())
                    addDestinationBytes(clientIp, domainHost, bytes.toLong())
                }
            } catch (_: Exception) {
            } finally {
                try { clientSocket.close() } catch (_: Exception) {}
                try { targetSocket.close() } catch (_: Exception) {}
                latch.countDown()
            }
        }

        executor.execute {
            copyStream(targetIn, clientOut)
        }

        copyStream(clientIn, targetOut)

        try {
            latch.await()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun startDnsRelay() {
        try {
            dnsSocket = DatagramSocket(53)
            executor.execute {
                val buffer = ByteArray(1024)
                while (isRunning && dnsSocket?.isClosed == false) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        dnsSocket?.receive(packet)
                        val packetData = packet.data.copyOf(packet.length)
                        val clientAddress = packet.address
                        val clientPort = packet.port
                        executor.execute { 
                            try {
                                handleDnsPacket(packetData, clientAddress, clientPort) 
                            } catch (_: Exception) {}
                        }
                    } catch (e: Exception) {
                        break
                    }
                }
            }
        } catch (e: Exception) {
            // Port 53 fallback
        }
    }

    private fun handleDnsPacket(data: ByteArray, clientAddress: InetAddress?, clientPort: Int) {
        val clientIp = clientAddress?.hostAddress ?: "127.0.0.1"
        try {
            val upstreamSocket = DatagramSocket()
            TetherService.upstreamNetwork?.bindSocket(upstreamSocket)
            
            val googleDns = InetAddress.getByName("8.8.8.8")
            val outgoingPacket = DatagramPacket(data, data.size, googleDns, 53)
            upstreamSocket.send(outgoingPacket)

            val replyBuffer = ByteArray(1024)
            val replyPacket = DatagramPacket(replyBuffer, replyBuffer.size)
            upstreamSocket.soTimeout = 3000
            upstreamSocket.receive(replyPacket)

            val returnPacket = DatagramPacket(
                replyPacket.data,
                replyPacket.length,
                clientAddress,
                clientPort
            )
            dnsSocket?.send(returnPacket)
            upstreamSocket.close()

            logPacket(
                src = "$clientIp:$clientPort",
                dst = "8.8.8.8:53",
                protocol = "UDP/DNS",
                length = data.size,
                info = "DNS Query Relayed",
                clientIp = clientIp
            )
        } catch (e: Exception) {
            // Handled
        }
    }

    private fun logPacket(src: String, dst: String, protocol: String, length: Int, info: String, clientIp: String = "") {
        val now = System.currentTimeMillis()
        val timeStr = synchronized(timeFormat) { timeFormat.format(Date(now)) }
        val packet = PacketLog(
            id = packetIdCounter.getAndIncrement(),
            timestamp = now,
            time = timeStr,
            src = src,
            dst = dst,
            protocol = protocol,
            length = length,
            info = info,
            clientIp = clientIp
        )
        packetLogs.add(packet)
        pruneOldPackets()
    }

    private fun pruneOldPackets() {
        val now = System.currentTimeMillis()
        packetLogs.removeIf { now - it.timestamp > PACKET_TTL_MILLIS }
        while (packetLogs.size > MAX_PACKET_COUNT) {
            packetLogs.poll()
        }
    }

    private fun String.toIntOrDefault(default: Int): Int {
        return try {
            this.toInt()
        } catch (_: Exception) {
            default
        }
    }
}
