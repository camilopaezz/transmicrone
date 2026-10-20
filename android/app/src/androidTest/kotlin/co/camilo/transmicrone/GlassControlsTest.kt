package co.camilo.transmicrone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import co.camilo.transmicrone.domain.*
import co.camilo.transmicrone.ui.BoardView
import co.camilo.transmicrone.ui.RouteState
import co.camilo.transmicrone.ui.common.FloatingScaffold
import co.camilo.transmicrone.ui.common.FloatingSnackbarHost
import co.camilo.transmicrone.ui.home.HomeScreen
import co.camilo.transmicrone.ui.search.CatalogDetailsScreen
import co.camilo.transmicrone.ui.search.CatalogSearchScreen
import co.camilo.transmicrone.ui.setup.SetupScreen
import co.camilo.transmicrone.ui.theme.TransmicroneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GlassControlsTest {
    @get:Rule val compose = createComposeRule()
    private val places = List(30) { i ->
        Place("estacion:$i", PlaceKind.ESTACION, "Estación $i", "estacion", null, "TM$i", emptyList())
    }
    private val service = CatalogEntry.Service(ServicioOption("E48", "Norte||Sur", listOf(1), null), emptyList())
    private val routes = listOf("Norte", "Sur").mapIndexed { direction, name ->
        ServiceRoute("$direction", List(40) { i -> RouteStop("$direction:$i", if (i == 39) name else "Parada $direction/$i") })
    }

    @Test fun undoSnackbarClearsHomeDockAndKeepsMaterialSemantics() {
        var result: SnackbarResult? = null
        val board = Board("1", "estacion:0", PlaceKind.ESTACION, "TM0", "Estación 0", null, emptyList())
        compose.setContent { TransmicroneTheme {
            val host = remember { SnackbarHostState() }
            LaunchedEffect(Unit) {
                result = host.showSnackbar("Tablero eliminado", "Deshacer", duration = SnackbarDuration.Indefinite)
            }
            HomeScreen(listOf(BoardView(board)), 0, {}, {}, {}, {}, snackbarHostState = host)
        } }
        val glass = compose.onNodeWithTag("floating-snackbar").assertIsDisplayed().getUnclippedBoundsInRoot()
        val dock = compose.onNodeWithContentDescription("Editar tablero").getUnclippedBoundsInRoot()
        assertTrue("Snackbar must leave 12 dp above the dock, including its 6 dp padding", glass.bottom <= dock.top - 18.dp)
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite), useUnmergedTree = true).assertExists()
        assertTrue("Single-line height allows pixel rounding", glass.bottom - glass.top in 52.dp..53.dp)
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss), useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Deshacer").assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(SnackbarResult.ActionPerformed, result) }
    }

    @Test fun snackbarClearsSavePanel() {
        compose.setContent { TransmicroneTheme {
            val host = remember { SnackbarHostState() }
            LaunchedEffect(Unit) { host.showSnackbar("No se pudo guardar", duration = SnackbarDuration.Indefinite) }
            SetupScreen("", emptyList(), places.first(), emptySet(), true, false,
                {}, {}, {}, {}, {}, snackbarHostState = host)
        } }
        val glass = compose.onNodeWithTag("floating-snackbar").assertIsDisplayed().getUnclippedBoundsInRoot()
        val save = compose.onNodeWithText("Guardar tablero").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("Snackbar must clear the save panel", glass.bottom <= save.top - 12.dp)
    }

    @Test fun snackbarRespectsBottomInsetAndDismisses() {
        var result: SnackbarResult? = null
        compose.setContent { TransmicroneTheme {
            val host = remember { SnackbarHostState() }
            LaunchedEffect(Unit) {
                result = host.showSnackbar("Mensaje largo que puede ocupar varias líneas", withDismissAction = true,
                    duration = SnackbarDuration.Indefinite)
            }
            FloatingScaffold(contentWindowInsets = WindowInsets(bottom = 200.dp),
                snackbarHost = { FloatingSnackbarHost(host) }) { Box(Modifier.height(600.dp)) }
        } }
        val glass = compose.onNodeWithTag("floating-snackbar").assertIsDisplayed().getUnclippedBoundsInRoot()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        assertTrue("Snackbar must clear the bottom inset", glass.bottom <= root.bottom - 212.dp)
        val dismiss = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(androidx.compose.material3.R.string.m3c_snackbar_dismiss)
        compose.onNodeWithContentDescription(dismiss).performClick()
        compose.runOnIdle { assertEquals(SnackbarResult.Dismissed, result) }
    }

    @Test fun snackbarWrapsAtDoubleFontWithoutShrinkingActions() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                TransmicroneTheme {
                    val host = remember { SnackbarHostState() }
                    LaunchedEffect(Unit) {
                        host.showSnackbar("No se pudo guardar el tablero. Inténtalo de nuevo.", "Reintentar",
                            withDismissAction = true, duration = SnackbarDuration.Indefinite)
                    }
                    FloatingSnackbarHost(host, Modifier.width(360.dp))
                }
            }
        }
        val glass = compose.onNodeWithTag("floating-snackbar").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("Wrapped text must grow the solid fallback surface", glass.bottom - glass.top > 52.dp)
        compose.onNodeWithText("Reintentar").assertHeightIsAtLeast(48.dp)
        val dismiss = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(androidx.compose.material3.R.string.m3c_snackbar_dismiss)
        compose.onNodeWithContentDescription(dismiss).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }

    @Test fun catalogSearchStaysAvailableAfterScrollingAndCanClearQuery() {
        val query = mutableStateOf("Estación")
        compose.setContent { TransmicroneTheme {
            CatalogSearchScreen(query.value, CatalogResults(places = places), false, false,
                { query.value = it }, {}, {})
        } }
        compose.onNode(hasSetTextAction()).performImeAction()
        val before = compose.onNode(hasSetTextAction()).getUnclippedBoundsInRoot()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Estación 29"))
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        assertEquals(before, compose.onNode(hasSetTextAction()).getUnclippedBoundsInRoot())
        compose.onNodeWithContentDescription("Borrar búsqueda").performClick()
        compose.runOnIdle { assertEquals("", query.value) }
    }

    @Test fun setupSearchStaysAvailableAfterScrollingResults() {
        var picked: Place? = null
        compose.setContent { TransmicroneTheme {
            SetupScreen("Estación", places, null, emptySet(), true, false,
                {}, { picked = it }, {}, {}, {})
        } }
        compose.onNode(hasSetTextAction()).performImeAction()
        val before = compose.onNode(hasSetTextAction()).getUnclippedBoundsInRoot()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Estación 29"))
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        assertEquals(before, compose.onNode(hasSetTextAction()).getUnclippedBoundsInRoot())
        compose.onNodeWithText("Estación 29").performClick()
        compose.runOnIdle { assertEquals(places.last(), picked) }
    }

    @Test fun bottomDockDirectionsStayAvailableAndSwitchRouteAfterScrolling() {
        val index = mutableIntStateOf(0)
        compose.setContent { TransmicroneTheme {
            CatalogDetailsScreen(service, {}, {}, {}, {}, RouteState.Loaded(routes),
                routeIndex = index.intValue, onSelectRoute = { index.intValue = it })
        } }
        val before = compose.onNodeWithContentDescription("Hacia Sur").getUnclippedBoundsInRoot()
        val list = compose.onNode(hasScrollAction()).getUnclippedBoundsInRoot()
        assertTrue("Directions belong in the bottom dock", before.top > (list.top + list.bottom) / 2)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Parada 0/35"))
        assertEquals(before, compose.onNodeWithContentDescription("Hacia Sur").getUnclippedBoundsInRoot())
        compose.onNodeWithContentDescription("Hacia Sur").assertIsDisplayed().performClick().assertIsSelected()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Parada 1/35"))
        compose.onNodeWithText("Parada 1/35").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToIndex(41)
        val lastStop = compose.onNodeWithText("1:39").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("The final stop must clear the floating dock", lastStop.bottom < before.top)
        compose.runOnIdle { assertEquals(1, index.intValue) }
    }

    @Test fun shortRouteAtDoubleFontKeepsDirectionsAndStopsReachable() {
        val index = mutableIntStateOf(0)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                TransmicroneTheme { Box(Modifier.height(360.dp).clipToBounds()) {
                    CatalogDetailsScreen(service, {}, {}, {}, {}, RouteState.Loaded(routes),
                        routeIndex = index.intValue, onSelectRoute = { index.intValue = it })
                } }
            }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Hacia Sur"))
        compose.onNodeWithContentDescription("Hacia Sur").assertIsDisplayed().performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Parada 1/35"))
        compose.onNodeWithText("Parada 1/35").assertIsDisplayed()
    }
}
