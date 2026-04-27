// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.ui.playback

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * Static waveform scrubber for the playback screen.
 *
 * - Single track: one centred waveform mirrored top↓bottom, primary colour for
 *   the played portion, surfaceVariant for the rest.
 * - Dual track: two waveforms stacked — uplink on top half (primary), downlink
 *   on bottom half (tertiary), each mirrored from its own midline. This makes
 *   call rhythm legible at a glance: who-talked-when is exactly the visual.
 *
 * Tap → seek to that x. Horizontal drag → live scrub via [onScrubStart] +
 * [onScrub] (continuous 0..1 progress) + [onScrubEnd] (commit). The contract
 * matches Slider's onValueChange pair so the parent integrates the same way.
 */
@Composable
fun WaveformView(
    primaryBins: FloatArray?,
    secondaryBins: FloatArray?,
    progress: Float,
    onScrubStart: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    // Snapshot colours once per recomposition so the Canvas closure doesn't
    // re-read MaterialTheme inside the draw thread.
    //
    // Expressive palette: played = primary (vibrant), played-alt = tertiary
    // (other side of dual recording). Unplayed leans on surfaceContainer-
    // Highest rather than surfaceVariant — it's the M3 1.5 token that
    // actually has enough tonal separation from the card background to
    // read as a distinct waveform "rest" colour. surfaceVariant on the
    // dynamic-Material-You scheme is barely a shade off the card and
    // visually melts into it.
    val played = cs.primary
    val playedAlt = cs.tertiary
    val unplayed = cs.surfaceContainerHighest
    val unplayedAlt = cs.surfaceContainerHigh
    val playhead = cs.primary
    val playheadGlow = cs.primary.copy(alpha = 0.18f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(84.dp)
            // TalkBack accessibility: expose as a slider so users can scrub
            // via the gesture explorer. Without this, the Canvas is silent
            // to screen readers.
            .semantics {
                contentDescription = "Прогрес відтворення"
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = progress.coerceIn(0f, 1f),
                    range = 0f..1f,
                )
                setProgress { value ->
                    onScrubStart()
                    onScrub(value.coerceIn(0f, 1f))
                    onScrubEnd()
                    true
                }
            }
            .pointerInput(primaryBins, secondaryBins) {
                detectTapGestures { offset ->
                    val frac = (offset.x / size.width).coerceIn(0f, 1f)
                    onScrubStart()
                    onScrub(frac)
                    onScrubEnd()
                }
            }
            .pointerInput(primaryBins, secondaryBins) {
                detectHorizontalDragGestures(
                    onDragStart = { onScrubStart() },
                    onDragEnd = { onScrubEnd() },
                    onDragCancel = { onScrubEnd() },
                ) { change, _ ->
                    val frac = (change.position.x / size.width).coerceIn(0f, 1f)
                    onScrub(frac)
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (primaryBins == null) {
                // Loading — single dim baseline so the area doesn't collapse.
                drawBaseline(unplayed)
                return@Canvas
            }
            val dual = secondaryBins != null
            val halfH = size.height / 2f
            if (dual) {
                drawBins(
                    bins = primaryBins,
                    progress = progress,
                    centerY = halfH * 0.5f,
                    halfHeight = halfH * 0.45f,
                    played = played,
                    unplayed = unplayed,
                )
                drawBins(
                    bins = secondaryBins!!,
                    progress = progress,
                    centerY = halfH + halfH * 0.5f,
                    halfHeight = halfH * 0.45f,
                    played = playedAlt,
                    unplayed = unplayedAlt,
                )
            } else {
                drawBins(
                    bins = primaryBins,
                    progress = progress,
                    centerY = size.height / 2f,
                    halfHeight = size.height / 2f * 0.85f,
                    played = played,
                    unplayed = unplayed,
                )
            }
            // Material 3 Expressive playhead — vertical pill that runs the
            // full waveform height, with a soft glow halo behind it. Reads
            // as a definite "you are here" cue without the ambiguity of
            // hunting for the played/unplayed boundary, especially in
            // silent passages where bins are barely a pixel tall.
            val px = (size.width * progress.coerceIn(0f, 1f))
            val haloHalf = 8f
            drawRoundRect(
                color = playheadGlow,
                topLeft = Offset(px - haloHalf, 0f),
                size = Size(haloHalf * 2f, size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(haloHalf, haloHalf),
            )
            val pillHalf = 2f
            drawRoundRect(
                color = playhead,
                topLeft = Offset(px - pillHalf, 0f),
                size = Size(pillHalf * 2f, size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(pillHalf, pillHalf),
            )
            // Top + bottom thumb caps so the playhead reads as a discrete
            // affordance, not just a thin line.
            val capR = 6f
            drawCircle(color = playhead, radius = capR, center = Offset(px, capR))
            drawCircle(color = playhead, radius = capR, center = Offset(px, size.height - capR))
        }
    }
}

private fun DrawScope.drawBaseline(color: Color) {
    val y = size.height / 2f
    drawLine(
        color = color,
        start = Offset(0f, y),
        end = Offset(size.width, y),
        strokeWidth = 2f,
    )
}

private fun DrawScope.drawBins(
    bins: FloatArray,
    progress: Float,
    centerY: Float,
    halfHeight: Float,
    played: Color,
    unplayed: Color,
) {
    val n = bins.size
    // Slightly wider gap (3 px) to read as discrete pills rather than a
    // dense block — important now that bars sit on the contrastier
    // surfaceContainerHighest backplate.
    val gap = 3f
    val totalGap = gap * (n - 1)
    val barW = max(2.5f, (size.width - totalGap) / n)
    val playedX = size.width * progress.coerceIn(0f, 1f)
    var x = 0f
    val cornerR = androidx.compose.ui.geometry.CornerRadius(barW / 2f, barW / 2f)
    for (i in 0 until n) {
        val amp = bins[i].coerceIn(0f, 1f)
        // Floor amp so silent bars still register — but pad to 3 px so the
        // unplayed track reads as a continuous "ribbon" rather than dotted.
        val h = max(3f, amp * halfHeight * 2f)
        val color = if (x + barW / 2f <= playedX) played else unplayed
        drawRoundRect(
            color = color,
            topLeft = Offset(x, centerY - h / 2f),
            size = Size(barW, h),
            cornerRadius = cornerR,
        )
        x += barW + gap
    }
}
