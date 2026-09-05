package com.example.cast

import androidx.mediarouter.media.MediaRouter

enum class TvCastType {
    CHROMECAST,
    DLNA_TV,
    SYSTEM_CAST
}

data class TvCastDevice(
    val id: String,
    val name: String,
    val type: TvCastType,
    val ipAddress: String? = null,
    val avTransportUrl: String? = null,
    val mediaRoute: MediaRouter.RouteInfo? = null,
    val manufacturer: String? = null,
    val modelName: String? = null
)
