package co.camilo.transmicrone

import androidx.compose.runtime.*
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarDuration
import kotlinx.coroutines.launch
import co.camilo.transmicrone.ui.BoardView
import co.camilo.transmicrone.ui.home.HomeScreen
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import co.camilo.transmicrone.domain.*
import co.camilo.transmicrone.ui.key
import co.camilo.transmicrone.ui.setup.SetupScreen
import co.camilo.transmicrone.ui.theme.TransmicroneTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ManagementUiTest {
    @get:Rule val compose = createComposeRule()
    private val service = ServicioOption("E48", "CAD", listOf(13266), null)
    private val place = Place("estacion:TM0101", PlaceKind.ESTACION, "León XIII", "leon xiii", "Soacha", "TM0101", listOf(service))

    @Test fun editDeletionNamesSavedBoardAndRequiresConfirmation() {
        var deleted = 0
        compose.setContent { TransmicroneTheme {
            SetupScreen("", emptyList(), place.copy(name = "Draft station"), setOf(service.key()), true, false,
                onQuery = {}, onPick = {}, onToggle = {}, onSave = {}, onBack = {},
                isEditing = true, editingBoardName = "León XIII", onDelete = { deleted++ })
        } }
        compose.onNodeWithText("Eliminar tablero").performClick()
        compose.onNodeWithText("Se eliminará el tablero «León XIII». Podrás deshacerlo durante unos segundos en el aviso de confirmación.").assertIsDisplayed()
        compose.onNodeWithText("Cancelar").performClick()
        compose.runOnIdle { assertEquals(0, deleted) }
        compose.onNodeWithText("¿Eliminar este tablero?").assertDoesNotExist()
        compose.onNodeWithText("Eliminar tablero").performClick()
        compose.onNodeWithText("Eliminar").performClick()
        compose.runOnIdle { assertEquals(1, deleted) }
    }

    @Test fun missingCatalogPlaceStillAllowsDeletingSavedBoard() {
        var deleted = 0
        compose.setContent { TransmicroneTheme {
            SetupScreen("", emptyList(), null, emptySet(), true, false,
                onQuery = {}, onPick = {}, onToggle = {}, onSave = {}, onBack = {},
                isEditing = true, editingBoardName = "Removed station", onDelete = { deleted++ })
        } }
        compose.onNodeWithText("Eliminar tablero").assertIsDisplayed().performClick()
        compose.onNodeWithText("Removed station", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Eliminar").performClick()
        compose.runOnIdle { assertEquals(1, deleted) }
        compose.onNodeWithText("Guardar tablero").assertDoesNotExist()
    }

    @Test fun finalBoardDeletionShowsUndoOnEmptyHomeAndRestoresBoard() {
        val saved = BoardView(Board("last", place.placeId, place.kind, place.queryParadero, place.name,
            place.disambiguator, listOf(SelectedServicio(service.painted, service.destino, service.routeIds))))
        compose.setContent {
            var boards by remember { mutableStateOf(listOf(saved)) }
            var editing by remember { mutableStateOf(false) }
            val host = remember { SnackbarHostState() }
            val scope = rememberCoroutineScope()
            TransmicroneTheme {
                if (editing) SetupScreen("", emptyList(), place, setOf(service.key()), true, false,
                    onQuery = {}, onPick = {}, onToggle = {}, onSave = {}, onBack = { editing = false },
                    isEditing = true, editingBoardName = saved.board.displayName,
                    onDelete = {
                        boards = emptyList()
                        editing = false
                        scope.launch {
                            if (host.showSnackbar("Tablero eliminado", "Deshacer", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) boards = listOf(saved)
                        }
                    }, snackbarHostState = host)
                else HomeScreen(boards, 0, {}, {}, { editing = true }, {}, snackbarHostState = host)
            }
        }
        compose.onNodeWithContentDescription("Editar tablero").performClick()
        compose.onNodeWithText("Eliminar tablero").performClick()
        compose.onNodeWithText("Eliminar").performClick()
        compose.onNodeWithText("Sin tableros").assertIsDisplayed()
        compose.onNodeWithText("Deshacer").assertIsDisplayed().performClick()
        compose.onNodeWithText("Sin tableros").assertDoesNotExist()
        compose.onNodeWithContentDescription("Editar tablero").assertIsDisplayed()
    }

    @Test fun addingHasNoDeleteAction() {
        compose.setContent { TransmicroneTheme {
            SetupScreen("", emptyList(), place, setOf(service.key()), true, false,
                onQuery = {}, onPick = {}, onToggle = {}, onSave = {}, onBack = {})
        } }
        compose.onNodeWithText("Eliminar tablero").assertDoesNotExist()
    }
}
