package com.example.interfaz.state

import com.example.motor.db.*
import com.example.motor.config.PlayerSettings
import com.example.motor.config.Playlist
import com.example.interfaz.AppThemeColors

// ─── HOME UI STATE ─────────────────────────────────────────────────────────────
data class HomeState(
    val activePlaylist: Playlist? = null,
    val activePlaylistName: String = "",
    val activePlaylistHost: String = "",
    val activePlaylistType: String = "",
    val activeProfile: UserProfile? = null,
    val activeProfileName: String = "",
    val recentChannels: List<PlaybackHistory> = emptyList(),
    val favorites: List<FavoriteItem> = emptyList(),
    val continueWatching: List<PlaybackHistory> = emptyList(),
    val newestMovies: List<IptvItem> = emptyList(),
    val totalLiveCount: Int = 0,
    val totalMoviesCount: Int = 0,
    val totalSeriesCount: Int = 0,
    val isStalkerDownloading: Boolean = false,
    val stalkerDownloadStats: String = "",
    val isResolvingMovie: Boolean = false,
    val isOnline: Boolean = true,
    val isLoading: Boolean = false,
    val error: String? = null
)

// ─── LIVE TV UI STATE ──────────────────────────────────────────────────────────
data class LiveTvState(
    val categories: Map<String, List<IptvItem>> = emptyMap(),
    val selectedCategory: String? = null,
    val channels: List<IptvItem> = emptyList(),
    val filteredChannels: List<IptvItem> = emptyList(),
    val selectedChannel: IptvItem? = null,
    val favoriteIds: Set<String> = emptySet(),
    val searchQuery: String = "",
    val selectedFilter: String = "ALL", // "ALL", "FAVORITES", "RECENT"
    val isLoading: Boolean = false,
    val error: String? = null
)

// ─── MOVIES UI STATE ───────────────────────────────────────────────────────────
data class MoviesState(
    val categories: Map<String, List<IptvItem>> = emptyMap(),
    val selectedCategory: String? = null,
    val movies: List<IptvItem> = emptyList(),
    val filteredMovies: List<IptvItem> = emptyList(),
    val selectedMovie: IptvItem? = null,
    val favoriteIds: Set<String> = emptySet(),
    val searchQuery: String = "",
    val tmdbBackdrop: String? = null,
    val tmdbOverview: String? = null,
    val tmdbRating: Float? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

// ─── SERIES UI STATE ───────────────────────────────────────────────────────────
data class SeriesState(
    val categories: Map<String, List<IptvItem>> = emptyMap(),
    val selectedCategory: String? = null,
    val seriesList: List<IptvItem> = emptyList(),
    val filteredSeries: List<IptvItem> = emptyList(),
    val selectedSeries: IptvItem? = null,
    val episodes: List<SeriesSeason> = emptyList(),
    val favoriteIds: Set<String> = emptySet(),
    val searchQuery: String = "",
    val tmdbBackdrop: String? = null,
    val tmdbOverview: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

// ─── PLAYER UI STATE ───────────────────────────────────────────────────────────
data class PlayerState(
    val title: String = "",
    val streamUrl: String = "",
    val headers: Map<String, String> = emptyMap(),
    val mediaType: String = "live",
    val isPlaying: Boolean = false,
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    val preferredPlayer: String = "EXOPLAYER",
    val isPipSupported: Boolean = false,
    val videoQuality: String = "Auto",
    val audioTracks: List<String> = emptyList(),
    val subtitleTracks: List<String> = emptyList(),
    val error: String? = null
)

// ─── PLAYLIST UI STATE ────────────────────────────────────────────────────────
data class PlaylistState(
    val playlists: List<Playlist> = emptyList(),
    val activePlaylist: Playlist? = null,
    val activePlaylistName: String = "",
    val currentProfile: UserProfile? = null,
    val isOnline: Boolean = true,
    val connectStatus: String? = null,
    val connectLog: String = "",
    val connectErrorMsg: String? = null,
    val isConnecting: Boolean = false,
    val isLoading: Boolean = false,
    val stalkerDownloading: Boolean = false,
    val stalkerDownloadStats: String = "",
    val error: String? = null
)

// ─── SETTINGS UI STATE ─────────────────────────────────────────────────────────
data class SettingsState(
    val profiles: List<UserProfile> = emptyList(),
    val currentProfile: UserProfile? = null,
    val playlists: List<Playlist> = emptyList(),
    val activePlaylist: Playlist? = null,
    val playerSettings: PlayerSettings = PlayerSettings(),
    val customThemes: Map<String, Map<String, String>> = emptyMap(),
    val activeTheme: AppThemeColors? = null,
    val isOnline: Boolean = true,
    val cacheSizeMb: Double = 0.0,
    val parentalPin: String = "",
    val isLoading: Boolean = false,
    val message: String? = null
)

// ─── PROFILE UI STATE ──────────────────────────────────────────────────────────
data class ProfileState(
    val profiles: List<UserProfile> = emptyList(),
    val currentProfile: UserProfile? = null,
    val isOnline: Boolean = true,
    val isLoading: Boolean = false,
    val error: String? = null
)

