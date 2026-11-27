package com.example.motor.repository

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import com.example.interfaz.ParentalControl
import com.example.motor.config.PlayerSettings
import com.example.motor.db.AppDatabase
import com.example.motor.db.FavoriteItem
import com.example.motor.db.IptvItem
import com.example.motor.db.MIGRATION_5_6
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class FavoritesSystemTest {

    private lateinit var app: Application
    private lateinit var db: AppDatabase
    private lateinit var sessionRepo: PortalSessionRepository
    private lateinit var iptvRepo: IptvRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        db = AppDatabase.getDatabase(app)
        sessionRepo = PortalSessionRepository.getInstance(app)
        iptvRepo = IptvRepository.getInstance(app, sessionRepo)
    }

    @Test
    fun migration5To6PreservesExistingFavoritesAndAddsNewColumns() {
        val dbFile = app.getDatabasePath("favorites_migration_5_6_test.db")
        if (dbFile.exists()) dbFile.delete()

        val callback = object : SupportSQLiteOpenHelper.Callback(5) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `favorites` (
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
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(app)
                .name(dbFile.absolutePath)
                .callback(callback)
                .build()
        )

        helper.use {
            val writable = it.writableDatabase
            writable.execSQL(
                """
                INSERT INTO `favorites` (`id`, `title`, `type`, `url`, `logo`, `addedAt`, `playlistId`)
                VALUES ('ch1', 'La 1 HD', 'tv', 'http://tv.test/la1.ts', 'http://logo/1.png', 123456, 77)
                """.trimIndent()
            )

            MIGRATION_5_6.migrate(writable)

            val cursor = writable.query(
                """
                SELECT id, title, type, url, logo, addedAt, playlistId,
                       stalkerCmd, seriesId, categoryGroup, drmScheme, drmLicenseUrl
                FROM favorites WHERE id = 'ch1' AND playlistId = 77
                """.trimIndent()
            )
            cursor.use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("ch1", c.getString(0))
                assertEquals("La 1 HD", c.getString(1))
                assertEquals("tv", c.getString(2))
                assertEquals("http://tv.test/la1.ts", c.getString(3))
                assertEquals("http://logo/1.png", c.getString(4))
                assertEquals(123456L, c.getLong(5))
                assertEquals(77L, c.getLong(6))
                assertTrue(c.isNull(7))
                assertTrue(c.isNull(8))
                assertTrue(c.isNull(9))
                assertTrue(c.isNull(10))
                assertTrue(c.isNull(11))
            }
        }
        if (dbFile.exists()) dbFile.delete()
    }

    @Test
    fun removingEmptyKeyOrBlankTvgIdDoesNotDeleteOtherStalkerOrSeriesFavorites() = runBlocking {
        val plId = 8801L
        val dao = db.favoriteItemDao()
        dao.clearForPlaylist(plId)

        val stalkerChannel = IptvItem(
            name = "Stalker Sports HD",
            group = "Deportes VIP",
            url = "",
            stalkerCmd = "ffmpeg http://stalker.local/ch/101",
            drmScheme = "widevine",
            drmLicenseUrl = "https://lic.local/wv",
            logo = "http://logo/stalker.png"
        )
        val xtreamSeries = IptvItem(
            name = "Dark Secrets",
            group = "Sci-Fi",
            url = "",
            seriesId = "9055",
            logo = "http://logo/dark.png"
        )
        val blankTvgM3uChannel = IptvItem(
            name = "Canal Sin TvgId",
            group = "General",
            tvgId = "",
            url = "http://m3u.local/live/55.m3u8"
        )

        iptvRepo.addFavoriteItem(stalkerChannel, "tv", plId)
        iptvRepo.addFavoriteItem(xtreamSeries, "series", plId)
        iptvRepo.addFavoriteItem(blankTvgM3uChannel, "tv", plId)

        assertEquals(3, dao.getFavoritesForPlaylist(plId).size)

        // Blank string removal must never wipe url == "" Stalker or Series entries
        iptvRepo.removeFavorite("", plId)
        assertEquals(3, dao.getFavoritesForPlaylist(plId).size)

        // Toggling off the M3U channel with blank tvgId must keep Stalker & Series intact
        val stillFav = iptvRepo.toggleFavoriteItem(blankTvgM3uChannel, "tv", plId)
        assertFalse(stillFav)

        val remaining = dao.getFavoritesForPlaylist(plId)
        assertEquals(2, remaining.size)
        assertTrue(IptvRepository.isItemFavorited(stalkerChannel, "tv", remaining))
        assertTrue(IptvRepository.isItemFavorited(xtreamSeries, "series", remaining))
        assertFalse(IptvRepository.isItemFavorited(blankTvgM3uChannel, "tv", remaining))

        // Toggling off Stalker TV preserves Xtream series
        assertFalse(iptvRepo.toggleFavoriteItem(stalkerChannel, "tv", plId))
        val afterStalkerRemove = dao.getFavoritesForPlaylist(plId)
        assertEquals(1, afterStalkerRemove.size)
        assertEquals("9055", afterStalkerRemove.first().seriesId)
    }

    @Test
    fun buildFavoriteIdAndMatchesFavoriteIsolateDuplicateNamesAndTypes() {
        val titanicTv = IptvItem(name = "Titanic", group = "Cine TV", url = "http://stream/titanic-tv")
        val titanicMovie = IptvItem(name = "Titanic", group = "VOD Drama", url = "http://stream/titanic-vod")
        val titanicSeries = IptvItem(name = "Titanic", group = "Miniseries", seriesId = "301")

        val idTv = IptvRepository.buildFavoriteId("tv", titanicTv)
        val idMovie = IptvRepository.buildFavoriteId("movie", titanicMovie)
        val idSeries = IptvRepository.buildFavoriteId("series", titanicSeries)

        assertNotEquals(idTv, idMovie)
        assertNotEquals(idMovie, idSeries)
        assertNotEquals(idTv, idSeries)

        // Two channels with the same name but distinct Stalker commands must remain isolated
        val espn1 = IptvItem(name = "ESPN", group = "US Sports", stalkerCmd = "ffmpeg http://s/1")
        val espn2 = IptvItem(name = "ESPN", group = "LATAM Sports", stalkerCmd = "ffmpeg http://s/2")

        assertNotEquals(
            IptvRepository.buildFavoriteId("tv", espn1),
            IptvRepository.buildFavoriteId("tv", espn2)
        )

        val savedEspn1 = FavoriteItem(
            id = IptvRepository.buildFavoriteId("tv", espn1),
            title = espn1.name,
            type = "tv",
            url = "",
            stalkerCmd = espn1.stalkerCmd,
            categoryGroup = espn1.group,
            addedAt = 1L,
            playlistId = 1L
        )
        assertTrue(IptvRepository.matchesFavorite(espn1, "tv", savedEspn1))
        assertFalse(IptvRepository.matchesFavorite(espn2, "tv", savedEspn1))
        assertFalse(IptvRepository.matchesFavorite(titanicMovie, "movie", savedEspn1))

        // Legacy v5 favorite with empty url matches legacy name safely within same type
        val legacySeriesFav = FavoriteItem(
            id = "Titanic",
            title = "Titanic",
            type = "series",
            url = "",
            addedAt = 1L,
            playlistId = 1L
        )
        assertTrue(IptvRepository.matchesFavorite(titanicSeries, "series", legacySeriesFav))
        assertFalse(IptvRepository.matchesFavorite(titanicMovie, "movie", legacySeriesFav))
    }

    @Test
    fun resolveCatalogItemForFavoriteAndParentalControlPreserveMetadata() {
        val adultChannel = IptvItem(
            name = "Late Night Club",
            group = "Adultos XXX",
            url = "",
            stalkerCmd = "ffmpeg http://adult.local/1",
            drmScheme = "clearkey",
            drmLicenseUrl = "https://lic.local/ck",
            logo = "http://logo/late.png"
        )

        val fav = FavoriteItem(
            id = IptvRepository.buildFavoriteId("tv", adultChannel),
            title = adultChannel.name,
            type = "tv",
            url = "",
            logo = adultChannel.logo,
            addedAt = 100L,
            playlistId = 99L,
            stalkerCmd = adultChannel.stalkerCmd,
            categoryGroup = adultChannel.group,
            drmScheme = adultChannel.drmScheme,
            drmLicenseUrl = adultChannel.drmLicenseUrl
        )

        // Case 1: Empty catalog map (fallback path before cache hydration completes)
        val fallbackResolved = IptvRepository.resolveCatalogItemForFavorite(fav, emptyMap())
        assertEquals("ffmpeg http://adult.local/1", fallbackResolved.stalkerCmd)
        assertEquals("Adultos XXX", fallbackResolved.group)
        assertEquals("clearkey", fallbackResolved.drmScheme)
        assertEquals("https://lic.local/ck", fallbackResolved.drmLicenseUrl)

        val settings = PlayerSettings(parentalEnabled = true, parentalRating = "12+")
        assertTrue(ParentalControl.isChannelRestricted(fallbackResolved, settings))

        // Case 2: Live catalog map contains full channel entry
        val catalogMap = mapOf("Adultos XXX" to listOf(adultChannel))
        val catalogResolved = IptvRepository.resolveCatalogItemForFavorite(fav, catalogMap)
        assertEquals(adultChannel, catalogResolved)
        assertTrue(ParentalControl.isChannelRestricted(catalogResolved, settings))
    }
}
