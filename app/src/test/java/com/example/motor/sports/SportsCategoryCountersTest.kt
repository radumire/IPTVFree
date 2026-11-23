package com.example.motor.sports

import com.example.interfaz.viewmodel.SportsUiState
import com.example.motor.sports.model.SportsEvent
import com.example.motor.sports.model.SportsEventStatus
import com.example.motor.sports.model.SportsFilter
import com.example.motor.sports.model.SportsSport
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SportsCategoryCountersTest {

    private fun createEvent(id: String, sport: SportsSport, isLive: Boolean = false): SportsEvent {
        return SportsEvent(
            id = id,
            sport = sport.id,
            league = "Liga Test",
            homeTeam = "Equipo A",
            awayTeam = "Equipo B",
            startTime = System.currentTimeMillis(),
            status = if (isLive) SportsEventStatus.LIVE else SportsEventStatus.SCHEDULED,
            isLive = isLive
        )
    }

    // 1. Categoría sin eventos devuelve 0
    @Test
    fun test01_emptyCategoryReturnsZero() {
        val state = SportsUiState(
            selectedSport = SportsSport.FOOTBALL,
            liveEvents = emptyList(),
            todayEvents = emptyList(),
            tomorrowEvents = emptyList()
        )
        val count = state.getEventCountForSport(SportsSport.FOOTBALL)
        assertEquals(0, count)
    }

    // 2. Categoría con eventos reales calcula el total correctamente (Live + Hoy + Mañana)
    @Test
    fun test02_categoryCalculatesTotalRealEvents() {
        val liveList = (1..5).map { createEvent("live_$it", SportsSport.FOOTBALL, isLive = true) }
        val todayList = (1..14).map { createEvent("today_$it", SportsSport.FOOTBALL) }
        val tomorrowList = (1..5).map { createEvent("tomorrow_$it", SportsSport.FOOTBALL) }

        val state = SportsUiState(
            selectedSport = SportsSport.FOOTBALL,
            liveEvents = liveList,
            todayEvents = todayList,
            tomorrowEvents = tomorrowList
        )

        val footballCount = state.getEventCountForSport(SportsSport.FOOTBALL)
        assertEquals(24, footballCount)
    }

    // 3. Coherencia total entre la 1ª barra (Categoría) y la 2ª barra (Filtros: Todos, Live, Hoy, Mañana)
    @Test
    fun test03_categoryCountIsCoherentWithFilterCounts() {
        val liveList = (1..3).map { createEvent("live_$it", SportsSport.BASKETBALL, isLive = true) }
        val todayList = (1..4).map { createEvent("today_$it", SportsSport.BASKETBALL) }
        val tomorrowList = (1..1).map { createEvent("tomorrow_$it", SportsSport.BASKETBALL) }

        val stateAll = SportsUiState(
            selectedSport = SportsSport.BASKETBALL,
            selectedFilter = SportsFilter.ALL,
            liveEvents = liveList,
            todayEvents = todayList,
            tomorrowEvents = tomorrowList
        )

        // Contador de la categoría
        val categoryCount = stateAll.getEventCountForSport(SportsSport.BASKETBALL)
        assertEquals(8, categoryCount)

        // Filtro TODOS debe ser exactamente igual al contador de la categoría
        assertEquals(categoryCount, stateAll.totalEventsCount)

        // Filtro EN DIRECTO
        val stateLive = stateAll.copy(selectedFilter = SportsFilter.LIVE)
        assertEquals(3, stateLive.totalEventsCount)

        // Filtro HOY
        val stateToday = stateAll.copy(selectedFilter = SportsFilter.TODAY)
        assertEquals(4, stateToday.totalEventsCount)

        // Filtro MAÑANA
        val stateTomorrow = stateAll.copy(selectedFilter = SportsFilter.TOMORROW)
        assertEquals(1, stateTomorrow.totalEventsCount)
    }

    // 4. Múltiples categorías mantienen sus contadores de forma independiente
    @Test
    fun test04_multipleCategoriesPreserveIndependentCounts() {
        val basketballEvents = (1..8).map { createEvent("b_$it", SportsSport.BASKETBALL) }

        val state = SportsUiState(
            selectedSport = SportsSport.BASKETBALL,
            liveEvents = basketballEvents,
            todayEvents = emptyList(),
            tomorrowEvents = emptyList(),
            categoryEventCounts = mapOf(
                SportsSport.FOOTBALL to 24,
                SportsSport.TENNIS to 12,
                SportsSport.BOXING to 0
            )
        )

        // Deporte actualmente activo
        assertEquals(8, state.getEventCountForSport(SportsSport.BASKETBALL))

        // Deportes no activos conservan sus conteos reales previos
        assertEquals(24, state.getEventCountForSport(SportsSport.FOOTBALL))
        assertEquals(12, state.getEventCountForSport(SportsSport.TENNIS))
        assertEquals(0, state.getEventCountForSport(SportsSport.BOXING))

        // Deportes nunca cargados devuelven 0 por defecto
        assertEquals(0, state.getEventCountForSport(SportsSport.RUGBY))
        assertEquals(0, state.getEventCountForSport(SportsSport.BASEBALL))
    }

    // 5. Categoría con 0 eventos nunca es ocultada y muestra contador 0
    @Test
    fun test05_zeroEventsCategoryShowsZeroAndNotHidden() {
        val state = SportsUiState(
            selectedSport = SportsSport.FORMULA1,
            isLoading = false,
            liveEvents = emptyList(),
            todayEvents = emptyList(),
            tomorrowEvents = emptyList(),
            categoryEventCounts = mapOf(SportsSport.FORMULA1 to 0)
        )

        val count = state.getEventCountForSport(SportsSport.FORMULA1)
        assertEquals(0, count)
        assertFalse(state.isLoading)
        assertTrue(state.isEmpty)
    }

    // 6. Robustez de fromId para mapear identificadores y endpoints de la API
    @Test
    fun test06_sportFromIdMapping() {
        assertEquals(SportsSport.FOOTBALL, SportsSport.fromId("football"))
        assertEquals(SportsSport.BASKETBALL, SportsSport.fromId("basketball"))
        assertEquals(SportsSport.TENNIS, SportsSport.fromId("tennis"))
        assertEquals(SportsSport.FORMULA1, SportsSport.fromId("formula1"))
        assertEquals(SportsSport.MOTOS, SportsSport.fromId("motos"))
        assertEquals(SportsSport.BOXING, SportsSport.fromId("boxing"))
        assertEquals(SportsSport.UFC, SportsSport.fromId("ufc"))
        assertEquals(SportsSport.UFC, SportsSport.fromId("mma"))
        assertEquals(SportsSport.UFC, SportsSport.fromId("ufc_mma"))
        assertEquals(SportsSport.UFC, SportsSport.fromId("mma/ufc"))
        assertEquals(SportsSport.RUGBY, SportsSport.fromId("rugby"))
        assertEquals(SportsSport.BASEBALL, SportsSport.fromId("baseball"))
        assertEquals(SportsSport.OTHER, SportsSport.fromId("other_sports"))
        assertEquals(SportsSport.OTHER, SportsSport.fromId("other"))
    }
}
