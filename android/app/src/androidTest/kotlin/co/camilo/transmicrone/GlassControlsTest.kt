package co.camilo.transmicrone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import co.camilo.transmicrone.domain.*
import co.camilo.transmicrone.ui.RouteState
import co.camilo.transmicrone.ui.search.CatalogDetailsScreen
import co.camilo.transmicrone.ui.search.CatalogSearchScreen
import co.camilo.transmicrone.ui.setup.SetupScreen
import co.camilo.transmicrone.ui.theme.TransmicroneTheme
import org.junit.Assert.assertEquals
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

    @Test fun pinnedDirectionsStayAvailableAndSwitchRouteAfterScrolling() {
        val index = mutableIntStateOf(0)
        compose.setContent { TransmicroneTheme {
            CatalogDetailsScreen(service, {}, {}, {}, {}, RouteState.Loaded(routes),
                routeIndex = index.intValue, onSelectRoute = { index.intValue = it })
        } }
        val before = compose.onNodeWithContentDescription("Hacia Sur").getUnclippedBoundsInRoot()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Parada 0/35"))
        assertEquals(before, compose.onNodeWithContentDescription("Hacia Sur").getUnclippedBoundsInRoot())
        compose.onNodeWithContentDescription("Hacia Sur").assertIsDisplayed().performClick().assertIsSelected()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Parada 1/35"))
        compose.onNodeWithText("Parada 1/35").assertIsDisplayed()
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
