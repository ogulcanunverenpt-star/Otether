package com.godot.otether.wifi

import android.content.Context
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.NetworkInterface

enum class NetShareMode {
    DIRECT_CELLULAR,
    DIRECT_WIFI_DUAL,
    PROXY_WIFI_REPEATER
}

class HotspotManager(private val context: Context) {
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null

    private var wifiP2pManager: WifiP2pManager? = null
    private var wifiP2pChannel: WifiP2pManager.Channel? = null

    interface HotspotCallback {
        fun onStarted(ssid: String, password: String, mode: NetShareMode)
        fun onStopped()
        fun onError(reason: String)
    }

    companion object {
        fun getP2pIpAddress(): String {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (iface.name.contains("p2p") || iface.name.contains("wlan1") || iface.name.contains("swlan")) {
                        val addrs = iface.inetAddresses
                        while (addrs.hasMoreElements()) {
                            val addr = addrs.nextElement()
                            if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                                return addr.hostAddress ?: "192.168.49.1"
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
            return "192.168.49.1"
        }

        fun isWifiSharingSupported(context: Context): Boolean {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                wifiManager.isStaApConcurrencySupported
            } else {
                false
            }
        }

        fun determineNetShareMode(context: Context, preferredSource: String): NetShareMode {
            if (preferredSource == "cellular") {
                return NetShareMode.DIRECT_CELLULAR
            }
            return if (isWifiSharingSupported(context)) {
                NetShareMode.DIRECT_WIFI_DUAL
            } else {
                NetShareMode.PROXY_WIFI_REPEATER
            }
        }
    }

    fun isLocationEnabled(): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    fun startHotspot(preferredSource: String, callback: HotspotCallback) {
        val activeMode = determineNetShareMode(context, preferredSource)
        Log.i("HotspotManager", "Starting Hotspot in Mode: $activeMode (source: $preferredSource)")

        if (preferredSource == "cellular") {
            val systemSuccess = tryStartSystemTethering(NetShareMode.DIRECT_CELLULAR, callback)
            if (!systemSuccess) {
                startLocalOnlyHotspotProxy(NetShareMode.DIRECT_CELLULAR, callback)
            }
        } else {
            // Wi-Fi Repeater Mode: MUST call Wi-Fi Direct Group ("Creating group...") directly.
            // Do NOT call LocalOnlyHotspot or system tethering as they turn off active Wi-Fi connection!
            startWifiDirectGroup(NetShareMode.PROXY_WIFI_REPEATER, callback)
        }
    }

    fun startWifiDirectGroup(mode: NetShareMode, callback: HotspotCallback) {
        if (!isLocationEnabled()) {
            callback.onError("Location Services (GPS) must be turned ON for Wi-Fi Direct Repeater Mode.")
            return
        }

        try {
            val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            if (manager == null) {
                callback.onError("Wi-Fi Direct P2P service is unavailable on this device.")
                return
            }

            val channel = manager.initialize(context, Looper.getMainLooper(), null)
            if (channel == null) {
                callback.onError("Wi-Fi Direct could not initialize. Toggle Wi-Fi and try again.")
                return
            }
            wifiP2pManager = manager
            wifiP2pChannel = channel

            // Clear any old P2P group first
            manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { createP2pGroupInternal(mode, callback) }
                override fun onFailure(reason: Int) { createP2pGroupInternal(mode, callback) }
            })
        } catch (e: Exception) {
            callback.onError("Error starting Wi-Fi Direct: ${e.message}")
        }
    }

    @Suppress("MissingPermission")
    private fun createP2pGroupInternal(mode: NetShareMode, callback: HotspotCallback) {
        try {
            wifiP2pManager?.createGroup(wifiP2pChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i("HotspotManager", "Wi-Fi Direct P2P Group Created ('Creating group...')")

                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            wifiP2pManager?.requestGroupInfo(wifiP2pChannel) { group ->
                                if (group != null) {
                                    val ssid = group.networkName
                                    val passphrase = group.passphrase ?: "otether123"
                                    callback.onStarted(ssid, passphrase, mode)
                                } else {
                                    callback.onStarted("DIRECT-Otether", "otether123", mode)
                                }
                            }
                        } catch (_: SecurityException) {
                            callback.onStarted("DIRECT-Otether", "otether123", mode)
                        }
                    }, 600)
                }

                override fun onFailure(reason: Int) {
                    Log.e("HotspotManager", "Wi-Fi Direct Group creation failed with reason code: $reason")
                    val errorMsg = when (reason) {
                        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct P2P Unsupported on this device"
                        WifiP2pManager.BUSY -> "Wi-Fi Direct P2P Engine Busy (Try toggling Wi-Fi)"
                        WifiP2pManager.ERROR -> "Generic Wi-Fi Direct Error (Turn ON Location/GPS & Wi-Fi)"
                        else -> "Wi-Fi Direct creation failed with code $reason"
                    }
                    callback.onError(errorMsg)
                }
            })
        } catch (e: SecurityException) {
            callback.onError("Wi-Fi Direct permission error: ${e.message}")
        }
    }

    private fun startLocalOnlyHotspotProxy(mode: NetShareMode, callback: HotspotCallback) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isLocationEnabled()) {
            callback.onError("Location Services (GPS) must be turned ON for LocalOnlyHotspot Repeater.")
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            callback.onError("Android 8.0+ required for LocalOnlyHotspot Repeater Mode.")
            return
        }

        try {
            wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
                    super.onStarted(reservation)
                    hotspotReservation = reservation

                    val ssid: String
                    val password: String

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val softApConfig = reservation?.softApConfiguration
                        @Suppress("DEPRECATION")
                        ssid = softApConfig?.ssid ?: "Otether"
                        password = softApConfig?.passphrase ?: "otether123"
                    } else {
                        @Suppress("DEPRECATION")
                        val config = reservation?.wifiConfiguration
                        @Suppress("DEPRECATION")
                        ssid = config?.SSID ?: "Otether"
                        @Suppress("DEPRECATION")
                        password = config?.preSharedKey ?: "otether123"
                    }

                    callback.onStarted(ssid, password, mode)
                }

                override fun onStopped() {
                    super.onStopped()
                    hotspotReservation = null
                    callback.onStopped()
                }

                override fun onFailed(reason: Int) {
                    super.onFailed(reason)
                    callback.onError("Hotspot Proxy Repeater failed with reason code: $reason")
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: SecurityException) {
            callback.onError("Security Exception starting hotspot proxy: ${e.message}")
        } catch (e: Exception) {
            callback.onError("Error starting hotspot proxy: ${e.message}")
        }
    }

    private fun tryStartSystemTethering(mode: NetShareMode, callback: HotspotCallback): Boolean {
        return try {
            val classConnectivityManager = Class.forName(connectivityManager.javaClass.name)
            val methodStartTethering = classConnectivityManager.getDeclaredMethod(
                "startTethering",
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Class.forName("android.net.ConnectivityManager\$OnStartTetheringCallback")
            )

            val callbackClass = Class.forName("android.net.ConnectivityManager\$OnStartTetheringCallback")
            val callbackInstance = java.lang.reflect.Proxy.newProxyInstance(
                callbackClass.classLoader,
                arrayOf(callbackClass)
            ) { _, method, _ ->
                if (method.name == "onTetheringStarted") {
                    Log.i("HotspotManager", "Direct Native System Tethering Started in Mode: $mode")
                    Handler(Looper.getMainLooper()).post {
                        callback.onStarted("Otether", "otether123", mode)
                    }
                }
                null
            }

            methodStartTethering.invoke(connectivityManager, 0, false, callbackInstance)
            true
        } catch (e: Exception) {
            Log.w("HotspotManager", "Direct system startTethering fallback: ${e.message}")
            false
        }
    }

    fun stopHotspot() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                hotspotReservation?.close()
            }
            hotspotReservation = null

            wifiP2pManager?.removeGroup(wifiP2pChannel, null)
            wifiP2pChannel = null
            wifiP2pManager = null
        } catch (e: Exception) {
            Log.e("HotspotManager", "Error closing hotspot / P2P group", e)
        }
    }
}
