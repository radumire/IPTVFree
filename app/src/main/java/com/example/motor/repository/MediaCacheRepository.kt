package com.example.motor.repository

import android.app.Application
import android.util.Log
import com.example.motor.config.JsonUtils
import com.example.motor.db.AppDatabase
import com.example.motor.db.CategoryCache
import com.example.motor.db.EpgChannelData
import com.example.motor.db.IptvItem
import com.example.motor.db.SeriesEpisodesCache
import com.squareup.moshi.Types
import okio.buffer
import okio.sink
import okio.source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class CacheResult<T>(
    val data: T?,
    val isExpired: Boolean,
    val updatedAt: Long
)

class MediaCacheRepository private constructor(private val application: Application) {
    private val TAG = "MediaCacheRepository"
    private val db = AppDatabase.getDatabase(application)
    private val categoryDao = db.categoryCacheDao()
    private val episodesDao = db.seriesEpisodesCacheDao()
    private val filesDir = application.filesDir

    companion object {
        const val DEFAULT_TTL_MS = 4 * 60 * 60 * 1000L // 4 Horas de TTL por defecto

        @Volatile
        private var INSTANCE: MediaCacheRepository? = null

        fun getInstance(application: Application): MediaCacheRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MediaCacheRepository(application).also { INSTANCE = it }
            }
        }
    }

    private fun makeCategoryKey(playlistId: Long, type: String): String = "${playlistId}::${type.lowercase()}"

    private fun getCacheFile(playlistId: Long, type: String): File {
        return File(filesDir, "playlist_cache_${playlistId}_${type.lowercase()}.json")
    }

    /**
     * Obtiene el catálogo de canales TV, Películas o Series.
     * Carga preferentemente desde el archivo de caché en disco (vía Okio stream) para evitar
     * el límite de CursorWindow (2MB) de Room cuando hay decenas de miles de elementos.
     */
    suspend fun getCategoryCache(
        playlistId: Long,
        type: String,
        ttlMs: Long = DEFAULT_TTL_MS
    ): CacheResult<Map<String, List<IptvItem>>> = withContext(Dispatchers.IO) {
        val key = makeCategoryKey(playlistId, type)
        var dataMap: Map<String, List<IptvItem>>? = null
        var updatedAt = 0L

        // 1. Intentar archivo en disco con Okio (rápido, sin límites de CursorWindow)
        try {
            val file = getCacheFile(playlistId, type)
            if (file.exists() && file.length() > 0) {
                file.source().buffer().use { source ->
                    val typeAdapter = JsonUtils.moshi.adapter<Map<String, List<IptvItem>>>(
                        Types.newParameterizedType(Map::class.java, String::class.java, Types.newParameterizedType(List::class.java, IptvItem::class.java))
                    )
                    dataMap = typeAdapter.fromJson(source)
                }
                updatedAt = file.lastModified()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error loading category cache from File for playlist $playlistId type $type", e)
        }

        // 2. Si el archivo no existe o falló, intentar de Room Database
        if (dataMap == null) {
            try {
                val entry = categoryDao.getById(key)
                if (entry != null && entry.dataJson.isNotBlank()) {
                    dataMap = JsonUtils.iptvItemsMapFromJson(entry.dataJson)
                    updatedAt = entry.updatedAt
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Error loading category cache from Room for key $key", e)
            }
        }

        if (dataMap == null && updatedAt == 0L) {
            return@withContext CacheResult(data = null, isExpired = true, updatedAt = 0L)
        }

        val now = System.currentTimeMillis()
        val isExpired = (updatedAt == 0L) || ((now - updatedAt) > ttlMs)
        CacheResult(
            data = if (!dataMap.isNullOrEmpty()) dataMap else null,
            isExpired = isExpired,
            updatedAt = updatedAt
        )
    }

    /**
     * Guarda en archivo de disco (vía Okio stream) y opcionalmente en Room Database.
     */
    suspend fun saveCategoryCache(
        playlistId: Long,
        type: String,
        data: Map<String, List<IptvItem>>
    ) = withContext(Dispatchers.IO) {
        val key = makeCategoryKey(playlistId, type)
        val now = System.currentTimeMillis()

        // 1. Guardar en archivo de disco usando Okio streaming
        try {
            val file = getCacheFile(playlistId, type)
            val typeAdapter = JsonUtils.moshi.adapter<Map<String, List<IptvItem>>>(
                Types.newParameterizedType(Map::class.java, String::class.java, Types.newParameterizedType(List::class.java, IptvItem::class.java))
            )
            file.sink().buffer().use { sink ->
                typeAdapter.toJson(sink, data)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error saving category cache to File for playlist $playlistId type $type", e)
        }

        // 2. Intentar guardar en Room Database solo si el tamaño es reducido (< 1.5MB)
        try {
            val json = JsonUtils.iptvItemsMapToJson(data)
            if (json.length < 1_500_000) {
                val entity = CategoryCache(
                    id = key,
                    source = playlistId.toString(),
                    type = type.lowercase(),
                    dataJson = json,
                    updatedAt = now
                )
                categoryDao.insert(entity)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Omitting Room cache store for large key $key (disk cache active)", e)
        }
    }

    /**
     * Obtiene de Room Database los episodios de una serie por su clave/ID.
     */
    suspend fun getEpisodesCache(
        key: String,
        ttlMs: Long = DEFAULT_TTL_MS
    ): CacheResult<List<EpgChannelData>> = withContext(Dispatchers.IO) {
        try {
            val entry = episodesDao.getByKey(key)
            if (entry == null || entry.episodesJson.isBlank()) {
                return@withContext CacheResult(data = null, isExpired = true, updatedAt = 0L)
            }
            val episodes = JsonUtils.episodesListFromJson(entry.episodesJson)
            val now = System.currentTimeMillis()
            val isExpired = (now - entry.updatedAt) > ttlMs
            CacheResult(
                data = if (episodes.isNotEmpty()) episodes else null,
                isExpired = isExpired,
                updatedAt = entry.updatedAt
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Error loading/deserializing episodes cache from Room for key $key", e)
            CacheResult(data = null, isExpired = true, updatedAt = 0L)
        }
    }

    /**
     * Guarda en Room Database los episodios cargados de una serie.
     */
    suspend fun saveEpisodesCache(
        key: String,
        seriesName: String,
        episodes: List<EpgChannelData>
    ) = withContext(Dispatchers.IO) {
        if (episodes.isEmpty()) return@withContext
        try {
            val json = JsonUtils.episodesListToJson(episodes)
            val entity = SeriesEpisodesCache(
                key = key,
                seriesName = seriesName,
                episodesJson = json,
                updatedAt = System.currentTimeMillis()
            )
            episodesDao.insert(entity)
        } catch (e: Throwable) {
            Log.e(TAG, "Error saving episodes cache to Room for key $key", e)
        }
    }

    /**
     * Limpia la caché de categorías para una lista de reproducción en específico.
     */
    suspend fun clearCategoryCache(playlistId: Long) = withContext(Dispatchers.IO) {
        categoryDao.deleteForSource(playlistId.toString())
    }

    /**
     * Limpia todas las cachés persistentes de Room (canales, películas, series y episodios).
     */
    suspend fun clearAllCaches() = withContext(Dispatchers.IO) {
        categoryDao.clearAll()
        episodesDao.clearAll()
    }
}
