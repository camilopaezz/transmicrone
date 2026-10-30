package co.camilo.transmicrone

import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import co.camilo.transmicrone.domain.Board
import co.camilo.transmicrone.domain.CatalogEntry
import co.camilo.transmicrone.domain.CatalogResults
import co.camilo.transmicrone.domain.Place
import co.camilo.transmicrone.domain.PlaceKind
import co.camilo.transmicrone.domain.ServicioOption
import co.camilo.transmicrone.ui.BoardView
import co.camilo.transmicrone.ui.Screen
import co.camilo.transmicrone.ui.UiState
import co.camilo.transmicrone.ui.common.ScreenTransition
import co.camilo.transmicrone.ui.home.HomeScreen
import co.camilo.transmicrone.ui.search.CatalogSearchScreen
import co.camilo.transmicrone.ui.theme.TransmicroneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ScreenTransitionUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun returningFromServicePreservesSearchScrollAndDoesNotRefocusSearch() =
        assertSearchScrollRestored(rapidReopen = false)

    @Test
    fun reopeningSearchDuringExitStillPreservesScrollAfterDetails() =
        assertSearchScrollRestored(rapidReopen = true)

    private fun assertSearchScrollRestored(rapidReopen: Boolean) {
        val services = (0..60).map {
            CatalogEntry.Service(ServicioOption("S$it", "Destino $it", listOf(it), null), emptyList())
        }
        val state = mutableStateOf(UiState(screen = Screen.SEARCH, catalogQuery = "S",
            catalogResults = CatalogResults(services = services)))
        compose.setContent {
            TransmicroneTheme {
                ScreenTransition(state.value) { displayed ->
                    when (displayed.screen) {
                        Screen.SEARCH -> CatalogSearchScreen(displayed.catalogQuery, displayed.catalogResults,
                            false, false, {}, { entry ->
                                state.value = state.value.copy(screen = Screen.DETAILS, details = listOf(entry))
                            }, {})
                        else -> Text("Volver", androidx.compose.ui.Modifier.clickable {
                            state.value = state.value.copy(screen = Screen.SEARCH, details = emptyList())
                        })
                    }
                }
            }
        }
        if (rapidReopen) {
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { state.value = state.value.copy(screen = Screen.HOME) }
            compose.mainClock.advanceTimeBy(64)
            compose.runOnIdle {
                state.value = state.value.copy(screen = Screen.SEARCH,
                    catalogSearchSession = state.value.catalogSearchSession + 1)
            }
            compose.mainClock.advanceTimeBy(400)
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
        }
        compose.onNode(hasScrollAction()).performScrollToIndex(21)
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 37f) }
        val scrollBefore = compose.onNode(hasScrollAction()).fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        val rowBefore = compose.onNodeWithText("S21").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("S21").performClick()
        compose.onNodeWithText("Volver").assertIsDisplayed()
        compose.onNodeWithText("Volver").performClick()
        compose.waitForIdle()
        val scrollAfter = compose.onNode(hasScrollAction()).fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(scrollBefore, scrollAfter, 0.000001f)
        assertEquals(rowBefore, compose.onNodeWithText("S21").fetchSemanticsNode().boundsInRoot)
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
    }

    @Test
    fun backKeepsDepartingDetailsSnapshotUntilTransitionEnds() {
        val entry = CatalogEntry.Location(Place("station", PlaceKind.ESTACION, "Estación", "estacion", null, "TM0002", emptyList()))
        val state = mutableStateOf(UiState(screen = Screen.DETAILS, details = listOf(entry)))
        val composed = mutableMapOf<Screen, UiState>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            TransmicroneTheme {
                ScreenTransition(state.value) { displayed ->
                    DisposableEffect(Unit) { onDispose { composed.remove(displayed.screen) } }
                    SideEffect { composed[displayed.screen] = displayed }
                    Text(displayed.screen.name)
                }
            }
        }
        compose.runOnIdle { state.value = UiState(screen = Screen.SEARCH) }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle {
            assertEquals(listOf(entry), composed[Screen.DETAILS]?.details)
            assertTrue(composed.containsKey(Screen.SEARCH))
        }
        compose.mainClock.advanceTimeBy(320)
        compose.runOnIdle { assertEquals(setOf(Screen.SEARCH), composed.keys) }
    }

    @Test
    fun typingAndLoadingUpdatesKeepTheCurrentViewMounted() {
        val state = mutableStateOf(UiState(screen = Screen.SEARCH))
        var mounts = 0
        var latestQuery = ""
        compose.setContent {
            TransmicroneTheme {
                ScreenTransition(state.value) { displayed ->
                    DisposableEffect(Unit) { mounts++; onDispose {} }
                    SideEffect { latestQuery = displayed.catalogQuery }
                    Text(displayed.catalogQuery)
                }
            }
        }
        compose.runOnIdle { state.value = state.value.copy(catalogQuery = "B23", catalogSearching = true) }
        compose.runOnIdle {
            assertEquals(1, mounts)
            assertEquals("B23", latestQuery)
        }
    }

    @Test
    fun rapidBackDuringEnterSettlesOnHome() {
        val state = mutableStateOf(UiState())
        val composed = mutableSetOf<Screen>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            TransmicroneTheme {
                ScreenTransition(state.value) { displayed ->
                    DisposableEffect(Unit) {
                        composed.add(displayed.screen)
                        onDispose { composed.remove(displayed.screen) }
                    }
                    Text(displayed.screen.name)
                }
            }
        }
        compose.runOnIdle { state.value = UiState(screen = Screen.SETTINGS) }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { state.value = UiState() }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle { assertEquals(setOf(Screen.HOME), composed) }
    }

    @Test
    fun searchRequestsFocusAfterItsEntranceFinishes() {
        val state = mutableStateOf(UiState())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            TransmicroneTheme {
                ScreenTransition(state.value) { displayed ->
                    if (displayed.screen == Screen.SEARCH) {
                        CatalogSearchScreen("", CatalogResults(), false, false, {}, {}, {})
                    } else Text("Home")
                }
            }
        }
        compose.runOnIdle { state.value = UiState(screen = Screen.SEARCH) }
        compose.mainClock.advanceTimeBy(64)
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        compose.mainClock.advanceTimeBy(400)
        compose.onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test
    fun homeSearchMorphsIntoCatalogSearchAndBack() {
        val state = mutableStateOf(UiState())
        val board = Board("1", "estacion:0", PlaceKind.ESTACION, "TM0", "Estación 0", null, emptyList())
        compose.setContent {
            TransmicroneTheme {
                ScreenTransition(state.value) { displayed ->
                    when (displayed.screen) {
                        Screen.HOME -> HomeScreen(
                            boards = listOf(BoardView(board)),
                            pageIndex = 0, onPage = {}, onAdd = {}, onEdit = {}, onRefresh = {},
                            onSearch = { state.value = UiState(screen = Screen.SEARCH) },
                        )
                        Screen.SEARCH -> CatalogSearchScreen(
                            "", CatalogResults(), false, false, {}, {},
                            { state.value = UiState() },
                        )
                        else -> Text(displayed.screen.name)
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Buscar").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertIsDisplayed().assertIsFocused()
        compose.onAllNodesWithContentDescription("Buscar").assertCountEquals(0)
        compose.onNodeWithContentDescription("Atrás").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Buscar").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Buscar").assertCountEquals(1)
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithContentDescription("Buscar").performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertIsFocused()
    }
}
