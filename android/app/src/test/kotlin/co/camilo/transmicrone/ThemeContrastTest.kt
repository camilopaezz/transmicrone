package co.camilo.transmicrone

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import co.camilo.transmicrone.ui.theme.DarkColors
import co.camilo.transmicrone.ui.theme.LightColors
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    @Test
    fun textColorsMeetMinimumContrastInBothThemes() {
        for ((name, colors) in listOf("light" to LightColors, "dark" to DarkColors)) {
            val pairs = listOf(
                "primary on surface" to (colors.primary to colors.surface),
                "onPrimary" to (colors.onPrimary to colors.primary),
                "onSurface" to (colors.onSurface to colors.surface),
                "onSurfaceVariant" to (colors.onSurfaceVariant to colors.surface),
                "error on surface" to (colors.error to colors.surface),
                "onPrimaryContainer" to (colors.onPrimaryContainer to colors.primaryContainer),
                "onSecondaryContainer" to (colors.onSecondaryContainer to colors.secondaryContainer),
                "arrival text" to (colors.onSurface to colors.surfaceContainerHigh),
                "arrival detail" to (colors.onSurfaceVariant to colors.surfaceContainerHigh),
            )
            for ((role, pair) in pairs) {
                val ratio = contrast(pair.first, pair.second)
                assertTrue("$name $role contrast $ratio is below 4.5", ratio >= 4.5)
            }
        }
    }

    private fun contrast(foreground: Color, background: Color): Double {
        val first = foreground.luminance().toDouble()
        val second = background.luminance().toDouble()
        return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
    }
}
