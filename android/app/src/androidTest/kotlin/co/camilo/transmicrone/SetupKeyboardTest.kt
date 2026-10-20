package co.camilo.transmicrone

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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

class SetupKeyboardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun editingSearchShowsResultsAboveOpenKeyboard() {
        compose.activityRule.scenario.onActivity {
            it.enableEdgeToEdge()
            it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        val place = Place("estacion:TM0101", PlaceKind.ESTACION, "León XIII", "leon xiii", "Soacha", "TM0101", emptyList())
        compose.setContent {
            TransmicroneTheme {
                SetupScreen("leon xii", listOf(place), null, emptySet(), true, false,
                    onQuery = {}, onPick = {}, onToggle = {}, onSave = {}, onBack = {},
                    isEditing = true, editingBoardName = "León XIII")
            }
        }
        compose.onNodeWithText("leon xii").performClick()
        compose.waitUntil(5_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        compose.onNodeWithText("leon xii").assertIsDisplayed()
        compose.onNodeWithText("León XIII").assertIsDisplayed()
    }

    @Test
    fun editingServicesKeepsNameAndActionsReachableWithKeyboard() {
        compose.activityRule.scenario.onActivity {
            it.enableEdgeToEdge()
            it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        val service = ServicioOption("E48", "CAD", listOf(13266), null)
        val place = Place("estacion:TM0101", PlaceKind.ESTACION, "León XIII", "leon", "Soacha", "TM0101", listOf(service))
        val showSnackbar = mutableStateOf(false)
        compose.setContent { TransmicroneTheme {
            val host = remember { SnackbarHostState() }
            LaunchedEffect(showSnackbar.value) {
                if (showSnackbar.value) host.showSnackbar("No se pudo guardar", duration = SnackbarDuration.Indefinite)
            }
            SetupScreen("", emptyList(), place, setOf(service.key()), true, false,
                {}, {}, {}, {}, {}, isEditing = true, snackbarHostState = host)
        } }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Nombre del tablero (opcional)"))
        compose.onNodeWithText("Nombre del tablero (opcional)").performClick()
        compose.waitUntil(5_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        compose.onNodeWithText("Nombre del tablero (opcional)").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Guardar tablero"))
        compose.onNodeWithText("Guardar tablero").assertIsDisplayed()
        compose.onNodeWithText("Eliminar tablero").performScrollTo().assertIsDisplayed()
        val saveBefore = compose.onNodeWithText("Guardar tablero").getUnclippedBoundsInRoot()
        compose.runOnIdle { showSnackbar.value = true }
        val snackbar = compose.onNodeWithTag("floating-snackbar").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertEquals(saveBefore, compose.onNodeWithText("Guardar tablero").getUnclippedBoundsInRoot())
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val ime = ViewCompat.getRootWindowInsets(view)!!.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val keyboardTop = with(compose.density) { (view.height - ime).toDp() }
            assertTrue("Snackbar clears the keyboard with 12 dp spacing", snackbar.bottom <= keyboardTop - 12.dp)
        }
    }

}
