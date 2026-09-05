package com.example.motor.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.Flow

// ─── DATA MODELS ─────────────────────────────────────────────────────────────

data class IptvItem(
    val name: String,
    val group: String,
    val logo: String? = null,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val tvgLogo: String? = null,
    val tvgLanguage: String? = null,
    val tvgCountry: String? = null,
    val tvgShift: String? = null,
    val tvgChno: String? = null,
    val catchup: String? = null,
    val catchupDays: Int? = null,
    val catchupSource: String? = null,
    val radio: Boolean = false,
    val url: String? = null,
    val stalkerCmd: String? = null,
    val rating: Float? = null,
    val year: Int? = null,
    val seriesId: String? = null,
    val episodes: Int? = null,
    val drmScheme: String? = null,
    val drmLicenseUrl: String? = null
)

data class EpgProgramme(
    val start: Long, // Epoch ms
    val stop: Long,  // Epoch ms
    val title: String,
    val subtitle: String = "",
    val desc: String = "",
    val category: String = "",
    val rating: String = "",
    val icon: String? = null
)

data class EpgChannelData(
    val id: String,
    val name: String,
    val icon: String? = null,
    val programmes: List<EpgProgramme> = emptyList()
)

// ─── ROOM ENTITIES ───────────────────────────────────────────────────────────

@Entity(tableName = "categories")
data class CategoryCache(
    @PrimaryKey val id: String, // e.g., "source::type"
    val source: String,
    val type: String, // "tv", "movie", "series"
    val dataJson: String, // Map<String, List<IptvItem>> serialized
    val updatedAt: Long
)

@Entity(tableName = "series_episodes_cache")
data class SeriesEpisodesCache(
    @PrimaryKey val key: String, // e.g., "source::seriesKey"
    val seriesName: String,
    val episodesJson: String, // List<EpgChannelData> serialized as JSON
    val updatedAt: Long
)

@Entity(tableName = "epg_cache")
data class EpgCache(
    @PrimaryKey val channelKey: String, // e.g., "source::channelKey"
    val source: String,
    val channelDataJson: String, // EpgChannelData serialized
    val updatedAt: Long
)

@Entity(tableName = "playback_history")
data class PlaybackHistory(
    @PrimaryKey val src: String, // Streaming URL
    val title: String,
    val position: Long, // in seconds
    val duration: Long, // in seconds
    val pct: Float,
    val type: String, // "movie", "series", "live"
    val ts: Long
)

@Entity(tableName = "favorites")
data class FavoriteItem(
    @PrimaryKey val id: String, // e.g., "tv-source-name"
    val title: String,
    val type: String, // "tv", "movie", "series"
    val url: String,
    val logo: String? = null,
    val addedAt: Long
)

@Entity(tableName = "general_settings")
data class GeneralSetting(
    @PrimaryKey val key: String,
    val valueJson: String,
    val updatedAt: Long
)

@Entity(tableName = "user_profiles")
data class UserProfile(
    @PrimaryKey val id: Long,
    val name: String,
    val avatar: String = "👤",
    val colorIdx: Int = 0,
    val pin: String = "",
    val playlistsJson: String = "[]", // List<Playlist> serialized as JSON string
    val settingsJson: String = "",    // PlayerSettings serialized as JSON string
    val themeId: String = "purple",
    val customThemesJson: String = "{}", // Overrides per theme
    val hiddenTvJson: String = "{}",     // Set of hidden channel names serialized
    val hiddenMoviesJson: String = "{}", // Set of hidden movie names serialized
    val hiddenSeriesJson: String = "{}"  // Set of hidden series names serialized
)

// ─── TYPE CONVERTERS ─────────────────────────────────────────────────────────

class Converters {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private val iptvItemListType = Types.newParameterizedType(List::class.java, IptvItem::class.java)
    private val categoryMapType = Types.newParameterizedType(Map::class.java, String::class.java, iptvItemListType)
    private val epgProgrammeListType = Types.newParameterizedType(List::class.java, EpgProgramme::class.java)

    private val categoryMapAdapter = moshi.adapter<Map<String, List<IptvItem>>>(categoryMapType)
    private val epgChannelDataAdapter = moshi.adapter(EpgChannelData::class.java)
    private val epgProgrammeListAdapter = moshi.adapter<List<EpgProgramme>>(epgProgrammeListType)

    @TypeConverter
    fun fromCategoryMap(map: Map<String, List<IptvItem>>?): String? {
        return map?.let { categoryMapAdapter.toJson(it) }
    }

    @TypeConverter
    fun toCategoryMap(json: String?): Map<String, List<IptvItem>>? {
        return json?.let { categoryMapAdapter.fromJson(it) }
    }

    @TypeConverter
    fun fromEpgChannelData(data: EpgChannelData?): String? {
        return data?.let { epgChannelDataAdapter.toJson(it) }
    }

    @TypeConverter
    fun toEpgChannelData(json: String?): EpgChannelData? {
        return json?.let { epgChannelDataAdapter.fromJson(it) }
    }
}

// ─── ROOM DAOS ───────────────────────────────────────────────────────────────

@Dao
interface CategoryCacheDao {
    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: String): CategoryCache?

    @Query("SELECT * FROM categories WHERE source = :source")
    suspend fun getAllForSource(source: String): List<CategoryCache>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(cache: CategoryCache)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM categories WHERE source = :source")
    suspend fun deleteForSource(source: String)

    @Query("DELETE FROM categories")
    suspend fun clearAll()
}

@Dao
interface SeriesEpisodesCacheDao {
    @Query("SELECT * FROM series_episodes_cache WHERE `key` = :key")
    suspend fun getByKey(key: String): SeriesEpisodesCache?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(cache: SeriesEpisodesCache)

    @Query("DELETE FROM series_episodes_cache WHERE `key` = :key")
    suspend fun deleteByKey(key: String)

    @Query("DELETE FROM series_episodes_cache")
    suspend fun clearAll()
}

@Dao
interface EpgCacheDao {
    @Query("SELECT * FROM epg_cache WHERE channelKey = :channelKey")
    suspend fun getByChannelKey(channelKey: String): EpgCache?

    @Query("SELECT * FROM epg_cache WHERE source = :source")
    suspend fun getAllForSource(source: String): List<EpgCache>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(cache: EpgCache)

    @Query("DELETE FROM epg_cache WHERE source = :source")
    suspend fun deleteForSource(source: String)

    @Query("DELETE FROM epg_cache")
    suspend fun clearAll()
}

@Dao
interface PlaybackHistoryDao {
    @Query("SELECT * FROM playback_history ORDER BY ts DESC")
    fun getAllHistoryFlow(): Flow<List<PlaybackHistory>>

    @Query("SELECT * FROM playback_history ORDER BY ts DESC")
    suspend fun getAllHistory(): List<PlaybackHistory>

    @Query("SELECT * FROM playback_history WHERE src = :src")
    suspend fun getBySrc(src: String): PlaybackHistory?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: PlaybackHistory)

    @Query("DELETE FROM playback_history WHERE src = :src")
    suspend fun deleteBySrc(src: String)

    @Query("DELETE FROM playback_history")
    suspend fun clearAll()
}

@Dao
interface FavoriteItemDao {
    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun getAllFavoritesFlow(): Flow<List<FavoriteItem>>

    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    suspend fun getAllFavorites(): List<FavoriteItem>

    @Query("SELECT * FROM favorites WHERE type = :type ORDER BY addedAt DESC")
    suspend fun getByType(type: String): List<FavoriteItem>

    @Query("SELECT * FROM favorites WHERE id = :id")
    suspend fun getById(id: String): FavoriteItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favorite: FavoriteItem)

    @Query("DELETE FROM favorites WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM favorites")
    suspend fun clearAll()
}

@Dao
interface GeneralSettingDao {
    @Query("SELECT * FROM general_settings WHERE `key` = :key")
    suspend fun getByKey(key: String): GeneralSetting?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(setting: GeneralSetting)

    @Query("DELETE FROM general_settings WHERE `key` = :key")
    suspend fun deleteByKey(key: String)
}

@Dao
interface UserProfileDao {
    @Query("SELECT * FROM user_profiles ORDER BY id ASC")
    fun getAllProfilesFlow(): Flow<List<UserProfile>>

    @Query("SELECT * FROM user_profiles ORDER BY id ASC")
    suspend fun getAllProfiles(): List<UserProfile>

    @Query("SELECT * FROM user_profiles WHERE id = :id")
    suspend fun getProfileById(id: Long): UserProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: UserProfile)

    @Query("DELETE FROM user_profiles WHERE id = :id")
    suspend fun deleteProfileById(id: Long)

    @Query("DELETE FROM user_profiles")
    suspend fun clearAll()
}

// ─── DATABASE DEFINITION ─────────────────────────────────────────────────────

@Database(
    entities = [
        CategoryCache::class,
        SeriesEpisodesCache::class,
        EpgCache::class,
        PlaybackHistory::class,
        FavoriteItem::class,
        GeneralSetting::class,
        UserProfile::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun categoryCacheDao(): CategoryCacheDao
    abstract fun seriesEpisodesCacheDao(): SeriesEpisodesCacheDao
    abstract fun epgCacheDao(): EpgCacheDao
    abstract fun playbackHistoryDao(): PlaybackHistoryDao
    abstract fun favoriteItemDao(): FavoriteItemDao
    abstract fun generalSettingDao(): GeneralSettingDao
    abstract fun userProfileDao(): UserProfileDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "radu_iptv_player_db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
