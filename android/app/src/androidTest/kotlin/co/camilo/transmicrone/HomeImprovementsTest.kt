package co.camilo.transmicrone

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import co.camilo.transmicrone.domain.*
import co.camilo.transmicrone.ui.*
import co.camilo.transmicrone.ui.home.HomeScreen
import co.camilo.transmicrone.ui.home.routeColor
import co.camilo.transmicrone.ui.theme.TransmicroneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class HomeImprovementsTest {
    @get:Rule val compose = createComposeRule()
    private val board = Board("1", "estacion:TM0101", PlaceKind.ESTACION, "TM0101", "León XIII", "Soacha",
        listOf(SelectedServicio("E48", "CAD", listOf(13266))))
    private val arrival = Arrival("E48", "CAD", 13266, 62282, "Entre 2 y 4 min", "200 mts", Occupancy.UNKNOWN, null, null)

    @Test fun failedCacheKeepsAgeAndOffersRetry() {
        var retries = 0
        compose.setContent { TransmicroneTheme {
            HomeScreen(listOf(BoardView(board, listOf(arrival), Freshness.FailedCached(Instant.now().minusSeconds(180), FailureKind.TIMEOUT))),
                0, {}, {}, {}, { retries++ })
        } }
        compose.onNodeWithText("se agotó el tiempo", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Mostrando datos anteriores", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Reintentar").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun scrollCollapsesAddAndLeavesNoticeClearAtLargeFont() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) { TransmicroneTheme {
                HomeScreen(listOf(BoardView(board, listOf(arrival), Freshness.Updated(Instant.now()))), 0, {}, {}, {}, {})
            } }
        }
        val notice = "Fuente no oficial. Las frases de llegada no son un reloj."
        compose.onNodeWithText(notice).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Agregar tablero").assertCountEquals(0)
        val noticeBounds = compose.onNodeWithText(notice).fetchSemanticsNode().boundsInRoot
        val addBounds = compose.onNodeWithContentDescription("Agregar tablero").fetchSemanticsNode().boundsInRoot
        assertTrue("Notice must remain above the FAB", noticeBounds.bottom <= addBounds.top)
    }

    @Test fun initialViewportClearsExpandedFabAtLargeFont() = checkInitialViewport(1.3f)

    @Test fun initialViewportClearsExpandedFabAtDoubleFont() = checkInitialViewport(2f)

    private fun checkInitialViewport(fontScale: Float) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) { TransmicroneTheme {
                HomeScreen(listOf(BoardView(board, List(8) { arrival.copy(nodo = 62282 + it) }, Freshness.Updated(Instant.now()))),
                    0, {}, {}, {}, {})
            } }
        }
        val add = compose.onNodeWithContentDescription("Agregar tablero").assertIsDisplayed()
        val fab = add.fetchSemanticsNode().boundsInRoot
        assertTrue("Initial FAB must be expanded", fab.width > fab.height * 2)
        val viewport = compose.onNodeWithTag("arrival-viewport").fetchSemanticsNode().boundsInRoot
        assertTrue("Initial list viewport must end above the expanded FAB", viewport.bottom <= fab.top)
    }

    @Test fun refreshDoesNotResizeArrivalViewport() {
        val freshness = mutableStateOf<Freshness>(Freshness.Updated(Instant.now()))
        compose.setContent { TransmicroneTheme {
            HomeScreen(listOf(BoardView(board, listOf(arrival), freshness.value)), 0, {}, {}, {}, {})
        } }
        val before = compose.onNodeWithTag("arrival-viewport").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { freshness.value = Freshness.UpdatingCached(Instant.now()) }
        val during = compose.onNodeWithTag("arrival-viewport").fetchSemanticsNode().boundsInRoot
        assertEquals(before, during)
    }

    @Test fun savedRouteColorIsUsedWhenLiveColorIsMissingOrDifferent() {
        val savedBoard = board.copy(servicios = listOf(board.servicios.single().copy(color = "#00B8D4")))
        assertEquals("#00B8D4", routeColor(BoardView(savedBoard, listOf(arrival)), "E48", arrival.routeId))
        assertEquals("#00B8D4", routeColor(BoardView(savedBoard, listOf(arrival.copy(colorRuta = "#FF0000"))), "E48", arrival.routeId))
        assertEquals("#FF0000", routeColor(BoardView(board, listOf(arrival.copy(colorRuta = "#FF0000"))), "E48", arrival.routeId))
    }

    @Test fun boardNavigationUpdatesTheVisibleBoard() {
        val page = mutableIntStateOf(0)
        val second = board.copy(boardId = "2", displayName = "Ricaurte", disambiguator = "NQS")
        compose.setContent { TransmicroneTheme {
            HomeScreen(listOf(BoardView(board, listOf(arrival)), BoardView(second, listOf(arrival))),
                page.intValue, { page.intValue = it }, {}, {}, {})
        } }
        compose.onNodeWithContentDescription("Tablero siguiente").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, page.intValue) }
        compose.onNodeWithContentDescription("Tablero anterior").performClick()
        compose.runOnIdle { assertEquals(0, page.intValue) }
    }

    @Test fun emptyHomeIsScrollableAtDoubleFont() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) { TransmicroneTheme {
                HomeScreen(emptyList(), 0, {}, {}, {}, {})
            } }
        }
        compose.onNodeWithText("Agregar tablero").performScrollTo().assertIsDisplayed()
    }

}
