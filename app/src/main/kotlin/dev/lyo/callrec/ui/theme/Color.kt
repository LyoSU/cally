// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Static fallback ColorScheme for the rare device that opts out of
 * dynamic-Material-You wallpaper extraction (minSdk 31 makes that
 * theoretically impossible at runtime, but the harness ships an opt-out so
 * the brand identity has to stand on its own).
 *
 * Generated against seed #5C42D6 (saturated violet) using the M3 HCT tonal
 * palette algorithm — every role is keyed to a specific tone on the seed's
 * tonal palette so light/dark contrast stays balanced.
 *
 * The role list is the **complete** Material 3 Expressive set: every
 * surface-container tier (lowest → highest), surface-bright/dim, the inverse
 * trio, outlineVariant, scrim, and surfaceTint. Without these, dialog/sheet
 * elevation looks washed out and any component that asks for
 * surfaceContainerHigh falls back to MaterialTheme defaults.
 *
 * Tone numbers below reference the Material HCT tone scale (0=black, 100=white).
 * Light scheme: surface tones 98 → container tones 92/94/96.
 * Dark scheme:  surface tone 6 → container tones 12/17/22/24/22.
 */
internal object SeedColors {
    // ─── Light scheme ────────────────────────────────────────────────────
    val PrimaryLight = Color(0xFF5C42D6)
    val OnPrimaryLight = Color(0xFFFFFFFF)
    val PrimaryContainerLight = Color(0xFFE6DEFF)
    val OnPrimaryContainerLight = Color(0xFF1A0F4A)

    val SecondaryLight = Color(0xFF625B70)
    val OnSecondaryLight = Color(0xFFFFFFFF)
    val SecondaryContainerLight = Color(0xFFE9DFF8)
    val OnSecondaryContainerLight = Color(0xFF1E182B)

    val TertiaryLight = Color(0xFF7E5260)
    val OnTertiaryLight = Color(0xFFFFFFFF)
    val TertiaryContainerLight = Color(0xFFFFD9E3)
    val OnTertiaryContainerLight = Color(0xFF31101D)

    val ErrorLight = Color(0xFFBA1A1A)
    val OnErrorLight = Color(0xFFFFFFFF)
    val ErrorContainerLight = Color(0xFFFFDAD6)
    val OnErrorContainerLight = Color(0xFF410002)

    val BackgroundLight = Color(0xFFFEF7FF)
    val OnBackgroundLight = Color(0xFF1C1B1F)
    val SurfaceLight = Color(0xFFFEF7FF)
    val OnSurfaceLight = Color(0xFF1C1B1F)
    val SurfaceVariantLight = Color(0xFFE6E0EC)
    val OnSurfaceVariantLight = Color(0xFF49454F)
    val OutlineLight = Color(0xFF7A757F)
    val OutlineVariantLight = Color(0xFFCAC4D0)

    // Tone-based surface containers (replaces the old opacity-overlay system).
    // Each tier is a discrete tonal step — surfaceContainer is the canonical
    // "card on background" colour, the others are for stacking depth.
    val SurfaceDimLight = Color(0xFFDED8E1)
    val SurfaceBrightLight = Color(0xFFFEF7FF)
    val SurfaceContainerLowestLight = Color(0xFFFFFFFF)
    val SurfaceContainerLowLight = Color(0xFFF7F2FA)
    val SurfaceContainerLight = Color(0xFFF2ECF4)
    val SurfaceContainerHighLight = Color(0xFFECE6EE)
    val SurfaceContainerHighestLight = Color(0xFFE6E0E9)

    val InverseSurfaceLight = Color(0xFF313033)
    val InverseOnSurfaceLight = Color(0xFFF4EFF4)
    val InversePrimaryLight = Color(0xFFC9BFFF)

    val SurfaceTintLight = PrimaryLight
    val ScrimLight = Color(0xFF000000)

    // ─── Dark scheme ─────────────────────────────────────────────────────
    val PrimaryDark = Color(0xFFC9BFFF)
    val OnPrimaryDark = Color(0xFF2C1E80)
    val PrimaryContainerDark = Color(0xFF433390)
    val OnPrimaryContainerDark = Color(0xFFE6DEFF)

    val SecondaryDark = Color(0xFFCDC4DD)
    val OnSecondaryDark = Color(0xFF332E40)
    val SecondaryContainerDark = Color(0xFF4A4458)
    val OnSecondaryContainerDark = Color(0xFFEAE0F8)

    val TertiaryDark = Color(0xFFEFB8C8)
    val OnTertiaryDark = Color(0xFF4A2532)
    val TertiaryContainerDark = Color(0xFF633B48)
    val OnTertiaryContainerDark = Color(0xFFFFD9E3)

    val ErrorDark = Color(0xFFFFB4AB)
    val OnErrorDark = Color(0xFF690005)
    val ErrorContainerDark = Color(0xFF93000A)
    val OnErrorContainerDark = Color(0xFFFFDAD6)

    val BackgroundDark = Color(0xFF14111C)
    val OnBackgroundDark = Color(0xFFE6E1E9)
    val SurfaceDark = Color(0xFF14111C)
    val OnSurfaceDark = Color(0xFFE6E1E9)
    val SurfaceVariantDark = Color(0xFF49454F)
    val OnSurfaceVariantDark = Color(0xFFCAC4D0)
    val OutlineDark = Color(0xFF948F99)
    val OutlineVariantDark = Color(0xFF49454F)

    val SurfaceDimDark = Color(0xFF14111C)
    val SurfaceBrightDark = Color(0xFF3B3742)
    val SurfaceContainerLowestDark = Color(0xFF0F0C18)
    val SurfaceContainerLowDark = Color(0xFF1C1B1F)
    val SurfaceContainerDark = Color(0xFF211F26)
    val SurfaceContainerHighDark = Color(0xFF2B2930)
    val SurfaceContainerHighestDark = Color(0xFF36343B)

    val InverseSurfaceDark = Color(0xFFE6E1E9)
    val InverseOnSurfaceDark = Color(0xFF313033)
    val InversePrimaryDark = Color(0xFF5C42D6)

    val SurfaceTintDark = PrimaryDark
    val ScrimDark = Color(0xFF000000)
}
