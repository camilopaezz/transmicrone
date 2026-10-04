package co.camilo.transmicrone

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarDuration
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import co.camilo.transmicrone.ui.AppViewModel
import co.camilo.transmicrone.ui.Screen
import co.camilo.transmicrone.ui.home.HomeScreen
import co.camilo.transmicrone.ui.setup.SetupScreen
import co.camilo.transmicrone.ui.theme.TransmicroneTheme
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels {
        AppViewModel.Factory((application as TransmicroneApp).container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.setStarted(true)
                try {
                    awaitCancellation()
                } finally {
                    vm.setStarted(false)
                }
            }
        }
        setContent {
            TransmicroneTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                val swipeHintDismissal = rememberSaveable { mutableStateOf(false) }
                val snackbarHostState = remember { SnackbarHostState() }
                val deletedMessage = state.pendingDeletion?.let { stringResource(R.string.manage_deleted_message, it.displayName) }
                val undoLabel = stringResource(R.string.manage_undo)
                LaunchedEffect(state.pendingDeletion?.boardId) {
                    val deleted = state.pendingDeletion ?: return@LaunchedEffect
                    snackbarHostState.currentSnackbarData?.dismiss()
                    val result = snackbarHostState.showSnackbar(deletedMessage.orEmpty(), undoLabel,
                        withDismissAction = true, duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) vm.undoDeleteBoard(deleted.boardId)
                    else vm.dismissDeletedBoard(deleted.boardId)
                }
                BackHandler(enabled = state.screen != Screen.HOME) {
                    vm.backSetup()
                }
                when (state.screen) {
                    Screen.HOME -> HomeScreen(
                        boards = state.boards,
                        pageIndex = state.pageIndex,
                        onPage = vm::onPage,
                        onAdd = { vm.openSetup(null) },
                        onEdit = { vm.openSetup(it) },
                        swipeHintDismissal = swipeHintDismissal,
                        onRefresh = vm::manualRefresh,
                        loadingBoards = !state.boardsReady,
                        snackbarHostState = snackbarHostState,
                    )
                    Screen.SETUP -> SetupScreen(
                        query = state.setupQuery,
                        results = state.setupResults,
                        place = state.setupPlace,
                        selected = state.setupSelected,
                        catalogReady = state.catalogReady,
                        searching = state.setupSearching,
                        onQuery = vm::onSearch,
                        onPick = vm::pickPlace,
                        onToggle = vm::toggleServicio,
                        onSave = vm::saveSetup,
                        onBack = vm::backSetup,
                        isEditing = state.editingBoardId != null,
                        editingBoardName = state.boards.find { it.board.boardId == state.editingBoardId }?.board?.displayName,
                        onDelete = { state.editingBoardId?.let(vm::deleteBoard) },
                        onChangePlace = vm::changeSetupPlace,
                        snackbarHostState = snackbarHostState,
                    )
                }
            }
        }
    }
}
