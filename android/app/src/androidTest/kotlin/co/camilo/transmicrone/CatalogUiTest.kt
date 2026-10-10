package co.camilo.transmicrone

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import co.camilo.transmicrone.domain.CatalogEntry
import co.camilo.transmicrone.domain.CatalogResults
import co.camilo.transmicrone.domain.Place
import co.camilo.transmicrone.domain.PlaceKind
import co.camilo.transmicrone.domain.ServicioOption
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
    fun failedRouteShowsCatalogServedPlacesAndOpensPlaceDetails() {
        val service = CatalogEntry.Service(option, listOf(place))
        var opened: Place? = null
        compose.setContent {
            TransmicroneTheme {
                CatalogDetailsScreen(service, onBack = {}, onPlace = { opened = it }, onService = {}, onAdd = {},
                    routeState = RouteState.Failed)
            }
        }
        compose.onNodeWithText("Lugares atendidos por el servicio").assertIsDisplayed()
        compose.onNodeWithText("León XIII").performClick()
        compose.runOnIdle { assertEquals(place, opened) }
        compose.onNodeWithText("CAD||Portal Norte").assertDoesNotExist()
    }
}
