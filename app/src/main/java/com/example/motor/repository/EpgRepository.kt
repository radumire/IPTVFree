package com.example.motor.repository

import android.app.Application
import android.util.Log
import com.example.motor.config.JsonUtils
import com.example.motor.db.AppDatabase
import com.example.motor.db.EpgCache
import com.example.motor.db.EpgChannelData
import com.example.motor.epg.EpgEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class EpgRepository private constructor(
    private val application: Application
) {
    private val TAG = "EpgRepository"
    private val db = AppDatabase.getDatabase(application)
    private val epgDao = db.epgCacheDao()

    suspend fun getEpgDataForChannel(channelName: String, epgUrl: String): EpgChannelData? = withContext(Dispatchers.IO) {
        if (epgUrl.isBlank() || channelName.isBlank()) return@withContext null

        try {
            val key = "$epgUrl::$channelName"
            val cached = epgDao.getByChannelKey(key)
            val now = System.currentTimeMillis()
            if (cached != null && (now - cached.updatedAt) < 24 * 3600 * 1000) { // 24h cache
                val data = JsonUtils.moshi.adapter(EpgChannelData::class.java).fromJson(cached.channelDataJson)
                if (data != null) return@withContext data
            }

            // Fetch EPG
            val epgMap = EpgEngine.fetchAndParseEPG(epgUrl)
            val normName = normalizeChannelName(channelName)
            val channelData = epgMap[channelName] ?: epgMap.values.find {
                it.name.equals(channelName, ignoreCase = true)
            } ?: epgMap.values.find {
                normalizeChannelName(it.name) == normName || (it.id.isNotBlank() && normalizeChannelName(it.id) == normName)
            }

            if (channelData != null) {
                val json = JsonUtils.moshi.adapter(EpgChannelData::class.java).toJson(channelData)
                val epgCache = EpgCache(key, epgUrl, json, now)
                epgDao.insert(epgCache)
            }

            channelData
        } catch (e: Exception) {
            Log.e(TAG, "Error loading EPG for channel: $channelName", e)
            null
        }
    }

    private fun normalizeChannelName(name: String): String {
        return name.lowercase(java.util.Locale.ROOT)
            .replace(Regex("(hd|fhd|4k|uhd|raw|1080p|720p|es:|sp:|uk:|us:|fr:|de:|it:|ro:|vip|plus)"), "")
            .replace(Regex("[^a-z0-9]"), "")
    }

    fun buildMockEPG(names: List<String>): Map<String, EpgChannelData> {
        val now = System.currentTimeMillis()
        val HOUR = 3600 * 1000L
        val SHOWS = listOf(
            Triple("Telediario", "Noticias de última hora", "Noticias"),
            Triple("Deportes en directo", "Cobertura completa", "Deporte"),
            Triple("El hormiguero", "Programa de entretenimiento", "Entretenimiento"),
            Triple("MasterChef", "Concurso culinario", "Cocina"),
            Triple("Cine de acción", "Películas clásicas", "Película"),
            Triple("Documental", "Misterios del universo", "Documental"),
            Triple("Fútbol en directo", "Partido de liga", "Deporte")
        )
        val result = HashMap<String, EpgChannelData>()
        val durs = listOf(30, 45, 60, 90)

        names.forEachIndexed { idx, name ->
            val id = "demo-$idx"
            val progs = ArrayList<com.example.motor.db.EpgProgramme>()
            var cursor = now - 12 * HOUR
            var sIdx = idx % SHOWS.size
            while (cursor < now + 36 * HOUR) {
                val durMin = durs[(sIdx + progs.size) % durs.size]
                val stop = cursor + durMin * 60 * 1000L
                val triple = SHOWS[sIdx % SHOWS.size]
                progs.add(com.example.motor.db.EpgProgramme(cursor, stop, triple.first, triple.second, triple.third, listOf("TP", "7", "12", "16", "18")[idx % 5]))
                cursor = stop
                sIdx++
            }
            result[id] = EpgChannelData(id = id, name = name, icon = null, programmes = progs)
        }
        return result
    }

    companion object {
        @Volatile
        private var INSTANCE: EpgRepository? = null

        fun getInstance(application: Application): EpgRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: EpgRepository(application).also { INSTANCE = it }
            }
        }
    }
}
