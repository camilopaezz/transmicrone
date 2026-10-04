package co.camilo.transmicrone.ui.home

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.delay
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextOverflow
import co.camilo.transmicrone.R
import co.camilo.transmicrone.BuildConfig
import co.camilo.transmicrone.ui.BoardView
import co.camilo.transmicrone.ui.Freshness
import co.camilo.transmicrone.ui.board.ArrivalList
import co.camilo.transmicrone.ui.board.parseColor
import co.camilo.transmicrone.ui.common.FreshnessLine
import co.camilo.transmicrone.ui.common.relativeTime
import co.camilo.transmicrone.ui.theme.TransmicroneBrandStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    boards: List<BoardView>, pageIndex: Int,
    onPage: (Int) -> Unit, onAdd: () -> Unit, onEdit: (String) -> Unit,
    onRefresh: () -> Unit,
    loadingBoards: Boolean = false,
    swipeHintDismissal: MutableState<Boolean> = rememberSaveable { mutableStateOf(false) },
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    if (loadingBoards) {
        Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { insets ->
            Column(Modifier.fillMaxSize().padding(insets), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(20.dp))
                Text(stringResource(R.string.loading_boards), style = MaterialTheme.typography.bodyLarge)
            }
        }
        return
    }
    if (boards.isEmpty()) { EmptyHome(onAdd, snackbarHostState); return }
    val index = pageIndex.coerceIn(0, boards.lastIndex)
    val current = boards[index]
    val listStates = boards.map { view -> key(view.board.boardId) { rememberLazyListState() } }
    val currentListState = listStates[index]
    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val fontScale = LocalDensity.current.fontScale
    val compactAppBar = fontScale > 1.2f || windowWidth < 400.dp
    val headerGone by remember(currentListState) { derivedStateOf {
        currentListState.firstVisibleItemIndex > 0 ||
            currentListState.layoutInfo.visibleItemsInfo.firstOrNull()?.let { it.offset < -it.size / 2 } == true
    } }
    val pager = rememberPagerState(initialPage = index, pageCount = { boards.size })
    var swipeHintDismissed by swipeHintDismissal
    val dragging by pager.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragging) {
        if (dragging) swipeHintDismissed = true
    }
    LaunchedEffect(boards.size > 1) {
        if (boards.size > 1) {
            delay(5000)
            swipeHintDismissed = true
        }
    }
    val showSwipeHint = boards.size > 1 && !swipeHintDismissed
    LaunchedEffect(index) {
        if (pager.currentPage != index) pager.animateScrollToPage(index)
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { onPage(it) }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                                style = if (gone) MaterialTheme.typography.titleMedium else TransmicroneBrandStyle,
                                maxLines = if (compactAppBar) 2 else 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (BuildConfig.DEBUG && BuildConfig.MOCK_FEED) {
                            Text(stringResource(R.string.sample_data), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                expandedHeight = if (fontScale >= 2f) 112.dp else if (compactAppBar) 88.dp else 64.dp,
                navigationIcon = {
                    if (!compactAppBar) Icon(Icons.Default.DirectionsBus, null, Modifier.padding(start = 20.dp, end = 12.dp), tint = MaterialTheme.colorScheme.primary)
                },
                actions = {
                    IconButton(onClick = { boards.getOrNull(pager.currentPage)?.board?.boardId?.let(onEdit) }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                        Icon(Icons.Default.Edit, stringResource(R.string.edit_board))
                    }
                },
            )
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                boards.forEachIndexed { i, view ->
                    key(view.board.boardId) {
                        val chipRequester = remember { BringIntoViewRequester() }
                        LaunchedEffect(i == index) {
                            if (i == index) chipRequester.bringIntoView()
                        }
                        FilterChip(
                            selected = i == index, onClick = { onPage(i) },
                            label = { Text(view.board.displayName) },
                            leadingIcon = if (i == index) ({ Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }) else null,
                            modifier = Modifier.bringIntoViewRequester(chipRequester),
                        )
                    }
                }
                val addLabel = stringResource(R.string.add_board)
                AssistChip(
                    onClick = onAdd,
                    label = { Text(stringResource(R.string.add_board_short)) },
                    leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(18.dp)) },
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = addLabel },
                )
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
                val pullState = rememberPullToRefreshState()
                val listState = listStates[page]
                val hasScrolled by remember(listState) { derivedStateOf { listState.canScrollBackward } }
                val fade by animateFloatAsState(if (hasScrolled) 1f else 0f, label = "listTopFade")
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = onRefresh,
                    state = pullState,
                    indicator = {},
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxWidth().height(4.dp)) {
                            if (refreshing) {
                                LinearProgressIndicator(Modifier.fillMaxSize())
                            } else if (pullState.distanceFraction > 0f) {
                                LinearProgressIndicator(progress = { pullState.distanceFraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxSize())
                            }
                        }
                        ArrivalList(
                            view.arrivals, empty, Modifier.weight(1f).fillMaxWidth().testTag("arrival-viewport").topFade { fade }, state = listState,
                            header = { StationHeader(view, page, boards.size, onRefresh) },
                            bottomContentPadding = 24.dp,
                            colorForArrival = { arrival -> routeColor(view, arrival.painted, arrival.routeId) },
                        )
                    }
                    if (showSwipeHint) Surface(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.inverseSurface,
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    ) {
                        Text(stringResource(R.string.swipe_board_hint), Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun StationHeader(view: BoardView, index: Int, boardCount: Int, onRefresh: () -> Unit) {
    Column(Modifier.padding(horizontal = 4.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(view.board.displayName, style = MaterialTheme.typography.headlineLarge)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    view.board.disambiguator?.takeIf { it.isNotBlank() && !view.board.displayName.contains(it, ignoreCase = true) }?.let {
                        Text(
                            it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FreshnessLine(view.freshness, onRetry = onRefresh)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                view.board.servicios.forEach { servicio ->
                    val color = parseColor(routeColor(view, servicio.painted, servicio.routeIds.firstOrNull()))
                        ?: MaterialTheme.colorScheme.primary
                    Surface(shape = CircleShape, color = color) {
                        Text(servicio.painted, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge,
                            color = if (color.luminance() > 0.179f) Color.Black else Color.White)
                    }
                }
            }
            if (boardCount > 1) {
                val activeDotRequester = remember { BringIntoViewRequester() }
                LaunchedEffect(index) { activeDotRequester.bringIntoView() }
                val pageDescription = stringResource(R.string.board_page_description, index + 1, boardCount)
                Row(Modifier.widthIn(max = 80.dp).horizontalScroll(rememberScrollState(), enabled = false)
                    .semantics { contentDescription = pageDescription }.testTag("board-page-dots"),
                    horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    repeat(boardCount) { dot ->
                        Box(Modifier.width(if (dot == index) 14.dp else 5.dp).height(5.dp)
                            .then(if (dot == index) Modifier.bringIntoViewRequester(activeDotRequester) else Modifier)
                            .background(if (dot == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape))
                    }
                }
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
fun EmptyHome(onAdd: () -> Unit, snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }) {
    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }, topBar = {
        TopAppBar(title = { Text(stringResource(R.string.app_name), style = TransmicroneBrandStyle) })
    }) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(Icons.Default.DirectionsBus, null, Modifier.padding(28.dp).size(48.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.height(28.dp))
            Text(stringResource(R.string.empty_home_headline), style = MaterialTheme.typography.headlineMedium)
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

// Saved route colors remain the same when the live feed omits its color.
internal fun routeColor(view: BoardView, painted: String, routeId: Int?): String? {
    val saved = view.board.servicios.firstOrNull {
        it.painted == painted && (routeId == null || routeId in it.routeIds)
    }?.color
    return saved?.takeIf { parseColor(it) != null }
        ?: view.arrivals.firstOrNull {
            it.painted == painted && parseColor(it.colorRuta) != null
        }?.colorRuta
}
