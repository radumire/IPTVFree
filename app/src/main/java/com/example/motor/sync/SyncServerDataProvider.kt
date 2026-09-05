package com.example.motor.sync

import android.content.Context
import com.example.motor.config.Playlist

interface SyncServerDataProvider {
    fun getPlaylists(): List<Playlist>
    fun updatePlaylists(playlists: List<Playlist>)
    fun connectPlaylist(playlist: Playlist)
    fun getApplicationContext(): Context?
}
