package co.camilo.transmicrone

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import co.camilo.transmicrone.domain.Arrival
import co.camilo.transmicrone.domain.Board
import co.camilo.transmicrone.domain.Occupancy
import co.camilo.transmicrone.domain.PlaceKind
import co.camilo.transmicrone.domain.SelectedServicio
import co.camilo.transmicrone.ui.BoardView
import co.camilo.transmicrone.ui.home.HomeScreen
import co.camilo.transmicrone.ui.theme.TransmicroneTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HomeCollapsingTitleTest {
    @get:Rule val compose = createComposeRule()
    private val board = Board("1", "estacion:TM0101", PlaceKind.ESTACION, "TM0101", "León XIII", "Soacha",
        listOf(SelectedServicio("E48", "CAD", listOf(13266))))
    private val arrival = Arrival("E48", "CAD", 13266, 62282, "Entre 2 y 4 min", "200 mts", Occupancy.UNKNOWN, null)

    private fun home(onRefresh: () -> Unit = {}) {
        compose.setContent { TransmicroneTheme {
            HomeScreen(listOf(BoardView(board, List(20) { arrival.copy(nodo = it) }),
                BoardView(board.copy(boardId = "2", displayName = "General Santander"))), 0, {}, {}, {}, onRefresh)
        } }
    }

    @Test fun shortListKeepsTitleAndBrandAfterUpwardDrag() {
        compose.setContent { TransmicroneTheme {
            HomeScreen(listOf(BoardView(board, listOf(arrival))), 0, {}, {}, {}, {})
        } }
        val list = compose.onNodeWithTag("arrival-viewport")
        assertEquals(0f, list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue())
        list.performTouchInput { swipeUp() }
        compose.onNodeWithTag("board-title-selector").assertIsDisplayed()
        compose.onNodeWithText("TransMinuto").assertIsDisplayed()
        compose.onNodeWithTag("home-title-capsule").assertHasNoClickAction()
    }

    @Test fun expandedTitleKeepsBrand() {
        home()
        compose.onNodeWithTag("board-title-selector").assertIsDisplayed()
        compose.onNodeWithText("TransMinuto").assertIsDisplayed()
        compose.onNodeWithTag("home-title-capsule").assertHasNoClickAction()
        compose.onNodeWithTag("scroll-edge-top").assertDoesNotExist()
    }

    @Test fun collapsedCapsuleNamesBoardAndOpensPicker() {
        home()
        compose.onNodeWithTag("arrival-viewport").performTouchInput { swipeUp() }
        compose.onNodeWithTag("board-title-selector").assertIsNotDisplayed()
        compose.onNodeWithText("León XIII · 1/2").assertIsDisplayed()
        compose.onNodeWithTag("scroll-edge-top").assertExists()
        compose.onNodeWithTag("arrival-edge-top").assertDoesNotExist()
        compose.onNodeWithTag("home-title-capsule").performClick()
        compose.onNodeWithTag("board-picker-list").assertIsDisplayed()
    }

    @Test fun returningToTopRestoresTitleAndBrand() {
        home()
        val list = compose.onNodeWithTag("arrival-viewport")
        list.performTouchInput { swipeUp() }
        list.performScrollToIndex(0)
        list.performTouchInput { swipeDown() }
        compose.onNodeWithTag("board-title-selector").assertIsDisplayed()
        compose.onNodeWithText("TransMinuto").assertIsDisplayed()
        compose.onNodeWithTag("scroll-edge-top").assertDoesNotExist()
    }

    @Test fun collapsedCardsExtendBehindHeader() {
        home()
        val list = compose.onNodeWithTag("arrival-viewport")
        list.performTouchInput { swipeUp() }
        list.performScrollToIndex(1)
        val card = compose.onAllNodesWithContentDescription("E48 a CAD", substring = true)[0].fetchSemanticsNode().boundsInRoot
        val capsule = compose.onNodeWithTag("home-title-capsule").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(card.top < capsule.bottom && card.bottom > capsule.top)
        compose.onNodeWithTag("scroll-edge-top").assertExists()
    }

    @Test fun partialCollapseConsumesDragBeforeListMoves() {
        home()
        val list = compose.onNodeWithTag("arrival-viewport")
        val title = compose.onNodeWithTag("board-title-selector").fetchSemanticsNode().boundsInRoot
        val counter = compose.onNodeWithTag("board-page-selector").fetchSemanticsNode().boundsInRoot
        val distance = (counter.bottom - title.top) * .45f
        list.performTouchInput {
            down(center)
            moveBy(Offset(0f, -distance / 2), delayMillis = 300)
            moveBy(Offset(0f, -distance / 2), delayMillis = 300)
        }
        compose.onNodeWithTag("board-page-selector").assertIsDisplayed()
        val moved = compose.onNodeWithTag("board-page-selector").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(moved.bottom < counter.bottom)
        assertEquals(0f, list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value())
        list.performTouchInput { up() }
    }

    @Test fun expandedTitleKeepsArrivalBandWhenListIsScrolled() {
        home()
        compose.onNodeWithTag("arrival-viewport").performScrollToIndex(2)
        compose.onNodeWithTag("board-page-selector").assertIsDisplayed()
        compose.onNodeWithTag("scroll-edge-top").assertDoesNotExist()
        compose.onNodeWithTag("arrival-edge-top").assertExists()
    }

    @Test fun fadingTitleRemovesInvisibleNavigationBeforePageRow() {
        home()
        val title = compose.onNodeWithTag("board-title-selector").fetchSemanticsNode().boundsInRoot
        val pages = compose.onNodeWithTag("board-page-selector").fetchSemanticsNode().boundsInRoot
        val distance = (pages.bottom - title.top) * .35f
        val list = compose.onNodeWithTag("arrival-viewport")
        list.performTouchInput {
            down(center)
            moveBy(Offset(0f, -distance / 2), delayMillis = 300)
            moveBy(Offset(0f, -distance / 2), delayMillis = 300)
        }
        compose.onAllNodesWithContentDescription("Tablero siguiente").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Tablero anterior").assertCountEquals(0)
        compose.onNodeWithTag("board-page-selector").assertIsDisplayed()
        list.performTouchInput { up() }
    }

    @Test fun pagingExpandsAtTopAndRetainsCollapseForScrolledBoard() {
        home()
        val pager = compose.onNodeWithTag("board-pager")
        compose.onNodeWithTag("arrival-viewport").performTouchInput { swipeUp() }
        pager.performTouchInput { swipeLeft() }
        compose.onNodeWithTag("board-title-selector").assertIsDisplayed()
        compose.onNodeWithText("TransMinuto").assertIsDisplayed()
        pager.performTouchInput { swipeRight() }
        compose.onNodeWithTag("board-title-selector").assertIsNotDisplayed()
        compose.onNodeWithText("León XIII · 1/2").assertIsDisplayed()
    }

    @Test fun collapsedPullExpandsWithoutRefreshingUntilNextGesture() {
        var refreshes = 0
        home { refreshes++ }
        val list = compose.onNodeWithTag("arrival-viewport")
        // A slow drag collapses the title without relying on fling distance.
        list.performTouchInput {
            down(center)
            moveBy(Offset(0f, -height * .45f), delayMillis = 1200)
            up()
        }
        compose.onNodeWithTag("board-title-selector").assertIsNotDisplayed()
        list.performScrollToIndex(0)
        list.performTouchInput { swipeDown() }
        compose.onNodeWithTag("board-title-selector").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, refreshes) }
        list.performTouchInput { swipeDown() }
        compose.runOnIdle { assertEquals(1, refreshes) }
    }
}
