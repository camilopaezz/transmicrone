package co.camilo.transmicrone.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import co.camilo.transmicrone.R
import co.camilo.transmicrone.ui.BoardView
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** A stationary viewport driven directly by the arrival pager, including title drags. */
@Composable
internal fun BoardTitleSelector(
    names: List<String>, pager: PagerState, showSwipeHint: Boolean, onChoose: () -> Unit,
) {
    val chooseLabel = stringResource(R.string.choose_board)
    val previousLabel = stringResource(R.string.previous_board)
    val nextLabel = stringResource(R.string.next_board)
    val reverseDirection = LocalLayoutDirection.current == LayoutDirection.Ltr
    val index = pager.currentPage.coerceIn(names.indices)
    val count = names.size
    val scope = rememberCoroutineScope()
    fun step(delta: Int) {
        val destination = pager.targetPage + delta
        if (destination in names.indices) scope.launch {
            pager.animateScrollToPage(destination, animationSpec = tween(280, easing = FastOutSlowInEasing))
        }
    }
    val titleStyle = MaterialTheme.typography.headlineLarge
    Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp)) {
        Box(
            modifier = Modifier.fillMaxWidth().clipToBounds()
                .scrollable(pager, Orientation.Horizontal,
                    reverseDirection = reverseDirection, flingBehavior = PagerDefaults.flingBehavior(pager))
                .combinedClickable(role = Role.Button, onClickLabel = chooseLabel,
                    onClick = onChoose, onLongClick = onChoose)
                .testTag("board-title-selector"),
        ) {
            Layout(
                modifier = Modifier.fillMaxWidth(),
                content = {
                    names.forEachIndexed { i, name ->
                        Text(name, style = titleStyle, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("board-title-$i")
                                .then(if (i != index) Modifier.clearAndSetSemantics {} else Modifier))
                    }
                },
            ) { measurables, constraints ->
                val gap = 24.dp.roundToPx()
                val previousPeek = 32.dp.toPx()
                val previousGap = 12.dp.toPx()
                val nextPeek = 32.dp.roundToPx()
                val titleConstraints = names.indices.map { i ->
                    val leading = if (i > 0) (previousPeek + previousGap).roundToInt() else 0
                    val trailing = if (i < count - 1) gap + nextPeek else 0
                    Constraints(maxWidth = (constraints.maxWidth - leading - trailing).coerceAtLeast(1))
                }
                val titles = measurables.mapIndexed { i, title -> title.measure(titleConstraints[i]) }
                val height = titles.maxOf { it.height }.coerceAtLeast(48.dp.roundToPx())
                fun titlePosition(title: Int, page: Int): Float {
                    val anchor = if (page == 0) 0f else previousPeek + previousGap
                    return when {
                        title < page -> anchor - previousGap - titles[title].width * .82f - (page - title - 1) * constraints.maxWidth
                        title > page -> anchor + titles[page].width + gap + (title - page - 1) * constraints.maxWidth
                        else -> anchor
                    }
                }
                layout(constraints.maxWidth, height) {
                    val position = (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, (count - 1).toFloat())
                    val left = floor(position).toInt()
                    val right = (left + 1).coerceAtMost(count - 1)
                    titles.forEachIndexed { i, title ->
                        val start = titlePosition(i, left)
                        val x = start + (titlePosition(i, right) - start) * (position - left)
                        val distance = abs(position - i).coerceIn(0f, 1f)
                        val scale = 1f - .18f * distance
                        title.placeRelativeWithLayer(x.roundToInt(), (height - title.height) / 2) {
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                            alpha = 1f - .55f * distance
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(0f, .5f)
                        }

                    }
                }
            }
            if (count > 1) Row(Modifier.matchParentSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()
                    .clickable(role = Role.Button, onClickLabel = previousLabel) { step(-1) }
                    .semantics {
                        contentDescription = previousLabel
                        if (pager.targetPage == 0) disabled()
                    }
                    .testTag("board-title-previous"))
                Spacer(Modifier.weight(1f))
                Box(Modifier.weight(1f).fillMaxHeight()
                    .clickable(role = Role.Button, onClickLabel = nextLabel) { step(1) }
                    .semantics {
                        contentDescription = nextLabel
                        if (pager.targetPage == count - 1) disabled()
                    }
                    .testTag("board-title-next"))
            }
        }
        if (count > 1) {
            val pageLabel = stringResource(R.string.board_page_description, index + 1, count)
            val track = MaterialTheme.colorScheme.outlineVariant
            val fill = MaterialTheme.colorScheme.primary
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.width(48.dp).height(4.dp).clearAndSetSemantics {}) {
                    drawLine(track, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), size.height, StrokeCap.Round)
                    val position = (pager.currentPage + 1 + pager.currentPageOffsetFraction).coerceIn(1f, count.toFloat()) / count
                    drawLine(fill, Offset(0f, size.height / 2), Offset(size.width * position, size.height / 2), size.height, StrokeCap.Round)
                }
                TextButton(onClick = onChoose,
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "$chooseLabel, $pageLabel" }
                        .testTag("board-page-selector")) {
                    Text(stringResource(R.string.board_page_count, index + 1, count), style = MaterialTheme.typography.labelMedium)
                    if (showSwipeHint) Text(stringResource(R.string.board_swipe_short), modifier = Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelMedium)
                    Icon(Icons.Default.ExpandMore, null, Modifier.padding(start = 4.dp).size(18.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BoardPickerSheet(
    boards: List<BoardView>, selectedId: String, onDismiss: () -> Unit,
    onSelect: (String) -> Unit, onAdd: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(horizontal = 24.dp)) {
            Text(stringResource(R.string.choose_board_title), style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 16.dp))
            LazyColumn(Modifier.weight(1f, fill = false).testTag("board-picker-list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(boards, key = { it.board.boardId }) { view ->
                    val board = view.board
                    val selected = board.boardId == selectedId
                    Surface(shape = MaterialTheme.shapes.large,
                        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = { onSelect(board.boardId) })
                            .padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(board.displayName, style = MaterialTheme.typography.titleMedium)
                                Text(listOfNotNull(board.disambiguator, board.servicios.joinToString(" · ") { it.painted })
                                    .filter { it.isNotBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (selected) Icon(Icons.Default.Check, stringResource(R.string.board_selected), Modifier.size(24.dp))
                        }
                    }
                }
            }
            TextButton(onClick = onAdd, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 8.dp)) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.add_board))
            }
        }
    }
}
