package com.example.cast

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import javax.net.SocketFactory

/**
 * Universal TV & Chromecast discovery and casting engine with VPN bypass,
 * physical Wi-Fi binding, direct Chromecast & DLNA discovery, and VLC fallback.
 */
class TvCastManager private constructor() {

    private val DIAG_TAG = "CAST_DISCOVERY_DIAGNOSTIC"

    private val _discoveredDevices = MutableStateFlow<List<TvCastDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<TvCastDevice>> = _discoveredDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var mediaRouter: MediaRouter? = null
    private var mediaRouteSelector: MediaRouteSelector? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var discoveryJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // Concurrency limiter for subnet scanning to prevent OS socket exhaustion
    private val subnetScanSemaphore = Semaphore(16)

    private val mediaRouterCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) {
            handleMediaRoute(route, "onRouteAdded")
        }

        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) {
            handleMediaRoute(route, "onRouteChanged")
        }

        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) {
            Log.d(DIAG_TAG, "[Google Cast] Route removed: id=${route.id}, name=${route.name}")
            val current = _discoveredDevices.value.filterNot { it.id == route.id || it.mediaRoute?.id == route.id }
            _discoveredDevices.value = current
        }
    }

    private fun handleMediaRoute(route: MediaRouter.RouteInfo, event: String) {
        if (route.isDefault) {
            Log.d(DIAG_TAG, "[Google Cast] Ignored default audio/video output route: ${route.name}")
            return
        }
        val deviceName = route.name
        if (deviceName.isBlank()) {
            Log.d(DIAG_TAG, "[Google Cast] Ignored route with blank name: id=${route.id}")
            return
        }

        Log.d(DIAG_TAG, "[Google Cast] Discovered Cast route ($event): id=${route.id}, name=$deviceName")

        val castDevice = TvCastDevice(
            id = route.id,
            name = deviceName,
            type = TvCastType.CHROMECAST,
            mediaRoute = route
        )

        addOrUpdateDevice(castDevice)
    }

    fun startDiscovery(context: Context) {
        _discoveredDevices.value = emptyList()
        _isScanning.value = true

        // 1. Diagnostic Network & VPN analysis
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val physicalNetwork = getPhysicalWifiNetwork(context)
        val hasVpn = checkIsVpnActive(cm)
        val physicalIp = CastStreamProxyServer.getPhysicalWifiIp(context)
        val physicalIfaces = getPhysicalNetworkInterfaces()
        val ifaceNames = physicalIfaces.joinToString(", ") { it.name }

        Log.i(DIAG_TAG, "==================================================")
        Log.i(DIAG_TAG, "STARTING TV & CAST DISCOVERY SESSION")
        Log.i(DIAG_TAG, "Physical Wi-Fi Interface(s): [$ifaceNames]")
        Log.i(DIAG_TAG, "Phone Physical IP: $physicalIp")
        Log.i(DIAG_TAG, "VPN Active on System: $hasVpn")
        Log.i(DIAG_TAG, "Physical Network Handle: ${physicalNetwork ?: "NONE"}")
        Log.i(DIAG_TAG, "==================================================")

        // 2. Google Cast SDK / MediaRouter discovery
        try {
            val appContext = context.applicationContext
            mediaRouter = MediaRouter.getInstance(appContext)

            mediaRouteSelector = MediaRouteSelector.Builder()
                .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
                .addControlCategory(MediaControlIntent.CATEGORY_LIVE_VIDEO)
                .addControlCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)
                .build()

            mediaRouteSelector?.let { selector ->
                mediaRouter?.addCallback(selector, mediaRouterCallback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
                val existingRoutes = mediaRouter?.routes ?: emptyList()
                Log.d(DIAG_TAG, "[Google Cast] MediaRouter initialized. Existing active routes count: ${existingRoutes.size}")
                existingRoutes.forEach { route ->
                    handleMediaRoute(route, "initialRouteScan")
                }
            }
        } catch (e: Exception) {
            Log.e(DIAG_TAG, "[Google Cast] Error initializing MediaRouter: ${e.message}", e)
        }

        // 3. Physical LAN discovery routines (mDNS, SSDP, Subnet probing)
        discoveryJob = scope.launch(Dispatchers.IO) {
            acquireMulticastLock(context)
            try {
                // Auxiliary mDNS discovery
                launch {
                    discoverMdns(physicalNetwork, physicalIfaces)
                }

                // Auxiliary SSDP (UPnP / DLNA) multicast discovery
                launch {
                    discoverSsdp(physicalNetwork, physicalIfaces)
                }

                // Controlled physical subnet scanning
                launch {
                    discoverSubnetDevices(context, physicalNetwork, physicalIp)
                }

            } catch (e: Exception) {
                Log.e(DIAG_TAG, "Discovery job failure: ${e.message}", e)
            }
        }
    }

    fun stopDiscovery() {
        Log.i(DIAG_TAG, "Stopping TV & Cast discovery session")
        _isScanning.value = false
        try {
            mediaRouter?.removeCallback(mediaRouterCallback)
        } catch (e: Exception) {
            Log.e(DIAG_TAG, "Error removing MediaRouter callback: ${e.message}")
        }
        discoveryJob?.cancel()
        releaseMulticastLock()
    }

    private fun checkIsVpnActive(cm: ConnectivityManager?): Boolean {
        if (cm == null) return false
        for (net in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(net) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return true
            }
        }
        return false
    }

    fun getPhysicalWifiNetwork(context: Context): Network? {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if ((caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return network
            }
        }
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                return network
            }
        }
        return null
    }

    private fun getPhysicalNetworkInterfaces(): List<NetworkInterface> {
        val list = mutableListOf<NetworkInterface>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isUp && !iface.isLoopback) {
                    val name = iface.name.lowercase()
                    if (name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("p2p") || name.startsWith("en") || name.startsWith("ap")) {
                        list.add(iface)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(DIAG_TAG, "Error reading network interfaces: ${e.message}")
        }
        return list
    }

    private fun acquireMulticastLock(context: Context) {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("TvCastMulticastLock")?.apply {
                setReferenceCounted(true)
                acquire()
            }
            Log.d(DIAG_TAG, "MulticastLock acquired successfully")
        } catch (e: Exception) {
            Log.d(DIAG_TAG, "Failed to acquire MulticastLock: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
                Log.d(DIAG_TAG, "MulticastLock released")
            }
        } catch (e: Exception) {
            Log.d(DIAG_TAG, "Failed to release MulticastLock: ${e.message}")
        }
    }

    private suspend fun discoverSsdp(
        physicalNetwork: Network?,
        physicalIfaces: List<NetworkInterface>
    ) = withContext(Dispatchers.IO) {
        Log.d(DIAG_TAG, "[SSDP] Starting UPnP/DLNA M-SEARCH on physical interfaces...")

        val ssdpQueries = listOf(
            "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 2\r\n" +
                    "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n",

            "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 2\r\n" +
                    "ST: urn:schemas-upnp-org:service:AVTransport:1\r\n\r\n",

            "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 2\r\n" +
                    "ST: ssdp:all\r\n\r\n"
        )

        val group = InetAddress.getByName("239.255.255.250")

        for (iface in physicalIfaces) {
            launch {
                var mSocket: MulticastSocket? = null
                try {
                    mSocket = MulticastSocket()
                    mSocket.soTimeout = 2500
                    mSocket.networkInterface = iface
                    if (physicalNetwork != null) {
                        try { physicalNetwork.bindSocket(mSocket) } catch (_: Exception) {}
                    }

                    for (query in ssdpQueries) {
                        val bytes = query.toByteArray(Charsets.UTF_8)
                        mSocket.send(DatagramPacket(bytes, bytes.size, group, 1900))
                    }

                    val buffer = ByteArray(2048)
                    val rxPacket = DatagramPacket(buffer, buffer.size)
                    val startTime = System.currentTimeMillis()

                    while (_isScanning.value && (System.currentTimeMillis() - startTime) < 6000) {
                        try {
                            mSocket.receive(rxPacket)
                            val response = String(rxPacket.data, 0, rxPacket.length)
                            val hostIp = rxPacket.address?.hostAddress ?: continue
                            parseSsdpPacket(response, hostIp, physicalNetwork)
                        } catch (_: Exception) {
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.d(DIAG_TAG, "[SSDP] Interface ${iface.name} error: ${e.message}")
                } finally {
                    mSocket?.close()
                }
            }
        }
    }

    private fun parseSsdpPacket(response: String, hostIp: String, physicalNetwork: Network?) {
        scope.launch(Dispatchers.IO) {
            var locationUrl: String? = null

            response.lines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("LOCATION:", ignoreCase = true) || trimmed.startsWith("Location:", ignoreCase = true)) {
                    locationUrl = trimmed.substringAfter(":").trim()
                }
            }

            if (locationUrl.isNullOrBlank()) {
                return@launch
            }

            inspectDeviceLocationUrl(locationUrl!!, hostIp, physicalNetwork, "SSDP Multicast")
        }
    }

    private suspend fun discoverMdns(
        physicalNetwork: Network?,
        physicalIfaces: List<NetworkInterface>
    ) = withContext(Dispatchers.IO) {
        Log.d(DIAG_TAG, "[mDNS] Starting DNS-SD queries on physical interfaces...")

        val mdnsQueries = listOf(
            buildDnsSdQuery("_googlecast._tcp.local"),
            buildDnsSdQuery("_airplay._tcp.local"),
            buildDnsSdQuery("_androidtvremote2._tcp.local")
        )

        val mdnsGroup = InetAddress.getByName("224.0.0.251")
        val mdnsPort = 5353

        for (iface in physicalIfaces) {
            launch {
                var socket: DatagramSocket? = null
                try {
                    socket = DatagramSocket()
                    socket.soTimeout = 3000
                    if (physicalNetwork != null) {
                        try { physicalNetwork.bindSocket(socket) } catch (_: Exception) {}
                    }

                    for (query in mdnsQueries) {
                        socket.send(DatagramPacket(query, query.size, mdnsGroup, mdnsPort))
                    }

                    val buffer = ByteArray(4096)
                    val packet = DatagramPacket(buffer, buffer.size)
                    val startTime = System.currentTimeMillis()

                    while (_isScanning.value && (System.currentTimeMillis() - startTime) < 6000) {
                        try {
                            socket.receive(packet)
                            val hostIp = packet.address?.hostAddress ?: continue
                            val data = String(packet.data, 0, packet.length, Charsets.ISO_8859_1)
                            parseMdnsPacket(data, hostIp, physicalNetwork)
                        } catch (_: Exception) {
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.d(DIAG_TAG, "[mDNS] Interface ${iface.name} error: ${e.message}")
                } finally {
                    socket?.close()
                }
            }
        }
    }

    private fun parseMdnsPacket(data: String, hostIp: String, physicalNetwork: Network?) {
        if (data.contains("googlecast") || data.contains("fn=")) {
            var friendlyName: String? = null
            var modelName: String? = null

            if (data.contains("fn=")) {
                friendlyName = data.substringAfter("fn=").takeWhile { it.code in 32..126 }
            }
            if (data.contains("md=")) {
                modelName = data.substringAfter("md=").takeWhile { it.code in 32..126 }
            }

            val finalName = friendlyName?.takeIf { it.isNotBlank() } ?: "Google Cast / TV ($hostIp)"
            Log.i(DIAG_TAG, "[mDNS] Discovered Google Cast service on $hostIp: fn='$finalName', md='$modelName'")

            val castDevice = TvCastDevice(
                id = "mdns_cast_$hostIp",
                name = finalName,
                type = TvCastType.CHROMECAST,
                ipAddress = hostIp,
                modelName = modelName
            )
            addOrUpdateDevice(castDevice)
        }
    }

    private fun buildDnsSdQuery(serviceName: String): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeShort(0x0001)
        dos.writeShort(0x0000)
        dos.writeShort(0x0001)
        dos.writeShort(0x0000)
        dos.writeShort(0x0000)
        dos.writeShort(0x0000)

        for (label in serviceName.split(".")) {
            if (label.isNotEmpty()) {
                val bytes = label.toByteArray(Charsets.UTF_8)
                dos.writeByte(bytes.size)
                dos.write(bytes)
            }
        }
        dos.writeByte(0)
        dos.writeShort(12)
        dos.writeShort(1)
        dos.flush()
        return baos.toByteArray()
    }

    private suspend fun discoverSubnetDevices(
        context: Context,
        physicalNetwork: Network?,
        physicalPhoneIp: String?
    ) = withContext(Dispatchers.IO) {
        val prefixes = mutableSetOf<String>()

        if (!physicalPhoneIp.isNullOrBlank() && physicalPhoneIp.contains(".")) {
            val prefix = physicalPhoneIp.substringBeforeLast(".")
            prefixes.add(prefix)
            Log.i(DIAG_TAG, "[Subnet Scan] Detected primary phone subnet: $prefix.0/24")
        }

        prefixes.add("192.168.1")
        prefixes.add("192.168.100")
        prefixes.add("192.168.0")

        val socketFactory = physicalNetwork?.socketFactory ?: SocketFactory.getDefault()

        for (prefix in prefixes) {
            Log.d(DIAG_TAG, "[Subnet Scan] Probing subnet $prefix.1 - $prefix.254 (Max concurrency: 16)...")
            for (hostNum in 1..254) {
                if (!_isScanning.value) break
                val targetIp = "$prefix.$hostNum"
                if (targetIp == physicalPhoneIp) continue

                launch {
                    subnetScanSemaphore.withPermit {
                        probeHostForAllServices(targetIp, socketFactory, physicalNetwork)
                    }
                }
            }
        }
    }

    private suspend fun probeHostForAllServices(
        ip: String,
        socketFactory: SocketFactory,
        physicalNetwork: Network?
    ) {
        // 1. Check Google Cast / DIAL port 8008
        if (isPortOpen(ip, 8008, socketFactory)) {
            Log.d(DIAG_TAG, "[Subnet Scan] Host $ip has open Google Cast / DIAL port 8008. Checking device info...")
            val eurekaJson = fetchHttpContent("http://$ip:8008/setup/eureka_info", physicalNetwork)
            var devName: String? = null

            if (!eurekaJson.isNullOrBlank()) {
                if (eurekaJson.contains("\"name\":")) {
                    devName = eurekaJson.substringAfter("\"name\":").substringAfter("\"").substringBefore("\"")
                }
            }

            if (devName.isNullOrBlank()) {
                val xmlDesc = fetchHttpContent("http://$ip:8008/ssdp/device-desc.xml", physicalNetwork)
                if (!xmlDesc.isNullOrBlank()) {
                    devName = extractXmlTag(xmlDesc, "friendlyName")
                }
            }

            val finalName = devName?.takeIf { it.isNotBlank() } ?: "Google TV / Cast ($ip)"
            Log.i(DIAG_TAG, "[Subnet Scan] Discovered Google Cast on $ip: '$finalName'")

            val castDevice = TvCastDevice(
                id = "ip_cast_$ip",
                name = finalName,
                type = TvCastType.CHROMECAST,
                ipAddress = ip
            )
            addOrUpdateDevice(castDevice)
            return
        }

        // 2. Check Google Cast TLS port 8009
        if (isPortOpen(ip, 8009, socketFactory)) {
            Log.d(DIAG_TAG, "[Subnet Scan] Host $ip has open Google Cast TLS port 8009.")
            val castDevice = TvCastDevice(
                id = "ip_cast_$ip",
                name = "Google Cast / TV ($ip)",
                type = TvCastType.CHROMECAST,
                ipAddress = ip
            )
            addOrUpdateDevice(castDevice)
            return
        }

        // 3. Check DLNA UPnP ports: 1900, 8080, 49152, 49153, 2869, 7676, 9197, 9000
        val upnpPorts = listOf(1900, 8080, 49152, 49153, 2869, 7676, 9197, 9000)
        for (port in upnpPorts) {
            if (isPortOpen(ip, port, socketFactory)) {
                val testUrls = listOf(
                    "http://$ip:$port/description.xml",
                    "http://$ip:$port/device-desc.xml",
                    "http://$ip:$port/dmr/SamsungMRDesc.xml",
                    "http://$ip:$port/upnp/dev/MediaRenderer/desc.xml",
                    "http://$ip:$port/rootDesc.xml"
                )

                for (descUrl in testUrls) {
                    val xml = fetchHttpContent(descUrl, physicalNetwork)
                    if (!xml.isNullOrBlank()) {
                        inspectDeviceLocationUrl(descUrl, ip, physicalNetwork, "Port $port")
                        return
                    }
                }
            }
        }
    }

    private fun isPortOpen(ip: String, port: Int, socketFactory: SocketFactory): Boolean {
        var socket: Socket? = null
        return try {
            socket = socketFactory.createSocket()
            socket.connect(InetSocketAddress(ip, port), 250)
            true
        } catch (_: Exception) {
            false
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private suspend fun inspectDeviceLocationUrl(
        locationUrl: String,
        hostIp: String,
        physicalNetwork: Network?,
        discoverySource: String
    ) = withContext(Dispatchers.IO) {
        try {
            val xmlContent = fetchHttpContent(locationUrl, physicalNetwork)
            if (xmlContent.isNullOrBlank()) return@withContext

            val friendlyName = extractXmlTag(xmlContent, "friendlyName") ?: "Smart TV ($hostIp)"
            val manufacturer = extractXmlTag(xmlContent, "manufacturer")
            val modelName = extractXmlTag(xmlContent, "modelName")

            val controlUrl = extractControlUrl(xmlContent, locationUrl)

            Log.i(DIAG_TAG, "[Device Evaluation] Found UPnP device: '$friendlyName' on $hostIp (AVTransport: ${controlUrl != null})")

            val dlnaDevice = TvCastDevice(
                id = "dlna_$hostIp",
                name = friendlyName,
                type = TvCastType.DLNA_TV,
                ipAddress = hostIp,
                avTransportUrl = controlUrl,
                manufacturer = manufacturer,
                modelName = modelName
            )

            addOrUpdateDevice(dlnaDevice)

        } catch (e: Exception) {
            Log.d(DIAG_TAG, "[Device Evaluation] Error inspecting $locationUrl: ${e.message}")
        }
    }

    private fun fetchHttpContent(urlStr: String, physicalNetwork: Network?): String? {
        return try {
            val url = URL(urlStr)
            val conn = if (physicalNetwork != null) {
                physicalNetwork.openConnection(url) as java.net.HttpURLConnection
            } else {
                url.openConnection() as java.net.HttpURLConnection
            }
            conn.connectTimeout = 600
            conn.readTimeout = 600
            conn.setRequestProperty("User-Agent", "Android-Cast-Scanner/2.0")
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun extractXmlTag(xml: String, tag: String): String? {
        val startTag = "<$tag>"
        val endTag = "</$tag>"
        val startIndex = xml.indexOf(startTag)
        if (startIndex == -1) return null
        val endIndex = xml.indexOf(endTag, startIndex)
        if (endIndex == -1) return null
        return xml.substring(startIndex + startTag.length, endIndex).trim()
    }

    private fun extractControlUrl(xml: String, baseUrl: String): String? {
        val serviceTypeTag = "urn:schemas-upnp-org:service:AVTransport:1"
        if (!xml.contains(serviceTypeTag)) return null

        val serviceChunk = xml.substringAfter(serviceTypeTag, "")
        val controlSub = extractXmlTag(serviceChunk, "controlURL") ?: return null

        return if (controlSub.startsWith("http://", ignoreCase = true) || controlSub.startsWith("https://", ignoreCase = true)) {
            controlSub
        } else {
            val base = URL(baseUrl)
            "${base.protocol}://${base.host}:${if (base.port > 0) base.port else 80}${if (controlSub.startsWith("/")) "" else "/"}$controlSub"
        }
    }

    private fun addOrUpdateDevice(device: TvCastDevice) {
        val current = _discoveredDevices.value.toMutableList()
        val existingIndex = current.indexOfFirst {
            it.id == device.id || (!device.ipAddress.isNullOrBlank() && it.ipAddress == device.ipAddress)
        }

        if (existingIndex >= 0) {
            val existing = current[existingIndex]
            val updated = existing.copy(
                name = if (device.name.length > existing.name.length && !device.name.contains("($")) device.name else existing.name,
                mediaRoute = existing.mediaRoute ?: device.mediaRoute,
                avTransportUrl = existing.avTransportUrl ?: device.avTransportUrl,
                ipAddress = existing.ipAddress ?: device.ipAddress,
                manufacturer = existing.manufacturer ?: device.manufacturer,
                modelName = existing.modelName ?: device.modelName
            )
            current[existingIndex] = updated
        } else {
            current.add(device)
            Log.i(DIAG_TAG, "Added new TV Cast device: [${device.type}] '${device.name}' (${device.ipAddress ?: "MediaRoute"})")
        }
        _discoveredDevices.value = current
    }

    suspend fun castToDevice(
        context: Context,
        device: TvCastDevice,
        videoUrl: String,
        title: String,
        headers: Map<String, String> = emptyMap()
    ): Boolean = withContext(Dispatchers.IO) {
        Log.i(DIAG_TAG, "==================================================")
        Log.i(DIAG_TAG, "CAST TO DEVICE REQUEST")
        Log.i(DIAG_TAG, "Target: '${device.name}' (Type: ${device.type}, IP: ${device.ipAddress})")
        Log.i(DIAG_TAG, "Original Source URL: $videoUrl")
        Log.i(DIAG_TAG, "Media Title: $title")
        Log.i(DIAG_TAG, "==================================================")

        try {
            CastStreamProxyServer.start(videoUrl, headers)
            val proxyUrl = CastStreamProxyServer.getLocalProxyUrl(context)
            val streamUrlToUse = if (proxyUrl.isNotBlank()) proxyUrl else videoUrl
            Log.i(DIAG_TAG, "Proxy URL generated for casting: $streamUrlToUse")

            val physicalNetwork = getPhysicalWifiNetwork(context)

            when (device.type) {
                TvCastType.CHROMECAST -> {
                    val route = device.mediaRoute
                    if (route != null) {
                        Log.d(DIAG_TAG, "[Google Cast] Selecting MediaRoute: ${route.name}")
                        withContext(Dispatchers.Main) {
                            mediaRouter?.selectRoute(route)
                        }

                        val castContext = try {
                            CastContext.getSharedInstance(context.applicationContext)
                        } catch (e: Exception) {
                            Log.e(DIAG_TAG, "[Google Cast] CastContext initialization error: ${e.message}")
                            null
                        }

                        if (castContext != null) {
                            var castSession = castContext.sessionManager.currentCastSession
                            var attempts = 0
                            while (castSession == null && attempts < 15) {
                                delay(200)
                                castSession = castContext.sessionManager.currentCastSession
                                attempts++
                            }

                            val remoteMediaClient = castSession?.remoteMediaClient
                            if (remoteMediaClient != null) {
                                val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
                                    putString(MediaMetadata.KEY_TITLE, title)
                                }

                                val mimeType = when {
                                    streamUrlToUse.contains(".m3u8", ignoreCase = true) || videoUrl.contains(".m3u8", ignoreCase = true) -> "application/x-mpegURL"
                                    streamUrlToUse.contains(".mpd", ignoreCase = true) || videoUrl.contains(".mpd", ignoreCase = true) -> "application/dash+xml"
                                    streamUrlToUse.contains(".ts", ignoreCase = true) || videoUrl.contains(".ts", ignoreCase = true) -> "video/mp2t"
                                    else -> "video/mp4"
                                }

                                val isLiveStream = videoUrl.contains(".m3u8", ignoreCase = true) ||
                                        videoUrl.contains("live", ignoreCase = true) ||
                                        videoUrl.contains(":8080", ignoreCase = true)

                                val mediaInfo = MediaInfo.Builder(streamUrlToUse)
                                    .setStreamType(if (isLiveStream) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
                                    .setContentType(mimeType)
                                    .setMetadata(metadata)
                                    .build()

                                Log.i(DIAG_TAG, "[Google Cast] Sending MediaInfo.load() to RemoteMediaClient...")
                                withContext(Dispatchers.Main) {
                                    remoteMediaClient.load(mediaInfo, true, 0)
                                }
                                return@withContext true
                            }
                        }
                    }

                    // Fallback to DIAL / Direct REST launch if route wasn't in MediaRouter due to VPN
                    val hostIp = device.ipAddress
                    if (!hostIp.isNullOrBlank()) {
                        Log.i(DIAG_TAG, "[Direct Cast] Attempting DIAL launch on $hostIp:8008...")
                        val dialOk = sendDialLaunch(hostIp, streamUrlToUse, physicalNetwork)
                        if (dialOk) return@withContext true
                    }

                    // If device also has DLNA AVTransport URL
                    if (!device.avTransportUrl.isNullOrBlank()) {
                        return@withContext sendDlnaPlay(device.avTransportUrl, streamUrlToUse, title, physicalNetwork)
                    }

                    return@withContext false
                }

                TvCastType.DLNA_TV -> {
                    val controlUrl = device.avTransportUrl
                    if (!controlUrl.isNullOrBlank()) {
                        return@withContext sendDlnaPlay(controlUrl, streamUrlToUse, title, physicalNetwork)
                    }
                    val hostIp = device.ipAddress
                    if (!hostIp.isNullOrBlank()) {
                        return@withContext sendDialLaunch(hostIp, streamUrlToUse, physicalNetwork)
                    }
                    return@withContext false
                }

                TvCastType.SYSTEM_CAST -> {
                    Log.i(DIAG_TAG, "[System Cast] Launching Android system display / cast settings")
                    withContext(Dispatchers.Main) {
                        try {
                            val intent = Intent(android.provider.Settings.ACTION_CAST_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            val intent = Intent("android.settings.WIFI_DISPLAY_SETTINGS").apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        }
                    }
                    return@withContext true
                }
            }
        } catch (e: Exception) {
            Log.e(DIAG_TAG, "Fatal error executing castToDevice: ${e.message}", e)
            return@withContext false
        }
    }

    private fun sendDialLaunch(hostIp: String, streamUrl: String, physicalNetwork: Network?): Boolean {
        return try {
            val url = URL("http://$hostIp:8008/apps/DefaultMediaReceiver")
            val conn = if (physicalNetwork != null) {
                physicalNetwork.openConnection(url) as java.net.HttpURLConnection
            } else {
                url.openConnection() as java.net.HttpURLConnection
            }
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.setRequestProperty("Content-Type", "application/json")
            val payload = "{\"url\":\"$streamUrl\"}"
            conn.outputStream.use { os ->
                os.write(payload.toByteArray(Charsets.UTF_8))
            }
            val code = conn.responseCode
            Log.d(DIAG_TAG, "[DIAL] Response from $hostIp: HTTP $code")
            code in 200..299
        } catch (e: Exception) {
            Log.d(DIAG_TAG, "[DIAL] Launch failed on $hostIp: ${e.message}")
            false
        }
    }

    private suspend fun sendDlnaPlay(
        controlUrl: String,
        streamUrl: String,
        title: String,
        physicalNetwork: Network?
    ): Boolean = withContext(Dispatchers.IO) {
        Log.i(DIAG_TAG, "[DLNA Cast] Starting UPnP AVTransport sequence to $controlUrl")
        try {
            val mimeType = when {
                streamUrl.contains(".m3u8", ignoreCase = true) -> "application/x-mpegURL"
                streamUrl.contains(".mpd", ignoreCase = true) -> "application/dash+xml"
                streamUrl.contains(".ts", ignoreCase = true) -> "video/mp2t"
                else -> "video/mp4"
            }

            val safeTitle = (if (title.isBlank()) "Stream" else title)
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
            val safeUrl = streamUrl
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

            val didlXml = "&lt;DIDL-Lite xmlns=&quot;urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/&quot; xmlns:dc=&quot;http://purl.org/dc/elements/1.1/&quot; xmlns:upnp=&quot;urn:schemas-upnp-org:metadata-1-0/upnp/&quot;&gt;" +
                    "&lt;item id=&quot;1&quot; parentID=&quot;0&quot; restricted=&quot;1&quot;&gt;" +
                    "&lt;dc:title&gt;$safeTitle&lt;/dc:title&gt;" +
                    "&lt;upnp:class&gt;object.item.videoItem.movie&lt;/upnp:class&gt;" +
                    "&lt;res protocolInfo=&quot;http-get:*:$mimeType:DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000&quot;&gt;$safeUrl&lt;/res&gt;" +
                    "&lt;/item&gt;" +
                    "&lt;/DIDL-Lite&gt;"

            // 1. Send Stop
            val stopSoap = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                    "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\n" +
                    "  <s:Body>\n" +
                    "    <u:Stop xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">\n" +
                    "      <InstanceID>0</InstanceID>\n" +
                    "    </u:Stop>\n" +
                    "  </s:Body>\n" +
                    "</s:Envelope>"
            sendSoapRequest(controlUrl, "urn:schemas-upnp-org:service:AVTransport:1#Stop", stopSoap, physicalNetwork)

            // 2. Send SetAVTransportURI
            val setUriSoap = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                    "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\n" +
                    "  <s:Body>\n" +
                    "    <u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">\n" +
                    "      <InstanceID>0</InstanceID>\n" +
                    "      <CurrentURI>$safeUrl</CurrentURI>\n" +
                    "      <CurrentURIMetaData>$didlXml</CurrentURIMetaData>\n" +
                    "    </u:SetAVTransportURI>\n" +
                    "  </s:Body>\n" +
                    "</s:Envelope>"

            val uriSuccess = sendSoapRequest(controlUrl, "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI", setUriSoap, physicalNetwork)
            if (!uriSuccess) {
                Log.e(DIAG_TAG, "[DLNA Cast] SetAVTransportURI failed on $controlUrl")
                return@withContext false
            }

            // 3. Send Play
            delay(300)
            val playSoap = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                    "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\n" +
                    "  <s:Body>\n" +
                    "    <u:Play xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">\n" +
                    "      <InstanceID>0</InstanceID>\n" +
                    "      <Speed>1</Speed>\n" +
                    "    </u:Play>\n" +
                    "  </s:Body>\n" +
                    "</s:Envelope>"

            val playSuccess = sendSoapRequest(controlUrl, "urn:schemas-upnp-org:service:AVTransport:1#Play", playSoap, physicalNetwork)
            Log.i(DIAG_TAG, "[DLNA Cast] Play action status: $playSuccess")
            return@withContext playSuccess
        } catch (e: Exception) {
            Log.e(DIAG_TAG, "[DLNA Cast] Error sending DLNA commands: ${e.message}", e)
            return@withContext false
        }
    }

    private fun sendSoapRequest(
        urlStr: String,
        soapAction: String,
        xmlPayload: String,
        physicalNetwork: Network?
    ): Boolean {
        return try {
            val url = URL(urlStr)
            val conn = if (physicalNetwork != null) {
                physicalNetwork.openConnection(url) as java.net.HttpURLConnection
            } else {
                url.openConnection() as java.net.HttpURLConnection
            }
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty("SOAPACTION", "\"$soapAction\"")

            conn.outputStream.use { os ->
                os.write(xmlPayload.toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            Log.d(DIAG_TAG, "[DLNA SOAP] Response for $soapAction: HTTP $code")
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) {
            Log.e(DIAG_TAG, "[DLNA SOAP] Exception executing $soapAction: ${e.message}")
            false
        }
    }

    /**
     * Launches VLC Player with proper stream URL, headers, and media title.
     * VLC has built-in Chromecast support, allowing streaming through VPN to Chromecast.
     */
    fun launchInVlcPlayer(
        context: Context,
        videoUrl: String,
        title: String,
        headers: Map<String, String> = emptyMap()
    ): Boolean {
        return try {
            CastStreamProxyServer.start(videoUrl, headers)
            val proxyUrl = CastStreamProxyServer.getLocalProxyUrl(context)
            val streamToUse = if (proxyUrl.isNotBlank()) proxyUrl else videoUrl

            val mimeType = when {
                streamToUse.contains(".m3u8", ignoreCase = true) || videoUrl.contains(".m3u8", ignoreCase = true) -> "application/x-mpegURL"
                streamToUse.contains(".mpd", ignoreCase = true) || videoUrl.contains(".mpd", ignoreCase = true) -> "application/dash+xml"
                streamToUse.contains(".ts", ignoreCase = true) || videoUrl.contains(".ts", ignoreCase = true) -> "video/mp2t"
                streamToUse.contains(".mkv", ignoreCase = true) || videoUrl.contains(".mkv", ignoreCase = true) -> "video/x-matroska"
                else -> "video/*"
            }

            val uri = Uri.parse(streamToUse)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                putExtra("title", title)
                if (headers.isNotEmpty()) {
                    val headerPairs = headers.flatMap { listOf(it.key, it.value) }.toTypedArray()
                    putExtra("headers", headerPairs)
                    val bundle = Bundle()
                    for ((k, v) in headers) {
                        bundle.putString(k, v)
                    }
                    putExtra("android.media.intent.extra.HTTP_HEADERS", bundle)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val packageManager = context.packageManager
            val vlcPackage = when {
                isPackageInstalled("org.videolan.vlc", packageManager) -> "org.videolan.vlc"
                isPackageInstalled("org.videolan.vlc.beta", packageManager) -> "org.videolan.vlc.beta"
                else -> null
            }

            if (vlcPackage != null) {
                intent.setPackage(vlcPackage)
                context.startActivity(intent)
                true
            } else {
                val chooser = Intent.createChooser(intent, "Reproducir / Transmitir con VLC o Reproductor Externo").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
                true
            }
        } catch (e: Exception) {
            Log.e(DIAG_TAG, "Error launching VLC: ${e.message}", e)
            false
        }
    }

    fun isVlcInstalled(context: Context): Boolean {
        val pm = context.packageManager
        return isPackageInstalled("org.videolan.vlc", pm) || isPackageInstalled("org.videolan.vlc.beta", pm)
    }

    private fun isPackageInstalled(packageName: String, packageManager: PackageManager): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        @Volatile
        private var instance: TvCastManager? = null

        fun getInstance(): TvCastManager {
            return instance ?: synchronized(this) {
                instance ?: TvCastManager().also { instance = it }
            }
        }
    }
}
