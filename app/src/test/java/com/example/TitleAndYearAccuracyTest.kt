package com.example

import com.example.motor.parser.IptvM3uParser
import com.example.motor.tmdb.TmdbService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleAndYearAccuracyTest {

    data class LabeledSample(
        val rawInput: String,
        val expectedTitle: String,
        val expectedYear: Int?,
        val isSeries: Boolean = false
    )

    // Labeled dataset of 60 representative real-world samples covering all edge cases
    private val benchmarkDataset = listOf(
        // 1. Titles with numbers that are part of the title itself
        LabeledSample("Blade Runner 2049 (2017)", "Blade Runner 2049", 2017),
        LabeledSample("Blade Runner 2049", "Blade Runner 2049", null),
        LabeledSample("Wonder Woman 1984 (2020)", "Wonder Woman 1984", 2020),
        LabeledSample("Wonder Woman 1984", "Wonder Woman 1984", null),
        LabeledSample("Cyberpunk 2077", "Cyberpunk 2077", null),
        LabeledSample("Death Race 2000 (1975)", "Death Race 2000", 1975),
        LabeledSample("Blues Brothers 2000 (1998)", "Blues Brothers 2000", 1998),
        LabeledSample("2001: A Space Odyssey (1968)", "2001: A Space Odyssey", 1968),
        LabeledSample("1917 (2019)", "1917", 2019),
        LabeledSample("Iron Man 3 (2013)", "Iron Man 3", 2013),
        LabeledSample("Apollo 13 (1995)", "Apollo 13", 1995),
        LabeledSample("Ocean's 11 (2001)", "Ocean's 11", 2001),
        LabeledSample("Se7en (1995)", "Se7en", 1995),
        LabeledSample("District 9 (2009)", "District 9", 2009),
        LabeledSample("The Hateful Eight (2015)", "The Hateful Eight", 2015),

        // 2. Standard movies with release years in parentheses or dots
        LabeledSample("The Matrix (1999)", "The Matrix", 1999),
        LabeledSample("Inception (2010)", "Inception", 2010),
        LabeledSample("Interstellar (2014)", "Interstellar", 2014),
        LabeledSample("Gladiator.2000.1080p.BluRay.mkv", "Gladiator", 2000),
        LabeledSample("Avatar.The.Way.of.Water.2022.2160p", "Avatar The Way of Water", 2022),
        LabeledSample("Oppenheimer.2023.720p.WEB-DL", "Oppenheimer", 2023),
        LabeledSample("Top.Gun.Maverick.2022.UHD.Remux", "Top Gun Maverick", 2022),
        LabeledSample("Barbie (2023)", "Barbie", 2023),
        LabeledSample("Parasite (2019)", "Parasite", 2019),
        LabeledSample("Joker (2019)", "Joker", 2019),

        // 3. Provider prefixes and bracket tags
        LabeledSample("[ES] [1080p] Gladiator (2000)", "Gladiator", 2000),
        LabeledSample("ES: Inception (2010)", "Inception", 2010),
        LabeledSample("LAT | Spider-Man: No Way Home (2021)", "Spider-Man: No Way Home", 2021),
        LabeledSample("4K - Dune: Part Two (2024)", "Dune: Part Two", 2024),
        LabeledSample("CH: Sword and Beloved (2023)", "Sword and Beloved", 2023),
        LabeledSample("[LATINO] [4K] Interstellar (2014)", "Interstellar", 2014),
        LabeledSample("UHD - The Batman (2022)", "The Batman", 2022),

        // 4. Titles in other languages and CJK
        LabeledSample("El secreto de sus ojos (2009)", "El secreto de sus ojos", 2009),
        LabeledSample("La vita è bella (1997)", "La vita è bella", 1997),
        LabeledSample("千与千寻 (2001)", "千与千寻", 2001),
        LabeledSample("寄生虫 (2019)", "寄生虫", 2019),
        LabeledSample("流浪地球 2 (2023)", "流浪地球 2", 2023),
        LabeledSample("Le Fabuleux Destin d'Amélie Poulain (2001)", "Le Fabuleux Destin d'Amélie Poulain", 2001),

        // 5. Punctuation preserved (colons, hyphens, apostrophes)
        LabeledSample("Mission: Impossible - Fallout (2018)", "Mission: Impossible - Fallout", 2018),
        LabeledSample("WALL-E (2008)", "WALL-E", 2008),
        LabeledSample("Spider-Man: Across the Spider-Verse (2023)", "Spider-Man: Across the Spider-Verse", 2023),
        LabeledSample("Avengers: Endgame (2019)", "Avengers: Endgame", 2019),
        LabeledSample("Everything Everywhere All at Once (2022)", "Everything Everywhere All at Once", 2022),

        // 6. Series with season/episode patterns (isSeries = true)
        LabeledSample("Breaking Bad S01E01", "Breaking Bad", null, isSeries = true),
        LabeledSample("Game of Thrones 1x01", "Game of Thrones", null, isSeries = true),
        LabeledSample("Stranger Things Temporada 1 Capitulo 1", "Stranger Things", null, isSeries = true),
        LabeledSample("The Last of Us S01 E01 (2023)", "The Last of Us", 2023, isSeries = true),
        LabeledSample("Loki S02 E06 (2023)", "Loki", 2023, isSeries = true),
        LabeledSample("House of the Dragon S01E10 (2022)", "House of the Dragon", 2022, isSeries = true),
        LabeledSample("Succession S04E03", "Succession", null, isSeries = true),
        LabeledSample("Better Call Saul S06E13", "Better Call Saul", null, isSeries = true),

        // 7. Movies without year or with invalid years
        LabeledSample("Pulp Fiction", "Pulp Fiction", null),
        LabeledSample("Fight Club", "Fight Club", null),
        LabeledSample("The Godfather", "The Godfather", null),
        LabeledSample("Some Movie (9999)", "Some Movie", null),
        LabeledSample("Old Film (1888)", "Old Film", null),

        // 8. Titles with commas in raw string
        LabeledSample("The Good, the Bad and the Ugly (1966)", "The Good, the Bad and the Ugly", 1966),
        LabeledSample("Lock, Stock and Two Smoking Barrels (1998)", "Lock, Stock and Two Smoking Barrels", 1998),
        LabeledSample("Hello, Dolly! (1969)", "Hello, Dolly!", 1969),
        LabeledSample("Love, Rosie (2014)", "Love, Rosie", 2014)
    )

    @Test
    fun benchmarkAccuracyReachesAtLeast95Percent() {
        var titleMatches = 0
        var yearMatches = 0
        val total = benchmarkDataset.size

        for (sample in benchmarkDataset) {
            val result = if (sample.isSeries) {
                val cleanedSeries = TmdbService.cleanSeriesName(sample.rawInput)
                val year = TmdbService.cleanTitleAndExtractYear(sample.rawInput).year
                TmdbService.CleanedTitle(cleanedSeries, year)
            } else {
                TmdbService.cleanTitleAndExtractYear(sample.rawInput)
            }

            val titleMatch = normalizeForComparison(result.title) == normalizeForComparison(sample.expectedTitle)
            val yearMatch = result.year == sample.expectedYear

            if (titleMatch) titleMatches++
            if (yearMatch) yearMatches++

            if (!titleMatch || !yearMatch) {
                println("MISMATCH for '${sample.rawInput}':")
                if (!titleMatch) println("  Title: got '${result.title}', expected '${sample.expectedTitle}'")
                if (!yearMatch) println("  Year: got ${result.year}, expected ${sample.expectedYear}")
            }
        }

        val titleAccuracy = (titleMatches.toDouble() / total.toDouble()) * 100.0
        val yearAccuracy = (yearMatches.toDouble() / total.toDouble()) * 100.0
        val combinedScore = ((titleMatches + yearMatches).toDouble() / (total * 2).toDouble()) * 100.0

        println("=== BENCHMARK RESULTS ===")
        println("Title accuracy: $titleMatches / $total (%.1f%%)".format(titleAccuracy))
        println("Year accuracy:  $yearMatches / $total (%.1f%%)".format(yearAccuracy))
        println("Combined score: %.1f%%".format(combinedScore))

        assertTrue("Title accuracy must reach at least 95%, got $titleAccuracy%", titleAccuracy >= 95.0)
        assertTrue("Year accuracy must reach at least 95%, got $yearAccuracy%", yearAccuracy >= 95.0)
        assertTrue("Combined accuracy must reach at least 95%, got $combinedScore%", combinedScore >= 95.0)
    }

    @Test
    fun testM3uTitleWithCommas() {
        val line1 = """#EXTINF:-1,The Good, the Bad and the Ugly (1966)"""
        val line2 = """#EXTINF:-1 group-title="Action, Thriller",Hello, Dolly! (1969)"""
        val line3 = """#EXTINF:-1 tvg-name="Love, Rosie" group-title="Comedy",Love, Rosie (2014)"""
        val line4 = """#EXTINF:-1,Lock, Stock and Two Smoking Barrels (1998)"""

        val name1 = IptvM3uParser.M3uAttributeParser.extractName(line1)
        val name2 = IptvM3uParser.M3uAttributeParser.extractName(line2)
        val name3 = IptvM3uParser.M3uAttributeParser.extractName(line3)
        val name4 = IptvM3uParser.M3uAttributeParser.extractName(line4)

        assertEquals("The Good, the Bad and the Ugly (1966)", name1)
        assertEquals("Hello, Dolly! (1969)", name2)
        assertEquals("Love, Rosie (2014)", name3)
        assertEquals("Lock, Stock and Two Smoking Barrels (1998)", name4)

        // Verify cleanTitleAndExtractYear preserves commas
        val clean1 = TmdbService.cleanTitleAndExtractYear(name1)
        assertEquals("The Good, the Bad and the Ugly", clean1.title)
        assertEquals(1966, clean1.year)

        val clean3 = TmdbService.cleanTitleAndExtractYear(name3)
        assertEquals("Love, Rosie", clean3.title)
        assertEquals(2014, clean3.year)
    }

    @Test
    fun testMovieTitlesWithNumbersNotMistakenForYears() {
        val rBlade = TmdbService.cleanTitleAndExtractYear("Blade Runner 2049 (2017)")
        assertEquals("Blade Runner 2049", rBlade.title)
        assertEquals(2017, rBlade.year)

        val rWonder = TmdbService.cleanTitleAndExtractYear("Wonder Woman 1984 (2020)")
        assertEquals("Wonder Woman 1984", rWonder.title)
        assertEquals(2020, rWonder.year)

        val rSpace = TmdbService.cleanTitleAndExtractYear("2001: A Space Odyssey (1968)")
        assertEquals("2001: A Space Odyssey", rSpace.title)
        assertEquals(1968, rSpace.year)

        val r1917 = TmdbService.cleanTitleAndExtractYear("1917 (2019)")
        assertEquals("1917", r1917.title)
        assertEquals(2019, r1917.year)

        val rSeven = TmdbService.cleanTitleAndExtractYear("Se7en (1995)")
        assertEquals("Se7en", rSeven.title)
        assertEquals(1995, rSeven.year)
    }

    @Test
    fun testTitlesWithHyphensColonsAndApostrophesPreserved() {
        val rSpider = TmdbService.cleanTitleAndExtractYear("LAT | Spider-Man: No Way Home (2021)")
        assertEquals("Spider-Man: No Way Home", rSpider.title)
        assertEquals(2021, rSpider.year)

        val rMission = TmdbService.cleanTitleAndExtractYear("Mission: Impossible - Fallout (2018)")
        assertEquals("Mission: Impossible - Fallout", rMission.title)
        assertEquals(2018, rMission.year)

        val rWallE = TmdbService.cleanTitleAndExtractYear("WALL-E (2008)")
        assertEquals("WALL-E", rWallE.title)
        assertEquals(2008, rWallE.year)

        val rOceans = TmdbService.cleanTitleAndExtractYear("Ocean's 11 (2001)")
        assertEquals("Ocean's 11", rOceans.title)
        assertEquals(2001, rOceans.year)
    }

    @Test
    fun testSeriesEpisodeHierarchyAndYearExtraction() {
        val ep1 = IptvM3uParser.SeriesEpisodeParser.parseEpisode("The Last of Us S01E02 - Infected")
        assertEquals("The Last of Us", ep1.seriesName)
        assertEquals(1, ep1.seasonNumber)
        assertEquals(2, ep1.episodeNumber)

        val ep2 = IptvM3uParser.SeriesEpisodeParser.parseEpisode("Breaking Bad 1x01 Pilot")
        assertEquals("Breaking Bad", ep2.seriesName)
        assertEquals(1, ep2.seasonNumber)
        assertEquals(1, ep2.episodeNumber)

        val cleanSeries = TmdbService.cleanSeriesName("The Last of Us S01 E01 (2023)")
        assertEquals("The Last of Us", cleanSeries)
        val seriesYear = TmdbService.cleanTitleAndExtractYear("The Last of Us S01 E01 (2023)").year
        assertEquals(2023, seriesYear)
    }

    @Test
    fun testEdgeCasesMovieParser() {
        // 1. Technical resolution numbers (1920) must not be mistaken for year
        val resTest = TmdbService.cleanTitleAndExtractYear("ES: Pelicula 1080p 1920x1080 2021")
        assertEquals("Pelicula", resTest.title)
        assertEquals(2021, resTest.year)

        // 2. Leading category bracket vs actual release year in title
        val categoryAndYear = TmdbService.cleanTitleAndExtractYear("[2024] Alien (1979)")
        assertEquals("Alien", categoryAndYear.title)
        assertEquals(1979, categoryAndYear.year)

        // 3. Leading bracket as only year
        val leadingBracket = TmdbService.cleanTitleAndExtractYear("[2024] Dune 2")
        assertEquals("Dune 2", leadingBracket.title)
        assertEquals(2024, leadingBracket.year)

        // 4. Leading parenthesis as only year
        val leadingParen = TmdbService.cleanTitleAndExtractYear("(2024) Dune: Parte Dos")
        assertEquals("Dune: Parte Dos", leadingParen.title)
        assertEquals(2024, leadingParen.year)

        // 5. Numeric title standalone with quality tags
        val numTitle = TmdbService.cleanTitleAndExtractYear("1917.1080p.mkv")
        assertEquals("1917", numTitle.title)
        assertNull(numTitle.year)

        // 6. calculateMovieMatchScore heavily penalizes old remake when file has a modern year
        val scoreModern = TmdbService.calculateMovieMatchScore(
            sourceTitle = "Halloween (2018)",
            cleanTitle = "Halloween",
            candidateTitle = "Halloween",
            sourceYear = 2018,
            candidateYear = 2018,
            isSeries = false,
            candidateMediaType = "movie"
        )
        val scoreClassic = TmdbService.calculateMovieMatchScore(
            sourceTitle = "Halloween (2018)",
            cleanTitle = "Halloween",
            candidateTitle = "Halloween",
            sourceYear = 2018,
            candidateYear = 1978,
            isSeries = false,
            candidateMediaType = "movie"
        )
        assertTrue("Modern match score ($scoreModern) must be far higher than classic score ($scoreClassic)", scoreModern > scoreClassic + 40.0)
        assertTrue("Modern score must pass threshold", scoreModern >= 50.0)
        assertTrue("Classic score must be penalized below threshold when file says 2018", scoreClassic < 50.0)
    }

    private fun normalizeForComparison(s: String): String {
        return s.trim()
            .replace(Regex("\\s+"), " ")
            .replace("’", "'")
            .replace("`", "'")
    }
}
