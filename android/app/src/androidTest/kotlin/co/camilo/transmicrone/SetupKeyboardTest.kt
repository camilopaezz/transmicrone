package co.camilo.transmicrone

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import co.camilo.transmicrone.domain.Place
import co.camilo.transmicrone.domain.PlaceKind
import co.camilo.transmicrone.ui.setup.SetupScreen
import co.camilo.transmicrone.ui.theme.TransmicroneTheme
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
}
