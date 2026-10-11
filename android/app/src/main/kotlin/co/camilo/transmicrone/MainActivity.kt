package co.camilo.transmicrone

import android.os.Bundle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import co.camilo.transmicrone.data.local.SettingsStore
import co.camilo.transmicrone.data.local.AppSettings
import co.camilo.transmicrone.data.local.ThemeMode
import co.camilo.transmicrone.ui.settings.SettingsScreen
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
import co.camilo.transmicrone.ui.common.ScreenTransition
import co.camilo.transmicrone.ui.home.HomeScreen
import co.camilo.transmicrone.ui.setup.SetupScreen
import co.camilo.transmicrone.ui.search.CatalogSearchScreen
import co.camilo.transmicrone.ui.search.CatalogDetailsScreen
import co.camilo.transmicrone.domain.CatalogEntry
import co.camilo.transmicrone.ui.key
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
            val context = LocalContext.current
            val settingsStore = remember { SettingsStore(context.applicationContext) }
            val settings by settingsStore.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            val state by vm.state.collectAsStateWithLifecycle()
            val scope = rememberCoroutineScope()
            val settingsFailure = stringResource(R.string.settings_save_failed)
            val dark = when (settings.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            TransmicroneTheme(darkTheme = dark, dynamicColor = settings.dynamicColor) {
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
                ScreenTransition(state) { displayedState ->
                    val state = displayedState
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
                            onSearch = vm::openSearch,
                            onSettings = vm::openSettings,
                        )
                        Screen.SETUP -> SetupScreen(
                            query = state.setupQuery,
                            results = state.setupResults,
                            place = state.setupPlace,
                            selected = state.setupSelected,
                            catalogReady = state.catalogReady,
                            catalogLoadFailed = state.catalogLoadFailed,
                            searching = state.setupSearching,
                            searchFailed = state.setupSearchFailed,
                            saveFailed = state.setupSaveFailed,
                            onQuery = vm::onSearch,
                            onPick = vm::pickPlace,
                            onToggle = vm::toggleServicio,
                            onSave = vm::saveSetup,
                            customName = state.setupCustomName,
                            onCustomName = vm::onSetupCustomName,
                            onBack = vm::backSetup,
                            isEditing = state.editingBoardId != null,
                            editingBoardName = state.boards.find { it.board.boardId == state.editingBoardId }?.board?.displayName,
                            onDelete = { state.editingBoardId?.let(vm::deleteBoard) },
                            onChangePlace = vm::changeSetupPlace,
                            snackbarHostState = snackbarHostState,
                        )
                        Screen.SETTINGS -> SettingsScreen(
                            settings = settings, boards = state.boards, saveFailed = state.settingsSaveFailed,
                            onTheme = { mode -> scope.launch {
                                try { settingsStore.setTheme(mode) }
                                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                catch (_: Exception) { snackbarHostState.showSnackbar(settingsFailure) }
                            } },
                            onDynamicColor = { enabled -> scope.launch {
                                try { settingsStore.setDynamicColor(enabled) }
                                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                catch (_: Exception) { snackbarHostState.showSnackbar(settingsFailure) }
                            } },
                            onMove = vm::moveBoard, onBack = vm::backSetup,
                            snackbarHostState = snackbarHostState,
                        )
                        Screen.SEARCH -> CatalogSearchScreen(
                            query = state.catalogQuery, results = state.catalogResults,
                            searching = state.catalogSearching, failed = state.catalogSearchFailed,
                            onQuery = vm::searchCatalog, onDetails = vm::openDetails, onBack = vm::backSetup,
                        )
                        Screen.DETAILS -> state.details.lastOrNull()?.let { entry ->
                            CatalogDetailsScreen(entry, onBack = vm::backSetup,
                                onPlace = { vm.openDetails(CatalogEntry.Location(it)) },
                                onService = vm::openService, onAdd = vm::createBoardFrom,
                                serviceLookup = state.serviceLookup, onRetryService = vm::retryServiceLookup,
                                routeState = (entry as? CatalogEntry.Service)?.option?.let { state.routes[it.key()] },
                                routeIndex = (entry as? CatalogEntry.Service)?.option?.let { state.selectedRoutes[it.key()] } ?: 0,
                                onSelectRoute = { index -> (entry as? CatalogEntry.Service)?.option?.let { vm.selectRoute(it, index) } },
                                onRetryRoute = { (entry as? CatalogEntry.Service)?.option?.let { vm.loadRoute(it, retry = true) } })
                        }
                    }
                }
            }
        }
    }
}
