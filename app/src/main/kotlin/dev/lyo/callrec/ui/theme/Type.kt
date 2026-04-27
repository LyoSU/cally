// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Material 3 Expressive type system. Two parallel sets of 15 styles:
 *
 *  - **baseline** ([CallrecTypography]) — the default, used everywhere
 *    typography is implicit (text in cards, list rows, body copy). Roboto
 *    family, conservative weights.
 *
 *  - **emphasized** ([CallrecEmphasizedTypography] — accessed at call sites
 *    that want extra visual weight: hero titles, status banner labels, FAB
 *    text. Heavier weights, tighter letter-spacing on display sizes,
 *    marginally looser on body sizes.
 *
 * Display/headline lean **bolder** (Bold/SemiBold) on small screens because
 * size-based hierarchy is scarce on phones — you have ~360 dp; weight does
 * the heavy lifting. Body text stays Regular so dense paragraphs stay
 * readable.
 *
 * Both sets use [FontFamily.Default]. Switching to Roboto Flex (variable)
 * is a one-line change here once the font is bundled — every TextStyle
 * already references the family by name.
 */

private val Family = FontFamily.Default

// ────────────────────────────────────────────────────────────────────────
// Baseline — the conservative set. Used implicitly via MaterialTheme.typography.
// Rationale per role:
//  - displayLarge/Medium/Small: hero copy. ExtraBold/Bold lean for screens
//    where one big sentence dominates (onboarding title, empty states).
//  - headlineLarge/Medium/Small: section/page titles. SemiBold = clear
//    hierarchy without screaming.
//  - titleLarge/Medium/Small: card and list headings. Medium weight.
//  - bodyLarge/Medium/Small: paragraph text. Regular, generous lineHeight.
//  - labelLarge/Medium/Small: chips, button text, captions. SemiBold so
//    a 12 sp chip label still reads at arm's length.
// ────────────────────────────────────────────────────────────────────────
internal val CallrecTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.ExtraBold,
        fontSize = 57.sp, lineHeight = 64.sp, letterSpacing = (-0.25).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.Bold,
        fontSize = 45.sp, lineHeight = 52.sp, letterSpacing = 0.sp,
    ),
    displaySmall = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.Bold,
        fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = 0.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = 0.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = 0.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.Medium,
        fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.Medium,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp,
    ),
)

// ────────────────────────────────────────────────────────────────────────
// Emphasized — opt-in companion set per the M3 Expressive spec. Pull these
// in at call sites that want the headline/title to do more work without
// having to copy() and tweak fontWeight inline.
//
// Differences from baseline:
//  - Display/headline lean one weight heavier (Black / ExtraBold / Bold).
//  - Title/label sizes stay at SemiBold for legible chip/button copy.
//  - Body sizes pick Medium — used sparingly for callout paragraphs.
// ────────────────────────────────────────────────────────────────────────
internal val CallrecEmphasizedTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.Black,
        fontSize = 57.sp, lineHeight = 64.sp, letterSpacing = (-0.5).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.ExtraBold,
        fontSize = 45.sp, lineHeight = 52.sp, letterSpacing = (-0.25).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.ExtraBold,
        fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = 0.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.ExtraBold,
        fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = 0.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.Bold,
        fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = 0.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.Bold,
        fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.Medium,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp,
    ),
)

@Suppress("unused")
internal val MonoTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontStyle = FontStyle.Normal,
)
