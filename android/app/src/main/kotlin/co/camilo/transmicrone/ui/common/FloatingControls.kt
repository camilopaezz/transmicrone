package co.camilo.transmicrone.ui.common

import android.os.Build
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.*

internal val FloatingHeaderHeight = 52.dp

private val LocalFloatingInsets = compositionLocalOf<WindowInsets> { WindowInsets(0, 0, 0, 0) }

@Composable
internal fun Modifier.floatingHeaderPadding(): Modifier =
    fillMaxWidth().windowInsetsPadding(LocalFloatingInsets.current.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
        .padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 4.dp)

@Composable
internal fun Modifier.floatingHorizontalPadding(): Modifier =
    fillMaxWidth().windowInsetsPadding(LocalFloatingInsets.current.only(WindowInsetsSides.Horizontal))
        .padding(horizontal = 20.dp)

private val LocalBackdrop = compositionLocalOf<HazeState?> { null }

/** Captures only screen content, keeping the navigation controls out of the blur source. */
@Composable
internal fun FloatingScaffold(
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    backdrop: HazeState = rememberHazeState(),
    topEdgeVisible: () -> Boolean = { true },
    bottomEdgeBlur: Boolean = false,
    bottomEdgeVisible: () -> Boolean = { true },
    bottomEdgeHeight: Dp = 96.dp,
    contentWindowInsets: WindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
    content: @Composable (PaddingValues) -> Unit,
) {
    val statusHeight = contentWindowInsets.asPaddingValues().calculateTopPadding()
    CompositionLocalProvider(LocalBackdrop provides backdrop, LocalFloatingInsets provides contentWindowInsets) {
        Scaffold(
            topBar = topBar, bottomBar = bottomBar, snackbarHost = snackbarHost,
            floatingActionButton = floatingActionButton,
            floatingActionButtonPosition = floatingActionButtonPosition,
            contentWindowInsets = contentWindowInsets,
        ) { padding ->
            // Keep system status icons over a clear background. Only the app controls overlap the list.
            Box(Modifier.fillMaxSize().windowInsetsPadding(contentWindowInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                Box(Modifier.fillMaxSize().hazeSource(backdrop)) {
                    content(PaddingValues(
                        start = 0.dp,
                        top = (padding.calculateTopPadding() - statusHeight).coerceAtLeast(0.dp),
                        end = 0.dp,
                        bottom = padding.calculateBottomPadding(),
                    ))
                }
                ScrollEdgeBand(backdrop, true, topEdgeVisible(),
                    (padding.calculateTopPadding() - statusHeight).coerceAtLeast(0.dp) + 20.dp, Modifier.align(Alignment.TopCenter))
                if (bottomEdgeBlur) ScrollEdgeBand(backdrop, false, bottomEdgeVisible(),
                    maxOf(bottomEdgeHeight, padding.calculateBottomPadding()) + 20.dp, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

/** Draw-only siblings of the source, so controls and bands cannot feed back into the blur. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ScrollEdgeBand(backdrop: HazeState, top: Boolean, visible: Boolean, height: Dp, modifier: Modifier) {
    val alpha by animateFloatAsState(if (visible) 1f else 0f, MaterialTheme.motionScheme.fastEffectsSpec(), label = "edgeBlur")
    FloatingEdgeBand(backdrop, top, alpha, height, modifier, if (top) "scroll-edge-top" else "scroll-edge-bottom")
}

@Composable
internal fun FloatingEdgeBand(backdrop: HazeState, top: Boolean, alpha: Float, height: Dp, modifier: Modifier, tag: String) {
    if (alpha <= 0f) return
    val surface = MaterialTheme.colorScheme.surface
    val gradient = Brush.verticalGradient(*if (top) arrayOf(
        0f to surface.copy(alpha = alpha), .35f to surface.copy(alpha = .6f * alpha), 1f to Color.Transparent,
    ) else arrayOf(
        0f to Color.Transparent, .65f to surface.copy(alpha = .6f * alpha), 1f to surface.copy(alpha = alpha),
    ))
    val heightPx = with(LocalDensity.current) { height.toPx() }
    val effect = if (Build.VERSION.SDK_INT >= 31) Modifier.hazeEffect(backdrop, HazeStyle(
        backgroundColor = surface, tint = HazeTint(Color.Transparent), blurRadius = 20.dp, noiseFactor = 0f,
    )) {
        this.alpha = alpha
        // Keep edge-clamped samples beneath the stronger scrim; the inner half stays sharp.
        progressive = HazeProgressive.verticalGradient(
            easing = if (top) LinearOutSlowInEasing else FastOutLinearInEasing,
            startY = if (top) 0f else heightPx * .5f, endY = if (top) heightPx * .5f else heightPx,
            startIntensity = if (top) 1f else 0f, endIntensity = if (top) 0f else 1f,
        )
    } else Modifier
    Box(modifier.fillMaxWidth().height(height).clip(RectangleShape)
        .testTag(tag).then(effect).background(gradient))
}

/** Clip the backdrop blur to each control, leaving the space between controls transparent. */
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun FloatingControlSurface(
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    blurred: Boolean = true,
    sharedKey: Any? = null,
    backdrop: HazeState? = LocalBackdrop.current,
    content: @Composable () -> Unit,
) {
    val shared = LocalSharedTransitionScope.current
    val visibility = LocalNavAnimatedScope.current
    val morphEnabled = rememberSharedMorphEnabled()
    // The overlay has no haze source; keep the solid fallback until the morph settles.
    val source = if (blurred && !(sharedKey != null && shared?.isTransitionActive == true)) backdrop else null
    val colors = MaterialTheme.colorScheme
    val rim = remember(colors.surface, colors.surfaceContainerHigh, colors.outlineVariant) {
        val highlight = if (colors.surface.luminance() < .5f) .55f else .85f
        Brush.verticalGradient(listOf(
            lerp(colors.surfaceContainerHigh, Color.White, .55f).copy(alpha = highlight),
            Color.Transparent,
            colors.outlineVariant.copy(alpha = .25f),
        ))
    }
    val spatialSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Rect>()
    val sharedModifier = if (sharedKey != null && shared != null && visibility != null && morphEnabled) with(shared) {
        Modifier.sharedBounds(
            rememberSharedContentState(sharedKey),
            visibility,
            enter = EnterTransition.None,
            exit = ExitTransition.None,
            boundsTransform = BoundsTransform { _, _ -> spatialSpec },
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            clipInOverlayDuringTransition = OverlayClip(shape),
        )
    } else Modifier
    val effect = if (source != null) Modifier.clip(shape).hazeEffect(
        state = source,
        style = HazeStyle(
            backgroundColor = colors.surface,
            tint = HazeTint(colors.surfaceContainerHigh.copy(alpha = .60f)),
            blurRadius = 20.dp,
            noiseFactor = 0f,
            fallbackTint = HazeTint(colors.surfaceContainerHigh.copy(alpha = .94f)),
        ),
    ) else Modifier
    Surface(
        modifier = modifier.then(sharedModifier).then(effect),
        shape = shape,
        color = if (source != null) Color.Transparent else colors.surfaceContainerHigh,
        contentColor = colors.onSurface,
        border = BorderStroke(1.dp, rim),
        content = content,
    )
}

@Composable
internal fun FloatingScreenHeader(title: String, backLabel: String, onBack: () -> Unit) {
    Row(
        Modifier.floatingHeaderPadding(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FloatingControlSurface {
            IconButton(onClick = onBack, modifier = Modifier.size(FloatingHeaderHeight)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, backLabel)
            }
        }
        FloatingControlSurface(Modifier.weight(1f, fill = false)) {
            Box(Modifier.heightIn(min = FloatingHeaderHeight).padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart) {
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** Reserves its own space in Scaffold, including when the keyboard opens. */
@Composable
internal fun FloatingActionPanel(inline: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val insets = if (inline) Modifier else Modifier
        .windowInsetsPadding(LocalFloatingInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
        .imePadding()
    Box(Modifier.fillMaxWidth().then(insets).padding(horizontal = if (inline) 0.dp else 20.dp, vertical = 12.dp)) {
        FloatingControlSurface(shape = MaterialTheme.shapes.extraLarge, blurred = !inline) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
    }
}
