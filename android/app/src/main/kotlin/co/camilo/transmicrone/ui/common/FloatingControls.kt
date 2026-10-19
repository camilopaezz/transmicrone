package co.camilo.transmicrone.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import dev.chrisbanes.haze.*
import androidx.compose.ui.unit.dp

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
            Box(Modifier.fillMaxSize().windowInsetsPadding(contentWindowInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).hazeSource(backdrop)) {
                content(PaddingValues(
                    start = 0.dp,
                    top = (padding.calculateTopPadding() - statusHeight).coerceAtLeast(0.dp),
                    end = 0.dp,
                    bottom = padding.calculateBottomPadding(),
                ))
            }
        }
    }
}

/** Clip the backdrop blur to each control, leaving the space between controls transparent. */
@Composable
internal fun FloatingControlSurface(
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    blurred: Boolean = true,
    backdrop: HazeState? = LocalBackdrop.current,
    content: @Composable () -> Unit,
) {
    val source = if (blurred) backdrop else null
    val colors = MaterialTheme.colorScheme
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
        modifier = modifier.then(effect),
        shape = shape,
        color = if (source != null) Color.Transparent else colors.surfaceContainerHigh,
        contentColor = colors.onSurface,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = .30f)),
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
