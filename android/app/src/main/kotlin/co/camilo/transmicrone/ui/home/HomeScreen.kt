package co.camilo.transmicrone.ui.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.animation.core.animate
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import co.camilo.transmicrone.ui.common.FloatingSnackbarHost
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import co.camilo.transmicrone.ui.common.FloatingEdgeBand
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.delay
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import co.camilo.transmicrone.R
import co.camilo.transmicrone.BuildConfig
import co.camilo.transmicrone.ui.BoardView
import co.camilo.transmicrone.ui.Freshness
import co.camilo.transmicrone.ui.board.ArrivalList
import co.camilo.transmicrone.ui.board.parseColor
import co.camilo.transmicrone.ui.common.ServiceChipSurface
import co.camilo.transmicrone.ui.common.FloatingHeaderHeight
import co.camilo.transmicrone.ui.common.floatingHeaderPadding
import co.camilo.transmicrone.ui.common.FloatingScaffold
import co.camilo.transmicrone.ui.common.FloatingControlSurface
import co.camilo.transmicrone.ui.common.MorphContent
import co.camilo.transmicrone.ui.common.navQuickEnterExit
import co.camilo.transmicrone.ui.common.relativeTime

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
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
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compactHeight = maxHeight < 480.dp || maxHeight / maxOf(1f, LocalDensity.current.fontScale) < 360.dp
        if (loadingBoards) {
            FloatingScaffold(snackbarHost = { FloatingSnackbarHost(snackbarHostState) }) { insets ->
                Column(Modifier.fillMaxSize().padding(insets), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(20.dp))
                    Text(stringResource(R.string.loading_boards), style = MaterialTheme.typography.bodyLarge)
                }
            }
            return@BoxWithConstraints
        }
        if (boards.isEmpty()) { EmptyHome(onAdd, snackbarHostState, onSearch, onSettings); return@BoxWithConstraints }
        val index = pageIndex.coerceIn(0, boards.lastIndex)
        // Pager callbacks can outlive the composition that supplied them.
        val pagerBoards = remember { mutableStateOf(boards) }
        val pager = rememberPagerState(initialPage = index, pageCount = { pagerBoards.value.size })
        val boardIds = boards.map { it.board.boardId }
        val scrolling = pager.isScrollInProgress
        var settledBoards by remember { mutableStateOf(boards) }
        LaunchedEffect(boards, scrolling) {
            if (!scrolling) settledBoards = boards
        }
        val displayedBoards = if (scrolling && settledBoards.map { it.board.boardId } == boardIds) settledBoards else boards
        pagerBoards.value = displayedBoards
        val visibleIndex = pager.currentPage.coerceIn(displayedBoards.indices)
        val current = displayedBoards[visibleIndex]
        val density = LocalDensity.current
        val safeInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
        val dockMaxWidth = with(density) {
            (LocalWindowInfo.current.containerSize.width - safeInsets.getLeft(density, androidx.compose.ui.platform.LocalLayoutDirection.current)
                - safeInsets.getRight(density, androidx.compose.ui.platform.LocalLayoutDirection.current)).toDp()
        } - 32.dp
        var dockHeight by remember { mutableStateOf(64.dp) }
        val scope = rememberCoroutineScope()
        var choosingBoard by remember { mutableStateOf(false) }
        val listStates by rememberUpdatedState(displayedBoards.associate { view ->
            view.board.boardId to key(view.board.boardId) { rememberLazyListState() }
        })
        val collapseEnabled = rememberDockCollapseEnabled() && !compactHeight
        var dockCollapsed by remember(current.board.boardId, collapseEnabled) { mutableStateOf(false) }
        val currentList = listStates.getValue(current.board.boardId)
        val titleCollapse = remember { TitleCollapse() }
        val titleSpatialSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
        val titleDecay = rememberSplineBasedDecay<Float>()
        LaunchedEffect(current.board.boardId, collapseEnabled) {
            if (!collapseEnabled) titleCollapse.offset = 0f
            else if (!currentList.canScrollBackward) {
                titleCollapse.expansion = currentCoroutineContext()[Job]
                try {
                    animate(titleCollapse.offset, 0f, animationSpec = titleSpatialSpec) { value, _ ->
                        titleCollapse.offset = value.coerceIn(0f, titleCollapse.height)
                    }
                } finally { titleCollapse.expansion = null }
            } else titleCollapse.offset = titleCollapse.height
        }
        val headerBoard by remember(collapseEnabled) { derivedStateOf {
            collapseEnabled && titleCollapse.height > 0f && titleCollapse.offset / titleCollapse.height > .6f
        } }
        val headerEdge by remember(compactHeight, collapseEnabled, currentList) { derivedStateOf {
            !compactHeight && collapseEnabled && titleCollapse.height > 0f &&
                titleCollapse.offset / titleCollapse.height >= .7f && currentList.canScrollBackward
        } }
        val dockExpanded = !collapseEnabled || !dockCollapsed || scrolling || !currentList.canScrollBackward || !currentList.canScrollForward
        val dockThreshold = with(density) { 10.dp.toPx() }
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
        val addLabel = stringResource(R.string.add_board)
        val backdrop = rememberHazeState()
        val spatialSpec = MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntSize>()
        val effectsSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
        val addPadding by animateDpAsState(if (dockExpanded) 16.dp else 12.dp,
            MaterialTheme.motionScheme.defaultSpatialSpec(), label = "addPadding")
        FloatingScaffold(
            backdrop = backdrop,
            topEdgeVisible = { headerEdge },
            bottomEdgeBlur = !compactHeight,
            bottomEdgeVisible = { currentList.canScrollForward },
            bottomEdgeHeight = dockHeight + safeInsets.asPaddingValues().calculateBottomPadding() + 16.dp,
            snackbarHost = { FloatingSnackbarHost(snackbarHostState) },
            topBar = { FloatingHomeHeader(onSearch, onSettings, compactHeight, onAdd, { onEdit(current.board.boardId) },
                if (headerBoard) current.board.displayName else null, visibleIndex, displayedBoards.size) { choosingBoard = true } },
            floatingActionButtonPosition = FabPosition.Center,
            floatingActionButton = {
                if (!compactHeight) FloatingControlSurface {
                    Row(Modifier.widthIn(max = dockMaxWidth).onSizeChanged { dockHeight = with(density) { it.height.toDp() } }.animateContentSize(spatialSpec).padding(6.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = { onEdit(current.board.boardId) }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.Edit, stringResource(R.string.edit_board))
                        }
                        Button(onClick = onAdd, contentPadding = PaddingValues(horizontal = addPadding, vertical = 8.dp),
                            modifier = Modifier.weight(1f, fill = false).widthIn(min = 48.dp).heightIn(min = 48.dp)
                            .semantics { contentDescription = addLabel }) {
                            Icon(Icons.Default.Add, null)
                            AnimatedVisibility(dockExpanded,
                                enter = expandHorizontally(spatialSpec, expandFrom = Alignment.Start) + fadeIn(effectsSpec),
                                exit = shrinkHorizontally(spatialSpec, shrinkTowards = Alignment.Start) + fadeOut(effectsSpec)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(addLabel)
                                }
                            }
                        }
                    }
                }
            },
        ) { insets ->
            val contentTop = { if (compactHeight) 0 else with(density) { insets.calculateTopPadding().roundToPx() } +
                (titleCollapse.height - titleCollapse.offset).roundToInt().coerceAtLeast(0) }
            val contentInsets = if (compactHeight) insets else PaddingValues(
                start = insets.calculateStartPadding(androidx.compose.ui.platform.LocalLayoutDirection.current),
                end = insets.calculateEndPadding(androidx.compose.ui.platform.LocalLayoutDirection.current),
                bottom = insets.calculateBottomPadding(),
            )
            Box(Modifier.fillMaxSize().padding(contentInsets)) {
                if (!compactHeight) Box(Modifier.zIndex(1f).padding(top = insets.calculateTopPadding()).collapsingTitle(titleCollapse)) {
                    BoardTitleSelector(displayedBoards.map { it.board.displayName }, pager, showSwipeHint,
                        collapseFraction = { titleCollapse.fraction }, collapseOffset = { titleCollapse.offset }) { choosingBoard = true }
                }
                // Prepare neighbors before a tap starts moving the pager.
                HorizontalPager(
                    state = pager, beyondViewportPageCount = 1, key = { pagerBoards.value.getOrNull(it)?.board?.boardId ?: it },
                    modifier = Modifier.fillMaxSize().testTag("board-pager"),
                ) { page ->
                    val view = pagerBoards.value.getOrNull(page) ?: return@HorizontalPager
                    val listState = listStates[view.board.boardId] ?: return@HorizontalPager
                    val empty = when (val freshness = view.freshness) {
                        Freshness.Idle, Freshness.Loading -> stringResource(R.string.freshness_updating)
                        is Freshness.Cached -> stringResource(R.string.freshness_stale, relativeTime(freshness.cachedAt))
                        is Freshness.UpdatingCached -> stringResource(
                            if (freshness.quiet) R.string.arrivals_empty else R.string.freshness_updating,
                        )
                        is Freshness.Failed, is Freshness.FailedCached -> stringResource(R.string.freshness_failed)
                        Freshness.Unsupported -> stringResource(R.string.freshness_unsupported)
                        is Freshness.Empty, is Freshness.Updated -> stringResource(R.string.arrivals_empty)
                    }
                    val refreshing = view.freshness == Freshness.Loading || view.freshness is Freshness.UpdatingCached
                    val quietRefresh = (view.freshness as? Freshness.UpdatingCached)?.quiet == true
                    val pullState = rememberPullToRefreshState()
                    // The edge reads only this list, never the enclosing scaffold source or its own output.
                    val arrivalsBackdrop = rememberHazeState()
                    val hasScrolled by remember(listState) { derivedStateOf { listState.canScrollBackward } }
                    val fade by animateFloatAsState(if (hasScrolled) 1f else 0f, label = "listTopFade")
                    var pullStartedAtTop by remember { mutableStateOf(false) }
                    val scrollReturnGuard = remember(listState) {
                        object : NestedScrollConnection {
                            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                                // This runs synchronously, before the parent's pull handler. Do not
                                // let excess return-to-top movement become a pull while it recomposes.
                                return if (!pullStartedAtTop && available.y > 0f) Offset(0f, available.y) else Offset.Zero
                            }
                        }
                    }
                    val dockScroll = remember(listState, visibleIndex, scrolling, collapseEnabled) {
                        object : NestedScrollConnection {
                            var distance = 0f
                            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                                if (page != visibleIndex || scrolling || !collapseEnabled) return Offset.Zero
                                val delta = consumed.y
                                if (!listState.canScrollBackward || !listState.canScrollForward) {
                                    distance = 0f
                                    dockCollapsed = false
                                } else if (delta != 0f) {
                                    if (distance * delta < 0f) distance = 0f
                                    distance += delta
                                    if (kotlin.math.abs(distance) >= dockThreshold) {
                                        dockCollapsed = distance < 0f
                                        distance = 0f
                                    }
                                }
                                return Offset.Zero
                            }
                        }
                    }
                    val titleScroll = remember(listState, visibleIndex, collapseEnabled) {
                        object : NestedScrollConnection {
                            private val active get() = page == visibleIndex && collapseEnabled
                            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                                if (active && available.y < 0f && (listState.canScrollForward || !titleCollapse.expanded))
                                    Offset(0f, titleCollapse.consume(available.y)) else Offset.Zero
                            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                                if (active && available.y > 0f) Offset(0f, titleCollapse.consume(available.y)) else Offset.Zero
                            override suspend fun onPreFling(available: Velocity): Velocity =
                                if (active && available.y < 0f && !titleCollapse.collapsed && (listState.canScrollForward || !titleCollapse.expanded))
                                    Velocity(0f, titleCollapse.fling(available.y, titleDecay)) else Velocity.Zero
                            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                                if (active && available.y > 0f && !titleCollapse.expanded)
                                    Velocity(0f, titleCollapse.fling(available.y, titleDecay)) else Velocity.Zero
                        }
                    }
                    Box(
                        modifier = Modifier.fillMaxSize().nestedScroll(dockScroll)
                            .pointerInput(listState) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    // Latch for the whole gesture: reaching the top while scrolling
                                    // must not turn the remainder of that drag into a refresh.
                                    pullStartedAtTop = !listState.canScrollBackward && (compactHeight || titleCollapse.expanded)
                                    do {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                    } while (event.changes.any { it.pressed })
                                }
                            }
                            .pullToRefresh(
                                isRefreshing = false, state = pullState,
                                // The synchronous nested-scroll guard owns gesture eligibility.
                                // Toggling this through recomposition can drop the first fresh pull.
                                enabled = page == visibleIndex,
                                onRefresh = { if (pullStartedAtTop) onRefresh() },
                            )
                            .nestedScroll(scrollReturnGuard)
                            .then(if (page != visibleIndex) Modifier.clearAndSetSemantics {} else Modifier),
                    ) {
                        // Request feedback owns the gap until the fetch completes.
                        // A new drag must not replace it with gesture-distance progress.
                        val showRequest = refreshing && !quietRefresh && page == visibleIndex
                        val pulling = (!refreshing || quietRefresh) && pullStartedAtTop && pullState.distanceFraction > 0f
                        val trackPull = pulling && !showRequest
                        val pullFraction = pullState.distanceFraction.coerceIn(0f, 1f)
                        val revealed = 48.dp + 12.dp * 2
                        val gap by animateDpAsState(
                            when {
                                showRequest -> revealed
                                trackPull -> revealed * pullFraction
                                else -> 0.dp
                            },
                            if (trackPull) snap() else MaterialTheme.motionScheme.defaultSpatialSpec(),
                            label = "refreshGap",
                        )
                        val capsuleFade by animateFloatAsState(
                            when {
                                showRequest -> 1f
                                trackPull -> pullFraction
                                else -> 0f
                            },
                            if (trackPull || showRequest) snap() else MaterialTheme.motionScheme.fastEffectsSpec(),
                            label = "refreshFade",
                        )
                        Box(Modifier.fillMaxSize().clipToBounds()) {
                            Box(Modifier.fillMaxSize().clipToBounds().drawWithContent {
                                // Keep the expanded title clear; the full list is exposed once it exits.
                                clipRect(top = if (compactHeight || titleCollapse.collapsed) 0f else contentTop().toFloat()) {
                                    this@drawWithContent.drawContent()
                                }
                            }) {
                                ArrivalList(
                                    view.arrivals, empty,
                                    (if (compactHeight) Modifier.fillMaxSize().testTag("arrival-viewport")
                                        else Modifier.arrivalsViewport(contentTop))
                                        .offset { IntOffset(0, gap.roundToPx()) }
                                        .hazeSource(arrivalsBackdrop)
                                        .nestedScroll(titleScroll),
                                    state = listState,
                                    header = {
                                        if (!compactHeight) Spacer(Modifier.fillMaxWidth().layout { _, constraints ->
                                            layout(constraints.maxWidth, contentTop()) {}
                                        })
                                        // Compact headers hold add/edit instead of the brand, so sample data is marked here.
                                        if (compactHeight && BuildConfig.DEBUG && BuildConfig.MOCK_FEED) Text(stringResource(R.string.sample_data),
                                            Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        if (compactHeight) BoardTitleSelector(displayedBoards.map { it.board.displayName }, pager, showSwipeHint) { choosingBoard = true }
                                        StationHeader(view)
                                    },
                                    // The refresh gap pushes the list down, so the end needs the same extra room.
                                    bottomContentPadding = (if (compactHeight) 24.dp else dockHeight + 48.dp) + gap,
                                    colorForArrival = { arrival -> routeColor(view, arrival.painted, arrival.routeId) },
                                    active = page == visibleIndex && !scrolling,
                                    initialLoading = view.cache == null && view.freshness == Freshness.Loading,
                                )
                                if (hasScrolled && page == visibleIndex && !headerEdge) FloatingEdgeBand(
                                    arrivalsBackdrop, true, fade, 28.dp,
                                    Modifier.align(Alignment.TopCenter).offset { IntOffset(0, contentTop() + gap.roundToPx()) },
                                    "arrival-edge-top",
                                )
                            }
                            // The list slides inside its viewport, so a pull that starts in the
                            // revealed gap still has to reach the existing pull handler.
                            if (gap > 0.dp) Box(
                                Modifier.align(Alignment.TopCenter).offset { IntOffset(0, contentTop()) }.fillMaxWidth().height(gap)
                                    .scrollable(rememberScrollableState { 0f }, Orientation.Vertical),
                            )
                            if (gap > 0.dp) RefreshCapsule(
                                showRequest || !trackPull, { pullState.distanceFraction.coerceIn(0f, 1f) },
                                gap, capsuleFade, Modifier.align(Alignment.TopCenter).offset { IntOffset(0, contentTop()) },
                            )
                        }
                    }
                }
            }
        }
        if (choosingBoard) BoardPickerSheet(
            backdrop = backdrop, boards = boards, selectedId = current.board.boardId,
            onDismiss = { choosingBoard = false },
            onSelect = { boardId ->
                choosingBoard = false
                val destination = boards.indexOfFirst { it.board.boardId == boardId }
                if (destination >= 0) scope.launch { pager.animateScrollToPage(destination) }
            },
            onAdd = { choosingBoard = false; onAdd() },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RefreshCapsule(
    requesting: Boolean, pullFraction: () -> Float, gap: Dp, fade: Float, modifier: Modifier = Modifier,
) {
    val indicator = Modifier.size(36.dp).testTag("refresh-capsule")
    FloatingControlSurface(
        modifier.offset(y = (gap - 48.dp) / 2).alpha(fade).size(48.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (requesting) LoadingIndicator(indicator)
            else LoadingIndicator(progress = pullFraction, modifier = indicator)
        }
    }
}

@Composable
private fun StationHeader(view: BoardView) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 12.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        view.board.servicios.forEach { servicio ->
            val color = parseColor(routeColor(view, servicio.painted, servicio.routeIds.firstOrNull()))
                ?: MaterialTheme.colorScheme.primary
            ServiceChipSurface(color) {
                Text(servicio.painted, Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmptyHome(onAdd: () -> Unit, snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }, onSearch: () -> Unit = {}, onSettings: () -> Unit = {}) {
    FloatingScaffold(snackbarHost = { FloatingSnackbarHost(snackbarHostState) }, topBar = {
        FloatingHomeHeader(onSearch, onSettings)
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FloatingHomeHeader(
    onSearch: () -> Unit, onSettings: () -> Unit,
    compact: Boolean = false, onAdd: () -> Unit = {}, onEdit: () -> Unit = {},
    boardName: String? = null, page: Int = 0, count: Int = 1, onChoose: () -> Unit = {},
) {
    val chooseLabel = stringResource(R.string.choose_board)
    val pageLabel = stringResource(R.string.board_page_description, page + 1, count)
    val label = boardName?.let { it + if (count > 1) stringResource(R.string.board_header_page, page + 1, count) else "" }
        ?: stringResource(R.string.app_name)
    // Wrap at large font scales rather than shrink touch targets.
    BoxWithConstraints(Modifier.floatingHeaderPadding()) {
    val capsuleWidth = if (LocalDensity.current.fontScale > 1.3f) maxWidth
        else (maxWidth - FloatingHeaderHeight * 2 - 12.dp).coerceAtLeast(48.dp)
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (compact) FloatingControlSurface {
            Row {
                IconButton(onClick = onAdd, modifier = Modifier.size(FloatingHeaderHeight)) {
                    Icon(Icons.Default.Add, stringResource(R.string.add_board))
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(FloatingHeaderHeight)) {
                    Icon(Icons.Default.Edit, stringResource(R.string.edit_board))
                }
            }
        } else FloatingControlSurface(Modifier.navQuickEnterExit()
            .then(if (boardName != null) Modifier.widthIn(max = capsuleWidth) else Modifier)
            .then(if (boardName != null) Modifier.clickable(onClickLabel = chooseLabel, onClick = onChoose)
                .semantics { contentDescription = "$chooseLabel, $boardName, $pageLabel" } else Modifier)
            .testTag("home-title-capsule")) {
            Column(Modifier.heightIn(min = FloatingHeaderHeight).padding(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.Center) {
                Crossfade(label, animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(), label = "homeHeaderTitle") { text ->
                    Text(text, style = MaterialTheme.typography.titleMedium, maxLines = if (boardName != null) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
                }
                if (BuildConfig.DEBUG && BuildConfig.MOCK_FEED) {
                    Text(stringResource(R.string.sample_data), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        FloatingControlSurface(sharedKey = "search-capsule") {
            MorphContent {
                Row {
                    IconButton(onClick = onSearch, modifier = Modifier.size(FloatingHeaderHeight)) {
                        Icon(Icons.Default.Search, stringResource(R.string.catalog_search))
                    }
                    IconButton(onClick = onSettings, modifier = Modifier.size(FloatingHeaderHeight)) {
                        Icon(Icons.Default.Settings, stringResource(R.string.settings_title))
                    }
                }
            }
        }
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
