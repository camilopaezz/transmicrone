package co.camilo.transmicrone

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import co.camilo.transmicrone.data.ArrivalsRepository
import co.camilo.transmicrone.data.catalog.CatalogRepository
import co.camilo.transmicrone.data.feed.FeedClient
import co.camilo.transmicrone.data.local.ArrivalsCache
import co.camilo.transmicrone.data.local.BoardRepository
import co.camilo.transmicrone.data.local.BoardSnapshot
import co.camilo.transmicrone.data.local.restoringDeleted
import co.camilo.transmicrone.data.local.saving
import co.camilo.transmicrone.data.local.deleting
import co.camilo.transmicrone.domain.*
import co.camilo.transmicrone.ui.AppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class BoardManagementTest {
    @Test
    fun deletingSelectedBoardChoosesClampedNeighborAndUndoRestoresIt() {
        val initial = BoardSnapshot(listOf(board("a"), board("b"), board("c")), "b")
        val middle = initial.deleting("b")
        assertEquals("c", middle.lastBoardId)
        assertEquals("b", middle.restoringDeleted(board("b"), listOf("a", "b", "c"), "b", "c").lastBoardId)
        val last = initial.copy(lastBoardId = "c").deleting("c")
        assertEquals("b", last.lastBoardId)
        assertEquals("c", last.restoringDeleted(board("c"), listOf("a", "b", "c"), "c", "b").lastBoardId)
        assertNull(BoardSnapshot(listOf(board("c")), "c").deleting("c").lastBoardId)
        assertEquals("b", initial.deleting("a").lastBoardId)
    }

    @Test
    fun restoreReturnsDeletedBoardToItsPositionAndSelection() {
        val deleted = board("b")
        val result = BoardSnapshot(listOf(board("a"), board("c")), "a")
            .restoringDeleted(deleted, listOf("a", "b", "c"), "b", "a")
        assertEquals(listOf("a", "b", "c"), result.boards.map { it.boardId })
        assertEquals(deleted, result.boards[1])
        assertEquals("b", result.lastBoardId)
    }

    @Test
    fun restoreRetainsLaterReorderingAdditionsEditsAndSelection() {
        val edited = board("c").copy(displayName = "Edited", servicios = listOf(SelectedServicio("B23", "Alcalá", listOf(12819))))
        val result = BoardSnapshot(listOf(edited, board("new"), board("a")), "new")
            .restoringDeleted(board("b"), listOf("a", "b", "c"), "b", "a")
        assertEquals(listOf("b", "c", "new", "a"), result.boards.map { it.boardId })
        assertEquals(edited, result.boards[1])
        assertEquals("new", result.lastBoardId)
    }

    @Test
    fun restoreUsesPreviousNeighborWhenFollowingBoardWasAlsoRemoved() {
        val result = BoardSnapshot(listOf(board("new"), board("a")), "new")
            .restoringDeleted(board("b"), listOf("a", "b", "c"), "b", "a")
        assertEquals(listOf("new", "a", "b"), result.boards.map { it.boardId })
    }

    @Test
    fun restoreLastDeletedBoardIntoEmptyList() {
        val result = BoardSnapshot(emptyList(), null)
            .restoringDeleted(board("a"), listOf("a"), "a", null)
        assertEquals(listOf(board("a")), result.boards)
        assertEquals("a", result.lastBoardId)
    }

    @Test
    fun staleUndoDoesNotOverwriteExistingBoard() {
        val edited = board("a").copy(displayName = "Edited")
        val original = BoardSnapshot(listOf(edited), "a")
        assertEquals(original, original.restoringDeleted(board("a"), listOf("a"), "a", null))
    }

    @Test
    fun deletionAndUndoUseRepositorySnapshotsBeforeUiObserverCatchesUp() = withVm { vm, repository ->
        vm.deleteBoard("b")
        vm.moveBoard("c", -1)
        runCurrent()
        assertEquals(listOf("c", "a"), repository.snapshot().boards.map { it.boardId })
        assertEquals("b", vm.state.value.pendingDeletion?.boardId)
        vm.undoDeleteBoard("b")
        runCurrent()
        assertEquals(listOf("b", "c", "a"), repository.snapshot().boards.map { it.boardId })
        assertEquals("b", repository.snapshot().lastBoardId)
        assertNull(vm.state.value.pendingDeletion)
    }

    @Test
    fun oldSnackbarCannotUndoOrDismissNewerDeletion() = withVm { vm, repository ->
        vm.deleteBoard("a")
        vm.deleteBoard("b")
        runCurrent()
        vm.undoDeleteBoard("a")
        vm.dismissDeletedBoard("a")
        runCurrent()
        assertEquals("b", vm.state.value.pendingDeletion?.boardId)
        assertEquals(listOf("c"), repository.snapshot().boards.map { it.boardId })
        vm.undoDeleteBoard("b")
        runCurrent()
        assertEquals(listOf("b", "c"), repository.snapshot().boards.map { it.boardId })
    }

    @Test
    fun dismissedSnackbarNoLongerRestoresBoard() = withVm { vm, repository ->
        vm.deleteBoard("b")
        runCurrent()
        vm.dismissDeletedBoard("b")
        runCurrent()
        vm.undoDeleteBoard("b")
        runCurrent()
        assertEquals(listOf("a", "c"), repository.snapshot().boards.map { it.boardId })
        assertNull(vm.state.value.pendingDeletion)
    }

    @Test
    fun deletingEditedFinalBoardReturnsEmptyHomeAndUndoRestoresSelection() = withVm { vm, repository ->
        vm.deleteBoard("a")
        vm.deleteBoard("b")
        runCurrent()
        vm.openSetup("c")
        assertEquals(co.camilo.transmicrone.ui.Screen.SETUP, vm.state.value.screen)
        vm.deleteBoard("c")
        runCurrent()
        assertEquals(co.camilo.transmicrone.ui.Screen.HOME, vm.state.value.screen)
        assertNull(vm.state.value.editingBoardId)
        assertTrue(vm.state.value.boards.isEmpty())
        assertEquals("c", vm.state.value.pendingDeletion?.boardId)
        vm.undoDeleteBoard("c")
        runCurrent()
        assertEquals(listOf("c"), vm.state.value.boards.map { it.board.boardId })
        assertEquals("c", repository.snapshot().lastBoardId)
        assertEquals(co.camilo.transmicrone.ui.Screen.HOME, vm.state.value.screen)
    }

    private fun withVm(body: suspend kotlinx.coroutines.test.TestScope.(AppViewModel, ManagementBoards) -> Unit) {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        val store = ViewModelStore()
        try {
            runTest(dispatcher) {
                val repository = ManagementBoards(BoardSnapshot(listOf(board("a"), board("b"), board("c")), "b"))
                val catalog = object : CatalogRepository {
                    override suspend fun search(query: String, limit: Int): List<Place> = emptyList()
                    override fun place(placeId: String): Place? = null
                    override suspend fun generatedAt(): String = "test"
                }
                val cache = object : ArrivalsCache {
                    override fun read(boardId: String): CachedArrivals? = null
                    override fun write(boardId: String, cached: CachedArrivals) {}
                    override fun remove(boardId: String) {}
                }
                val feed = object : FeedClient {
                    override suspend fun fetchBoard(queryParadero: String): FetchResult = FetchResult.Empty(Instant.EPOCH)
                }
                val clock = Clock { Instant.EPOCH }
                val factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        AppViewModel(catalog, repository, ArrivalsRepository(feed, cache, clock), clock, dispatcher, dispatcher) as T
                }
                val vm = ViewModelProvider(store, factory)[AppViewModel::class.java]
                try {
                    runCurrent()
                    body(vm, repository)
                } finally {
                    store.clear()
                }
            }
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    private fun board(id: String) = Board(id, "estacion:TM0101", PlaceKind.ESTACION, "TM0101", id, "NQS",
        listOf(SelectedServicio("E48", "CAD", listOf(13266))))
}

private class ManagementBoards(initial: BoardSnapshot) : BoardRepository {
    private val state = MutableStateFlow(initial)
    override fun observe() = state
    override suspend fun snapshot() = state.value
    override suspend fun save(board: Board) { state.value = state.value.saving(board) }
    override suspend fun delete(boardId: String) { state.value = state.value.deleting(boardId) }
    override suspend fun reorder(boardIds: List<String>) {
        val byId = state.value.boards.associateBy { it.boardId }
        state.value = state.value.copy(boards = boardIds.mapNotNull { byId[it] })
    }
    override suspend fun setLastBoard(boardId: String?) { state.value = state.value.copy(lastBoardId = boardId) }
    override suspend fun restoreDeleted(board: Board, originalOrder: List<String>, previousLastBoardId: String?, lastBoardIdAfterDelete: String?) {
        state.value = state.value.restoringDeleted(board, originalOrder, previousLastBoardId, lastBoardIdAfterDelete)
    }
}
