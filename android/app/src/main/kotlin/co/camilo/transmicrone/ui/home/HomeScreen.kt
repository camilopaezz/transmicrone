package co.camilo.transmicrone.ui.home

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import co.camilo.transmicrone.R
import co.camilo.transmicrone.BuildConfig
import co.camilo.transmicrone.ui.BoardView
import co.camilo.transmicrone.ui.Freshness
import co.camilo.transmicrone.ui.board.ArrivalList
import co.camilo.transmicrone.ui.common.FreshnessLine
import co.camilo.transmicrone.ui.common.relativeTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    boards: List<BoardView>, pageIndex: Int,
    onPage: (Int) -> Unit, onAdd: () -> Unit, onManage: () -> Unit,
    onRefresh: () -> Unit,
    loadingBoards: Boolean = false,
) {
    if (loadingBoards) {
        Scaffold { insets ->
            Column(Modifier.fillMaxSize().padding(insets), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(20.dp))
                Text(stringResource(R.string.loading_boards), style = MaterialTheme.typography.bodyLarge)
            }
        }
        return
    }
    if (boards.isEmpty()) { EmptyHome(onAdd); return }
    val index = pageIndex.coerceIn(0, boards.lastIndex)
    val current = boards[index]
    val listStates = boards.map { view -> key(view.board.boardId) { rememberLazyListState() } }
    val currentListState = listStates[index]
    val headerGone by remember(currentListState) { derivedStateOf { currentListState.firstVisibleItemIndex > 0 } }
    val pager = rememberPagerState(initialPage = index, pageCount = { boards.size })
    LaunchedEffect(index) {
        if (pager.currentPage != index) pager.animateScrollToPage(index)
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { onPage(it) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        AnimatedContent(
                            targetState = headerGone,
                            transitionSpec = { (fadeIn() + slideInVertically { it / 2 }) togetherWith fadeOut() },
                            label = "stationTitle",
                        ) { gone ->
                            Text(
                                if (gone) current.board.displayName else stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (BuildConfig.DEBUG && BuildConfig.MOCK_FEED) {
                            Text(stringResource(R.string.sample_data), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                navigationIcon = {
                    Icon(Icons.Default.DirectionsBus, null, Modifier.padding(start = 20.dp, end = 12.dp), tint = MaterialTheme.colorScheme.primary)
                },
                actions = {
                    TextButton(onClick = onManage, modifier = Modifier.padding(end = 8.dp)) {
                        Icon(Icons.Default.Dashboard, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.manage_boards))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.add_board)) },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            if (boards.size > 1) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    boards.forEachIndexed { i, view ->
                        FilterChip(
                            selected = i == index, onClick = { onPage(i) },
                            label = { Text(view.board.displayName) },
                            leadingIcon = if (i == index) ({ Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }) else null,
                        )
                    }
                }
            }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
                val view = boards[page]
                val empty = when (val freshness = view.freshness) {
                    Freshness.Idle, Freshness.Loading -> stringResource(R.string.freshness_updating)
                    is Freshness.Cached -> stringResource(R.string.freshness_stale, relativeTime(freshness.cachedAt))
                    is Freshness.UpdatingCached -> stringResource(R.string.freshness_updating)
                    is Freshness.Failed, is Freshness.FailedCached -> stringResource(R.string.freshness_failed)
                    Freshness.Unsupported -> stringResource(R.string.freshness_unsupported)
                    is Freshness.Empty, is Freshness.Updated -> stringResource(R.string.arrivals_empty)
                }
                val refreshing = view.freshness == Freshness.Loading || view.freshness is Freshness.UpdatingCached
                val listState = listStates[page]
                val hasScrolled by remember(listState) { derivedStateOf { listState.canScrollBackward } }
                val fade by animateFloatAsState(if (hasScrolled) 1f else 0f, label = "listTopFade")
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    ArrivalList(
                        view.arrivals, empty, Modifier.fillMaxSize().topFade { fade }, state = listState,
                        header = { StationHeader(view, page, boards.size, onPage) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StationHeader(view: BoardView, index: Int, boardCount: Int, onPage: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 4.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(view.board.displayName, style = MaterialTheme.typography.headlineLarge)
                Row(verticalAlignment = Alignment.Top) {
                    view.board.disambiguator?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            "$it · ", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FreshnessLine(view.freshness, Modifier.weight(1f))
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            view.board.servicios.forEach { servicio ->
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(servicio.painted, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
    }
    if (boardCount > 1) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 0.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End) {
            IconButton(onClick = { onPage(index - 1) }, enabled = index > 0) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.previous_board))
            }
            Text("${index + 1}/${boardCount}", style = MaterialTheme.typography.labelMedium)
            IconButton(onClick = { onPage(index + 1) }, enabled = index < boardCount - 1) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.next_board))
            }
        }
    }
}

private fun Modifier.topFade(fraction: () -> Float): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fade = fraction()
            if (fade > 0f) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Black.copy(alpha = 1f - fade), Color.Black),
                        endY = 24.dp.toPx(),
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
        }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmptyHome(onAdd: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium) })
    }) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(Icons.Default.DirectionsBus, null, Modifier.padding(28.dp).size(48.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.height(28.dp))
            Text(stringResource(R.string.empty_home_headline), style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.empty_boards_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.empty_boards_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(28.dp))
            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.add_board))
            }
            Spacer(Modifier.height(64.dp))
        }
    }
}
