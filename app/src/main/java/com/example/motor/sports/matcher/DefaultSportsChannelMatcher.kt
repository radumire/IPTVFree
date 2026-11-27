package com.example.motor.sports.matcher

import android.util.Log
import com.example.motor.db.IptvItem
import com.example.motor.sports.model.SportsEvent
import com.example.motor.sports.model.SportsSport

/**
 * Implementación determinista de scoring y emparejamiento para SportsChannelMatcher.
 *
 * Principios del matcher:
 * 1. Las coincidencias específicas y verificables (emisora oficial de TheSportsDB,
 *    nombres de equipos, competición específica, canal temático de la disciplina) tienen prioridad absoluta.
 * 2. Las categorías genéricas ("Deportes", "Sports", "Sport") NO identifican ninguna disciplina concreta
 *    y aportan únicamente un leve contexto secundario que por sí solo nunca supera el umbral.
 * 3. Los proveedores ("DAZN", "Movistar", "M+", "ESPN") emiten múltiples disciplinas y por sí solos
 *    NO determinan el deporte ni acreditan coincidencia para tenis, baloncesto o fútbol.
 * 4. Las contradicciones de disciplina (ej. canal con tokens de fútbol en un evento de tenis o baloncesto,
 *    o canal con tokens de baloncesto en fútbol) provocan una penalización inmediata de descarte (-100).
 * 5. Si TheSportsDB devuelve emisoras verificadas pero ningún canal de la playlist coincide, NO se presentan
 *    candidatos genéricos como si estuviesen verificados: solo se aceptan candidatos con evidencia local fuerte
 *    (ambos equipos o torneo específico), y en su defecto se devuelve NO_MATCH.
 * 6. Sin inventar canales, URLs ni asociaciones dudosas.
 */
class DefaultSportsChannelMatcher : SportsChannelMatcher {

    companion object {
        private const val TAG = "SportsChannelMatcher"

        // Términos de exclusión/penalización para evitar canales no deportivos (noticias, infantil, cine, etc.)
        private val NON_SPORT_KEYWORDS = setOf(
            "noticias", "news", "24h", "24 horas", "infantil", "kids", "dibujos",
            "cine", "cinema", "peliculas", "movies", "series", "musica", "music",
            "radio", "religioso", "novelas", "documentales", "docu", "cocina", "parlamento",
            "clasicos", "clasico", "drama", "accion", "comedia", "terror", "suspense",
            "estrenos", "filmes"
        )

        // Indicadores específicos de cada disciplina deportiva
        private val FOOTBALL_INDICATORS = setOf(
            "laliga", "la liga", "champions", "liga de campeones", "ucl", "premier league",
            "premier", "epl", "serie a", "calcio", "bundesliga", "ligue 1", "copa del rey",
            "uefa", "fifa", "futbol", "football", "soccer", "gol play", "gol tv", "barca tv",
            "real madrid tv", "rmtv", "laligatv", "hypermotion"
        )

        private val BASKET_INDICATORS = setOf(
            "nba", "basket", "baloncesto", "basketball", "euroleague", "euroliga", "eurocup",
            "acb", "liga endesa", "endesa", "wnba", "fiba"
        )

        private val TENNIS_INDICATORS = setOf(
            "tenis", "tennis", "atp", "wta", "wimbledon", "roland garros", "us open",
            "australian open", "davis cup", "copa davis", "tennis channel", "grand slam"
        )

        private val MOTOR_INDICATORS = setOf(
            "f1", "formula 1", "formula1", "motogp", "moto gp", "superbike", "wsbk", "nascar", "indycar"
        )

        private val GOLF_INDICATORS = setOf(
            "golf", "pga"
        )

        private val RUGBY_INDICATORS = setOf(
            "rugby", "six nations", "seis naciones"
        )

        private val BASEBALL_INDICATORS = setOf(
            "baseball", "beisbol", "mlb"
        )

        private val UFC_INDICATORS = setOf(
            "ufc", "mma", "fight pass", "artes marciales", "bellator", "pfl", "combate"
        )

        private val MOVISTAR_PLUS_WORD_REGEX = Regex("\\bmovistar\\s+plus\\s*\\+?")
        private val MOVISTAR_SIGN_REGEX = Regex("\\bmovistar\\s*\\+")
        private val M_PLUS_ABBR_REGEX = Regex("(^|[\\s|:\\-\\[\\]()/•.,])m\\s*\\+(?=[\\s|:\\-\\[\\]()/•.,a-z0-9]|$)")
        private val M_DOT_ABBR_REGEX = Regex("(^|[\\s|:\\-\\[\\]()/•.,])m\\.(?=\\s*[a-z0-9]|$)")
        private val M_PREFIX_WORD_REGEX = Regex(
            "\\bm (?=(?:laliga|la liga|campeones|liga de campeones|l campeones|deportes|golf|clasicos|vamos|ellas vamos|basket|baloncesto|nba|f1|motogp|tenis|tennis|estrenos|accion|comedia|drama)\\b)"
        )
        private val LEADING_PORTAL_NUMBER_REGEX = Regex(
            "(?i)^\\s*(?:(?:\\[[a-z]{2,5}\\]|\\([a-z]{2,5}\\))\\s*[|\\-:]?\\s*|[a-z]{2,3}\\s*[|\\-:]\\s*)?(?:ch\\s*\\d{1,4}\\s*[|\\-.:]+\\s*|#?\\d{1,4}\\s*[|\\-.:]+\\s*|#?\\d{2,4}\\s+(?=[a-zA-Z]))"
        )
    }

    override suspend fun matchChannel(
        event: SportsEvent,
        availableChannels: List<IptvItem>,
        playlistId: Long?
    ): SportsChannelMatchResult {
        if (availableChannels.isEmpty()) {
            Log.d(TAG, "Búsqueda para '${event.homeTeam} vs ${event.awayTeam}': La lista de canales está vacía.")
            return SportsChannelMatchResult(
                decision = MatchDecision.NO_MATCH,
                candidates = emptyList(),
                playlistId = playlistId
            )
        }

        val evaluatedCandidates = mutableListOf<SportsChannelCandidate>()

        val normHome = TextNormalizer.normalize(event.homeTeam)
        val normAway = TextNormalizer.normalize(event.awayTeam)
        val normLeague = TextNormalizer.normalize(event.league)
        val sportType = SportsSport.fromId(event.sport)

        val verifiedBroadcasters = event.broadcastChannels
        val hasVerifiedBroadcasters = verifiedBroadcasters.isNotEmpty()

        val homeTokens = if (normHome.isNotBlank()) TextNormalizer.extractCleanTokens(normHome) else emptyList()
        val awayTokens = if (normAway.isNotBlank()) TextNormalizer.extractCleanTokens(normAway) else emptyList()

        for (channel in availableChannels) {
            // Canal sin URL reproducible o comando Stalker válido no es candidato
            if (!channel.isPlayableChannel()) {
                continue
            }

            val channelNameNorm = TextNormalizer.normalize(channel.name)
            val channelGroupNorm = TextNormalizer.normalize(channel.group)
            val channelTvgNameNorm = TextNormalizer.normalize(channel.tvgName)
            val channelTvgIdNorm = TextNormalizer.normalize(channel.tvgId)

            val fullText = "$channelNameNorm $channelTvgNameNorm $channelGroupNorm $channelTvgIdNorm".trim()
            val paddedFullText = " $fullText "

            var score = 0
            val reasons = mutableListOf<String>()

            // 1. Penalización estricta por canal no deportivo (noticias, cine, infantil, etc.)
            val isNonSport = NON_SPORT_KEYWORDS.any { paddedFullText.contains(" $it ") }
            if (isNonSport) {
                score -= 50
                reasons.add("Penalización por canal no deportivo")
            }

            // 2. Comprobación de contradicción de disciplina (Falso positivo cruzado)
            val contradiction = checkSportContradiction(sportType, paddedFullText)
            if (contradiction != null) {
                score -= 100
                reasons.add("Descarte por contradicción deportiva: $contradiction")
            }

            // 3. Coincidencia de Equipos / Participantes
            val homeMatch = isEntityMatch(fullText, normHome, homeTokens)
            val awayMatch = isEntityMatch(fullText, normAway, awayTokens)

            if (homeMatch && awayMatch) {
                score += 60
                reasons.add("Ambos equipos presentes en el canal (${event.homeTeam} y ${event.awayTeam})")
            } else if (homeMatch) {
                score += 25
                reasons.add("Equipo o participante presente (${event.homeTeam})")
            } else if (awayMatch) {
                score += 25
                reasons.add("Equipo o participante presente (${event.awayTeam})")
            }

            // 4. Coincidencia de Competición / Liga / Torneo
            if (normLeague.isNotBlank()) {
                val leagueMatch = matchLeague(fullText, normLeague, sportType)
                if (leagueMatch) {
                    score += 35
                    reasons.add("Competición coincidente (${event.league})")
                }
            }

            // 5. Coincidencia de Emisora / Broadcaster
            var matchedVerifiedBroadcaster: String? = null
            if (hasVerifiedBroadcasters) {
                for (vb in verifiedBroadcasters) {
                    if (matchBroadcasterPrecise(channel.name ?: "", fullText, vb)) {
                        matchedVerifiedBroadcaster = vb
                        break
                    }
                }
                if (matchedVerifiedBroadcaster != null) {
                    score += 75
                    reasons.add("BROADCASTER_VERIFIED_BY_THESPORTSDB: Coincidencia con emisora oficial ($matchedVerifiedBroadcaster)")
                }
            } else if (!event.broadcaster.isNullOrBlank()) {
                val candidateBroadcasters = event.broadcaster.split(",").map { it.trim() }.filter { it.isNotBlank() }
                var matchedFallback: String? = null
                for (b in candidateBroadcasters) {
                    if (matchThematicBroadcaster(channel.name ?: "", fullText, b, sportType)) {
                        matchedFallback = b
                        break
                    }
                }
                if (matchedFallback != null) {
                    score += 30
                    reasons.add("Emisora temática asociada ($matchedFallback)")
                }
            }

            // 6. Coincidencia de disciplina específica en nombre o grupo de canal
            val sportSpecificScore = scoreSportCategory(sportType, fullText, channelGroupNorm)
            if (sportSpecificScore > 0) {
                score += sportSpecificScore
                reasons.add("Canal temático de la disciplina ($sportType)")
            }

            // 7. Contexto secundario: categoría genérica "Deportes" aporta solo 2 puntos de contexto y SOLO si ya hay coincidencia previa
            val isGeneralSportsGroup = channelGroupNorm.contains("deportes") || channelGroupNorm.contains("sports") ||
                    channelGroupNorm.contains("sport") || channelGroupNorm.contains("deporte")
            if (isGeneralSportsGroup && score > 0) {
                score += 2
                reasons.add("Contexto secundario: categoría deportiva general")
            }

            // 8. Señal secundaria: País de la competición vs País del canal
            if (!event.country.isNullOrBlank()) {
                val normCountry = TextNormalizer.normalize(event.country)
                val normTvgCountry = TextNormalizer.normalize(channel.tvgCountry)
                if (normCountry.isNotBlank() && (normTvgCountry.contains(normCountry) || channelGroupNorm.contains(normCountry))) {
                    if (score > 0) {
                        score += 3
                        reasons.add("País coincidente (${event.country})")
                    }
                }
            }

            // Clasificación por umbral SELECTION_THRESHOLD
            if (score >= SportsChannelMatcher.SELECTION_THRESHOLD) {
                val confidence = if (score >= SportsChannelMatcher.AUTO_PLAY_THRESHOLD) {
                    MatchConfidence.HIGH
                } else {
                    MatchConfidence.MEDIUM
                }

                evaluatedCandidates.add(
                    SportsChannelCandidate(
                        channel = channel,
                        score = score,
                        confidence = confidence,
                        reasons = reasons
                    )
                )
            }
        }

        // Ordenar candidatos por puntuación descendente y deduplicar entradas idénticas
        val sortedCandidates = evaluatedCandidates
            .sortedByDescending { it.score }
            .distinctBy { it.channel.dedupIdentityKey() }

        val verifiedCandidates = sortedCandidates.filter {
            it.reasons.any { r -> r.startsWith("BROADCASTER_VERIFIED_BY_THESPORTSDB") }
        }

        val finalCandidates: List<SportsChannelCandidate>
        val decision: MatchDecision

        if (hasVerifiedBroadcasters) {
            if (verifiedCandidates.isNotEmpty()) {
                finalCandidates = verifiedCandidates
                decision = if (verifiedCandidates.size == 1 && verifiedCandidates[0].confidence == MatchConfidence.HIGH) {
                    MatchDecision.AUTO_PLAY
                } else {
                    MatchDecision.SELECTION
                }
            } else {
                // TheSportsDB proporcionó emisoras pero no se encontró ninguna en la lista.
                // NO inventar coincidencias genéricas. Solo aceptar candidatos con evidencia local fuerte (score >= AUTO_PLAY_THRESHOLD).
                val strongLocalCandidates = sortedCandidates.filter { it.score >= SportsChannelMatcher.AUTO_PLAY_THRESHOLD }
                if (strongLocalCandidates.isNotEmpty()) {
                    finalCandidates = strongLocalCandidates
                    decision = if (strongLocalCandidates.size == 1) {
                        MatchDecision.AUTO_PLAY
                    } else {
                        MatchDecision.SELECTION
                    }
                } else {
                    finalCandidates = emptyList()
                    decision = MatchDecision.NO_MATCH
                }
            }
        } else {
            // Sin TheSportsDB: matching local estricto
            finalCandidates = sortedCandidates
            decision = when {
                sortedCandidates.isEmpty() -> MatchDecision.NO_MATCH
                sortedCandidates.size == 1 && sortedCandidates.first().confidence == MatchConfidence.HIGH -> MatchDecision.AUTO_PLAY
                else -> MatchDecision.SELECTION
            }
        }

        Log.d(TAG, "MATCH_RESULT: Evento='${event.homeTeam} vs ${event.awayTeam}' (${event.league}), CanalesExaminados=${availableChannels.size}, CandidatosValidos=${finalCandidates.size}, Decision=$decision")
        finalCandidates.take(3).forEachIndexed { idx, cand ->
            Log.d(TAG, " -> Top[${idx + 1}]: '${cand.channel.name}' (Grupo='${cand.channel.group}'), Score=${cand.score}, Conf=${cand.confidence}, Razones=${cand.reasons.joinToString("; ")}")
        }

        return SportsChannelMatchResult(
            decision = decision,
            candidates = finalCandidates,
            topMatch = finalCandidates.firstOrNull(),
            playlistId = playlistId
        )
    }

    /**
     * Detecta contradicciones entre el deporte del evento y el canal evaluado.
     * Si un canal tiene indicadores explícitos de otra disciplina, se devuelve la descripción de la contradicción.
     */
    private fun checkSportContradiction(sport: SportsSport, paddedText: String): String? {
        val hasFootball = containsAnyIndicator(paddedText, FOOTBALL_INDICATORS)
        val hasBasket = containsAnyIndicator(paddedText, BASKET_INDICATORS)
        val hasTennis = containsAnyIndicator(paddedText, TENNIS_INDICATORS)
        val hasMotor = containsAnyIndicator(paddedText, MOTOR_INDICATORS)
        val hasGolf = containsAnyIndicator(paddedText, GOLF_INDICATORS)
        val hasRugby = containsAnyIndicator(paddedText, RUGBY_INDICATORS)
        val hasBaseball = containsAnyIndicator(paddedText, BASEBALL_INDICATORS)
        val hasUfc = containsAnyIndicator(paddedText, UFC_INDICATORS)

        return when (sport) {
            SportsSport.TENNIS -> {
                when {
                    hasFootball && !hasTennis -> "Canal específico de fútbol para un evento de tenis"
                    hasBasket && !hasTennis -> "Canal específico de baloncesto para un evento de tenis"
                    hasMotor && !hasTennis -> "Canal de motor para un evento de tenis"
                    hasGolf && !hasTennis -> "Canal de golf para un evento de tenis"
                    hasRugby && !hasTennis -> "Canal de rugby para un evento de tenis"
                    hasBaseball && !hasTennis -> "Canal de béisbol para un evento de tenis"
                    hasUfc && !hasTennis -> "Canal de UFC / MMA para un evento de tenis"
                    else -> null
                }
            }
            SportsSport.BASKETBALL -> {
                when {
                    hasFootball && !hasBasket -> "Canal específico de fútbol para un evento de baloncesto"
                    hasTennis && !hasBasket -> "Canal de tenis para un evento de baloncesto"
                    hasMotor && !hasBasket -> "Canal de motor para un evento de baloncesto"
                    hasGolf && !hasBasket -> "Canal de golf para un evento de baloncesto"
                    hasRugby && !hasBasket -> "Canal de rugby para un evento de baloncesto"
                    hasBaseball && !hasBasket -> "Canal de béisbol para un evento de baloncesto"
                    hasUfc && !hasBasket -> "Canal de UFC / MMA para un evento de baloncesto"
                    else -> null
                }
            }
            SportsSport.FOOTBALL -> {
                when {
                    hasBasket && !hasFootball -> "Canal de baloncesto para un evento de fútbol"
                    hasTennis && !hasFootball -> "Canal de tenis para un evento de fútbol"
                    hasMotor && !hasFootball -> "Canal de motor para un evento de fútbol"
                    hasGolf && !hasFootball -> "Canal de golf para un evento de fútbol"
                    hasRugby && !hasFootball -> "Canal de rugby para un evento de fútbol"
                    hasBaseball && !hasFootball -> "Canal de béisbol para un evento de fútbol"
                    hasUfc && !hasFootball -> "Canal de UFC / MMA para un evento de fútbol"
                    else -> null
                }
            }
            SportsSport.FORMULA1, SportsSport.MOTOS -> {
                when {
                    hasFootball && !hasMotor -> "Canal de fútbol para un evento de motor"
                    hasBasket && !hasMotor -> "Canal de baloncesto para un evento de motor"
                    hasTennis && !hasMotor -> "Canal de tenis para un evento de motor"
                    hasUfc && !hasMotor -> "Canal de UFC / MMA para un evento de motor"
                    else -> null
                }
            }
            SportsSport.UFC -> {
                when {
                    hasFootball && !hasUfc -> "Canal de fútbol para un evento de UFC / MMA"
                    hasBasket && !hasUfc -> "Canal de baloncesto para un evento de UFC / MMA"
                    hasTennis && !hasUfc -> "Canal de tenis para un evento de UFC / MMA"
                    hasMotor && !hasUfc -> "Canal de motor para un evento de UFC / MMA"
                    hasGolf && !hasUfc -> "Canal de golf para un evento de UFC / MMA"
                    hasBaseball && !hasUfc -> "Canal de béisbol para un evento de UFC / MMA"
                    hasRugby && !hasUfc -> "Canal de rugby para un evento de UFC / MMA"
                    else -> null
                }
            }
            SportsSport.RUGBY -> {
                when {
                    hasFootball && !hasRugby -> "Canal de fútbol para un evento de rugby"
                    hasBasket && !hasRugby -> "Canal de baloncesto para un evento de rugby"
                    else -> null
                }
            }
            SportsSport.BASEBALL -> {
                when {
                    hasFootball && !hasBaseball -> "Canal de fútbol para un evento de béisbol"
                    hasBasket && !hasBaseball -> "Canal de baloncesto para un evento de béisbol"
                    else -> null
                }
            }
            SportsSport.GOLF -> {
                when {
                    hasFootball && !hasGolf -> "Canal de fútbol para un evento de golf"
                    hasBasket && !hasGolf -> "Canal de baloncesto para un evento de golf"
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun containsAnyIndicator(paddedText: String, indicators: Set<String>): Boolean {
        for (ind in indicators) {
            if (paddedText.contains(" $ind ")) {
                return true
            }
        }
        return false
    }

    /**
     * Valida si un equipo o participante coincide en el canal evitando colisiones accidentales de subcadenas.
     */
    private fun isEntityMatch(fullText: String, normEntity: String, entityTokens: List<String>): Boolean {
        if (normEntity.isBlank()) return false
        val padded = " $fullText "

        // 1. Coincidencia de frase completa con límites de palabra
        if (padded.contains(" $normEntity ")) return true

        // 2. Coincidencia de todos los tokens significativos (longitud >= 3 y sin tokens de ruido)
        val significantTokens = entityTokens.filter { it.length >= 3 }
        if (significantTokens.isNotEmpty() && significantTokens.all { padded.contains(" $it ") }) {
            return true
        }

        // 3. Para tenis o deportes individuales donde el nombre suele ser "Nombre Apellido"
        // y en transmisiones se muestra el apellido (ej. "Alcaraz", "Djokovic", "Sinner")
        if (significantTokens.size >= 2) {
            val lastName = significantTokens.last()
            if (lastName.length >= 4 && padded.contains(" $lastName ")) {
                return true
            }
        }

        return false
    }

    /**
     * Coincidencia de competición o liga con canales temáticos pertinentes de esa competición.
     * NUNCA asume que términos como 'deportes', 'dazn', 'movistar' o 'm+' demuestran una competición.
     */
    private fun matchLeague(channelText: String, leagueNorm: String, sport: SportsSport): Boolean {
        if (leagueNorm.isBlank()) return false
        val padded = " $channelText "

        if (padded.contains(" $leagueNorm ") || TextNormalizer.containsAllTokens(channelText, leagueNorm)) {
            return true
        }

        return when (sport) {
            SportsSport.FOOTBALL -> when {
                leagueNorm.contains("laliga") || leagueNorm.contains("la liga") || leagueNorm.contains("primera division") -> {
                    padded.contains(" laliga ") || padded.contains(" la liga ") || padded.contains(" m laliga ") ||
                            padded.contains(" dazn laliga ") || padded.contains(" laligatv ")
                }
                leagueNorm.contains("champions") || leagueNorm.contains("ucl") || leagueNorm.contains("liga de campeones") -> {
                    padded.contains(" champions ") || padded.contains(" liga de campeones ") ||
                            padded.contains(" m campeones ") || padded.contains(" l campeones ") || padded.contains(" ucl ")
                }
                leagueNorm.contains("premier") -> {
                    padded.contains(" premier ") || padded.contains(" premier league ") || padded.contains(" epl ")
                }
                leagueNorm.contains("serie a") -> {
                    padded.contains(" serie a ") || padded.contains(" calcio ")
                }
                leagueNorm.contains("bundesliga") -> {
                    padded.contains(" bundesliga ")
                }
                leagueNorm.contains("ligue 1") -> {
                    padded.contains(" ligue 1 ")
                }
                leagueNorm.contains("copa del rey") -> {
                    padded.contains(" copa del rey ")
                }
                else -> false
            }
            SportsSport.BASKETBALL -> when {
                leagueNorm.contains("nba") -> {
                    padded.contains(" nba ") || padded.contains(" nba tv ")
                }
                leagueNorm.contains("euroleague") || leagueNorm.contains("euroliga") -> {
                    padded.contains(" euroleague ") || padded.contains(" euroliga ") || padded.contains(" euroleague tv ")
                }
                leagueNorm.contains("eurocup") -> {
                    padded.contains(" eurocup ") || padded.contains(" eurocup tv ")
                }
                leagueNorm.contains("acb") || leagueNorm.contains("endesa") -> {
                    padded.contains(" acb ") || padded.contains(" liga endesa ") || padded.contains(" endesa ") || padded.contains(" acb tv ")
                }
                else -> false
            }
            SportsSport.TENNIS -> when {
                leagueNorm.contains("wimbledon") -> {
                    padded.contains(" wimbledon ") || padded.contains(" tennis channel ") || padded.contains(" tennis ") || padded.contains(" tenis ")
                }
                leagueNorm.contains("roland garros") || leagueNorm.contains("french open") -> {
                    padded.contains(" roland garros ") || padded.contains(" french open ") || padded.contains(" tennis channel ") ||
                            padded.contains(" eurosport ")
                }
                leagueNorm.contains("us open") -> {
                    padded.contains(" us open ") || padded.contains(" tennis channel ") || padded.contains(" eurosport ")
                }
                leagueNorm.contains("australian open") -> {
                    padded.contains(" australian open ") || padded.contains(" ao tennis ") || padded.contains(" tennis channel ") ||
                            padded.contains(" eurosport ")
                }
                leagueNorm.contains("atp") || leagueNorm.contains("wta") -> {
                    padded.contains(" atp ") || padded.contains(" wta ") || padded.contains(" tennis channel ") ||
                            padded.contains(" tennis ") || padded.contains(" tenis ")
                }
                else -> false
            }
            SportsSport.FORMULA1 -> {
                padded.contains(" f1 ") || padded.contains(" formula 1 ") || padded.contains(" formula1 ")
            }
            SportsSport.MOTOS -> {
                padded.contains(" motogp ") || padded.contains(" moto gp ") || padded.contains(" superbike ")
            }
            SportsSport.RUGBY -> {
                padded.contains(" six nations ") || padded.contains(" seis naciones ") || padded.contains(" rugby ")
            }
            SportsSport.BASEBALL -> {
                padded.contains(" mlb ") || padded.contains(" baseball ") || padded.contains(" beisbol ")
            }
            SportsSport.GOLF -> {
                padded.contains(" pga ") || padded.contains(" golf ")
            }
            SportsSport.UFC -> {
                padded.contains(" ufc ") || padded.contains(" mma ") ||
                padded.contains(" fight pass ") || padded.contains(" combate ") ||
                padded.contains(" bellator ") || padded.contains(" pfl ") ||
                (leagueNorm.contains("ufc") && padded.contains(" ufc "))
            }
            else -> false
        }
    }

    /**
     * Asigna puntuación cuando el canal o grupo es explícitamente temático de la disciplina requerida.
     * La categoría genérica "Deportes" NUNCA puntúa aquí.
     */
    private fun scoreSportCategory(sport: SportsSport, channelText: String, groupNorm: String): Int {
        val paddedChannel = " $channelText "
        val paddedGroup = " $groupNorm "

        return when (sport) {
            SportsSport.FOOTBALL -> {
                if (paddedGroup.contains(" futbol ") || paddedGroup.contains(" football ") || paddedGroup.contains(" soccer ") ||
                    paddedChannel.contains(" futbol ") || paddedChannel.contains(" football ") || paddedChannel.contains(" soccer ") ||
                    paddedChannel.contains(" laliga ") || paddedChannel.contains(" champions ")) {
                    25
                } else {
                    0
                }
            }
            SportsSport.BASKETBALL -> {
                if (paddedGroup.contains(" basket ") || paddedGroup.contains(" baloncesto ") || paddedGroup.contains(" basketball ") || paddedGroup.contains(" nba ") ||
                    paddedChannel.contains(" basket ") || paddedChannel.contains(" baloncesto ") || paddedChannel.contains(" basketball ") || paddedChannel.contains(" nba ")) {
                    25
                } else {
                    0
                }
            }
            SportsSport.TENNIS -> {
                if (paddedGroup.contains(" tenis ") || paddedGroup.contains(" tennis ") || paddedGroup.contains(" atp ") || paddedGroup.contains(" wta ") ||
                    paddedChannel.contains(" tenis ") || paddedChannel.contains(" tennis ") || paddedChannel.contains(" atp ") || paddedChannel.contains(" wta ")) {
                    25
                } else {
                    0
                }
            }
            SportsSport.FORMULA1 -> {
                if (paddedGroup.contains(" f1 ") || paddedGroup.contains(" formula 1 ") || paddedGroup.contains(" motor ") ||
                    paddedChannel.contains(" f1 ") || paddedChannel.contains(" formula 1 ") || paddedChannel.contains(" motor ")) {
                    25
                } else {
                    0
                }
            }
            SportsSport.MOTOS -> {
                if (paddedGroup.contains(" motogp ") || paddedGroup.contains(" moto gp ") || paddedGroup.contains(" motor ") ||
                    paddedChannel.contains(" motogp ") || paddedChannel.contains(" moto gp ") || paddedChannel.contains(" motor ")) {
                    25
                } else {
                    0
                }
            }
            SportsSport.RUGBY -> {
                if (paddedGroup.contains(" rugby ") || paddedChannel.contains(" rugby ")) 25 else 0
            }
            SportsSport.BASEBALL -> {
                if (paddedGroup.contains(" baseball ") || paddedGroup.contains(" beisbol ") || paddedGroup.contains(" mlb ") ||
                    paddedChannel.contains(" baseball ") || paddedChannel.contains(" beisbol ") || paddedChannel.contains(" mlb ")) 25 else 0
            }
            SportsSport.GOLF -> {
                if (paddedGroup.contains(" golf ") || paddedGroup.contains(" pga ") ||
                    paddedChannel.contains(" golf ") || paddedChannel.contains(" pga ")) 25 else 0
            }
            SportsSport.BOXING -> {
                if (paddedGroup.contains(" boxeo ") || paddedGroup.contains(" boxing ") ||
                    paddedChannel.contains(" boxeo ") || paddedChannel.contains(" boxing ")) 25 else 0
            }
            SportsSport.UFC -> {
                if (paddedGroup.contains(" ufc ") || paddedGroup.contains(" mma ") || paddedGroup.contains(" fight ") ||
                    paddedChannel.contains(" ufc ") || paddedChannel.contains(" mma ") || paddedChannel.contains(" fight pass ") ||
                    paddedChannel.contains(" eurosport 2 ") || paddedChannel.contains(" eurosport ")) 25 else 0
            }
            SportsSport.OTHER -> 0
        }
    }

    /**
     * Comprueba si un canal coincide con una emisora temática específica (ej. "Tennis Channel", "Eurosport 1", "NBA TV").
     * Proveedores genéricos como "DAZN" o "Movistar" por sí solos no coinciden a menos que el nombre del canal
     * corresponda estrictamente con la emisora temática.
     */
    private fun matchThematicBroadcaster(channelName: String, fullText: String, broadcasterCandidate: String, sport: SportsSport): Boolean {
        val canonCandidate = canonicalizeBroadcaster(broadcasterCandidate)
        if (canonCandidate.isBlank()) return false

        // Proveedores genéricos solos ("dazn", "movistar", "m+") no acreditan una emisora temática de otra disciplina
        if (canonCandidate == "dazn" || canonCandidate == "movistar" || canonCandidate == "movistar plus") {
            val cleanTokens = TextNormalizer.extractCleanTokens(canonicalizeBroadcaster(stripLeadingPortalChannelNumber(channelName)))
            return cleanTokens == listOf("dazn") ||
                (canonCandidate.startsWith("movistar") && (cleanTokens == listOf("movistar") || cleanTokens == listOf("movistar", "plus")))
        }

        return matchBroadcasterPrecise(channelName, fullText, broadcasterCandidate)
    }

    /**
     * Comprobación precisa de coincidencia de emisora (utilizada para emisoras verificadas por TheSportsDB).
     *
     * Reglas estrictas:
     * - Coincide: "[ES] DAZN LALIGA HD", "104 | DAZN LaLiga H.265", "DAZN LaLiga 1", "M+ LaLiga", "M. DEPORTES HD"
     * - NO coincide: "DAZN", "DAZN 1", "DAZN 2", "DAZN LaLiga 2", "M. CLASICOS", "M. GOLF"
     */
    fun matchBroadcasterPrecise(channelName: String, fullText: String, targetBroadcaster: String): Boolean {
        if (targetBroadcaster.isBlank() || channelName.isBlank()) return false

        val canonTarget = canonicalizeBroadcaster(targetBroadcaster)
        val canonChannel = canonicalizeBroadcaster(stripLeadingPortalChannelNumber(channelName))

        val targetTokens = TextNormalizer.extractCleanTokens(canonTarget)
        val channelTokens = TextNormalizer.extractCleanTokens(canonChannel)

        if (targetTokens.isEmpty()) return false

        // 1. Números específicos (ej. DAZN 1 vs DAZN 2 vs DAZN LaLiga 2)
        val targetNumbers = targetTokens
            .filter { it.all { c -> c.isDigit() } }
            .map { it.trimStart('0').ifEmpty { "0" } }
            .toSet()
        val channelNumbers = channelTokens
            .filter { it.all { c -> c.isDigit() } }
            .map { it.trimStart('0').ifEmpty { "0" } }
            .toSet()

        if (targetNumbers.isNotEmpty()) {
            // Si la emisora objetivo tiene número, el canal DEBE tener exactamente ese número
            if (targetNumbers != channelNumbers) return false
        } else {
            // Si la emisora objetivo NO tiene número (ej. "DAZN LaLiga"),
            // se acepta la señal principal numerada con "1" (ej. "DAZN LaLiga 1"),
            // pero canales secundarios ("DAZN LaLiga 2", "DAZN 2") NO coinciden
            if (channelNumbers.isNotEmpty() && channelNumbers != setOf("1")) return false
        }

        // 2. Tokens no numéricos obligatorios
        val targetNonNumTokens = targetTokens.filter { !it.all { c -> c.isDigit() } }
        val channelNonNumTokens = channelTokens.filter { !it.all { c -> c.isDigit() } }.toSet()

        if (targetNonNumTokens.isEmpty()) return false

        // Todos los tokens principales de la emisora objetivo deben estar presentes en el nombre del canal
        return targetNonNumTokens.all { it in channelNonNumTokens }
    }

    private fun stripLeadingPortalChannelNumber(channelName: String): String {
        return LEADING_PORTAL_NUMBER_REGEX.replaceFirst(channelName, "").trim().ifEmpty { channelName }
    }

    private fun canonicalizeBroadcaster(text: String): String {
        var prepared = text.trim().lowercase()
        prepared = MOVISTAR_PLUS_WORD_REGEX.replace(prepared, "movistar")
        prepared = MOVISTAR_SIGN_REGEX.replace(prepared, "movistar")
        prepared = M_PLUS_ABBR_REGEX.replace(prepared, "$1movistar ")
        prepared = M_DOT_ABBR_REGEX.replace(prepared, "$1movistar ")

        var norm = TextNormalizer.normalize(prepared)
        norm = M_PREFIX_WORD_REGEX.replace(norm, "movistar ")
        return norm
            .replace("liga de campeones", "campeones")
            .replace("l campeones", "campeones")
            .replace("la liga", "laliga")
            .replace("formula 1", "f1")
            .replace("formula1", "f1")
            .replace("moto gp", "motogp")
            .replace("bt sport", "tnt sports")
            .trim()
    }
}
