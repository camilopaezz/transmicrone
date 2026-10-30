package co.camilo.transmicrone.ui.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import co.camilo.transmicrone.ui.Screen
import co.camilo.transmicrone.ui.UiState

// Search views request keyboard focus once their entrance has finished.
val LocalScreenEntered = compositionLocalOf { true }

// Only navigation changes animate. Queries, arrivals and loading states keep the same content.
private fun UiState.destinationKey(): List<Any?> = when (screen) {
    Screen.SEARCH -> listOf(screen, catalogSearchSession)
    Screen.DETAILS -> listOf(screen, details)
    Screen.SETUP -> listOf(screen, editingBoardId, setupPlace?.placeId)
    else -> listOf(screen)
}

private fun UiState.navigationDepth(): Int = when (screen) {
    Screen.HOME -> 0
    Screen.SEARCH, Screen.SETTINGS -> 1
    Screen.DETAILS -> 1 + details.size
    Screen.SETUP -> 2 + details.size + if (setupPlace == null) 0 else 1
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ScreenTransition(state: UiState, content: @Composable (UiState) -> Unit) {
    val searchState = rememberSaveableStateHolder()
    // The holder survives process death even when the new ViewModel restarts at session zero.
    var retainedSearchSession by rememberSaveable { mutableLongStateOf(state.catalogSearchSession) }
    LaunchedEffect(state.screen, state.catalogSearchSession) {
        if (state.screen == Screen.HOME) searchState.removeState(retainedSearchSession)
        else retainedSearchSession = state.catalogSearchSession
    }
    val distance = with(LocalDensity.current) { 24.dp.roundToPx() }
    val layoutSign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    SharedTransitionLayout(Modifier.fillMaxSize()) {
        val sharedScope = this
        AnimatedContent(
            targetState = state,
            contentKey = { it.destinationKey() },
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).clipToBounds(),
            transitionSpec = {
                val direction = if (targetState.navigationDepth() < initialState.navigationDepth()) -1 else 1
                val offset = distance * direction * layoutSign
                (fadeIn(tween(210)) + slideInHorizontally(tween(240, easing = FastOutSlowInEasing)) { offset })
                    .togetherWith(fadeOut(tween(120)) + slideOutHorizontally(tween(120, easing = FastOutSlowInEasing)) { -offset })
                    .using(null)
            },
            label = "screenNavigation",
        ) { displayedState ->
            val visibilityScope = this
            val current = displayedState.destinationKey() == state.destinationKey()
            // Retain the departing snapshot through the animation, but prevent stale actions and
            // duplicate accessibility nodes while both screens are composed.
            Box(Modifier.fillMaxSize().then(if (current) Modifier else Modifier
                .clearAndSetSemantics {}
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                })) {
                CompositionLocalProvider(
                    LocalSharedTransitionScope provides sharedScope,
                    LocalNavAnimatedScope provides visibilityScope,
                    LocalScreenEntered provides (current &&
                        transition.currentState == EnterExitState.Visible &&
                        transition.targetState == EnterExitState.Visible &&
                        !sharedScope.isTransitionActive),
                ) {
                    // Details temporarily replace search in the composition. Keep its list and
                    // focus state until the user leaves the search flow for home.
                    if (displayedState.screen == Screen.SEARCH) {
                        searchState.SaveableStateProvider(displayedState.catalogSearchSession) { content(displayedState) }
                    } else {
                        content(displayedState)
                    }
                }
            }
        }
    }
}
