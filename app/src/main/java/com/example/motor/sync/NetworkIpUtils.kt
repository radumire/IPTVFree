package com.example.motor.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale

/**
 * Shared utility for local IP resolution across sync and streaming servers.
 * Prioritizes physical, non-VPN Wi-Fi and Ethernet interfaces.
 */
object NetworkIpUtils {
    private const val TAG = "NetworkIpUtils"

    /**
     * Checks if an IP is a valid private LAN IPv4 address (RFC 1918).
     * Performs strict 4-octet IPv4 parsing to reject malformed strings (e.g. "192.168.mal", "192.168.1.999").
     */
    fun isValidLanIp(ip: String): Boolean {
        if (ip.isBlank()) return false
        val parts = ip.split(".")
        if (parts.size != 4) return false
        val o0 = parts[0].toIntOrNull() ?: return false
        val o1 = parts[1].toIntOrNull() ?: return false
        val o2 = parts[2].toIntOrNull() ?: return false
        val o3 = parts[3].toIntOrNull() ?: return false

        if (o0 !in 0..255 || o1 !in 0..255 || o2 !in 0..255 || o3 !in 0..255) return false
        if (o0 == 0 || o0 == 127) return false
        if (o0 == 169 && o1 == 254) return false

        return (o0 == 10) ||
               (o0 == 172 && o1 in 16..31) ||
               (o0 == 192 && o1 == 168)
    }

    fun isClassBPrivate(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        val o0 = parts[0].toIntOrNull() ?: return false
        val o1 = parts[1].toIntOrNull() ?: return false
        val o2 = parts[2].toIntOrNull() ?: return false
        val o3 = parts[3].toIntOrNull() ?: return false
        if (o0 !in 0..255 || o1 !in 0..255 || o2 !in 0..255 || o3 !in 0..255) return false
        return o0 == 172 && o1 in 16..31
    }

    /**
     * Resolves the primary physical Wi-Fi or Ethernet IPv4 address of the device.
     * Safely handles VPNs, active network capabilities, and legacy fallbacks.
     */
    fun getPhysicalWifiIp(context: Context?): String? {
        if (context == null) return null
        val appContext = context.applicationContext

        // 1. ConnectivityManager: Physical Wi-Fi / Ethernet excluding VPN
        try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                // Priority 1: non-VPN physical Wi-Fi or Ethernet
                for (network in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(network) ?: continue
                    if ((caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                         caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                        !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    ) {
                        val linkProps = cm.getLinkProperties(network) ?: continue
                        for (linkAddr in linkProps.linkAddresses) {
                            val addr = linkAddr.address
                            if (addr is Inet4Address && !addr.isLoopbackAddress) {
                                val host = addr.hostAddress
                                if (!host.isNullOrBlank() && isValidLanIp(host)) {
                                    return host
                                }
                            }
                        }
                    }
                }

                // Priority 2: Any Wi-Fi or Ethernet network
                for (network in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(network) ?: continue
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    ) {
                        val linkProps = cm.getLinkProperties(network) ?: continue
                        for (linkAddr in linkProps.linkAddresses) {
                            val addr = linkAddr.address
                            if (addr is Inet4Address && !addr.isLoopbackAddress) {
                                val host = addr.hostAddress
                                if (!host.isNullOrBlank() && isValidLanIp(host)) {
                                    return host
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "ConnectivityManager IP lookup failed: ${e.message}")
        }

        // 2. WifiManager check (direct Wi-Fi adapter connection info and DHCP gateway)
        try {
            val wm = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val dhcp = wm?.dhcpInfo
            val ipInt = dhcp?.ipAddress ?: (wm?.connectionInfo?.ipAddress ?: 0)
            if (ipInt != 0) {
                val ip = String.format(
                    Locale.US,
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
                if (isValidLanIp(ip)) {
                    return ip
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "WifiManager IP lookup failed: ${e.message}")
        }

        // 3. Fallback: Direct NetworkInterface enumeration
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (iface.isUp && !iface.isLoopback) {
                    val name = iface.name.lowercase(Locale.ROOT)
                    if (name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("p2p") || name.startsWith("ap")) {
                        val addrs = iface.inetAddresses
                        while (addrs.hasMoreElements()) {
                            val addr = addrs.nextElement()
                            if (addr is Inet4Address && !addr.isLoopbackAddress) {
                                val host = addr.hostAddress ?: continue
                                if (isValidLanIp(host)) {
                                    return host
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "NetworkInterface IP lookup failed: ${e.message}")
        }

        return null
    }

    /**
     * Resolves all possible local LAN subnet prefixes (e.g. ["192.168.1", "192.168.0", ...])
     * by querying hardware Wi-Fi DHCP gateway, network interfaces, and fallback subnets.
     * Centralized here to eliminate duplicated scanning logic.
     */
    fun resolveLocalSubnetPrefixes(context: Context?): Set<String> {
        val prefixes = linkedSetOf<String>()
        if (context == null) return setOf("192.168.1", "192.168.0", "192.168.18", "192.168.8", "192.168.100", "192.168.31", "10.0.0")

        val appContext = context.applicationContext

        // 1. Hardware Wi-Fi DHCP Gateway & IP (Direct from hardware registers, never routed via VPN)
        try {
            val wm = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val dhcp = wm?.dhcpInfo
            if (dhcp != null) {
                if (dhcp.gateway != 0) {
                    val gw = String.format(
                        Locale.US,
                        "%d.%d.%d.%d",
                        dhcp.gateway and 0xff,
                        dhcp.gateway shr 8 and 0xff,
                        dhcp.gateway shr 16 and 0xff,
                        dhcp.gateway shr 24 and 0xff
                    )
                    if (isValidLanIp(gw)) {
                        prefixes.add(gw.substringBeforeLast("."))
                    }
                }
                if (dhcp.ipAddress != 0) {
                    val ip = String.format(
                        Locale.US,
                        "%d.%d.%d.%d",
                        dhcp.ipAddress and 0xff,
                        dhcp.ipAddress shr 8 and 0xff,
                        dhcp.ipAddress shr 16 and 0xff,
                        dhcp.ipAddress shr 24 and 0xff
                    )
                    if (isValidLanIp(ip)) {
                        prefixes.add(ip.substringBeforeLast("."))
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "resolveLocalSubnetPrefixes DHCP failed: ${e.message}")
        }

        // 2. Physical phone IP
        val physicalIp = getPhysicalWifiIp(context)
        if (!physicalIp.isNullOrBlank() && physicalIp.contains(".")) {
            prefixes.add(physicalIp.substringBeforeLast("."))
        }

        // 3. Network interfaces scan (ignoring VPN tunnel IPs like 10.8.x.x)
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces?.hasMoreElements() == true) {
                val iface = ifaces.nextElement()
                if (iface.isUp && !iface.isLoopback) {
                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val a = addrs.nextElement()
                        if (a is Inet4Address && !a.isLoopbackAddress) {
                            val host = a.hostAddress
                            if (!host.isNullOrBlank() && isValidLanIp(host) &&
                                !host.startsWith("10.8.") && !host.startsWith("10.9.") && !host.startsWith("10.0.2.")) {
                                prefixes.add(host.substringBeforeLast("."))
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        // 4. Standard home router prefixes
        prefixes.add("192.168.1")
        prefixes.add("192.168.0")
        prefixes.add("192.168.18") // Common fiber router subnet
        prefixes.add("192.168.8")  // Common 4G/5G router subnet
        prefixes.add("192.168.100")
        prefixes.add("192.168.31") // Xiaomi router subnet
        prefixes.add("10.0.0")

        return prefixes
    }
}
