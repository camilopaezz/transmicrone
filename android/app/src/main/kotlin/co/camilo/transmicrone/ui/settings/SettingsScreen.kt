package co.camilo.transmicrone.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import co.camilo.transmicrone.BuildConfig
import co.camilo.transmicrone.R
import co.camilo.transmicrone.data.local.AppSettings
import co.camilo.transmicrone.data.local.ThemeMode
import co.camilo.transmicrone.ui.common.FloatingScaffold
import co.camilo.transmicrone.ui.common.FloatingScreenHeader
import co.camilo.transmicrone.ui.BoardView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings, boards: List<BoardView>, saveFailed: Boolean,
    onTheme: (ThemeMode) -> Unit, onDynamicColor: (Boolean) -> Unit,
    onMove: (String, Int) -> Unit, onBack: () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    FloatingScaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            FloatingScreenHeader(stringResource(R.string.settings_title), stringResource(R.string.settings_back), onBack)
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding()),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = padding.calculateTopPadding() + 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { SectionTitle(R.string.settings_appearance) }
            item {
                SettingsCard {
                    Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(16.dp))
                    Column(Modifier.selectableGroup()) {
                        ThemeMode.entries.forEach { mode ->
                            val label = when (mode) {
                                ThemeMode.SYSTEM -> R.string.settings_system
                                ThemeMode.LIGHT -> R.string.settings_light
                                ThemeMode.DARK -> R.string.settings_dark
                            }
                            Row(Modifier.fillMaxWidth().selectable(settings.theme == mode,
                                role = Role.RadioButton, onClick = { onTheme(mode) }).padding(horizontal = 16.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = settings.theme == mode, onClick = null)
                                Spacer(Modifier.width(16.dp))
                                Text(stringResource(label), Modifier.padding(vertical = 12.dp))
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    Row(Modifier.fillMaxWidth().toggleable(settings.dynamicColor, enabled = supported,
                        role = Role.Switch, onValueChange = onDynamicColor).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_dynamic), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(if (supported) R.string.settings_dynamic_body else R.string.settings_dynamic_unavailable),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = settings.dynamicColor, onCheckedChange = null, enabled = supported)
                    }
                }
            }
            item {
                SectionTitle(R.string.settings_boards)
                Text(stringResource(R.string.settings_boards_body), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (saveFailed) Text(stringResource(R.string.settings_save_failed), color = MaterialTheme.colorScheme.error)
            }
            if (boards.isEmpty()) item { Text(stringResource(R.string.settings_no_boards)) }
            itemsIndexed(boards, key = { _, view -> view.board.boardId }) { index, view ->
                SettingsCard {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(view.board.displayName, style = MaterialTheme.typography.titleMedium)
                            Text(view.board.servicios.joinToString(" · ") { it.painted },
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { onMove(view.board.boardId, -1) }, enabled = index > 0) {
                            Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.settings_up, view.board.displayName))
                        }
                        IconButton(onClick = { onMove(view.board.boardId, 1) }, enabled = index < boards.lastIndex) {
                            Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.settings_down, view.board.displayName))
                        }
                    }
                }
            }
            item { SectionTitle(R.string.settings_help) }
            item { SettingsCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.settings_estimates))
                Text(stringResource(R.string.settings_stale))
                Text(stringResource(R.string.settings_occupancy))
            } } }
            item { SectionTitle(R.string.settings_about) }
            item { SettingsCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium)
                if (BuildConfig.DEBUG && BuildConfig.MOCK_FEED) Text(stringResource(R.string.sample_data), color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.settings_source), style = MaterialTheme.typography.bodyMedium)
                Text(pluralStringResource(R.plurals.settings_refresh, (BuildConfig.AUTO_REFRESH_MS / 1000).toInt(),
                    BuildConfig.AUTO_REFRESH_MS / 1000), style = MaterialTheme.typography.bodyMedium)
            } } }
        }
    }
}

@Composable
private fun SectionTitle(resource: Int) {
    Text(stringResource(resource), style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}
