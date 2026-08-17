// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.ui.playback

import android.media.MediaPlayer
import android.media.PlaybackParams
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lyo.callrec.R
import dev.lyo.callrec.codec.Waveform
import dev.lyo.callrec.core.L
import dev.lyo.callrec.di.AppContainer
import dev.lyo.callrec.storage.CallRecord
import dev.lyo.callrec.storage.RecordingPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToLong

private val SPEED_OPTIONS = listOf(0.5f, 1f, 1.5f, 2f)

@OptIn(FlowPreview::class)
@Composable
fun PlaybackScreen(
    container: AppContainer,
    callId: String,
    onBack: () -> Unit,
) {
    val rec by produceState<CallRecord?>(initialValue = null, key1 = callId) {
        value = container.db.calls().byId(callId)
    }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // Survives rotation; seeded once from the loaded record.
    var notes by rememberSaveable(callId) { mutableStateOf<String?>(null) }
    var notesSeeded by rememberSaveable(callId) { mutableStateOf(false) }
    LaunchedEffect(rec?.callId) {
        val r = rec ?: return@LaunchedEffect
        if (!notesSeeded) {
            notes = r.notes
            notesSeeded = true
        }
    }
    // Debounced auto-save: persist 1.5 s after the user stops typing.
    LaunchedEffect(callId, notesSeeded) {
        if (!notesSeeded) return@LaunchedEffect
        snapshotFlow { notes }
            .drop(1)
            .distinctUntilChanged()
            .debounce(1500)
            .collect { value ->
                runCatching { container.db.calls().setNotes(callId, value?.takeIf { it.isNotEmpty() }) }
                    .onFailure { L.w("Playback", "notes save failed", it) }
            }
    }

    var favorite by rememberSaveable(callId) { mutableStateOf(false) }
    var favSeeded by rememberSaveable(callId) { mutableStateOf(false) }
    LaunchedEffect(rec?.callId) {
        val r = rec ?: return@LaunchedEffect
        if (!favSeeded) {
            favorite = r.favorite
            favSeeded = true
        }
    }

    // Resolve the actual playable paths. Old DB rows from before the
    // persistOutcomeFinal fix may still point at a .m4a that was deleted
    // by downgradeIfHalfSilent (one-sided silence). For those, fall back
    // to whichever file actually exists so the user can still play the
    // surviving side instead of seeing ENOENT in the player.
    val (primaryPath, secondaryPath) = remember(rec?.uplinkPath, rec?.downlinkPath) {
        val up = rec?.uplinkPath?.takeIf { RecordingPaths.exists(ctx, it) }
        val dn = rec?.downlinkPath?.takeIf { RecordingPaths.exists(ctx, it) }
        when {
            up != null && dn != null -> up to dn
            up != null -> up to null
            dn != null -> dn to null     // swap: surviving file becomes primary
            else -> null to null
        }
    }
    val isDual = secondaryPath != null

    // Build static waveform bins on a worker dispatcher — peak amplitude per
    // window, normalised to [0,1]. Decoding a 22-min AAC takes ~1-2 s, so
    // until bins are ready the WaveformView shows a baseline.
    val primaryBins by produceState<FloatArray?>(initialValue = null, key1 = primaryPath) {
        val path = primaryPath ?: return@produceState
        value = withContext(Dispatchers.Default) {
            RecordingPaths.materializeToCache(ctx, path)
                ?.let { Waveform.buildBins(it) }
                ?.let { Waveform.normalize(it) }
        }
    }
    val secondaryBins by produceState<FloatArray?>(initialValue = null, key1 = secondaryPath) {
        val path = secondaryPath ?: return@produceState
        value = withContext(Dispatchers.Default) {
            RecordingPaths.materializeToCache(ctx, path)
                ?.let { Waveform.buildBins(it) }
                ?.let { Waveform.normalize(it) }
        }
    }

    val playerA = remember { MediaPlayer() }
    val playerB = remember { MediaPlayer() }

    var preparedA by remember { mutableStateOf(false) }
    var preparedB by remember { mutableStateOf(false) }
    var durationA by remember { mutableIntStateOf(0) }
    var durationB by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { playerA.release() }
            runCatching { playerB.release() }
        }
    }

    LaunchedEffect(rec?.callId, primaryPath, secondaryPath) {
        rec ?: return@LaunchedEffect
        preparedA = false; preparedB = false
        val pri = primaryPath ?: return@LaunchedEffect
        runCatching {
            playerA.reset()
            playerA.setDataSource(ctx, RecordingPaths.playableUri(pri))
            playerA.prepare()
            durationA = playerA.duration.coerceAtLeast(0)
            preparedA = true
        }.onFailure { L.w("Playback", "primary prepare failed: ${it.message}") }
        val sec = secondaryPath
        if (sec != null) {
            runCatching {
                playerB.reset()
                playerB.setDataSource(ctx, RecordingPaths.playableUri(sec))
                playerB.prepare()
                durationB = playerB.duration.coerceAtLeast(0)
                preparedB = true
            }.onFailure { L.w("Playback", "secondary prepare failed: ${it.message}") }
        }
    }

    var playing by remember { mutableStateOf(false) }
    var speedIdx by remember { mutableIntStateOf(1) }
    var positionMs by remember { mutableIntStateOf(0) }
    var volA by rememberSaveable(callId) { mutableFloatStateOf(1f) }
    var volB by rememberSaveable(callId) { mutableFloatStateOf(1f) }
    var seeking by remember { mutableStateOf(false) }
    var pendingSeek by remember { mutableFloatStateOf(0f) }

    val totalMs by remember(durationA, durationB) {
        derivedStateOf { max(durationA, durationB) }
    }

    // ─── MediaSession + MediaStyle notification ──────────────────────────
    // Build a controller mirror of the in-screen player state. The session
    // holder keeps a single MediaSession at app level so lockscreen and
    // shade controls stay consistent across screen transitions.
    val playbackController = remember { dev.lyo.callrec.playback.ScreenPlaybackController() }
    // Lambdas read from current scope; updated below on every state change.
    fun togglePlay(target: Boolean) {
        if (!preparedA) return
        if (target) {
            if (positionMs >= totalMs && totalMs > 0) {
                runCatching { playerA.seekTo(0) }
                if (preparedB) runCatching { playerB.seekTo(0) }
                positionMs = 0
            }
            runCatching { playerA.start() }
            if (preparedB) runCatching { playerB.start() }
        } else {
            runCatching { playerA.pause() }
            if (preparedB) runCatching { playerB.pause() }
        }
        playing = target
    }
    fun seekToMs(target: Int) {
        runCatching { if (preparedA) playerA.seekTo(target.coerceAtMost(durationA)) }
        runCatching { if (preparedB) playerB.seekTo(target.coerceAtMost(durationB)) }
        positionMs = target
    }
    // Mirror the latest state into the controller every recomposition so
    // transport callbacks always see fresh closures over the in-screen
    // state. Cheap (just field assigns) — no notification post here.
    playbackController.title = rec?.contactName ?: rec?.contactNumber ?: callId
    playbackController.subtitle = rec?.contactNumber.orEmpty()
    playbackController.durationMs = totalMs.toLong()
    playbackController.positionMs = positionMs.toLong()
    playbackController.isPlaying = playing
    playbackController.speed = SPEED_OPTIONS[speedIdx]
    playbackController.onPlay = { togglePlay(true) }
    playbackController.onPause = { togglePlay(false) }
    playbackController.onSeek = { seekToMs(it.toInt().coerceIn(0, totalMs)) }
    playbackController.onSkipBack = { seekToMs((positionMs - 10_000).coerceAtLeast(0)) }
    playbackController.onSkipForward = { seekToMs((positionMs + 10_000).coerceAtMost(totalMs)) }

    // Push to the MediaSession only on state-defining changes (play/pause,
    // seek-to-a-new-bucket, speed, metadata). The lockscreen progress bar
    // interpolates between pushes via PlaybackStateCompat's
    // elapsedRealtime baseline — no per-tick spam needed.
    LaunchedEffect(
        rec?.callId, primaryPath, secondaryPath, totalMs, playing, speedIdx,
        // Round position to ~1 s buckets so seeks update the session but
        // tick-by-tick polling does not.
        positionMs / 1000,
    ) {
        if (rec != null) container.mediaSession.push()
    }
    DisposableEffect(rec?.callId) {
        if (rec != null) container.mediaSession.attach(playbackController)
        onDispose { container.mediaSession.detach() }
    }

    // Drives both players in lockstep with the chosen rate.
    LaunchedEffect(speedIdx, preparedA, preparedB) {
        val rate = SPEED_OPTIONS[speedIdx]
        runCatching {
            if (preparedA) playerA.playbackParams = PlaybackParams().setSpeed(rate).also {
                // Keep params consistent regardless of play state — MediaPlayer
                // auto-starts on setPlaybackParams when prepared, so re-pause if
                // we weren't playing.
            }
            if (!playing && preparedA) runCatching { playerA.pause() }
            if (preparedB) playerB.playbackParams = PlaybackParams().setSpeed(rate)
            if (!playing && preparedB) runCatching { playerB.pause() }
        }
    }

    // Poll position; MediaPlayer has no callback for current ms.
    LaunchedEffect(playing, preparedA) {
        while (playing && preparedA) {
            if (!seeking) positionMs = runCatching { playerA.currentPosition }.getOrDefault(0)
            // Auto-stop on completion of the longer track.
            if (positionMs >= totalMs && totalMs > 0) {
                playing = false
                runCatching { playerA.pause() }
                runCatching { if (preparedB) playerB.pause() }
                positionMs = totalMs
                break
            }
            delay(100)
        }
    }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showShareSheet by remember { mutableStateOf(false) }
    var sharingMix by remember { mutableStateOf(false) }

    if (showShareSheet) {
        ShareChoiceDialog(
            preparing = sharingMix,
            onStereo = {
                val r = rec ?: return@ShareChoiceDialog
                sharingMix = true
                scope.launch {
                    val ok = withContext(Dispatchers.Default) {
                        Sharing.shareStereoMix(ctx, r)
                    }
                    sharingMix = false
                    showShareSheet = false
                    if (!ok) L.w("Playback", "stereo mix failed for ${r.callId}")
                }
            },
            onSeparate = {
                val r = rec ?: return@ShareChoiceDialog
                showShareSheet = false
                Sharing.shareSeparate(ctx, r)
            },
            onDismiss = { if (!sharingMix) showShareSheet = false },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.playback_delete_title)) },
            text = { Text(stringResource(R.string.playback_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    val r = rec ?: return@TextButton
                    runCatching { playerA.stop() }
                    runCatching { playerB.stop() }
                    scope.launch {
                        runCatching { RecordingPaths.delete(ctx, r.uplinkPath) }
                        r.downlinkPath?.let { runCatching { RecordingPaths.delete(ctx, it) } }
                        container.db.calls().delete(r.callId)
                        onBack()
                    }
                }) { Text(stringResource(R.string.playback_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.playback_delete_cancel))
                }
            },
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            TopBar(
                title = rec?.contactName ?: rec?.contactNumber ?: callId,
                favorite = favorite,
                onBack = onBack,
                onToggleFavorite = {
                    val next = !favorite
                    favorite = next
                    scope.launch {
                        runCatching { container.db.calls().setFavorite(callId, next) }
                            .onFailure { L.w("Playback", "favorite save failed", it) }
                    }
                },
                onShare = onShareClicked@{
                    val r = rec ?: return@onShareClicked
                    if (isDual) showShareSheet = true
                    else Sharing.shareSingle(ctx, r)
                },
                onDelete = { showDeleteConfirm = true },
            )

            // Master scrubber drives playerA (longer side); playerB is slaved
            // visually only — we keep its absolute position synced via seekTo
            // when the user scrubs.
            ScrubberCard(
                primaryBins = primaryBins,
                secondaryBins = secondaryBins,
                positionMs = if (seeking) (pendingSeek * totalMs).toLong() else positionMs.toLong(),
                totalMs = totalMs.toLong(),
                onScrubStart = { seeking = true },
                onScrub = { pendingSeek = it },
                onScrubEnd = {
                    seeking = false
                    val target = (pendingSeek * totalMs).roundToLong().toInt()
                    runCatching { if (preparedA) playerA.seekTo(target.coerceAtMost(durationA)) }
                    runCatching { if (preparedB) playerB.seekTo(target.coerceAtMost(durationB)) }
                    positionMs = target
                },
            )

            // Per-side volume mixer for dual recordings — adjust your own
            // voice vs the caller independently. Single-track recordings
            // need only a master volume which the system handles.
            if (isDual) {
                VolumeMixer(
                    volA = volA,
                    volB = volB,
                    onVolA = {
                        volA = it
                        runCatching { playerA.setVolume(it, it) }
                    },
                    onVolB = {
                        volB = it
                        runCatching { playerB.setVolume(it, it) }
                    },
                )
            }

            TransportRow(
                playing = playing,
                onSkipBack = {
                    val target = (positionMs - 10_000).coerceAtLeast(0)
                    runCatching { if (preparedA) playerA.seekTo(target) }
                    runCatching { if (preparedB) playerB.seekTo(target.coerceAtMost(durationB)) }
                    positionMs = target
                },
                onPlayPause = {
                    if (!preparedA) return@TransportRow
                    if (playing) {
                        runCatching { playerA.pause() }
                        if (preparedB) runCatching { playerB.pause() }
                    } else {
                        // If we're at the end, rewind first.
                        if (positionMs >= totalMs && totalMs > 0) {
                            runCatching { playerA.seekTo(0) }
                            if (preparedB) runCatching { playerB.seekTo(0) }
                            positionMs = 0
                        }
                        runCatching { playerA.start() }
                        if (preparedB) runCatching { playerB.start() }
                    }
                    playing = !playing
                },
                onSkipForward = {
                    val target = (positionMs + 10_000).coerceAtMost(totalMs)
                    runCatching { if (preparedA) playerA.seekTo(target.coerceAtMost(durationA)) }
                    runCatching { if (preparedB) playerB.seekTo(target.coerceAtMost(durationB)) }
                    positionMs = target
                },
            )

            SpeedSelector(
                selected = speedIdx,
                onSelect = { speedIdx = it },
            )

            Spacer(Modifier.height(0.dp))

            NotesField(
                value = notes.orEmpty(),
                onChange = { notes = it },
                onCommit = {
                    scope.launch {
                        runCatching {
                            container.db.calls().setNotes(callId, notes?.takeIf { it.isNotEmpty() })
                        }.onFailure { L.w("Playback", "notes commit failed", it) }
                    }
                },
            )

            // ─── Transcription block (cloud STT via OpenAI-compatible API)
            rec?.let { r ->
                // For dual recordings we send a mono RMS-normalised mix.
                // Gemini auto-downmixes any stereo input (per their docs), so
                // pan is wasted — the real fix is balancing per-side levels
                // before the sum so the louder mic-uplink doesn't drown out
                // the post-codec downlink and let the model "hear" only one
                // speaker. Mixing decodes both AAC tracks via MediaCodec —
                // seconds of work for a 22-min call. Run on Dispatchers.Default
                // and let the UI render before the mix is ready; until then we
                // fall back to whichever side survives, so the section is
                // usable immediately even on records where one side was
                // dropped by the silence-downgrade.
                val pri = primaryPath
                val sttFile by produceState<java.io.File?>(
                    initialValue = pri?.takeUnless { RecordingPaths.isSaf(it) }?.let { java.io.File(it) },
                    key1 = r.callId,
                    key2 = primaryPath,
                    key3 = secondaryPath,
                ) {
                    val priFile = pri?.let { RecordingPaths.materializeToCache(ctx, it) } ?: return@produceState
                    val sec = secondaryPath ?: return@produceState
                    val secFile = RecordingPaths.materializeToCache(ctx, sec) ?: return@produceState
                    value = withContext(Dispatchers.Default) {
                        val mixed = java.io.File(ctx.cacheDir, "stt/${r.callId}-stt.wav")
                        if (mixed.exists() &&
                            mixed.lastModified() >= maxOf(priFile.lastModified(), secFile.lastModified())
                        ) mixed
                        else {
                            mixed.parentFile?.mkdirs()
                            dev.lyo.callrec.codec.AudioMixer
                                .mixNormalizedMonoForStt(priFile, secFile, mixed) ?: priFile
                        }
                    }
                }
                val sttPath = sttFile?.absolutePath ?: pri ?: return@let
                TranscriptionSection(
                    container = container,
                    callId = callId,
                    audioPath = sttPath,
                    persistedTranscript = r.transcript,
                    onSeek = { ms ->
                        val target = ms.coerceIn(0, totalMs)
                        runCatching { if (preparedA) playerA.seekTo(target.coerceAtMost(durationA)) }
                        runCatching { if (preparedB) playerB.seekTo(target.coerceAtMost(durationB)) }
                        positionMs = target
                        // Tap-to-seek + auto-play feels more natural for a
                        // transcript than tap-and-still-paused. Pausing later
                        // is one tap away on the play/pause button.
                        if (!playing && preparedA) {
                            runCatching { playerA.start() }
                            if (preparedB) runCatching { playerB.start() }
                            playing = true
                        }
                    },
                )
            }

            rec?.let { MetaCard(it, isDual) }
        }
    }
}

@Composable
private fun NotesField(
    value: String,
    onChange: (String) -> Unit,
    onCommit: () -> Unit,
) {
    var hadFocus by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { state ->
                if (state.isFocused) {
                    hadFocus = true
                } else if (hadFocus) {
                    hadFocus = false
                    onCommit()
                }
            },
        placeholder = { Text(stringResource(R.string.playback_notes_placeholder)) },
        maxLines = 4,
        shape = MaterialTheme.shapes.large,
    )
}

@Composable
private fun ShareChoiceDialog(
    preparing: Boolean,
    onStereo: () -> Unit,
    onSeparate: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.playback_share_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.playback_share_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (preparing) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.playback_share_preparing),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    LinearWavyProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onStereo, enabled = !preparing) {
                Text(stringResource(R.string.playback_share_stereo))
            }
        },
        dismissButton = {
            TextButton(onClick = onSeparate, enabled = !preparing) {
                Text(stringResource(R.string.playback_share_separate))
            }
        },
    )
}

@Composable
private fun TopBar(
    title: String,
    favorite: Boolean,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.playback_back),
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        )
        IconButton(onClick = onToggleFavorite) {
            Icon(
                imageVector = if (favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                contentDescription = stringResource(
                    if (favorite) R.string.playback_favorite_on else R.string.playback_favorite_off,
                ),
                tint = if (favorite) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onShare) {
            Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.playback_share))
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = stringResource(R.string.playback_delete),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ScrubberCard(
    primaryBins: FloatArray?,
    secondaryBins: FloatArray?,
    positionMs: Long,
    totalMs: Long,
    onScrubStart: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            val progress = if (totalMs > 0) (positionMs.toFloat() / totalMs).coerceIn(0f, 1f) else 0f
            WaveformView(
                primaryBins = primaryBins,
                secondaryBins = secondaryBins,
                progress = progress,
                onScrubStart = onScrubStart,
                onScrub = onScrub,
                onScrubEnd = onScrubEnd,
            )
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TimeLabel(positionMs)
                Spacer(Modifier.weight(1f))
                TimeLabel(totalMs)
            }
        }
    }
}

@Composable
private fun TimeLabel(ms: Long) {
    Text(
        formatTime(ms),
        style = MaterialTheme.typography.labelLarge,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun VolumeMixer(
    volA: Float,
    volB: Float,
    onVolA: (Float) -> Unit,
    onVolB: (Float) -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            VolumeRow(label = stringResource(R.string.recording_uplink), value = volA, onChange = onVolA)
            Spacer(Modifier.height(8.dp))
            VolumeRow(label = stringResource(R.string.recording_downlink), value = volB, onChange = onVolB)
        }
    }
}

@Composable
private fun VolumeRow(label: String, value: Float, onChange: (Float) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${(value * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = 0f..1f,
        )
    }
}

@Composable
private fun TransportRow(
    playing: Boolean,
    onSkipBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipForward: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onSkipBack, modifier = Modifier.size(56.dp)) {
            Icon(
                Icons.Outlined.Replay10,
                contentDescription = stringResource(R.string.playback_skip_back),
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.size(24.dp))
        // Material 3 Expressive shape morphing on the play/pause hero
        // button. Paused → fully-rounded "circle" affordance (the canonical
        // "play" cue). Playing → softer rounded square that signals
        // "interruptible". The radius animates via the spring scheme
        // inherited from MaterialExpressiveTheme.
        val targetCorner by animateDpAsState(
            targetValue = if (playing) 22.dp else 36.dp,
            label = "play-pause-corner",
        )
        FilledIconButton(
            onClick = onPlayPause,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            shape = RoundedCornerShape(targetCorner),
            modifier = Modifier.size(72.dp),
        ) {
            AnimatedContent(
                targetState = playing,
                transitionSpec = {
                    (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut())
                },
                label = "play-pause",
            ) { isPlaying ->
                Icon(
                    if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = stringResource(
                        if (isPlaying) R.string.playback_pause else R.string.playback_play,
                    ),
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        Spacer(Modifier.size(24.dp))
        IconButton(onClick = onSkipForward, modifier = Modifier.size(56.dp)) {
            Icon(
                Icons.Outlined.Forward10,
                contentDescription = stringResource(R.string.playback_skip_forward),
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

@Composable
private fun SpeedSelector(selected: Int, onSelect: (Int) -> Unit) {
    Column {
        Text(
            stringResource(R.string.playback_speed_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        // Expressive ButtonGroup — replaces the legacy SegmentedButtonRow.
        // ToggleButton's three-shape system (default → pressed → checked)
        // animates the corner radius on selection, which reads as an honest
        // "this is now active" instead of just a colour swap.
        ButtonGroup(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SPEED_OPTIONS.forEachIndexed { index, rate ->
                ToggleButton(
                    checked = index == selected,
                    onCheckedChange = { onSelect(index) },
                    shapes = ToggleButtonDefaults.shapes(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(formatSpeed(rate))
                }
            }
        }
    }
}

@Composable
private fun MetaCard(rec: CallRecord, isDual: Boolean) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            val tracks = if (isDual) {
                listOf(rec.uplinkPath, rec.downlinkPath!!)
            } else {
                listOf(rec.uplinkPath)
            }
            tracks.forEachIndexed { idx, path ->
                if (idx > 0) Spacer(Modifier.height(8.dp))
                MetaRow(path)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (isDual) stringResource(R.string.playback_meta_dual)
                else stringResource(R.string.playback_meta_single),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MetaRow(path: String) {
    val ctx = LocalContext.current
    val name = remember(path) { RecordingPaths.displayName(ctx, path) ?: path.substringAfterLast('/') }
    val ext = remember(name) { name.substringAfterLast('.', "").lowercase(Locale.US) }
    val codec = when (ext) {
        "wav" -> "WAV PCM"
        "m4a", "mp4", "aac" -> "AAC"
        "ogg", "opus" -> "OPUS"
        else -> ext.uppercase(Locale.US).ifEmpty { "?" }
    }
    val bytes = remember(path) { RecordingPaths.length(ctx, path) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            name.substringBeforeLast('.', name),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Box(modifier = Modifier.size(8.dp))
        Text(
            "$codec  •  ${formatBytes(bytes)}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    return "%02d:%02d".format(m, s)
}

private fun formatBytes(b: Long): String {
    if (b < 1024) return "${b} B"
    val kb = b / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    return "%.1f MB".format(mb)
}

private fun formatSpeed(rate: Float): String =
    if (rate == rate.toInt().toFloat()) "${rate.toInt()}x" else "${rate}x"

// ─── Transcription UI (added by transcription agent) ──────────────────────────

@Composable
private fun TranscriptionSection(
    container: AppContainer,
    callId: String,
    audioPath: String,
    persistedTranscript: String?,
    onSeek: (ms: Int) -> Unit,
) {
    val ctx = LocalContext.current
    val job = remember(callId) {
        dev.lyo.callrec.transcription.TranscribeJob(container)
    }
    val state by job.state.collectAsState()
    val rawJson: String? = when (val s = state) {
        is dev.lyo.callrec.transcription.TranscribeState.Done -> s.text
        else -> persistedTranscript
    }
    val parsed = remember(rawJson) { dev.lyo.callrec.transcription.TranscriptCodec.parse(rawJson) }

    // No surrounding Card — the transcript chat lays out flush with the
    // surrounding playback content so each segment bubble carries its own
    // surface, the way Telegram's main feed does. Wrapping it in another
    // tonal Card was eating ~32 dp of horizontal/vertical padding for no
    // gain — the bubbles already provide the necessary visual grouping.
    Column(modifier = Modifier.fillMaxWidth()) {
        FilledTonalButton(
            onClick = { job.start(callId, audioPath) },
            enabled = state !is dev.lyo.callrec.transcription.TranscribeState.Running,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.Article,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.playback_transcribe))
        }

        when (val s = state) {
            is dev.lyo.callrec.transcription.TranscribeState.Running -> {
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.playback_transcribe_running),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearWavyProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
            is dev.lyo.callrec.transcription.TranscribeState.Error -> {
                Spacer(Modifier.height(10.dp))
                Text(
                    s.message,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            else -> Unit
        }

        when {
            parsed != null && parsed.segments.isNotEmpty() -> {
                if (!parsed.title.isNullOrBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        parsed.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.height(12.dp))
                TranscriptView(parsed, onSeek)
            }
            !rawJson.isNullOrBlank() -> {
                Spacer(Modifier.height(12.dp))
                Text(
                    rawJson,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * Renders the transcript as a Telegram-style chat: the user-side speaker
 * (id == "ME" or first speaker in calls) is right-aligned without an avatar
 * — like own messages in a messenger — while every other speaker gets a
 * coloured initial-avatar on the left, shown only on the first bubble of a
 * consecutive group. Bubble shape uses asymmetric rounded corners so the
 * "tail-side" corner is sharper, matching the canonical chat look while
 * staying inside Material 3's RoundedCornerShape vocabulary.
 */
@Composable
private fun TranscriptView(
    transcript: dev.lyo.callrec.transcription.Transcript,
    onSeek: (ms: Int) -> Unit,
) {
    val accents = speakerAccents(transcript.speakers)
    val labels = remember(transcript.speakers) {
        transcript.speakers.associate { it.id to it.label }
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        var prevSpeakerId: String? = null
        for ((idx, seg) in transcript.segments.withIndex()) {
            val isFirstOfGroup = seg.speakerId != prevSpeakerId
            ChatSegment(
                seg = seg,
                accent = accents[seg.speakerId] ?: SpeakerAccent.fallback(),
                speakerLabel = labels[seg.speakerId] ?: legacyDisplayLabel(seg.speakerId),
                isFirstOfGroup = isFirstOfGroup,
                onSeek = onSeek,
            )
            prevSpeakerId = seg.speakerId
            if (idx < transcript.segments.lastIndex &&
                transcript.segments[idx + 1].speakerId != seg.speakerId
            ) {
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/**
 * Per-speaker accent — drives the avatar circle and the header tint above
 * the first bubble of a group. The bubble itself does NOT use these; it
 * stays neutral so the chat reads as a standard Telegram conversation
 * (colour lives on the avatar, the cloud is grey).
 */
private data class SpeakerAccent(
    val container: Color,
    val onContainer: Color,
    /** True for the user-side speaker — bubbles align right and skip the avatar. */
    val isSelf: Boolean,
) {
    companion object {
        @Composable
        fun fallback() = SpeakerAccent(
            container = MaterialTheme.colorScheme.surfaceContainerHighest,
            onContainer = MaterialTheme.colorScheme.onSurface,
            isSelf = false,
        )
    }
}

/**
 * Order in `speakers` drives palette assignment. The first speaker — by
 * convention "Я"/ME for phone calls or the dominant voice for voice memos —
 * gets `primaryContainer` from the active scheme and right alignment,
 * mirroring "own messages" in a chat client. Subsequent speakers each draw
 * from a curated 8-hue palette so even meetings with 4–5 distinct voices
 * stay visually unambiguous.
 *
 * Palette is held in the chat domain rather than `colorScheme` because M3
 * exposes only ~3 container roles — not enough for "categorical" colours.
 * Each entry has hand-tuned light/dark variants picked at HCT tones 90/30
 * so they read as soft pastels in light theme and deep saturated tones in
 * dark theme, with contrast-safe content colours on either side.
 */
@Composable
private fun speakerAccents(
    speakers: List<dev.lyo.callrec.transcription.Transcript.SpeakerInfo>,
): Map<String, SpeakerAccent> {
    val cs = MaterialTheme.colorScheme
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val slots = remember(dark) { chatAccents(dark) }
    // Self avatar uses the dynamic primary tone so it tracks Material You.
    val selfAccent = SpeakerAccent(cs.primary, cs.onPrimary, isSelf = true)
    val map = LinkedHashMap<String, SpeakerAccent>()
    speakers.forEachIndexed { idx, s ->
        val isSelf = s.id == dev.lyo.callrec.transcription.Transcript.LEGACY_ME ||
            (idx == 0 && speakers.size > 1)
        map[s.id] = if (isSelf) selfAccent else slots[idx % slots.size]
    }
    return map
}

/**
 * 8 categorically distinct hues for non-self speakers. Indexes are stable
 * across recompositions because [speakerAccents] keys by speaker order, so
 * "Speaker B" keeps the same colour for the lifetime of one transcript.
 *
 * Avatar uses these as its background tile + initial colour. Light theme
 * tones are saturated mid-tones (avatars are small, need punch); dark theme
 * tones are deeper but stay legible against `surfaceContainerHigh` bubbles.
 */
private fun chatAccents(dark: Boolean): List<SpeakerAccent> = if (dark) {
    listOf(
        SpeakerAccent(Color(0xFF4A78A8), Color(0xFFFFFFFF), false), // azure
        SpeakerAccent(Color(0xFFB07A2E), Color(0xFFFFFFFF), false), // amber
        SpeakerAccent(Color(0xFF3F8C72), Color(0xFFFFFFFF), false), // teal
        SpeakerAccent(Color(0xFFB04D7A), Color(0xFFFFFFFF), false), // rose
        SpeakerAccent(Color(0xFF6F68B0), Color(0xFFFFFFFF), false), // indigo
        SpeakerAccent(Color(0xFF8C7E3A), Color(0xFFFFFFFF), false), // olive
        SpeakerAccent(Color(0xFF9E5A4A), Color(0xFFFFFFFF), false), // terracotta
        SpeakerAccent(Color(0xFF408384), Color(0xFFFFFFFF), false), // cyan
    )
} else {
    listOf(
        SpeakerAccent(Color(0xFF3F78AC), Color(0xFFFFFFFF), false),
        SpeakerAccent(Color(0xFFB57E2E), Color(0xFFFFFFFF), false),
        SpeakerAccent(Color(0xFF2D8A6E), Color(0xFFFFFFFF), false),
        SpeakerAccent(Color(0xFFB44A75), Color(0xFFFFFFFF), false),
        SpeakerAccent(Color(0xFF6862B5), Color(0xFFFFFFFF), false),
        SpeakerAccent(Color(0xFF8A7A30), Color(0xFFFFFFFF), false),
        SpeakerAccent(Color(0xFFA15240), Color(0xFFFFFFFF), false),
        SpeakerAccent(Color(0xFF358082), Color(0xFFFFFFFF), false),
    )
}

private fun legacyDisplayLabel(id: String): String = when (id) {
    dev.lyo.callrec.transcription.Transcript.LEGACY_ME -> "Я"
    dev.lyo.callrec.transcription.Transcript.LEGACY_THEM -> "Співрозмовник"
    dev.lyo.callrec.transcription.Transcript.LEGACY_UNKNOWN -> "—"
    else -> id
}

@Composable
private fun ChatSegment(
    seg: dev.lyo.callrec.transcription.Transcript.Segment,
    accent: SpeakerAccent,
    speakerLabel: String,
    isFirstOfGroup: Boolean,
    onSeek: (ms: Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    // Bubble colours stay neutral — chat clouds are not the identity carrier.
    // Self bubble gets a soft primary tint (Telegram-blue equivalent), all
    // other speakers share the same surfaceContainerHigh so the eye keys on
    // the avatar/header for "who's talking", not the cloud.
    val bubbleBg = if (accent.isSelf) cs.primaryContainer else cs.surfaceContainerHigh
    val bubbleFg = if (accent.isSelf) cs.onPrimaryContainer else cs.onSurface

    val r = 18.dp
    val tight = 6.dp
    val shape = if (accent.isSelf) {
        RoundedCornerShape(
            topStart = r,
            topEnd = r,
            bottomEnd = if (isFirstOfGroup) tight else r,
            bottomStart = r,
        )
    } else {
        RoundedCornerShape(
            topStart = r,
            topEnd = r,
            bottomEnd = r,
            bottomStart = if (isFirstOfGroup) tight else r,
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (accent.isSelf) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!accent.isSelf) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .padding(top = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (isFirstOfGroup) {
                    SpeakerAvatar(label = speakerLabel, accent = accent)
                }
            }
            Spacer(Modifier.size(8.dp))
        }
        Column(
            horizontalAlignment = if (accent.isSelf) Alignment.End else Alignment.Start,
            modifier = Modifier.weight(1f, fill = false),
        ) {
            if (isFirstOfGroup && !accent.isSelf) {
                // Header label uses the avatar accent — that's the only place
                // (beyond the avatar itself) where the speaker's identity
                // colour is visible. Keeps "who said this" cue alive when the
                // avatar is hidden (later bubbles in a group).
                Text(
                    speakerLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = accent.container,
                    modifier = Modifier.padding(start = 12.dp, bottom = 2.dp),
                )
            }
            Surface(
                onClick = { onSeek((seg.startSec * 1000).toInt()) },
                color = bubbleBg,
                contentColor = bubbleFg,
                shape = shape,
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (seg.nonSpeech.isNotEmpty()) {
                        Text(
                            seg.nonSpeech.joinToString(", ") { "[${nonSpeechLabel(it)}]" },
                            style = MaterialTheme.typography.labelSmall,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            color = bubbleFg.copy(alpha = 0.6f),
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                    Text(
                        seg.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = bubbleFg,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(top = 2.dp),
                    ) {
                        seg.tone?.let { tone ->
                            Text(
                                toneLabel(tone),
                                style = MaterialTheme.typography.labelSmall,
                                color = bubbleFg.copy(alpha = 0.55f),
                            )
                            Spacer(Modifier.size(6.dp))
                        }
                        Text(
                            formatTime((seg.startSec * 1000).toLong()),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = bubbleFg.copy(alpha = 0.65f),
                        )
                    }
                }
            }
        }
        if (accent.isSelf) {
            Spacer(Modifier.size(4.dp))
        }
    }
}

@Composable
private fun SpeakerAvatar(label: String, accent: SpeakerAccent) {
    val initial = label.trim().firstOrNull { it.isLetter() }?.uppercase() ?: "?"
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(accent.container),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = accent.onContainer,
        )
    }
}

private fun toneLabel(t: String): String = when (t.lowercase()) {
    "friendly" -> "тепло"
    "tense" -> "напружено"
    "excited" -> "захоплено"
    "sad" -> "сумно"
    "angry" -> "зло"
    "questioning" -> "питально"
    "neutral" -> "нейтрально"
    else -> t
}

private fun nonSpeechLabel(s: String): String = when (s.lowercase()) {
    "laugh" -> "сміх"
    "sigh" -> "зітхання"
    "pause" -> "пауза"
    "cough" -> "кашель"
    "background_music" -> "музика"
    "background_voice" -> "голос на фоні"
    else -> s
}
