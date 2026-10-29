package co.camilo.transmicrone

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import co.camilo.transmicrone.domain.*
import co.camilo.transmicrone.ui.*
import co.camilo.transmicrone.ui.home.HomeScreen
import co.camilo.transmicrone.ui.search.ServiceDetailsHeader
import co.camilo.transmicrone.ui.theme.TransmicroneTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DestinationDisplayUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun arrivalCardsAndAccessibilityUseDisplayCaseWhileRowsStayRaw() {
        val arrivals = listOf("H21" to "PORTAL TUNAL", "D21" to "PORTAL 80").map { (code, name) ->
            Arrival(code, name, 1, null, "Servicio llegando", "100 m", Occupancy.UNKNOWN, null)
        }
        val board = Board("test", "estacion:TM0069", PlaceKind.ESTACION, "TM0069", "Ricaurte", "NQS",
            arrivals.map { SelectedServicio(it.painted, it.destino, listOf(1)) })
        compose.setContent { TransmicroneTheme {
            HomeScreen(listOf(BoardView(board, arrivals)), 0, {}, {}, {}, {})
        } }
        compose.onNodeWithText("Portal Tunal", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Portal 80", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("H21 a Portal Tunal", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("D21 a Portal 80", substring = true).assertIsDisplayed()
        compose.onNodeWithText("PORTAL TUNAL", useUnmergedTree = true).assertDoesNotExist()
        assertEquals("PORTAL TUNAL", arrivals.first().destino)
        assertEquals("PORTAL TUNAL", board.servicios.first().destino)
    }

    @Test fun sharedServiceDestinationsFormatSingleAndCompositeNames() {
        compose.setContent { TransmicroneTheme { Column {
            ServiceDestinationList("PORTAL TUNAL", R.string.destination_prefix)
            ServiceDestinationList("PORTAL 80 || CAD", R.string.destination_prefix)
        } } }
        compose.onNodeWithText("Portal Tunal").assertIsDisplayed()
        compose.onNodeWithText("Portal 80", substring = true).assertIsDisplayed()
        compose.onNodeWithText("CAD", substring = true).assertIsDisplayed()
    }

    @Test fun routeDetailsAndDirectionAccessibilityFormatNamesWithoutChangingSelection() {
        val option = ServicioOption("H21", "PORTAL TUNAL || CAD", listOf(1, 2), null)
        val service = CatalogEntry.Service(option, emptyList(), listOf(
            ServiceOperatingHours("a", "H21", "PORTAL TUNAL", null, emptyList()),
            ServiceOperatingHours("b", "H21", "CAD", null, emptyList()),
        ))
        val routes = listOf(
            ServiceRoute("a", listOf(RouteStop("1", "Origin"), RouteStop("2", "PORTAL TUNAL"))),
            ServiceRoute("b", listOf(RouteStop("1", "Origin"), RouteStop("3", "CAD"))),
        )
        var selected = -1
        compose.setContent { TransmicroneTheme {
            ServiceDetailsHeader(service, routes, 0, { selected = it })
        } }
        compose.onNodeWithText("Portal Tunal").assertIsDisplayed()
        compose.onNodeWithContentDescription("Hacia Portal Tunal").assertIsSelected()
        compose.onNodeWithContentDescription("Hacia CAD").performClick()
        compose.runOnIdle {
            assertEquals(1, selected)
            assertEquals("PORTAL TUNAL || CAD", service.option.destino)
            assertEquals("PORTAL TUNAL", service.operatingHours.first().destino)
        }
    }
}
