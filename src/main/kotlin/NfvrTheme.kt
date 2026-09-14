import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * NFVR's deliberate control-console language: ink-blue surfaces, a cool
 * processing blue, and success green reserved for verified work.
 */
private val NfvrColors = darkColorScheme(
    primary = Color(0xFF61B7FF),
    onPrimary = Color(0xFF062137),
    primaryContainer = Color(0xFF103B5B),
    onPrimaryContainer = Color(0xFFD0E9FF),
    secondary = Color(0xFF8AC9E8),
    onSecondary = Color(0xFF08212C),
    secondaryContainer = Color(0xFF173B4B),
    onSecondaryContainer = Color(0xFFC9EEFF),
    tertiary = Color(0xFF82D5A5),
    onTertiary = Color(0xFF052316),
    tertiaryContainer = Color(0xFF164A30),
    onTertiaryContainer = Color(0xFFA4F2C1),
    error = Color(0xFFFF8D8D),
    onError = Color(0xFF3C090B),
    errorContainer = Color(0xFF5A171C),
    onErrorContainer = Color(0xFFFFDAD8),
    background = Color(0xFF071119),
    onBackground = Color(0xFFE0ECF3),
    surface = Color(0xFF0B1822),
    onSurface = Color(0xFFE0ECF3),
    surfaceVariant = Color(0xFF162A37),
    onSurfaceVariant = Color(0xFFAFC2CC),
    outline = Color(0xFF3A5666),
    outlineVariant = Color(0xFF213A49)
)

private val NfvrTypography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineSmall = headlineSmall.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.copy(fontFamily = FontFamily.SansSerif, lineHeight = 25.sp),
        bodyMedium = bodyMedium.copy(fontFamily = FontFamily.SansSerif, lineHeight = 21.sp),
        labelLarge = labelLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold),
        labelSmall = labelSmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 0.3.sp)
    )
}

@Composable
fun NfvrTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    // The utility stays dark by default: this is a focused Windows console,
    // not a document editor. isSystemInDarkTheme remains available to callers.
    val colors = if (darkTheme || isSystemInDarkTheme()) NfvrColors else NfvrColors
    MaterialTheme(colorScheme = colors, typography = NfvrTypography, content = content)
}