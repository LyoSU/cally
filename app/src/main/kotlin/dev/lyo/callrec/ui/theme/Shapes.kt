// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Material 3 Expressive shape scale. Values match the spec — "pillow"
 * radii get progressively roomier so hero containers (FAB, status banner,
 * dialog) read as soft surfaces.
 *
 * | role            | radius |
 * |-----------------|--------|
 * | extraSmall      |  4 dp  |
 * | small           |  8 dp  |
 * | medium          | 12 dp  |
 * | large           | 16 dp  |
 * | extraLarge      | 28 dp  |
 *
 * The five canonical Material 3 [Shapes] roles are kept on-spec so any
 * MD3 component that pulls from `MaterialTheme.shapes` looks correct out
 * of the box. The Expressive-only `extraExtraLarge` (48 dp) plus the
 * "increased" half-step variants live as standalone constants in
 * [CallrecShapeTokens] — Material 3 1.4.0's `Shapes` constructor still
 * exposes only the five canonical roles, so anything beyond them is
 * surfaced as plain `Shape` constants and applied at call sites.
 */
internal val CallrecShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Expressive-only shape tokens not yet exposed by the canonical [Shapes]
 * API. Reach for these explicitly when a hero card / dialog / sheet needs
 * the next step up from `extraLarge`. Keep usage rare — the bigger the
 * radius, the harder it is to read as a "container" rather than a "blob".
 */
internal object CallrecShapeTokens {
    val LargeIncreased = RoundedCornerShape(20.dp)
    val ExtraLargeIncreased = RoundedCornerShape(32.dp)
    val ExtraExtraLarge = RoundedCornerShape(48.dp)
}
