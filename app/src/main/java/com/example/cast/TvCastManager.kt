package com.example.cast

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
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
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import javax.net.SocketFactory
import org.json.JSONArray
import org.json.JSONObject

/**
 * Universal TV & Chromecast discovery and casting engine with VPN bypass,
 * physical Wi-Fi binding, direct Chromecast & DLNA discovery, and VLC fallback.
 */
class TvCastManager private constructor() {

    private val DIAG_TAG = "CAST_DISCOVERY_DIAGNOSTIC"
    private val PREFS_NAME = "tv_cast_preferences"
    private val KEY_SAVED_DEVICES_JSON = "saved_tv_devices"

    private val _discoveredDevices = MutableStateFlow<List<TvCastDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<TvCastDevice>> = _discoveredDevices.asStateFlow()

    private val _savedDevices = MutableStateFlow<List<TvCastDevice>>(emptyList())
    val savedDevices: StateFlow<List<TvCastDevice>> = _savedDevices.asStateFlow()

    private val _currentCastingDevice = MutableStateFlow<TvCastDevice?>(null)
    val currentCastingDevice: StateFlow<TvCastDevice?> = _currentCastingDevice.asStateFlow()

    private val _isCasting = MutableStateFlow(false)
    val isCasting: StateFlow<Boolean> = _isCasting.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val nativeCastV2Client = NativeCastV2Client()

    @Volatile
    private var appContext: Context? = null

    private var mediaRouter: MediaRouter? = null
    private var mediaRouteSelector: MediaRouteSelector? = null
    private var nsdManager: NsdManager? = null
    private var nsdDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var discoveryJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // High-performance parallel subnet scanning concurrency
    private val subnetScanSemaphore = Semaphore(48)

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
        this.appContext = context.applicationContext
        val saved = loadSavedDevices(context)
        _discoveredDevices.value = saved
        _isScanning.value = true

        // 1. Diagnostic Network & VPN analysis
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val physicalNetwork = getPhysicalWifiNetwork(context)
        val hasVpn = checkIsVpnActive(cm)
        val physicalIp = com.example.motor.sync.NetworkIpUtils.getPhysicalWifiIp(context)
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

        // 3. Android System NsdManager (Native DNS-SD daemon for Google Cast & AirPlay)
        try {
            val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
            nsdManager = nsd
            val nsdListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {
                    Log.d(DIAG_TAG, "[NSD] Started discovery for $regType")
                }
                override fun onServiceFound(service: NsdServiceInfo) {
                    Log.d(DIAG_TAG, "[NSD] Service found: ${service.serviceName} (${service.serviceType})")
                    try {
                        nsd?.resolveService(service, object : NsdManager.ResolveListener {
                            override fun onServiceResolved(resolved: NsdServiceInfo) {
                                val host = resolved.host?.hostAddress
                                val port = resolved.port
                                val name = resolved.serviceName
                                if (!host.isNullOrBlank() && !host.startsWith("127.")) {
                                    var friendlyName = name
                                    val attributes = resolved.attributes
                                    val fnBytes = attributes?.get("fn")
                                    if (fnBytes != null && fnBytes.isNotEmpty()) {
                                        friendlyName = String(fnBytes, Charsets.UTF_8)
                                    }
                                    val mdBytes = attributes?.get("md")
                                    val model = if (mdBytes != null && mdBytes.isNotEmpty()) String(mdBytes, Charsets.UTF_8) else null

                                    Log.i(DIAG_TAG, "[NSD] Discovered Cast device: '$friendlyName' at $host:$port (model: $model)")
                                    addOrUpdateDevice(
                                        TvCastDevice(
                                            id = "nsd_${host}_$port",
                                            name = friendlyName,
                                            type = TvCastType.CHROMECAST,
                                            ipAddress = host,
                                            modelName = model
                                        )
                                    )
                                }
                            }
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                Log.d(DIAG_TAG, "[NSD] Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                            }
                        })
                    } catch (e: Exception) {
                        Log.d(DIAG_TAG, "[NSD] Resolve exception: ${e.message}")
                    }
                }
                override fun onServiceLost(service: NsdServiceInfo) {
                    Log.d(DIAG_TAG, "[NSD] Service lost: ${service.serviceName}")
                }
                override fun onDiscoveryStopped(regType: String) {}
                override fun onStartDiscoveryFailed(regType: String, errorCode: Int) {
                    Log.e(DIAG_TAG, "[NSD] Start failed: $errorCode")
                }
                override fun onStopDiscoveryFailed(regType: String, errorCode: Int) {}
            }
            nsdDiscoveryListener = nsdListener
            nsd?.discoverServices("_googlecast._tcp", NsdManager.PROTOCOL_DNS_SD, nsdListener)
        } catch (e: Exception) {
            Log.e(DIAG_TAG, "[NSD] Error starting NsdManager: ${e.message}")
        }

        // 4. Physical LAN discovery routines (High-Speed Subnet Scan, mDNS, SSDP)
        discoveryJob = scope.launch(Dispatchers.IO) {
            acquireMulticastLock(context)
            try {
                // High-speed physical subnet probing (Probes port 8009 & 8008 across local Wi-Fi in < 2s)
                launch {
                    discoverSubnetDevices(context, physicalNetwork, physicalIp)
                }

                // Auxiliary mDNS discovery with QU unicast response bit
                launch {
                    discoverMdns(physicalNetwork, physicalIfaces)
                }

                // Auxiliary SSDP (UPnP / DLNA) multicast discovery
                launch {
                    discoverSsdp(physicalNetwork, physicalIfaces)
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
        try {
            nsdDiscoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (e: Exception) {
            Log.e(DIAG_TAG, "Error stopping NsdManager: ${e.message}")
        }
        nsdDiscoveryListener = null
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
        dos.writeShort(0x0001) // Transaction ID
        dos.writeShort(0x0000) // Flags: Standard query
        dos.writeShort(0x0001) // Questions count: 1
        dos.writeShort(0x0000) // Answer RRs: 0
        dos.writeShort(0x0000) // Authority RRs: 0
        dos.writeShort(0x0000) // Additional RRs: 0

        for (label in serviceName.split(".")) {
            if (label.isNotEmpty()) {
                val bytes = label.toByteArray(Charsets.UTF_8)
                dos.writeByte(bytes.size)
                dos.write(bytes)
            }
        }
        dos.writeByte(0)
        dos.writeShort(12) // Type: PTR (12)
        dos.writeShort(0x8001) // Class: IN (1) with QU unicast response bit (0x8000) set!
        dos.flush()
        return baos.toByteArray()
    }

    private suspend fun discoverSubnetDevices(
        context: Context,
        physicalNetwork: Network?,
        physicalPhoneIp: String?
    ) = withContext(Dispatchers.IO) {
        val prefixes = com.example.motor.sync.NetworkIpUtils.resolveLocalSubnetPrefixes(context)
        Log.i(DIAG_TAG, "[Subnet Scan] Scanning localized subnets: ${prefixes.joinToString(", ") { "$it.0/24" }}")

        // Fast-path: immediately probe all previously saved devices to make them appear within 100ms
        val saved = _savedDevices.value
        if (saved.isNotEmpty()) {
            launch {
                for (dev in saved) {
                    val ip = dev.ipAddress
                    if (!ip.isNullOrBlank()) {
                        probeHostForAllServices(ip, physicalNetwork)
                    }
                }
            }
        }

        for (prefix in prefixes) {
            if (!_isScanning.value) break
            Log.d(DIAG_TAG, "[Subnet Scan] Probing subnet $prefix.1 - $prefix.254 (High-speed parallel scan)...")

            val jobs = (1..254).map { hostNum ->
                val targetIp = "$prefix.$hostNum"
                if (targetIp == physicalPhoneIp) return@map null

                launch {
                    subnetScanSemaphore.withPermit {
                        if (_isScanning.value) {
                            probeHostForAllServices(targetIp, physicalNetwork)
                        }
                    }
                }
            }.filterNotNull()

            // Wait for current subnet to finish before next one
            jobs.forEach { it.join() }
        }
    }

    private suspend fun probeHostForAllServices(
        ip: String,
        physicalNetwork: Network?
    ) {
        var discoveredCastName: String? = null
        var discoveredModelName: String? = null

        // 1. Check Google Cast TLS port 8009 (Present on all Android TV, Google TV, Chromecast & Android boxes)
        val isCast8009 = isPortOpen(ip, 8009, physicalNetwork, 350)
        val isDial8008 = if (!isCast8009) isPortOpen(ip, 8008, physicalNetwork, 300) else false

        if (isCast8009 || isDial8008) {
            Log.d(DIAG_TAG, "[Fast Probe] Host $ip responded on Cast port (8009=$isCast8009, 8008=$isDial8008)")
            var devName: String? = null
            var modelName: String? = null

            // Try eureka_info on port 8008
            val eurekaJson = fetchHttpContent("http://$ip:8008/setup/eureka_info", physicalNetwork, 500)
            if (!eurekaJson.isNullOrBlank()) {
                if (eurekaJson.contains("\"name\":")) {
                    devName = eurekaJson.substringAfter("\"name\":").substringAfter("\"").substringBefore("\"")
                }
                if (eurekaJson.contains("\"model_name\":")) {
                    modelName = eurekaJson.substringAfter("\"model_name\":").substringAfter("\"").substringBefore("\"")
                }
            }

            if (devName.isNullOrBlank()) {
                val xmlDesc = fetchHttpContent("http://$ip:8008/ssdp/device-desc.xml", physicalNetwork, 500)
                if (!xmlDesc.isNullOrBlank()) {
                    devName = extractXmlTag(xmlDesc, "friendlyName")
                    modelName = extractXmlTag(xmlDesc, "modelName")
                }
            }

            // Check if we previously remembered this device name
            if (devName.isNullOrBlank()) {
                val savedMatch = _savedDevices.value.firstOrNull { it.ipAddress == ip }
                if (savedMatch != null) {
                    devName = savedMatch.name
                    modelName = savedMatch.modelName
                }
            }

            val finalName = devName?.takeIf { it.isNotBlank() } ?: "Google Cast / TV ($ip)"
            discoveredCastName = finalName
            discoveredModelName = modelName
            Log.i(DIAG_TAG, "[Subnet Scan] Discovered Google Cast device on $ip: '$finalName' (model: $modelName)")

            val castDevice = TvCastDevice(
                id = "ip_cast_$ip",
                name = finalName,
                type = TvCastType.CHROMECAST,
                ipAddress = ip,
                modelName = modelName
            )
            addOrUpdateDevice(castDevice)
        }

        // 2. Check DLNA / UPnP on this host (Smart TVs, Shield, Android TV boxes with UPnP)
        val controlUrl = findControlUrlForIp(ip, physicalNetwork)
        if (!controlUrl.isNullOrBlank()) {
            val baseName = discoveredCastName?.removeSuffix(" (Cast)")?.removeSuffix(" (DLNA)") ?: "Smart TV / Shield ($ip)"
            val dlnaName = "$baseName (DLNA)"
            val dlnaDevice = TvCastDevice(
                id = "dlna_$ip",
                name = dlnaName,
                type = TvCastType.DLNA_TV,
                ipAddress = ip,
                avTransportUrl = controlUrl,
                modelName = discoveredModelName
            )
            addOrUpdateDevice(dlnaDevice)
            return
        }

        if (discoveredCastName != null) return

        // 3. Check Samsung Smart TV (Tizen port 8001)
        if (isPortOpen(ip, 8001, physicalNetwork, 300)) {
            val samsungJson = fetchHttpContent("http://$ip:8001/api/v2/", physicalNetwork, 500)
            var tvName = "Samsung Smart TV ($ip)"
            if (!samsungJson.isNullOrBlank() && samsungJson.contains("\"name\":")) {
                val parsed = samsungJson.substringAfter("\"name\":").substringAfter("\"").substringBefore("\"")
                if (parsed.isNotBlank()) tvName = parsed
            }
            addOrUpdateDevice(
                TvCastDevice(
                    id = "samsung_$ip",
                    name = tvName,
                    type = TvCastType.DLNA_TV,
                    ipAddress = ip,
                    manufacturer = "Samsung"
                )
            )
            return
        }

        // 4. Check Roku TV (port 8060)
        if (isPortOpen(ip, 8060, physicalNetwork, 300)) {
            val rokuXml = fetchHttpContent("http://$ip:8060/query/device-info", physicalNetwork, 500)
            var rokuName = "Roku TV ($ip)"
            if (!rokuXml.isNullOrBlank()) {
                val parsed = extractXmlTag(rokuXml, "user-given-name") ?: extractXmlTag(rokuXml, "friendly-device-name")
                if (!parsed.isNullOrBlank()) rokuName = parsed
            }
            addOrUpdateDevice(
                TvCastDevice(
                    id = "roku_$ip",
                    name = rokuName,
                    type = TvCastType.DLNA_TV,
                    ipAddress = ip,
                    manufacturer = "Roku"
                )
            )
            return
        }

        // 4. Check DLNA UPnP ports: 1900, 8080, 49152, 49153, 9197, 2869
        val upnpPorts = listOf(1900, 8080, 49152, 49153, 9197, 2869)
        for (port in upnpPorts) {
            if (isPortOpen(ip, port, physicalNetwork, 250)) {
                val testUrls = listOf(
                    "http://$ip:$port/description.xml",
                    "http://$ip:$port/device-desc.xml",
                    "http://$ip:$port/dmr/SamsungMRDesc.xml",
                    "http://$ip:$port/upnp/dev/MediaRenderer/desc.xml",
                    "http://$ip:$port/rootDesc.xml"
                )

                for (descUrl in testUrls) {
                    val xml = fetchHttpContent(descUrl, physicalNetwork, 500)
                    if (!xml.isNullOrBlank()) {
                        inspectDeviceLocationUrl(descUrl, ip, physicalNetwork, "Port $port")
                        return
                    }
                }
            }
        }
    }

    private fun isPortOpen(ip: String, port: Int, physicalNetwork: Network?, timeoutMs: Int = 450): Boolean {
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.soTimeout = timeoutMs
            if (physicalNetwork != null) {
                try {
                    physicalNetwork.bindSocket(socket)
                } catch (e: Exception) {
                    Log.v(DIAG_TAG, "bindSocket warning on $ip:$port: ${e.message}")
                }
            }

            // Bind to physical Wi-Fi local address if available to ensure probe bypasses VPN routing table
            val localWifiIp = com.example.motor.sync.NetworkIpUtils.getPhysicalWifiIp(appContext)
            if (!localWifiIp.isNullOrBlank() && !socket.isBound) {
                try {
                    socket.bind(InetSocketAddress(localWifiIp, 0))
                } catch (_: Exception) {}
            }

            socket.connect(InetSocketAddress(ip, port), timeoutMs)
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

    private fun fetchHttpContent(urlStr: String, physicalNetwork: Network?, timeoutMs: Int = 600): String? {
        var conn: java.net.HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = if (physicalNetwork != null) {
                physicalNetwork.openConnection(url) as java.net.HttpURLConnection
            } else {
                url.openConnection() as java.net.HttpURLConnection
            }
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", "Android-Cast-Scanner/2.0")
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } catch (_: Exception) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {}
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
        try {
            val avtIdx = xml.indexOf("AVTransport", ignoreCase = true)
            if (avtIdx == -1) return null

            val serviceStart = xml.lastIndexOf("<service>", avtIdx, ignoreCase = true).let {
                if (it == -1) xml.lastIndexOf("<service ", avtIdx, ignoreCase = true) else it
            }
            val serviceEnd = xml.indexOf("</service>", avtIdx, ignoreCase = true)
            val serviceChunk = if (serviceStart != -1 && serviceEnd != -1 && serviceEnd > serviceStart) {
                xml.substring(serviceStart, serviceEnd)
            } else {
                xml.substring(avtIdx, (avtIdx + 800).coerceAtMost(xml.length))
            }

            val controlRegex = Regex("(?i)<controlURL>\\s*([^<\\s]+)\\s*</controlURL>")
            val match = controlRegex.find(serviceChunk) ?: return null
            val controlSub = match.groupValues[1].trim()

            return if (controlSub.startsWith("http://", ignoreCase = true) || controlSub.startsWith("https://", ignoreCase = true)) {
                controlSub
            } else {
                try {
                    java.net.URI(baseUrl).resolve(controlSub).toString()
                } catch (_: Exception) {
                    val base = URL(baseUrl)
                    "${base.protocol}://${base.host}:${if (base.port > 0) base.port else 80}${if (controlSub.startsWith("/")) "" else "/"}$controlSub"
                }
            }
        } catch (_: Exception) {
            return null
        }
    }

    fun loadSavedDevices(context: Context): List<TvCastDevice> {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonStr = prefs.getString(KEY_SAVED_DEVICES_JSON, null) ?: return emptyList()
            val jsonArray = JSONArray(jsonStr)
            val list = mutableListOf<TvCastDevice>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val typeStr = obj.optString("type", TvCastType.CHROMECAST.name)
                val type = try { TvCastType.valueOf(typeStr) } catch (_: Exception) { TvCastType.CHROMECAST }
                list.add(
                    TvCastDevice(
                        id = obj.optString("id"),
                        name = obj.optString("name"),
                        type = type,
                        ipAddress = obj.optString("ipAddress").takeIf { it.isNotBlank() },
                        avTransportUrl = obj.optString("avTransportUrl").takeIf { it.isNotBlank() },
                        manufacturer = obj.optString("manufacturer").takeIf { it.isNotBlank() },
                        modelName = obj.optString("modelName").takeIf { it.isNotBlank() },
                        isSaved = true
                    )
                )
            }
            _savedDevices.value = list
            list
        } catch (e: Exception) {
            Log.e(DIAG_TAG, "Error loading saved devices: ${e.message}")
            emptyList()
        }
    }

    fun rememberDevice(context: Context, device: TvCastDevice) {
        try {
            val current = loadSavedDevices(context).toMutableList()
            val existingIdx = current.indexOfFirst {
                it.id == device.id || (!device.ipAddress.isNullOrBlank() && it.ipAddress == device.ipAddress)
            }
            val devToSave = device.copy(isSaved = true)
            if (existingIdx >= 0) {
                current[existingIdx] = devToSave
            } else {
                current.add(0, devToSave)
            }

            val jsonArray = JSONArray()
            for (d in current) {
                val obj = JSONObject().apply {
                    put("id", d.id)
                    put("name", d.name)
                    put("type", d.type.name)
                    put("ipAddress", d.ipAddress ?: "")
                    put("avTransportUrl", d.avTransportUrl ?: "")
                    put("manufacturer", d.manufacturer ?: "")
                    put("modelName", d.modelName ?: "")
                }
                jsonArray.put(obj)
            }

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_SAVED_DEVICES_JSON, jsonArray.toString()).apply()
            _savedDevices.value = current
            addOrUpdateDevice(devToSave)
            Log.i(DIAG_TAG, "Device remembered in IP memory: ${device.name} (${device.ipAddress})")
        } catch (e: Exception) {
            Log.e(DIAG_TAG, "Error saving device to memory: ${e.message}")
        }
    }

    fun forgetDevice(context: Context, deviceIdOrIp: String) {
        try {
            val current = loadSavedDevices(context).toMutableList()
            current.removeAll { it.id == deviceIdOrIp || it.ipAddress == deviceIdOrIp }

            val jsonArray = JSONArray()
            for (d in current) {
                val obj = JSONObject().apply {
                    put("id", d.id)
                    put("name", d.name)
                    put("type", d.type.name)
                    put("ipAddress", d.ipAddress ?: "")
                    put("avTransportUrl", d.avTransportUrl ?: "")
                    put("manufacturer", d.manufacturer ?: "")
                    put("modelName", d.modelName ?: "")
                }
                jsonArray.put(obj)
            }

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_SAVED_DEVICES_JSON, jsonArray.toString()).apply()
            _savedDevices.value = current

            val discovered = _discoveredDevices.value.map {
                if (it.id == deviceIdOrIp || it.ipAddress == deviceIdOrIp) it.copy(isSaved = false) else it
            }
            _discoveredDevices.value = discovered
        } catch (e: Exception) {
            Log.e(DIAG_TAG, "Error forgetting device: ${e.message}")
        }
    }

    private fun addOrUpdateDevice(device: TvCastDevice) {
        val current = _discoveredDevices.value.toMutableList()
        val isSavedAlready = _savedDevices.value.any {
            it.id == device.id || (!device.ipAddress.isNullOrBlank() && it.ipAddress == device.ipAddress && it.type == device.type)
        }
        val deviceWithSaved = if (isSavedAlready) device.copy(isSaved = true) else device

        val existingIndex = current.indexOfFirst {
            it.id == deviceWithSaved.id || (!deviceWithSaved.ipAddress.isNullOrBlank() && it.ipAddress == deviceWithSaved.ipAddress && it.type == deviceWithSaved.type)
        }

        if (existingIndex >= 0) {
            val existing = current[existingIndex]
            val updated = existing.copy(
                name = if (deviceWithSaved.name.length > existing.name.length && !deviceWithSaved.name.contains("($")) deviceWithSaved.name else existing.name,
                mediaRoute = existing.mediaRoute ?: deviceWithSaved.mediaRoute,
                avTransportUrl = existing.avTransportUrl ?: deviceWithSaved.avTransportUrl,
                ipAddress = existing.ipAddress ?: deviceWithSaved.ipAddress,
                manufacturer = existing.manufacturer ?: deviceWithSaved.manufacturer,
                modelName = existing.modelName ?: deviceWithSaved.modelName,
                isSaved = existing.isSaved || deviceWithSaved.isSaved
            )
            current[existingIndex] = updated
        } else {
            current.add(deviceWithSaved)
            Log.i(DIAG_TAG, "Added new TV Cast device: [${deviceWithSaved.type}] '${deviceWithSaved.name}' (${deviceWithSaved.ipAddress ?: "MediaRoute"}) [saved=${deviceWithSaved.isSaved}]")
        }
        _discoveredDevices.value = current
    }

    private fun probeUnicastSsdp(ip: String, physicalNetwork: Network?): String? {
        var socket: DatagramSocket? = null
        return try {
            val localWifiIp = com.example.motor.sync.NetworkIpUtils.getPhysicalWifiIp(appContext)
            socket = if (!localWifiIp.isNullOrBlank()) {
                try {
                    DatagramSocket(InetSocketAddress(InetAddress.getByName(localWifiIp), 0))
                } catch (_: Exception) {
                    DatagramSocket()
                }
            } else {
                DatagramSocket()
            }
            socket.soTimeout = 700
            if (physicalNetwork != null) {
                try { physicalNetwork.bindSocket(socket) } catch (_: Exception) {}
            }
            val query = "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: $ip:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 1\r\n" +
                    "ST: urn:schemas-upnp-org:service:AVTransport:1\r\n\r\n"
            val bytes = query.toByteArray(Charsets.UTF_8)
            val targetAddr = InetAddress.getByName(ip)
            socket.send(DatagramPacket(bytes, bytes.size, targetAddr, 1900))

            val buf = ByteArray(2048)
            val packet = DatagramPacket(buf, buf.size)
            socket.receive(packet)
            val resp = String(packet.data, 0, packet.length)
            resp.lines().firstOrNull {
                it.startsWith("LOCATION:", ignoreCase = true) || it.startsWith("Location:", ignoreCase = true)
            }?.substringAfter(":")?.trim()
        } catch (_: Exception) {
            null
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    fun findControlUrlForIp(ip: String, physicalNetwork: Network?): String? {
        // 1. Try unicast SSDP M-SEARCH directly to device port 1900 (< 100ms)
        val locationUrl = probeUnicastSsdp(ip, physicalNetwork)
        if (!locationUrl.isNullOrBlank()) {
            val xml = fetchHttpContent(locationUrl, physicalNetwork, 600)
            if (!xml.isNullOrBlank()) {
                val control = extractControlUrl(xml, locationUrl)
                if (!control.isNullOrBlank()) {
                    Log.i(DIAG_TAG, "[DLNA Probe] Unicast SSDP resolved control URL for $ip: $control")
                    return control
                }
            }
        }

        // 2. Scan common DLNA UPnP ports
        val upnpPorts = listOf(1900, 8080, 49152, 49153, 52235, 8200, 2869, 7676, 9197, 9000, 8008, 38400, 5000, 60024)
        val testPaths = listOf(
            "/description.xml",
            "/device-desc.xml",
            "/dmr/SamsungMRDesc.xml",
            "/upnp/dev/MediaRenderer/desc.xml",
            "/rootDesc.xml",
            "/dd.xml",
            "/devicedesc.xml",
            "/dmr.xml",
            "/renderer.xml",
            "/ssdp/device-desc.xml"
        )

        for (port in upnpPorts) {
            if (isPortOpen(ip, port, physicalNetwork, 200)) {
                for (path in testPaths) {
                    val descUrl = "http://$ip:$port$path"
                    val xml = fetchHttpContent(descUrl, physicalNetwork, 400)
                    if (!xml.isNullOrBlank()) {
                        val controlUrl = extractControlUrl(xml, descUrl)
                        if (!controlUrl.isNullOrBlank()) {
                            Log.i(DIAG_TAG, "[DLNA Probe] Port $port ($path) resolved control URL for $ip: $controlUrl")
                            return controlUrl
                        }
                    }
                }
            }
        }
        return null
    }

    /**
     * Resolves the direct, cloud-accessible streaming URL for Google Cast & Smart TVs.
     * Like Vegeta TV, this sends the direct server stream directly to the TV so the TV
     * downloads independently from the internet without passing gigabytes through the phone.
     * This allows the user to close the app, lock the phone, or turn off the screen without
     * interrupting playback on the TV.
     */
    data class StreamProbeResult(
        val resolvedUrl: String,
        val hasCors: Boolean,
        val isHighPort: Boolean
    )

    /**
     * Resolves the streaming URL for Google Cast & Smart TVs.
     * For Chromecast live channels, converts .ts to .m3u8 (HLS) because Google Cast HTML5 player
     * only supports HLS and cannot play raw MPEG-TS over HTTP.
     * For DLNA, retains .ts or .m3u8 as supported by Smart TV renderers.
     */
    fun resolveDirectStreamUrl(sourceUrl: String, isLive: Boolean, isChromecast: Boolean = false): String {
        val trimmed = sourceUrl.trim()
        if (trimmed.isBlank()) return trimmed

        // Local content (e.g. device storage content:// or file://)
        if (trimmed.startsWith("content://", ignoreCase = true) || trimmed.startsWith("file://", ignoreCase = true)) {
            return trimmed
        }

        // VOD (Movies & Series): Use direct source URL (.mp4, .mkv, etc.)
        if (!isLive) {
            return trimmed
        }

        // Live Channels:
        if (isChromecast) {
            // Google Cast Web Receiver requires HLS (.m3u8) for live streams
            if (trimmed.contains(".m3u8", ignoreCase = true)) {
                return trimmed
            }
            if (trimmed.endsWith(".ts", ignoreCase = true)) {
                return trimmed.substringBeforeLast(".ts") + ".m3u8"
            }
            if (trimmed.contains(".ts?", ignoreCase = true)) {
                return trimmed.replace(".ts?", ".m3u8?")
            }
            if (trimmed.contains("output=ts", ignoreCase = true)) {
                return trimmed.replace("output=ts", "output=m3u8")
            }
            val qIdx = trimmed.indexOf('?')
            return if (qIdx != -1) {
                val base = trimmed.substring(0, qIdx)
                val query = trimmed.substring(qIdx)
                "$base.m3u8$query"
            } else {
                "$trimmed.m3u8"
            }
        }

        // DLNA / Other Smart TVs:
        if (trimmed.contains(".m3u8", ignoreCase = true) || trimmed.contains("token=", ignoreCase = true)) {
            return trimmed
        }
        if (trimmed.contains(".ts", ignoreCase = true)) {
            return trimmed
        }
        if (trimmed.contains("output=ts", ignoreCase = true)) {
            return trimmed
        }
        val slashParts = trimmed.split("/")
        if (slashParts.size >= 6 &&
            !trimmed.contains("/live/", ignoreCase = true) &&
            !trimmed.contains("/movie/", ignoreCase = true) &&
            !trimmed.contains("/series/", ignoreCase = true)
        ) {
            val protoAndHost = "${slashParts[0]}//${slashParts[2]}"
            val remaining = slashParts.drop(3).joinToString("/")
            val cleanRemaining = remaining.substringBefore("?").removeSuffix(".ts")
            val query = if (trimmed.contains("?")) "?" + trimmed.substringAfter("?") else ""
            return "$protoAndHost/live/$cleanRemaining.ts$query"
        }
        val qIdx = trimmed.indexOf('?')
        return if (qIdx != -1) {
            val base = trimmed.substring(0, qIdx)
            val query = trimmed.substring(qIdx)
            "$base.ts$query"
        } else {
            "$trimmed.ts"
        }
    }

    /**
     * Pre-resolves dynamic tokens and 302 redirects by probing with an authorized IPTV User-Agent.
     * Also checks for CORS headers (Access-Control-Allow-Origin) and non-standard high ports.
     */
    suspend fun resolveStreamWithAuthorizedHeaders(
        sourceUrl: String,
        headers: Map<String, String> = emptyMap(),
        physicalNetwork: Network? = null
    ): StreamProbeResult = withContext(Dispatchers.IO) {
        if (!sourceUrl.startsWith("http://", ignoreCase = true) && !sourceUrl.startsWith("https://", ignoreCase = true)) {
            return@withContext StreamProbeResult(sourceUrl, hasCors = true, isHighPort = false)
        }

        try {
            val url = URL(sourceUrl)
            val conn = if (physicalNetwork != null) {
                physicalNetwork.openConnection(url) as java.net.HttpURLConnection
            } else {
                url.openConnection() as java.net.HttpURLConnection
            }

            conn.instanceFollowRedirects = false
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "GET"

            val ua = headers["User-Agent"] ?: "IPTVSmarters/3.1.5 (Linux; Android 12)"
            conn.setRequestProperty("User-Agent", ua)
            headers.forEach { (k, v) ->
                if (!k.equals("User-Agent", ignoreCase = true) && !k.equals("Host", ignoreCase = true)) {
                    conn.setRequestProperty(k, v)
                }
            }

            val code = conn.responseCode
            var finalUrl = sourceUrl
            var corsHeader = conn.getHeaderField("Access-Control-Allow-Origin")

            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                if (!location.isNullOrBlank()) {
                    finalUrl = try {
                        url.toURI().resolve(location).toString()
                    } catch (_: Exception) {
                        location
                    }
                    Log.i(DIAG_TAG, "[Token Resolver] Successfully pre-resolved redirect token URL: $finalUrl")

                    // Check CORS on redirected target
                    try {
                        val finalUrlObj = URL(finalUrl)
                        val finalConn = if (physicalNetwork != null) {
                            physicalNetwork.openConnection(finalUrlObj) as java.net.HttpURLConnection
                        } else {
                            finalUrlObj.openConnection() as java.net.HttpURLConnection
                        }
                        finalConn.instanceFollowRedirects = true
                        finalConn.connectTimeout = 2500
                        finalConn.readTimeout = 2500
                        finalConn.requestMethod = "HEAD"
                        finalConn.setRequestProperty("User-Agent", ua)
                        finalConn.setRequestProperty("Origin", "https://www.gstatic.com")
                        finalConn.connect()
                        corsHeader = finalConn.getHeaderField("Access-Control-Allow-Origin")
                        finalConn.disconnect()
                    } catch (_: Exception) {}
                }
            }
            conn.disconnect()

            val parsedUrl = try { URL(finalUrl) } catch (_: Exception) { null }
            val port = parsedUrl?.port ?: if (parsedUrl?.protocol.equals("https", ignoreCase = true)) 443 else 80
            val isHighPort = port > 10000
            val hasCors = corsHeader != null && (corsHeader == "*" || corsHeader.contains("gstatic", ignoreCase = true) || corsHeader.contains("null"))

            Log.i(DIAG_TAG, "[Probe Result] URL: $finalUrl | CORS: $hasCors ($corsHeader) | HighPort: $isHighPort (port=$port)")
            return@withContext StreamProbeResult(finalUrl, hasCors, isHighPort)

        } catch (e: Exception) {
            Log.v(DIAG_TAG, "[Token Resolver] Fast probe skipped: ${e.message}")
            val parsedUrl = try { URL(sourceUrl) } catch (_: Exception) { null }
            val port = parsedUrl?.port ?: 80
            val isHighPort = port > 10000
            return@withContext StreamProbeResult(sourceUrl, hasCors = false, isHighPort = isHighPort)
        }
    }

    suspend fun castToDevice(
        context: Context,
        device: TvCastDevice,
        videoUrl: String,
        title: String,
        headers: Map<String, String> = emptyMap(),
        mediaType: String = "live",
        forceDlna: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        Log.i(DIAG_TAG, "==================================================")
        Log.i(DIAG_TAG, "CAST TO DEVICE REQUEST")
        Log.i(DIAG_TAG, "Target: '${device.name}' (Type: ${device.type}, IP: ${device.ipAddress}) [forceDlna=$forceDlna]")
        Log.i(DIAG_TAG, "Original Source URL: $videoUrl")
        Log.i(DIAG_TAG, "Media Title: $title")
        Log.i(DIAG_TAG, "Media Type: $mediaType")
        Log.i(DIAG_TAG, "==================================================")

        try {
            val isVod = mediaType.equals("movie", ignoreCase = true) ||
                    mediaType.equals("series", ignoreCase = true) ||
                    videoUrl.contains("/movie/", ignoreCase = true) ||
                    videoUrl.contains("/series/", ignoreCase = true) ||
                    videoUrl.endsWith(".mp4", ignoreCase = true) ||
                    videoUrl.contains(".mp4?", ignoreCase = true) ||
                    videoUrl.endsWith(".mkv", ignoreCase = true) ||
                    videoUrl.contains(".mkv?", ignoreCase = true)

            val isLiveStream = !isVod && (
                    mediaType.equals("live", ignoreCase = true) ||
                    mediaType.equals("tv", ignoreCase = true) ||
                    videoUrl.contains(".m3u8", ignoreCase = true) ||
                    videoUrl.contains("/live/", ignoreCase = true) ||
                    videoUrl.endsWith(".ts", ignoreCase = true) ||
                    videoUrl.contains(".ts?", ignoreCase = true)
            )

            val isDlnaTarget = forceDlna || device.type == TvCastType.DLNA_TV || !device.avTransportUrl.isNullOrBlank()
            val isChromecastTarget = device.type == TvCastType.CHROMECAST && !forceDlna

            val physicalNetwork = getPhysicalWifiNetwork(context)
            val baseDirectUrl = resolveDirectStreamUrl(videoUrl, isLiveStream, isChromecast = isChromecastTarget)
            val probeResult = resolveStreamWithAuthorizedHeaders(baseDirectUrl, headers, physicalNetwork)
            val directStreamUrl = probeResult.resolvedUrl

            // Determine if local proxy is needed:
            // 1. For Chromecast: Web Receiver demands CORS (Access-Control-Allow-Origin: *).
            //    If provider lacks CORS (like fortv.cc or ynjuomzz.store) or redirects to a high port (:25461),
            //    local proxy injects CORS and proxies HLS chunks/Range bytes so Chromecast plays instantly.
            // 2. For DLNA: If stream is on a high port blocked by home router/firewall, proxy bypasses it.
            val needsCorsProxy = isChromecastTarget && (!probeResult.hasCors || probeResult.isHighPort)
            val streamForChromecast = if (needsCorsProxy) {
                CastStreamProxyServer.start(context, directStreamUrl, headers, isLive = isLiveStream)
                val proxyUrl = CastStreamProxyServer.getLocalProxyUrl(context, isLive = isLiveStream, isDlna = false, sourceUrl = directStreamUrl)
                Log.i(DIAG_TAG, "[Chromecast CORS Proxy] Remote server lacks CORS / high port. Using local CORS proxy: $proxyUrl")
                proxyUrl
            } else {
                directStreamUrl
            }

            val streamForDlna = if (probeResult.isHighPort) {
                CastStreamProxyServer.start(context, directStreamUrl, headers, isLive = isLiveStream)
                val proxyUrl = CastStreamProxyServer.getLocalProxyUrl(context, isLive = isLiveStream, isDlna = true, sourceUrl = directStreamUrl)
                Log.i(DIAG_TAG, "[DLNA High-Port Proxy] Remote server uses high port. Using local DLNA proxy: $proxyUrl")
                proxyUrl
            } else {
                directStreamUrl
            }

            Log.i(DIAG_TAG, "[Stream Dispatch] Chromecast Stream: $streamForChromecast | DLNA Stream: $streamForDlna")

            val hostIp = device.ipAddress

            // 1. DLNA DISPATCH (FOR SMART TVS: SAMSUNG TIZEN, LG WEBOS, ETC.)
            if (isDlnaTarget) {
                Log.i(DIAG_TAG, "[DLNA Priority] Dispatching to Smart TV (IP=$hostIp): $streamForDlna")

                val controlUrl = device.avTransportUrl ?: (if (!hostIp.isNullOrBlank()) findControlUrlForIp(hostIp, physicalNetwork) else null)
                if (!controlUrl.isNullOrBlank()) {
                    var dlnaOk = sendDlnaPlay(controlUrl, streamForDlna, title, physicalNetwork)
                    if (!dlnaOk && streamForDlna != directStreamUrl) {
                        Log.d(DIAG_TAG, "[DLNA Priority] Retrying DLNA with direct stream: $directStreamUrl")
                        dlnaOk = sendDlnaPlay(controlUrl, directStreamUrl, title, physicalNetwork)
                    }
                    if (dlnaOk) {
                        Log.i(DIAG_TAG, "[DLNA Priority] Successfully started stream on $hostIp via DLNA!")
                        val updatedDev = device.copy(type = TvCastType.DLNA_TV, avTransportUrl = controlUrl)
                        rememberDevice(context, updatedDev)
                        _currentCastingDevice.value = updatedDev
                        _isCasting.value = true
                        return@withContext true
                    }
                }

                // Fallback to CastV2 ONLY if forceDlna was not explicitly requested and port 8009 is open
                if (!forceDlna && !hostIp.isNullOrBlank() && isPortOpen(hostIp, 8009, physicalNetwork, 300)) {
                    val connected = nativeCastV2Client.connect(hostIp, physicalNetwork, context = context.applicationContext)
                    if (connected) {
                        val ok = nativeCastV2Client.launchAndPlay(streamForChromecast, title, isLiveStream)
                        if (ok) {
                            rememberDevice(context, device.copy(type = TvCastType.CHROMECAST))
                            _currentCastingDevice.value = device
                            _isCasting.value = true
                            return@withContext true
                        }
                    }
                }
                return@withContext false
            }

            // 2. CHROMECAST / GOOGLE CAST DISPATCH (HLS / MP4 with CORS)
            if (device.type == TvCastType.CHROMECAST) {
                Log.i(DIAG_TAG, "[Chromecast Priority] Dispatching stream to Cast device (IP=$hostIp): $streamForChromecast")

                // Option A: Try Native CastV2 engine directly on port 8009 (Fastest, VPN bypass)
                if (!hostIp.isNullOrBlank()) {
                    val connected = nativeCastV2Client.connect(hostIp, physicalNetwork, context = context.applicationContext)
                    if (connected) {
                        val ok = nativeCastV2Client.launchAndPlay(streamForChromecast, title, isLiveStream)
                        if (ok) {
                            Log.i(DIAG_TAG, "[Native CastV2] Successfully playing on $hostIp!")
                            rememberDevice(context, device)
                            _currentCastingDevice.value = device
                            _isCasting.value = true
                            return@withContext true
                        }
                    }
                }

                // Option B: Google Cast SDK via MediaRouter
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

                            val mimeType = if (isLiveStream) "application/x-mpegURL" else {
                                when {
                                    streamForChromecast.contains(".mpd", ignoreCase = true) || videoUrl.contains(".mpd", ignoreCase = true) -> "application/dash+xml"
                                    streamForChromecast.contains(".mkv", ignoreCase = true) -> "video/x-matroska"
                                    else -> "video/mp4"
                                }
                            }

                            val mediaInfo = MediaInfo.Builder(streamForChromecast)
                                .setStreamType(if (isLiveStream) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
                                .setContentType(mimeType)
                                .setMetadata(metadata)
                                .build()

                            Log.i(DIAG_TAG, "[Google Cast] Sending MediaInfo.load() with stream to RemoteMediaClient...")
                            withContext(Dispatchers.Main) {
                                remoteMediaClient.load(mediaInfo, true, 0)
                            }
                            rememberDevice(context, device)
                            _currentCastingDevice.value = device
                            _isCasting.value = true
                            return@withContext true
                        }
                    }
                }

                // Option C: DLNA fallback for hybrid TV models that advertise both Cast & DLNA
                if (!hostIp.isNullOrBlank()) {
                    val controlUrl = device.avTransportUrl ?: findControlUrlForIp(hostIp, physicalNetwork)
                    if (!controlUrl.isNullOrBlank()) {
                        Log.i(DIAG_TAG, "[Chromecast DLNA Fallback] Attempting DLNA on $controlUrl...")
                        val dlnaOk = sendDlnaPlay(controlUrl, streamForDlna, title, physicalNetwork)
                        if (dlnaOk) {
                            rememberDevice(context, device.copy(type = TvCastType.DLNA_TV, avTransportUrl = controlUrl))
                            _currentCastingDevice.value = device
                            _isCasting.value = true
                            return@withContext true
                        }
                    }
                }

                return@withContext false
            }

            // 3. SYSTEM CAST FALLBACK
            if (device.type == TvCastType.SYSTEM_CAST) {
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

            return@withContext false
        } catch (e: Exception) {
            Log.e(DIAG_TAG, "Fatal error executing castToDevice: ${e.message}", e)
            return@withContext false
        }
    }

    suspend fun castToDirectIp(
        context: Context,
        ipAddress: String,
        videoUrl: String,
        title: String,
        headers: Map<String, String> = emptyMap(),
        saveDevice: Boolean = true,
        mediaType: String = "live",
        preferDlna: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        val ip = ipAddress.trim()
        if (!isValidIpv4(ip)) {
            Log.e(DIAG_TAG, "Invalid IP address provided: $ip")
            return@withContext false
        }

        Log.i(DIAG_TAG, "Starting cast to Direct IP: $ip (Save=$saveDevice, mediaType=$mediaType, preferDlna=$preferDlna)")
        val physicalNetwork = getPhysicalWifiNetwork(context)

        if (preferDlna) {
            // 1. Direct DLNA requested by user
            Log.i(DIAG_TAG, "[Direct IP] Probing DLNA control URL on $ip...")
            val controlUrl = findControlUrlForIp(ip, physicalNetwork)
            if (!controlUrl.isNullOrBlank()) {
                val dev = TvCastDevice(
                    id = "direct_dlna_$ip",
                    name = "Smart TV (DLNA - $ip)",
                    type = TvCastType.DLNA_TV,
                    ipAddress = ip,
                    avTransportUrl = controlUrl,
                    isSaved = saveDevice
                )
                val ok = castToDevice(context, dev, videoUrl, title, headers, mediaType, forceDlna = true)
                if (ok) {
                    if (saveDevice) rememberDevice(context, dev)
                    return@withContext true
                }
            } else {
                Log.w(DIAG_TAG, "[Direct IP] No DLNA service found on $ip")
                return@withContext false
            }
        } else {
            // 2. Direct Chromecast / Google Cast requested by user
            Log.i(DIAG_TAG, "[Direct IP] Checking Google Cast port 8009 on $ip...")
            val isCastOpen = isPortOpen(ip, 8009, physicalNetwork, 500)
            if (isCastOpen) {
                val dev = TvCastDevice(
                    id = "direct_cast_$ip",
                    name = "Google TV / Cast ($ip)",
                    type = TvCastType.CHROMECAST,
                    ipAddress = ip,
                    isSaved = saveDevice
                )
                val ok = castToDevice(context, dev, videoUrl, title, headers, mediaType, forceDlna = false)
                if (ok) {
                    if (saveDevice) rememberDevice(context, dev)
                    return@withContext true
                }
            } else {
                Log.w(DIAG_TAG, "[Direct IP] Google Cast port 8009 is closed on $ip")
                return@withContext false
            }
        }

        return@withContext false
    }

    private fun isValidIpv4(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        return parts.all {
            val num = it.toIntOrNull() ?: return false
            num in 0..255
        }
    }

    fun stopCasting() {
        scope.launch(Dispatchers.IO) {
            try {
                nativeCastV2Client.stopMedia()
            } catch (_: Exception) {}
            try {
                nativeCastV2Client.disconnect()
            } catch (_: Exception) {}
            try {
                CastStreamProxyServer.stop()
            } catch (_: Exception) {}
            _isCasting.value = false
            _currentCastingDevice.value = null
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
            val isLive = streamUrl.contains(".m3u8", ignoreCase = true) ||
                    streamUrl.endsWith(".ts", ignoreCase = true) ||
                    streamUrl.contains("/live", ignoreCase = true)

            val mimeType = when {
                streamUrl.contains(".m3u8", ignoreCase = true) -> "application/x-mpegURL"
                streamUrl.contains(".mpd", ignoreCase = true) -> "application/dash+xml"
                streamUrl.contains(".ts", ignoreCase = true) || streamUrl.contains("/live", ignoreCase = true) -> "video/mp2t"
                streamUrl.contains(".mkv", ignoreCase = true) -> "video/x-matroska"
                else -> "video/mp4"
            }

            val dlnaOp = if (isLive) "00" else "01"
            val safeTitle = (if (title.isBlank()) "Stream" else title)
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
            val safeUrl = streamUrl
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

            val didlXml = "&lt;DIDL-Lite xmlns=&quot;urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/&quot; xmlns:dc=&quot;http://purl.org/dc/elements/1.1/&quot; xmlns:upnp=&quot;urn:schemas-upnp-org:metadata-1-0/upnp/&quot;&gt;" +
                    "&lt;item id=&quot;0&quot; parentID=&quot;-1&quot; restricted=&quot;1&quot;&gt;" +
                    "&lt;dc:title&gt;$safeTitle&lt;/dc:title&gt;" +
                    "&lt;upnp:class&gt;object.item.videoItem&lt;/upnp:class&gt;" +
                    "&lt;res protocolInfo=&quot;http-get:*:$mimeType:DLNA.ORG_OP=$dlnaOp;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000&quot;&gt;$safeUrl&lt;/res&gt;" +
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

            var uriSuccess = sendSoapRequest(controlUrl, "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI", setUriSoap, physicalNetwork)
            if (!uriSuccess) {
                Log.d(DIAG_TAG, "[DLNA Cast] Retrying SetAVTransportURI without metadata...")
                val fallbackSetUri = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\n" +
                        "  <s:Body>\n" +
                        "    <u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">\n" +
                        "      <InstanceID>0</InstanceID>\n" +
                        "      <CurrentURI>$safeUrl</CurrentURI>\n" +
                        "      <CurrentURIMetaData></CurrentURIMetaData>\n" +
                        "    </u:SetAVTransportURI>\n" +
                        "  </s:Body>\n" +
                        "</s:Envelope>"
                uriSuccess = sendSoapRequest(controlUrl, "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI", fallbackSetUri, physicalNetwork)
            }

            if (!uriSuccess) {
                Log.e(DIAG_TAG, "[DLNA Cast] SetAVTransportURI failed on $controlUrl")
                return@withContext false
            }

            // 3. Send Play
            delay(250)
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
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty("SOAPACTION", "\"$soapAction\"")
            conn.setRequestProperty("User-Agent", "DLNADOC/1.50 UPnP/1.0 DLNADR/1.0")
            conn.setRequestProperty("Connection", "close")

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
            val isLive = videoUrl.contains(".ts", ignoreCase = true) || videoUrl.contains("live", ignoreCase = true)
            val streamToUse = resolveDirectStreamUrl(videoUrl, isLive)

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
