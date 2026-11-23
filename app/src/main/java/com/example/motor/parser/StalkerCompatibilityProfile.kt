package com.example.motor.parser

/**
 * Representa la taxonomía de modelos y perfiles de dispositivos STB MAG/Stalker
 * garantizando coherencia interna absoluta entre User-Agent, X-User-Agent y versiones de hardware.
 */
enum class StalkerDeviceModel(
    val modelName: String,
    val hwVersion: String,
    val hwVersion2: String,
    val swVersion: String,
    val imageVersion: String,
    val vendor: String,
    val userAgent: String,
    val xUserAgent: String
) {
    /**
     * Perfil idéntico al que utilizaba la aplicación para no romper compatibilidad
     * con servidores que ya funcionaban con esta combinación.
     */
    LEGACY_HYBRID(
        modelName = "LEGACY_HYBRID",
        hwVersion = "MAG254",
        hwVersion2 = "1.7-BD-00",
        swVersion = "5.0.10",
        imageVersion = "218",
        vendor = "Infomir",
        userAgent = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3",
        xUserAgent = "Model: MAG254; Link: Ethernet"
    ),

    /**
     * Perfil 100% coherente para MAG254 (evita rechazos por inconsistencia MAG200 vs MAG254).
     */
    MAG254_COHERENT(
        modelName = "MAG254",
        hwVersion = "MAG254",
        hwVersion2 = "1.7-BD-00",
        swVersion = "5.0.10",
        imageVersion = "218",
        vendor = "Infomir",
        userAgent = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG254 stbapp ver: 4 rev: 2712 Safari/533.3",
        xUserAgent = "Model: MAG254; Link: Ethernet"
    ),

    /**
     * Perfil 100% coherente para MAG250 (usado ampliamente en middlewares Stalker clásicos).
     */
    MAG250_COHERENT(
        modelName = "MAG250",
        hwVersion = "MAG250",
        hwVersion2 = "1.7-BD-00",
        swVersion = "2.20.0",
        imageVersion = "218",
        vendor = "Infomir",
        userAgent = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3",
        xUserAgent = "Model: MAG250; Link: Ethernet"
    ),

    /**
     * Perfil adaptado para Ministra / Stalker 5.x modernos.
     */
    MINISTRA_STB(
        modelName = "AURA_HD",
        hwVersion = "MAG254",
        hwVersion2 = "1.7-BD-00",
        swVersion = "5.1.0",
        imageVersion = "218",
        vendor = "Infomir",
        userAgent = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG254 stbapp ver: 5 rev: 612 Safari/533.3",
        xUserAgent = "Model: MAG254; Link: WiFi"
    );

    fun toStalkerDevice(customSerial: String = ""): com.example.motor.config.StalkerDevice {
        return com.example.motor.config.StalkerDevice(
            hwVersion = this.hwVersion,
            vendor = this.vendor,
            imageVersion = this.imageVersion,
            userAgent = this.userAgent,
            xUserAgent = this.xUserAgent,
            serialNumber = customSerial,
            hwVersion2 = this.hwVersion2,
            swVersion = this.swVersion
        )
    }
}

/**
 * Configuración de negociación para llamadas get_profile.
 */
enum class StalkerProfileVariant {
    /**
     * Formato clásico de la app con ImageDescription y num_banks=2
     */
    STANDARD_V1,
    /**
     * Formato simplificado sin ver=ImageDescription que rechazan algunos servidores modernos
     */
    SIMPLIFIED_V2,
    /**
     * Formato con client_type=STB y versión numérica directa
     */
    MINISTRA_MODERN
}

/**
 * Modo de envío de tokens y autorización
 */
enum class StalkerAuthMode {
    BOTH_HEADER_AND_COOKIE,
    COOKIE_ONLY,
    HEADER_ONLY
}

/**
 * Estado y características aprendidas de un portal Stalker asociadas a una playlist específica.
 */
data class PortalCompatibilityState(
    val playlistId: Long,
    val host: String,
    val resolvedPortalUrl: String,
    val effectiveScheme: String, // "http" o "https"
    val deviceModel: StalkerDeviceModel = StalkerDeviceModel.LEGACY_HYBRID,
    val profileVariant: StalkerProfileVariant = StalkerProfileVariant.STANDARD_V1,
    val authMode: StalkerAuthMode = StalkerAuthMode.BOTH_HEADER_AND_COOKIE,
    val preferredTvCreateLinkVariant: String? = null,
    val preferredVodCreateLinkVariant: String? = null,
    val preferredSeriesCreateLinkVariant: String? = null,
    val lastDiscoveredAt: Long = System.currentTimeMillis()
)
