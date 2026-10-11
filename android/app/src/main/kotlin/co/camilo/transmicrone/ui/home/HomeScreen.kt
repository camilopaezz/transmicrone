package co.camilo.transmicrone.ui.home

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.semantics.clearAndSetSemantics
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
    onSearch: () -> Unit = {},
    onSettings: () -> Unit = {},
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
    if (boards.isEmpty()) { EmptyHome(onAdd, snackbarHostState, onSearch, onSettings); return }
    val index = pageIndex.coerceIn(0, boards.lastIndex)
    val pager = rememberPagerState(initialPage = index, pageCount = { boards.size })
    val visibleIndex = pager.currentPage.coerceIn(boards.indices)
    val current = boards[visibleIndex]
    val scope = rememberCoroutineScope()
    var choosingBoard by remember { mutableStateOf(false) }
    val listStates = boards.map { view -> key(view.board.boardId) { rememberLazyListState() } }
    val currentListState = listStates[visibleIndex]
    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val fontScale = LocalDensity.current.fontScale
    val compactAppBar = fontScale > 1.2f || windowWidth < 400.dp
    val headerGone by remember(currentListState) { derivedStateOf {
        currentListState.firstVisibleItemIndex > 0 ||
            currentListState.layoutInfo.visibleItemsInfo.firstOrNull()?.let { it.offset < -it.size / 2 } == true
    } }
    var swipeHintDismissed by swipeHintDismissal
    val dragging by pager.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragging, pager.isScrollInProgress) {
        if (dragging || pager.isScrollInProgress) swipeHintDismissed = true
    }
    LaunchedEffect(boards.size > 1) {
        if (boards.size > 1) {
            delay(5000)
            swipeHintDismissed = true
        }
    }
    val showSwipeHint = boards.size > 1 && !swipeHintDismissed
    val boardIds = boards.map { it.board.boardId }
    var synchronizingSelection by remember { mutableStateOf(false) }
    LaunchedEffect(index, boardIds) {
        synchronizingSelection = true
        try {
            if (pager.currentPage != index) pager.animateScrollToPage(index)
        } finally {
            synchronizingSelection = false
        }
    }
    val latestOnPage by rememberUpdatedState(onPage)
    val latestIndex by rememberUpdatedState(index)
    LaunchedEffect(pager, boardIds) {
        snapshotFlow { Triple(pager.settledPage, pager.isScrollInProgress, synchronizingSelection) }.collect { (page, scrolling, syncing) ->
            if (!scrolling && !syncing && page in boards.indices && page != latestIndex) latestOnPage(page)
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.app_name), style = TransmicroneBrandStyle,
                            maxLines = if (compactAppBar) 2 else 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (BuildConfig.DEBUG && BuildConfig.MOCK_FEED) {
                            Text(stringResource(R.string.sample_data), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                expandedHeight = if (fontScale >= 2f) 112.dp else if (fontScale > 1.2f) 88.dp else 64.dp,
                navigationIcon = {
                    if (!compactAppBar) Icon(Icons.Default.DirectionsBus, null, Modifier.padding(start = 20.dp, end = 12.dp), tint = MaterialTheme.colorScheme.primary)
                },
                actions = {
                    IconButton(onClick = onAdd, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Add, stringResource(R.string.add_board)) }
                    if (headerGone) IconButton(onClick = { onEdit(current.board.boardId) }) {
                        Icon(Icons.Default.Edit, stringResource(R.string.edit_board))
                    }
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Default.Search, stringResource(R.string.catalog_search))
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, stringResource(R.string.settings_title))
                    }
                },
            )
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).roundedHomeBody()) {
            BoardTitleSelector(boards.map { it.board.displayName }, pager, showSwipeHint) { choosingBoard = true }
            HorizontalPager(state = pager, key = { boards[it].board.boardId }, modifier = Modifier.weight(1f).testTag("board-pager")) { page ->
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
                    modifier = Modifier.fillMaxSize()
                        .then(if (page != visibleIndex) Modifier.clearAndSetSemantics {} else Modifier),
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
                            header = { StationHeader(view, onRefresh, onEdit) },
                            bottomContentPadding = 24.dp,
                            colorForArrival = { arrival -> routeColor(view, arrival.painted, arrival.routeId) },
                        )
                    }

                }
            }
        }
    }
    if (choosingBoard) BoardPickerSheet(
        boards = boards, selectedId = current.board.boardId,
        onDismiss = { choosingBoard = false },
        onSelect = { boardId ->
            choosingBoard = false
            val destination = boards.indexOfFirst { it.board.boardId == boardId }
            if (destination >= 0) scope.launch { pager.animateScrollToPage(destination) }
        },
        onAdd = { choosingBoard = false; onAdd() },
    )
}

@Composable
private fun StationHeader(
    view: BoardView, onRefresh: () -> Unit, onEdit: (String) -> Unit,
) {
    Column(Modifier.padding(horizontal = 4.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
            IconButton(
                onClick = { onEdit(view.board.boardId) },
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            ) {
                Icon(Icons.Default.Edit, stringResource(R.string.edit_board))
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
fun EmptyHome(onAdd: () -> Unit, snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }, onSearch: () -> Unit = {}, onSettings: () -> Unit = {}) {
    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }, topBar = {
        TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), title = { Text(stringResource(R.string.app_name), style = TransmicroneBrandStyle) },
            actions = {
                IconButton(onClick = onSearch) { Icon(Icons.Default.Search, stringResource(R.string.catalog_search)) }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, stringResource(R.string.settings_title)) }
            })
    }) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).roundedHomeBody().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
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

@Composable
private fun Modifier.roundedHomeBody(): Modifier =
    background(MaterialTheme.colorScheme.surfaceContainer)
        .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
        .background(MaterialTheme.colorScheme.surface)

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
