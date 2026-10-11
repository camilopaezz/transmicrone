package co.camilo.transmicrone

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import co.camilo.transmicrone.data.local.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import co.camilo.transmicrone.data.local.AppSettings
import co.camilo.transmicrone.data.local.ThemeMode
import co.camilo.transmicrone.domain.*
import co.camilo.transmicrone.ui.BoardView
import co.camilo.transmicrone.ui.home.HomeScreen
import co.camilo.transmicrone.ui.settings.SettingsScreen
import co.camilo.transmicrone.ui.theme.TransmicroneTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private fun board(id: String) = BoardView(Board(id, "estacion:TM0101", PlaceKind.ESTACION,
        "TM0101", id, "Soacha", listOf(SelectedServicio("E48", "CAD", listOf(13266)))))

    @Test fun appearancePreferencesSurviveStoreRecreation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsStore(context)
        val original = store.settings.first()
        try {
            store.setTheme(ThemeMode.DARK)
            store.setDynamicColor(false)
            assertEquals(AppSettings(ThemeMode.DARK, false), SettingsStore(context).settings.first())
        } finally {
            store.setTheme(original.theme)
            store.setDynamicColor(original.dynamicColor)
        }
    }

    @Test fun gearOpensSettingsOnEmptyHome() {
        var opened = false
        compose.setContent { TransmicroneTheme {
            HomeScreen(emptyList(), 0, {}, {}, {}, {}, onSettings = { opened = true })
        } }
        compose.onNodeWithContentDescription("Ajustes").performClick()
        compose.runOnIdle { assertEquals(true, opened) }
    }

    @Test fun themeAndOrderingControlsWorkAndBoundaryMovesAreDisabled() {
        var moved: Pair<String, Int>? = null
        var theme = ThemeMode.SYSTEM
        compose.setContent {
            var settings by remember { mutableStateOf(AppSettings()) }
            TransmicroneTheme {
                SettingsScreen(settings, listOf(board("A"), board("B")), false,
                    onTheme = { theme = it; settings = settings.copy(theme = it) },
                    onDynamicColor = { settings = settings.copy(dynamicColor = it) },
                    onMove = { id, offset -> moved = id to offset }, onBack = {},
                    snackbarHostState = remember { SnackbarHostState() })
            }
        }
        compose.onNodeWithText("Oscuro").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(ThemeMode.DARK, theme) }
        compose.onNodeWithContentDescription("Subir A").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Bajar A").performClick()
        compose.runOnIdle { assertEquals("A" to 1, moved) }
        compose.onNodeWithContentDescription("Bajar B").performScrollTo().assertIsNotEnabled()
    }
}
