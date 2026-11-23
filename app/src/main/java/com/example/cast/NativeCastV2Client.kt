package com.example.cast

import android.net.Network
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Native, autonomous Google CastV2 protocol client (port 8009 TLS).
 *
 * Runs fully independently of Google Play Services, enabling physical Wi-Fi socket binding
 * (physicalNetwork.bindSocket) to bypass VPN routing blocks.
 */
class NativeCastV2Client {

    companion object {
        private const val TAG = "NativeCastV2Client"
        private const val CAST_PORT = 8009
        const val DEFAULT_MEDIA_RECEIVER_APP_ID = "CC1AD845"

        private const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        private const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
        private const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
        private const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
    }

    private var sslSocket: SSLSocket? = null
    private var dataInput: DataInputStream? = null
    private var dataOutput: DataOutputStream? = null
    private var heartbeatJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val requestIdCounter = AtomicInteger(1)

    @Volatile
    private var currentTransportId: String? = null

    @Volatile
    private var currentSessionId: String? = null

    @Volatile
    var currentMediaSessionId: Long = -1L
        private set

    @Volatile
    var isConnected: Boolean = false
        private set

    private fun nextRequestId(): Int = requestIdCounter.getAndIncrement()

    /**
     * Connects directly to the Chromecast/Android TV on port 8009 using TLS.
     * If [physicalNetwork] is provided, binds socket to the physical Wi-Fi interface.
     */
    suspend fun connect(
        targetIp: String,
        physicalNetwork: Network?,
        timeoutMs: Int = 4000,
        context: android.content.Context? = null
    ): Boolean = withContext(Dispatchers.IO) {
        disconnect()

        try {
            Log.d(TAG, "Conectando por TLS a $targetIp:$CAST_PORT (physicalNetwork=${physicalNetwork != null})...")

            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, trustAllCerts, SecureRandom())
            }

            val rawSocket = Socket()
            rawSocket.soTimeout = 8000
            if (physicalNetwork != null) {
                try {
                    physicalNetwork.bindSocket(rawSocket)
                } catch (e: Exception) {
                    Log.v(TAG, "bindSocket warning: ${e.message}")
                }
            }

            // Bind to physical Wi-Fi local address if available to ensure packets bypass VPN tunnel (tun0)
            val localWifiIp = com.example.motor.sync.NetworkIpUtils.getPhysicalWifiIp(context)
            if (!localWifiIp.isNullOrBlank() && !rawSocket.isBound) {
                try {
                    rawSocket.bind(InetSocketAddress(localWifiIp, 0))
                    Log.d(TAG, "rawSocket successfully bound to local Wi-Fi IP: $localWifiIp")
                } catch (e: Exception) {
                    Log.v(TAG, "Socket bind to local IP $localWifiIp skipped: ${e.message}")
                }
            }

            rawSocket.connect(InetSocketAddress(targetIp, CAST_PORT), timeoutMs)

            val ssl = sslContext.socketFactory.createSocket(
                rawSocket,
                targetIp,
                CAST_PORT,
                true
            ) as SSLSocket

            ssl.startHandshake()
            sslSocket = ssl
            dataInput = DataInputStream(ssl.inputStream)
            dataOutput = DataOutputStream(ssl.outputStream)
            isConnected = true

            // Send initial CONNECT to receiver-0
            sendCastMessage(
                sourceId = "sender-0",
                destId = "receiver-0",
                namespace = NS_CONNECTION,
                payloadJson = "{\"type\":\"CONNECT\"}"
            )

            startHeartbeat()
            Log.i(TAG, "Conexión Cast TLS establecida con éxito a $targetIp:$CAST_PORT")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error conectando a Cast TLS ($targetIp): ${e.message}", e)
            disconnect()
            false
        }
    }

    /**
     * Launches the Default Media Receiver and loads the media stream URL.
     */
    suspend fun launchAndPlay(
        streamUrl: String,
        title: String,
        isLive: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isConnected || sslSocket == null) {
            Log.e(TAG, "No conectado a ningún dispositivo Cast.")
            return@withContext false
        }

        try {
            val launchReqId = nextRequestId()
            Log.d(TAG, "Enviando comando LAUNCH (appId=$DEFAULT_MEDIA_RECEIVER_APP_ID, reqId=$launchReqId)...")

            // 1. Enviar comando LAUNCH
            sendCastMessage(
                sourceId = "sender-0",
                destId = "receiver-0",
                namespace = NS_RECEIVER,
                payloadJson = "{\"type\":\"LAUNCH\",\"appId\":\"$DEFAULT_MEDIA_RECEIVER_APP_ID\",\"requestId\":$launchReqId}"
            )

            // 2. Leer respuestas hasta obtener el transportId de la aplicación levantada
            var transportId: String? = null
            var sessionId: String? = null
            val startTime = System.currentTimeMillis()

            while (System.currentTimeMillis() - startTime < 8000) {
                val msg = readCastMessage(timeoutMs = 3000) ?: continue
                if (msg.namespace == NS_HEARTBEAT && msg.payload.contains("PING")) {
                    sendCastMessage("sender-0", "receiver-0", NS_HEARTBEAT, "{\"type\":\"PONG\"}")
                }
                if (msg.namespace == NS_RECEIVER && msg.payload.contains("RECEIVER_STATUS")) {
                    val info = extractAppSessionInfo(msg.payload)
                    if (!info.transportId.isNullOrBlank()) {
                        transportId = info.transportId
                        sessionId = info.sessionId
                        Log.d(TAG, "Default Media Receiver activo! transportId=$transportId, sessionId=$sessionId")
                        break
                    }
                }
            }

            val finalTransportId = transportId ?: "web-0"
            currentTransportId = finalTransportId
            currentSessionId = sessionId

            // 3. Conectar con el transportId de la aplicación de medios
            sendCastMessage(
                sourceId = "sender-0",
                destId = finalTransportId,
                namespace = NS_CONNECTION,
                payloadJson = "{\"type\":\"CONNECT\"}"
            )

            delay(200)

            // 4. Preparar payload LOAD
            val mimeType = when {
                streamUrl.contains(".m3u8", ignoreCase = true) || isLive -> "application/x-mpegURL"
                streamUrl.contains(".mpd", ignoreCase = true) -> "application/dash+xml"
                streamUrl.contains(".ts", ignoreCase = true) -> "application/x-mpegURL"
                streamUrl.contains(".mkv", ignoreCase = true) -> "video/x-matroska"
                else -> "video/mp4"
            }

            val mediaJson = JSONObject().apply {
                put("contentId", streamUrl)
                put("streamType", if (isLive) "LIVE" else "BUFFERED")
                put("contentType", mimeType)
                put("metadata", JSONObject().apply {
                    put("type", 0)
                    put("metadataType", if (isLive) 0 else 1)
                    put("title", title)
                })
            }

            val loadReqId = nextRequestId()
            val loadPayload = JSONObject().apply {
                put("type", "LOAD")
                put("requestId", loadReqId)
                put("autoplay", true)
                put("currentTime", 0)
                if (!sessionId.isNullOrBlank()) {
                    put("sessionId", sessionId)
                }
                put("media", mediaJson)
            }.toString()

            Log.d(TAG, "Enviando comando LOAD a $finalTransportId (loadReqId=$loadReqId)...")
            val sendOk = sendCastMessage(
                sourceId = "sender-0",
                destId = finalTransportId,
                namespace = NS_MEDIA,
                payloadJson = loadPayload
            )
            Log.i(TAG, "LOAD enviado con éxito a CastV2 ($finalTransportId)")
            sendOk
        } catch (e: Exception) {
            Log.e(TAG, "Error durante launchAndPlay en CastV2: ${e.message}", e)
            false
        }
    }

    suspend fun stopMedia(): Boolean = withContext(Dispatchers.IO) {
        val transportId = currentTransportId ?: return@withContext false
        try {
            val reqId = nextRequestId()
            val mediaSess = if (currentMediaSessionId > 0) ",\"mediaSessionId\":$currentMediaSessionId" else ""
            sendCastMessage(
                sourceId = "sender-0",
                destId = transportId,
                namespace = NS_MEDIA,
                payloadJson = "{\"type\":\"STOP\",\"requestId\":$reqId$mediaSess}"
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Cast media: ${e.message}")
            false
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && isConnected) {
                delay(5000)
                try {
                    sendCastMessage(
                        sourceId = "sender-0",
                        destId = "receiver-0",
                        namespace = NS_HEARTBEAT,
                        payloadJson = "{\"type\":\"PING\"}"
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Heartbeat error: ${e.message}")
                    break
                }
            }
        }
    }

    fun disconnect() {
        isConnected = false
        heartbeatJob?.cancel()
        heartbeatJob = null
        try {
            dataInput?.close()
        } catch (_: Exception) {}
        try {
            dataOutput?.close()
        } catch (_: Exception) {}
        try {
            sslSocket?.close()
        } catch (_: Exception) {}
        dataInput = null
        dataOutput = null
        sslSocket = null
        currentTransportId = null
        currentMediaSessionId = -1L
    }

    // ─── CODIFICACIÓN Y DECODIFICACIÓN PROTOBUF CAST_CHANNEL ────────────────────

    private fun sendCastMessage(
        sourceId: String,
        destId: String,
        namespace: String,
        payloadJson: String
    ): Boolean {
        val out = dataOutput ?: return false
        val rawBytes = encodeCastMessage(sourceId, destId, namespace, payloadJson)
        return try {
            synchronized(out) {
                out.write(rawBytes)
                out.flush()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error writing cast message: ${e.message}")
            false
        }
    }

    private fun readCastMessage(timeoutMs: Int = 4000): CastMessage? {
        val input = dataInput ?: return null
        val sock = sslSocket ?: return null
        return try {
            sock.soTimeout = timeoutMs
            val length = input.readInt()
            if (length <= 0 || length > 65536) return null
            val buffer = ByteArray(length)
            input.readFully(buffer)
            decodeCastMessage(buffer)
        } catch (_: Exception) {
            null
        }
    }

    data class CastMessage(
        val sourceId: String,
        val destId: String,
        val namespace: String,
        val payload: String
    )

    private fun encodeCastMessage(
        sourceId: String,
        destId: String,
        namespace: String,
        payloadUtf8: String
    ): ByteArray {
        val body = ByteArrayOutputStream()

        // 1. protocol_version = 0 (varint) -> tag (1 << 3) | 0 = 0x08, val 0
        body.write(byteArrayOf(0x08, 0x00))

        // 2. source_id -> tag (2 << 3) | 2 = 0x12
        writeProtobufString(body, 2, sourceId)

        // 3. destination_id -> tag (3 << 3) | 2 = 0x1a
        writeProtobufString(body, 3, destId)

        // 4. namespace -> tag (4 << 3) | 2 = 0x22
        writeProtobufString(body, 4, namespace)

        // 5. payload_type = STRING (0) -> tag (5 << 3) | 0 = 0x28, val 0
        body.write(byteArrayOf(0x28, 0x00))

        // 6. payload_utf8 -> tag (6 << 3) | 2 = 0x32
        writeProtobufString(body, 6, payloadUtf8)

        val bodyBytes = body.toByteArray()
        val total = ByteArrayOutputStream()
        val dos = DataOutputStream(total)
        dos.writeInt(bodyBytes.size)
        dos.write(bodyBytes)
        dos.flush()
        return total.toByteArray()
    }

    private fun writeProtobufString(stream: OutputStream, fieldNum: Int, value: String) {
        val tag = (fieldNum shl 3) or 2
        val bytes = value.toByteArray(Charsets.UTF_8)
        stream.write(tag)
        writeVarint(stream, bytes.size)
        stream.write(bytes)
    }

    private fun writeVarint(stream: OutputStream, value: Int) {
        var v = value
        while ((v and 0x7F.inv()) != 0) {
            stream.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        stream.write(v and 0x7F)
    }

    fun decodeCastMessage(data: ByteArray): CastMessage {
        var pos = 0
        var sourceId = ""
        var destId = ""
        var namespace = ""
        var payloadUtf8 = ""

        while (pos < data.size) {
            val tagAndWire = data[pos].toInt() and 0xFF
            pos++
            val wireType = tagAndWire and 0x07
            val fieldNumber = tagAndWire ushr 3

            when (wireType) {
                0 -> { // Varint
                    while (pos < data.size && (data[pos].toInt() and 0x80) != 0) {
                        pos++
                    }
                    pos++
                }
                2 -> { // Length-delimited (string or embedded)
                    var length = 0
                    var shift = 0
                    while (pos < data.size) {
                        val b = data[pos].toInt() and 0xFF
                        pos++
                        length = length or ((b and 0x7F) shl shift)
                        if ((b and 0x80) == 0) break
                        shift += 7
                    }
                    if (pos + length <= data.size) {
                        val stringBytes = data.copyOfRange(pos, pos + length)
                        pos += length
                        val strVal = String(stringBytes, Charsets.UTF_8)
                        when (fieldNumber) {
                            2 -> sourceId = strVal
                            3 -> destId = strVal
                            4 -> namespace = strVal
                            6 -> payloadUtf8 = strVal
                        }
                    } else {
                        break
                    }
                }
                else -> {
                    // Skip unsupported wire type or break
                    break
                }
            }
        }

        return CastMessage(sourceId, destId, namespace, payloadUtf8)
    }

    data class AppSessionInfo(
        val transportId: String?,
        val sessionId: String?
    )

    fun extractAppSessionInfo(json: String): AppSessionInfo {
        try {
            val root = JSONObject(json)
            val status = root.optJSONObject("status")
            val apps = status?.optJSONArray("applications")
            if (apps != null && apps.length() > 0) {
                var selectedApp: JSONObject? = null
                for (i in 0 until apps.length()) {
                    val app = apps.optJSONObject(i) ?: continue
                    val appId = app.optString("appId")
                    if (appId.equals(DEFAULT_MEDIA_RECEIVER_APP_ID, ignoreCase = true)) {
                        selectedApp = app
                        break
                    }
                }
                if (selectedApp == null) {
                    selectedApp = apps.optJSONObject(0)
                }
                val transportId = selectedApp?.optString("transportId")?.takeIf { it.isNotBlank() }
                val sessionId = selectedApp?.optString("sessionId")?.takeIf { it.isNotBlank() }
                return AppSessionInfo(transportId, sessionId)
            }
        } catch (_: Throwable) {
            // JVM unit test stub fallback
        }

        val tid = Regex(""""transportId"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
        val sid = Regex(""""sessionId"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
        return AppSessionInfo(tid, sid)
    }
}
