package co.camilo.transmicrone

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import co.camilo.transmicrone.domain.Place
import co.camilo.transmicrone.domain.PlaceKind
import co.camilo.transmicrone.domain.ServicioOption
import co.camilo.transmicrone.ui.key
import co.camilo.transmicrone.ui.setup.SetupScreen
import co.camilo.transmicrone.ui.theme.TransmicroneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SetupUiTest {
    @get:Rule val compose = createComposeRule()

    private val service = ServicioOption("E48", "CAD", listOf(13266), null)
    private val place = Place("estacion:TM0101", PlaceKind.ESTACION, "León XIII", "leon xiii", "Soacha", "TM0101", listOf(service))

    @Test
    fun editBackExitsAndChangeOpensPlaceSearch() {
        var changed = 0
        var exited = false
        val selected = setOf(service.key())
        compose.setContent {
            TransmicroneTheme {
                SetupScreen("leon", listOf(place), place, selected, true, false,
                    onQuery = {}, onPick = {}, onToggle = {}, onSave = {},
                    onBack = { exited = true }, isEditing = true,
                    onChangePlace = { changed++ })
            }
        }
        compose.onNodeWithText("Editar tablero").assertIsDisplayed()
        compose.onNodeWithText("Cambiar").performClick()
        compose.onNodeWithContentDescription("Atrás").performClick()
        compose.runOnIdle {
            assertEquals(1, changed)
            assertTrue(exited)
        }
        compose.onNodeWithText("1 servicio seleccionado").assertIsDisplayed()
        compose.onNodeWithText("Guardar tablero").assertIsEnabled()
    }

    @Test
    fun serviceIsOneAccessibleToggleAndDisabledSaveExplainsSelection() {
        val selected = mutableStateOf(emptySet<String>())
        compose.setContent {
            TransmicroneTheme {
                SetupScreen("", emptyList(), place, selected.value, true, false,
                    onQuery = {}, onPick = {}, onToggle = { selected.value = setOf(it) },
                    onSave = {}, onBack = {})
            }
        }
        compose.onNodeWithText("Elige al menos un servicio.").assertIsDisplayed()
        compose.onNodeWithText("Guardar tablero").assertIsNotEnabled()
        compose.onAllNodes(isToggleable(), useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText("CAD").performClick()
        compose.onNodeWithText("1 servicio seleccionado").assertIsDisplayed()
        compose.onNodeWithText("Guardar tablero").assertIsEnabled()
    }

    @Test
    fun editingShowsInitialSelectionsFirstWithoutReorderingOnToggle() {
        val laterService = ServicioOption("K43", "Portal Norte", listOf(13267), null)
        val editingPlace = place.copy(servicios = listOf(service, laterService))
        val selected = mutableStateOf(setOf(laterService.key()))
        compose.setContent {
            TransmicroneTheme {
                SetupScreen("", emptyList(), editingPlace, selected.value, true, false,
                    onQuery = {}, onPick = {},
                    onToggle = { key -> selected.value = if (key in selected.value) selected.value - key else selected.value + key },
                    onSave = {}, onBack = {}, isEditing = true)
            }
        }
        fun assertSelectedServiceStayedFirst() {
            val first = compose.onNodeWithText("Portal Norte").fetchSemanticsNode().boundsInRoot.top
            val second = compose.onNodeWithText("CAD").fetchSemanticsNode().boundsInRoot.top
            assertTrue("The initially selected service should stay before CAD", first < second)
        }
        assertSelectedServiceStayedFirst()
        compose.onNodeWithText("Portal Norte").performClick()
        assertSelectedServiceStayedFirst()
        compose.onNodeWithText("CAD").performClick()
        assertSelectedServiceStayedFirst()
    }

    @Test
    fun stopsSharingNameAndAddressShowDistinctCodes() {
        val first = place.copy(placeId = "paradero:167B02", kind = PlaceKind.PARADERO,
            name = "Uniagraria", disambiguator = "AC 170 - KR 54A", queryParadero = "167B02")
        val second = first.copy(placeId = "paradero:167A02", queryParadero = "167A02")
        var picked: Place? = null
        compose.setContent {
            TransmicroneTheme {
                SetupScreen("uniagraria", listOf(first, second), null, emptySet(), true, false,
                    onQuery = {}, onPick = { picked = it }, onToggle = {}, onSave = {}, onBack = {})
            }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText("Código 167B02"))
        compose.onNodeWithText("Código 167B02").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText("Código 167A02"))
        compose.onNodeWithText("Código 167A02").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(second, picked) }
    }

    @Test
    fun searchResultsRemainReachableAtDoubleFontScale() {
        var picked: Place? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                TransmicroneTheme {
                    SetupScreen("leon", listOf(place), null, emptySet(), true, false,
                        onQuery = {}, onPick = { picked = it }, onToggle = {}, onSave = {}, onBack = {})
                }
            }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText("León XIII"))
        compose.onNodeWithText("León XIII").performClick()
        compose.runOnIdle { assertEquals(place, picked) }
    }
}
