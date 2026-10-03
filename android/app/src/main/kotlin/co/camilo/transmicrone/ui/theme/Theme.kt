package co.camilo.transmicrone.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val TransitRed = Color(0xFF9F4034)

internal val LightColors = lightColorScheme(
    primary = TransitRed, onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD4), onPrimaryContainer = Color(0xFF3F0702),
    secondary = Color(0xFF775750), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDAD4), onSecondaryContainer = Color(0xFF2C1510),
    tertiary = Color(0xFF6C5D2F), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF5E1A7), onTertiaryContainer = Color(0xFF241A00),
    surface = Color(0xFFFFF8F6), onSurface = Color(0xFF231917),
    background = Color(0xFFFFF8F6), onBackground = Color(0xFF231917),
    surfaceVariant = Color(0xFFF5DDD8), onSurfaceVariant = Color(0xFF53433F),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFFF0EC),
    surfaceContainer = Color(0xFFFCEAE5), surfaceContainerHigh = Color(0xFFF6E4DF),
    surfaceContainerHighest = Color(0xFFF0DED9),
    outline = Color(0xFF85736E), outlineVariant = Color(0xFFD8C2BC),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB4A8), onPrimary = Color(0xFF601409),
    primaryContainer = Color(0xFF80281E), onPrimaryContainer = Color(0xFFFFDAD4),
    secondary = Color(0xFFE7BDB5), onSecondary = Color(0xFF442A24),
    secondaryContainer = Color(0xFF5D403A), onSecondaryContainer = Color(0xFFFFDAD4),
    tertiary = Color(0xFFD8C58D), onTertiary = Color(0xFF3A2F05),
    tertiaryContainer = Color(0xFF52461A), onTertiaryContainer = Color(0xFFF5E1A7),
    surface = Color(0xFF1B1210), onSurface = Color(0xFFF8DED8),
    background = Color(0xFF1B1210), onBackground = Color(0xFFF8DED8),
    surfaceVariant = Color(0xFF53433F), onSurfaceVariant = Color(0xFFD8C2BC),
    surfaceContainerLowest = Color(0xFF150C0B), surfaceContainerLow = Color(0xFF231917),
    surfaceContainer = Color(0xFF271D1B), surfaceContainerHigh = Color(0xFF322825),
    surfaceContainerHighest = Color(0xFF3D3230),
    outline = Color(0xFFA08C86), outlineVariant = Color(0xFF53433F),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
)

private val AppTypography = Typography(
    displaySmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 36.sp, lineHeight = 42.sp, letterSpacing = (-0.8).sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.6).sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
)

@Composable
fun TransmicroneTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
    } else if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(12.dp), small = RoundedCornerShape(16.dp),
            medium = RoundedCornerShape(24.dp), large = RoundedCornerShape(28.dp),
            extraLarge = RoundedCornerShape(32.dp),
        ),
        content = content,
    )
}
