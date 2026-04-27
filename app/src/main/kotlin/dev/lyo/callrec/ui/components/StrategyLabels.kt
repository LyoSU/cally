// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.lyo.callrec.R
import dev.lyo.callrec.recorder.Strategy

/**
 * One place where the recorder's internal Strategy enum gets translated to
 * end-user copy. Anything outside this file should never embed strings like
 * "VOICE_CALL" — that's our promise to keep the UI free of jargon.
 */
@Composable
fun strategyHumanStatus(strategy: Strategy?): String = when (strategy) {
    Strategy.DualUplinkDownlink, Strategy.DualMicDownlink ->
        stringResource(R.string.rec_status_active_dual)
    Strategy.SingleVoiceCallStereo, Strategy.SingleVoiceCallMono ->
        stringResource(R.string.rec_status_active_single_full)
    Strategy.SingleMic ->
        stringResource(R.string.rec_status_active_mic_only)
    null -> stringResource(R.string.rec_status_idle)
}

@Composable
fun strategyQuality(strategy: Strategy): String = when (strategy) {
    Strategy.DualUplinkDownlink -> stringResource(R.string.rec_quality_excellent)
    Strategy.DualMicDownlink, Strategy.SingleVoiceCallStereo, Strategy.SingleVoiceCallMono ->
        stringResource(R.string.rec_quality_good)
    Strategy.SingleMic -> stringResource(R.string.rec_quality_partial)
}

@Composable
fun friendlyMode(rawMode: String): String =
    runCatching { Strategy.valueOf(rawMode) }.getOrNull()?.let { strategyQuality(it) }
        ?: rawMode
