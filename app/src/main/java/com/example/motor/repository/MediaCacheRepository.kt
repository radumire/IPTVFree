package com.example.motor.repository

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.motor.config.JsonUtils
import com.example.motor.db.AppDatabase
import com.example.motor.db.CategoryCache
import com.example.motor.db.IptvItem
import com.example.motor.db.SeriesEpisodesCache
import com.example.motor.db.SeriesSeason
import com.squareup.moshi.JsonReader
import com.squareup.moshi.Types
import okio.buffer
import okio.sink
import okio.source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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

    private data class MemoryCacheEntry(
        val data: Map<String, List<IptvItem>>,
        val updatedAt: Long,
        val count: Int
    )

    // L2 In-Memory Cache: mapa thread-safe asociando "playlistId_type" -> MemoryCacheEntry
    private val memoryCategoryCache = ConcurrentHashMap<String, MemoryCacheEntry>()

    @Volatile
    private var activePlaylistId: Long = -1L

    // Scope para operaciones en segundo plano y deduplicación de cargas a nivel de repositorio
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Protección contra cargas simultáneas (Single-Flight) y escrituras concurrentes por archivo de caché
    private val inFlightLoads = ConcurrentHashMap<String, Deferred<CacheResult<Map<String, List<IptvItem>>>>>()
    private val inFlightMutex = Mutex()
    private val categoryWriteMutexes = ConcurrentHashMap<String, Mutex>()

    private fun getCategoryWriteMutex(key: String): Mutex =
        categoryWriteMutexes.getOrPut(key) { Mutex() }

    private fun writeCategoryMapAtomically(file: File, data: Map<String, List<IptvItem>>) {
        file.parentFile?.mkdirs()
        val tmpFile = File(file.parentFile ?: filesDir, "${file.name}.tmp.${System.nanoTime()}")
        try {
            val typeAdapter = JsonUtils.moshi.adapter<Map<String, List<IptvItem>>>(
                Types.newParameterizedType(
                    Map::class.java,
                    String::class.java,
                    Types.newParameterizedType(List::class.java, IptvItem::class.java)
                )
            )
            tmpFile.sink().buffer().use { sink ->
                typeAdapter.toJson(sink, data)
            }
            if (!tmpFile.renameTo(file)) {
                if (file.exists()) {
                    file.delete()
                }
                if (!tmpFile.renameTo(file)) {
                    tmpFile.copyTo(file, overwrite = true)
                    tmpFile.delete()
                }
            }
        } finally {
            if (tmpFile.exists()) {
                try {
                    tmpFile.delete()
                } catch (_: Throwable) {}
            }
        }
    }

    private val _cacheUpdateEvents = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    val cacheUpdateEvents: SharedFlow<Long> = _cacheUpdateEvents.asSharedFlow()

    private val prefs by lazy {
        application.getSharedPreferences("playlist_counts_cache", Context.MODE_PRIVATE)
    }

    fun setActivePlaylist(playlistId: Long) {
        if (activePlaylistId != playlistId) {
            val oldId = activePlaylistId
            activePlaylistId = playlistId
            Log.d(TAG, "PLAYLIST_SWITCH: setActivePlaylist old=$oldId -> new=$playlistId. Cancelling obsolete in-flight loads and freeing inactive RAM cache.")
            // 1. Cancelar cargas en segundo plano de playlists que ya no son la activa
            cancelLoadsExcept(playlistId)
            // 2. Liberar referencias de playlists antiguas en memoria RAM inmediatamente
            val activePrefix = "${playlistId}::"
            val keysToRemove = memoryCategoryCache.keys.filter { !it.startsWith(activePrefix) }
            for (k in keysToRemove) {
                memoryCategoryCache.remove(k)
            }
        }
    }

    /**
     * Cancela todas las cargas y deserializaciones asíncronas en disco excepto las de la playlist indicada.
     */
    fun cancelLoadsExcept(keepPlaylistId: Long) {
        val keepPrefix = "${keepPlaylistId}::"
        val toCancel = mutableListOf<Deferred<*>>()
        val keysToRemove = mutableListOf<String>()

        for ((key, deferred) in inFlightLoads) {
            if (!key.startsWith(keepPrefix)) {
                keysToRemove.add(key)
                toCancel.add(deferred)
            }
        }
        for (k in keysToRemove) {
            inFlightLoads.remove(k)
        }
        for (d in toCancel) {
            try {
                d.cancel()
            } catch (_: Throwable) {}
        }
        if (toCancel.isNotEmpty()) {
            Log.d(TAG, "PLAYLIST_SWITCH: Cancelled ${toCancel.size} obsolete in-flight loads (kept playlist $keepPlaylistId)")
        }
    }

    fun trimMemory(level: Int) {
        // En cualquier nivel de presión de memoria, liberar Movies y Series de la RAM
        val tvKey = if (activePlaylistId != -1L) makeCategoryKey(activePlaylistId, "tv") else null
        val keysToRemove = memoryCategoryCache.keys.filter { it != tvKey }
        for (k in keysToRemove) {
            memoryCategoryCache.remove(k)
        }
        // Si el nivel es crítico o completo, liberar toda la caché en RAM
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            memoryCategoryCache.clear()
        }
    }

    fun clearMemoryCache(keepActiveTvOnly: Boolean = false) {
        if (keepActiveTvOnly && activePlaylistId != -1L) {
            val tvKey = makeCategoryKey(activePlaylistId, "tv")
            val keysToRemove = memoryCategoryCache.keys.filter { it != tvKey }
            for (k in keysToRemove) {
                memoryCategoryCache.remove(k)
            }
        } else {
            memoryCategoryCache.clear()
        }
    }

    suspend fun notifyCacheUpdated(playlistId: Long) {
        _cacheUpdateEvents.emit(playlistId)
    }

    fun getCategoryCount(playlistId: Long, type: String): Int {
        val key = "count_${playlistId}_${type.lowercase()}"
        val saved = prefs.getInt(key, -1)
        if (saved >= 0) return saved
        val count = fastCountCategoryCache(playlistId, type)
        saveCategoryCount(playlistId, type, count)
        return count
    }

    fun saveCategoryCount(playlistId: Long, type: String, count: Int) {
        val key = "count_${playlistId}_${type.lowercase()}"
        prefs.edit().putInt(key, count).apply()
    }

    private fun fastCountCategoryCache(playlistId: Long, type: String): Int {
        val file = getCacheFile(playlistId, type)
        if (!file.exists() || file.length() == 0L) return 0

        var count = 0
        try {
            file.source().buffer().use { source ->
                val reader = JsonReader.of(source)
                if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        reader.nextName()
                        if (reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
                            reader.beginArray()
                            while (reader.hasNext()) {
                                reader.skipValue()
                                count++
                            }
                            reader.endArray()
                        } else {
                            reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error fast-counting category cache for playlist $playlistId type $type", e)
        }
        return count
    }

    companion object {
        const val DEFAULT_TTL_MS = 4 * 60 * 60 * 1000L // 4 Horas de TTL por defecto

        @Volatile
        private var INSTANCE: MediaCacheRepository? = null

        fun getInstance(application: Application): MediaCacheRepository {
            val existing = INSTANCE
            if (existing != null && existing.application === application) {
                return existing
            }
            return synchronized(this) {
                val locked = INSTANCE
                if (locked != null && locked.application === application) {
                    locked
                } else {
                    MediaCacheRepository(application).also { INSTANCE = it }
                }
            }
        }
    }

    private fun makeCategoryKey(playlistId: Long, type: String): String = "${playlistId}::${type.lowercase()}"

    fun getCacheFile(playlistId: Long, type: String): File {
        return File(filesDir, "playlist_cache_${playlistId}_${type.lowercase()}.json")
    }

    fun hasCategoryCache(playlistId: Long, type: String): Boolean {
        val file = getCacheFile(playlistId, type)
        return file.exists() && file.length() > 0
    }

    suspend fun hasCategoryCacheAsync(playlistId: Long, type: String): Boolean = withContext(Dispatchers.IO) {
        if (hasCategoryCache(playlistId, type)) return@withContext true
        val key = makeCategoryKey(playlistId, type)
        try {
            categoryDao.getById(key) != null
        } catch (e: Exception) {
            false
        }
    }

    fun hasAnyCache(playlistId: Long): Boolean {
        return hasCategoryCache(playlistId, "tv") ||
               hasCategoryCache(playlistId, "movie") ||
               hasCategoryCache(playlistId, "series")
    }

    suspend fun hasAnyCacheAsync(playlistId: Long): Boolean = withContext(Dispatchers.IO) {
        hasCategoryCacheAsync(playlistId, "tv") ||
        hasCategoryCacheAsync(playlistId, "movie") ||
        hasCategoryCacheAsync(playlistId, "series")
    }

    fun isCacheComplete(playlistId: Long): Boolean {
        return hasCategoryCache(playlistId, "tv") &&
               hasCategoryCache(playlistId, "movie") &&
               hasCategoryCache(playlistId, "series")
    }

    fun hasMemoryCache(playlistId: Long, type: String): Boolean {
        val key = makeCategoryKey(playlistId, type)
        return memoryCategoryCache.containsKey(key)
    }

    /**
     * Precarga en segundo plano:
     * TV prioritariamente en RAM para la playlist activa.
     * Movies y Series se cargan desde disco cuando se necesitan.
     */
    fun preloadWarmCache(playlistId: Long) {
        setActivePlaylist(playlistId)
        repoScope.launch(Dispatchers.IO) {
            try {
                if (!hasMemoryCache(playlistId, "tv") && hasCategoryCache(playlistId, "tv")) {
                    getCategoryCache(playlistId, "tv")
                }
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Obtiene el catálogo de canales TV, Películas o Series.
     * 1. Consulta la caché en memoria L2 (0 ms).
     * 2. Si hay una carga en curso para esta playlist y categoría, se suma a la misma (Single-Flight, sin duplicar lecturas).
     * 3. Si no, carga desde disco, deserializa una única vez, guarda en memoria y despacha el resultado.
     */
    suspend fun getCategoryCache(
        playlistId: Long,
        type: String,
        ttlMs: Long = DEFAULT_TTL_MS
    ): CacheResult<Map<String, List<IptvItem>>> {
        val lowerType = type.lowercase()
        val key = makeCategoryKey(playlistId, lowerType)

        // 1. Verificar si ya está caliente en la caché en memoria (L2 Cache)
        val memoryEntry = memoryCategoryCache[key]
        if (memoryEntry != null) {
            val now = System.currentTimeMillis()
            val isExpired = (memoryEntry.updatedAt == 0L) || ((now - memoryEntry.updatedAt) > ttlMs)
            return CacheResult(
                data = memoryEntry.data,
                isExpired = isExpired,
                updatedAt = memoryEntry.updatedAt
            )
        }

        // 2. Single-Flight: Si ya hay una carga activa en disco/deserialización para esta clave, compartirla
        val deferred = inFlightMutex.withLock {
            val existingMemory = memoryCategoryCache[key]
            if (existingMemory != null) {
                return@withLock null
            }
            val active = inFlightLoads[key]
            if (active != null && !active.isCancelled) {
                active
            } else {
                val newDeferred = repoScope.async {
                    loadCategoryCacheFromDiskInternal(playlistId, lowerType, ttlMs)
                }
                inFlightLoads[key] = newDeferred
                newDeferred.invokeOnCompletion {
                    inFlightLoads.remove(key)
                }
                newDeferred
            }
        }

        if (deferred == null) {
            val entry = memoryCategoryCache[key]
            if (entry != null) {
                return CacheResult(data = entry.data, isExpired = false, updatedAt = entry.updatedAt)
            }
        }

        return try {
            deferred!!.await()
        } catch (e: CancellationException) {
            val entry = memoryCategoryCache[key]
            if (entry != null) {
                CacheResult(data = entry.data, isExpired = false, updatedAt = entry.updatedAt)
            } else {
                CacheResult(data = null, isExpired = true, updatedAt = 0L)
            }
        }
    }

    private suspend fun loadCategoryCacheFromDiskInternal(
        playlistId: Long,
        type: String,
        ttlMs: Long
    ): CacheResult<Map<String, List<IptvItem>>> = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        // Si la playlist ya no es la activa y no es TV, abortar para no saturar CPU y RAM
        if (activePlaylistId != -1L && activePlaylistId != playlistId && type != "tv") {
            Log.d(TAG, "PLAYLIST_SWITCH: Aborting obsolete load for playlist $playlistId type $type (active is $activePlaylistId)")
            return@withContext CacheResult(data = null, isExpired = true, updatedAt = 0L)
        }

        val key = makeCategoryKey(playlistId, type)
        var dataMap: Map<String, List<IptvItem>>? = null
        var updatedAt = 0L

        // 1. Intentar archivo en disco con Okio
        val file = getCacheFile(playlistId, type)
        if (file.exists() && file.length() > 0) {
            try {
                coroutineContext.ensureActive()
                file.source().buffer().use { source ->
                    val typeAdapter = JsonUtils.moshi.adapter<Map<String, List<IptvItem>>>(
                        Types.newParameterizedType(
                            Map::class.java,
                            String::class.java,
                            Types.newParameterizedType(List::class.java, IptvItem::class.java)
                        )
                    )
                    dataMap = typeAdapter.fromJson(source)
                }
                coroutineContext.ensureActive()
                updatedAt = file.lastModified()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "Error loading category cache from File for playlist $playlistId type $type", e)
            }
        }

        // 2. Si el archivo no existe o falló, intentar de Room Database
        if (dataMap == null) {
            try {
                coroutineContext.ensureActive()
                val entry = categoryDao.getById(key)
                if (entry != null && entry.dataJson.isNotBlank()) {
                    dataMap = JsonUtils.iptvItemsMapFromJson(entry.dataJson)
                    updatedAt = entry.updatedAt
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "Error loading category cache from Room for key $key", e)
            }
        }

        if (dataMap == null && updatedAt == 0L) {
            return@withContext CacheResult(data = null, isExpired = true, updatedAt = 0L)
        }

        val totalCount = dataMap?.values?.sumOf { it.size } ?: 0
        val finalMap = if (!dataMap.isNullOrEmpty()) dataMap else null

        if (finalMap != null) {
            // Guardar en la memoria L2 SOLO si esta playlist sigue siendo la activa
            if (activePlaylistId == -1L || activePlaylistId == playlistId) {
                // Evitar acumular simultáneamente catálogos VOD gigantes en memoria RAM
                if (type == "movie") {
                    memoryCategoryCache.remove(makeCategoryKey(playlistId, "series"))
                } else if (type == "series") {
                    memoryCategoryCache.remove(makeCategoryKey(playlistId, "movie"))
                }

                memoryCategoryCache[key] = MemoryCacheEntry(
                    data = finalMap,
                    updatedAt = updatedAt,
                    count = totalCount
                )
            }
            saveCategoryCount(playlistId, type, totalCount)
        }

        val now = System.currentTimeMillis()
        val isExpired = (updatedAt == 0L) || ((now - updatedAt) > ttlMs)
        CacheResult(
            data = finalMap,
            isExpired = isExpired,
            updatedAt = updatedAt
        )
    }

    /**
     * Obtiene los nombres de las categorías de forma ultraligera mediante JsonReader streaming,
     * sin deserializar ni parsear los 150.000 elementos a objetos IptvItem en memoria RAM.
     */
    fun getCategoryNames(playlistId: Long, type: String): List<String> {
        val lowerType = type.lowercase()
        val key = makeCategoryKey(playlistId, lowerType)
        // 1. Si ya está en la memoria L2, retornar directamente sus claves
        val memoryEntry = memoryCategoryCache[key]
        if (memoryEntry != null) {
            return memoryEntry.data.keys.filter { !IptvRepository.isDividerOrHeader(it) }.sorted()
        }

        // 2. Leer directamente los nombres de categorías del archivo en disco con JsonReader
        val file = getCacheFile(playlistId, lowerType)
        if (!file.exists() || file.length() == 0L) return emptyList()

        val categories = mutableListOf<String>()
        try {
            file.source().buffer().use { source ->
                val reader = JsonReader.of(source)
                if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val name = reader.nextName()
                        if (!IptvRepository.isDividerOrHeader(name)) {
                            categories.add(name)
                        }
                        reader.skipValue() // Salta el array completo de elementos en 1 ms sin asignaciones en RAM
                    }
                    reader.endObject()
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error reading category names for playlist $playlistId type $type", e)
        }
        return categories.sorted()
    }

    /**
     * Obtiene una vista previa de las películas más nuevas leyendo en streaming únicamente
     * un número reducido de elementos (máximo 80) desde el archivo de caché,
     * sin deserializar ni mantener en RAM el catálogo completo de 150.000 elementos.
     */
    fun getNewestMoviesPreview(playlistId: Long, limit: Int = 15): List<IptvItem> {
        val key = makeCategoryKey(playlistId, "movie")
        // 1. Si ya está cargado en la memoria L2 (por ejemplo, si el usuario ya entró a Películas):
        val memEntry = memoryCategoryCache[key]
        if (memEntry != null) {
            val all = memEntry.data.values.flatten().filter { !it.url.isNullOrEmpty() || !it.stalkerCmd.isNullOrEmpty() }
            return sortAndPickNewest(all, limit)
        }

        // 2. Extraer streaming de candidatos sin cargar el archivo completo
        val file = getCacheFile(playlistId, "movie")
        if (!file.exists() || file.length() == 0L) return emptyList()

        val sampleItems = mutableListOf<IptvItem>()
        val itemAdapter = JsonUtils.moshi.adapter(IptvItem::class.java)

        try {
            file.source().buffer().use { source ->
                val reader = JsonReader.of(source)
                if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
                    reader.beginObject()
                    while (reader.hasNext() && sampleItems.size < 80) {
                        val groupName = reader.nextName()
                        val isRelevantGroup = groupName.contains("ESTRENO", ignoreCase = true) ||
                                              groupName.contains("NOVEDAD", ignoreCase = true) ||
                                              groupName.contains("NUEV", ignoreCase = true) ||
                                              groupName.contains("2026") ||
                                              groupName.contains("2025") ||
                                              groupName.contains("2024") ||
                                              sampleItems.size < 30

                        if (reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
                            reader.beginArray()
                            var countInGroup = 0
                            while (reader.hasNext() && sampleItems.size < 80) {
                                if (isRelevantGroup && countInGroup < 15) {
                                    try {
                                        val item = itemAdapter.fromJson(reader)
                                        if (item != null && (!item.url.isNullOrEmpty() || !item.stalkerCmd.isNullOrEmpty())) {
                                            sampleItems.add(item)
                                            countInGroup++
                                        }
                                    } catch (_: Exception) {
                                        reader.skipValue()
                                    }
                                } else {
                                    reader.skipValue()
                                }
                            }
                            while (reader.hasNext()) {
                                reader.skipValue()
                            }
                            reader.endArray()
                        } else {
                            reader.skipValue()
                        }
                    }
                    while (reader.hasNext()) {
                        reader.nextName()
                        reader.skipValue()
                    }
                    reader.endObject()
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error streaming newest movies preview for playlist $playlistId", e)
        }

        return sortAndPickNewest(sampleItems, limit)
    }

    private fun sortAndPickNewest(items: List<IptvItem>, limit: Int): List<IptvItem> {
        if (items.isEmpty()) return emptyList()
        val tmdb = TmdbRepository.getInstance()
        return items
            .map { item ->
                val nameStr = item.name
                val groupStr = item.group
                val cleanedInfo = tmdb.cleanTitleAndExtractYear(nameStr)
                val effectiveYear = item.year ?: cleanedInfo.year ?: 0
                val isEstrenoGroup = groupStr.contains("ESTRENO", ignoreCase = true) ||
                        groupStr.contains("NOVEDAD", ignoreCase = true) ||
                        groupStr.contains("NUEV", ignoreCase = true) ||
                        groupStr.contains("LATEST", ignoreCase = true) ||
                        groupStr.contains("RELEASE", ignoreCase = true)
                Triple(item, effectiveYear, isEstrenoGroup)
            }
            .sortedWith(
                compareByDescending<Triple<IptvItem, Int, Boolean>> { it.second }
                    .thenByDescending { it.third }
                    .thenByDescending { it.first.rating ?: 0f }
                    .thenBy { it.first.name }
            )
            .map { it.first }
            .take(limit)
    }

    /**
     * Guarda en archivo de disco (vía Okio stream), actualiza la caché caliente en memoria
     * y opcionalmente en Room Database.
     */
    suspend fun saveCategoryCache(
        playlistId: Long,
        type: String,
        data: Map<String, List<IptvItem>>
    ) = withContext(Dispatchers.IO) {
        val key = makeCategoryKey(playlistId, type)
        getCategoryWriteMutex(key).withLock {
            val totalCount = data.values.sumOf { it.size }
            saveCategoryCount(playlistId, type, totalCount)

            val now = System.currentTimeMillis()

            // Para evitar mantener catálogos gigantes innecesarios simultáneamente en RAM:
            val lowerType = type.lowercase()
            if (lowerType == "tv") {
                // Canales de TV se mantienen calientes en RAM para acceso instantáneo
                memoryCategoryCache[key] = MemoryCacheEntry(
                    data = data,
                    updatedAt = now,
                    count = totalCount
                )
            } else {
                // VOD (películas y series): Se persisten en disco de forma segura mediante streaming Okio.
                // No retenemos los mapas de decenas de miles de items en RAM aquí para evitar picos de Heap
                // y ciclos continuos de GC durante conexiones o cambios de listas. Se cargarán bajo demanda
                // al acceder a sus pantallas respectivas (MoviesViewModel / SeriesViewModel).
                memoryCategoryCache.remove(key)
            }

            // 1. Guardar en archivo de disco de forma atómica usando Okio streaming + archivo temporal
            try {
                val file = getCacheFile(playlistId, type)
                writeCategoryMapAtomically(file, data)
            } catch (e: Throwable) {
                Log.e(TAG, "Error saving category cache to File for playlist $playlistId type $type", e)
            }

            // 2. Intentar guardar en Room Database solo si el tamaño es reducido (< 1500 items y < 1.5MB)
            if (totalCount in 1..1500) {
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

            try {
                _cacheUpdateEvents.tryEmit(playlistId)
            } catch (_: Throwable) {}
        }
    }

    /**
     * Guarda incrementalmente una categoría VOD en la caché sin bloquear el catálogo completo.
     * Actualiza la memoria L2, el archivo en disco (Okio streaming) y notifica el flujo reactivo.
     */
    suspend fun appendVodCategoryToCache(
        playlistId: Long,
        categoryName: String,
        items: List<IptvItem>,
        notify: Boolean = true
    ) = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext
        val key = makeCategoryKey(playlistId, "movie")
        getCategoryWriteMutex(key).withLock {
            val currentMap = LinkedHashMap(memoryCategoryCache[key]?.data ?: run {
                val existing = getCategoryCache(playlistId, "movie")
                existing.data ?: emptyMap()
            })

            val existingCatItems = currentMap[categoryName] ?: emptyList()
            val seenKeys = HashSet<String>(existingCatItems.size + items.size)
            existingCatItems.forEach { item ->
                val id = item.stalkerCmd ?: item.url ?: item.name
                seenKeys.add("${item.name}|$id")
            }

            val mergedCatList = ArrayList(existingCatItems)
            for (item in items) {
                val id = item.stalkerCmd ?: item.url ?: item.name
                if (seenKeys.add("${item.name}|$id")) {
                    mergedCatList.add(item)
                }
            }

            currentMap[categoryName] = mergedCatList
            val totalCount = currentMap.values.sumOf { it.size }
            val now = System.currentTimeMillis()

            // 1. Actualizar L2 memoria
            memoryCategoryCache[key] = MemoryCacheEntry(
                data = currentMap,
                updatedAt = now,
                count = totalCount
            )
            saveCategoryCount(playlistId, "movie", totalCount)

            // 2. Persistir a disco de forma atómica y streaming
            try {
                val file = getCacheFile(playlistId, "movie")
                writeCategoryMapAtomically(file, currentMap)
            } catch (e: Throwable) {
                Log.e(TAG, "Error appending VOD category cache to file for playlist $playlistId", e)
            }

            // 3. Notificar a MoviesViewModel
            if (notify) {
                notifyCacheUpdated(playlistId)
            }
        }
    }

    /**
     * Obtiene de Room Database los episodios de una serie por su clave/ID.
     */
    suspend fun getEpisodesCache(
        key: String,
        ttlMs: Long = DEFAULT_TTL_MS
    ): CacheResult<List<SeriesSeason>> = withContext(Dispatchers.IO) {
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
        episodes: List<SeriesSeason>
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
     * Limpia la caché de categorías (disco y memoria) para una lista de reproducción en específico.
     */
    suspend fun clearCategoryCache(playlistId: Long) = withContext(Dispatchers.IO) {
        val prefix = "${playlistId}::"
        memoryCategoryCache.keys.filter { it.startsWith(prefix) }.forEach { memoryCategoryCache.remove(it) }
        categoryDao.deleteForSource(playlistId.toString())
        listOf("tv", "movie", "series").forEach { type ->
            prefs.edit().remove("count_${playlistId}_${type}").apply()
            val file = getCacheFile(playlistId, type)
            if (file.exists()) {
                file.delete()
            }
        }
    }

    /**
     * Limpia todas las cachés persistentes y en memoria (canales, películas, series y episodios).
     */
    suspend fun clearAllCaches(): Unit = withContext(Dispatchers.IO) {
        memoryCategoryCache.clear()
        categoryDao.clearAll()
        episodesDao.clearAll()
        prefs.edit().clear().apply()
        try {
            filesDir.listFiles { _, name ->
                name.startsWith("playlist_cache_") && (name.endsWith(".json") || name.contains(".json.tmp"))
            }?.forEach { it.delete() }
        } catch (e: Throwable) {
            Log.e(TAG, "Error deleting disk cache files", e)
        }
    }
}

/**
 * Serializa y regula (throttle + single-flight) los guardados parciales en segundo plano
 * para evitar ráfagas de E/S en disco, picos de memoria y escrituras fuera de orden.
 */
internal class ThrottledCategoryCacheSaver(
    private val minIntervalMs: Long = 2_500L,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val onPersist: suspend (type: String, grouped: Map<String, List<IptvItem>>) -> Unit
) {
    private val persistMutex = Mutex()
    private val stateLock = Any()
    private val lastSavedAtByType = HashMap<String, Long>()
    private val lastSavedCountByType = HashMap<String, Int>()
    private var inFlightJob: Job? = null

    fun submitPartial(
        scope: CoroutineScope,
        type: String,
        itemsSnapshot: List<IptvItem>,
        shouldContinue: () -> Boolean
    ) {
        if (!shouldContinue() || itemsSnapshot.isEmpty()) return
        synchronized(stateLock) {
            val existing = inFlightJob
            if (existing != null && existing.isActive) {
                return
            }
            val now = clockMs()
            val lastAt = lastSavedAtByType[type] ?: 0L
            val lastCount = lastSavedCountByType[type] ?: 0
            val isFirstBatch = lastCount == 0
            val enoughTimeElapsed = (now - lastAt) >= minIntervalMs
            if (!isFirstBatch && (!enoughTimeElapsed || itemsSnapshot.size <= lastCount)) {
                return
            }
            inFlightJob = scope.launch(Dispatchers.IO) {
                persistMutex.withLock {
                    if (!shouldContinue()) return@withLock
                    try {
                        val grouped = itemsSnapshot.groupBy { it.group }
                        onPersist(type, grouped)
                        synchronized(stateLock) {
                            lastSavedAtByType[type] = clockMs()
                            lastSavedCountByType[type] = itemsSnapshot.size
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        Log.w("ThrottledCacheSaver", "Error guardando caché parcial $type", t)
                    }
                }
            }
        }
    }

    suspend fun saveFinal(
        type: String,
        items: List<IptvItem>,
        shouldContinue: () -> Boolean
    ) {
        val prevJob = synchronized(stateLock) { inFlightJob }
        try {
            prevJob?.join()
        } catch (_: CancellationException) {
            // Ignored; cancellation check runs inside lock
        } catch (_: Throwable) {}

        persistMutex.withLock {
            if (!shouldContinue() || items.isEmpty()) return@withLock
            val grouped = items.groupBy { it.group }
            onPersist(type, grouped)
            synchronized(stateLock) {
                lastSavedAtByType[type] = clockMs()
                lastSavedCountByType[type] = items.size
            }
        }
    }
}
