package co.camilo.transmicrone.ui.home

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.Job

@Stable
internal class TitleCollapse {
    var height by mutableFloatStateOf(0f)
    var offset by mutableFloatStateOf(0f)
    var expansion: Job? = null
    val fraction get() = if (height > 0f) (offset / height).coerceIn(0f, 1f) else 0f
    val expanded get() = offset == 0f
    val collapsed get() = height > 0f && offset >= height

    fun consume(delta: Float): Float {
        expansion?.cancel()
        val previous = offset
        offset = (offset - delta).coerceIn(0f, height)
        return previous - offset
    }

    // Return only the velocity spent on the title; the list inherits the remainder.
    suspend fun fling(velocity: Float, decay: DecayAnimationSpec<Float>): Float {
        var previous = 0f
        var remaining = 0f
        AnimationState(0f, velocity).animateDecay(decay) {
            val delta = value - previous
            previous = value
            consume(delta)
            if ((velocity < 0f && collapsed) || (velocity > 0f && expanded)) {
                remaining = this.velocity
                cancelAnimation()
            }
        }
        return velocity - remaining
    }
}

internal fun Modifier.collapsingTitle(state: TitleCollapse): Modifier = clipToBounds().layout { measurable, constraints ->
    val child = measurable.measure(constraints.copy(minHeight = 0))
    state.height = child.height.toFloat()
    if (state.offset > state.height) state.offset = state.height
    val height = (child.height - state.offset).roundToInt().coerceIn(0, child.height)
    layout(child.width, height) { child.placeRelative(0, height - child.height) }
}

internal fun Modifier.titleCollapseFade(fraction: () -> Float, until: Float, opacityLimit: () -> Float = { 1f }): Modifier = graphicsLayer {
    val progress = fraction()
    alpha = minOf(1f - progress / until, opacityLimit()).coerceIn(0f, 1f)
    translationY = -8.dp.toPx() * progress
}

// Preserve the exposed gesture/semantics bounds while measuring the list at full height.
// Its scrollable header supplies clearance; scrolled cards can draw behind the capsules.
internal fun Modifier.arrivalsViewport(clearance: () -> Int): Modifier =
    offset { IntOffset(0, clearance()) }.testTag("arrival-viewport").layout { measurable, constraints ->
        val child = measurable.measure(constraints)
        val top = clearance().coerceIn(0, child.height)
        layout(child.width, child.height - top) { child.placeRelative(0, -top) }
    }.fillMaxSize()
