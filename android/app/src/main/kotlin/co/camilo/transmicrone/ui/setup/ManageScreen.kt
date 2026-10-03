package co.camilo.transmicrone.ui.setup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.camilo.transmicrone.R
import co.camilo.transmicrone.ui.BoardView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageScreen(
    boards: List<BoardView>, onBack: () -> Unit, onEdit: (String) -> Unit,
    onDelete: (String) -> Unit, onMove: (String, Int) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<BoardView?>(null) }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.manage_boards), style = MaterialTheme.typography.titleMedium) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } })
    }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(Modifier.padding(horizontal = 4.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.your_boards), style = MaterialTheme.typography.headlineLarge)
                    Text(stringResource(R.string.manage_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            itemsIndexed(boards, key = { _, item -> item.board.boardId }) { index, view ->
                var expanded by remember { mutableStateOf(false) }
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 20.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(view.board.displayName, style = MaterialTheme.typography.titleLarge)
                            view.board.disambiguator?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Text(view.board.servicios.joinToString(" · ") { it.painted }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        Box {
                            IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.board_options, view.board.displayName)) }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.edit_board)) }, leadingIcon = { Icon(Icons.Default.Edit, null) },
                                    onClick = { expanded = false; onEdit(view.board.boardId) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.move_up)) }, enabled = index > 0, leadingIcon = { Icon(Icons.Default.KeyboardArrowUp, null) },
                                    onClick = { expanded = false; onMove(view.board.boardId, -1) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.move_down)) }, enabled = index < boards.lastIndex, leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, null) },
                                    onClick = { expanded = false; onMove(view.board.boardId, 1) })
                                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                                DropdownMenuItem(text = { Text(stringResource(R.string.delete_board), color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { expanded = false; pendingDelete = view })
                            }
                        }
                    }
                }
            }
        }
    }
    pendingDelete?.let { view ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            icon = { Icon(Icons.Default.DeleteOutline, null) },
            title = { Text(stringResource(R.string.confirm_delete)) },
            text = { Text(stringResource(R.string.delete_board_body, view.board.displayName)) },
            confirmButton = { TextButton(onClick = { pendingDelete = null; onDelete(view.board.boardId) }) { Text(stringResource(R.string.delete_action)) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
