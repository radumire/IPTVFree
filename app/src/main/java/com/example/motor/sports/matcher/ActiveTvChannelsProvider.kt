package com.example.motor.sports.matcher

import android.app.Application
import com.example.motor.db.IptvItem
import com.example.motor.repository.MediaCacheRepository
import com.example.motor.repository.PortalSessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Proveedor de solo lectura de los canales de TV de la playlist activa.
 *
 * REGLAS DE MEMORIA Y RENDIMIENTO:
 * - NO descarga nuevamente la lista ni ejecuta parsers de red.
 * - Reutiliza la caché L2 en RAM o lectura desde disco local de MediaCacheRepository.
 * - Consulta de forma reactiva el ID de la playlist activa en PortalSessionRepository.
 */
interface ActiveTvChannelsProvider {
    fun getActivePlaylistId(): Long?
    suspend fun getActiveTvChannels(): List<IptvItem>
}

class DefaultActiveTvChannelsProvider(
    private val sessionRepo: PortalSessionRepository,
    private val mediaCacheRepo: MediaCacheRepository
) : ActiveTvChannelsProvider {

    companion object {
        @Volatile
        private var INSTANCE: DefaultActiveTvChannelsProvider? = null

        fun getInstance(application: Application): DefaultActiveTvChannelsProvider {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DefaultActiveTvChannelsProvider(
                    sessionRepo = PortalSessionRepository.getInstance(application),
                    mediaCacheRepo = MediaCacheRepository.getInstance(application)
                ).also { INSTANCE = it }
            }
        }
    }

    override fun getActivePlaylistId(): Long? {
        return sessionRepo.activePlaylist.value?.id
    }

    override suspend fun getActiveTvChannels(): List<IptvItem> = withContext(Dispatchers.IO) {
        val playlist = sessionRepo.activePlaylist.value ?: return@withContext emptyList()
        val cacheResult = mediaCacheRepo.getCategoryCache(playlist.id, "tv")
        val dataMap = cacheResult.data ?: return@withContext emptyList()
        val rawHidden = sessionRepo.settings.value.hiddenTvCategories
        val hiddenCategories = rawHidden.map { it.trim().lowercase() }.toSet()

        val hasNonSyntheticCategories = dataMap.keys.any { key ->
            val norm = key.trim().lowercase()
            norm != "todos" && norm != "all"
        }

        val seenKeys = HashSet<String>()
        val result = ArrayList<IptvItem>()

        for ((categoryKey, items) in dataMap) {
            val normCategoryKey = categoryKey.trim().lowercase()
            if (hasNonSyntheticCategories && (normCategoryKey == "todos" || normCategoryKey == "all")) {
                continue
            }
            if (normCategoryKey in hiddenCategories) {
                continue
            }

            for (item in items) {
                val itemGroup = item.group.trim().lowercase()
                if (itemGroup.isNotEmpty() && itemGroup in hiddenCategories) {
                    continue
                }
                if (!item.isPlayableChannel()) {
                    continue
                }
                if (seenKeys.add(item.dedupIdentityKey())) {
                    result.add(item)
                }
            }
        }

        result
    }
}
