package dev.pocketreviewer.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/*
 * Pocket Reviewer palette.
 *
 * Goals: inviting enough to want to open, quiet enough to read reviews in for a long
 * time. So: one brand hue (the indigo of the launcher icon), cool slate neutrals,
 * and colour reserved for meaning — green = approved/ok, amber = needs attention,
 * red = failed, violet = merged. Backgrounds are never pure white or pure black, and
 * the dark theme uses lighter, less saturated tones of each hue to avoid glare.
 *
 * No screen should hardcode a colour: use MaterialTheme.colorScheme for the standard
 * roles and AppTheme.colors for the semantic extras below.
 */

private val LightScheme = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE5E8FF),
    onPrimaryContainer = Color(0xFF1E1B4B),
    // Secondary stays in the brand family: it colours the selected tab and chips.
    secondary = Color(0xFF565E8F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE1E5FA),
    onSecondaryContainer = Color(0xFF1B2050),
    tertiary = Color(0xFF7C3AED),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEDE9FE),
    onTertiaryContainer = Color(0xFF3B1A78),
    background = Color(0xFFF7F8FB),
    onBackground = Color(0xFF111827),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFEDF0F6),
    onSurfaceVariant = Color(0xFF4B5567),
    surfaceTint = Color(0xFF4F46E5),
    outline = Color(0xFF8A94A6),
    outlineVariant = Color(0xFFD3D9E4),
    error = Color(0xFFC62828),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFDE7E7),
    onErrorContainer = Color(0xFF7A1C1C),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFAAB4FF),
    onPrimary = Color(0xFF1B1F5C),
    primaryContainer = Color(0xFF2E3270),
    onPrimaryContainer = Color(0xFFE1E4FF),
    secondary = Color(0xFFBFC6F2),
    onSecondary = Color(0xFF21264F),
    secondaryContainer = Color(0xFF2C3157),
    onSecondaryContainer = Color(0xFFDEE2FF),
    tertiary = Color(0xFFC0A6FF),
    onTertiary = Color(0xFF2A1260),
    tertiaryContainer = Color(0xFF3F2A78),
    onTertiaryContainer = Color(0xFFEBE3FF),
    background = Color(0xFF10131A),
    onBackground = Color(0xFFE3E7EF),
    surface = Color(0xFF161A23),
    onSurface = Color(0xFFE3E7EF),
    surfaceVariant = Color(0xFF1F2430),
    onSurfaceVariant = Color(0xFFA3ADBF),
    surfaceTint = Color(0xFFAAB4FF),
    outline = Color(0xFF596274),
    outlineVariant = Color(0xFF333A49),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF4A0E0B),
    errorContainer = Color(0xFF4A1C1A),
    onErrorContainer = Color(0xFFFFD3CF),
)

/** Semantic colours Material's scheme has no role for. */
@Immutable
data class AppColors(
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    /** A merged pull request. */
    val merge: Color,
    /** Background and text of blocks that show review text, rules or logs. */
    val codeBackground: Color,
    val codeText: Color,
    val codeMuted: Color,
    val codeDivider: Color,
)

private val LightAppColors = AppColors(
    success = Color(0xFF15803D),
    successContainer = Color(0xFFDDF5E4),
    onSuccessContainer = Color(0xFF0F4F27),
    warning = Color(0xFFB45309),
    warningContainer = Color(0xFFFDF0D2),
    onWarningContainer = Color(0xFF6B3A06),
    merge = Color(0xFF7C3AED),
    codeBackground = Color(0xFFF1F3F8),
    codeText = Color(0xFF1F2937),
    codeMuted = Color(0xFF5B6577),
    codeDivider = Color(0xFFD9DEE8),
)

private val DarkAppColors = AppColors(
    success = Color(0xFF5FD38D),
    successContainer = Color(0xFF143524),
    onSuccessContainer = Color(0xFFBFEFD0),
    warning = Color(0xFFF2B84B),
    warningContainer = Color(0xFF3A2D12),
    onWarningContainer = Color(0xFFFBE2A8),
    merge = Color(0xFFB79CFF),
    codeBackground = Color(0xFF0B0E14),
    codeText = Color(0xFFD9DEE8),
    codeMuted = Color(0xFF8A94A6),
    codeDivider = Color(0xFF232936),
)

private val LocalAppColors = staticCompositionLocalOf { LightAppColors }

object AppTheme {
    val colors: AppColors
        @Composable @ReadOnlyComposable get() = LocalAppColors.current
}

/** App theme. Follows the system light/dark setting. */
@Composable
fun PocketReviewerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme: ColorScheme = if (darkTheme) DarkScheme else LightScheme

    // Paint the system bars to match, with icons that contrast.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = scheme.background.toArgb()
            window.navigationBarColor = scheme.surfaceColorAtElevation(3.dp).toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalAppColors provides if (darkTheme) DarkAppColors else LightAppColors) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}
