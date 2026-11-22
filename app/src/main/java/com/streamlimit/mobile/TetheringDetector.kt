package com.streamlimit.mobile

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

data class TetheringStatus(
    val isUsbTetheringActive: Boolean,
    val usbInterfaceName: String?,
    val usbIpAddress: String?,
    val isWifiHotspotActive: Boolean,
    val wifiInterfaceName: String?,
    val wifiIpAddress: String?
) {
    val isAnyActive: Boolean
        get() = isUsbTetheringActive || isWifiHotspotActive

    fun getSummaryText(): String {
        return when {
            isUsbTetheringActive && isWifiHotspotActive ->
                "USB (${usbInterfaceName}) + Hotspot (${wifiInterfaceName}) Aktif"
            isUsbTetheringActive ->
                "USB Tethering Aktif (${usbInterfaceName ?: "rndis0"}${usbIpAddress?.let { " • $it" } ?: ""})"
            isWifiHotspotActive ->
                "Wi-Fi Hotspot Aktif (${wifiInterfaceName ?: "ap0"}${wifiIpAddress?.let { " • $it" } ?: ""})"
            else ->
                "USB Tethering / Hotspot Bağlantısı Bekleniyor"
        }
    }
}

object TetheringDetector {

    private val USB_PREFIXES = listOf("rndis", "usb", "ncm", "eth_usb")
    private val HOTSPOT_PREFIXES = listOf("ap", "wlan1", "swlan", "softap", "p2p-wlan")

    fun detectStatus(): TetheringStatus {
        var usbActive = false
        var usbIface: String? = null
        var usbIp: String? = null

        var wifiActive = false
        var wifiIface: String? = null
        var wifiIp: String? = null

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return TetheringStatus(
                false, null, null, false, null, null
            )
            for (nif in Collections.list(interfaces)) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase()
                val ipv4 = Collections.list(nif.inetAddresses)
                    .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                    ?.hostAddress

                if (USB_PREFIXES.any { name.startsWith(it) } && ipv4 != null) {
                    usbActive = true
                    usbIface = nif.name
                    usbIp = ipv4
                } else if (HOTSPOT_PREFIXES.any { name.startsWith(it) } && ipv4 != null) {
                    wifiActive = true
                    wifiIface = nif.name
                    wifiIp = ipv4
                } else if (name == "wlan0" && ipv4 != null &&
                    (ipv4.startsWith("192.168.43.") || ipv4.startsWith("192.168.137."))
                ) {
                    // Fallback for devices that share hotspot on wlan0 subnet 192.168.43.x
                    wifiActive = true
                    wifiIface = nif.name
                    wifiIp = ipv4
                }
            }
        } catch (_: Exception) {
        }

        return TetheringStatus(
            isUsbTetheringActive = usbActive,
            usbInterfaceName = usbIface,
            usbIpAddress = usbIp,
            isWifiHotspotActive = wifiActive,
            wifiInterfaceName = wifiIface,
            wifiIpAddress = wifiIp
        )
    }

    fun openTetheringSettings(context: Context) {
        val candidates = listOf(
            Intent().apply {
                component = ComponentName(
                    "com.android.settings",
                    "com.android.settings.TetherSettings"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent("android.settings.TETHER_SETTINGS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )

        for (intent in candidates) {
            try {
                context.startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
    }
}
