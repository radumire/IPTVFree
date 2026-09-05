package com.example.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight local HTTP proxy server that relays IPTV streams (VOD and Live TV)
 * from the phone to Chromecast, Android TV, and DLNA devices.
 *
 * Benefits:
 * 1. Bypasses VPN restrictions: phone downloads via VPN and serves via physical Wi-Fi LAN to TV.
 * 2. Injects required IPTV headers (User-Agent, Referer, Authorization).
 * 3. Provides required CORS headers (Access-Control-Allow-Origin: *) for Google Cast receiver.
 * 4. Supports range requests for video seeking.
 */
object CastStreamProxyServer {

    private const val TAG = "CastStreamProxy"
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private val isRunning = AtomicBoolean(false)

    @Volatile
    private var activePort: Int = 8998

    @Volatile
    private var currentSourceUrl: String = ""

    @Volatile
    private var currentHeaders: Map<String, String> = emptyMap()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun start(sourceUrl: String, headers: Map<String, String>): String? {
        currentSourceUrl = sourceUrl
        currentHeaders = headers

        if (!isRunning.get() || serverSocket == null || serverSocket?.isClosed == true) {
            try {
                // Try preferred port 8998 or find available port
                val port = 8998
                serverSocket = try {
                    ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"))
                } catch (e: Exception) {
                    ServerSocket(0, 50, InetAddress.getByName("0.0.0.0"))
                }
                activePort = serverSocket!!.localPort
                isRunning.set(true)

                serverThread = Thread({
                    while (isRunning.get() && serverSocket != null && !serverSocket!!.isClosed) {
                        try {
                            val clientSocket = serverSocket!!.accept()
                            Thread({
                                handleClient(clientSocket)
                            }, "CastProxyClient-${System.currentTimeMillis()}").start()
                        } catch (e: Exception) {
                            if (!isRunning.get()) break
                        }
                    }
                }, "CastProxyServerThread").apply {
                    isDaemon = true
                    start()
                }
                Log.d(TAG, "Cast proxy server started on port $activePort")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Cast proxy server: ${e.message}", e)
                return null
            }
        }
        return getProxyUrl()
    }

    fun stop() {
        isRunning.set(false)
        try {
            serverSocket?.close()
            serverSocket = null
        } catch (_: Exception) {}
        try {
            serverThread?.interrupt()
            serverThread = null
        } catch (_: Exception) {}
        Log.d(TAG, "Cast proxy server stopped")
    }

    fun getProxyUrl(): String {
        val extension = when {
            currentSourceUrl.contains(".m3u8", ignoreCase = true) -> ".m3u8"
            currentSourceUrl.contains(".mpd", ignoreCase = true) -> ".mpd"
            currentSourceUrl.contains(".ts", ignoreCase = true) -> ".ts"
            currentSourceUrl.contains(".mkv", ignoreCase = true) -> ".mkv"
            else -> ".mp4"
        }
        return "http://localhost:$activePort/stream$extension"
    }

    fun getLocalProxyUrl(context: Context): String {
        val localIp = getPhysicalWifiIp(context) ?: "127.0.0.1"
        val extension = when {
            currentSourceUrl.contains(".m3u8", ignoreCase = true) -> ".m3u8"
            currentSourceUrl.contains(".mpd", ignoreCase = true) -> ".mpd"
            currentSourceUrl.contains(".ts", ignoreCase = true) -> ".ts"
            currentSourceUrl.contains(".mkv", ignoreCase = true) -> ".mkv"
            else -> ".mp4"
        }
        return "http://$localIp:$activePort/stream$extension"
    }

    fun getPhysicalWifiIp(context: Context): String? {
        try {
            // 1. WifiManager
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ipInt = wifi?.connectionInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                val ip = String.format(
                    java.util.Locale.US,
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
                if (ip != "0.0.0.0" && !ip.startsWith("127.")) {
                    return ip
                }
            }

            // 2. ConnectivityManager physical network binding
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                for (network in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(network) ?: continue
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                        val linkProps = cm.getLinkProperties(network)
                        val address = linkProps?.linkAddresses?.firstOrNull {
                            it.address is java.net.Inet4Address && !it.address.isLoopbackAddress
                        }?.address?.hostAddress
                        if (!address.isNullOrBlank() && !address.startsWith("127.")) {
                            return address
                        }
                    }
                }
            }

            // 3. Network Interfaces
            val ifaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (iface.isUp && !iface.isLoopback) {
                    val name = iface.name.lowercase()
                    if (name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("p2p") || name.startsWith("ap")) {
                        val addrs = iface.inetAddresses
                        while (addrs.hasMoreElements()) {
                            val addr = addrs.nextElement()
                            if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                                val host = addr.hostAddress ?: continue
                                if (!host.startsWith("127.")) return host
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting Wi-Fi IP: ${e.message}")
        }
        return null
    }

    private fun handleClient(clientSocket: Socket) {
        var inputStream: InputStream? = null
        var outputStream: OutputStream? = null
        var remoteResponse: Response? = null

        try {
            clientSocket.soTimeout = 15000
            inputStream = clientSocket.getInputStream()
            outputStream = clientSocket.getOutputStream()

            val reader = inputStream.bufferedReader()
            val requestLine = reader.readLine() ?: return
            Log.d(TAG, "Proxy Request: $requestLine")

            val clientHeaders = mutableMapOf<String, String>()
            var line: String? = reader.readLine()
            while (!line.isNullOrBlank()) {
                val colonIdx = line.indexOf(':')
                if (colonIdx > 0) {
                    val key = line.substring(0, colonIdx).trim()
                    val value = line.substring(colonIdx + 1).trim()
                    clientHeaders[key] = value
                }
                line = reader.readLine()
            }

            val targetUrl = currentSourceUrl
            if (targetUrl.isBlank()) {
                sendErrorResponse(outputStream, 404, "No media stream configured")
                return
            }

            // Build request to IPTV source with original headers + client Range header
            val reqBuilder = Request.Builder().url(targetUrl)
            
            // Add custom IPTV headers (User-Agent, token, etc.)
            currentHeaders.forEach { (k, v) ->
                if (!k.equals("Host", ignoreCase = true)) {
                    reqBuilder.header(k, v)
                }
            }
            if (!currentHeaders.containsKey("User-Agent")) {
                reqBuilder.header("User-Agent", "IPTVSmarters/3.1.5 (Linux; Android 12)")
            }

            // Forward Range header from TV/Chromecast for video seeking
            val rangeHeader = clientHeaders["Range"] ?: clientHeaders["range"]
            if (!rangeHeader.isNullOrBlank()) {
                reqBuilder.header("Range", rangeHeader)
            }

            val request = reqBuilder.build()
            remoteResponse = httpClient.newCall(request).execute()

            val responseCode = remoteResponse.code
            val contentType = remoteResponse.header("Content-Type") ?: guessMimeType(targetUrl)
            val contentLength = remoteResponse.header("Content-Length")
            val contentRange = remoteResponse.header("Content-Range")
            val acceptRanges = remoteResponse.header("Accept-Ranges") ?: "bytes"

            val statusLine = if (responseCode == 206) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n"
            outputStream.write(statusLine.toByteArray())
            outputStream.write("Content-Type: $contentType\r\n".toByteArray())
            outputStream.write("Access-Control-Allow-Origin: *\r\n".toByteArray())
            outputStream.write("Access-Control-Allow-Headers: *\r\n".toByteArray())
            outputStream.write("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n".toByteArray())
            outputStream.write("Accept-Ranges: $acceptRanges\r\n".toByteArray())
            outputStream.write("Connection: keep-alive\r\n".toByteArray())

            if (!contentRange.isNullOrBlank()) {
                outputStream.write("Content-Range: $contentRange\r\n".toByteArray())
            }
            if (!contentLength.isNullOrBlank()) {
                outputStream.write("Content-Length: $contentLength\r\n".toByteArray())
            }
            outputStream.write("\r\n".toByteArray())
            outputStream.flush()

            val remoteBodyStream = remoteResponse.body?.byteStream()
            if (remoteBodyStream != null) {
                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                while (remoteBodyStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    outputStream.flush()
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Proxy stream connection closed: ${e.message}")
        } finally {
            try { remoteResponse?.close() } catch (_: Exception) {}
            try { inputStream?.close() } catch (_: Exception) {}
            try { outputStream?.close() } catch (_: Exception) {}
            try { clientSocket.close() } catch (_: Exception) {}
        }
    }

    private fun guessMimeType(url: String): String {
        return when {
            url.contains(".m3u8", ignoreCase = true) -> "application/x-mpegURL"
            url.contains(".mpd", ignoreCase = true) -> "application/dash+xml"
            url.contains(".ts", ignoreCase = true) -> "video/mp2t"
            url.contains(".mkv", ignoreCase = true) -> "video/x-matroska"
            url.contains(".avi", ignoreCase = true) -> "video/x-msvideo"
            else -> "video/mp4"
        }
    }

    private fun sendErrorResponse(output: OutputStream, code: Int, message: String) {
        try {
            val response = "HTTP/1.1 $code $message\r\nContent-Type: text/plain\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: ${message.length}\r\n\r\n$message"
            output.write(response.toByteArray())
            output.flush()
        } catch (_: Exception) {}
    }
}
