package co.camilo.transmicrone

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
    private val arrival = Arrival("E48", "CAD", 13266, 62282, "Entre 2 y 4 min", "200 mts", Occupancy.UNKNOWN, null)

    @Test fun homeOmitsRefreshStatusAndRetry() {
        compose.setContent { TransmicroneTheme {
            HomeScreen(listOf(BoardView(board, listOf(arrival), Freshness.FailedCached(Instant.now().minusSeconds(180), FailureKind.TIMEOUT))),
                0, {}, {}, {}, {})
        } }
        compose.onAllNodesWithText("se agotó el tiempo", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Mostrando datos anteriores", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Actualizado", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Reintentar").assertCountEquals(0)
    }

    @Test fun singleBoardShowsOneTitleAndToolbarAdd() {
        var adds = 0
        var edits = 0
        compose.setContent { TransmicroneTheme {
            HomeScreen(listOf(BoardView(board, listOf(arrival))), 0, {}, { adds++ }, { edits++ }, {})
        } }
        compose.onAllNodesWithText("León XIII").assertCountEquals(1)
        compose.onNodeWithContentDescription("Agregar tablero").assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performClick()
        compose.onNodeWithContentDescription("Editar tablero")
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Tableros").assertDoesNotExist()
        compose.onNodeWithTag("board-page-dots").assertDoesNotExist()
        compose.onNodeWithText("· desliza").assertDoesNotExist()
        compose.onNodeWithContentDescription("Tablero siguiente").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, adds); assertEquals(1, edits) }
    }

    @Test fun arrivalsAndNoticeUseFullViewportAtDoubleFont() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) { TransmicroneTheme {
                HomeScreen(listOf(BoardView(board, listOf(arrival), Freshness.Updated(Instant.now()))), 0, {}, {}, {}, {})
            } }
        }
        compose.onNodeWithText("Fuente no oficial. Las frases de llegada no son un reloj.").performScrollTo().assertIsDisplayed()
        val viewport = compose.onNodeWithTag("arrival-viewport").fetchSemanticsNode().boundsInRoot
        val add = compose.onNodeWithContentDescription("Agregar tablero").fetchSemanticsNode().boundsInRoot
        assertTrue("Add chip should precede the arrivals", add.bottom <= viewport.top)
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

    @Test fun firstLoadShowsSkeletonsAndKeepsStripSpaceAfterLoading() {
        val view = mutableStateOf(BoardView(board, freshness = Freshness.Loading))
        compose.setContent { TransmicroneTheme {
            HomeScreen(listOf(view.value), 0, {}, {}, {}, {})
        } }
        compose.onNodeWithText("Actualizando…").assertIsDisplayed()
        compose.onAllNodesWithTag("arrival-skeleton").assertCountEquals(3)
        val loadingViewport = compose.onNodeWithTag("arrival-viewport").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { view.value = BoardView(board, listOf(arrival), Freshness.Updated(Instant.now())) }
        compose.onAllNodesWithTag("arrival-skeleton").assertCountEquals(0)
        val loadedViewport = compose.onNodeWithTag("arrival-viewport").fetchSemanticsNode().boundsInRoot
        assertEquals(loadingViewport, loadedViewport)
        compose.runOnIdle { view.value = BoardView(board, freshness = Freshness.Empty(Instant.now())) }
        compose.onAllNodesWithTag("arrival-skeleton").assertCountEquals(0)
        assertEquals(loadingViewport, compose.onNodeWithTag("arrival-viewport").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun cachedEmptyRefreshDoesNotShowSkeletons() {
        compose.setContent { TransmicroneTheme {
            HomeScreen(listOf(BoardView(board, freshness = Freshness.UpdatingCached(Instant.now(), quiet = true),
                cache = CachedArrivals(emptyList(), Instant.now()))), 0, {}, {}, {}, {})
        } }
        compose.onAllNodesWithTag("arrival-skeleton").assertCountEquals(0)
        compose.onAllNodesWithText("Actualizando…").assertCountEquals(0)
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
        compose.onNodeWithTag("board-page-selector").assertIsDisplayed()
        compose.onNodeWithText("· desliza").assertIsDisplayed()
        compose.onNodeWithTag("arrival-viewport").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, page.intValue) }
        compose.onNodeWithText("· desliza").assertDoesNotExist()
        compose.onNodeWithTag("board-title-selector").performClick()
        compose.onNode(isSelectable() and hasText("León XIII")).performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, page.intValue) }
        compose.onNodeWithContentDescription("Tablero anterior").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Tablero siguiente").assertIsEnabled()
        compose.onNodeWithText("1/2").assertDoesNotExist()
    }

    @Test fun titleAndCounterFollowExternalDistantSelection() {
        val page = mutableIntStateOf(0)
        val boards = List(12) { i -> BoardView(board.copy(boardId = "$i", displayName = "Estación $i"), listOf(arrival)) }
        compose.setContent { TransmicroneTheme {
            HomeScreen(boards, page.intValue, { page.intValue = it }, {}, {}, {})
        } }
        compose.runOnIdle { page.intValue = 11 }
        compose.waitForIdle()
        compose.onNodeWithText("Estación 11").assertIsDisplayed()
        compose.onNodeWithText("12 de 12").assertIsDisplayed()
    }

    @Test fun hintDismissalDoesNotMoveArrivalsAndSurvivesNavigationAndRestoration() {
        val restoration = StateRestorationTester(compose)
        val showHome = mutableStateOf(true)
        val boards = listOf(BoardView(board, listOf(arrival)), BoardView(board.copy(boardId = "2"), listOf(arrival)))
        restoration.setContent {
            val dismissal = rememberSaveable { mutableStateOf(false) }
            TransmicroneTheme {
                if (showHome.value) HomeScreen(boards, 0, {}, {}, {}, {}, swipeHintDismissal = dismissal)
            }
        }
        val before = visibleArrivalBounds()
        compose.mainClock.advanceTimeBy(6000)
        compose.onNodeWithText("· desliza").assertDoesNotExist()
        val after = visibleArrivalBounds()
        assertEquals(before, after)
        compose.runOnIdle { showHome.value = false }
        compose.runOnIdle { showHome.value = true }
        compose.onNodeWithText("· desliza").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("· desliza").assertDoesNotExist()
    }

    @Test fun pencilEditsVisibleBoardAfterSwipe() {
        var edited: String? = null
        var settled = 0
        val second = board.copy(boardId = "2", displayName = "Ricaurte")
        compose.setContent { TransmicroneTheme {
            HomeScreen(listOf(BoardView(board, listOf(arrival)), BoardView(second, listOf(arrival))),
                0, { settled = it }, {}, { edited = it }, {})
        } }
        compose.onNodeWithTag("arrival-viewport").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Editar tablero").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("2", edited); assertEquals(1, settled) }
    }

    private fun visibleArrivalBounds() =
        compose.onAllNodesWithText("Entre 2 y 4 min", useUnmergedTree = true)
            .fetchSemanticsNodes().minBy { it.boundsInRoot.left }.boundsInRoot

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
