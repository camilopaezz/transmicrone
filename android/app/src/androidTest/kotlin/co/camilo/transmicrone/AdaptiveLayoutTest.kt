package co.camilo.transmicrone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import co.camilo.transmicrone.ui.common.FloatingScaffold
import co.camilo.transmicrone.ui.common.FloatingScreenHeader
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import co.camilo.transmicrone.domain.*
import co.camilo.transmicrone.ui.BoardView
import co.camilo.transmicrone.ui.key
import co.camilo.transmicrone.ui.Freshness
import co.camilo.transmicrone.ui.home.HomeScreen
import co.camilo.transmicrone.ui.setup.SetupScreen
import co.camilo.transmicrone.ui.theme.TransmicroneTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class AdaptiveLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val service = ServicioOption("E48", "CAD", listOf(13266), null)
    private val place = Place("estacion:TM0101", PlaceKind.ESTACION, "León XIII", "leon", "Soacha", "TM0101", listOf(service))
    private val board = Board("adaptive", place.placeId, place.kind, place.queryParadero, place.name, "Soacha",
        listOf(SelectedServicio("E48", "CAD", listOf(13266))))

    @Test fun shortHomeKeepsScrollableArrivalsAndActionsAtDoubleFont() {
        var added = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                TransmicroneTheme {
                    Box(Modifier.height(280.dp).clipToBounds()) {
                        HomeScreen(listOf(BoardView(board, freshness = Freshness.Empty(Instant.now())),
                            BoardView(board.copy(boardId = "second", displayName = "Ricaurte"))),
                            0, {}, { added = true }, {}, {})
                    }
                }
            }
        }
        val viewport = compose.onNodeWithTag("arrival-viewport").fetchSemanticsNode().boundsInRoot
        val minHeight = with(compose.density) { 80.dp.toPx() }
        assertTrue("A short home must retain room for list gestures", viewport.height >= minHeight)
        val dock = compose.onNodeWithContentDescription("Agregar tablero").fetchSemanticsNode().boundsInRoot
        assertTrue("The dock must leave room for unobscured list gestures", dock.bottom <= viewport.top || dock.top >= viewport.top + minHeight)
        compose.onNodeWithContentDescription("Agregar tablero").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(added) }
        compose.onNodeWithTag("arrival-viewport").performScrollToNode(hasText("No hay servicios acercándose de los que elegiste."))
        compose.onNodeWithText("No hay servicios acercándose de los que elegiste.").assertIsDisplayed()
    }

    @Test fun shortEditorKeepsNameAndSaveReachableAtDoubleFont() {
        var saved = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                TransmicroneTheme {
                    Box(Modifier.height(280.dp).clipToBounds()) {
                        SetupScreen("", emptyList(), place, setOf(service.key()), true, false,
                            {}, {}, {}, { saved = true }, {}, isEditing = true)
                    }
                }
            }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Nombre del tablero (opcional)"))
        compose.onNodeWithText("Nombre del tablero (opcional)").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Guardar tablero"))
        compose.onNodeWithText("Guardar tablero").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(saved) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Eliminar tablero"))
        compose.onNodeWithText("Eliminar tablero").assertIsDisplayed()
    }

    @Test fun sideCutoutInsetsProtectHeaderAndContent() {
        compose.setContent {
            val left = with(LocalDensity.current) { 40.dp.roundToPx() }
            val right = with(LocalDensity.current) { 12.dp.roundToPx() }
            TransmicroneTheme {
                FloatingScaffold(contentWindowInsets = WindowInsets(left = left, right = right),
                    topBar = { FloatingScreenHeader("Buscar", "Atrás", {}) }) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).testTag("safe-content"))
                }
            }
        }
        val body = compose.onNodeWithTag("safe-content").fetchSemanticsNode().boundsInRoot
        val back = compose.onNodeWithContentDescription("Atrás").fetchSemanticsNode().boundsInRoot
        val left = with(compose.density) { 40.dp.toPx() }
        assertTrue("Content must respect the side cutout", body.left >= left)
        assertTrue("The back button must respect the side cutout", back.left >= left)
    }

    @Test fun quietRefreshStillShowsPullFeedback() {
        compose.setContent { TransmicroneTheme {
            HomeScreen(listOf(BoardView(board, freshness = Freshness.UpdatingCached(Instant.now(), quiet = true))),
                0, {}, {}, {}, {})
        } }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
        compose.onNodeWithTag("arrival-viewport").performTouchInput {
            down(center)
            moveBy(Offset(0f, height * .4f), delayMillis = 300)
        }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(1)
        compose.onNodeWithTag("arrival-viewport").performTouchInput { cancel() }
    }

}
