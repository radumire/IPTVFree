package com.example.motor.parser

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.URL
import java.net.UnknownHostException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

// ─── ROBUST DNS RESOLVER WITH FALLBACK ────────────────────────────────────────

object RobustDns : Dns {
    private val dnsCache = ConcurrentHashMap<String, List<InetAddress>>()

    override fun lookup(hostname: String): List<InetAddress> {
        dnsCache[hostname]?.let { return it }

        // 1. Try system DNS first
        try {
            val addresses = Dns.SYSTEM.lookup(hostname)
            if (addresses.isNotEmpty()) {
                dnsCache[hostname] = addresses
                return addresses
            }
        } catch (e: Exception) {
            Log.w("RobustDns", "System DNS lookup failed for $hostname: ${e.message}, trying DoH fallback...")
        }

        // 2. Fallback to Cloudflare & Google DNS over HTTPS
        val resolved = resolveViaDoh(hostname)
        if (resolved.isNotEmpty()) {
            dnsCache[hostname] = resolved
            return resolved
        }

        throw UnknownHostException("Unable to resolve host \"$hostname\": No address associated with hostname")
    }

    private fun resolveViaDoh(hostname: String): List<InetAddress> {
        val dohUrls = listOf(
            "https://1.1.1.1/dns-query?name=$hostname&type=A",
            "https://dns.google/resolve?name=$hostname&type=A"
        )
        for (dohUrl in dohUrls) {
            try {
                val conn = URL(dohUrl).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.setRequestProperty("Accept", "application/dns-json, application/json")
                conn.requestMethod = "GET"
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val answers = json.optJSONArray("Answer")
                    if (answers != null && answers.length() > 0) {
                        val list = mutableListOf<InetAddress>()
                        for (i in 0 until answers.length()) {
                            val ans = answers.getJSONObject(i)
                            val ip = ans.optString("data").trim()
                            if (ip.isNotEmpty() && !ip.contains(":")) {
                                try {
                                    list.add(InetAddress.getByName(ip))
                                } catch (_: Exception) {}
                            }
                        }
                        if (list.isNotEmpty()) return list
                    }
                }
            } catch (e: Exception) {
                Log.w("RobustDns", "DoH fallback failed on $dohUrl for $hostname: ${e.message}")
            }
        }
        return emptyList()
    }
}

// ─── ERROR TAXONOMY ──────────────────────────────────────────────────────────

sealed class NetworkError(val code: String, message: String, val httpStatus: Int? = null) : Exception(message) {
    class Timeout(msg: String) : NetworkError("TIMEOUT", msg)
    class Dns(msg: String) : NetworkError("DNS", msg)
    class ServerDown(msg: String) : NetworkError("SERVER_DOWN", msg)
    class ServerError(status: Int, msg: String) : NetworkError("SERVER_ERROR", msg, status)
    class Auth(msg: String) : NetworkError("AUTH", msg)
    class NotFound(msg: String) : NetworkError("NOT_FOUND", msg)
    class ProviderCode(status: Int, msg: String) : NetworkError("PROVIDER_CODE", msg, status)
    class RateLimit(msg: String) : NetworkError("RATE_LIMIT", msg, 429)
    class TlsError(msg: String) : NetworkError("TLS_ERROR", msg)
    class PortalBlockedMac(msg: String) : NetworkError("PORTAL_BLOCKED_MAC", msg, 403)
    class InvalidHandshake(msg: String) : NetworkError("INVALID_HANDSHAKE", msg)
    class InvalidProfile(msg: String) : NetworkError("INVALID_PROFILE", msg)
    class TokenExpired(msg: String) : NetworkError("TOKEN_EXPIRED", msg, 401)
    class InvalidStalkerResponse(msg: String) : NetworkError("INVALID_STALKER_RESPONSE", msg)
    class CreateLinkRejected(msg: String) : NetworkError("CREATE_LINK_REJECTED", msg)
    class PlaybackLinkInvalid(msg: String) : NetworkError("PLAYBACK_LINK_INVALID", msg)
    class Unknown(msg: String) : NetworkError("UNKNOWN", msg)
}

object BaseEngine {
    private const val TAG = "BaseEngine"

    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })

    private val sslContext = SSLContext.getInstance("SSL").apply {
        init(null, trustAllCerts, java.security.SecureRandom())
    }

    // OkHttpClient with robust DNS, TLS trust-all and NO_COOKIES so explicit headers (MAC, tokens) are never wiped
    val client = OkHttpClient.Builder()
        .dns(RobustDns)
        .connectionPool(okhttp3.ConnectionPool(20, 10, TimeUnit.MINUTES))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
        .hostnameVerifier { _, _ -> true }
        .cookieJar(CookieJar.NO_COOKIES)
        .build()

    fun classifyException(e: Exception): NetworkError {
        val msg = e.message?.lowercase() ?: ""
        return when {
            e is NetworkError -> e
            msg.contains("timeout") || msg.contains("timed out") -> NetworkError.Timeout("Tiempo de espera agotado")
            msg.contains("dns") || msg.contains("unknownhost") -> NetworkError.Dns("No se pudo resolver la dirección del servidor")
            msg.contains("refused") || msg.contains("econnrefused") || msg.contains("connect") -> NetworkError.ServerDown("El servidor rechazó la conexión")
            else -> NetworkError.Unknown(e.message ?: "Error de red desconocido")
        }
    }

    fun classifyHttpStatus(status: Int, statusText: String): NetworkError? {
        return when {
            status == 401 || status == 403 -> NetworkError.Auth("Usuario o contraseña incorrectos")
            status == 404 -> NetworkError.NotFound("Lista o stream no encontrado (404)")
            status == 408 || status == 504 -> NetworkError.Timeout("Tiempo de espera agotado")
            status > 599 || status < 100 -> NetworkError.ProviderCode(status, "Código de proveedor propio ($status)")
            status >= 500 -> NetworkError.ServerError(status, "Error interno del servidor ($status)")
            status >= 400 -> NetworkError.Unknown("Error del servidor ($status $statusText)")
            else -> null
        }
    }

    fun isDividerOrHeader(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        
        if (trimmed.contains("---") || trimmed.contains("===") || trimmed.contains("***") || 
            trimmed.contains("___") || trimmed.contains("|||") || trimmed.contains("###") || 
            trimmed.contains(":::") || trimmed.contains("||") || trimmed.contains("------") ||
            trimmed.contains("======") || trimmed.contains("******") || trimmed.contains("██") ||
            trimmed.contains("▓▓") || trimmed.contains("▒▒") || trimmed.contains("░░")) {
            return true
        }
        
        if ((trimmed.startsWith("<") && trimmed.endsWith(">")) || 
            (trimmed.startsWith("[") && trimmed.endsWith("]")) || 
            (trimmed.startsWith("【") && trimmed.endsWith("】")) || 
            (trimmed.startsWith("★") && trimmed.endsWith("★")) ||
            (trimmed.startsWith("►") && trimmed.endsWith("◄")) ||
            (trimmed.startsWith("•") && trimmed.endsWith("•")) ||
            (trimmed.startsWith("*") && trimmed.endsWith("*")) ||
            trimmed.startsWith("------") || trimmed.endsWith("------")) {
            return true
        }
        
        val nameLower = trimmed.lowercase()
        if (nameLower.contains("click aquí") || nameLower.contains("click aqui") || 
            nameLower.contains("doble click") || nameLower.contains("bienvenido") ||
            nameLower.contains("siguenos") || nameLower.contains("grupo telegram") ||
            nameLower.contains("síguenos") || nameLower.contains("telegram:") ||
            nameLower.contains("whatsapp:") || nameLower.contains("soporte:") ||
            nameLower.contains("servidor:") || nameLower.contains("info:") ||
            nameLower.contains("contacto:") || nameLower.contains("atencion:") ||
            nameLower.contains("----") || nameLower.contains("====")) {
            return true
        }
        
        return false
    }

    suspend fun executeGetRequest(url: String, customHeaders: Map<String, String> = emptyMap(), timeoutSeconds: Long = 45): String = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        val backoffs = listOf(1000L, 2500L, 5000L)

        for (attempt in 1..3) {
            try {
                var currentUrl = url
                if (currentUrl.startsWith("https://", ignoreCase = true) && currentUrl.contains(":80")) {
                    currentUrl = "http" + currentUrl.substring(5)
                }

                val builder = Request.Builder().url(currentUrl)
                builder.header("User-Agent", "VLC/3.0.18 LibVLC/3.0.18")
                customHeaders.forEach { (k, v) -> builder.header(k, v) }

                val okClient = client.newBuilder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .build()

                okClient.newCall(builder.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        return@withContext response.body?.string() ?: ""
                    }
                    val err = classifyHttpStatus(response.code, response.message)
                    if (err != null) throw err
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException || e.cause is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Attempt $attempt failed for URL $url: ${e.message}")
                val msg = e.message?.lowercase() ?: ""
                if (url.startsWith("https://", ignoreCase = true) && 
                    (e is javax.net.ssl.SSLException || msg.contains("tls") || msg.contains("ssl") || msg.contains("certificate") || msg.contains("handshake") || msg.contains("unrecognized ssl message"))) {
                    val fallbackUrl = "http" + url.substring(5)
                    Log.w(TAG, "TLS/SSL error on executeGetRequest for $url, falling back to $fallbackUrl")
                    return@withContext executeGetRequest(fallbackUrl, customHeaders, timeoutSeconds)
                }

                lastError = e
                if (e is NetworkError && (e.code == "AUTH" || e.code == "NOT_FOUND")) {
                    throw e
                }
                if (attempt < 3) {
                    kotlinx.coroutines.delay(backoffs[attempt - 1])
                }
            }
        }
        throw classifyException(lastError ?: Exception("Error de red desconocido"))
    }
}
