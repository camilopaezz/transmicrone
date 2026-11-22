package co.camilo.transmicrone.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import co.camilo.transmicrone.BuildConfig
import co.camilo.transmicrone.R
import co.camilo.transmicrone.data.local.AppSettings
import co.camilo.transmicrone.data.local.ThemeMode
import co.camilo.transmicrone.ui.BoardView
import co.camilo.transmicrone.ui.common.FloatingScaffold
import co.camilo.transmicrone.ui.common.FloatingScreenHeader
import co.camilo.transmicrone.ui.common.FloatingSnackbarHost
import co.camilo.transmicrone.ui.common.SegmentedGroupGap
import co.camilo.transmicrone.ui.common.SegmentedRow
import co.camilo.transmicrone.ui.common.SegmentedStackFontScale
import co.camilo.transmicrone.ui.common.SegmentedSectionLabel

@Composable
fun SettingsScreen(
    settings: AppSettings, boards: List<BoardView>, saveFailed: Boolean,
    onTheme: (ThemeMode) -> Unit, onDynamicColor: (Boolean) -> Unit,
    onMove: (String, Int) -> Unit, onBack: () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    val list = rememberLazyListState()
    FloatingScaffold(
        topEdgeVisible = { list.canScrollBackward },
        snackbarHost = { FloatingSnackbarHost(snackbarHostState) },
        topBar = {
            FloatingScreenHeader(stringResource(R.string.settings_title), stringResource(R.string.settings_back), onBack)
        },
    ) { padding ->
        // The list scrolls under the navigation bar; its inset is scrollable clearance, not a clipped strip.
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(
            start = 20.dp, end = 20.dp, top = padding.calculateTopPadding(),
            bottom = padding.calculateBottomPadding() + SegmentedGroupGap,
        )) {
            appearance(settings, onTheme, onDynamicColor)
            boardOrder(boards, saveFailed, onMove)
            help()
            about()
        }
    }
}

private fun LazyListScope.appearance(settings: AppSettings, onTheme: (ThemeMode) -> Unit, onDynamicColor: (Boolean) -> Unit) {
    item(key = "appearance-label") { SegmentedSectionLabel(stringResource(R.string.settings_appearance)) }
    item(key = "theme") {
        Column(Modifier.selectableGroup()) {
            ThemeMode.entries.forEachIndexed { index, mode ->
                val (label, icon) = when (mode) {
                    ThemeMode.SYSTEM -> R.string.settings_system to Icons.Outlined.BrightnessAuto
                    ThemeMode.LIGHT -> R.string.settings_light to Icons.Outlined.LightMode
                    ThemeMode.DARK -> R.string.settings_dark to Icons.Outlined.DarkMode
                }
                val selected = settings.theme == mode
                SegmentedRow(index, ThemeMode.entries.size, stringResource(label), icon = icon,
                    rowModifier = Modifier.selectable(selected, role = Role.RadioButton, onClick = { onTheme(mode) })) {
                    RadioButton(selected = selected, onClick = null, Modifier.minimumInteractiveComponentSize())
                }
            }
        }
    }
    item(key = "dynamic-color") {
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        SegmentedRow(0, 1, stringResource(R.string.settings_dynamic), Modifier.padding(top = SegmentedGroupGap),
            icon = Icons.Outlined.Palette,
            supporting = stringResource(if (supported) R.string.settings_dynamic_body else R.string.settings_dynamic_unavailable),
            rowModifier = Modifier.toggleable(settings.dynamicColor, enabled = supported, role = Role.Switch,
                onValueChange = onDynamicColor)) {
            Switch(checked = settings.dynamicColor, onCheckedChange = null, enabled = supported,
                modifier = Modifier.padding(end = 8.dp))
        }
    }
}

private fun LazyListScope.boardOrder(boards: List<BoardView>, saveFailed: Boolean, onMove: (String, Int) -> Unit) {
    item(key = "boards-label") { SegmentedSectionLabel(stringResource(R.string.settings_boards)) }
    if (saveFailed) item(key = "boards-error") {
        Text(stringResource(R.string.settings_save_failed), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
    }
    if (boards.isEmpty()) item(key = "boards-empty") {
        SegmentedRow(0, 1, stringResource(R.string.settings_no_boards), icon = Icons.Outlined.DashboardCustomize)
    }
    itemsIndexed(boards, key = { _, view -> "board-" + view.board.boardId }) { index, view ->
        val name = view.board.displayName
        SegmentedRow(index, boards.size, name, Modifier.animateItem(),
            icon = Icons.Outlined.DepartureBoard,
            supporting = view.board.servicios.joinToString(" · ") { it.painted },
            stackTrailing = LocalDensity.current.fontScale >= SegmentedStackFontScale) {
            IconButton(onClick = { onMove(view.board.boardId, -1) }, enabled = index > 0) {
                Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.settings_up, name))
            }
            IconButton(onClick = { onMove(view.board.boardId, 1) }, enabled = index < boards.lastIndex) {
                Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.settings_down, name))
            }
        }
    }
    item(key = "boards-body") {
        Text(stringResource(R.string.settings_boards_body), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
    }
}

private fun LazyListScope.help() {
    item(key = "help-label") { SegmentedSectionLabel(stringResource(R.string.settings_help)) }
    item(key = "help") {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                HelpLine(Icons.Outlined.Schedule, R.string.settings_estimates)
                HelpLine(Icons.Outlined.History, R.string.settings_stale)
                HelpLine(Icons.Outlined.Groups, R.string.settings_occupancy)
            }
        }
    }
}

@Composable
private fun HelpLine(icon: ImageVector, text: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun LazyListScope.about() {
    item(key = "about-label") { SegmentedSectionLabel(stringResource(R.string.settings_about)) }
    if (BuildConfig.DEBUG && BuildConfig.MOCK_FEED) item(key = "sample-data") {
        Surface(Modifier.padding(bottom = SegmentedGroupGap), shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.tertiaryContainer) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Outlined.Science, null, Modifier.size(24.dp))
                Text(stringResource(R.string.sample_data), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
    item(key = "about") {
        val seconds = (BuildConfig.AUTO_REFRESH_MS / 1000).toInt()
        Column {
            SegmentedRow(0, 3, stringResource(R.string.app_name), icon = Icons.Outlined.Info,
                supporting = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME))
            SegmentedRow(1, 3, stringResource(R.string.settings_source_title), icon = Icons.Outlined.Dataset,
                supporting = stringResource(R.string.settings_source))
            SegmentedRow(2, 3, stringResource(R.string.settings_refresh_title), icon = Icons.Outlined.Update,
                supporting = pluralStringResource(R.plurals.settings_refresh, seconds, seconds))
        }
    }
}
