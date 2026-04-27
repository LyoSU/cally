// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.pow

/**
 * Two-row live waveform. Left bars = your voice (uplink), right bars = the
 * other side. Each row is a sliding 32-sample history of the live RMS,
 * visualised as rounded vertical bars.
 *
 * The history-shift loop runs at ~30 fps off the same RMS source — we don't
 * subscribe inside the Canvas, instead we mutate a [SnapshotStateList] which
 * Compose recomposes only when bars actually change.
 *
 * `disabled = true` greys out a row. Used for "Other side: pristrij blocks"
 * cases where downlink isn't captured at all.
 */
@Composable
fun LiveLevelMeter(
    label: String,
    rms: Float,
    color: Color,
    disabled: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val history = remember { mutableStateListOf<Float>().apply { repeat(32) { add(0f) } } }

    // Sample rms/disabled via rememberUpdatedState so the shift-loop reads
    // fresh values on each tick **without restarting itself** every time the
    // upstream rms StateFlow emits — the recorder pumps RMS at ~60 Hz and the
    // earlier `LaunchedEffect(rms, disabled)` was cancelling/restarting the
    // coroutine on every emit, churning the dispatcher.
    val rmsState = rememberUpdatedState(rms)
    val disabledState = rememberUpdatedState(disabled)
    LaunchedEffect(Unit) {
        while (true) {
            history.removeAt(0)
            val v = if (disabledState.value) 0f
            else rmsState.value.coerceIn(0f, 1f).pow(0.6f)
            history.add(v)
            delay(33)
        }
    }

    val animatedRms by animateFloatAsState(
        targetValue = if (disabled) 0f else rms,
        animationSpec = spring(stiffness = 600f),
        label = "rms",
    )

    // Material 3 Expressive halo: a slow infinite scale ring around the
    // status dot, so even at near-silent levels the user can tell "we're
    // listening". Stops when the row is disabled.
    val infinite = rememberInfiniteTransition(label = "live-pulse")
    val pulse by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulse",
    )

    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Status dot with an Expressive expanding halo. The halo radius
            // and alpha cycle once per ~1.4 s independent of the live level;
            // the centre dot's alpha still tracks RMS so the colour intensity
            // reads "louder = brighter".
            Box(modifier = Modifier.size(16.dp)) {
                Canvas(modifier = Modifier.fillMaxWidth()) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val baseR = size.minDimension / 4f
                    if (!disabled) {
                        val haloR = baseR + (size.minDimension / 2f - baseR) * pulse
                        drawCircle(
                            color = color.copy(alpha = (1f - pulse) * 0.45f),
                            radius = haloR,
                            center = Offset(cx, cy),
                        )
                    }
                    drawCircle(
                        color = color.copy(alpha = (0.35f + animatedRms * 1.2f).coerceAtMost(1f)),
                        radius = baseR,
                        center = Offset(cx, cy),
                    )
                }
            }
            Spacer(Modifier.size(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (disabled) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(8.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(top = 4.dp),
        ) {
            val barCount = history.size
            val gap = 3.dp.toPx()
            val totalGap = gap * (barCount - 1)
            val barWidth = ((size.width - totalGap) / barCount).coerceAtLeast(2f)
            val cornerRadius = CornerRadius(barWidth / 2, barWidth / 2)
            history.forEachIndexed { i, v ->
                val height = (v.coerceIn(0f, 1f) * size.height).coerceAtLeast(2f)
                val left = i * (barWidth + gap)
                val top = (size.height - height) / 2
                drawRoundRect(
                    color = color.copy(alpha = if (disabled) 0.18f else 0.35f + v * 0.65f),
                    topLeft = Offset(left, top),
                    size = Size(barWidth, height),
                    cornerRadius = cornerRadius,
                )
            }
        }
    }
}

/**
 * Quality pill — "Найкраща якість" / "Хороша якість" / "Часткова". Compact
 * variant used inside the status hero. Reflects [hasDownlink] and the
 * recorder strategy class without exposing the underlying enum.
 */
@Composable
fun QualityPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(top = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 0.dp),
        ) {
            val derivedColor by remember(color) { derivedStateOf { color.copy(alpha = 0.18f) } }
            androidx.compose.material3.Surface(
                color = derivedColor,
                contentColor = color,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
