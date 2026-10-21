package co.camilo.transmicrone.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/** Inline glass finish; contrast accounts for the actual card or screen behind the tint. */
@Composable
internal fun ServiceChipSurface(
    routeColor: Color,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    shape: Shape = CircleShape,
    content: @Composable () -> Unit,
) {
    val tint = routeColor.copy(alpha = .72f)
    val background = tint.compositeOver(containerColor)
    Surface(
        shape = shape, color = tint,
        contentColor = if (background.luminance() > .179f) Color.Black else Color.White,
        border = BorderStroke(1.dp, Brush.verticalGradient(listOf(
            lerp(routeColor, Color.White, .65f).copy(alpha = .65f),
            routeColor.copy(alpha = .25f),
        ))),
        content = content,
    )
}
