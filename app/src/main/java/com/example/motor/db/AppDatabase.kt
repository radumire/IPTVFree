package com.example.motor.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Index
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

data class SeriesEpisode(
    val id: String = "",
    val episodeNum: String = "",
    val seasonNum: String = "",
    val title: String,
    val subtitle: String = "",
    val desc: String = "",
    val rating: String = "",
    val icon: String? = null,
    val playbackUrl: String? = null,
    val stalkerCmd: String? = null,
    val seriesId: String? = null,
    val seasonId: String? = null,
    val episodeId: String? = null,
    val videoId: String? = null,
    val fileId: String? = null,
    val file: String? = null,
    val path: String? = null,
    val category: String? = null
) {
    val playUrl: String
        get() = playbackUrl?.ifBlank { null } ?: category?.ifBlank { null } ?: stalkerCmd ?: ""
}

data class SeriesSeason(
    val id: String,
    val name: String,
    val icon: String? = null,
    val episodes: List<SeriesEpisode> = emptyList()
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
    val episodesJson: String, // List<SeriesSeason> serialized as JSON
    val updatedAt: Long
)

@Entity(
    tableName = "playback_history",
    primaryKeys = ["src", "playlistId"],
    indices = [Index(value = ["playlistId"])]
)
data class PlaybackHistory(
    val src: String, // Streaming URL
    val title: String,
    val position: Long, // in seconds
    val duration: Long, // in seconds
    val pct: Float,
    val type: String, // "movie", "series", "live"
    val ts: Long,
    @ColumnInfo(defaultValue = "0")
    val playlistId: Long = 0L
)

@Entity(
    tableName = "favorites",
    primaryKeys = ["id", "playlistId"],
    indices = [Index(value = ["playlistId"])]
)
data class FavoriteItem(
    val id: String, // e.g., "tv-source-name"
    val title: String,
    val type: String, // "tv", "movie", "series"
    val url: String,
    val logo: String? = null,
    val addedAt: Long,
    @ColumnInfo(defaultValue = "0")
    val playlistId: Long = 0L
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
    val avatar: String = "avatar_plumber",
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

    private val categoryMapAdapter = moshi.adapter<Map<String, List<IptvItem>>>(categoryMapType)

    @TypeConverter
    fun fromCategoryMap(map: Map<String, List<IptvItem>>?): String? {
        return map?.let { categoryMapAdapter.toJson(it) }
    }

    @TypeConverter
    fun toCategoryMap(json: String?): Map<String, List<IptvItem>>? {
        return json?.let { categoryMapAdapter.fromJson(it) }
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
interface PlaybackHistoryDao {
    @Query("SELECT * FROM playback_history ORDER BY ts DESC")
    fun getAllHistoryFlow(): Flow<List<PlaybackHistory>>

    @Query("SELECT * FROM playback_history WHERE playlistId = :playlistId ORDER BY ts DESC")
    fun getHistoryForPlaylistFlow(playlistId: Long): Flow<List<PlaybackHistory>>

    @Query("SELECT * FROM playback_history ORDER BY ts DESC")
    suspend fun getAllHistory(): List<PlaybackHistory>

    @Query("SELECT * FROM playback_history WHERE playlistId = :playlistId ORDER BY ts DESC")
    suspend fun getHistoryForPlaylist(playlistId: Long): List<PlaybackHistory>

    @Query("SELECT * FROM playback_history WHERE src = :src")
    suspend fun getBySrc(src: String): PlaybackHistory?

    @Query("SELECT * FROM playback_history WHERE src = :src AND playlistId = :playlistId")
    suspend fun getBySrcAndPlaylist(src: String, playlistId: Long): PlaybackHistory?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: PlaybackHistory)

    @Query("DELETE FROM playback_history WHERE src = :src")
    suspend fun deleteBySrc(src: String)

    @Query("DELETE FROM playback_history WHERE src = :src AND playlistId = :playlistId")
    suspend fun deleteBySrcAndPlaylist(src: String, playlistId: Long)

    @Query("DELETE FROM playback_history WHERE playlistId = :playlistId")
    suspend fun clearForPlaylist(playlistId: Long)

    @Query("DELETE FROM playback_history")
    suspend fun clearAll()
}

@Dao
interface FavoriteItemDao {
    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun getAllFavoritesFlow(): Flow<List<FavoriteItem>>

    @Query("SELECT * FROM favorites WHERE playlistId = :playlistId ORDER BY addedAt DESC")
    fun getFavoritesForPlaylistFlow(playlistId: Long): Flow<List<FavoriteItem>>

    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    suspend fun getAllFavorites(): List<FavoriteItem>

    @Query("SELECT * FROM favorites WHERE playlistId = :playlistId ORDER BY addedAt DESC")
    suspend fun getFavoritesForPlaylist(playlistId: Long): List<FavoriteItem>

    @Query("SELECT * FROM favorites WHERE type = :type ORDER BY addedAt DESC")
    suspend fun getByType(type: String): List<FavoriteItem>

    @Query("SELECT * FROM favorites WHERE type = :type AND playlistId = :playlistId ORDER BY addedAt DESC")
    suspend fun getByTypeAndPlaylist(type: String, playlistId: Long): List<FavoriteItem>

    @Query("SELECT * FROM favorites WHERE id = :id")
    suspend fun getById(id: String): FavoriteItem?

    @Query("SELECT * FROM favorites WHERE id = :id AND playlistId = :playlistId")
    suspend fun getByIdAndPlaylist(id: String, playlistId: Long): FavoriteItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favorite: FavoriteItem)

    @Query("DELETE FROM favorites WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM favorites WHERE id = :id AND playlistId = :playlistId")
    suspend fun deleteByIdAndPlaylist(id: String, playlistId: Long)

    @Query("DELETE FROM favorites WHERE (id = :key OR url = :key OR title = :key) AND playlistId = :playlistId")
    suspend fun deleteByAnyKeyAndPlaylist(key: String, playlistId: Long)

    @Query("DELETE FROM favorites WHERE playlistId = :playlistId")
    suspend fun clearForPlaylist(playlistId: Long)

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

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `playback_history_new` (
                `src` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `position` INTEGER NOT NULL,
                `duration` INTEGER NOT NULL,
                `pct` REAL NOT NULL,
                `type` TEXT NOT NULL,
                `ts` INTEGER NOT NULL,
                `playlistId` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`src`, `playlistId`)
            )
            """.trimIndent()
        )

        var activePlId = 0L
        try {
            val cursor = db.query("SELECT playlistsJson FROM user_profiles LIMIT 1")
            if (cursor.moveToFirst()) {
                val json = cursor.getString(0) ?: ""
                val regex = Regex(""""id":\s*(\d+)[^}]*?"active":\s*true""")
                val match = regex.find(json)
                if (match != null) {
                    activePlId = match.groupValues[1].toLongOrNull() ?: 0L
                } else {
                    val fallbackRegex = Regex(""""id":\s*(\d+)""")
                    val fbMatch = fallbackRegex.find(json)
                    if (fbMatch != null) {
                        activePlId = fbMatch.groupValues[1].toLongOrNull() ?: 0L
                    }
                }
            }
            cursor.close()
        } catch (_: Exception) {}

        db.execSQL(
            """
            INSERT INTO `playback_history_new` (`src`, `title`, `position`, `duration`, `pct`, `type`, `ts`, `playlistId`)
            SELECT `src`, `title`, `position`, `duration`, `pct`, `type`, `ts`, $activePlId FROM `playback_history`
            """.trimIndent()
        )
        db.execSQL("DROP TABLE `playback_history`")
        db.execSQL("ALTER TABLE `playback_history_new` RENAME TO `playback_history`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_playback_history_playlistId` ON `playback_history` (`playlistId`)")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `favorites_new` (
                `id` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `url` TEXT NOT NULL,
                `logo` TEXT,
                `addedAt` INTEGER NOT NULL,
                `playlistId` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`id`, `playlistId`)
            )
            """.trimIndent()
        )

        var activePlId = 0L
        try {
            val cursor = db.query("SELECT playlistsJson FROM user_profiles LIMIT 1")
            if (cursor.moveToFirst()) {
                val json = cursor.getString(0) ?: ""
                val regex = Regex(""""id":\s*(\d+)[^}]*?"active":\s*true""")
                val match = regex.find(json)
                if (match != null) {
                    activePlId = match.groupValues[1].toLongOrNull() ?: 0L
                } else {
                    val fallbackRegex = Regex(""""id":\s*(\d+)""")
                    val fbMatch = fallbackRegex.find(json)
                    if (fbMatch != null) {
                        activePlId = fbMatch.groupValues[1].toLongOrNull() ?: 0L
                    }
                }
            }
            cursor.close()
        } catch (_: Exception) {}

        db.execSQL(
            """
            INSERT INTO `favorites_new` (`id`, `title`, `type`, `url`, `logo`, `addedAt`, `playlistId`)
            SELECT `id`, `title`, `type`, `url`, `logo`, `addedAt`, $activePlId FROM `favorites`
            """.trimIndent()
        )
        db.execSQL("DROP TABLE `favorites`")
        db.execSQL("ALTER TABLE `favorites_new` RENAME TO `favorites`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_favorites_playlistId` ON `favorites` (`playlistId`)")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `epg_cache`")
    }
}

@Database(
    entities = [
        CategoryCache::class,
        SeriesEpisodesCache::class,
        PlaybackHistory::class,
        FavoriteItem::class,
        GeneralSetting::class,
        UserProfile::class
    ],
    version = 5,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun categoryCacheDao(): CategoryCacheDao
    abstract fun seriesEpisodesCacheDao(): SeriesEpisodesCacheDao
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
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
