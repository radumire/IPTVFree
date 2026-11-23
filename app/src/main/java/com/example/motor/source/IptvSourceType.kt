package com.example.motor.source

import com.example.motor.config.Playlist

enum class IptvSourceType {
    STALKER,
    XTREAM,
    M3U,
    UNKNOWN;

    companion object {
        fun fromPlaylist(playlist: Playlist): IptvSourceType {
            return when (playlist.type.lowercase().trim()) {
                "stalker", "mac" -> STALKER
                "xtream", "xc" -> XTREAM
                "m3u", "m3u8" -> M3U
                else -> UNKNOWN
            }
        }

        fun isStalker(playlist: Playlist): Boolean = fromPlaylist(playlist) == STALKER
        fun isXtream(playlist: Playlist): Boolean = fromPlaylist(playlist) == XTREAM
        fun isM3u(playlist: Playlist): Boolean = fromPlaylist(playlist) == M3U
    }
}
