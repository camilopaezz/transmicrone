package co.camilo.transmicrone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import co.camilo.transmicrone.ui.search.ServiceDetailsHeader
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import co.camilo.transmicrone.domain.CatalogEntry
import co.camilo.transmicrone.domain.CatalogResults
import co.camilo.transmicrone.domain.Place
import co.camilo.transmicrone.domain.PlaceKind
import co.camilo.transmicrone.domain.ServicioOption
import co.camilo.transmicrone.domain.ServiceOperatingHours
import co.camilo.transmicrone.domain.OperatingWindow
import co.camilo.transmicrone.domain.ServiceRoute
import co.camilo.transmicrone.domain.RouteStop
import co.camilo.transmicrone.ui.RouteState
import co.camilo.transmicrone.ui.ServiceLookupState
import androidx.compose.runtime.mutableStateOf
import co.camilo.transmicrone.ui.search.CatalogDetailsScreen
import co.camilo.transmicrone.ui.search.CatalogSearchScreen
import co.camilo.transmicrone.ui.setup.SetupScreen
import co.camilo.transmicrone.ui.theme.TransmicroneTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CatalogUiTest {
    @get:Rule val compose = createComposeRule()

    private val option = ServicioOption("E48", "CAD||Portal Norte", listOf(1), null)
    private val place = Place("estacion:TM0101", PlaceKind.ESTACION, "León XIII", "leon xiii", "Soacha", "TM0101", listOf(option))

    @Test
    fun wrappedDestinationSegmentsShareTheirTopAndBottomEdges() {
        compose.setContent {
            TransmicroneTheme {
                Box(Modifier.width(280.dp)) {
                    ServiceDetailsHeader(CatalogEntry.Service(option, emptyList()), listOf(
                        ServiceRoute("a", listOf(RouteStop("1", "Origin"), RouteStop("2", "Santo Domingo"))),
                        ServiceRoute("b", listOf(RouteStop("3", "Origin"), RouteStop("4", "Sur"))),
                    ), 0, {})
                }
            }
        }
        val first = compose.onNodeWithContentDescription("Hacia Santo Domingo").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithContentDescription("Hacia Sur").fetchSemanticsNode().boundsInRoot
        assertEquals(first.top, second.top, 1f)
        assertEquals(first.bottom, second.bottom, 1f)
    }

    @Test
    fun publishedHoursRemainVisibleWhenNetworkRouteFails() {
        val service = CatalogEntry.Service(option, listOf(place), listOf(
            ServiceOperatingHours("12444", "E48", "CAD", "2026-10-13T01:00:00Z",
                listOf(OperatingWindow("L-V", "04:00", "10:00"), OperatingWindow("S", "04:30", "10:00"))),
        ))
        compose.setContent {
            TransmicroneTheme {
                CatalogDetailsScreen(service, onBack = {}, onPlace = {}, onService = {}, onAdd = {},
                    routeState = RouteState.Failed)
            }
        }
        compose.onNodeWithText("Horario de operación").assertDoesNotExist()
        compose.onNodeWithText("Consultado el", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Horario publicado del servicio.", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Lunes a viernes").assertIsDisplayed()
        compose.onNodeWithText("04:00 – 10:00").assertIsDisplayed()
        compose.onNodeWithText("Sábados").assertIsDisplayed()
        compose.onNodeWithText("04:30 – 10:00").assertIsDisplayed()
    }

    @Test
    fun selectedRouteShowsOnlyItsOwnPublishedHours() {
        val service = CatalogEntry.Service(option, listOf(place), listOf(
            ServiceOperatingHours("a", "E48", "CAD", null, listOf(OperatingWindow("L-V", "04:00", "10:00"))),
            ServiceOperatingHours("b", "E48", "Portal Norte", null, listOf(OperatingWindow("D-F", "05:00", "22:00"))),
        ))
        compose.setContent {
            TransmicroneTheme {
                CatalogDetailsScreen(service, onBack = {}, onPlace = {}, onService = {}, onAdd = {},
                    routeState = RouteState.Loaded(listOf(
                        ServiceRoute("a", listOf(RouteStop("1", "Origin"), RouteStop("2", "CAD"))),
                        ServiceRoute("b", listOf(RouteStop("1", "Origin"), RouteStop("3", "Portal Norte"))),
                    )), routeIndex = 1)
            }
        }
        compose.onNodeWithText("Domingos y festivos").assertIsDisplayed()
        compose.onNodeWithText("05:00 – 22:00").assertIsDisplayed()
        compose.onNodeWithText("04:00 – 10:00").assertDoesNotExist()
    }

    @Test
    fun destinationSegmentsSwitchTheHeaderHoursAndRouteWithoutCheckmarks() {
        val index = mutableStateOf(0)
        val service = CatalogEntry.Service(option, listOf(place), listOf(
            ServiceOperatingHours("a", "E48", "CAD", null, listOf(OperatingWindow("L-V", "04:00", "10:00"))),
            ServiceOperatingHours("b", "E48", "Portal Norte", null, listOf(OperatingWindow("D-F", "05:00", "22:00"))),
        ))
        compose.setContent {
            TransmicroneTheme {
                CatalogDetailsScreen(service, onBack = {}, onPlace = {}, onService = {}, onAdd = {},
                    routeState = RouteState.Loaded(listOf(
                        ServiceRoute("a", listOf(RouteStop("1", "South origin"), RouteStop("2", "CAD terminal stop"))),
                        ServiceRoute("b", listOf(RouteStop("3", "North origin"), RouteStop("4", "Northern terminal stop"))),
                    )), routeIndex = index.value, onSelectRoute = { index.value = it })
            }
        }
        compose.onNodeWithContentDescription("Hacia CAD").assertIsSelected()
        compose.onNodeWithContentDescription("Hacia Portal Norte").assertIsNotSelected().performClick()
        compose.onNodeWithContentDescription("Hacia Portal Norte").assertIsSelected()
        compose.onNodeWithContentDescription("Hacia Portal Norte")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .performClick().assertIsSelected()
        compose.onNodeWithText("Desde North origin").assertIsDisplayed()
        compose.onNodeWithText("05:00 – 22:00").assertIsDisplayed()
        compose.onNodeWithText("04:00 – 10:00").assertDoesNotExist()
        compose.onNodeWithText("✓", substring = true).assertDoesNotExist()
    }

    @Test
    fun detailsServiceTapDisplaysFailureAndRetriesTheTappedService() {
        val entry = CatalogEntry.Location(place)
        val lookup = mutableStateOf<ServiceLookupState?>(null)
        var retried: ServicioOption? = null
        compose.setContent {
            TransmicroneTheme {
                CatalogDetailsScreen(entry, onBack = {}, onPlace = {}, onAdd = {},
                    onService = { lookup.value = ServiceLookupState(it, listOf(entry), failed = true) },
                    serviceLookup = lookup.value,
                    onRetryService = {
                        retried = lookup.value!!.option
                        lookup.value = lookup.value!!.copy(failed = false)
                    })
            }
        }
        compose.onNodeWithText("E48").performClick()
        compose.onNodeWithText("No se pudo abrir el servicio E48.").assertIsDisplayed()
        compose.onNodeWithText("Reintentar").performClick()
        compose.runOnIdle { assertEquals(option, retried) }
        compose.onNodeWithText("No se pudo abrir el servicio E48.").assertDoesNotExist()
        compose.onNodeWithText("Abriendo servicio E48…").assertIsDisplayed()
        compose.onNodeWithText("León XIII").assertIsDisplayed()
    }

    @Test
    fun setupSearchFailureCanRetry() {
        var searches = 0
        compose.setContent {
            TransmicroneTheme {
                SetupScreen("leon", emptyList(), null, emptySet(), true, false,
                    onQuery = { searches++ }, onPick = {}, onToggle = {}, onSave = {}, onBack = {},
                    searchFailed = true)
            }
        }
        compose.onNodeWithText("No se pudo buscar en el catálogo.").assertIsDisplayed()
        compose.onNodeWithText("Reintentar").performClick()
        compose.runOnIdle { assertEquals(1, searches) }
    }

    @Test
    fun saveFailureKeepsEditorAvailable() {
        compose.setContent {
            TransmicroneTheme {
                SetupScreen("", emptyList(), place, setOf(option.painted + "\u0001" + option.destino), true, false,
                    onQuery = {}, onPick = {}, onToggle = {}, onSave = {}, onBack = {}, saveFailed = true)
            }
        }
        compose.onNodeWithText("No se pudo guardar el tablero. Inténtalo de nuevo.").assertIsDisplayed()
        compose.onNodeWithText("Guardar tablero").assertIsEnabled()
        compose.onNodeWithText("Hacia CAD").assertIsDisplayed()
    }

    @Test
    fun pairedCatalogDestinationsHaveLabelsWithoutRawDelimiter() {
        val service = CatalogEntry.Service(option, listOf(place))
        compose.setContent {
            TransmicroneTheme {
                CatalogSearchScreen("E48", CatalogResults(services = listOf(service)), false, false,
                    onQuery = {}, onDetails = {}, onBack = {})
            }
        }
        compose.onNodeWithText("Hacia CAD").assertIsDisplayed()
        compose.onNodeWithText("Hacia Portal Norte").assertIsDisplayed()
        compose.onNodeWithText("CAD||Portal Norte").assertDoesNotExist()
    }

    @Test
    fun failedRouteDoesNotShowTheRemovedServedPlacesSection() {
        val service = CatalogEntry.Service(option, listOf(place))
        compose.setContent {
            TransmicroneTheme {
                CatalogDetailsScreen(service, onBack = {}, onPlace = {}, onService = {}, onAdd = {},
                    routeState = RouteState.Failed)
            }
        }
        compose.onNodeWithText("Lugares atendidos por el servicio").assertDoesNotExist()
        compose.onNodeWithText("León XIII").assertDoesNotExist()
        compose.onNodeWithText("No se pudo leer el recorrido del catálogo. Intenta de nuevo.").assertIsDisplayed()
        compose.onNodeWithText("CAD||Portal Norte").assertDoesNotExist()
    }
}
