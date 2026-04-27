// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.recorder

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Validates the natural order of [Strategy] — the fallback ladder is encoded
 * in the enum declaration, so the test pins it. If a future change tries to
 * reorder (e.g. move `SingleMic` higher because of a Samsung workaround),
 * this test forces an explicit decision instead of a silent regression.
 */
class StrategyOrderTest {

    @Test fun `enum declaration order is the fallback ladder`() {
        assertThat(Strategy.values().toList()).containsExactly(
            Strategy.DualUplinkDownlink,
            Strategy.DualMicDownlink,
            Strategy.SingleVoiceCallStereo,
            Strategy.SingleVoiceCallMono,
            Strategy.SingleMic,
        ).inOrder()
    }

    @Test fun `every strategy has exactly the right shape`() {
        Strategy.values().forEach { s ->
            if (s.isDual) {
                assertThat(s.uplinkSource).isNotNull()
                assertThat(s.downlinkSource).isNotNull()
                assertThat(s.singleSource).isNull()
                assertThat(s.stereo).isFalse()
            } else {
                assertThat(s.uplinkSource).isNull()
                assertThat(s.downlinkSource).isNull()
                assertThat(s.singleSource).isNotNull()
            }
        }
    }
}
