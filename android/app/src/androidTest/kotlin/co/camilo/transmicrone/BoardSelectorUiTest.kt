package co.camilo.transmicrone

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import co.camilo.transmicrone.domain.*
import co.camilo.transmicrone.ui.BoardView
import co.camilo.transmicrone.ui.Freshness
import co.camilo.transmicrone.ui.home.HomeScreen
import co.camilo.transmicrone.ui.theme.TransmicroneTheme
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BoardSelectorUiTest {
    @get:Rule val compose = createComposeRule()
    private fun boards(count: Int) = List(count) { i -> BoardView(Board("$i", "place:$i", PlaceKind.ESTACION,
        "TM$i", "Estación $i", "Localidad $i", listOf(SelectedServicio("E48", "CAD", listOf(13266))))) }

    @Test fun titleTapSettlesWithin350Milliseconds() {
        val page = mutableIntStateOf(0)
        compose.mainClock.autoAdvance = false
        compose.setContent { TransmicroneTheme {
            HomeScreen(boards(3), page.intValue, { page.intValue = it }, {}, {}, {})
        } }
        compose.onNodeWithTag("board-title-next").performClick()
        compose.mainClock.advanceTimeBy(350)
        compose.runOnIdle { assertEquals(1, page.intValue) }
    }

    @Test fun arrivalUpdateWaitsUntilTheBoardSlideFinishes() {
        val views = mutableStateOf(boards(3))
        val page = mutableIntStateOf(0)
        compose.mainClock.autoAdvance = false
        compose.setContent { TransmicroneTheme {
            HomeScreen(views.value, page.intValue, { page.intValue = it }, {}, {}, {})
        } }
        compose.onNodeWithTag("board-title-next").performClick()
        compose.mainClock.advanceTimeBy(160)
        compose.runOnIdle {
            views.value = views.value.mapIndexed { index, view ->
                if (index != 1) view else view.copy(
                    arrivals = listOf(Arrival("E48", "Live update sentinel", 13266, null,
                        "Entre 2 y 4 min", null, Occupancy.UNKNOWN, null)),
                    freshness = Freshness.Updated(java.time.Instant.now()),
                )
            }
        }
        compose.onAllNodesWithContentDescription("Live update sentinel", substring = true).assertCountEquals(0)
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithContentDescription("Live update sentinel", substring = true).assertIsDisplayed()
    }

    @Test fun titleSideTapsNavigateAndCenterStillOpensPicker() {
        val page = mutableIntStateOf(1)
        compose.setContent { TransmicroneTheme {
            HomeScreen(boards(3), page.intValue, { page.intValue = it }, {}, {}, {})
        } }
        compose.onNodeWithTag("board-title-selector").performTouchInput {
            click(androidx.compose.ui.geometry.Offset(width * .15f, height / 2f))
        }
        compose.onNodeWithText("1 de 3").assertIsDisplayed()
        compose.onNodeWithTag("board-picker-list").assertDoesNotExist()
        compose.onNodeWithTag("board-title-selector").performTouchInput {
            click(androidx.compose.ui.geometry.Offset(width * .85f, height / 2f))
        }
        compose.onNodeWithText("2 de 3").assertIsDisplayed()
        compose.onNodeWithTag("board-picker-list").assertDoesNotExist()
        compose.onNodeWithTag("board-title-selector").performTouchInput { click(center) }
        compose.onNodeWithTag("board-picker-list").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, page.intValue) }
    }

    @Test fun titleSideTapsStopAtFirstAndLastBoard() {
        val page = mutableIntStateOf(0)
        compose.setContent { TransmicroneTheme {
            HomeScreen(boards(2), page.intValue, { page.intValue = it }, {}, {}, {})
        } }
        compose.onNodeWithTag("board-title-selector").performTouchInput { click(centerLeft) }
        compose.onNodeWithText("1 de 2").assertIsDisplayed()
        compose.onNodeWithTag("board-picker-list").assertDoesNotExist()
        compose.runOnIdle { page.intValue = 1 }
        compose.onNodeWithTag("board-title-selector").performTouchInput { click(centerRight) }
        compose.onNodeWithText("2 de 2").assertIsDisplayed()
        compose.onNodeWithTag("board-picker-list").assertDoesNotExist()
    }

    @Test fun pickerJumpsToDistantBoardAndOnlyPublishesFinalSelection() {
        val page = mutableIntStateOf(0)
        val published = mutableListOf<Int>()
        compose.setContent { TransmicroneTheme {
            HomeScreen(boards(12), page.intValue, { published += it; page.intValue = it }, {}, {}, {})
        } }
        compose.onNodeWithTag("board-title-selector").performClick()
        compose.onNodeWithTag("board-picker-list").performScrollToNode(hasText("Estación 11"))
        compose.onNode(isSelectable() and hasText("Estación 11")).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("12 de 12").assertIsDisplayed()
        compose.onNodeWithText("Estación 11").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(11), published); assertEquals(11, page.intValue) }
    }

    @Test fun titleSwipeAndReverseSwipeKeepCounterAndEditTargetTogether() {
        val page = mutableIntStateOf(0)
        var edited = ""
        compose.setContent { TransmicroneTheme {
            HomeScreen(boards(3), page.intValue, { page.intValue = it }, {}, { edited = it }, {})
        } }
        compose.onNodeWithTag("board-title-selector").performTouchInput { swipeLeft() }
        compose.onNodeWithText("2 de 3").assertIsDisplayed()
        compose.onNodeWithContentDescription("Editar tablero").performClick()
        compose.runOnIdle { assertEquals("1", edited) }
        compose.onNodeWithTag("board-title-selector").performTouchInput { swipeRight() }
        compose.onNodeWithText("1 de 3").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, page.intValue) }
    }

    @Test fun titleViewportStaysFixedWhileTextTracksAnUnfinishedSwipe() {
        val views = boards(2).mapIndexed { i, view -> view.copy(board = view.board.copy(
            displayName = if (i == 0) "León XIII" else "General Santander")) }
        val page = mutableIntStateOf(0)
        compose.setContent { TransmicroneTheme {
            HomeScreen(views, page.intValue, { page.intValue = it }, {}, {}, {})
        } }
        val viewport = compose.onNodeWithTag("board-title-selector").getUnclippedBoundsInRoot()
        val counter = compose.onNodeWithTag("board-page-selector").getUnclippedBoundsInRoot()
        val initialTitle = compose.onNodeWithText("León XIII", useUnmergedTree = true).getUnclippedBoundsInRoot()
        compose.onNodeWithTag("board-pager").performTouchInput {
            down(centerRight)
            moveBy(androidx.compose.ui.geometry.Offset(-width * .1f, 0f), delayMillis = 100)
            moveBy(androidx.compose.ui.geometry.Offset(-width * .2f, 0f), delayMillis = 100)
        }
        assertEquals(viewport, compose.onNodeWithTag("board-title-selector").getUnclippedBoundsInRoot())
        assertEquals(counter.top, compose.onNodeWithTag("board-page-selector").getUnclippedBoundsInRoot().top)
        assertTrue(compose.onNodeWithText("León XIII", useUnmergedTree = true).getUnclippedBoundsInRoot().left < initialTitle.left)
        compose.onNodeWithTag("board-pager").performTouchInput {
            moveBy(androidx.compose.ui.geometry.Offset(-width * .5f, 0f), delayMillis = 100)
            up()
        }
        compose.onNodeWithText("General Santander").assertIsDisplayed()
        assertEquals(viewport, compose.onNodeWithTag("board-title-selector").getUnclippedBoundsInRoot())
        assertEquals(counter.top, compose.onNodeWithTag("board-page-selector").getUnclippedBoundsInRoot().top)
        compose.runOnIdle { assertEquals(1, page.intValue) }
    }

    @Test fun shortDragReturnsToSameBoardWithoutPublishingAnotherPage() {
        val published = mutableListOf<Int>()
        compose.setContent { TransmicroneTheme { HomeScreen(boards(2), 0, { published += it }, {}, {}, {}) } }
        compose.onNodeWithTag("board-title-selector").performTouchInput {
            down(center); moveBy(androidx.compose.ui.geometry.Offset(-30f, 0f), delayMillis = 400); up()
        }
        compose.onNodeWithText("1 de 2").assertIsDisplayed()
        compose.runOnIdle { assertEquals(emptyList<Int>(), published) }
    }

    @Test fun reorderAndDeletionPreserveTheRequestedBoardIdentity() {
        val all = boards(3)
        val views = mutableStateOf(all)
        val page = mutableIntStateOf(1)
        compose.setContent { TransmicroneTheme {
            HomeScreen(views.value, page.intValue, { page.intValue = it }, {}, {}, {})
        } }
        compose.runOnIdle { views.value = listOf(all[1], all[2], all[0]); page.intValue = 0 }
        compose.onNodeWithText("Estación 1").assertIsDisplayed()
        compose.onNodeWithText("1 de 3").assertIsDisplayed()
        compose.runOnIdle { views.value = listOf(all[2], all[0]); page.intValue = 0 }
        compose.onNodeWithText("Estación 2").assertIsDisplayed()
        compose.onNodeWithText("1 de 2").assertIsDisplayed()
    }

    @Test fun middleTitleKeepsBothHintsAndLastTitleKeepsPreviousHint() {
        val names = listOf("León XIII", "Universidad Nacional", "General Santander")
        val views = boards(3).mapIndexed { i, view -> view.copy(board = view.board.copy(displayName = names[i])) }
        val page = mutableIntStateOf(1)
        compose.setContent { TransmicroneTheme {
            HomeScreen(views, page.intValue, { page.intValue = it }, {}, {}, {})
        } }
        fun assertInkInEdge(previous: Boolean) {
            val pixels = compose.onNodeWithTag("board-title-selector").captureToImage().toPixelMap()
            val background = pixels[pixels.width / 2, pixels.height - 1]
            val start = if (previous) 0 else (pixels.width * .92f).toInt()
            val end = if (previous) (pixels.width * .04f).toInt() else pixels.width
            var ink = 0
            for (x in start until end) for (y in 0 until pixels.height) {
                val color = pixels[x, y]
                if (kotlin.math.abs(color.red - background.red) + kotlin.math.abs(color.green - background.green) +
                    kotlin.math.abs(color.blue - background.blue) > .15f) ink++
            }
            assertTrue("Missing ${if (previous) "previous" else "next"} station hint", ink > 5)
        }
        compose.onNodeWithText("Universidad Nacional").assertIsDisplayed()
        assertInkInEdge(previous = true)
        assertInkInEdge(previous = false)
        compose.runOnIdle { page.intValue = 2 }
        compose.onNodeWithText("General Santander").assertIsDisplayed()
        assertInkInEdge(previous = true)
    }

    @Test fun longStationNamesAreEllipsizedAndSelectableAtDoubleFont() {
        val views = boards(2).map { it.copy(board = it.board.copy(displayName = "Estación General Santander y sus conexiones ${it.board.boardId}")) }
        val page = mutableIntStateOf(0)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) { TransmicroneTheme {
                HomeScreen(views, page.intValue, { page.intValue = it }, {}, {}, {})
            } }
        }
        val titleLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(views[0].board.displayName, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(titleLayouts) }
        assertEquals(1, titleLayouts.single().lineCount)
        assertTrue(titleLayouts.single().isLineEllipsized(0))
        compose.onNodeWithTag("board-title-selector").assertIsDisplayed().performClick()
        compose.onNodeWithTag("board-picker-list").performScrollToNode(hasText(views[1].board.displayName))
        compose.onNode(isSelectable() and hasText(views[1].board.displayName)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, page.intValue) }
    }
}
