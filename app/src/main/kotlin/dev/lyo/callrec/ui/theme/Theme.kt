// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Material 3 Expressive theme entry point.
 *
 * - **Color**: dynamic Material You wallpaper extraction on Android 12+
 *   (the only branch that hits at runtime — minSdk is 31), with a curated
 *   fallback palette built from seed #5C42D6.
 *
 * - **Motion**: Expressive scheme. This swaps duration/easing animations
 *   for a spring-physics system that components inherit through
 *   `LocalMotionScheme`. AnimatedContent, AnimatedVisibility, and any
 *   M3 component that takes an `animationSpec` will pick this up
 *   automatically when the spec is omitted.
 *
 * - **Shapes**: the five canonical M3 roles, on-spec. Expressive-only
 *   "extraExtraLarge" / "Increased" variants live in [CallrecShapeTokens]
 *   for opt-in use.
 *
 * - **Typography**: baseline 15-style scale via [CallrecTypography].
 *   The emphasized companion ([CallrecEmphasizedTypography]) is opt-in at
 *   call sites that want extra weight contrast — pulled in via
 *   `MaterialTheme.typography` would override the baseline globally and
 *   make every body text bold, which is exactly the wrong move.
 *
 * Edge-to-edge insets are owned by `MainActivity.enableEdgeToEdge`.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CallrecTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> fallbackDarkScheme()
        else -> fallbackLightScheme()
    }

    MaterialExpressiveTheme(
        colorScheme = colors,
        motionScheme = MotionScheme.expressive(),
        shapes = CallrecShapes,
        typography = CallrecTypography,
        content = content,
    )
}

/**
 * Full M3 ColorScheme — every role explicitly assigned, including the
 * surface-container family, outlineVariant, the inverse trio, scrim, and
 * surfaceTint. Don't drop unspecified roles to defaults: Material 3
 * components that ask for `surfaceContainerHigh` will fall back to
 * `surfaceVariant` if it's missing, which looks washed out and breaks
 * the depth hierarchy.
 */
private fun fallbackLightScheme() = lightColorScheme(
    primary = SeedColors.PrimaryLight,
    onPrimary = SeedColors.OnPrimaryLight,
    primaryContainer = SeedColors.PrimaryContainerLight,
    onPrimaryContainer = SeedColors.OnPrimaryContainerLight,
    secondary = SeedColors.SecondaryLight,
    onSecondary = SeedColors.OnSecondaryLight,
    secondaryContainer = SeedColors.SecondaryContainerLight,
    onSecondaryContainer = SeedColors.OnSecondaryContainerLight,
    tertiary = SeedColors.TertiaryLight,
    onTertiary = SeedColors.OnTertiaryLight,
    tertiaryContainer = SeedColors.TertiaryContainerLight,
    onTertiaryContainer = SeedColors.OnTertiaryContainerLight,
    error = SeedColors.ErrorLight,
    onError = SeedColors.OnErrorLight,
    errorContainer = SeedColors.ErrorContainerLight,
    onErrorContainer = SeedColors.OnErrorContainerLight,
    background = SeedColors.BackgroundLight,
    onBackground = SeedColors.OnBackgroundLight,
    surface = SeedColors.SurfaceLight,
    onSurface = SeedColors.OnSurfaceLight,
    surfaceVariant = SeedColors.SurfaceVariantLight,
    onSurfaceVariant = SeedColors.OnSurfaceVariantLight,
    surfaceTint = SeedColors.SurfaceTintLight,
    inverseSurface = SeedColors.InverseSurfaceLight,
    inverseOnSurface = SeedColors.InverseOnSurfaceLight,
    inversePrimary = SeedColors.InversePrimaryLight,
    outline = SeedColors.OutlineLight,
    outlineVariant = SeedColors.OutlineVariantLight,
    scrim = SeedColors.ScrimLight,
    surfaceBright = SeedColors.SurfaceBrightLight,
    surfaceDim = SeedColors.SurfaceDimLight,
    surfaceContainer = SeedColors.SurfaceContainerLight,
    surfaceContainerHigh = SeedColors.SurfaceContainerHighLight,
    surfaceContainerHighest = SeedColors.SurfaceContainerHighestLight,
    surfaceContainerLow = SeedColors.SurfaceContainerLowLight,
    surfaceContainerLowest = SeedColors.SurfaceContainerLowestLight,
)

private fun fallbackDarkScheme() = darkColorScheme(
    primary = SeedColors.PrimaryDark,
    onPrimary = SeedColors.OnPrimaryDark,
    primaryContainer = SeedColors.PrimaryContainerDark,
    onPrimaryContainer = SeedColors.OnPrimaryContainerDark,
    secondary = SeedColors.SecondaryDark,
    onSecondary = SeedColors.OnSecondaryDark,
    secondaryContainer = SeedColors.SecondaryContainerDark,
    onSecondaryContainer = SeedColors.OnSecondaryContainerDark,
    tertiary = SeedColors.TertiaryDark,
    onTertiary = SeedColors.OnTertiaryDark,
    tertiaryContainer = SeedColors.TertiaryContainerDark,
    onTertiaryContainer = SeedColors.OnTertiaryContainerDark,
    error = SeedColors.ErrorDark,
    onError = SeedColors.OnErrorDark,
    errorContainer = SeedColors.ErrorContainerDark,
    onErrorContainer = SeedColors.OnErrorContainerDark,
    background = SeedColors.BackgroundDark,
    onBackground = SeedColors.OnBackgroundDark,
    surface = SeedColors.SurfaceDark,
    onSurface = SeedColors.OnSurfaceDark,
    surfaceVariant = SeedColors.SurfaceVariantDark,
    onSurfaceVariant = SeedColors.OnSurfaceVariantDark,
    surfaceTint = SeedColors.SurfaceTintDark,
    inverseSurface = SeedColors.InverseSurfaceDark,
    inverseOnSurface = SeedColors.InverseOnSurfaceDark,
    inversePrimary = SeedColors.InversePrimaryDark,
    outline = SeedColors.OutlineDark,
    outlineVariant = SeedColors.OutlineVariantDark,
    scrim = SeedColors.ScrimDark,
    surfaceBright = SeedColors.SurfaceBrightDark,
    surfaceDim = SeedColors.SurfaceDimDark,
    surfaceContainer = SeedColors.SurfaceContainerDark,
    surfaceContainerHigh = SeedColors.SurfaceContainerHighDark,
    surfaceContainerHighest = SeedColors.SurfaceContainerHighestDark,
    surfaceContainerLow = SeedColors.SurfaceContainerLowDark,
    surfaceContainerLowest = SeedColors.SurfaceContainerLowestDark,
)
