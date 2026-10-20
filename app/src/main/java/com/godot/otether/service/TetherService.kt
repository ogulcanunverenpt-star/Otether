package com.godot.otether.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.godot.otether.R
import com.godot.otether.jni.NativeEngine
import com.godot.otether.web.LocalWebServer
import com.godot.otether.wifi.HotspotManager
import com.godot.otether.wifi.NetShareMode

class TetherService : Service() {
    private lateinit var hotspotManager: HotspotManager
    private lateinit var webServer: LocalWebServer
    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val proxyPort = 8080
    private val webPort = 8081

    companion object {
        const val CHANNEL_ID = "OtetherServiceChannel"
        const val PREFS_NAME = "OtetherPrefs"
        
        var isRunning = false
            private set
        var currentSsid: String = "Otether"
            private set
        var currentPassword: String = "otether123"
            private set
        var hotspotError: String? = null
            private set
        var selectedSource: String = "cellular"
            private set
        var activeNetShareMode: NetShareMode = NetShareMode.DIRECT_CELLULAR
            private set
        var upstreamNetwork: Network? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        hotspotManager = HotspotManager(this)
        webServer = LocalWebServer(this, webPort)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentSsid = prefs.getString("ssid", "Otether") ?: "Otether"
        currentPassword = prefs.getString("password", "otether123") ?: "otether123"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val source = intent?.getStringExtra("source") ?: "cellular"
        selectedSource = source

        createNotificationChannel()
        updateNotification("Otether Active", "SSID: $currentSsid | Pass: $currentPassword")

        acquireLocks()
        registerNetworkCallback()

        isRunning = true
        hotspotError = null

        // Start Native Proxy Server
        NativeEngine.startNativeProxy(proxyPort)

        // Start Local Web Dashboard Server
        webServer.start()

        // Start NetShare Hotspot with Mode Auto-Detection
        hotspotManager.startHotspot(selectedSource, object : HotspotManager.HotspotCallback {
            override fun onStarted(ssid: String, password: String, mode: NetShareMode) {
                activeNetShareMode = mode
                if (!ssid.contains("System") && !ssid.contains("Direct")) {
                    currentSsid = ssid
                }
                if (!password.contains("Direct")) {
                    currentPassword = password
                }
                
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putString("ssid", currentSsid)
                    .putString("password", currentPassword)
                    .apply()

                hotspotError = null
                val modeTitle = when(mode) {
                    NetShareMode.DIRECT_CELLULAR -> "Otether (Cellular Direct)"
                    NetShareMode.DIRECT_WIFI_DUAL -> "Otether (Wi-Fi Direct)"
                    NetShareMode.PROXY_WIFI_REPEATER -> "Otether (Wi-Fi Repeater Proxy)"
                }
                updateNotification(modeTitle, "SSID: $currentSsid | Pass: $currentPassword")
            }

            override fun onStopped() {
                // Keep saved credentials
            }

            override fun onError(reason: String) {
                hotspotError = reason
                updateNotification("Otether Warning", reason)
            }
        })

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i("TetherService", "Task removed from recent apps, service continues running sticky.")
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterNetworkCallback()
        releaseLocks()
        hotspotManager.stopHotspot()
        webServer.stop()
        NativeEngine.stopNativeProxy()
        isRunning = false
        hotspotError = null
        upstreamNetwork = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerNetworkCallback() {
        try {
            val builder = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

            if (selectedSource == "wifi") {
                builder.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            } else {
                builder.addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            }

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    upstreamNetwork = network
                    Log.i("TetherService", "Upstream internet network available: $network")
                }

                override fun onLost(network: Network) {
                    super.onLost(network)
                    if (upstreamNetwork == network) {
                        upstreamNetwork = connectivityManager.activeNetwork
                    }
                }
            }

            connectivityManager.registerNetworkCallback(builder.build(), networkCallback!!)
            upstreamNetwork = connectivityManager.activeNetwork
        } catch (e: Exception) {
            Log.e("TetherService", "Error registering network callback", e)
            upstreamNetwork = connectivityManager.activeNetwork
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun acquireLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Otether:WakeLock").apply {
                setReferenceCounted(false)
                acquire(10 * 60 * 60 * 1000L)
            }

            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifiManager.createWifiLock(lockMode, "Otether:WifiLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateNotification(title: String, content: String) {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Otether Foreground Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }
}
