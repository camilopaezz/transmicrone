package co.camilo.transmicrone

import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import co.camilo.transmicrone.domain.*
import co.camilo.transmicrone.ui.UiState
import co.camilo.transmicrone.ui.common.ScreenTransition
import co.camilo.transmicrone.ui.BoardView
import co.camilo.transmicrone.ui.RouteState
import co.camilo.transmicrone.ui.common.FloatingScaffold
import co.camilo.transmicrone.ui.common.FloatingSnackbarHost
import co.camilo.transmicrone.ui.home.HomeScreen
import co.camilo.transmicrone.ui.search.CatalogDetailsScreen
import co.camilo.transmicrone.ui.search.CatalogSearchScreen
import co.camilo.transmicrone.ui.setup.SetupScreen
import co.camilo.transmicrone.ui.theme.TransmicroneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GlassControlsTest {
    @get:Rule val compose = createComposeRule()
    private val places = List(30) { i ->
        Place("estacion:$i", PlaceKind.ESTACION, "Estación $i", "estacion", null, "TM$i", emptyList())
    }
    private val service = CatalogEntry.Service(ServicioOption("E48", "Norte||Sur", listOf(1), null), emptyList())
    private val routes = listOf("Norte", "Sur").mapIndexed { direction, name ->
        ServiceRoute("$direction", List(40) { i -> RouteStop("$direction:$i", if (i == 39) name else "Parada $direction/$i") })
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = java.io.File(instrumentation.targetContext.externalCacheDir, name)
        file.outputStream().use { instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun homeEdges(dark: Boolean, name: String) {
        val board = Board("1", "estacion:0", PlaceKind.ESTACION, "TM0", "Ricaurte - NQS", null, emptyList())
        val arrivals = List(40) { i -> Arrival("E48", "CAD $i", i, i, "Entre 2 y 4 min", "200 mts", Occupancy.UNKNOWN, null) }
        compose.setContent { TransmicroneTheme(darkTheme = dark, dynamicColor = false) {
            HomeScreen(listOf(BoardView(board, arrivals), BoardView(board.copy(boardId = "2", displayName = "León XIII"), arrivals)), 0, {}, {}, {}, {})
        } }
        compose.mainClock.advanceTimeBy(6_000)
        compose.onNodeWithTag("arrival-edge-top").assertDoesNotExist()
        if (dark) screenshot("glass-step3b-rest.png")
        compose.onNodeWithTag("scroll-edge-top").assertDoesNotExist()
        compose.onNodeWithTag("scroll-edge-bottom").assertExists()
        compose.onNodeWithTag("arrival-viewport").performScrollToIndex(18)
        compose.onNodeWithTag("scroll-edge-top").assertDoesNotExist()
        compose.onNodeWithTag("arrival-edge-top").assertExists()
        screenshot(name)
        compose.onNodeWithTag("arrival-viewport").performScrollToIndex(0)
        compose.onNodeWithTag("arrival-edge-top").assertDoesNotExist()
        compose.onNodeWithTag("arrival-viewport").performScrollToIndex(40)
        compose.onNodeWithTag("scroll-edge-bottom").assertDoesNotExist()
    }

    @Test fun homeScrollEdgesDark() = homeEdges(true, "glass-step3b-scrolled.png")
    @Test fun homeScrollEdgesLight() = homeEdges(false, "glass-step3b-scrolled-light.png")

    @Test fun detailsScrollEdgesScreenshot() {
        compose.setContent { TransmicroneTheme(darkTheme = true, dynamicColor = false) {
            CatalogDetailsScreen(service, {}, {}, {}, {}, RouteState.Loaded(routes))
        } }
        compose.onNode(hasScrollAction()).performScrollToIndex(18)
        compose.onNodeWithTag("scroll-edge-top").assertExists()
        compose.onNodeWithTag("scroll-edge-bottom").assertExists()
        screenshot("glass-step3-details.png")
    }

    @Test fun edgeBandsPassPhysicalTapsToContent() {
        var taps = 0
        compose.setContent { TransmicroneTheme {
            FloatingScaffold(topBar = { Box(Modifier.height(80.dp)) }, bottomEdgeBlur = true,
                contentWindowInsets = WindowInsets(0, 0, 0, 0)) {
                androidx.compose.foundation.lazy.LazyColumn {
                    items(30) { index ->
                        Box(Modifier.height(80.dp).width(200.dp).clickable { taps++ }.testTag("under-edge-$index"))
                    }
                }
            }
        } }
        compose.onNodeWithTag("scroll-edge-top").assertExists()
        compose.onNodeWithTag("under-edge-0").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, taps) }
    }

    @Test fun undoSnackbarClearsHomeDockAndKeepsMaterialSemantics() {
        var result: SnackbarResult? = null
        val board = Board("1", "estacion:0", PlaceKind.ESTACION, "TM0", "Estación 0", null, emptyList())
        compose.setContent { TransmicroneTheme {
            val host = remember { SnackbarHostState() }
            LaunchedEffect(Unit) {
                result = host.showSnackbar("Tablero eliminado", "Deshacer", duration = SnackbarDuration.Indefinite)
            }
            HomeScreen(listOf(BoardView(board)), 0, {}, {}, {}, {}, snackbarHostState = host)
        } }
        val glass = compose.onNodeWithTag("floating-snackbar").assertIsDisplayed().getUnclippedBoundsInRoot()
        val dock = compose.onNodeWithContentDescription("Editar tablero").getUnclippedBoundsInRoot()
        assertTrue("Snackbar must leave 12 dp above the dock, including its 6 dp padding", glass.bottom <= dock.top - 18.dp)
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite), useUnmergedTree = true).assertExists()
        assertTrue("Single-line height allows pixel rounding", glass.bottom - glass.top in 52.dp..53.dp)
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss), useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Deshacer").assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(SnackbarResult.ActionPerformed, result) }
    }

    @Test fun homeDockContractsOnScrollAndExpandsOnReturnAndPageChange() = withAnimations {
        var adds = 0
        val page = mutableIntStateOf(0)
        val board = Board("1", "estacion:0", PlaceKind.ESTACION, "TM0", "Estación 0", null, emptyList())
        val arrivals = List(40) { i -> Arrival("E48", "CAD $i", i, i, "Entre 2 y 4 min", "200 mts", Occupancy.UNKNOWN, null) }
        compose.setContent { TransmicroneTheme {
            val host = remember { SnackbarHostState() }
            LaunchedEffect(Unit) { host.showSnackbar("Mensaje", duration = SnackbarDuration.Indefinite) }
            ScreenTransition(UiState()) {
                HomeScreen(listOf(BoardView(board, arrivals), BoardView(board.copy(boardId = "2", displayName = "Estación 1"), arrivals)),
                    page.intValue, { page.intValue = it }, { adds++ }, {}, {}, snackbarHostState = host)
            }
        } }
        fun assertSnackbarClearance() {
            val snackbar = compose.onNodeWithTag("floating-snackbar").getUnclippedBoundsInRoot()
            val edit = compose.onNodeWithContentDescription("Editar tablero").getUnclippedBoundsInRoot()
            assertTrue("Snackbar keeps 12 dp above dock padding", snackbar.bottom <= edit.top - 18.dp)
        }
        val add = compose.onNodeWithContentDescription("Agregar tablero")
        compose.onNodeWithText("Agregar tablero").assertIsDisplayed()
        val expanded = add.getUnclippedBoundsInRoot().let { it.right - it.left }
        assertSnackbarClearance()
        val list = compose.onNodeWithTag("arrival-viewport")
        list.performTouchInput { swipeUp(durationMillis = 600) }
        compose.onNodeWithText("Agregar tablero").assertDoesNotExist()
        add.assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        assertTrue("Add contracts to 48 dp", add.getUnclippedBoundsInRoot().let { it.right - it.left } <= 49.dp)
        assertSnackbarClearance()
        compose.runOnIdle { assertEquals(1, adds) }
        list.performTouchInput { swipeDown(durationMillis = 600) }
        compose.onNodeWithText("Agregar tablero").assertIsDisplayed()
        assertEquals(expanded, add.getUnclippedBoundsInRoot().let { it.right - it.left })
        list.performTouchInput { swipeUp(durationMillis = 600) }
        compose.onNodeWithText("Agregar tablero").assertDoesNotExist()
        compose.runOnIdle { page.intValue = 1 }
        compose.onNodeWithText("Agregar tablero").assertIsDisplayed()
        compose.onNodeWithTag("arrival-viewport").performScrollToIndex(40)
        compose.onNodeWithText("Fuente no oficial. Las frases de llegada no son un reloj.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Agregar tablero").assertIsDisplayed()
    }

    private fun shell(command: String): String = InstrumentationRegistry.getInstrumentation().uiAutomation
        .executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText().trim() }
        }

    // CI emulators run with animations disabled, which keeps the dock expanded by design.
    private fun withAnimations(block: () -> Unit) {
        val original = shell("settings get global animator_duration_scale")
        try {
            shell("settings put global animator_duration_scale 1")
            block()
        } finally {
            shell(if (original == "null") "settings delete global animator_duration_scale" else "settings put global animator_duration_scale $original")
        }
    }

    @Test fun homeDockStaysExpandedWhenSystemAnimationsAreDisabled() = withAnimations {
        val board = Board("1", "estacion:0", PlaceKind.ESTACION, "TM0", "Estación 0", null, emptyList())
        val arrivals = List(40) { i -> Arrival("E48", "CAD $i", i, i, "Entre 2 y 4 min", "200 mts", Occupancy.UNKNOWN, null) }
        compose.setContent { TransmicroneTheme {
            HomeScreen(listOf(BoardView(board, arrivals)), 0, {}, {}, {}, {})
        } }
        val list = compose.onNodeWithTag("arrival-viewport")
        list.performTouchInput { swipeUp(durationMillis = 600) }
        compose.onNodeWithText("Agregar tablero").assertDoesNotExist()
        shell("settings put global animator_duration_scale 0")
        compose.waitUntil { compose.onAllNodesWithText("Agregar tablero").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Agregar tablero").assertIsDisplayed()
        list.performTouchInput { swipeUp(durationMillis = 600) }
        compose.onNodeWithText("Agregar tablero").assertIsDisplayed()
    }

    @Test fun snackbarClearsSavePanel() {
        compose.setContent { TransmicroneTheme {
            val host = remember { SnackbarHostState() }
            LaunchedEffect(Unit) { host.showSnackbar("No se pudo guardar", duration = SnackbarDuration.Indefinite) }
            SetupScreen("", emptyList(), places.first(), emptySet(), true, false,
                {}, {}, {}, {}, {}, snackbarHostState = host)
        } }
        val glass = compose.onNodeWithTag("floating-snackbar").assertIsDisplayed().getUnclippedBoundsInRoot()
        val save = compose.onNodeWithText("Guardar tablero").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("Snackbar must clear the save panel", glass.bottom <= save.top - 12.dp)
    }

    @Test fun snackbarRespectsBottomInsetAndDismisses() {
        var result: SnackbarResult? = null
        compose.setContent { TransmicroneTheme {
            val host = remember { SnackbarHostState() }
            LaunchedEffect(Unit) {
                result = host.showSnackbar("Mensaje largo que puede ocupar varias líneas", withDismissAction = true,
                    duration = SnackbarDuration.Indefinite)
            }
            FloatingScaffold(contentWindowInsets = WindowInsets(bottom = 200.dp),
                snackbarHost = { FloatingSnackbarHost(host) }) { Box(Modifier.height(600.dp)) }
        } }
        val glass = compose.onNodeWithTag("floating-snackbar").assertIsDisplayed().getUnclippedBoundsInRoot()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        assertTrue("Snackbar must clear the bottom inset", glass.bottom <= root.bottom - 212.dp)
        val dismiss = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(androidx.compose.material3.R.string.m3c_snackbar_dismiss)
        compose.onNodeWithContentDescription(dismiss).performClick()
        compose.runOnIdle { assertEquals(SnackbarResult.Dismissed, result) }
    }

    @Test fun snackbarWrapsAtDoubleFontWithoutShrinkingActions() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                TransmicroneTheme {
                    val host = remember { SnackbarHostState() }
                    LaunchedEffect(Unit) {
                        host.showSnackbar("No se pudo guardar el tablero. Inténtalo de nuevo.", "Reintentar",
                            withDismissAction = true, duration = SnackbarDuration.Indefinite)
                    }
                    FloatingSnackbarHost(host, Modifier.width(360.dp))
                }
            }
        }
        val glass = compose.onNodeWithTag("floating-snackbar").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("Wrapped text must grow the solid fallback surface", glass.bottom - glass.top > 52.dp)
        compose.onNodeWithText("Reintentar").assertHeightIsAtLeast(48.dp)
        val dismiss = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(androidx.compose.material3.R.string.m3c_snackbar_dismiss)
        compose.onNodeWithContentDescription(dismiss).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }

    @Test fun catalogSearchStaysAvailableAfterScrollingAndCanClearQuery() {
        val query = mutableStateOf("Estación")
        compose.setContent { TransmicroneTheme {
            CatalogSearchScreen(query.value, CatalogResults(places = places), false, false,
                { query.value = it }, {}, {})
        } }
        compose.onNode(hasSetTextAction()).performImeAction()
        val before = compose.onNode(hasSetTextAction()).getUnclippedBoundsInRoot()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Estación 29"))
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        assertEquals(before, compose.onNode(hasSetTextAction()).getUnclippedBoundsInRoot())
        compose.onNodeWithContentDescription("Borrar búsqueda").performClick()
        compose.runOnIdle { assertEquals("", query.value) }
    }

    @Test fun setupSearchStaysAvailableAfterScrollingResults() {
        var picked: Place? = null
        compose.setContent { TransmicroneTheme {
            SetupScreen("Estación", places, null, emptySet(), true, false,
                {}, { picked = it }, {}, {}, {})
        } }
        compose.onNode(hasSetTextAction()).performImeAction()
        val before = compose.onNode(hasSetTextAction()).getUnclippedBoundsInRoot()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Estación 29"))
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        assertEquals(before, compose.onNode(hasSetTextAction()).getUnclippedBoundsInRoot())
        compose.onNodeWithText("Estación 29").performClick()
        compose.runOnIdle { assertEquals(places.last(), picked) }
    }

    @Test fun bottomDockDirectionsStayAvailableAndSwitchRouteAfterScrolling() {
        val index = mutableIntStateOf(0)
        compose.setContent { TransmicroneTheme {
            CatalogDetailsScreen(service, {}, {}, {}, {}, RouteState.Loaded(routes),
                routeIndex = index.intValue, onSelectRoute = { index.intValue = it })
        } }
        compose.onNodeWithTag("scroll-edge-top").assertDoesNotExist()
        compose.onNodeWithTag("scroll-edge-bottom").assertExists()
        val before = compose.onNodeWithContentDescription("Hacia Sur").getUnclippedBoundsInRoot()
        val list = compose.onNode(hasScrollAction()).getUnclippedBoundsInRoot()
        assertTrue("Directions belong in the bottom dock", before.top > (list.top + list.bottom) / 2)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Parada 0/35"))
        assertEquals(before, compose.onNodeWithContentDescription("Hacia Sur").getUnclippedBoundsInRoot())
        compose.onNodeWithContentDescription("Hacia Sur").assertIsDisplayed().performClick().assertIsSelected()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Parada 1/35"))
        compose.onNodeWithText("Parada 1/35").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToIndex(41)
        val lastStop = compose.onNodeWithText("1:39").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("The final stop must clear the floating dock", lastStop.bottom < before.top)
        compose.runOnIdle { assertEquals(1, index.intValue) }
    }

    @Test fun shortRouteAtDoubleFontKeepsDirectionsAndStopsReachable() {
        val index = mutableIntStateOf(0)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                TransmicroneTheme { Box(Modifier.height(360.dp).clipToBounds()) {
                    CatalogDetailsScreen(service, {}, {}, {}, {}, RouteState.Loaded(routes),
                        routeIndex = index.intValue, onSelectRoute = { index.intValue = it })
                } }
            }
        }
        compose.onNodeWithTag("scroll-edge-bottom").assertDoesNotExist()
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Hacia Sur"))
        compose.onNodeWithContentDescription("Hacia Sur").assertIsDisplayed().performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Parada 1/35"))
        compose.onNodeWithText("Parada 1/35").assertIsDisplayed()
    }
}
