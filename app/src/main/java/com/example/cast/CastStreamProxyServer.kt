package com.example.cast

import android.content.Context
import android.util.Log
import com.example.motor.sync.NetworkIpUtils
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-performance local HTTP proxy server for Google Cast and Smart TVs.
 * 
 * Features:
 * 1. Automatic CORS injection (Access-Control-Allow-Origin: *) to bypass Chromecast Web Receiver restrictions.
 * 2. HLS Manifest (.m3u8) proxying & segment URL rewriting so Chromecast can stream live IPTV channels.
 * 3. Range-request proxying for VOD (Movies & Series) enabling seamless seeking and fast buffering.
 * 4. Bypasses router/firewall blocking of high non-standard IPTV ports (e.g. :25461).
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

    @Volatile
    private var isLiveStreamMode: Boolean = false

    @Volatile
    private var appContext: Context? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun start(
        context: Context,
        sourceUrl: String,
        headers: Map<String, String> = emptyMap(),
        isLive: Boolean = false
    ): String? {
        appContext = context.applicationContext
        currentSourceUrl = sourceUrl
        currentHeaders = headers
        isLiveStreamMode = isLive

        if (!isRunning.get() || serverSocket == null || serverSocket?.isClosed == true) {
            try {
                val port = 8998
                serverSocket = try {
                    ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"))
                } catch (_: Exception) {
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
                        } catch (_: Exception) {
                            if (!isRunning.get()) break
                        }
                    }
                }, "CastProxyServerThread").apply {
                    isDaemon = true
                    start()
                }
                Log.d(TAG, "Cast proxy server started on port $activePort (isLive=$isLive)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Cast proxy server: ${e.message}")
                return null
            }
        }
        return getLocalProxyUrl(context, isLive = isLive)
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

    fun getLocalProxyUrl(context: Context, isLive: Boolean = false, isDlna: Boolean = false, sourceUrl: String = ""): String {
        val localIp = getPhysicalWifiIp(context) ?: "127.0.0.1"
        if (isLive) {
            return if (isDlna) "http://$localIp:$activePort/stream.ts" else "http://$localIp:$activePort/live.m3u8"
        }
        val target = if (sourceUrl.isNotBlank()) sourceUrl else currentSourceUrl
        val ext = when {
            target.contains(".mkv", ignoreCase = true) -> "mkv"
            target.contains(".avi", ignoreCase = true) -> "avi"
            else -> "mp4"
        }
        return "http://$localIp:$activePort/video.$ext"
    }

    fun getPhysicalWifiIp(context: Context?): String? {
        return NetworkIpUtils.getPhysicalWifiIp(context ?: appContext)
    }

    private fun handleClient(clientSocket: Socket) {
        var inputStream: InputStream? = null
        var outputStream: OutputStream? = null

        try {
            clientSocket.soTimeout = 25000
            inputStream = clientSocket.getInputStream()
            outputStream = clientSocket.getOutputStream()

            val reader = inputStream.bufferedReader()
            val requestLine = reader.readLine() ?: return

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

            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1]

            // 1. Fast CORS preflight handling (OPTIONS)
            if (method.equals("OPTIONS", ignoreCase = true)) {
                val preflight = "HTTP/1.1 204 No Content\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n" +
                        "Access-Control-Allow-Headers: *\r\n" +
                        "Access-Control-Max-Age: 86400\r\n" +
                        "Content-Length: 0\r\n\r\n"
                outputStream.write(preflight.toByteArray())
                outputStream.flush()
                return
            }

            // 2. Proxied HLS Chunk / Segment Request: /chunk?u=... or /segment?u=...
            if (path.startsWith("/chunk", ignoreCase = true) || path.startsWith("/segment", ignoreCase = true)) {
                handleChunkRelay(path, method, clientHeaders, outputStream)
                return
            }

            val isVideoFile = path.contains(".mp4", ignoreCase = true) ||
                    path.contains(".mkv", ignoreCase = true) ||
                    path.contains(".avi", ignoreCase = true) ||
                    path.contains("/video.", ignoreCase = true)

            // 3. Live HLS Playlist Request: /live.m3u8 or *.m3u8 (Only for non-video files)
            if (!isVideoFile && (path.contains(".m3u8", ignoreCase = true) || (isLiveStreamMode && !path.endsWith(".ts", ignoreCase = true)))) {
                handleHlsManifestRelay(path, method, clientHeaders, outputStream)
                return
            }

            // 4. Standard Stream Relay for VOD (Movies / Series) or continuous MPEG-TS
            handleStreamRelay(method, clientHeaders, outputStream)

        } catch (e: Exception) {
            Log.d(TAG, "Proxy client connection closed: ${e.message}")
        } finally {
            try { inputStream?.close() } catch (_: Exception) {}
            try { outputStream?.close() } catch (_: Exception) {}
            try { clientSocket.close() } catch (_: Exception) {}
        }
    }

    /**
     * Proxies individual HLS transport stream segments (.ts chunks) with full CORS headers.
     */
    private fun handleChunkRelay(
        path: String,
        method: String,
        clientHeaders: Map<String, String>,
        outputStream: OutputStream
    ) {
        val qIdx = path.indexOf('?')
        if (qIdx == -1) {
            sendErrorResponse(outputStream, 400, "Missing chunk URL parameter")
            return
        }

        val query = path.substring(qIdx + 1)
        var rawUrl: String? = null
        for (param in query.split("&")) {
            val eqIdx = param.indexOf('=')
            if (eqIdx != -1) {
                val key = param.substring(0, eqIdx)
                val value = param.substring(eqIdx + 1)
                if (key == "u" || key == "url") {
                    rawUrl = value
                    break
                }
            }
        }

        if (rawUrl.isNullOrBlank()) {
            sendErrorResponse(outputStream, 400, "Invalid chunk URL parameter")
            return
        }

        val targetUrl = try {
            URLDecoder.decode(rawUrl, "UTF-8")
        } catch (_: Exception) {
            rawUrl
        }

        val reqBuilder = Request.Builder().url(targetUrl)
        if (method.equals("HEAD", ignoreCase = true)) {
            reqBuilder.head()
        }
        currentHeaders.forEach { (k, v) ->
            if (!k.equals("Host", ignoreCase = true)) {
                reqBuilder.header(k, v)
            }
        }
        if (!currentHeaders.containsKey("User-Agent")) {
            reqBuilder.header("User-Agent", "IPTVSmarters/3.1.5 (Linux; Android 12)")
        }

        val rangeHeader = clientHeaders["Range"] ?: clientHeaders["range"]
        if (!rangeHeader.isNullOrBlank()) {
            reqBuilder.header("Range", rangeHeader)
        }

        var remoteResponse: Response? = null
        try {
            remoteResponse = httpClient.newCall(reqBuilder.build()).execute()
            val code = remoteResponse.code
            val contentType = remoteResponse.header("Content-Type") ?: "video/mp2t"
            val contentLength = remoteResponse.header("Content-Length")
            val contentRange = remoteResponse.header("Content-Range")
            val acceptRanges = remoteResponse.header("Accept-Ranges") ?: "bytes"

            val statusLine = if (code == 206) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n"
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

            if (!method.equals("HEAD", ignoreCase = true)) {
                val bodyStream = remoteResponse.body?.byteStream()
                if (bodyStream != null) {
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    while (bodyStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                    outputStream.flush()
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Chunk relay closed: ${e.message}")
        } finally {
            try { remoteResponse?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Proxies HLS .m3u8 manifest files and rewrites segment URLs to route through this local proxy.
     */
    private fun handleHlsManifestRelay(
        path: String,
        method: String,
        clientHeaders: Map<String, String>,
        outputStream: OutputStream
    ) {
        var sourceUrl = currentSourceUrl
        if (sourceUrl.isBlank()) {
            sendErrorResponse(outputStream, 404, "No live stream configured")
            return
        }

        // If source was passed as .ts, convert to .m3u8 for HLS manifest request
        if (sourceUrl.endsWith(".ts", ignoreCase = true)) {
            sourceUrl = sourceUrl.substringBeforeLast(".ts") + ".m3u8"
        } else if (sourceUrl.contains(".ts?", ignoreCase = true)) {
            sourceUrl = sourceUrl.replace(".ts?", ".m3u8?")
        }

        val reqBuilder = Request.Builder().url(sourceUrl)
        currentHeaders.forEach { (k, v) ->
            if (!k.equals("Host", ignoreCase = true)) {
                reqBuilder.header(k, v)
            }
        }
        if (!currentHeaders.containsKey("User-Agent")) {
            reqBuilder.header("User-Agent", "IPTVSmarters/3.1.5 (Linux; Android 12)")
        }

        var remoteResponse: Response? = null
        try {
            remoteResponse = httpClient.newCall(reqBuilder.build()).execute()
            if (!remoteResponse.isSuccessful) {
                // If .m3u8 failed, fall back to standard stream relay
                remoteResponse.close()
                handleStreamRelay(method, clientHeaders, outputStream)
                return
            }

            val bodyText = remoteResponse.body?.string() ?: ""
            if (!bodyText.contains("#EXTM3U")) {
                // Not a valid HLS playlist, fall back to streaming directly
                remoteResponse.close()
                handleStreamRelay(method, clientHeaders, outputStream)
                return
            }

            val finalBaseUrl = remoteResponse.request.url.toString()
            val localIp = getPhysicalWifiIp(appContext) ?: "127.0.0.1"
            val rewritten = rewriteM3u8Playlist(bodyText, finalBaseUrl, localIp, activePort)
            val rewrittenBytes = rewritten.toByteArray(Charsets.UTF_8)

            outputStream.write("HTTP/1.1 200 OK\r\n".toByteArray())
            outputStream.write("Content-Type: application/vnd.apple.mpegurl\r\n".toByteArray())
            outputStream.write("Access-Control-Allow-Origin: *\r\n".toByteArray())
            outputStream.write("Access-Control-Allow-Headers: *\r\n".toByteArray())
            outputStream.write("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n".toByteArray())
            outputStream.write("Cache-Control: no-cache, no-store, must-revalidate\r\n".toByteArray())
            outputStream.write("Connection: close\r\n".toByteArray())
            outputStream.write("Content-Length: ${rewrittenBytes.size}\r\n\r\n".toByteArray())
            outputStream.write(rewrittenBytes)
            outputStream.flush()

        } catch (e: Exception) {
            Log.e(TAG, "HLS Manifest error: ${e.message}")
            sendErrorResponse(outputStream, 502, "Error fetching live playlist")
        } finally {
            try { remoteResponse?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Rewrites each segment URL inside the .m3u8 manifest to point back to the local proxy.
     */
    fun rewriteM3u8Playlist(m3u8Text: String, baseUrl: String, localIp: String, port: Int): String {
        val sb = StringBuilder()
        val baseUri = try { URI(baseUrl) } catch (_: Exception) { null }

        m3u8Text.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                sb.append("\n")
            } else if (trimmed.startsWith("#")) {
                if (trimmed.startsWith("#EXT-X-KEY:") && trimmed.contains("URI=\"")) {
                    val uriPart = trimmed.substringAfter("URI=\"").substringBefore("\"")
                    val resolvedUri = resolveUrl(uriPart, baseUri, baseUrl)
                    val proxiedUri = "http://$localIp:$port/chunk?u=${URLEncoder.encode(resolvedUri, "UTF-8")}"
                    sb.append(trimmed.replace("URI=\"$uriPart\"", "URI=\"$proxiedUri\"")).append("\n")
                } else {
                    sb.append(trimmed).append("\n")
                }
            } else {
                // Segment URL line
                val resolvedUrl = resolveUrl(trimmed, baseUri, baseUrl)
                val proxiedUrl = "http://$localIp:$port/chunk?u=${URLEncoder.encode(resolvedUrl, "UTF-8")}"
                sb.append(proxiedUrl).append("\n")
            }
        }
        return sb.toString()
    }

    private fun resolveUrl(pathOrUrl: String, baseUri: URI?, baseUrlStr: String): String {
        if (pathOrUrl.startsWith("http://", ignoreCase = true) || pathOrUrl.startsWith("https://", ignoreCase = true)) {
            return pathOrUrl
        }
        return try {
            baseUri?.resolve(pathOrUrl)?.toString() ?: (baseUrlStr.substringBeforeLast("/") + "/" + pathOrUrl.removePrefix("/"))
        } catch (_: Exception) {
            val baseWithoutQuery = baseUrlStr.substringBefore("?")
            baseWithoutQuery.substringBeforeLast("/") + "/" + pathOrUrl.removePrefix("/")
        }
    }

    /**
     * Standard byte relay for VOD (Movies & Series) and continuous MPEG-TS streams.
     */
    private fun handleStreamRelay(method: String, clientHeaders: Map<String, String>, outputStream: OutputStream) {
        val targetUrl = currentSourceUrl
        if (targetUrl.isBlank()) {
            sendErrorResponse(outputStream, 404, "No media stream configured")
            return
        }

        val reqBuilder = Request.Builder().url(targetUrl)
        if (method.equals("HEAD", ignoreCase = true)) {
            reqBuilder.head()
        }
        currentHeaders.forEach { (k, v) ->
            if (!k.equals("Host", ignoreCase = true)) {
                reqBuilder.header(k, v)
            }
        }
        if (!currentHeaders.containsKey("User-Agent")) {
            reqBuilder.header("User-Agent", "IPTVSmarters/3.1.5 (Linux; Android 12)")
        }

        val rangeHeader = clientHeaders["Range"] ?: clientHeaders["range"]
        if (!rangeHeader.isNullOrBlank()) {
            reqBuilder.header("Range", rangeHeader)
        }

        var remoteResponse: Response? = null
        try {
            remoteResponse = httpClient.newCall(reqBuilder.build()).execute()

            val responseCode = remoteResponse.code
            val contentType = remoteResponse.header("Content-Type") ?: if (isLiveStreamMode) "video/mp2t" else "video/mp4"
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
            outputStream.write("transferMode.dlna.org: Streaming\r\n".toByteArray())

            if (!contentRange.isNullOrBlank()) {
                outputStream.write("Content-Range: $contentRange\r\n".toByteArray())
            }
            if (!contentLength.isNullOrBlank()) {
                outputStream.write("Content-Length: $contentLength\r\n".toByteArray())
            }
            outputStream.write("\r\n".toByteArray())
            outputStream.flush()

            if (!method.equals("HEAD", ignoreCase = true)) {
                val remoteBodyStream = remoteResponse.body?.byteStream()
                if (remoteBodyStream != null) {
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    while (remoteBodyStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                    outputStream.flush()
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Stream connection closed: ${e.message}")
        } finally {
            try { remoteResponse?.close() } catch (_: Exception) {}
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
