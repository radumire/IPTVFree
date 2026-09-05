package com.example.motor.config

import com.example.motor.db.IptvItem
import com.example.motor.db.EpgChannelData
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import android.util.Log

// ─── PLAYLIST DATA MODEL ─────────────────────────────────────────────────────

data class Playlist(
    val id: Long,
    val type: String, // "m3u", "xtream", "stalker"
    val name: String,
    val url: String = "",
    val host: String = "",
    val user: String = "",
    val pass: String = "",
    val port: String = "80",
    val expires: String = "",
    val mac: String = "",
    val stalkerDeviceJson: String = "", // StalkerDevice serialized as JSON
    val active: Boolean = false
)

fun formatMacAddress(input: String): String {
    val cleanHex = input.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }.take(12)
    return cleanHex.chunked(2).joinToString(":")
}

fun normalizeHost(host: String): String {
    var h = host.trim().trimEnd('/')
    if (h.isEmpty()) return ""
    if (!h.startsWith("http://", ignoreCase = true) && !h.startsWith("https://", ignoreCase = true)) {
        h = "http://$h"
    }
    return try {
        val uri = java.net.URI(h)
        val hostPart = uri.host?.lowercase() ?: h.lowercase()
        val portPart = if (uri.port != -1) ":${uri.port}" else ""
        val pathPart = uri.path?.trimEnd('/')?.lowercase() ?: ""
        "$hostPart$portPart$pathPart"
    } catch (e: Exception) {
        h.lowercase().removePrefix("http://").removePrefix("https://").trimEnd('/')
    }
}

fun normalizeUrl(url: String): String {
    return url.trim().trimEnd('/').lowercase()
}

fun findDuplicatePlaylist(newPl: Playlist, existingList: List<Playlist>, excludeId: Long? = null): Playlist? {
    val normNewType = newPl.type.lowercase().trim()
    val isNewXtream = normNewType == "xtream" || normNewType == "xc"
    val isNewM3u = normNewType == "m3u" || normNewType == "m3u8"
    val isNewStalker = normNewType == "stalker"

    val newNormHost = normalizeHost(newPl.host)
    val newRawUrl = newPl.url.trim()
    val newNormUrl = if (newRawUrl.isNotBlank()) normalizeUrl(newRawUrl) else ""
    val newNormUser = newPl.user.trim().lowercase()
    val newMacFormatted = formatMacAddress(newPl.mac)

    for (existing in existingList) {
        if (excludeId != null && existing.id == excludeId) continue

        val normExistType = existing.type.lowercase().trim()
        val isExistXtream = normExistType == "xtream" || normExistType == "xc"
        val isExistM3u = normExistType == "m3u" || normExistType == "m3u8"
        val isExistStalker = normExistType == "stalker"

        val existNormHost = normalizeHost(existing.host)
        val existRawUrl = existing.url.trim()
        val existNormUrl = if (existRawUrl.isNotBlank()) normalizeUrl(existRawUrl) else ""
        val existNormUser = existing.user.trim().lowercase()
        val existMacFormatted = formatMacAddress(existing.mac)

        // 1. Xtream / XC Duplicate Check:
        // Same server is allowed for different accounts. Only duplicate if BOTH host AND username match!
        if (isNewXtream && isExistXtream) {
            if (newNormHost.isNotBlank() && newNormHost == existNormHost &&
                newNormUser.isNotBlank() && newNormUser == existNormUser) {
                return existing
            }
            // If explicit URLs are entered, duplicate if both URL and user match
            if (newNormUrl.isNotBlank() && newNormUrl == existNormUrl &&
                (newNormUser.isBlank() || existNormUser.isBlank() || newNormUser == existNormUser)) {
                return existing
            }
            continue
        }

        // 2. Stalker Duplicate Check:
        // Host and MAC address must match
        if (isNewStalker && isExistStalker) {
            if (newNormHost.isNotBlank() && newNormHost == existNormHost &&
                newMacFormatted.isNotBlank() && newMacFormatted == existMacFormatted) {
                return existing
            }
            continue
        }

        // 3. M3U Duplicate Check:
        if (isNewM3u && isExistM3u) {
            val effectiveNewUrl = if (newNormUrl.isNotBlank()) newNormUrl else newNormHost
            val effectiveExistUrl = if (existNormUrl.isNotBlank()) existNormUrl else existNormHost
            if (effectiveNewUrl.isNotBlank() && effectiveNewUrl == effectiveExistUrl) {
                return existing
            }
            continue
        }

        // 4. Cross-type fallbacks:
        if (newNormHost.isNotBlank() && newNormHost == existNormHost) {
            if (newNormUser.isNotBlank() && newNormUser == existNormUser) {
                return existing
            }
            if (newMacFormatted.isNotBlank() && newMacFormatted == existMacFormatted) {
                return existing
            }
        }
    }
    return null
}

// ─── SETTINGS DATA MODEL ─────────────────────────────────────────────────────

data class PlayerSettings(
    val lang: String = "es",
    val subtitles: Boolean = false,
    val subtitleLang: String = "Español",
    val subtitleSize: String = "Normal",
    val subtitleColor: String = "Blanco",
    val subtitleBg: Boolean = true,
    val player: String = "Player integrado",
    val decode: String = "Automático",
    val ratio: String = "Ajustar",
    val autoFullscreen: Boolean = false,
    val skipSilence: Boolean = false,
    val notifications: Boolean = true,
    val autoUpdate: Boolean = false,
    val tvMode: Boolean = false,
    val quality: String = "Auto",
    val timeout: String = "15s",
    val timeFormat: String = "24h",
    val parentalEnabled: Boolean = false,
    val parentalPin: String = "",
    val parentalRating: String = "12+",
    val showTv: Boolean = true,
    val showMovies: Boolean = true,
    val showSeries: Boolean = true,
    val hiddenTvCategories: List<String> = emptyList(),
    val hiddenMovieCategories: List<String> = emptyList(),
    val hiddenSeriesCategories: List<String> = emptyList()
)

// ─── STALKER DEVICE CONFIG ───────────────────────────────────────────────────

data class StalkerDevice(
    val hwVersion: String = "MAG254",
    val vendor: String = "Infomir",
    val imageVersion: String = "218",
    val userAgent: String = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3",
    val xUserAgent: String = "Model: MAG254; Link: Ethernet",
    val serialNumber: String = "",
    val hwVersion2: String = "1.7-BD-00",
    val swVersion: String = "5.0.10"
)

// ─── JSON UTILITIES ──────────────────────────────────────────────────────────

object JsonUtils {
    val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    fun playlistsFromJson(json: String): List<Playlist> {
        val type = Types.newParameterizedType(List::class.java, Playlist::class.java)
        return try { moshi.adapter<List<Playlist>>(type).fromJson(json) ?: emptyList() } catch (e: Exception) { emptyList() }
    }

    fun playlistsToJson(list: List<Playlist>): String {
        val type = Types.newParameterizedType(List::class.java, Playlist::class.java)
        return moshi.adapter<List<Playlist>>(type).toJson(list)
    }

    fun stringSetFromJson(json: String): Set<String> {
        val type = Types.newParameterizedType(Set::class.java, String::class.java)
        return try { moshi.adapter<Set<String>>(type).fromJson(json) ?: emptySet() } catch (e: Exception) { emptySet() }
    }

    fun stringSetToJson(set: Set<String>): String {
        val type = Types.newParameterizedType(Set::class.java, String::class.java)
        return moshi.adapter<Set<String>>(type).toJson(set)
    }

    fun customThemesFromJson(json: String): Map<String, Map<String, String>> {
        val stringMapType = Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
        val type = Types.newParameterizedType(Map::class.java, String::class.java, stringMapType)
        return try { moshi.adapter<Map<String, Map<String, String>>>(type).fromJson(json) ?: emptyMap() } catch (e: Exception) { emptyMap() }
    }

    fun customThemesToJson(map: Map<String, Map<String, String>>): String {
        val stringMapType = Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
        val type = Types.newParameterizedType(Map::class.java, String::class.java, stringMapType)
        return moshi.adapter<Map<String, Map<String, String>>>(type).toJson(map)
    }

    fun settingsFromJson(json: String): PlayerSettings {
        return try { moshi.adapter(PlayerSettings::class.java).fromJson(json) ?: PlayerSettings() } catch (e: Exception) { PlayerSettings() }
    }

    fun settingsToJson(settings: PlayerSettings): String {
        return moshi.adapter(PlayerSettings::class.java).toJson(settings)
    }

    fun stalkerDeviceFromJson(json: String): StalkerDevice {
        return try { moshi.adapter(StalkerDevice::class.java).fromJson(json) ?: StalkerDevice() } catch (e: Exception) { StalkerDevice() }
    }

    fun stalkerDeviceToJson(device: StalkerDevice): String {
        return moshi.adapter(StalkerDevice::class.java).toJson(device)
    }

    fun iptvItemsMapFromJson(json: String): Map<String, List<IptvItem>> {
        val listType = Types.newParameterizedType(List::class.java, IptvItem::class.java)
        val mapType = Types.newParameterizedType(Map::class.java, String::class.java, listType)
        return try { moshi.adapter<Map<String, List<IptvItem>>>(mapType).fromJson(json) ?: emptyMap() } catch (e: Exception) { emptyMap() }
    }

    fun iptvItemsMapToJson(map: Map<String, List<IptvItem>>): String {
        val listType = Types.newParameterizedType(List::class.java, IptvItem::class.java)
        val mapType = Types.newParameterizedType(Map::class.java, String::class.java, listType)
        return moshi.adapter<Map<String, List<IptvItem>>>(mapType).toJson(map)
    }

    fun epgChannelDataFromJson(json: String): EpgChannelData? {
        return try { moshi.adapter(EpgChannelData::class.java).fromJson(json) } catch (e: Exception) { null }
    }

    fun epgChannelDataToJson(data: EpgChannelData): String {
        return moshi.adapter(EpgChannelData::class.java).toJson(data)
    }

    fun episodesListFromJson(json: String): List<EpgChannelData> {
        val type = Types.newParameterizedType(List::class.java, EpgChannelData::class.java)
        return try { moshi.adapter<List<EpgChannelData>>(type).fromJson(json) ?: emptyList() } catch (e: Exception) { emptyList() }
    }

    fun episodesListToJson(list: List<EpgChannelData>): String {
        val type = Types.newParameterizedType(List::class.java, EpgChannelData::class.java)
        return moshi.adapter<List<EpgChannelData>>(type).toJson(list)
    }
}

// ─── TRANSLATION UTILITY ──────────────────────────────────────────────────────

object Translator {
    private val translations = mapOf(
        "es" to mapOf(
            "home" to "Inicio",
            "live_tv" to "Televisión",
            "movies" to "Películas",
            "series" to "Series",
            "playlists" to "Listas",
            "settings" to "Ajustes",
            "accounts" to "Perfiles",
            "exit" to "Salir",
            "welcome_back" to "¡Hola de nuevo, %s!",
            "active_playlist" to "Lista activa: %s",
            "no_active_playlist" to "No hay listas activas",
            "control_panel" to "Panel de Control",
            "system_settings" to "Ajustes del Sistema",
            "settings_desc" to "Idiomas, reproductor, temas...",
            "change_profile" to "Cambiar Perfil",
            "manage_accounts" to "Gestionar cuentas",
            "continue_watching" to "Seguir Viendo",
            "recent_favorites" to "Favoritos Recientes",
            "channel_categories" to "Categorías de Canales",
            "no_lists" to "No tienes ninguna lista agregada",
            "add_list" to "Añadir Lista",
            "m3u_management" to "Gestión de Listas IPTV",
            "general" to "General",
            "player" to "Reproductor",
            "subtitles" to "Subtítulos",
            "parental" to "Control Parental",
            "themes" to "Temas Visuales",
            "diagnostic" to "Diagnóstico",
            "cache" to "Mantenimiento",
            "general_settings" to "Ajustes Generales",
            "general_desc" to "Idiomas, formato y comportamiento del sistema",
            "app_lang" to "Idioma de la aplicación",
            "app_lang_desc" to "Selecciona el idioma preferido para los menús",
            "time_format" to "Formato de hora",
            "time_format_desc" to "Estilo de reloj mostrado en el reproductor",
            "auto_update" to "Actualizar listas al inicio",
            "auto_update_desc" to "Descarga y refresca canales en segundo plano",
            "notifications" to "Habilitar notificaciones",
            "notifications_desc" to "Muestra avisos del sistema en pantalla",
            "tv_mode" to "Modo Android TV",
            "tv_mode_desc" to "Optimiza la interfaz y navegación para pantallas grandes",
            "player_settings" to "Configuración del Reproductor",
            "player_desc" to "Ajustes de video, audio y relaciones de aspecto",
            "video_engine" to "Motor de video",
            "video_engine_desc" to "Reproductor interno ExoPlayer o externo",
            "video_decoding" to "Decodificación de video",
            "video_decoding_desc" to "Selecciona cómo se procesa el stream de video",
            "aspect_ratio" to "Relación de aspecto",
            "aspect_ratio_desc" to "Estiramiento o escalado predeterminado del video",
            "stream_quality" to "Calidad de stream predeterminada",
            "stream_quality_desc" to "Ajuste de resolución preferida",
            "connection_timeout" to "Tiempo de espera de conexión (Timeout)",
            "connection_timeout_desc" to "Espera máxima antes de abortar la carga del canal",
            "skip_silence" to "Omitir silencios",
            "skip_silence_desc" to "Acelera sutilmente pasajes de video sin audio",
            "direct_fullscreen" to "Pantalla completa directa",
            "direct_fullscreen_desc" to "Abre los canales directamente ocupando toda la pantalla",
            "sub_settings" to "Ajustes de Subtítulos",
            "sub_desc" to "Estilo, tamaño, color e idioma de los subtítulos",
            "enable_subs" to "Activar subtítulos",
            "enable_subs_desc" to "Muestra subtítulos de forma predeterminada",
            "pref_lang" to "Idioma preferido",
            "pref_lang_desc" to "Idioma del canal de subtítulos principal",
            "font_size" to "Tamaño de fuente",
            "font_size_desc" to "Tamaño del texto de los subtítulos en pantalla",
            "font_color" to "Color de fuente",
            "font_color_desc" to "Color del texto para mejor lectura",
            "translucent_bg" to "Fondo negro translúcido",
            "translucent_bg_desc" to "Muestra una franja oscura detrás del texto para mayor contraste",
            "age_restriction" to "Activar restricción por edad",
            "age_restriction_desc" to "Pide PIN parental para canales restringidos",
            "max_rating" to "Clasificación máxima sin PIN",
            "max_rating_desc" to "Edad máxima para canales sin protección",
            "parental_pin" to "PIN Parental de Seguridad",
            "parental_pin_desc" to "PIN requerido para ver contenido protegido",
            "profile_pin" to "PIN de Acceso al Perfil",
            "profile_pin_desc" to "Contraseña para restringir el acceso a este perfil al arrancar la app",
            "custom_themes" to "Temas Visuales",
            "custom_themes_desc" to "Personaliza los colores principales y el fondo de pantalla",
            "sys_diagnostic" to "Diagnóstico del Sistema",
            "sys_diagnostic_desc" to "Verificación técnica de recursos y red",
            "jvm_mem" to "Consumo de Memoria JVM:",
            "conn_details" to "Detalles de Conexión y Listas:",
            "net_status" to "Estado de red:",
            "active_playlist_label" to "Lista IPTV Activa:",
            "backend_tech" to "Tecnología del Backend:",
            "cache_maintenance" to "Mantenimiento y Caché",
            "cache_desc" to "Limpia o restablece las bases de datos de canales y EPG",
            "reset_db" to "Restablecer Base de Datos Local",
            "reset_db_desc" to "Si tienes problemas al cargar canales, el EPG no coincide, o notas lentitud, limpiar la caché descargará todo de nuevo desde el servidor en la próxima conexión.",
            "clear_cache_btn" to "Limpiar Caché IPTV y EPG",
            "cache_cleared_success" to "¡Caché e Historial restablecidos con éxito!",
            "online" to "En línea",
            "offline" to "Sin conexión",
            "profiles_mgmt" to "Gestión de Perfiles y Cuentas",
            "change" to "Cambiar",
            "disconnect" to "Deconecte",
            "loading_channels" to "Cargando canales...",
            "no_channels_found" to "No se encontraron canales en esta categoría",
            "all_channels" to "Todos los Canales",
            "favorites" to "Favoritos"
        ),
        "en" to mapOf(
            "home" to "Home",
            "live_tv" to "Live TV",
            "movies" to "Movies",
            "series" to "Series",
            "playlists" to "Playlists",
            "settings" to "Settings",
            "accounts" to "Profiles",
            "exit" to "Exit",
            "welcome_back" to "Welcome back, %s!",
            "active_playlist" to "Active playlist: %s",
            "no_active_playlist" to "No active playlists",
            "control_panel" to "Control Panel",
            "system_settings" to "System Settings",
            "settings_desc" to "Languages, player, themes...",
            "change_profile" to "Change Profile",
            "manage_accounts" to "Manage profiles",
            "continue_watching" to "Continue Watching",
            "recent_favorites" to "Recent Favorites",
            "channel_categories" to "Channel Categories",
            "no_lists" to "You do not have any playlist added",
            "add_list" to "Add Playlist",
            "m3u_management" to "IPTV Playlist Management",
            "general" to "General",
            "player" to "Player",
            "subtitles" to "Subtitles",
            "parental" to "Parental Control",
            "themes" to "Visual Themes",
            "diagnostic" to "Diagnostics",
            "cache" to "Maintenance",
            "general_settings" to "General Settings",
            "general_desc" to "Language, format and system behavior",
            "app_lang" to "App Language",
            "app_lang_desc" to "Select preferred language for menus",
            "time_format" to "Time Format",
            "time_format_desc" to "Clock style shown in the player",
            "auto_update" to "Update lists on startup",
            "auto_update_desc" to "Download and refresh channels in background",
            "notifications" to "Enable notifications",
            "notifications_desc" to "Show system alerts on screen",
            "tv_mode" to "Android TV Mode",
            "tv_mode_desc" to "Optimize interface and navigation for large screens",
            "player_settings" to "Player Settings",
            "player_desc" to "Video, audio and aspect ratio settings",
            "video_engine" to "Video Engine",
            "video_engine_desc" to "Internal ExoPlayer or external player",
            "video_decoding" to "Video Decoding",
            "video_decoding_desc" to "Select how video stream is processed",
            "aspect_ratio" to "Aspect Ratio",
            "aspect_ratio_desc" to "Default video stretching or scaling",
            "stream_quality" to "Default Stream Quality",
            "stream_quality_desc" to "Preferred resolution setting",
            "connection_timeout" to "Connection Timeout",
            "connection_timeout_desc" to "Max wait before aborting channel load",
            "skip_silence" to "Skip Silences",
            "skip_silence_desc" to "Subtly speed up video segments without audio",
            "direct_fullscreen" to "Direct Fullscreen",
            "direct_fullscreen_desc" to "Open channels directly in fullscreen",
            "sub_settings" to "Subtitle Settings",
            "sub_desc" to "Subtitle style, size, color and language",
            "enable_subs" to "Enable Subtitles",
            "enable_subs_desc" to "Show subtitles by default",
            "pref_lang" to "Preferred Language",
            "pref_lang_desc" to "Language of the main subtitle track",
            "font_size" to "Font Size",
            "font_size_desc" to "Subtitle text size on screen",
            "font_color" to "Font Color",
            "font_color_desc" to "Text color for better readability",
            "translucent_bg" to "Translucent Black Background",
            "translucent_bg_desc" to "Show a dark strip behind text for higher contrast",
            "age_restriction" to "Enable Age Restriction",
            "age_restriction_desc" to "Ask for parental PIN for restricted channels",
            "max_rating" to "Max Rating Without PIN",
            "max_rating_desc" to "Max age for unprotected channels",
            "parental_pin" to "Parental Security PIN",
            "parental_pin_desc" to "PIN required to view protected content",
            "profile_pin" to "Profile Access PIN",
            "profile_pin_desc" to "Password to restrict access to this profile when starting the app",
            "custom_themes" to "Visual Themes",
            "custom_themes_desc" to "Customize main colors and background wallpaper",
            "sys_diagnostic" to "System Diagnostics",
            "sys_diagnostic_desc" to "Technical check of resources and network",
            "jvm_mem" to "JVM Memory Consumption:",
            "conn_details" to "Connection and Playlist Details:",
            "net_status" to "Network status:",
            "active_playlist_label" to "Active IPTV Playlist:",
            "backend_tech" to "Backend Technology:",
            "cache_maintenance" to "Maintenance and Cache",
            "cache_desc" to "Clear or reset channel databases and EPG",
            "reset_db" to "Reset Local Database",
            "reset_db_desc" to "If you have issues loading channels, EPG doesn't match, or you notice slowness, clearing the cache will download everything fresh from the server next connection.",
            "clear_cache_btn" to "Clear IPTV Cache and EPG",
            "cache_cleared_success" to "Cache and History cleared successfully!",
            "online" to "Online",
            "offline" to "Offline",
            "profiles_mgmt" to "Profiles and Accounts Management",
            "change" to "Change",
            "disconnect" to "Disconnect",
            "loading_channels" to "Loading channels...",
            "no_channels_found" to "No channels found in this category",
            "all_channels" to "All Channels",
            "favorites" to "Favorites"
        ),
        "ro" to mapOf(
            "home" to "Acasă",
            "live_tv" to "TV Live",
            "movies" to "Filme",
            "series" to "Seriale",
            "playlists" to "Liste",
            "settings" to "Setări",
            "accounts" to "Profiluri",
            "exit" to "Ieșire",
            "welcome_back" to "Salut, %s!",
            "active_playlist" to "Listă activă: %s",
            "no_active_playlist" to "Nicio listă activă",
            "control_panel" to "Panou de Control",
            "system_settings" to "Setări Sistem",
            "settings_desc" to "Limbi, player, teme...",
            "change_profile" to "Schimbă Profilul",
            "manage_accounts" to "Gestionare conturi",
            "continue_watching" to "Continuă Vizionarea",
            "recent_favorites" to "Favorite Recente",
            "channel_categories" to "Categorii Canale",
            "no_lists" to "Nu aveți nicio listă adăugată",
            "add_list" to "Adaugă Listă",
            "m3u_management" to "Gestionare Liste IPTV",
            "general" to "General",
            "player" to "Player",
            "subtitles" to "Subtitrări",
            "parental" to "Control Parental",
            "themes" to "Teme Vizuale",
            "diagnostic" to "Diagnostic",
            "cache" to "Întreținere",
            "general_settings" to "Setări Generale",
            "general_desc" to "Limbă, format și comportament sistem",
            "app_lang" to "Limba aplicației",
            "app_lang_desc" to "Selectați limba preferată pentru meniuri",
            "time_format" to "Format oră",
            "time_format_desc" to "Stilul ceasului afișat în player",
            "auto_update" to "Actualizare liste la pornire",
            "auto_update_desc" to "Descarcă și reîmprospătează canalele în fundal",
            "notifications" to "Activează notificările",
            "notifications_desc" to "Afișează alerte de sistem pe ecran",
            "tv_mode" to "Mod Android TV",
            "tv_mode_desc" to "Optimizează interfața și navigarea pentru ecrane mari",
            "player_settings" to "Setări Player",
            "player_desc" to "Setări video, audio și raport de aspect",
            "video_engine" to "Motor Video",
            "video_engine_desc" to "Player intern ExoPlayer sau extern",
            "video_decoding" to "Decodare Video",
            "video_decoding_desc" to "Selectați cum este procesat fluxul video",
            "aspect_ratio" to "Raport de Aspect",
            "aspect_ratio_desc" to "Întinderea sau scalarea implicită a videoclipului",
            "stream_quality" to "Calitate Flux Implicită",
            "stream_quality_desc" to "Setare rezoluție preferată",
            "connection_timeout" to "Timp de Expirare Conexiune",
            "connection_timeout_desc" to "Așteptare maximă înainte de a anula încărcarea canalului",
            "skip_silence" to "Omite Tăcerile",
            "skip_silence_desc" to "Accelerează subtil segmentele video fără audio",
            "direct_fullscreen" to "Ecran Complet Direct",
            "direct_fullscreen_desc" to "Deschide canalele direct în ecran complet",
            "sub_settings" to "Setări Subtitrări",
            "sub_desc" to "Stil, dimensiune, culoare și limbă subtitrare",
            "enable_subs" to "Activează Subtitrările",
            "enable_subs_desc" to "Afișează subtitrările implicit",
            "pref_lang" to "Limbă Preferată",
            "pref_lang_desc" to "Limba pistei principale de subtitrare",
            "font_size" to "Dimensiune Font",
            "font_size_desc" to "Dimensiunea textului subtitrării pe ecran",
            "font_color" to "Culoare Font",
            "font_color_desc" to "Culoarea textului pentru o mai bună lizibilitate",
            "translucent_bg" to "Fundal Negru Translucid",
            "translucent_bg_desc" to "Afișează o bandă întunecată în spatele textului pentru contrast mai mare",
            "age_restriction" to "Activează Restricția de Vârstă",
            "age_restriction_desc" to "Cere PIN parental pentru canale restricționate",
            "max_rating" to "Clasificare Maximă fără PIN",
            "max_rating_desc" to "Vârsta maximă pentru canale neprotejate",
            "parental_pin" to "PIN de Securitate Parental",
            "parental_pin_desc" to "PIN necesar pentru vizualizarea conținutului protejat",
            "profile_pin" to "PIN de Acces Profil",
            "profile_pin_desc" to "Parolă pentru a restricționa accesul la acest profil la pornirea aplicației",
            "custom_themes" to "Teme Vizuale",
            "custom_themes_desc" to "Personalizați culorile principale și imaginea de fundal",
            "sys_diagnostic" to "Diagnostic Sistem",
            "sys_diagnostic_desc" to "Verificare tehnică a resurselor și rețelei",
            "jvm_mem" to "Consum Memorie JVM:",
            "conn_details" to "Detalii Conexiune și Liste:",
            "net_status" to "Stare rețea:",
            "active_playlist_label" to "Listă IPTV Activă:",
            "backend_tech" to "Tehnologie Backend:",
            "cache_maintenance" to "Întreținere și Cache",
            "cache_desc" to "Curățați sau resetați bazele de date de canale și EPG",
            "reset_db" to "Resetați Baza de Date Locală",
            "reset_db_desc" to "Dacă aveți probleme la încărcarea canalelor, EPG-ul nu se potrivește sau observați încetineală, curățarea cache-ului va descărca totul proaspăt de pe server la următoarea conexiune.",
            "clear_cache_btn" to "Curăță Cache IPTV și EPG",
            "cache_cleared_success" to "Cache-ul și Istoricul au fost resetate cu succes!",
            "online" to "În linie",
            "offline" to "Fără conexiune",
            "profiles_mgmt" to "Gestionare Profiluri și Conturi",
            "change" to "Schimbă",
            "disconnect" to "Deconectează",
            "loading_channels" to "Se încarcă canalele...",
            "no_channels_found" to "Nu s-au găsit canale în această categorie",
            "all_channels" to "Toate Canalele",
            "favorites" to "Favorite"
        )
    )

    fun translate(key: String, lang: String, vararg args: Any): String {
        val normalizedLang = when (lang.lowercase()) {
            "en" -> "en"
            "ro" -> "ro"
            else -> "es"
        }
        val langMap = translations[normalizedLang] ?: translations["es"]!!
        val text = langMap[key] ?: translations["es"]!![key] ?: key
        return try {
            if (args.isNotEmpty()) {
                String.format(text, *args)
            } else {
                text
            }
        } catch (e: java.lang.Exception) {
            text
        }
    }
}
