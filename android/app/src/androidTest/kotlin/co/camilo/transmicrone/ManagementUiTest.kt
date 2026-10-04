package co.camilo.transmicrone

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import co.camilo.transmicrone.domain.Board
import co.camilo.transmicrone.domain.PlaceKind
import co.camilo.transmicrone.domain.SelectedServicio
import co.camilo.transmicrone.ui.BoardView
import co.camilo.transmicrone.ui.setup.ManageScreen
import co.camilo.transmicrone.ui.theme.TransmicroneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class ManagementUiTest {
    @get:Rule val compose = createComposeRule()

    private val board = BoardView(Board("synthetic-board", "estacion:TM0101", PlaceKind.ESTACION,
        "TM0101", "León XIII", "Soacha", listOf(SelectedServicio("E48", "CAD", listOf(13266)))))

    @Test
    fun tappingBoardOpensEditAndOptionsHaveMinimumTouchArea() {
        var edited: String? = null
        compose.setContent {
            TransmicroneTheme {
                ManageScreen(listOf(board), onBack = {}, onEdit = { edited = it }, onDelete = {}, onMove = { _, _ -> })
            }
        }
        compose.onNodeWithText("León XIII").performClick()
        compose.runOnIdle { assertEquals(board.board.boardId, edited) }
        compose.onNodeWithContentDescription("Opciones de León XIII")
            .assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun deletionNamesBoardAndCancelKeepsItWhileConfirmInvokesDeletion() {
        val boards = mutableStateOf(listOf(board))
        var deleted: String? = null
        var edited: String? = null
        compose.setContent {
            TransmicroneTheme {
                ManageScreen(boards.value, onBack = {}, onEdit = { edited = it },
                    onDelete = { id -> deleted = id; boards.value = boards.value.filterNot { it.board.boardId == id } },
                    onMove = { _, _ -> })
            }
        }
        compose.onNodeWithContentDescription("Opciones de León XIII").performClick()
        compose.onNodeWithText("Eliminar tablero").performClick()
        compose.onNodeWithText("¿Eliminar este tablero?").assertIsDisplayed()
        compose.onNodeWithText("Se eliminará el tablero «León XIII». Podrás deshacerlo durante unos segundos en el aviso de confirmación.").assertIsDisplayed()
        compose.onNodeWithText("Cancelar").performClick()
        compose.onNodeWithText("¿Eliminar este tablero?").assertDoesNotExist()
        compose.onNodeWithText("León XIII").assertIsDisplayed()
        compose.runOnIdle {
            assertNull(deleted)
            assertNull(edited)
            assertEquals(listOf(board), boards.value)
        }
        compose.onNodeWithContentDescription("Opciones de León XIII").performClick()
        compose.onNodeWithText("Eliminar tablero").performClick()
        compose.onNodeWithText("Eliminar").performClick()
        compose.runOnIdle {
            assertEquals(board.board.boardId, deleted)
            assertNull(edited)
            assertEquals(emptyList<BoardView>(), boards.value)
        }
        compose.onNodeWithText("Aún no tienes tableros").assertIsDisplayed()
    }

    @Test
    fun emptyManagementHasAddBoardAction() {
        var added = 0
        compose.setContent {
            TransmicroneTheme {
                ManageScreen(emptyList(), onBack = {}, onEdit = {}, onDelete = {}, onMove = { _, _ -> }, onAdd = { added++ })
            }
        }
        compose.onNodeWithText("Aún no tienes tableros").assertIsDisplayed()
        compose.onNodeWithText("Agregar tablero").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, added) }
    }
}
