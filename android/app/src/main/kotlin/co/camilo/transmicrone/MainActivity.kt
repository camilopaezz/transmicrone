package co.camilo.transmicrone

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import co.camilo.transmicrone.ui.AppViewModel
import co.camilo.transmicrone.ui.Screen
import co.camilo.transmicrone.ui.home.HomeScreen
import co.camilo.transmicrone.ui.setup.ManageScreen
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
                BackHandler(enabled = state.screen != Screen.HOME) { vm.goHome() }
                when (state.screen) {
                    Screen.HOME -> HomeScreen(
                        boards = state.boards,
                        pageIndex = state.pageIndex,
                        onPage = vm::onPage,
                        onAdd = { vm.openSetup(null) },
                        onManage = vm::openManage,
                        onRefresh = vm::manualRefresh,
                        loadingBoards = !state.boardsReady,
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
                        onBack = vm::goHome,
                    )
                    Screen.MANAGE -> ManageScreen(
                        boards = state.boards,
                        onBack = vm::goHome,
                        onEdit = { vm.openSetup(it) },
                        onDelete = vm::deleteBoard,
                        onMove = vm::moveBoard,
                    )
                }
            }
        }
    }
}
