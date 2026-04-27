// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.aidl;

import android.os.ParcelFileDescriptor;

/**
 * Bridge between the user-facing app process (UID u0_aXXX) and the privileged
 * recorder running inside the Shizuku UserService process (UID 2000 = shell).
 *
 * The AIDL transaction codes are pinned via the `= N` markers so that a daemon
 * UserService surviving an APK upgrade keeps a stable wire format with old
 * clients. Add new methods with the next free integer; never reuse a slot.
 */
interface IRecorderService {

    /**
     * Returns this service's BuildConfig.VERSION_CODE so the client can detect
     * a stale daemon (e.g. after an APK upgrade) and rebind with `remove=true`.
     */
    int getVersion() = 1;

    /**
     * Open two parallel AudioRecord instances and stream raw 16-bit little-endian
     * PCM into the supplied pipes. Caller writes to the pipe's read-end.
     *
     * @param uplinkSource   MediaRecorder.AudioSource (e.g. VOICE_UPLINK = 2, MIC = 1)
     * @param downlinkSource MediaRecorder.AudioSource (e.g. VOICE_DOWNLINK = 3)
     * @param sampleRate     16_000 recommended; 8_000 / 48_000 also valid
     * @param uplinkFd       write-end of pipe; service writes mono PCM
     * @param downlinkFd     write-end of pipe; service writes mono PCM
     *
     * @return result bitmask:
     *           bit0 (0x01) — uplink stream started successfully
     *           bit1 (0x02) — downlink stream started successfully
     *           0           — both failed; caller must release pfds and try fallback
     */
    int startDualRecord(
        int uplinkSource,
        int downlinkSource,
        int sampleRate,
        in ParcelFileDescriptor uplinkFd,
        in ParcelFileDescriptor downlinkFd
    ) = 2;

    /**
     * Single-stream fallback (used when dual-stream is denied by the audio HAL,
     * notably on Samsung One UI 5.1+).
     *
     * @param source       MediaRecorder.AudioSource (typically VOICE_CALL = 4 or MIC = 1)
     * @param sampleRate   16_000 / 8_000 / 48_000
     * @param channelMask  AudioFormat.CHANNEL_IN_MONO (16) or CHANNEL_IN_STEREO (12)
     * @param pcmFd        write-end of pipe; service writes interleaved PCM if stereo
     *
     * @return 1 on success, 0 on failure
     */
    int startSingleRecord(
        int source,
        int sampleRate,
        int channelMask,
        in ParcelFileDescriptor pcmFd
    ) = 3;

    /** Stops any active recording. Safe to call from idle. */
    void stop() = 10;

    /**
     * @return one of:
     *           0 — idle
     *           1 — starting (transient)
     *           2 — recording dual
     *           3 — recording single
     *           9 — error (call getLastError for details)
     */
    int getState() = 11;

    /** Last error string from the recorder threads, or null. */
    @nullable
    String getLastError() = 12;

    /**
     * Probe an AudioSource: allocate an AudioRecord for `durationMs`, read PCM,
     * compute RMS. Used to detect HAL muting (Samsung policy) before a live call.
     *
     * @return RMS as int (>=0), or -1 if AudioRecord could not be initialised,
     *         -2 if the read failed
     */
    int probeSource(int source, int sampleRate, int durationMs) = 20;

    /**
     * Run `pm grant <packageName> <permission>` from the shell UID. Used to
     * one-shot grant `READ_LOGS` / `WRITE_SECURE_SETTINGS` to the app process.
     *
     * @return true if the grant command exited with status 0
     */
    boolean grantPermission(String packageName, String permission) = 30;
}
