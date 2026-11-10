package com.bxane.nuvx.ui.tv.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import com.bxane.nuvx.R
import com.bxane.nuvx.data.settings.TvSettings

@Immutable
data class TvColorPalette(
    val accentRed: Color,
    val accentRedGlow: Color,
    val accentPink: Color,
    val accentPurple: Color,
    val accentBlue: Color,

    val background: Color,
    val backgroundElevated: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val surfaceFocused: Color,
    val surfaceSelected: Color,

    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,

    val borderSubtle: Color,
    val borderFocused: Color,
    val borderFocusedWhite: Color,

    val scrimDark: Color,
    val glassOverlay: Color,
    val glassOverlayFocused: Color,

    val backgroundGradient: Brush,

    val isDark: Boolean,

    val canvasTop: Color = Color(0xFF26272C),
    val canvasBottom: Color = Color(0xFF101012),
    val tintsCanvas: Boolean = true,
)

val TvDarkPalette = TvColorPalette(
    accentRed = Color(0xFFFFFFFF),
    accentRedGlow = Color(0x66FFFFFF),
    accentPink = Color(0xFFE5E5EA),
    accentPurple = Color(0xFFD1D1D6),
    accentBlue = Color(0xFF0A84FF),

    background = Color(0xFF08080B),
    backgroundElevated = Color(0xFF101015),
    surface = Color(0xFF17171E),
    surfaceVariant = Color(0xFF22222C),
    surfaceFocused = Color(0xFF323242),
    surfaceSelected = Color(0xFF38151D),

    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFA0A0AB),
    textMuted = Color(0xFF6E6E7A),

    borderSubtle = Color(0xFF282834),
    borderFocused = Color(0xFFFFFFFF),
    borderFocusedWhite = Color(0xFFFFFFFF),

    scrimDark = Color(0xCC000000),
    glassOverlay = Color(0x3320202E),
    glassOverlayFocused = Color(0x44FFFFFF),

    backgroundGradient = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF150D18),
            Color(0xFF0A090D),
            Color(0xFF050507),
        ),
    ),

    isDark = true,
)

val TvOledPalette = TvColorPalette(
    accentRed = Color(0xFFFFFFFF),
    accentRedGlow = Color(0x66FFFFFF),
    accentPink = Color(0xFFE5E5EA),
    accentPurple = Color(0xFFD1D1D6),
    accentBlue = Color(0xFF0A84FF),

    background = Color(0xFF000000),
    backgroundElevated = Color(0xFF080808),
    surface = Color(0xFF0E0E0E),
    surfaceVariant = Color(0xFF161616),
    surfaceFocused = Color(0xFF282828),
    surfaceSelected = Color(0xFF202020),

    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFA5A5B0),
    textMuted = Color(0xFF666670),

    borderSubtle = Color(0xFF1A1A1A),
    borderFocused = Color(0xFFFFFFFF),
    borderFocusedWhite = Color(0xFFFFFFFF),

    scrimDark = Color(0xFA000000),
    glassOverlay = Color(0x22000000),
    glassOverlayFocused = Color(0x44FFFFFF),

    backgroundGradient = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF000000),
            Color(0xFF000000),
            Color(0xFF000000),
        ),
    ),

    isDark = true,
    canvasTop = Color(0xFF000000),
    canvasBottom = Color(0xFF000000),
    tintsCanvas = false,
)

val TvMidnightPalette = TvColorPalette(
    accentRed = Color(0xFF0A84FF),
    accentRedGlow = Color(0x660A84FF),
    accentPink = Color(0xFF5E5CE6),
    accentPurple = Color(0xFFBF5AF2),
    accentBlue = Color(0xFF64D2FF),

    background = Color(0xFF070B14),
    backgroundElevated = Color(0xFF0D1424),
    surface = Color(0xFF121B30),
    surfaceVariant = Color(0xFF1B2844),
    surfaceFocused = Color(0xFF283B64),
    surfaceSelected = Color(0xFF172B4D),

    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFA0B0CB),
    textMuted = Color(0xFF657595),

    borderSubtle = Color(0xFF1E2E50),
    borderFocused = Color(0xFFFFFFFF),
    borderFocusedWhite = Color(0xFFFFFFFF),

    scrimDark = Color(0xCC000000),
    glassOverlay = Color(0x33121B30),
    glassOverlayFocused = Color(0x44FFFFFF),

    backgroundGradient = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF0D162B),
            Color(0xFF080D1A),
            Color(0xFF050810),
        ),
    ),

    isDark = true,
    canvasTop = Color(0xFF18213A),
    canvasBottom = Color(0xFF070A12),
)



val LocalTvColors = compositionLocalOf { TvDarkPalette }

object TvThemeColors {
    val current: TvColorPalette
        @Composable
        @ReadOnlyComposable
        get() = LocalTvColors.current
}

val TvSFProDisplay = FontFamily(
    Font(R.font.sf_pro_display_regular, FontWeight.W400),
    Font(R.font.sf_pro_display_medium, FontWeight.W500),
    Font(R.font.sf_pro_display_semibold, FontWeight.W600),
    Font(R.font.sf_pro_display_bold, FontWeight.W700),
    Font(R.font.sf_pro_display_heavy, FontWeight.W800),
)

fun createTvTypography(palette: TvColorPalette): Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = TvSFProDisplay,
        fontWeight = FontWeight.W800,
        fontSize = 37.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.7).sp,
        color = palette.textPrimary,
    ),
    displayMedium = TextStyle(
        fontFamily = TvSFProDisplay,
        fontWeight = FontWeight.W800,
        fontSize = 31.sp,
        lineHeight = 37.sp,
        letterSpacing = (-0.5).sp,
        color = palette.textPrimary,
    ),
    displaySmall = TextStyle(
        fontFamily = TvSFProDisplay,
        fontWeight = FontWeight.W700,
        fontSize = 24.sp,
        lineHeight = 31.sp,
        letterSpacing = (-0.3).sp,
        color = palette.textPrimary,
    ),
    headlineLarge = TextStyle(
        fontFamily = TvSFProDisplay,
        fontWeight = FontWeight.W700,
        fontSize = 20.sp,
        lineHeight = 27.sp,
        color = palette.textPrimary,
    ),
    headlineMedium = TextStyle(
        fontFamily = TvSFProDisplay,
        fontWeight = FontWeight.W700,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        color = palette.textPrimary,
    ),
    headlineSmall = TextStyle(
        fontFamily = TvSFProDisplay,
        fontWeight = FontWeight.W600,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        color = palette.textPrimary,
    ),
    titleLarge = TextStyle(
        fontFamily = TvSFProDisplay,
        fontWeight = FontWeight.W600,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        color = palette.textPrimary,
    ),
    titleMedium = TextStyle(
        fontFamily = TvSFProDisplay,
        fontWeight = FontWeight.W600,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        color = palette.textPrimary,
    ),
    titleSmall = TextStyle(
        fontFamily = TvSFProDisplay,
        fontWeight = FontWeight.W500,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = palette.textSecondary,
    ),
    bodyLarge = TextStyle(
        fontFamily = TvSFProDisplay,
        fontWeight = FontWeight.W400,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = palette.textPrimary,
    ),
    bodyMedium = TextStyle(
        fontFamily = TvSFProDisplay,
        fontWeight = FontWeight.W400,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = palette.textSecondary,
    ),
    bodySmall = TextStyle(
        fontFamily = TvSFProDisplay,
        fontWeight = FontWeight.W400,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        color = palette.textMuted,
    ),
    labelLarge = TextStyle(
        fontFamily = TvSFProDisplay,
        fontWeight = FontWeight.W600,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        color = palette.textPrimary,
    ),
    labelMedium = TextStyle(
        fontFamily = TvSFProDisplay,
        fontWeight = FontWeight.W600,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        color = palette.textSecondary,
    ),
    labelSmall = TextStyle(
        fontFamily = TvSFProDisplay,
        fontWeight = FontWeight.W600,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        color = palette.textMuted,
    ),
)

object TvDimensions {
    val SafeMarginHorizontal = 38.dp
    val SafeMarginVertical = 22.dp
    val CardSpacing = 16.dp
    val ShelfSpacing = 24.dp
    val TopNavBarHeight = 51.dp

    val ContentTop = 69.dp

    val NavigationRailWidth = 61.dp
    val NavigationRailExpandedWidth = 176.dp
}

object TvGlass {
    val Fill = Color.White.copy(alpha = 0.10f)
    val FillStrong = Color.White.copy(alpha = 0.18f)
    val FillSelected = Color.White.copy(alpha = 0.28f)
    val Bar = Color.White.copy(alpha = 0.08f)
    val Placeholder = Color(0xFF2E2F33)
    val Hairline = Color.White.copy(alpha = 0.10f)

    val Platter = Color.White
    val OnPlatter = Color(0xFF0C0C0E)
    val OnPlatterSecondary = Color(0xFF0C0C0E).copy(alpha = 0.60f)

    val TextPrimary = Color.White
    val TextSecondary = Color.White.copy(alpha = 0.60f)
    val TextTertiary = Color.White.copy(alpha = 0.38f)

    val AppleRed = Color(0xFFFA2D48)
    val Destructive = Color(0xFFFF453A)
}

object TvType {
    val LargeTitle = TextStyle(fontFamily = TvSFProDisplay, fontWeight = FontWeight.W700, fontSize = 29.sp, lineHeight = 34.sp, letterSpacing = (-0.3).sp)
    val Title = TextStyle(fontFamily = TvSFProDisplay, fontWeight = FontWeight.W700, fontSize = 22.sp, lineHeight = 27.sp, letterSpacing = (-0.3).sp)
    val Headline = TextStyle(fontFamily = TvSFProDisplay, fontWeight = FontWeight.W600, fontSize = 16.sp, lineHeight = 20.sp)
    val Body = TextStyle(fontFamily = TvSFProDisplay, fontWeight = FontWeight.W500, fontSize = 14.sp, lineHeight = 18.sp)
    val Callout = TextStyle(fontFamily = TvSFProDisplay, fontWeight = FontWeight.W400, fontSize = 13.sp, lineHeight = 18.sp)
    val CardTitle = TextStyle(fontFamily = TvSFProDisplay, fontWeight = FontWeight.W500, fontSize = 12.sp, lineHeight = 15.sp)
    val Caption = TextStyle(fontFamily = TvSFProDisplay, fontWeight = FontWeight.W400, fontSize = 11.sp, lineHeight = 14.sp)
    val Tab = TextStyle(fontFamily = TvSFProDisplay, fontWeight = FontWeight.W600, fontSize = 13.sp, lineHeight = 17.sp)
}

val LocalTvFontFamily = compositionLocalOf { TvSFProDisplay }

@Composable
fun NuvxTvTheme(
    content: @Composable () -> Unit,
) {
    val tvThemeId by TvSettings.tvTheme.collectAsState()
    val tvUiScale by TvSettings.tvUiScale.collectAsState()

    val palette = when (tvThemeId.lowercase()) {
        "pure_black", "oled", "pure_black_oled" -> TvOledPalette
        "midnight" -> TvMidnightPalette
        else -> TvDarkPalette
    }

    val colorScheme = darkColorScheme(
        primary = palette.accentRed,
        onPrimary = Color.Black,
        primaryContainer = palette.surfaceVariant,
        onPrimaryContainer = palette.textPrimary,
        secondary = palette.accentPink,
        onSecondary = Color.Black,
        background = palette.background,
        onBackground = palette.textPrimary,
        surface = palette.surface,
        onSurface = palette.textPrimary,
        surfaceVariant = palette.surfaceVariant,
        onSurfaceVariant = palette.textSecondary,
        border = palette.borderSubtle,
    )

    val typography = remember(palette) {
        createTvTypography(palette)
    }

    val baseDensity = LocalDensity.current
    val scaledDensity = remember(baseDensity, tvUiScale) {
        Density(density = baseDensity.density * tvUiScale, fontScale = baseDensity.fontScale * tvUiScale)
    }

    CompositionLocalProvider(
        LocalTvColors provides palette,
        LocalTvFontFamily provides TvSFProDisplay,
        LocalDensity provides scaledDensity,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content,
        )
    }
}
