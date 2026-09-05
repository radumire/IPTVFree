package com.example.interfaz

import com.example.motor.db.EpgChannelData
import com.example.motor.db.EpgProgramme
import com.example.motor.db.IptvItem
import com.example.motor.repository.TmdbRepository

data class PrecomputedSeriesData(
    val groups: List<String>,
    val seriesBaseNameCache: Map<String, String>,
    val categoryCounts: Map<String, Int>,
    val allSeriesUnique: List<IptvItem>,
    val groupToUniqueSeries: Map<String, List<IptvItem>>,
    val allSeries: List<IptvItem>
)

data class PrecomputedMoviesData(
    val groups: List<String>,
    val allMovies: List<IptvItem>,
    val groupToMovies: Map<String, List<IptvItem>>,
    val categoryCounts: Map<String, Int>,
    val totalMoviesCount: Int
)

fun computeMoviesData(moviesMap: Map<String, List<IptvItem>>): PrecomputedMoviesData {
    val allMovies = ArrayList<IptvItem>()
    val cleanedMap = HashMap<String, List<IptvItem>>()
    val counts = HashMap<String, Int>()

    moviesMap.forEach { (group, list) ->
        if (!com.example.motor.parser.BaseEngine.isDividerOrHeader(group)) {
            val validItems = list.filter { !com.example.motor.parser.BaseEngine.isDividerOrHeader(it.name) }
            if (validItems.isNotEmpty()) {
                cleanedMap[group] = validItems
                counts[group] = validItems.size
                allMovies.addAll(validItems)
            }
        }
    }

    val groupsList = ArrayList<String>().apply {
        add("Todos")
        add("Favoritos")
        addAll(cleanedMap.keys.sorted())
    }

    counts["Todos"] = allMovies.size
    counts["Favoritos"] = 0

    return PrecomputedMoviesData(
        groups = groupsList,
        allMovies = allMovies,
        groupToMovies = cleanedMap,
        categoryCounts = counts,
        totalMoviesCount = allMovies.size
    )
}

private val s00e00RegexVM = Regex("(?i)\\b[ST]\\s*\\d+\\s*[-_.]?\\s*E\\s*\\d+\\b")
private val s00RegexVM = Regex("(?i)\\b[ST]\\s*\\d+\\b")
private val e00RegexVM = Regex("(?i)\\b[EC]\\s*\\d+\\b")
private val x00RegexVM = Regex("(?i)\\b\\d+\\s*x\\s*\\d+\\b")
private val temporadaRegexVM = Regex("(?i)\\b(temporada|temp|t)\\.?\\s*\\d+\\b")
private val seasonRegexVM = Regex("(?i)\\bseason\\s*\\d+\\b")
private val capituloRegexVM = Regex("(?i)\\bcapitulo\\s*\\d+\\b")
private val capRegexVM = Regex("(?i)\\bcap\\.?\\s*\\d+\\b")
private val episodioRegexVM = Regex("(?i)\\bepisodio\\s*\\d+\\b")
private val epRegexVM = Regex("(?i)\\bep\\.?\\s*\\d+\\b")
private val spacesRegexVM = Regex("\\s+")

private val regexStEp = Regex("(?i)\\b[ST]\\s*\\d+\\s*[-_.]?\\s*(?:E|EP|EPISODE|EPISODIO|C|CAP|CAPITULO)?\\s*[-_.]?\\s*\\d+\\b")
private val regexNxN = Regex("(?i)\\b\\d+\\s*x\\s*\\d+\\b")
private val regexSeasonTemp = Regex("(?i)\\b(?:season|temporada|temp|t|s)\\.?\\s*\\d+\\b")
private val regexEpisodeCap = Regex("(?i)\\b(?:episode|episodio|ep|capitulo|cap|c|e)\\.?\\s*\\d+\\b")
private val regexStDigits = Regex("(?i)\\b[ST]\\d+\\b")
private val regexEpcDigits = Regex("(?i)\\b[EPC]\\d+\\b")
private val regexTrailingDashEp = Regex("(?i)[-_/#:]+\\s*(?:ep|ep\\.|cap|cap\\.|e|c)?\\s*\\d{1,4}\\b")
private val regexTrailingDigit = Regex("\\s+[-_.]?\\s*\\d{1,4}$")
private val regexMultiSpace = Regex("\\s+")
private val regexNonAlphaNum = Regex("[^a-z0-9áéíóúñ]")

private val regexCategoryGroup = Regex("^(?i)([a-z]{2,4}|latino|castellano|canales|esp|spa|usa|uk|fra|ger|ita|por)\\s*[|:\\-➔➔➔]\\s*(.*)$")
private val regexSpain = Regex("(?i)\\b(españa|spain|esp|es)\\b")
private val regexLat = Regex("(?i)\\b(latino|latam|lat)\\b")
private val regexUS = Regex("(?i)\\b(usa|uk|english|eng|us)\\b")
private val regexIT = Regex("(?i)\\b(italy|italia|it)\\b")
private val regexFR = Regex("(?i)\\b(france|francia|fr)\\b")
private val regexPT = Regex("(?i)\\b(portugal|pt)\\b")
private val regexDE = Regex("(?i)\\b(germany|alemania|de)\\b")

fun normalizeBaseSeriesName(name: String?): String {
    if (name.isNullOrBlank()) return ""
    val base = getBaseSeriesName(name)
    return base.lowercase().replace(regexNonAlphaNum, "")
}

private val countryPrefixRegexSeries = Regex("(?i)^(?:[:|\\-]?\\s*(?:en|es|esp|spa|lat|latino|latam|cast|castellano|fr|fra|de|deu|it|ita|pt|por|uk|us|usa|eng|fhd|hd|sd|4k|uhd|3d|2d|hdx|movistar|m\\+|ar|mx|cl|co|pe|uy|ve|br)\\b[:|\\-]?\\s*|^\\[[^\\]]+\\]\\s*|^\\([^\\)]+\\)\\s*|^[a-zA-Z]{2,3}[:|\\-]\\s*|^:([a-zA-Z]{2,3})\\s*)+")

private val sEPatternBase = Regex("(?i)\\b[ST]\\s*\\.?\\s*\\d{1,3}\\s*[-_./\\s]*(?:E|EP|EPISODE|EPISODIO|C|CAP|CAPITULO|FOLGE)?\\s*\\.?\\s*\\d{1,4}\\b")
private val xPatternBase = Regex("(?i)\\b\\d{1,3}\\s*x\\s*\\d{1,4}\\b")
private val seasonWordPatternBase = Regex("(?i)\\b(?:season|temporada|temp|saison|staffel)\\.?\\s*\\d{1,3}\\b")
private val epWordPatternBase = Regex("(?i)\\b(?:episode|épisode|episodio|capitulo|capítulo|cap|ep|folge)\\.?\\s*\\d{1,4}\\b")
private val bracketPatternBase = Regex("(?i)[\\[\\(](?:S|T|Temporada|Season|Temp|Saison)?\\.?\\s*\\d{1,3}[XE._\\- /]*(?:E|EP|C|CAP|Episodio|Capitulo|Episode|Épisode)?\\.?\\s*\\d{1,4}[\\]\\)]")
private val dashDigitPatternBase = Regex("(?i)\\s+[-_/#:]+\\s*(?:ep|cap|episodio|capitulo|episode|épisode|folge|e|c)\\.?\\s*\\d{1,4}\\b")
private val trailingDashNumPatternBase = Regex("(?i)\\s+[-_/#:]+\\s*\\d{1,4}$")
private val trailingDigitPatternBase = Regex("\\s+\\d{1,4}$")

private val yearRegexInBase = Regex("\\b(19[5-9]\\d|20[0-9]\\d)\\b")
private val bracketsInBase = Regex("\\[.*?\\]")
private val parensInBase = Regex("\\(.*?\\)")
private val keywordsInBase = Regex("(?i)\\b(1080p|720p|2160p|4k|uhd|bluray|blu-ray|h264|h265|x264|x265|hevc|webrip|web-dl|dvdrip|dvd|latino|castellano|español|espanol|spanish|english|french|dual|multi|hdtv|mkv|mp4|avi|hd|fhd|sd|3d)\\b")
private val punctuationInBase = Regex("[.\\-_/|+]")
private val nonAlphaNumInBase = Regex("[^a-zA-Z0-9áéíóúÁÉÍÓÚñÑ ]")
private val multiSpaceInBase = Regex("\\s+")

private const val DEBUG_SERIES_LOG = false

private val basePatterns = arrayOf(
    sEPatternBase,
    xPatternBase,
    seasonWordPatternBase,
    epWordPatternBase,
    bracketPatternBase,
    dashDigitPatternBase,
    trailingDashNumPatternBase
)

fun normalizeBaseSeriesName(name: String?, groupTitle: String? = null): String {
    if (name.isNullOrBlank() && groupTitle.isNullOrBlank()) return ""
    val base = getBaseSeriesName(name, groupTitle)
    return base.lowercase().replace(regexNonAlphaNum, "")
}

fun getBaseSeriesName(name: String?, groupTitle: String? = null): String {
    if (name.isNullOrBlank() && groupTitle.isNullOrBlank()) return ""

    var s = (name ?: "").trim()

    // 1. Strip country/language/category prefixes from start
    var prefixMatched = true
    var loopCount = 0
    while (prefixMatched && loopCount < 5) {
        loopCount++
        val beforeLen = s.length
        s = s.replace(countryPrefixRegexSeries, "").trim()
        prefixMatched = s.length != beforeLen
    }

    // 2. Find cutIndex: the earliest position of ANY season/episode indicator
    var cutIndex = s.length

    for (regex in basePatterns) {
        regex.find(s)?.let { match ->
            if (match.range.first < cutIndex && match.range.first > 0) {
                cutIndex = match.range.first
            }
        }
    }

    // If no explicit S/E marker found, check trailing digits preceded by space
    if (cutIndex == s.length) {
        trailingDigitPatternBase.find(s)?.let { match ->
            if (match.range.first < cutIndex && match.range.first >= 3) {
                cutIndex = match.range.first
            }
        }
    }

    var base = if (cutIndex < s.length) s.substring(0, cutIndex) else s

    // 3. Remove year, brackets, parens, quality keywords
    base = base.replace(yearRegexInBase, " ")
    base = base.replace(bracketsInBase, " ")
    base = base.replace(parensInBase, " ")
    base = base.replace(keywordsInBase, " ")

    // 4. Clean punctuation and non-alphanumeric characters
    base = base.replace(punctuationInBase, " ")
    base = base.replace(nonAlphaNumInBase, " ")
    base = base.replace(multiSpaceInBase, " ").trim()

    val isGeneric = base.length < 2 || base.equals("episode", ignoreCase = true) || base.equals("capitulo", ignoreCase = true) || base.equals("episodio", ignoreCase = true)

    if (isGeneric && !groupTitle.isNullOrBlank() && !groupTitle.equals("General", ignoreCase = true)) {
        val groupBase = getBaseSeriesName(groupTitle, null)
        if (groupBase.length >= 2 && !groupBase.equals("series", ignoreCase = true) && !groupBase.equals("peliculas", ignoreCase = true)) {
            return groupBase
        }
    }

    val result = if (base.length >= 2) base else (name ?: "").trim()
    if (DEBUG_SERIES_LOG) {
        android.util.Log.d("M3U_SERIES_BASE_LOG", "$name\n↓\n$result")
    }
    return result
}

fun getCleanSeriesCategory(groupName: String): Pair<String, String> {
    val cleanGroup = groupName.trim()
    
    val match = regexCategoryGroup.find(cleanGroup)
    val (countryRaw, categoryRaw) = if (match != null) {
        Pair(match.groupValues[1].uppercase(), match.groupValues[2].trim())
    } else {
        when {
            regexSpain.containsMatchIn(cleanGroup) -> Pair("ES", cleanGroup)
            regexLat.containsMatchIn(cleanGroup) -> Pair("LAT", cleanGroup)
            regexUS.containsMatchIn(cleanGroup) -> Pair("US", cleanGroup)
            regexIT.containsMatchIn(cleanGroup) -> Pair("IT", cleanGroup)
            regexFR.containsMatchIn(cleanGroup) -> Pair("FR", cleanGroup)
            regexPT.containsMatchIn(cleanGroup) -> Pair("PT", cleanGroup)
            regexDE.containsMatchIn(cleanGroup) -> Pair("DE", cleanGroup)
            else -> Pair("OTROS", cleanGroup)
        }
    }
    
    val countryAbbr = when (countryAbbrRaw(countryRaw)) {
        "ES", "ESP", "SPA", "CASTELLANO" -> "ES"
        "LAT", "LATINO", "LATIN" -> "LAT"
        "US", "USA", "UK", "ENG" -> "US"
        "FR", "FRA" -> "FR"
        "DE", "GER" -> "DE"
        "IT", "ITA" -> "IT"
        "PT", "POR" -> "PT"
        "OTROS" -> "OTROS"
        else -> countryRaw
    }
    
    val catLower = categoryRaw.lowercase()
    val genre = when {
        catLower.contains("novedad") || catLower.contains("new") || catLower.contains("estreno") || catLower.contains("reciente") || catLower.contains("2026") || catLower.contains("2025") || catLower.contains("2024") -> "Novedades"
        catLower.contains("accion") || catLower.contains("acción") || catLower.contains("action") || catLower.contains("thriller") || catLower.contains("suspenso") || catLower.contains("suspense") || catLower.contains("bélic") || catLower.contains("guerra") -> "Acción"
        catLower.contains("comedia") || catLower.contains("comedy") || catLower.contains("humor") || catLower.contains("risas") -> "Comedia"
        catLower.contains("drama") || catLower.contains("romanc") || catLower.contains("romántica") || catLower.contains("amor") -> "Drama"
        catLower.contains("cienc") || catLower.contains("scifi") || catLower.contains("sci-fi") || catLower.contains("ficcion") || catLower.contains("ficción") || catLower.contains("fantas") || catLower.contains("stargate") || catLower.contains("star trek") || catLower.contains("espacia") -> "Ciencia Ficción"
        catLower.contains("anim") || catLower.contains("dibujo") || catLower.contains("anime") || catLower.contains("infantil") || catLower.contains("kids") || catLower.contains("niño") || catLower.contains("disney") || catLower.contains("cartoon") -> "Animación / Infantil"
        catLower.contains("docu") || catLower.contains("history") || catLower.contains("discovery") || catLower.contains("biograf") || catLower.contains("naturalez") -> "Documentales"
        catLower.contains("terror") || catLower.contains("horror") || catLower.contains("miedo") || catLower.contains("fantasma") || catLower.contains("gore") || catLower.contains("vampir") -> "Terror"
        catLower.contains("aventura") || catLower.contains("adventure") || catLower.contains("viaje") -> "Aventuras"
        catLower.contains("misterio") || catLower.contains("mystery") || catLower.contains("policial") || catLower.contains("crimen") || catLower.contains("detective") -> "Misterio"
        catLower.contains("novela") || catLower.contains("soap") || catLower.contains("drama de tv") -> "Telenovelas"
        else -> "Series"
    }
    
    return Pair(countryAbbr, genre)
}

fun countryAbbrRaw(raw: String): String {
    return when (raw.uppercase()) {
        "ESP", "SPA", "ESPAÑA" -> "ES"
        "LATINO", "LATAM", "LAT" -> "LAT"
        "USA", "UK", "ENGLISH", "ENG" -> "US"
        "FRANCE", "FRANCIA", "FR" -> "FR"
        "ITALY", "ITALIA", "IT" -> "IT"
        "PORTUGAL", "PT" -> "PT"
        "GERMANY", "ALEMANIA", "DE" -> "DE"
        else -> raw
    }
}

fun computeSeriesData(seriesMap: Map<String, List<IptvItem>>): PrecomputedSeriesData {
    val allSeries = ArrayList<IptvItem>()
    val cleanedGroupToSeries = LinkedHashMap<String, List<IptvItem>>()
    val counts = HashMap<String, Int>()
    val baseNameCache = HashMap<String, String>()
    val allSeriesUnique = ArrayList<IptvItem>()

    seriesMap.forEach { (group, list) ->
        val trimmedGroup = group.trim()
        if (trimmedGroup.isNotEmpty() && !com.example.motor.parser.BaseEngine.isDividerOrHeader(trimmedGroup)) {
            val validItems = list.filter { !com.example.motor.parser.BaseEngine.isDividerOrHeader(it.name) }
            if (validItems.isNotEmpty()) {
                allSeries.addAll(validItems)

                // Deduplicate & group items in this category to represent unique series
                val seenInGroup = LinkedHashMap<String, IptvItem>()
                for (item in validItems) {
                    val normBase = normalizeBaseSeriesName(item.name, item.group)
                    baseNameCache[item.name] = normBase
                    
                    val groupKey = if (!item.seriesId.isNullOrBlank()) item.seriesId!! else normBase
                    if (!seenInGroup.containsKey(groupKey)) {
                        // Create clean representative item for the series
                        val cleanName = getBaseSeriesName(item.name, item.group).ifBlank { item.name.trim() }
                        seenInGroup[groupKey] = item.copy(name = cleanName)
                    }
                }
                val uniqueInGroup = seenInGroup.values.toList()
                cleanedGroupToSeries[trimmedGroup] = uniqueInGroup
                counts[trimmedGroup] = uniqueInGroup.size
            }
        }
    }

    // Global unique series across all categories (for "Todos" tab)
    val seenGlobal = LinkedHashMap<String, IptvItem>()
    cleanedGroupToSeries.values.flatten().forEach { item ->
        val normBase = normalizeBaseSeriesName(item.name, item.group)
        val globalKey = if (!item.seriesId.isNullOrBlank()) item.seriesId!! else normBase
        if (!seenGlobal.containsKey(globalKey)) {
            seenGlobal[globalKey] = item
        }
    }
    allSeriesUnique.addAll(seenGlobal.values)

    val groupsList = ArrayList<String>().apply {
        add("Todos")
        add("Favoritos")
        addAll(cleanedGroupToSeries.keys.sorted())
    }

    counts["Todos"] = allSeriesUnique.size
    counts["Favoritos"] = 0

    return PrecomputedSeriesData(
        groups = groupsList,
        seriesBaseNameCache = baseNameCache,
        categoryCounts = counts,
        allSeriesUnique = allSeriesUnique,
        groupToUniqueSeries = cleanedGroupToSeries,
        allSeries = allSeries
    )
}

private val s00e00Regex = Regex("(?i)\\b[ST]\\s*\\d+\\s*[-_.]?\\s*E\\s*\\d+\\b")
private val s00Regex = Regex("(?i)\\b[ST]\\s*\\d+\\b")
private val e00Regex = Regex("(?i)\\b[EC]\\s*\\d+\\b")
private val x00Regex = Regex("(?i)\\b\\d+\\s*x\\s*\\d+\\b")
private val temporadaRegex = Regex("(?i)\\b(temporada|temp|t)\\.?\\s*\\d+\\b")
private val seasonRegex = Regex("(?i)\\bseason\\s*\\d+\\b")
private val capituloRegex = Regex("(?i)\\bcapitulo\\s*\\d+\\b")
private val capRegex = Regex("(?i)\\bcap\\.?\\s*\\d+\\b")
private val episodioRegex = Regex("(?i)\\bepisodio\\s*\\d+\\b")
private val epRegex = Regex("(?i)\\bep\\.?\\s*\\d+\\b")
private val spacesRegex = Regex("\\s+")

private val regexCountryPrefixStrip = Regex("^(?:es|esp|spa|lat|latino|latam|us|usa|uk|eng|fr|fra|de|deu|it|ita|pt|por|ar|mx|cl|co|pe|uy|ve|br)")

fun groupM3uEpisodes(
    seriesItem: IptvItem,
    allSeries: List<IptvItem>,
    baseNameCache: Map<String, String>? = null
): List<EpgChannelData> {
    val baseName = baseNameCache?.get(seriesItem.name) ?: normalizeBaseSeriesName(seriesItem.name, seriesItem.group)
    
    // Fast O(1) matching using baseNameCache, falling back to cached local computations
    val localCache = HashMap<String, String>()
    val mainMatches = allSeries.filter { ep ->
        val cached = baseNameCache?.get(ep.name)
        val normEp = if (cached != null) cached else localCache.getOrPut(ep.name) { normalizeBaseSeriesName(ep.name, ep.group) }
        normEp.equals(baseName, ignoreCase = true)
    }
    val countAfterMain = mainMatches.size
    var matchingEpisodes = mainMatches

    // Fallback 1: Match by stripping common country/region prefix tags if exact normBase didn't hit
    var countAfterFallback1 = countAfterMain
    if (matchingEpisodes.isEmpty() && baseName.isNotEmpty()) {
        val strippedBase = baseName.replace(regexCountryPrefixStrip, "")
        if (strippedBase.length >= 3) {
            matchingEpisodes = allSeries.filter { ep ->
                val cached = baseNameCache?.get(ep.name) ?: localCache.getOrPut(ep.name) { normalizeBaseSeriesName(ep.name, ep.group) }
                val strippedEp = cached.replace(regexCountryPrefixStrip, "")
                strippedEp.equals(strippedBase, ignoreCase = true)
            }
        }
        countAfterFallback1 = matchingEpisodes.size
    }

    // Fallback 2: Match by group-title if the items share the same group
    var countAfterFallback2 = countAfterFallback1
    if (matchingEpisodes.isEmpty() && seriesItem.group.isNotBlank() && !seriesItem.group.equals("General", ignoreCase = true)) {
        val cleanGroupBase = normalizeBaseSeriesName(seriesItem.group)
        if (cleanGroupBase.length >= 3) {
            matchingEpisodes = allSeries.filter { ep ->
                ep.group.equals(seriesItem.group, ignoreCase = true) ||
                normalizeBaseSeriesName(ep.group) == cleanGroupBase
            }
        }
        countAfterFallback2 = matchingEpisodes.size
    }

    if (matchingEpisodes.isEmpty()) {
        return listOf(
            EpgChannelData(
                id = "1",
                name = "Temporada 1",
                programmes = listOf(
                    EpgProgramme(
                        start = 0,
                        stop = 0,
                        title = seriesItem.name,
                        category = seriesItem.url ?: "",
                        rating = "1"
                    )
                )
            )
        )
    }

    val displayBaseName = getBaseSeriesName(seriesItem.name, seriesItem.group).ifBlank { seriesItem.name.trim() }

    val seasonsMap = mutableMapOf<Int, MutableList<Pair<Int, IptvItem>>>()
    matchingEpisodes.forEachIndexed { idx, ep ->
        val sMatch = Regex("(?i)\\b[ST](\\d+)\\s*[Ee.]?\\s*(\\d+)").find(ep.name)
            ?: Regex("(?i)\\b(\\d+)x(\\d+)\\b").find(ep.name)
            ?: Regex("(?i)(?:temporada|season|temp|t|s)\\s*(\\d+)").find(ep.name)
        val epMatch = Regex("(?i)(?:capitulo|episodio|episode|ep|cap|e|c)\\s*(\\d+)").find(ep.name)

        val seasonNum = sMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val epNum = sMatch?.groupValues?.getOrNull(2)?.toIntOrNull() 
            ?: epMatch?.groupValues?.get(1)?.toIntOrNull() 
            ?: (idx + 1)
        seasonsMap.getOrPut(seasonNum) { mutableListOf() }.add(Pair(epNum, ep))
    }

    return seasonsMap.entries.sortedBy { it.key }.map { (seasonNum, epList) ->
        val sortedList = epList.sortedBy { it.first }

        val programmes = sortedList.map { (epNum, epItem) ->
            val cleanEpName = TmdbRepository.getInstance().cleanTitleAndExtractYear(epItem.name).title
            var displayName = cleanEpName.replace(displayBaseName, "", ignoreCase = true).trim()
            displayName = displayName.replace(s00e00Regex, "").trim()
            displayName = displayName.replace(x00Regex, "").trim()
            displayName = displayName.replace(temporadaRegex, "").trim()
            displayName = displayName.replace(seasonRegex, "").trim()
            displayName = displayName.replace(capituloRegex, "").trim()
            displayName = displayName.replace(capRegex, "").trim()
            displayName = displayName.replace(episodioRegex, "").trim()
            displayName = displayName.replace(epRegex, "").trim()
            displayName = displayName.trim().removePrefix("-").trim().removePrefix(":").trim()

            val isOnlyEpNumber = displayName == epNum.toString() ||
                                 displayName == String.format("%02d", epNum) ||
                                 displayName == String.format("%03d", epNum)

            val titleText = if (displayName.isNotEmpty() && !isOnlyEpNumber) {
                "S${seasonNum} E${epNum}: $displayName"
            } else {
                "S${seasonNum} E${epNum}"
            }

            EpgProgramme(
                start = 0,
                stop = 0,
                title = titleText,
                category = epItem.url ?: "",
                rating = epNum.toString()
            )
        }

        EpgChannelData(
            id = seasonNum.toString(),
            name = "Temporada $seasonNum",
            programmes = programmes
        )
    }
}

