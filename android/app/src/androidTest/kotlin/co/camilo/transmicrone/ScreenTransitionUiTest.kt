package co.camilo.transmicrone

import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import co.camilo.transmicrone.domain.CatalogEntry
import co.camilo.transmicrone.domain.CatalogResults
import co.camilo.transmicrone.domain.Place
import co.camilo.transmicrone.domain.PlaceKind
import co.camilo.transmicrone.ui.Screen
import co.camilo.transmicrone.ui.UiState
import co.camilo.transmicrone.ui.common.ScreenTransition
import co.camilo.transmicrone.ui.search.CatalogSearchScreen
import co.camilo.transmicrone.ui.theme.TransmicroneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ScreenTransitionUiTest {
    @get:Rule val compose = createComposeRule()

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
}
