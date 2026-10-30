package co.camilo.transmicrone

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.swipeDown
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import co.camilo.transmicrone.domain.Arrival
import co.camilo.transmicrone.domain.Board
import co.camilo.transmicrone.domain.CachedService
import co.camilo.transmicrone.domain.ServiceArrivals
import co.camilo.transmicrone.domain.ServiceStatus
import co.camilo.transmicrone.domain.serviceKey
import co.camilo.transmicrone.domain.FailureKind
import co.camilo.transmicrone.domain.Occupancy
import co.camilo.transmicrone.domain.PlaceKind
import co.camilo.transmicrone.domain.Place
import co.camilo.transmicrone.domain.ServicioOption
import co.camilo.transmicrone.domain.SelectedServicio
import co.camilo.transmicrone.ui.BoardView
import co.camilo.transmicrone.ui.Freshness
import co.camilo.transmicrone.ui.home.HomeScreen
import co.camilo.transmicrone.ui.setup.SetupScreen
import co.camilo.transmicrone.ui.theme.TransmicroneTheme
import co.camilo.transmicrone.ui.key
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class HomeUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun failedRequestHasExplanationAndRetryThenClearsOnRecovery() {
        val freshness = mutableStateOf<Freshness>(Freshness.Failed(FailureKind.TIMEOUT))
        var retries = 0
        compose.setContent {
            TransmicroneTheme {
                HomeScreen(
                    boards = listOf(BoardView(sampleBoard(), freshness = freshness.value)),
                    pageIndex = 0, onPage = {}, onAdd = {}, onEdit = {},
                    onRefresh = { retries++; freshness.value = Freshness.Loading },
                )
            }
        }
        compose.onNodeWithText("No se pudo actualizar").assertIsDisplayed()
        compose.onNodeWithText("El servicio de llegadas tardó demasiado en responder. Vuelve a intentar.").assertDoesNotExist()
        compose.onNodeWithContentDescription("Ver detalles del error").performClick()
        compose.onNodeWithText("El servicio de llegadas tardó demasiado en responder. Vuelve a intentar.").assertIsDisplayed()
        compose.onNodeWithText("Cerrar").performClick()
        compose.onNodeWithText("No hay servicios acercándose de los que elegiste.").assertDoesNotExist()
        compose.onNodeWithText("Reintentar").performClick()
        compose.runOnIdle { assertTrue(retries == 1) }
        compose.onNodeWithTag("retry-progress").assertIsDisplayed()
        compose.onNodeWithTag("refresh-capsule").assertDoesNotExist()
        compose.runOnIdle { freshness.value = Freshness.Empty(Instant.now()) }
        compose.onNodeWithTag("request-failure-notice").assertDoesNotExist()
        compose.onNodeWithText("No hay servicios acercándose de los que elegiste.").assertIsDisplayed()
        compose.onNodeWithText("No se pudieron consultar las llegadas").assertDoesNotExist()
    }

    @Test
    fun recoveredServiceClearsFailureWhileSiblingRemainsPending() {
        val first = sampleBoard().servicios.single()
        val second = SelectedServicio("S48", "Soacha", listOf(2))
        val board = sampleBoard().copy(servicios = listOf(first, second))
        val old = Instant.now().minusSeconds(240)
        val previous = CachedService(first.serviceKey(), listOf(sampleArrival()), old)
        val view = mutableStateOf(BoardView(
            board, previous.arrivals, Freshness.FailedCached(old, FailureKind.TIMEOUT),
            services = listOf(ServiceArrivals(first, previous, ServiceStatus.Failed(FailureKind.TIMEOUT)),
                ServiceArrivals(second, null, ServiceStatus.Empty)),
            unresolvedFailures = mapOf(first.serviceKey() to FailureKind.TIMEOUT),
        ))
        compose.setContent {
            TransmicroneTheme { HomeScreen(listOf(view.value), 0, {}, {}, {}, {}) }
        }
        compose.onNodeWithTag("request-failure-notice").assertIsDisplayed()
        compose.runOnIdle {
            view.value = view.value.copy(freshness = Freshness.UpdatingCached(old), services = listOf(
                ServiceArrivals(first, previous, ServiceStatus.Pending),
                ServiceArrivals(second, null, ServiceStatus.Pending),
            ))
        }
        compose.onNodeWithTag("retry-progress").assertIsDisplayed()
        compose.runOnIdle {
            val recovered = previous.copy(arrivals = listOf(sampleArrival().copy(etaPhrase = "Entre 5 y 7 min")), fetchedAt = Instant.now())
            view.value = view.value.copy(arrivals = recovered.arrivals, services = listOf(
                ServiceArrivals(first, recovered, ServiceStatus.Updated),
                ServiceArrivals(second, null, ServiceStatus.Pending),
            ), unresolvedFailures = emptyMap())
        }
        compose.onNodeWithTag("request-failure-notice").assertDoesNotExist()
        compose.onNodeWithText("Entre 5 y 7 min", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun retryFromDetailsKeepsCachedCardsAndNoticeInPlace() {
        val now = Instant.now().minusSeconds(240)
        val freshness = mutableStateOf<Freshness>(Freshness.FailedCached(now, FailureKind.TIMEOUT))
        var retries = 0
        compose.setContent {
            TransmicroneTheme {
                HomeScreen(
                    boards = listOf(BoardView(sampleBoard(), listOf(sampleArrival()), freshness.value)),
                    pageIndex = 0, onPage = {}, onAdd = {}, onEdit = {},
                    onRefresh = { retries++; freshness.value = Freshness.UpdatingCached(now) },
                )
            }
        }
        val cardTop = compose.onNodeWithText("CAD", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithContentDescription("Ver detalles del error").performClick()
        compose.onNodeWithText("Última consulta correcta:", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("Reintentar")[1].performClick()
        compose.runOnIdle { assertTrue(retries == 1) }
        compose.onNodeWithText("Cerrar").assertDoesNotExist()
        compose.onNodeWithTag("retry-progress").assertIsDisplayed()
        compose.onNodeWithTag("refresh-capsule").assertDoesNotExist()
        val duringRetry = compose.onNodeWithText("CAD", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
        assertTrue("Retry progress must not move cached arrivals", kotlin.math.abs(duringRetry - cardTop) < 1f)
        compose.runOnIdle { freshness.value = Freshness.FailedCached(now, FailureKind.TIMEOUT) }
        compose.onNodeWithTag("retry-progress").assertDoesNotExist()
        compose.onNodeWithText("Reintentar").assertIsEnabled()
    }

    @Test
    fun retryKeepsNoticeHeightWithLargeText() {
        val now = Instant.now().minusSeconds(240)
        val freshness = mutableStateOf<Freshness>(Freshness.FailedCached(now, FailureKind.TIMEOUT))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                TransmicroneTheme {
                    HomeScreen(listOf(BoardView(sampleBoard(), listOf(sampleArrival()), freshness.value)),
                        0, {}, {}, {}, { freshness.value = Freshness.UpdatingCached(now) })
                }
            }
        }
        val before = compose.onNodeWithTag("request-failure-notice").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithText("Reintentar").performClick()
        compose.onNodeWithTag("retry-progress").assertIsDisplayed()
        val during = compose.onNodeWithTag("request-failure-notice").fetchSemanticsNode().boundsInRoot.height
        assertTrue("Wrapped error text must keep its height during retry", kotlin.math.abs(before - during) < 1f)
    }

    @Test
    fun finishedRequestStopsDrawingCapsuleBeforeGapFinishesClosing() {
        val freshness = mutableStateOf<Freshness>(Freshness.Loading)
        compose.setContent {
            TransmicroneTheme {
                HomeScreen(listOf(BoardView(sampleBoard(), listOf(sampleArrival()), freshness.value)),
                    0, {}, {}, {}, {})
            }
        }
        compose.onNodeWithTag("refresh-capsule").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { freshness.value = Freshness.Failed(FailureKind.TIMEOUT) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("refresh-capsule").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun partialFailureNamesFailedServiceWithoutMarkingFreshArrivalsAsOld() {
        val board = sampleBoard()
        val first = board.servicios.first()
        val second = SelectedServicio("S48", "Soacha", listOf(2))
        val now = Instant.now()
        compose.setContent {
            TransmicroneTheme {
                HomeScreen(
                    boards = listOf(BoardView(
                        board.copy(servicios = listOf(first, second)), listOf(sampleArrival()),
                        Freshness.FailedCached(now, FailureKind.TRANSPORT),
                        services = listOf(
                            ServiceArrivals(first, CachedService(first.serviceKey(), listOf(sampleArrival()), now), ServiceStatus.Updated),
                            ServiceArrivals(second, null, ServiceStatus.Failed(FailureKind.TRANSPORT)),
                        ),
                    )),
                    pageIndex = 0, onPage = {}, onAdd = {}, onEdit = {}, onRefresh = {},
                )
            }
        }
        compose.onNodeWithText("Actualización incompleta").assertIsDisplayed()
        compose.onNodeWithText("Servicios sin actualizar").assertDoesNotExist()
        compose.onNodeWithContentDescription("Ver detalles del error").performClick()
        compose.onNodeWithText("Servicios sin actualizar").assertIsDisplayed()
        compose.onNodeWithText("Soacha").assertIsDisplayed()
        compose.onNodeWithText("Sin consulta correcta anterior").assertIsDisplayed()
        compose.onNodeWithText("Las llegadas anteriores siguen visibles y pueden haber cambiado.").assertDoesNotExist()
        compose.onNodeWithText("Cerrar").performClick()
        compose.onNodeWithText("Reintentar").assertIsDisplayed()
    }

    @Test
    fun requestProgressStaysIndeterminateDuringAnotherDrag() {
        val freshness = mutableStateOf<Freshness>(Freshness.Loading)
        compose.setContent {
            TransmicroneTheme {
                HomeScreen(
                    boards = listOf(BoardView(sampleBoard(), listOf(sampleArrival()), freshness.value)),
                    pageIndex = 0, onPage = {}, onAdd = {}, onEdit = {}, onRefresh = {},
                )
            }
        }
        val requestProgress = SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate,
        )
        compose.onNode(requestProgress).assertIsDisplayed()
        val list = compose.onNodeWithTag("arrival-viewport")
        list.performTouchInput {
            down(center)
            moveTo(center.copy(y = height.toFloat() - 1f), delayMillis = 200)
        }
        compose.onNode(requestProgress).assertIsDisplayed()
        list.performTouchInput { up() }
        compose.onNode(requestProgress).assertIsDisplayed()
        compose.runOnIdle { freshness.value = Freshness.Updated(Instant.now()) }
        compose.onNode(requestProgress).assertDoesNotExist()
    }

    @Test
    fun refreshCapsuleAppearsForRequestAndHidesWhenIdle() {
        val freshness = mutableStateOf<Freshness>(Freshness.Updated(Instant.now()))
        compose.setContent {
            TransmicroneTheme {
                HomeScreen(
                    boards = listOf(BoardView(sampleBoard(), listOf(sampleArrival()), freshness.value)),
                    pageIndex = 0, onPage = {}, onAdd = {}, onEdit = {}, onRefresh = {},
                )
            }
        }
        compose.onNodeWithTag("refresh-capsule").assertDoesNotExist()
        val idleCardTop = compose.onNodeWithText("CAD", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { freshness.value = Freshness.Loading }
        val capsule = compose.onNode(
            hasTestTag("refresh-capsule") and SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate,
            ),
        ).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val cardTop = compose.onNodeWithText("CAD", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
        val minDrop = with(compose.density) { 60.dp.toPx() }
        assertTrue("The first arrival must slide down to reveal the capsule", cardTop - idleCardTop >= minDrop)
        val titleBottom = compose.onNodeWithTag("board-title-0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("The capsule must sit fully below the board title", capsule.top >= titleBottom)
    }

    @Test
    fun scrollingBackToTopDoesNotRefreshUntilANewPull() {
        var refreshes = 0
        compose.setContent {
            TransmicroneTheme {
                HomeScreen(
                    boards = listOf(BoardView(sampleBoard(), List(12) { sampleArrival().copy(nodo = it) })),
                    pageIndex = 0, onPage = {}, onAdd = {}, onEdit = {},
                    onRefresh = { refreshes++ },
                )
            }
        }
        val list = compose.onNodeWithTag("arrival-viewport")
        list.performScrollToIndex(1)
        list.performTouchInput { swipeDown() }
        compose.runOnIdle { assertTrue("Scrolling back to the top must not request refresh", refreshes == 0) }
        list.performTouchInput { swipeDown() }
        compose.runOnIdle { assertTrue("A new pull starting at the top must refresh", refreshes == 1) }
    }

    @Test
    fun fastReturnToTopRequiresAnotherGestureToRefresh() {
        var refreshes = 0
        compose.setContent {
            TransmicroneTheme {
                HomeScreen(
                    boards = listOf(BoardView(sampleBoard(), List(20) { sampleArrival().copy(nodo = it) })),
                    pageIndex = 0, onPage = {}, onAdd = {}, onEdit = {},
                    onRefresh = { refreshes++ },
                )
            }
        }
        val list = compose.onNodeWithTag("arrival-viewport")
        // First arm refresh with a gesture at the top, as in normal use.
        list.performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.runOnIdle { refreshes = 0 }
        repeat(3) {
            list.performScrollToIndex(1)
            list.performTouchInput { swipeDown(durationMillis = 32) }
            compose.waitForIdle()
        }
        compose.runOnIdle { assertTrue("A fast scroll reaching the top must never refresh", refreshes == 0) }
        list.performTouchInput { swipeDown() }
        compose.runOnIdle { assertTrue("Only a separate pull from the settled top refreshes", refreshes == 1) }
    }

    @Test
    fun firstPullImmediatelyAfterReturningToTopRefreshes() {
        var refreshes = 0
        compose.setContent {
            TransmicroneTheme {
                HomeScreen(
                    boards = listOf(BoardView(sampleBoard(), List(20) { sampleArrival().copy(nodo = it) })),
                    pageIndex = 0, onPage = {}, onAdd = {}, onEdit = {},
                    onRefresh = { refreshes++ },
                )
            }
        }
        val list = compose.onNodeWithTag("arrival-viewport")
        list.performScrollToIndex(1)
        // No waitForIdle between gestures: the first fresh pull must work even
        // while the scroll/overscroll from returning to the top is settling.
        list.performTouchInput {
            swipeDown(durationMillis = 64)
            advanceEventTime(16)
            swipeDown()
        }
        compose.runOnIdle { assertTrue("The first fresh pull at the top must refresh exactly once", refreshes == 1) }
    }

    @Test
    fun pullDownIsAcceptedRepeatedlyWhileFetchIsRunning() {
        var refreshes = 0
        compose.setContent {
            TransmicroneTheme {
                HomeScreen(
                    boards = listOf(BoardView(board = sampleBoard(), freshness = Freshness.Loading)),
                    pageIndex = 0, onPage = {}, onAdd = {}, onEdit = {},
                    onRefresh = { refreshes++ },
                )
            }
        }
        repeat(2) {
            compose.onNodeWithTag("arrival-viewport").performTouchInput { swipeDown() }
            compose.waitForIdle()
        }
        compose.runOnIdle { assertTrue("Each pull must reach the refresh handler while fetching", refreshes == 2) }
    }

    @Test
    fun pullDownRetriesAfterFailedFetchWithoutCachedArrivals() {
        var refreshes = 0
        compose.setContent {
            TransmicroneTheme {
                HomeScreen(
                    boards = listOf(BoardView(board = sampleBoard(), freshness = Freshness.Failed(FailureKind.TIMEOUT))),
                    pageIndex = 0, onPage = {}, onAdd = {}, onEdit = {},
                    onRefresh = { refreshes++ },
                )
            }
        }
        compose.onNodeWithTag("arrival-viewport").performTouchInput { swipeDown() }
        compose.runOnIdle { assertTrue("Pulling down after a failure must request a retry", refreshes == 1) }
    }

    @Test
    fun oldBusReportDoesNotClaimArrivingAfterFreshFetch() {
        val now = Instant.parse("2026-10-02T13:19:03Z")
        compose.setContent {
            TransmicroneTheme {
                co.camilo.transmicrone.ui.board.ArrivalRow(sampleArrival().copy(
                    etaPhrase = "Servicio llegando",
                    identity = co.camilo.transmicrone.domain.ArrivalIdentity.VEHICLE,
                    vehicleId = "41405", label = "T10405", distanceLabel = "2594 m",
                    sourceReportedAt = now.minusSeconds(180),
                ), now)
            }
        }
        compose.onNodeWithText("Reporte antiguo", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText("Reporte 08:16:03", substring = true, useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText("Bus T10405 · 2594 m", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Última estimación: Servicio llegando", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun emptyHomeShowsAddBoard() {
        compose.setContent {
            TransmicroneTheme {
                HomeScreen(
                    boards = emptyList(),
                    pageIndex = 0,
                    onPage = {},
                    onAdd = {},
                    onEdit = {},
                    onRefresh = {},
                )
            }
        }
        compose.onNodeWithText("Sin tableros").assertIsDisplayed()
        compose.onNodeWithText("Agregar tablero").assertIsDisplayed()
    }

    @Test
    fun loadingSavedBoardsDoesNotFlashTheEmptyState() {
        compose.setContent {
            TransmicroneTheme {
                HomeScreen(emptyList(), 0, {}, {}, {}, {}, loadingBoards = true)
            }
        }
        compose.onNodeWithText("Cargando tus tableros…").assertIsDisplayed()
        compose.onAllNodesWithText("Sin tableros").assertCountEquals(0)
        compose.onAllNodesWithText("Agregar tablero").assertCountEquals(0)
    }

    @Test
    fun boardShowsPhraseNotCountdownAndUnknownOccupancy() {
        val board = Board(
            boardId = "1",
            placeId = "estacion:TM0101",
            kind = PlaceKind.ESTACION,
            queryParadero = "TM0101",
            displayName = "León XIII",
            disambiguator = "Soacha",
            servicios = listOf(SelectedServicio("E48", "CAD", listOf(13266))),
        )
        val arrival = Arrival(
            painted = "E48",
            destino = "CAD",
            routeId = 13266,
            nodo = 62282,
            etaPhrase = "Entre 2 y 4 min",
            distanceLabel = "200 mts",
            occupancy = Occupancy.UNKNOWN,
            colorRuta = "#AE6B00",
        )
        compose.setContent {
            TransmicroneTheme {
                HomeScreen(
                    boards = listOf(
                        BoardView(
                            board = board,
                            arrivals = listOf(arrival),
                            freshness = Freshness.Updated(Instant.parse("2026-10-02T05:00:00Z")),
                        ),
                    ),
                    pageIndex = 0,
                    onPage = {},
                    onAdd = {},
                    onEdit = {},
                    onRefresh = {},
                )
            }
        }
        compose.onAllNodesWithText("León XIII")[0].assertIsDisplayed()
        compose.onAllNodesWithText("Soacha").assertCountEquals(0)
        compose.onAllNodesWithText("E48", useUnmergedTree = true)[0].assertIsDisplayed()
        compose.onNodeWithText("CAD", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Entre 2 y 4 min", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Ocupación desconocida", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText("Tableros").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Editar tablero")[0].assertIsDisplayed()
        val rowName = compose.onAllNodesWithContentDescription(
            "E48 a CAD, Entre 2 y 4 min, 200 m, Ocupación desconocida",
            substring = true,
        ).fetchSemanticsNodes()
        assertTrue(rowName.isNotEmpty())
    }

    @Test
    fun failedRefreshKeepsArrivalsAndOffersRetry() {
        compose.setContent {
            TransmicroneTheme {
                HomeScreen(
                    boards = listOf(
                        BoardView(
                            board = sampleBoard(),
                            arrivals = listOf(sampleArrival()),
                            freshness = Freshness.FailedCached(
                                Instant.parse("2026-10-02T05:00:00Z"),
                                FailureKind.HOST_REJECTED,
                            ),
                        ),
                    ),
                    pageIndex = 0,
                    onPage = {},
                    onAdd = {},
                    onEdit = {},
                    onRefresh = {},
                )
            }
        }
        compose.onNodeWithText("No se pudo actualizar").assertIsDisplayed()
        compose.onNodeWithText("Guardados hace", substring = true).assertIsDisplayed()
        val notice = compose.onNodeWithTag("request-failure-notice").fetchSemanticsNode().boundsInRoot
        assertTrue("Failure feedback must leave room for arrivals", notice.height <= with(compose.density) { 80.dp.toPx() })
        compose.onNodeWithText("Las llegadas anteriores siguen visibles y pueden haber cambiado.").assertDoesNotExist()
        compose.onNodeWithText("CAD", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Reintentar").assertIsDisplayed()
    }

    @Test
    fun updatingCachedDoesNotShowAStatusLine() {
        compose.setContent {
            TransmicroneTheme {
                HomeScreen(
                    boards = listOf(
                        BoardView(
                            board = sampleBoard(),
                            arrivals = listOf(sampleArrival()),
                            freshness = Freshness.UpdatingCached(Instant.parse("2026-10-02T05:00:00Z")),
                        ),
                    ),
                    pageIndex = 0,
                    onPage = {},
                    onAdd = {},
                    onEdit = {},
                    onRefresh = {},
                )
            }
        }
        compose.onAllNodesWithText("Mostrando datos anteriores", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Actualizado", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("el servidor rechazó la consulta", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("No se pudo actualizar", substring = true).assertCountEquals(0)
    }

    @Test
    fun setupDoesNotClaimEmptyWhileCatalogLoads() {
        compose.setContent {
            TransmicroneTheme {
                SetupScreen(
                    query = "leon",
                    results = emptyList(),
                    place = null,
                    selected = emptySet(),
                    catalogReady = false,
                    searching = true,
                    onQuery = {},
                    onPick = {},
                    onToggle = {},
                    onSave = {},
                    onBack = {},
                )
            }
        }
        compose.onNodeWithText("Cargando catálogo…").assertIsDisplayed()
        compose.onAllNodesWithText("No hay lugares con ese nombre en el catálogo.").assertCountEquals(0)
    }

    @Test
    fun serviceSelectionEnablesSaveAndSavesTheSelectedService() {
        val option = ServicioOption("E48", "CAD", listOf(13266), null)
        val place = Place("estacion:TM0101", PlaceKind.ESTACION, "León XIII", "leon xiii", "Soacha", "TM0101", listOf(option))
        val selected = mutableStateOf(emptySet<String>())
        var saved = false
        compose.setContent {
            TransmicroneTheme {
                SetupScreen("", emptyList(), place, selected.value, true, false,
                    onQuery = {}, onPick = {}, onToggle = { key -> selected.value = if (key in selected.value) emptySet() else setOf(key) },
                    onSave = { saved = true }, onBack = {})
            }
        }
        compose.onNodeWithText("Guardar tablero").assertIsNotEnabled()
        compose.onNodeWithText("CAD").performClick()
        compose.onNodeWithText("Guardar tablero").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertTrue(saved)
            assertTrue(option.key() in selected.value)
        }
    }

    @Test
    fun arrivalPhraseAndOccupancyRemainVisibleWithLargeText() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.6f)) {
                TransmicroneTheme {
                    co.camilo.transmicrone.ui.board.ArrivalRow(sampleArrival())
                }
            }
        }
        compose.onNodeWithText("Entre 2 y 4 min", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Ocupación desconocida", useUnmergedTree = true).assertIsDisplayed()
    }

    private fun sampleBoard() = Board(
        boardId = "1",
        placeId = "estacion:TM0101",
        kind = PlaceKind.ESTACION,
        queryParadero = "TM0101",
        displayName = "León XIII",
        disambiguator = "Soacha",
        servicios = listOf(SelectedServicio("E48", "CAD", listOf(13266))),
    )

    private fun sampleArrival() = Arrival(
        painted = "E48",
        destino = "CAD",
        routeId = 13266,
        nodo = 62282,
        etaPhrase = "Entre 2 y 4 min",
        distanceLabel = "200 mts",
        occupancy = Occupancy.UNKNOWN,
        colorRuta = "#AE6B00",
    )
}
