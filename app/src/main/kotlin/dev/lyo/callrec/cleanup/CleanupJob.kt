// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.cleanup

import dev.lyo.callrec.core.L
import dev.lyo.callrec.settings.AppSettings
import dev.lyo.callrec.storage.BulkOps
import dev.lyo.callrec.storage.CallRecord
import dev.lyo.callrec.storage.RecordingsDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Auto-cleanup orchestrator. Runs at app start (fire-and-forget on the
 * application scope). Both policies are opt-in:
 *  - max-age   — delete recordings older than N days.
 *  - max-size  — delete oldest non-favourite until total size is under cap.
 *
 * Favourites are NEVER deleted, even when they push the storage over the cap.
 * If both policies are configured, max-age runs first so we don't waste a
 * size-cap pass on rows we're going to drop anyway.
 *
 * Idempotent — running twice in a row is a no-op the second time, because
 * the first pass already brings the dataset under both limits.
 */
object CleanupJob {

    private const val TAG = "CleanupJob"

    /** Bytes in one gigabyte. We treat the cap as binary GiB — closer to what
     *  Android's storage UI shows than decimal GB and easier to reason about. */
    private const val BYTES_PER_GB: Long = 1024L * 1024L * 1024L

    /** One day in milliseconds — used to convert the user's days knob to a cutoff. */
    private const val MS_PER_DAY: Long = 24L * 60L * 60L * 1000L

    /**
     * Read settings, query DB, prune. Always runs on [Dispatchers.IO] —
     * the caller may invoke from any context (typically the appScope).
     *
     * @param now current time in epoch millis; injected for testability.
     */
    suspend fun runOnce(
        settings: AppSettings,
        db: RecordingsDb,
        now: Long = System.currentTimeMillis(),
    ): Unit = withContext(Dispatchers.IO) {
        val maxAgeDays = settings.autoCleanupMaxAgeDays.first()
        val maxSizeGb = settings.autoCleanupMaxSizeGb.first()

        if (maxAgeDays == null && maxSizeGb == null) {
            L.d(TAG, "both policies off — skip")
            return@withContext
        }

        val dao = db.calls()
        var prunedAge = 0
        if (maxAgeDays != null) {
            val cutoffMs = now - maxAgeDays * MS_PER_DAY
            val victims = dao.selectOlderThan(cutoffMs)
            if (victims.isNotEmpty()) {
                L.i(TAG, "max-age=${maxAgeDays}d → pruning ${victims.size} record(s) older than $cutoffMs")
                BulkOps.deleteFiles(victims)
                prunedAge = dao.deleteOlderThan(cutoffMs)
            } else {
                L.d(TAG, "max-age=${maxAgeDays}d → nothing to prune")
            }
        }

        var prunedSize = 0
        if (maxSizeGb != null) {
            prunedSize = enforceSizeCap(db, maxSizeGb)
        }

        L.i(TAG, "done — age-pruned=$prunedAge size-pruned=$prunedSize")
    }

    /**
     * Walk the full table (favourites + non-favourites) to compute total
     * size, then delete oldest non-favourite recordings one at a time
     * until we're under the cap. Favourites count toward the total but are
     * never selected for deletion.
     *
     * Edge case: if the cap is so small that pruning every non-favourite
     * still leaves us over (e.g. a single 5 GB favourite vs a 1 GB cap),
     * we stop at the most recent non-favourite — never wipe the whole
     * non-favourite set down to zero. That matches the spec: "still keep
     * the most recent record. Never wipe to zero."
     */
    private suspend fun enforceSizeCap(db: RecordingsDb, capGb: Int): Int {
        val capBytes = capGb.toLong() * BYTES_PER_GB
        val dao = db.calls()
        // Snapshot of non-favourites, oldest first. Favourites are excluded
        // from the deletion candidate list, but we still account for their
        // bytes when measuring total size below.
        val candidates = dao.selectOldestNotFavorite()
        val all = dao.observeAll().first()

        var totalBytes = all.sumOf { recordBytes(it) }
        if (totalBytes <= capBytes) {
            L.d(TAG, "max-size=${capGb}GB → already under cap (totalBytes=$totalBytes)")
            return 0
        }

        L.i(TAG, "max-size=${capGb}GB → over cap by ${totalBytes - capBytes} bytes; pruning oldest")

        // Keep at least the newest non-favourite. The list is ASC by started_at,
        // so the LAST entry is the newest non-favourite — drop it from the
        // candidate set so the size cap can never wipe everything to zero.
        val deletable = if (candidates.size <= 1) emptyList() else candidates.dropLast(1)

        val toDelete = mutableListOf<CallRecord>()
        for (c in deletable) {
            if (totalBytes <= capBytes) break
            toDelete += c
            totalBytes -= recordBytes(c)
        }

        if (toDelete.isEmpty()) {
            L.w(TAG, "max-size=${capGb}GB → nothing to delete (favourites push over cap?)")
            return 0
        }

        BulkOps.deleteFiles(toDelete)
        dao.deleteAll(toDelete.map { it.callId })
        return toDelete.size
    }

    /** Sum of uplink and (optional) downlink file sizes on disk. Missing
     *  files contribute 0 bytes — matches what `BulkOps.deleteFiles` would
     *  free up if asked. */
    private fun recordBytes(r: CallRecord): Long {
        val up = runCatching { File(r.uplinkPath).length() }.getOrDefault(0L)
        val dn = r.downlinkPath?.let { p ->
            runCatching { File(p).length() }.getOrDefault(0L)
        } ?: 0L
        return up + dn
    }
}
