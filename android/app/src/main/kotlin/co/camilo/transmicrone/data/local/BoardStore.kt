package co.camilo.transmicrone.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import co.camilo.transmicrone.domain.Board
import co.camilo.transmicrone.domain.Paradero
import co.camilo.transmicrone.domain.PlaceKind
import co.camilo.transmicrone.domain.SelectedServicio
import co.camilo.transmicrone.domain.TextNorm
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.boardDataStore: DataStore<Preferences> by preferencesDataStore(name = "boards")

interface BoardRepository {
    fun observe(): Flow<BoardSnapshot>
    suspend fun snapshot(): BoardSnapshot
    suspend fun save(board: Board)
    suspend fun delete(boardId: String)
    suspend fun reorder(boardIds: List<String>)
    suspend fun setLastBoard(boardId: String?)
    fun newId(): String = UUID.randomUUID().toString()
}

data class BoardSnapshot(
    val boards: List<Board>,
    val lastBoardId: String?,
)

internal fun BoardSnapshot.saving(cleaned: Board): BoardSnapshot {
    val index = boards.indexOfFirst { it.boardId == cleaned.boardId }
    val next = if (index >= 0) {
        boards.toMutableList().also { it[index] = cleaned }
    } else {
        boards + cleaned
    }
    return copy(boards = next, lastBoardId = cleaned.boardId)
}

class DataStoreBoardRepository(
    private val context: Context,
    private val json: Json,
) : BoardRepository {
    override fun observe(): Flow<BoardSnapshot> =
        context.boardDataStore.data.map { prefs -> decode(prefs) }

    override suspend fun snapshot(): BoardSnapshot = observe().first()

    override suspend fun save(board: Board) {
        val query = Paradero.normalize(board.queryParadero) ?: return
        val cleaned = board.copy(
            queryParadero = query,
            displayName = TextNorm.nfc(board.displayName),
            disambiguator = board.disambiguator?.let(TextNorm::nfc)?.ifBlank { null },
            servicios = board.servicios.map {
                it.copy(
                    destino = TextNorm.nfc(it.destino),
                    routeIds = it.routeIds.distinct(),
                )
            },
        )
        mutate { current -> current.saving(cleaned) }
    }

    override suspend fun delete(boardId: String) {
        mutate { current ->
            val boards = current.boards.filterNot { it.boardId == boardId }
            val last = if (current.lastBoardId == boardId) boards.firstOrNull()?.boardId else current.lastBoardId
            current.copy(boards = boards, lastBoardId = last)
        }
    }

    override suspend fun reorder(boardIds: List<String>) {
        mutate { current ->
            val map = current.boards.associateBy { it.boardId }
            val ordered = boardIds.mapNotNull { map[it] }
            val rest = current.boards.filter { it.boardId !in boardIds }
            current.copy(boards = ordered + rest)
        }
    }

    override suspend fun setLastBoard(boardId: String?) {
        mutate { it.copy(lastBoardId = boardId) }
    }

    private suspend fun mutate(block: (BoardSnapshot) -> BoardSnapshot) {
        context.boardDataStore.edit { prefs ->
            val next = block(decode(prefs))
            prefs[BOARDS] = json.encodeToString(
                StoredFile.serializer(),
                StoredFile(
                    boards = next.boards.map { it.toStored() },
                    lastBoardId = next.lastBoardId,
                ),
            )
            if (next.lastBoardId == null) prefs.remove(LAST) else prefs[LAST] = next.lastBoardId
        }
    }

    private fun decode(prefs: Preferences): BoardSnapshot {
        val raw = prefs[BOARDS]
        val parsed = if (raw.isNullOrBlank()) {
            StoredFile()
        } else {
            runCatching { json.decodeFromString(StoredFile.serializer(), raw) }
                .getOrDefault(StoredFile())
        }
        val boards = parsed.boards.mapNotNull { it.toBoard() }
        val last = prefs[LAST] ?: parsed.lastBoardId
        return BoardSnapshot(
            boards = boards,
            lastBoardId = last?.takeIf { id -> boards.any { it.boardId == id } },
        )
    }

    companion object {
        private val BOARDS = stringPreferencesKey("boards_json")
        private val LAST = stringPreferencesKey("last_board_id")
    }
}

@Serializable
private data class StoredFile(
    val boards: List<StoredBoard> = emptyList(),
    @SerialName("last_board_id") val lastBoardId: String? = null,
)

@Serializable
private data class StoredBoard(
    @SerialName("board_id") val boardId: String,
    @SerialName("place_id") val placeId: String,
    val kind: String,
    @SerialName("query_paradero") val queryParadero: String,
    @SerialName("display_name") val displayName: String,
    val disambiguator: String? = null,
    val servicios: List<StoredServicio> = emptyList(),
) {
    fun toBoard(): Board? {
        val query = Paradero.normalize(queryParadero) ?: return null
        if (servicios.isEmpty()) return null
        return Board(
            boardId = boardId,
            placeId = placeId,
            kind = if (kind == "paradero") PlaceKind.PARADERO else PlaceKind.ESTACION,
            queryParadero = query,
            displayName = TextNorm.nfc(displayName),
            disambiguator = disambiguator,
            servicios = servicios.map { it.toSelected() },
        )
    }
}

@Serializable
private data class StoredServicio(
    val painted: String,
    val destino: String,
    @SerialName("route_ids") val routeIds: List<Int> = emptyList(),
    val color: String? = null,
) {
    fun toSelected() = SelectedServicio(
        painted = painted,
        destino = TextNorm.nfc(destino),
        routeIds = routeIds.distinct(),
        color = color,
    )
}

private fun Board.toStored() = StoredBoard(
    boardId = boardId,
    placeId = placeId,
    kind = if (kind == PlaceKind.PARADERO) "paradero" else "estacion",
    queryParadero = queryParadero,
    displayName = displayName,
    disambiguator = disambiguator,
    servicios = servicios.map {
        StoredServicio(it.painted, it.destino, it.routeIds, it.color)
    },
)
