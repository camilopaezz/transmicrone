package co.camilo.transmicrone.ui.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import co.camilo.transmicrone.R

private val InterfaceFont = FontFamily(
    Font(R.font.adwaita_sans_regular, FontWeight.Normal),
    Font(R.font.adwaita_sans_medium, FontWeight.Medium),
)

val TransmicroneBrandStyle = TextStyle(
    fontFamily = InterfaceFont,
    fontWeight = FontWeight.Medium,
    fontSize = 22.sp,
    lineHeight = 28.sp,
    letterSpacing = (-0.6).sp,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val defaults = Typography()

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal val AppTypography = Typography(
    displayLarge = defaults.displayLarge.copy(fontFamily = InterfaceFont),
    displayMedium = defaults.displayMedium.copy(fontFamily = InterfaceFont),
    displaySmall = defaults.displaySmall.copy(fontFamily = InterfaceFont, fontWeight = FontWeight.Medium, fontSize = 36.sp, lineHeight = 42.sp, letterSpacing = (-0.8).sp),
    headlineLarge = defaults.headlineLarge.copy(fontFamily = InterfaceFont, fontWeight = FontWeight.Medium, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.6).sp),
    headlineMedium = defaults.headlineMedium.copy(fontFamily = InterfaceFont),
    headlineSmall = defaults.headlineSmall.copy(fontFamily = InterfaceFont, fontWeight = FontWeight.Medium),
    titleLarge = defaults.titleLarge.copy(fontFamily = InterfaceFont, fontWeight = FontWeight.Medium),
    titleMedium = defaults.titleMedium.copy(fontFamily = InterfaceFont, fontWeight = FontWeight.Medium),
    titleSmall = defaults.titleSmall.copy(fontFamily = InterfaceFont),
    bodyLarge = defaults.bodyLarge.copy(fontFamily = InterfaceFont),
    bodyMedium = defaults.bodyMedium.copy(fontFamily = InterfaceFont),
    bodySmall = defaults.bodySmall.copy(fontFamily = InterfaceFont, letterSpacing = 0.sp),
    labelLarge = defaults.labelLarge.copy(fontFamily = InterfaceFont, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    labelMedium = defaults.labelMedium.copy(fontFamily = InterfaceFont, letterSpacing = 0.sp),
    labelSmall = defaults.labelSmall.copy(fontFamily = InterfaceFont, letterSpacing = 0.sp),
    headlineSmallEmphasized = defaults.headlineSmallEmphasized.copy(fontFamily = InterfaceFont, fontWeight = FontWeight.Medium),
    titleSmallEmphasized = defaults.titleSmallEmphasized.copy(fontFamily = InterfaceFont, fontWeight = FontWeight.Medium),
    labelLargeEmphasized = defaults.labelLargeEmphasized.copy(fontFamily = InterfaceFont, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
)
