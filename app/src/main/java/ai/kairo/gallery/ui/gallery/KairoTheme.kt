package ai.kairo.gallery.ui.gallery

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Extra colours the gallery uses beyond Material's scheme. */
@Immutable
data class KairoColors(
    val background: Color,
    val surface: Color,        // cards, search pill
    val surfaceHigh: Color,    // chips, placeholders
    val text: Color,
    val textSecondary: Color,
    val divider: Color,
    val accent: Color,
    /** The "AI" gradient used for glows, sparkles and the answer card. */
    val ai: List<Color>,
)

private val AiColors = listOf(Color(0xFF7C5CFF), Color(0xFF3D8BFF), Color(0xFF00C6FF), Color(0xFFFF5FA2), Color(0xFF7C5CFF))

private val Light = KairoColors(
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFF3F4F7),
    surfaceHigh = Color(0xFFE9EBF0),
    text = Color(0xFF121418),
    textSecondary = Color(0xFF7D828C),
    divider = Color(0xFFE6E8EC),
    accent = Color(0xFF3D6BFF),
    ai = AiColors,
)

private val Dark = KairoColors(
    background = Color(0xFF000000),
    surface = Color(0xFF16181C),
    surfaceHigh = Color(0xFF24272D),
    text = Color(0xFFF2F3F5),
    textSecondary = Color(0xFF8D929B),
    divider = Color(0xFF26292F),
    accent = Color(0xFF6C8CFF),
    ai = AiColors,
)

val LocalKairoColors = staticCompositionLocalOf { Light }

object Kairo {
    val colors: KairoColors
        @Composable get() = LocalKairoColors.current
}

private val KairoTypography = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp),
)

@Composable
fun KairoTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) Dark else Light
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = c.accent, background = c.background, surface = c.background, onBackground = c.text, onSurface = c.text,
        // Material buttons in the Kairo chat (tonal, outlined) in Kairo's greys instead of Material's purple tints.
        secondaryContainer = c.surfaceHigh, onSecondaryContainer = c.text, surfaceContainerHigh = c.surface,
        onSurfaceVariant = c.textSecondary, outline = c.textSecondary, outlineVariant = c.divider,
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalKairoColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = KairoTypography, content = content)
    }
}
