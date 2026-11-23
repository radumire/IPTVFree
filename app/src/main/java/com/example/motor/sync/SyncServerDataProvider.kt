package com.example.motor.sync

import android.content.Context
import com.example.motor.config.Playlist

interface SyncServerDataProvider {
    fun getPlaylists(): List<Playlist>
    fun updatePlaylists(playlists: List<Playlist>)
    fun connectPlaylist(playlist: Playlist)
    fun getApplicationContext(): Context?
    fun getSubtitleKeys(): Pair<String, String> = Pair("", "")
    fun updateSubtitleKeys(openSubtitlesKey: String?, assrtToken: String?) {}
    fun syncData(playlists: List<Playlist>, openSubtitlesKey: String?, assrtToken: String?) {
        if (openSubtitlesKey != null || assrtToken != null) {
            updateSubtitleKeys(openSubtitlesKey, assrtToken)
        }
        updatePlaylists(playlists)
    }
}
