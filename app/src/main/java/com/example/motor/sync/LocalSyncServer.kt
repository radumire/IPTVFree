package com.example.motor.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import com.example.motor.config.*
import com.squareup.moshi.Types
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import javax.net.SocketFactory

object LocalSyncServer {
    private const val TAG = "LocalSyncServer"
    private const val TAG_QR = "TV_SYNC_QR"
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private var dataProvider: SyncServerDataProvider? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var isRunning = false
        private set
    var serverPort: Int = 9999
        private set

    data class SyncTarget(
        val host: String,
        val port: Int,
        val candidates: List<String> = emptyList()
    )

    fun isVpnActive(context: Context? = null): Boolean {
        val ctx = context ?: dataProvider?.getApplicationContext() ?: return false
        return try {
            val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    return true
                }
            }
            false
        } catch (e: Throwable) {
            false
        }
    }

    fun getPhysicalWifiOrLanNetwork(context: Context? = null): android.net.Network? {
        val ctx = context ?: dataProvider?.getApplicationContext() ?: return null
        return try {
            val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
            // Priority 1: Physical Wi-Fi or Ethernet WITHOUT TRANSPORT_VPN
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if ((caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    return network
                }
            }
            // Priority 2: Any Wi-Fi or Ethernet
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    return network
                }
            }
            null
        } catch (e: Throwable) {
            Log.w(TAG, "Error getting physical network: ${e.message}")
            null
        }
    }

    fun getPhysicalWifiIp(context: Context? = null): String? {
        val ctx = context ?: dataProvider?.getApplicationContext() ?: return null
        return NetworkIpUtils.getPhysicalWifiIp(ctx)
    }

    fun start(provider: SyncServerDataProvider, port: Int = 9999): Boolean {
        dataProvider = provider
        if (isRunning && serverSocket != null && !serverSocket!!.isClosed) return true

        val physicalNetwork = getPhysicalWifiOrLanNetwork(provider.getApplicationContext())

        for (p in listOf(port, 9998, 9997, 8888, 8080)) {
            try {
                // Explicitly bind to 0.0.0.0 (all interfaces: Wi-Fi, Ethernet, etc.)
                val ss = ServerSocket(p, 50, InetAddress.getByName("0.0.0.0"))
                serverSocket = ss
                serverPort = p
                isRunning = true

                serverThread = Thread {
                    while (isRunning && !ss.isClosed) {
                        try {
                            val clientSocket = ss.accept()
                            scope.launch(Dispatchers.IO) {
                                handleClientSocket(clientSocket)
                            }
                        } catch (e: Throwable) {
                            if (!isRunning) break
                        }
                    }
                }.apply {
                    name = "LocalSyncServerThread"
                    start()
                }

                Log.d(TAG, "Sync server started successfully on 0.0.0.0:$serverPort")
                return true
            } catch (e: Throwable) {
                Log.w(TAG, "Port $p taken or failed: ${e.message}")
            }
        }

        Log.e(TAG, "Could not bind to any port")
        isRunning = false
        return false
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
            serverSocket = null
        } catch (e: Throwable) {
            Log.e(TAG, "Error closing server socket", e)
        }
        try {
            serverThread?.interrupt()
            serverThread = null
        } catch (_: Throwable) {}
        Log.d(TAG, "Sync server stopped")
    }

    /**
     * Gets a SocketFactory bound to physical Wi-Fi or Ethernet network.
     * This bypasses any active VPN tunnel on Android, allowing direct LAN communication.
     */
    fun getWifiOrLanSocketFactory(context: Context?): SocketFactory? {
        val net = getPhysicalWifiOrLanNetwork(context ?: dataProvider?.getApplicationContext())
        return net?.socketFactory
    }

    private fun isClassBPrivate(ip: String): Boolean = NetworkIpUtils.isClassBPrivate(ip)

    private fun isValidLanIp(ip: String): Boolean = NetworkIpUtils.isValidLanIp(ip)

    private fun getIpPriorityScore(ip: String): Int {
        return when {
            ip.startsWith("192.168.") -> 100
            ip.startsWith("172.") && isClassBPrivate(ip) -> 80
            ip.startsWith("10.") -> 60
            else -> 10
        }
    }

    /**
     * Returns all viable local LAN IPv4 addresses, prioritizing physical Wi-Fi and Ethernet
     * and filtering out VPN tunnels (tun, tap, wireguard, rmnet, etc.)
     */
    fun getAllLocalIpAddresses(context: Context? = null): List<String> {
        val result = LinkedHashSet<String>()
        val effectiveContext = context ?: dataProvider?.getApplicationContext()

        // 1. Highest priority: Physical Wi-Fi / Ethernet IP from ConnectivityManager (GUARANTEED non-VPN physical LAN IP)
        val physicalWifiIp = getPhysicalWifiIp(effectiveContext)
        if (!physicalWifiIp.isNullOrBlank()) {
            result.add(physicalWifiIp)
        }

        // 2. WifiManager check (fallback for older APIs or standalone Wi-Fi)
        if (effectiveContext != null) {
            try {
                val wm = effectiveContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val ipInt = wm?.connectionInfo?.ipAddress ?: 0
                if (ipInt != 0) {
                    val ip = String.format(
                        java.util.Locale.US,
                        "%d.%d.%d.%d",
                        ipInt and 0xff,
                        ipInt shr 8 and 0xff,
                        ipInt shr 16 and 0xff,
                        ipInt shr 24 and 0xff
                    )
                    if (isValidLanIp(ip)) {
                        result.add(ip)
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "WifiManager check: ${e.message}")
            }
        }

        // 3. Iterate network interfaces with intelligent filtering and scoring
        val wifiAndEthIps = mutableListOf<String>()
        val regularLanIps = mutableListOf<String>()
        val virtualIps = mutableListOf<String>()

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return result.toList()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (!intf.isUp || intf.isLoopback) continue
                val name = intf.name.lowercase()

                val isVirtualOrVpn = name.startsWith("tun") ||
                        name.startsWith("tap") ||
                        name.startsWith("ppp") ||
                        name.startsWith("p2p") ||
                        name.startsWith("dummy") ||
                        name.startsWith("rmnet") ||
                        name.startsWith("wg") ||
                        name.contains("vpn") ||
                        name.contains("docker") ||
                        name.contains("vbox") ||
                        name.contains("clash") ||
                        name.contains("warp") ||
                        name.contains("tailscale")

                val isWifiOrEth = name.startsWith("wlan") ||
                        name.startsWith("wifi") ||
                        name.startsWith("eth") ||
                        name.startsWith("en") ||
                        name.startsWith("lan")

                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (host == "127.0.0.1" || host.startsWith("169.254.")) continue

                        if (isWifiOrEth && isValidLanIp(host)) {
                            wifiAndEthIps.add(host)
                        } else if (!isVirtualOrVpn && isValidLanIp(host)) {
                            regularLanIps.add(host)
                        } else if (isValidLanIp(host)) {
                            virtualIps.add(host)
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error enumerating interfaces: ${e.message}")
        }

        val ipComparator = Comparator<String> { ip1, ip2 ->
            getIpPriorityScore(ip2).compareTo(getIpPriorityScore(ip1))
        }

        wifiAndEthIps.sortWith(ipComparator)
        regularLanIps.sortWith(ipComparator)

        result.addAll(wifiAndEthIps)
        result.addAll(regularLanIps)
        if (result.isEmpty()) {
            result.addAll(virtualIps)
        }

        return result.toList()
    }

    fun getLocalIpAddress(context: Context? = null): String? {
        val effectiveContext = context ?: dataProvider?.getApplicationContext()
        val all = getAllLocalIpAddresses(effectiveContext)
        return all.firstOrNull()
    }

    private fun readHttpLine(input: java.io.InputStream): String? {
        val baos = java.io.ByteArrayOutputStream()
        var b = input.read()
        if (b == -1) return null
        while (b != -1) {
            if (b == '\n'.code) break
            if (b != '\r'.code) {
                baos.write(b)
            }
            b = input.read()
        }
        return String(baos.toByteArray(), Charsets.UTF_8)
    }

    private suspend fun handleClientSocket(socket: Socket) {
        val clientIp = socket.inetAddress?.hostAddress ?: "desconocida"
        Log.i("SYNC_DIAG", "accepted socket remote=$clientIp")

        try {
            val ctx = dataProvider?.getApplicationContext()
            val cm = ctx?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val physicalNetwork = getPhysicalWifiOrLanNetwork(ctx)
            val caps = if (physicalNetwork != null && cm != null) {
                try {
                    cm.getNetworkCapabilities(physicalNetwork)
                } catch (_: Throwable) {
                    null
                }
            } else null

            Log.i("SYNC_DIAG", "physical network=$physicalNetwork")
            Log.i("SYNC_DIAG", "physical network capabilities=$caps")

            if (physicalNetwork != null) {
                Log.i("SYNC_DIAG", "attempting socket bind")
                try {
                    physicalNetwork.bindSocket(socket)
                    Log.i("SYNC_DIAG", "socket bind SUCCESS")
                } catch (e: Throwable) {
                    Log.w("SYNC_DIAG", "socket bind FAILED: ${e.message}")
                    // Non-fatal: continue processing request normally over accepted socket
                }
            }

            socket.soTimeout = 10000
            val input = java.io.BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            val requestLine = readHttpLine(input) ?: return socket.close()

            val parts = requestLine.split(" ")
            if (parts.size < 2) return socket.close()

            val method = parts[0].uppercase()
            val rawPath = parts[1]
            val path = rawPath.substringBefore("?").trimEnd('/')

            var contentLength = 0
            while (true) {
                val hLine = readHttpLine(input) ?: break
                if (hLine.isEmpty()) break
                if (hLine.lowercase().startsWith("content-length:")) {
                    contentLength = hLine.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            val body = if (contentLength > 0) {
                val maxBytes = minOf(contentLength, 16 * 1024 * 1024)
                val bodyBytes = ByteArray(maxBytes)
                var totalRead = 0
                while (totalRead < maxBytes) {
                    val r = input.read(bodyBytes, totalRead, maxBytes - totalRead)
                    if (r <= 0) break
                    totalRead += r
                }
                String(bodyBytes, 0, totalRead, Charsets.UTF_8)
            } else ""

            if (method == "OPTIONS") {
                Log.i("SYNC_DIAG", "endpoint requested=$rawPath")
                Log.i("SYNC_DIAG", "response=200")
                Log.i("SYNC_DIAG", "playlists sent=0")
                sendHttpResponse(output, 200, "application/json", """{"status":"ok"}""")
                return
            }

            when {
                path == "/api/ping" -> {
                    Log.i("SYNC_DIAG", "endpoint requested=$rawPath")
                    Log.i("SYNC_DIAG", "response=200")
                    Log.i("SYNC_DIAG", "playlists sent=0")
                    sendHttpResponse(output, 200, "application/json", """{"status":"ok","deviceName":"IPTV App Sync Service"}""")
                }
                path == "/api/playlists" -> {
                    val provider = dataProvider
                    if (provider == null) {
                        Log.e("SYNC_DIAG", "endpoint requested=$rawPath")
                        Log.e("SYNC_DIAG", "response=500")
                        Log.e("SYNC_DIAG", "playlists sent=0")
                        sendHttpResponse(output, 500, "application/json", """{"error":"Sync provider not initialized"}""")
                    } else if (method == "GET") {
                        val plList = provider.getPlaylists()
                        Log.i("SYNC_DIAG", "endpoint requested=$rawPath")
                        Log.i("SYNC_DIAG", "response=200")
                        Log.i("SYNC_DIAG", "playlists sent=${plList.size}")
                        val json = JsonUtils.playlistsToJson(plList)
                        sendHttpResponse(output, 200, "application/json", json)
                    } else if (method == "POST") {
                        Log.i("SYNC_DIAG", "endpoint requested=$rawPath")
                        processSyncRequest(provider, body, output, clientIp)
                    } else {
                        Log.w("SYNC_DIAG", "endpoint requested=$rawPath")
                        Log.w("SYNC_DIAG", "response=405")
                        Log.w("SYNC_DIAG", "playlists sent=0")
                        sendHttpResponse(output, 405, "application/json", """{"error":"Method Not Allowed"}""")
                    }
                }
                path == "/api/sync" -> {
                    val provider = dataProvider
                    if (provider == null) {
                        Log.e("SYNC_DIAG", "endpoint requested=$rawPath")
                        Log.e("SYNC_DIAG", "response=500")
                        Log.e("SYNC_DIAG", "playlists sent=0")
                        sendHttpResponse(output, 500, "application/json", """{"error":"Sync provider not initialized"}""")
                    } else if (method == "POST") {
                        Log.i("SYNC_DIAG", "endpoint requested=$rawPath")
                        processSyncRequest(provider, body, output, clientIp)
                    } else if (method == "GET") {
                        val plList = provider.getPlaylists()
                        Log.i("SYNC_DIAG", "endpoint requested=$rawPath")
                        Log.i("SYNC_DIAG", "response=200")
                        Log.i("SYNC_DIAG", "playlists sent=${plList.size}")
                        val json = JsonUtils.playlistsToJson(plList)
                        sendHttpResponse(output, 200, "application/json", json)
                    } else {
                        Log.w("SYNC_DIAG", "endpoint requested=$rawPath")
                        Log.w("SYNC_DIAG", "response=405")
                        Log.w("SYNC_DIAG", "playlists sent=0")
                        sendHttpResponse(output, 405, "application/json", """{"error":"Method Not Allowed"}""")
                    }
                }
                path == "/api/add" -> {
                    Log.i("SYNC_DIAG", "endpoint requested=$rawPath")
                    val provider = dataProvider
                    if (provider == null) {
                        sendHttpResponse(output, 500, "application/json", """{"error":"Sync provider not initialized"}""")
                    } else if (method == "POST") {
                        processAddRequest(provider, body, output)
                    } else {
                        sendHttpResponse(output, 405, "application/json", """{"error":"Method Not Allowed"}""")
                    }
                }
                path == "/api/delete" -> {
                    Log.i("SYNC_DIAG", "endpoint requested=$rawPath")
                    val provider = dataProvider
                    if (provider == null) {
                        sendHttpResponse(output, 500, "application/json", """{"error":"Sync provider not initialized"}""")
                    } else if (method == "POST") {
                        processDeleteRequest(provider, body, output)
                    } else {
                        sendHttpResponse(output, 405, "application/json", """{"error":"Method Not Allowed"}""")
                    }
                }
                path == "/api/subtitles" -> {
                    Log.i("SYNC_DIAG", "endpoint requested=$rawPath")
                    val provider = dataProvider
                    if (provider == null) {
                        sendHttpResponse(output, 500, "application/json", """{"error":"Sync provider not initialized"}""")
                    } else if (method == "POST") {
                        processSubtitlesRequest(provider, body, output)
                    } else if (method == "GET") {
                        val (osKey, assrt) = provider.getSubtitleKeys()
                        val res = org.json.JSONObject().apply {
                            put("openSubtitlesKey", osKey)
                            put("assrtToken", assrt)
                        }
                        sendHttpResponse(output, 200, "application/json", res.toString())
                    } else {
                        sendHttpResponse(output, 405, "application/json", """{"error":"Method Not Allowed"}""")
                    }
                }
                else -> {
                    Log.i("SYNC_DIAG", "endpoint requested=$rawPath")
                    Log.i("SYNC_DIAG", "response=200")
                    Log.i("SYNC_DIAG", "playlists sent=0")
                    sendHttpResponse(output, 200, "text/html", getWebPortalHtml())
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error handling client socket", e)
        } finally {
            try { socket.close() } catch (_: Throwable) {}
        }
    }

    private suspend fun processSyncRequest(provider: SyncServerDataProvider, body: String, output: OutputStream, clientIp: String = "desconocida") {
        try {
            Log.d(TAG, "Received payload for sync: $body")
            val trimmedBody = body.trim()
            val incomingPlaylists: List<Playlist>
            var syncOpenSubtitlesKey: String? = null
            var syncAssrtToken: String? = null

            if (trimmedBody.startsWith("{")) {
                val jsonObject = org.json.JSONObject(trimmedBody)
                val playlistsArr = jsonObject.optJSONArray("playlists")
                incomingPlaylists = if (playlistsArr != null) {
                    JsonUtils.playlistsFromJson(playlistsArr.toString())
                } else emptyList()

                syncOpenSubtitlesKey = jsonObject.optString("openSubtitlesKey", "").takeIf { it.isNotBlank() }
                syncAssrtToken = jsonObject.optString("assrtToken", "").takeIf { it.isNotBlank() }
            } else {
                incomingPlaylists = JsonUtils.playlistsFromJson(body)
            }

            if (incomingPlaylists.isEmpty() && !body.contains("[]") && syncOpenSubtitlesKey == null && syncAssrtToken == null) {
                Log.e("SYNC_DIAG", "response=400")
                Log.e("SYNC_DIAG", "playlists sent=0")
                sendHttpResponse(output, 400, "application/json", """{"error":"Invalid payload format"}""")
                return
            }

            withContext(Dispatchers.Main) {
                val existingPlaylists = provider.getPlaylists().toMutableList()
                var addedCount = 0
                incomingPlaylists.forEach { pl ->
                    val index = existingPlaylists.indexOfFirst {
                        (it.name.equals(pl.name, ignoreCase = true) && it.type == pl.type) ||
                        (!it.url.isNullOrBlank() && it.url == pl.url) ||
                        (!it.host.isNullOrBlank() && it.host == pl.host && it.user == pl.user)
                    }
                    if (index >= 0) {
                        existingPlaylists[index] = pl.copy(
                            id = existingPlaylists[index].id,
                            active = existingPlaylists[index].active
                        )
                    } else {
                        val isFirst = existingPlaylists.isEmpty()
                        existingPlaylists.add(pl.copy(
                            id = System.currentTimeMillis() + addedCount,
                            active = isFirst
                        ))
                        addedCount++
                    }
                }

                if (existingPlaylists.none { it.active } && existingPlaylists.isNotEmpty()) {
                    existingPlaylists[0] = existingPlaylists[0].copy(active = true)
                }

                // Sincronización atómica de listas Y claves de subtítulos en una única transacción Room DB
                provider.syncData(existingPlaylists, syncOpenSubtitlesKey, syncAssrtToken)
                if (syncOpenSubtitlesKey != null || syncAssrtToken != null) {
                    Log.i(TAG, "Claves de subtítulos sincronizadas en la TV de forma atómica: OpenSubtitles=${syncOpenSubtitlesKey != null}, Assrt=${syncAssrtToken != null}")
                }
            }

            Log.i("SYNC_DIAG", "response=200")
            Log.i("SYNC_DIAG", "playlists sent=${incomingPlaylists.size}")
            sendHttpResponse(output, 200, "application/json", """{"status":"success","message":"Playlists synchronized successfully"}""")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling sync POST", e)
            Log.e("SYNC_DIAG", "response=500")
            Log.e("SYNC_DIAG", "playlists sent=0")
            sendHttpResponse(output, 500, "application/json", """{"error":"${e.message}"}""")
        }
    }

    private suspend fun processAddRequest(provider: SyncServerDataProvider, body: String, output: OutputStream) {
        try {
            val playlist = try {
                JsonUtils.moshi.adapter(Playlist::class.java).fromJson(body)
            } catch (e: Exception) {
                // Fallback parsing via JSONObject in case of any deserialization issues
                val json = org.json.JSONObject(body)
                Playlist(
                    id = json.optLong("id", System.currentTimeMillis()),
                    type = json.optString("type", "m3u"),
                    name = json.optString("name", ""),
                    url = json.optString("url", ""),
                    host = json.optString("host", ""),
                    user = json.optString("user", ""),
                    pass = json.optString("pass", ""),
                    port = json.optString("port", "80"),
                    mac = json.optString("mac", "")
                )
            }
            if (playlist == null || playlist.name.isBlank()) {
                sendHttpResponse(output, 400, "application/json", """{"error":"El nombre de la lista es obligatorio"}""")
                return
            }

            val existingPlaylists = provider.getPlaylists().toMutableList()
            var cleanHost = playlist.host.trim()
            var cleanMac = playlist.mac.trim()
            if (playlist.type == "stalker") {
                val macRegex = Regex("(?i)\\b([0-9A-F]{2}[:-][0-9A-F]{2}[:-][0-9A-F]{2}[:-][0-9A-F]{2}[:-][0-9A-F]{2}[:-][0-9A-F]{2})\\b")
                val match = macRegex.find(cleanHost)
                if (match != null) {
                    cleanMac = match.value.replace("-", ":").uppercase()
                    cleanHost = cleanHost.replace(match.value, "").replace(",", "").trim()
                } else {
                    val matchMac = macRegex.find(cleanMac)
                    if (matchMac != null) {
                        cleanMac = matchMac.value.replace("-", ":").uppercase()
                    }
                }
            }
            val shouldBeActive = existingPlaylists.isEmpty()
            val newPlaylist = playlist.copy(
                id = System.currentTimeMillis(),
                host = cleanHost,
                mac = cleanMac,
                active = shouldBeActive
            )

            val duplicate = findDuplicatePlaylist(newPlaylist, existingPlaylists)
            if (duplicate != null) {
                sendHttpResponse(output, 409, "application/json", """{"error":"La lista ya existe en la aplicación (${duplicate.name})"}""")
                return
            }

            existingPlaylists.add(newPlaylist)
            withContext(Dispatchers.Main) {
                provider.updatePlaylists(existingPlaylists)
            }
            sendHttpResponse(output, 200, "application/json", """{"status":"success","message":"Playlist added successfully"}""")
        } catch (e: Exception) {
            Log.e(TAG, "Error adding playlist", e)
            sendHttpResponse(output, 500, "application/json", """{"error":"${e.message}"}""")
        }
    }

    private suspend fun processDeleteRequest(provider: SyncServerDataProvider, body: String, output: OutputStream) {
        try {
            val jsonMapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
            val adapter = JsonUtils.moshi.adapter<Map<String, Any>>(jsonMapType)
            val data = adapter.fromJson(body)

            val idDouble = data?.get("id") as? Double
            val idLong = idDouble?.toLong() ?: (data?.get("id") as? Long) ?: (data?.get("id") as? String)?.toLongOrNull()

            if (idLong == null) {
                sendHttpResponse(output, 400, "application/json", """{"error":"Invalid or missing ID"}""")
                return
            }

            withContext(Dispatchers.Main) {
                val existingPlaylists = provider.getPlaylists().filter { it.id != idLong }
                provider.updatePlaylists(existingPlaylists)
            }

            sendHttpResponse(output, 200, "application/json", """{"status":"success","message":"Playlist deleted successfully"}""")
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting playlist", e)
            sendHttpResponse(output, 500, "application/json", """{"error":"${e.message}"}""")
        }
    }

    private fun sendHttpResponse(output: OutputStream, status: Int, contentType: String, body: String) {
        try {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val statusText = if (status == 200) "OK" else if (status == 400) "Bad Request" else if (status == 405) "Method Not Allowed" else "Error"
            val header = "HTTP/1.1 $status $statusText\r\n" +
                    "Content-Type: $contentType; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                    "Access-Control-Allow-Headers: Content-Type, Authorization, *\r\n" +
                    "Connection: close\r\n\r\n"
            output.write(header.toByteArray(Charsets.UTF_8))
            output.write(bytes)
            output.flush()
        } catch (e: Throwable) {
            Log.w(TAG, "sendHttpResponse error: ${e.message}")
        }
    }

    fun parseQrSyncTarget(qrData: String): SyncTarget? {
        Log.i(TAG_QR, "QR detectado: $qrData")
        return try {
            val trimmed = qrData.trim()
            val candidateList = mutableListOf<String>()

            // 1. If payload is JSON format
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                val typeMap = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
                val adapter = JsonUtils.moshi.adapter<Map<String, Any>>(typeMap)
                val map = adapter.fromJson(trimmed)
                if (map != null) {
                    val ip = map["ip"] as? String ?: (map["host"] as? String)
                    val port = (map["port"] as? Number)?.toInt() ?: 9999
                    val candidatesRaw = map["candidates"] as? List<*>
                    candidatesRaw?.forEach { c ->
                        if (c is String && c.isNotBlank()) candidateList.add(c.trim())
                    }
                    if (!ip.isNullOrBlank()) {
                        if (!candidateList.contains(ip.trim())) candidateList.add(0, ip.trim())
                        Log.i(TAG_QR, "IP/puerto detectados (JSON): ${ip.trim()}:$port con ${candidateList.size} candidatos")
                        return SyncTarget(ip.trim(), port, candidateList)
                    }
                    val url = map["url"] as? String
                    if (!url.isNullOrBlank()) {
                        return parseUrlToSyncTarget(url)
                    }
                }
            }

            // 2. Standard URL or custom protocol or IP:PORT
            parseUrlToSyncTarget(trimmed)
        } catch (e: Exception) {
            Log.e(TAG_QR, "Fallo al analizar datos del QR: ${e.message}", e)
            null
        }
    }

    private fun parseUrlToSyncTarget(rawUrl: String): SyncTarget? {
        try {
            var clean = rawUrl.trim()
            if (clean.startsWith("iptvsync://", ignoreCase = true)) {
                clean = "http://" + clean.substringAfter("://")
            } else if (!clean.startsWith("http://", ignoreCase = true) && !clean.startsWith("https://", ignoreCase = true)) {
                clean = "http://$clean"
            }
            val uri = java.net.URI(clean)
            val host = uri.host ?: clean.substringAfter("://").substringBefore(":").substringBefore("/").substringBefore("?")
            val port = if (uri.port != -1) uri.port else 9999

            val candidateList = mutableListOf<String>()
            if (!host.isNullOrBlank()) {
                candidateList.add(host)
            }

            // Check query parameters (e.g. ?candidates=192.168.1.100,10.0.0.5)
            val query = uri.query
            if (!query.isNullOrBlank()) {
                val params = query.split("&")
                for (param in params) {
                    val kv = param.split("=", limit = 2)
                    if (kv.size == 2 && kv[0].equals("candidates", ignoreCase = true)) {
                        val items = kv[1].split(",")
                        for (it in items) {
                            val c = it.trim()
                            if (c.isNotBlank() && !candidateList.contains(c)) {
                                candidateList.add(c)
                            }
                        }
                    }
                }
            }

            if (!host.isNullOrBlank()) {
                Log.i(TAG_QR, "IP/puerto detectados: $host:$port con ${candidateList.size} candidatos")
                return SyncTarget(host, port, candidateList)
            }
        } catch (e: Exception) {
            Log.e(TAG_QR, "Formato de URL inválido en QR: ${e.message}")
        }
        return null
    }

    fun syncPlaylistsWithTv(
        qrOrAddress: String,
        playlists: List<Playlist>,
        openSubtitlesKey: String? = null,
        assrtToken: String? = null,
        context: Context? = null,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val target = parseQrSyncTarget(qrOrAddress)
                if (target == null) {
                    val errMsg = "No se pudo extraer la dirección IP/puerto del código QR: $qrOrAddress"
                    Log.e(TAG_QR, errMsg)
                    launch(Dispatchers.Main) { onError(errMsg) }
                    return@launch
                }

                val candidateHosts = target.candidates.ifEmpty { listOf(target.host) }
                val port = target.port

                // Prepare clients:
                // 1. Direct Wi-Fi/LAN client (bypasses active VPN if supported)
                // 2. Default OkHttpClient (standard routing)
                val effectiveContext = context ?: dataProvider?.getApplicationContext()
                val isVpn = isVpnActive(effectiveContext)
                val wifiSocketFactory = getWifiOrLanSocketFactory(effectiveContext)

                val directWifiClient = if (wifiSocketFactory != null) {
                    okhttp3.OkHttpClient.Builder()
                        .socketFactory(wifiSocketFactory)
                        .connectTimeout(4000, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .readTimeout(8000, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .writeTimeout(8000, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .build()
                } else null

                val defaultClient = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(4000, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .readTimeout(8000, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .writeTimeout(8000, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build()

                // If VPN is active on THIS device, directWifiClient is tried first to bypass VPN tunnel.
                // If VPN is not active (e.g. Android TV), defaultClient is used first without risk of EPERM.
                val clientsToTry = if (isVpn) {
                    listOfNotNull(directWifiClient, defaultClient)
                } else {
                    listOfNotNull(defaultClient, directWifiClient)
                }

                var workingClient: okhttp3.OkHttpClient? = null
                var workingHost: String? = null
                val connectionErrors = mutableListOf<String>()

                // Probe candidate IPs to find the active server
                for (host in candidateHosts) {
                    val pingUrl = "http://$host:$port/api/ping"
                    val pingRequest = okhttp3.Request.Builder()
                        .url(pingUrl)
                        .get()
                        .build()

                    for (client in clientsToTry) {
                        try {
                            client.newCall(pingRequest).execute().use { response ->
                                val responseBody = response.body?.string() ?: ""
                                if (response.isSuccessful && (responseBody.contains("ok") || responseBody.contains("IPTV"))) {
                                    workingClient = client
                                    workingHost = host
                                    Log.i(TAG_QR, "Ping exitoso con $host:$port (bypassed VPN: ${client == directWifiClient})")
                                    break
                                } else {
                                    connectionErrors.add("$host:$port -> HTTP ${response.code}")
                                }
                            }
                        } catch (e: Exception) {
                            val msg = e.message ?: e.javaClass.simpleName
                            connectionErrors.add("$host:$port -> $msg")
                        }
                    }
                    if (workingClient != null && workingHost != null) {
                        break
                    }
                }

                val activeClient = workingClient
                val activeHost = workingHost

                if (activeClient == null || activeHost == null) {
                    val detail = connectionErrors.distinct().take(3).joinToString("; ")
                    val errMsg = "No se pudo conectar al otro dispositivo ($detail). Verifica que ambos dispositivos estén en la misma red Wi-Fi."
                    Log.e(TAG_QR, errMsg)
                    launch(Dispatchers.Main) { onError(errMsg) }
                    return@launch
                }

                // POST /api/sync with verified working connection
                val syncUrl = "http://$activeHost:$port/api/sync"
                val jsonPayload = if (!openSubtitlesKey.isNullOrBlank() || !assrtToken.isNullOrBlank()) {
                    val root = org.json.JSONObject()
                    root.put("playlists", org.json.JSONArray(JsonUtils.playlistsToJson(playlists)))
                    if (!openSubtitlesKey.isNullOrBlank()) {
                        root.put("openSubtitlesKey", openSubtitlesKey.trim())
                    }
                    if (!assrtToken.isNullOrBlank()) {
                        root.put("assrtToken", assrtToken.trim())
                    }
                    root.toString()
                } else {
                    JsonUtils.playlistsToJson(playlists)
                }

                val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                val requestBody = okhttp3.RequestBody.create(mediaType, jsonPayload)

                val syncRequest = okhttp3.Request.Builder()
                    .url(syncUrl)
                    .post(requestBody)
                    .build()

                activeClient.newCall(syncRequest).execute().use { response ->
                    val responseBody = response.body?.string() ?: ""
                    if (response.isSuccessful) {
                        Log.i(TAG_QR, "resultado de /api/sync: Exitoso (HTTP ${response.code} - $responseBody)")
                        val keysInfo = mutableListOf<String>()
                        if (!openSubtitlesKey.isNullOrBlank()) keysInfo.add("OpenSubtitles")
                        if (!assrtToken.isNullOrBlank()) keysInfo.add("Assrt")
                        val extraMsg = if (keysInfo.isNotEmpty()) " y claves (${keysInfo.joinToString(", ")})" else ""
                        launch(Dispatchers.Main) {
                            onSuccess("¡Sincronización exitosa! ${playlists.size} listas$extraMsg enviadas a la TV.")
                        }
                    } else {
                        val errMsg = "Error en el receptor al guardar listas (HTTP ${response.code}): $responseBody"
                        Log.e(TAG_QR, errMsg)
                        launch(Dispatchers.Main) {
                            onError(errMsg)
                        }
                    }
                }
            } catch (e: Exception) {
                val errMsg = "Error durante la sincronización: ${e.message}"
                Log.e(TAG_QR, errMsg, e)
                launch(Dispatchers.Main) {
                    onError(errMsg)
                }
            }
        }
    }

    private suspend fun processSubtitlesRequest(provider: SyncServerDataProvider, body: String, output: OutputStream) {
        try {
            val json = org.json.JSONObject(body)
            val osKey = json.optString("openSubtitlesKey", "").takeIf { it.isNotBlank() }
            val assrtToken = json.optString("assrtToken", "").takeIf { it.isNotBlank() }
            withContext(Dispatchers.Main) {
                provider.updateSubtitleKeys(osKey, assrtToken)
            }
            sendHttpResponse(output, 200, "application/json", """{"status":"success","message":"Subtitle keys saved on TV"}""")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving subtitle keys", e)
            sendHttpResponse(output, 500, "application/json", """{"error":"${e.message}"}""")
        }
    }

    fun sendPlaylistsToDevice(
        targetIpAndPort: String,
        playlists: List<Playlist>,
        openSubtitlesKey: String? = null,
        assrtToken: String? = null,
        context: Context? = null,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        syncPlaylistsWithTv(
            qrOrAddress = targetIpAndPort,
            playlists = playlists,
            openSubtitlesKey = openSubtitlesKey,
            assrtToken = assrtToken,
            context = context,
            onSuccess = { onSuccess() },
            onError = onError
        )
    }

    private fun getWebPortalHtml(): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8">
                <title>IPTV App Sync Portal</title>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <link href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.0.0/css/all.min.css" rel="stylesheet">
                <style>
                    :root {
                        --bg: #121214;
                        --surface: #1e1e24;
                        --surface-accent: #2a2a32;
                        --primary: #9b51e0;
                        --primary-hover: #bb6bd9;
                        --text: #f3f4f6;
                        --text-muted: #9ca3af;
                        --success: #10b981;
                        --error: #ef4444;
                        --border: rgba(255, 255, 255, 0.1);
                    }
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                        background: var(--bg);
                        color: var(--text);
                        line-height: 1.5;
                        padding: 20px;
                    }
                    .container {
                        max-width: 900px;
                        margin: 0 auto;
                    }
                    header {
                        text-align: center;
                        margin-bottom: 30px;
                        padding: 20px 0;
                    }
                    header i {
                        font-size: 48px;
                        color: var(--primary);
                        margin-bottom: 12px;
                    }
                    h1 { font-size: 28px; margin-bottom: 8px; }
                    .subtitle { color: var(--text-muted); font-size: 15px; }
                    
                    .grid {
                        display: grid;
                        grid-template-columns: 1fr;
                        gap: 24px;
                    }
                    @media(min-width: 768px) {
                        .grid { grid-template-columns: 1fr 1fr; }
                    }
                    
                    .card {
                        background: var(--surface);
                        border-radius: 16px;
                        padding: 24px;
                        border: 1px solid var(--border);
                        box-shadow: 0 4px 20px rgba(0,0,0,0.3);
                    }
                    .card-title {
                        font-size: 18px;
                        font-weight: 700;
                        margin-bottom: 16px;
                        display: flex;
                        align-items: center;
                        gap: 8px;
                        border-bottom: 1px solid var(--border);
                        padding-bottom: 12px;
                    }
                    .card-title i { color: var(--primary); }
                    
                    .playlist-item {
                        background: var(--surface-accent);
                        border-radius: 10px;
                        padding: 12px 16px;
                        margin-bottom: 10px;
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                    }
                    .playlist-info h4 { font-size: 15px; margin-bottom: 2px; }
                    .playlist-info p { font-size: 12px; color: var(--text-muted); }
                    .badge {
                        background: var(--primary);
                        color: white;
                        font-size: 10px;
                        padding: 2px 8px;
                        border-radius: 12px;
                        font-weight: bold;
                        text-transform: uppercase;
                    }
                    
                    .form-group { margin-bottom: 16px; }
                    label { display: block; font-size: 13px; font-weight: 600; margin-bottom: 6px; color: var(--text-muted); }
                    input, select {
                        width: 100%;
                        padding: 12px;
                        background: var(--bg);
                        border: 1px solid var(--border);
                        border-radius: 8px;
                        color: white;
                        font-size: 14px;
                    }
                    input:focus, select:focus {
                        outline: none;
                        border-color: var(--primary);
                    }
                    button {
                        width: 100%;
                        padding: 12px;
                        background: var(--primary);
                        color: white;
                        border: none;
                        border-radius: 8px;
                        font-weight: bold;
                        font-size: 15px;
                        cursor: pointer;
                        transition: background 0.2s;
                    }
                    button:hover { background: var(--primary-hover); }
                    .btn-danger {
                        background: transparent;
                        color: var(--error);
                        width: auto;
                        padding: 6px 10px;
                    }
                    .btn-danger:hover { background: rgba(239, 68, 68, 0.1); }
                    
                    .alert {
                        padding: 12px;
                        border-radius: 8px;
                        margin-bottom: 16px;
                        display: none;
                    }
                    .alert-success { background: rgba(16, 185, 129, 0.2); color: var(--success); border: 1px solid var(--success); }
                    .alert-error { background: rgba(239, 68, 68, 0.2); color: var(--error); border: 1px solid var(--error); }

                    .tab-buttons {
                        display: flex;
                        gap: 8px;
                        margin-bottom: 16px;
                    }
                    .tab-btn {
                        flex: 1;
                        padding: 8px;
                        background: var(--surface-accent);
                        border: 1px solid var(--border);
                        color: var(--text-muted);
                        font-size: 13px;
                    }
                    .tab-btn.active {
                        background: var(--primary);
                        color: white;
                        border-color: var(--primary);
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <header>
                        <i class="fa-solid fa-tv"></i>
                        <h1>Panel de Sincronización Smart TV</h1>
                        <p class="subtitle">Añade o gestiona listas IPTV directamente en tu Smart TV desde tu móvil o PC</p>
                    </header>

                    <div id="alert-box" class="alert"></div>

                    <div class="grid">
                        <div class="card">
                            <div class="card-title">
                                <i class="fa-solid fa-list-check"></i>
                                Listas en la Smart TV
                            </div>
                            <div id="playlists-container">
                                <p style="color: var(--text-muted); text-align: center; padding: 20px;">Cargando listas...</p>
                            </div>
                        </div>

                        <div class="card">
                            <div class="card-title">
                                <i class="fa-solid fa-plus-circle"></i>
                                Añadir Nueva Lista
                            </div>

                            <div class="tab-buttons">
                                <button type="button" class="tab-btn active" id="tab-m3u" onclick="switchType('m3u')">M3U / URL</button>
                                <button type="button" class="tab-btn" id="tab-xtream" onclick="switchType('xtream')">Xtream Codes</button>
                                <button type="button" class="tab-btn" id="tab-stalker" onclick="switchType('stalker')">Stalker Portal</button>
                            </div>

                            <form id="add-form" onsubmit="handleAddPlaylist(event)">
                                <input type="hidden" id="pl-type" value="m3u">

                                <div class="form-group">
                                    <label>Nombre de la Lista</label>
                                    <input type="text" id="pl-name" placeholder="Ej: Mi Lista de Canales" required>
                                </div>

                                <div id="fields-m3u">
                                    <div class="form-group">
                                        <label>URL M3U</label>
                                        <input type="url" id="pl-url" placeholder="http://servidor.com/get.php?username=...&password=...">
                                    </div>
                                </div>

                                <div id="fields-xtream" style="display: none;">
                                    <div class="form-group">
                                        <label>Servidor / Host URL</label>
                                        <input type="text" id="pl-host" placeholder="http://servidor.com:8080">
                                    </div>
                                    <div class="form-group">
                                        <label>Usuario</label>
                                        <input type="text" id="pl-user" placeholder="Tu usuario">
                                    </div>
                                    <div class="form-group">
                                        <label>Contraseña</label>
                                        <input type="password" id="pl-pass" placeholder="Tu contraseña">
                                    </div>
                                </div>

                                <div id="fields-stalker" style="display: none;">
                                    <div class="form-group">
                                        <label>Portal URL</label>
                                        <input type="text" id="stalker-host" placeholder="http://portal.domain.com:8080/c/">
                                    </div>
                                    <div class="form-group">
                                        <label>Dirección MAC</label>
                                        <input type="text" id="stalker-mac" placeholder="00:1A:79:XX:XX:XX">
                                    </div>
                                </div>

                                <button type="submit" style="margin-top: 10px;">
                                    <i class="fa-solid fa-paper-plane"></i> Enviar a la Smart TV
                                </button>
                            </form>
                        </div>
                    </div>

                    <div class="card" style="margin-top: 24px;">
                        <div class="card-title">
                            <i class="fa-solid fa-closed-captioning"></i>
                            Claves de API de Subtítulos
                        </div>
                        <p style="font-size: 13px; color: var(--text-muted); margin-bottom: 16px;">
                            Introduce o actualiza tus claves de OpenSubtitles y Assrt para que la Smart TV busque y descargue subtítulos automáticamente.
                        </p>
                        <form id="subtitles-form" onsubmit="handleSaveSubtitles(event)">
                            <div class="form-group">
                                <label><i class="fa-solid fa-key"></i> OpenSubtitles.com API Key</label>
                                <input type="text" id="sub-opensubtitles" placeholder="Pega tu API Key de OpenSubtitles.com">
                            </div>
                            <div class="form-group">
                                <label><i class="fa-solid fa-shield-halved"></i> ASSRT.net API Token</label>
                                <input type="text" id="sub-assrt" placeholder="Pega tu Token de Assrt.net">
                            </div>
                            <button type="submit" style="margin-top: 6px;">
                                <i class="fa-solid fa-floppy-disk"></i> Guardar Claves de Subtítulos en la TV
                            </button>
                        </form>
                    </div>
                </div>

                <script>
                    let currentType = 'm3u';

                    function switchType(type) {
                        currentType = type;
                        document.getElementById('pl-type').value = type;
                        document.querySelectorAll('.tab-btn').forEach(btn => btn.classList.remove('active'));
                        document.getElementById(`tab-${'$'}{type}`).classList.add('active');

                        document.getElementById('fields-m3u').style.display = type === 'm3u' ? 'block' : 'none';
                        document.getElementById('fields-xtream').style.display = type === 'xtream' ? 'block' : 'none';
                        document.getElementById('fields-stalker').style.display = type === 'stalker' ? 'block' : 'none';
                    }

                    async function loadPlaylists() {
                        try {
                            const res = await fetch('/api/playlists');
                            const data = await res.json();
                            const container = document.getElementById('playlists-container');
                            if (!Array.isArray(data) || data.length === 0) {
                                container.innerHTML = '<p style="color: var(--text-muted); text-align: center; padding: 20px;">No hay listas guardadas en la TV</p>';
                                return;
                            }
                            container.innerHTML = data.map(pl => `
                                <div class="playlist-item">
                                    <div class="playlist-info">
                                        <h4>${'$'}{pl.name} <span class="badge">${'$'}{pl.type}</span></h4>
                                        <p>${'$'}{pl.url || pl.host || ''}</p>
                                    </div>
                                    <button class="btn-danger" onclick="deletePlaylist(${'$'}{pl.id})">
                                        <i class="fa-solid fa-trash"></i>
                                    </button>
                                </div>
                            `).join('');
                        } catch (e) {
                            console.error(e);
                        }
                    }

                    async function handleAddPlaylist(e) {
                        e.preventDefault();
                        const name = document.getElementById('pl-name').value;
                        const type = currentType;
                        let payload = { id: Date.now(), name, type };

                        if (type === 'm3u') {
                            payload.url = document.getElementById('pl-url').value;
                        } else if (type === 'xtream') {
                            payload.host = document.getElementById('pl-host').value;
                            payload.user = document.getElementById('pl-user').value;
                            payload.pass = document.getElementById('pl-pass').value;
                        } else if (type === 'stalker') {
                            payload.host = document.getElementById('stalker-host').value;
                            payload.mac = document.getElementById('stalker-mac').value;
                        }

                        try {
                            const res = await fetch('/api/add', {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/json' },
                                body: JSON.stringify(payload)
                            });
                            const result = await res.json();
                            if (res.ok) {
                                showAlert('alert-box', '¡Lista enviada y guardada en la Smart TV con éxito!', 'success');
                                document.getElementById('add-form').reset();
                                loadPlaylists();
                            } else {
                                showAlert('alert-box', result.error || 'Error al enviar lista', 'error');
                            }
                        } catch (err) {
                            showAlert('alert-box', 'Error de conexión con la Smart TV', 'error');
                        }
                    }

                    async function deletePlaylist(id) {
                        if (!confirm('¿Eliminar esta lista de la Smart TV?')) return;
                        try {
                            const res = await fetch('/api/delete', {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/json' },
                                body: JSON.stringify({ id })
                            });
                            if (res.ok) {
                                showAlert('alert-box', 'Lista eliminada', 'success');
                                loadPlaylists();
                            }
                        } catch (err) {
                            showAlert('alert-box', 'Error al eliminar', 'error');
                        }
                    }

                    function showAlert(elementId, msg, type) {
                        const alert = document.getElementById(elementId);
                        alert.className = `alert alert-${'$'}{type}`;
                        alert.innerText = msg;
                        alert.style.display = 'block';
                        setTimeout(() => {
                            alert.style.display = 'none';
                        }, 5000);
                    }

                    async function loadSubtitles() {
                        try {
                            const res = await fetch('/api/subtitles');
                            if (res.ok) {
                                const data = await res.json();
                                if (data.openSubtitlesKey) {
                                    document.getElementById('sub-opensubtitles').value = data.openSubtitlesKey;
                                }
                                if (data.assrtToken) {
                                    document.getElementById('sub-assrt').value = data.assrtToken;
                                }
                            }
                        } catch (e) {
                            console.error(e);
                        }
                    }

                    async function handleSaveSubtitles(e) {
                        e.preventDefault();
                        const openSubtitlesKey = document.getElementById('sub-opensubtitles').value.trim();
                        const assrtToken = document.getElementById('sub-assrt').value.trim();
                        try {
                            const res = await fetch('/api/subtitles', {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/json' },
                                body: JSON.stringify({ openSubtitlesKey, assrtToken })
                            });
                            const result = await res.json();
                            if (res.ok) {
                                showAlert('alert-box', '¡Claves de subtítulos guardadas con éxito en la Smart TV!', 'success');
                            } else {
                                showAlert('alert-box', result.error || 'Error al guardar claves de subtítulos', 'error');
                            }
                        } catch (err) {
                            showAlert('alert-box', 'Error de conexión con la Smart TV', 'error');
                        }
                    }

                    loadPlaylists();
                    loadSubtitles();
                </script>
            </body>
            </html>
        """.trimIndent()
    }
}
