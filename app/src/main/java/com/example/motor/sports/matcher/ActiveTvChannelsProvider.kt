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

        val candidates = if (hiddenCategories.isEmpty()) {
            dataMap.values.flatten()
        } else {
            // Solo buscar entre las categorías y canales visibles que el usuario ha elegido mostrar (en los ocultos no)
            dataMap.filterKeys { key -> key.trim().lowercase() !in hiddenCategories }
                .values.flatten()
        }

        candidates.filter { item ->
            val itemGroup = item.group?.trim()?.lowercase().orEmpty()
            (itemGroup.isEmpty() || itemGroup !in hiddenCategories) &&
                (!item.url.isNullOrBlank() || !item.stalkerCmd.isNullOrBlank())
        }
    }
}
