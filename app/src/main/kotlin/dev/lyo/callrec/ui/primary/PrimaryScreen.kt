// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.ui.primary

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import dev.lyo.callrec.R
import dev.lyo.callrec.contacts.ContactResolver
import dev.lyo.callrec.di.AppContainer
import dev.lyo.callrec.recorder.RecorderController
import dev.lyo.callrec.recorder.ShizukuState
import dev.lyo.callrec.recorder.Strategy
import dev.lyo.callrec.storage.BulkOps
import dev.lyo.callrec.storage.CallRecord
import dev.lyo.callrec.telephony.CallMonitorService
import dev.lyo.callrec.ui.components.LiveLevelMeter
import dev.lyo.callrec.ui.components.QualityPill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.absoluteValue

/**
 * The single primary screen. One canonical place where the user lands:
 *  - large "cally" title with a discreet mic (manual record) and settings;
 *  - status banner only when something interesting is happening
 *    (recording / probing / failed / Shizuku missing) — silent at idle;
 *  - filled search field + horizontally-scrollable filter chips;
 *  - date-grouped list of recordings with hash-coloured contact avatars,
 *    duration, favorite indicator, swipe-to-delete with undo;
 *  - the FAB shows up ONLY while a call is being recorded (Stop button) —
 *    no FAB clutter when nothing is happening, manual record lives in the
 *    title bar instead.
 */
@OptIn(FlowPreview::class)
@Composable
fun PrimaryScreen(
    container: AppContainer,
    shizukuState: ShizukuState,
    onOpenSettings: () -> Unit,
    onOpenPlayback: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val recState by container.recorder.state.collectAsState()
    val levels by container.recorder.levels.collectAsState()

    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(PrimaryFilter.All) }

    val searchFlow = remember {
        snapshotFlow { query }
            .debounce(250)
            .distinctUntilChanged()
            .flatMapLatest { container.db.calls().observeSearch(it.trim()) }
    }
    val items by searchFlow.collectAsState(initial = emptyList())

    val pendingDeletion = remember { mutableStateOf<CallRecord?>(null) }
    val filteredItems by remember {
        derivedStateOf {
            val skip = pendingDeletion.value?.callId
            val base = if (skip == null) items else items.filterNot { it.callId == skip }
            applyFilter(base, filter)
        }
    }

    val selected = remember { mutableStateMapOf<String, Unit>() }
    val inSelectionMode by remember { derivedStateOf { selected.isNotEmpty() } }
    var manualSelectMode by remember { mutableStateOf(false) }
    val selectionUiActive = inSelectionMode || manualSelectMode

    var showDeleteDialog by remember { mutableStateOf(false) }
    val snackbarHost = remember { SnackbarHostState() }
    val avatarCache = remember { mutableStateMapOf<String, String?>() }

    LaunchedEffect(pendingDeletion.value) {
        val rec = pendingDeletion.value ?: return@LaunchedEffect
        val result = snackbarHost.showSnackbar(
            message = ctx.getString(R.string.library_deleted),
            actionLabel = ctx.getString(R.string.playback_delete_cancel),
            withDismissAction = false,
        )
        when (result) {
            SnackbarResult.ActionPerformed -> pendingDeletion.value = null
            SnackbarResult.Dismissed -> {
                withContext(Dispatchers.IO) {
                    BulkOps.deleteFiles(listOf(rec))
                    container.db.calls().delete(rec.callId)
                }
                pendingDeletion.value = null
            }
        }
    }

    val onStartRecord: () -> Unit = {
        val intent = Intent(ctx, CallMonitorService::class.java)
            .setAction(CallMonitorService.ACTION_MANUAL_START)
        ContextCompat.startForegroundService(ctx, intent)
    }
    val onStopRecord: () -> Unit = {
        val intent = Intent(ctx, CallMonitorService::class.java)
            .setAction(CallMonitorService.ACTION_MANUAL_STOP)
        ContextCompat.startForegroundService(ctx, intent)
    }
    val isActiveOrProbing = recState is RecorderController.RecordingState.Active ||
        recState is RecorderController.RecordingState.Probing

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHost) },
            floatingActionButton = {
                // Expressive HorizontalFloatingToolbar — the M3 successor to
                // a one-action ExtendedFAB when more than the canonical
                // "primary action" needs to live in the floating layer.
                // Wrapped in AnimatedVisibility because the toolbar's own
                // `expanded` parameter only collapses content; the chrome
                // itself stays on screen, which is wrong here — at idle
                // there is no recording action to offer at all.
                AnimatedVisibility(
                    visible = isActiveOrProbing && !selectionUiActive,
                    enter = scaleIn() + fadeIn(),
                    exit = scaleOut() + fadeOut(),
                ) {
                    HorizontalFloatingToolbar(expanded = true) {
                        FilledIconButton(
                            onClick = onStopRecord,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                            modifier = Modifier.size(56.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Stop,
                                contentDescription = stringResource(R.string.recording_stop),
                            )
                        }
                        Spacer(Modifier.size(8.dp))
                        Text(
                            stringResource(R.string.recording_stop),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    }
                }
            },
        ) { padding ->
            Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                AnimatedContent(
                    targetState = selectionUiActive,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
                    label = "topbar",
                ) { sel ->
                    if (sel) {
                        SelectionTopBar(
                            count = selected.size,
                            onExit = {
                                selected.clear()
                                manualSelectMode = false
                            },
                            onSelectAll = {
                                filteredItems.forEach { selected[it.callId] = Unit }
                            },
                            onShare = {
                                val records = filteredItems.filter {
                                    selected.containsKey(it.callId)
                                }
                                shareMultiple(ctx, records)
                                selected.clear()
                                manualSelectMode = false
                            },
                            onDelete = { showDeleteDialog = true },
                        )
                    } else {
                        TitleBar(
                            recordingActive = isActiveOrProbing,
                            onManualRecord = onStartRecord,
                            onSelect = { manualSelectMode = true },
                            onSettings = onOpenSettings,
                        )
                    }
                }

                val bannerVisible = recState !is RecorderController.RecordingState.Idle ||
                    shizukuState != ShizukuState.Ready
                AnimatedVisibility(
                    visible = bannerVisible && !selectionUiActive,
                    enter = fadeIn(tween(220)),
                    exit = fadeOut(tween(160)),
                ) {
                    StatusBanner(
                        shizukuState = shizukuState,
                        recState = recState,
                        levels = levels,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                if (!selectionUiActive) {
                    SearchAndFilters(
                        query = query,
                        onQueryChange = { query = it },
                        filter = filter,
                        onFilterChange = { filter = it },
                    )
                }

                when {
                    items.isEmpty() && query.isBlank() -> EmptyDb()
                    filteredItems.isEmpty() && query.isNotBlank() -> EmptySearch(query)
                    filteredItems.isEmpty() -> EmptyFilter()
                    else -> RecordsList(
                        ctx = ctx,
                        items = filteredItems,
                        selected = selected,
                        avatarCache = avatarCache,
                        inSelectionMode = selectionUiActive,
                        onTapRow = { rec ->
                            if (selectionUiActive) {
                                if (selected.containsKey(rec.callId)) selected.remove(rec.callId)
                                else selected[rec.callId] = Unit
                            } else {
                                onOpenPlayback(rec.callId)
                            }
                        },
                        onLongPress = { rec ->
                            if (!selected.containsKey(rec.callId)) selected[rec.callId] = Unit
                        },
                        onSwipeDelete = { rec -> pendingDeletion.value = rec },
                    )
                }
            }
        }
    }

    if (showDeleteDialog) {
        val toDelete = filteredItems.filter { selected.containsKey(it.callId) }
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.playback_delete_title)) },
            text = {
                Text(
                    String.format(
                        Locale.getDefault(),
                        ctx.getString(R.string.library_delete_bulk_msg),
                        toDelete.size,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    val ids = toDelete.map { it.callId }
                    selected.clear()
                    manualSelectMode = false
                    scope.launch(Dispatchers.IO) {
                        BulkOps.deleteFiles(toDelete)
                        container.db.calls().deleteAll(ids)
                    }
                }) { Text(stringResource(R.string.playback_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.playback_delete_cancel))
                }
            },
        )
    }
}

// ── Top bar ──────────────────────────────────────────────────────────────

@Composable
private fun TitleBar(
    recordingActive: Boolean,
    onManualRecord: () -> Unit,
    onSelect: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 8.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        // Discreet manual-record button. Hidden while a call is being
        // recorded — the FAB takes over with a clear Stop affordance.
        AnimatedVisibility(
            visible = !recordingActive,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(120)),
        ) {
            IconButton(onClick = onManualRecord) {
                Icon(
                    Icons.Outlined.MicNone,
                    contentDescription = stringResource(R.string.primary_fab_record),
                )
            }
        }
        IconButton(onClick = onSelect) {
            Icon(
                Icons.Outlined.Checklist,
                contentDescription = stringResource(R.string.primary_select),
            )
        }
        IconButton(onClick = onSettings) {
            Icon(
                Icons.Outlined.Settings,
                contentDescription = stringResource(R.string.home_action_settings),
            )
        }
    }
}

@Composable
private fun SelectionTopBar(
    count: Int,
    onExit: () -> Unit,
    onSelectAll: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onExit) {
            Icon(Icons.Outlined.Close, contentDescription = null)
        }
        Spacer(Modifier.size(4.dp))
        Text(
            text = String.format(
                Locale.getDefault(),
                stringResource(R.string.primary_selection_count),
                count,
            ),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onSelectAll) {
            Icon(Icons.Outlined.DoneAll, contentDescription = null)
        }
        IconButton(onClick = onShare, enabled = count > 0) {
            Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.library_share))
        }
        IconButton(onClick = onDelete, enabled = count > 0) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = stringResource(R.string.library_delete),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

// ── Status banner ────────────────────────────────────────────────────────

@Composable
private fun StatusBanner(
    shizukuState: ShizukuState,
    recState: RecorderController.RecordingState,
    levels: RecorderController.Levels,
    modifier: Modifier = Modifier,
) {
    val strategy = (recState as? RecorderController.RecordingState.Active)?.outcome
        ?.let { o ->
            when (o) {
                is RecorderController.Outcome.Dual -> o.strategy
                is RecorderController.Outcome.Single -> o.strategy
                is RecorderController.Outcome.Failed -> null
            }
        }

    val (containerColor, onColor, primaryLabel) = when {
        recState is RecorderController.RecordingState.Active -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            humanStatus(strategy),
        )
        recState is RecorderController.RecordingState.Probing -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            stringResource(R.string.rec_status_probing),
        )
        recState is RecorderController.RecordingState.Failed -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            stringResource(R.string.rec_status_failed_silence),
        )
        shizukuState != ShizukuState.Ready -> Triple(
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurfaceVariant,
            stringResource(R.string.home_status_no_shizuku),
        )
        else -> Triple(
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurface,
            stringResource(R.string.rec_status_idle),
        )
    }

    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(
                    onClick = {},
                    enabled = false,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = onColor.copy(alpha = 0.15f),
                        contentColor = onColor,
                        disabledContainerColor = onColor.copy(alpha = 0.15f),
                        disabledContentColor = onColor,
                    ),
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Outlined.Mic, contentDescription = null, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.size(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = primaryLabel,
                        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
                        label = "primary",
                    ) { label ->
                        Text(
                            label,
                            style = MaterialTheme.typography.titleMedium,
                            color = onColor,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (strategy != null) {
                        QualityPill(text = qualityLabel(strategy), color = onColor)
                    }
                }
            }

            AnimatedVisibility(
                visible = recState is RecorderController.RecordingState.Active ||
                    recState is RecorderController.RecordingState.Probing,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(150)),
            ) {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    LiveLevelMeter(
                        label = stringResource(R.string.recording_uplink),
                        rms = levels.uplinkRms,
                        color = onColor,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    LiveLevelMeter(
                        label = stringResource(R.string.recording_downlink),
                        rms = levels.downlinkRms,
                        color = onColor,
                        disabled = !levels.hasDownlink,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun humanStatus(strategy: Strategy?): String = when (strategy) {
    Strategy.DualUplinkDownlink, Strategy.DualMicDownlink ->
        stringResource(R.string.rec_status_active_dual)
    Strategy.SingleVoiceCallStereo, Strategy.SingleVoiceCallMono ->
        stringResource(R.string.rec_status_active_single_full)
    Strategy.SingleMic ->
        stringResource(R.string.rec_status_active_mic_only)
    null -> stringResource(R.string.rec_status_idle)
}

@Composable
private fun qualityLabel(strategy: Strategy): String = when (strategy) {
    Strategy.DualUplinkDownlink -> stringResource(R.string.rec_quality_excellent)
    Strategy.DualMicDownlink, Strategy.SingleVoiceCallStereo, Strategy.SingleVoiceCallMono ->
        stringResource(R.string.rec_quality_good)
    Strategy.SingleMic -> stringResource(R.string.rec_quality_partial)
}

// ── Search + filters ─────────────────────────────────────────────────────

private enum class PrimaryFilter { All, Today, Week, Favorites }

private fun applyFilter(items: List<CallRecord>, filter: PrimaryFilter): List<CallRecord> {
    if (filter == PrimaryFilter.All) return items
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val weekStart = today.minusDays((today.dayOfWeek.value - 1).toLong())
    return items.filter { rec ->
        when (filter) {
            PrimaryFilter.All -> true
            PrimaryFilter.Today -> {
                val d = Instant.ofEpochMilli(rec.startedAt).atZone(zone).toLocalDate()
                d == today
            }
            PrimaryFilter.Week -> {
                val d = Instant.ofEpochMilli(rec.startedAt).atZone(zone).toLocalDate()
                !d.isBefore(weekStart)
            }
            PrimaryFilter.Favorites -> rec.favorite
        }
    }
}

@Composable
private fun SearchAndFilters(
    query: String,
    onQueryChange: (String) -> Unit,
    filter: PrimaryFilter,
    onFilterChange: (PrimaryFilter) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        // Filled search field — softer than OutlinedTextField, matches the
        // tonal-surface aesthetic the rest of the screen leans into.
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.primary_search_placeholder)) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Outlined.Close, contentDescription = null)
                    }
                }
            },
            shape = RoundedCornerShape(28.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
        )
        Spacer(Modifier.height(10.dp))
        // Expressive ButtonGroup with ToggleButton — the chip-rail successor
        // for canonical "filter" pickers. The shape morphs on press and on
        // checked state via ToggleButtonDefaults.shapes(), giving a tactile
        // "I'm now selected" without requiring a visible state colour swap.
        ButtonGroup(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PrimaryFilter.values().forEach { f ->
                ToggleButton(
                    checked = filter == f,
                    onCheckedChange = { onFilterChange(f) },
                    shapes = ToggleButtonDefaults.shapes(),
                ) {
                    if (f == PrimaryFilter.Favorites) {
                        Icon(
                            Icons.Outlined.Star,
                            contentDescription = null,
                            modifier = Modifier.size(ToggleButtonDefaults.IconSize),
                        )
                        Spacer(Modifier.size(ToggleButtonDefaults.IconSpacing))
                    }
                    Text(
                        when (f) {
                            PrimaryFilter.All -> stringResource(R.string.primary_filter_all)
                            PrimaryFilter.Today -> stringResource(R.string.primary_filter_today)
                            PrimaryFilter.Week -> stringResource(R.string.primary_filter_week)
                            PrimaryFilter.Favorites ->
                                stringResource(R.string.primary_filter_favorites)
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ── List ─────────────────────────────────────────────────────────────────

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun RecordsList(
    ctx: Context,
    items: List<CallRecord>,
    selected: Map<String, Unit>,
    avatarCache: androidx.compose.runtime.snapshots.SnapshotStateMap<String, String?>,
    inSelectionMode: Boolean,
    onTapRow: (CallRecord) -> Unit,
    onLongPress: (CallRecord) -> Unit,
    onSwipeDelete: (CallRecord) -> Unit,
) {
    val grouped = remember(items) { groupByBucket(ctx, items) }

    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 4.dp,
            bottom = 96.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        grouped.forEach { (label, bucketItems) ->
            stickyHeader(key = "h_$label") { BucketHeader(label) }
            items(
                count = bucketItems.size,
                key = { idx -> bucketItems[idx].callId },
            ) { idx ->
                val rec = bucketItems[idx]
                RowSwipeable(
                    rec = rec,
                    isSelected = selected.containsKey(rec.callId),
                    inSelectionMode = inSelectionMode,
                    avatarCache = avatarCache,
                    ctx = ctx,
                    onTap = { onTapRow(rec) },
                    onLongPress = { onLongPress(rec) },
                    onSwipeDelete = { onSwipeDelete(rec) },
                )
            }
        }
    }
}

@Composable
private fun BucketHeader(label: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = 12.dp, bottom = 6.dp, start = 4.dp, end = 4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun RowSwipeable(
    rec: CallRecord,
    isSelected: Boolean,
    inSelectionMode: Boolean,
    avatarCache: androidx.compose.runtime.snapshots.SnapshotStateMap<String, String?>,
    ctx: Context,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onSwipeDelete: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onSwipeDelete(); true
            } else false
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = !inSelectionMode,
        backgroundContent = { SwipeBackground() },
        modifier = Modifier.animateContentSize(),
    ) {
        RecordingRow(
            rec = rec,
            isSelected = isSelected,
            avatarCache = avatarCache,
            ctx = ctx,
            onTap = onTap,
            onLongPress = onLongPress,
        )
    }
}

@Composable
private fun SwipeBackground() {
    // Match RecordingRow's Card shape (extraLarge = 28 dp). With the M3
    // canonical scale (large = 16 dp, extraLarge = 28 dp) the swipe
    // background must use the same role as the foreground row, otherwise
    // the smaller-radius backplate peeks out from behind rounded corners.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 2.dp)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Icon(
            Icons.Outlined.Delete,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun RecordingRow(
    rec: CallRecord,
    isSelected: Boolean,
    avatarCache: androidx.compose.runtime.snapshots.SnapshotStateMap<String, String?>,
    ctx: Context,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val containerColor =
        if (isSelected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh

    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ContactAvatar(rec = rec, avatarCache = avatarCache, ctx = ctx, isSelected = isSelected)
            Spacer(Modifier.size(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = rec.contactName ?: rec.contactNumber ?: "—",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (rec.favorite) {
                        Spacer(Modifier.size(6.dp))
                        Icon(
                            Icons.Outlined.Star,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Spacer(Modifier.size(2.dp))
                Text(
                    text = subtitle(rec),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = formatTime(rec.startedAt),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

private fun subtitle(rec: CallRecord): String {
    val durMs = (rec.endedAt ?: rec.startedAt) - rec.startedAt
    val secs = (durMs / 1000).coerceAtLeast(0)
    val dur = if (secs >= 60) "%d:%02d".format(secs / 60, secs % 60) else "${secs}с"
    val parts = mutableListOf(dur)
    rec.contactNumber?.takeIf { it.isNotBlank() && rec.contactName != null }?.let { parts.add(it) }
    return parts.joinToString("  •  ")
}

@Composable
private fun ContactAvatar(
    rec: CallRecord,
    avatarCache: androidx.compose.runtime.snapshots.SnapshotStateMap<String, String?>,
    ctx: Context,
    isSelected: Boolean,
) {
    val number = rec.contactNumber
    val uri by produceState<String?>(initialValue = avatarCache[number ?: ""], number) {
        if (number.isNullOrBlank()) {
            value = null
            return@produceState
        }
        if (avatarCache.containsKey(number)) {
            value = avatarCache[number]
            return@produceState
        }
        val resolved = ContactResolver.resolvePhotoUri(ctx, number)
        avatarCache[number] = resolved
        value = resolved
    }

    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, uri) {
        val u = uri
        if (u == null) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            runCatching {
                ctx.contentResolver.openInputStream(Uri.parse(u))?.use {
                    BitmapFactory.decodeStream(it)
                }
            }.getOrNull()
        }
    }

    val (bg, fg) = avatarColors(rec.contactName ?: rec.contactNumber, isSelected)
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        val bm = bitmap
        if (bm != null) {
            Image(
                bitmap = bm.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
            )
        } else {
            Text(
                text = initials(rec.contactName ?: rec.contactNumber),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = fg,
            )
        }
    }
}

/**
 * Pick a deterministic background colour per contact so a sea of "no photo"
 * avatars doesn't look like a wall of identical pills. Falls back to the
 * primary palette in selection mode for a clear "I'm picked" cue.
 */
@Composable
private fun avatarColors(seed: String?, isSelected: Boolean): Pair<Color, Color> {
    if (isSelected) {
        return MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
    }
    val palette = listOf(
        MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer,
        MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer,
        MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer,
    )
    val key = seed?.takeIf { it.isNotBlank() } ?: "?"
    val idx = (key.hashCode().absoluteValue) % palette.size
    return palette[idx]
}

// ── Empty states ─────────────────────────────────────────────────────────

@Composable
private fun EmptySearch(query: String) {
    EmptyState(
        icon = Icons.Outlined.Search,
        title = String.format(
            Locale.getDefault(),
            stringResource(R.string.primary_empty_search),
            query,
        ),
        hint = null,
    )
}

@Composable
private fun EmptyFilter() {
    EmptyState(
        icon = Icons.Outlined.Star,
        title = stringResource(R.string.primary_empty_filter_title),
        hint = stringResource(R.string.primary_empty_filter_hint),
    )
}

@Composable
private fun EmptyDb() {
    EmptyState(
        icon = Icons.Outlined.Phone,
        title = stringResource(R.string.home_recordings_empty),
        hint = stringResource(R.string.primary_empty_hint),
    )
}

@Composable
private fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    hint: String?,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(44.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            if (hint != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────

private fun shareMultiple(ctx: Context, records: List<CallRecord>) {
    if (records.isEmpty()) return
    val authority = "${ctx.packageName}.fileprovider"
    val uris = records.mapNotNull {
        runCatching { FileProvider.getUriForFile(ctx, authority, File(it.uplinkPath)) }.getOrNull()
    }
    if (uris.isEmpty()) return
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "audio/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { ctx.startActivity(Intent.createChooser(intent, null)) }
}

private fun initials(src: String?): String {
    if (src.isNullOrBlank()) return "?"
    val cleaned = src.trim()
    val first = cleaned.firstOrNull { it.isLetter() } ?: cleaned.first()
    return first.uppercase()
}

private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private val DATE_FMT = DateTimeFormatter.ofPattern("d MMM").withZone(ZoneId.systemDefault())

private fun formatTime(ts: Long): String {
    val instant = Instant.ofEpochMilli(ts)
    val today = LocalDate.now()
    val date = instant.atZone(ZoneId.systemDefault()).toLocalDate()
    return if (date == today) TIME_FMT.format(instant) else DATE_FMT.format(instant)
}

private fun groupByBucket(
    ctx: Context,
    items: List<CallRecord>,
): List<Pair<String, List<CallRecord>>> {
    if (items.isEmpty()) return emptyList()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val yesterday = today.minusDays(1)
    val weekStart = today.minusDays((today.dayOfWeek.value - 1).toLong())
    val monthStart = today.withDayOfMonth(1)

    val monthFmt = DateTimeFormatter.ofPattern("LLLL yyyy", Locale.forLanguageTag("uk"))

    val buckets = LinkedHashMap<String, MutableList<CallRecord>>()
    for (rec in items) {
        val date = Instant.ofEpochMilli(rec.startedAt).atZone(zone).toLocalDate()
        val label = when {
            date == today -> ctx.getString(R.string.primary_bucket_today)
            date == yesterday -> ctx.getString(R.string.primary_bucket_yesterday)
            !date.isBefore(weekStart) -> ctx.getString(R.string.primary_bucket_week)
            !date.isBefore(monthStart) -> ctx.getString(R.string.primary_bucket_month)
            else -> monthFmt.format(date)
                .replaceFirstChar { it.titlecase(Locale.forLanguageTag("uk")) }
        }
        buckets.getOrPut(label) { mutableListOf() }.add(rec)
    }
    return buckets.map { it.key to it.value }
}
