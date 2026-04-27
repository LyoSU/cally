// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lyo.callrec.BuildConfig
import dev.lyo.callrec.R
import dev.lyo.callrec.di.AppContainer
import dev.lyo.callrec.settings.RecordingFormat
import dev.lyo.callrec.ui.components.strategyQuality
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val autoRecord by container.settings.autoRecord.collectAsState(initial = true)
    val ringback by container.settings.recordIncludingRingback.collectAsState(initial = true)
    val sampleRate by container.settings.sampleRate.collectAsState(initial = 16_000)
    val format by container.settings.format.collectAsState(initial = RecordingFormat.AAC)
    val sttBaseUrl by container.settings.sttBaseUrl.collectAsState(
        initial = dev.lyo.callrec.settings.AppSettings.DEFAULT_STT_BASE_URL,
    )
    val sttApiKey by container.settings.sttApiKey.collectAsState(initial = "")
    val sttModel by container.settings.sttModel.collectAsState(
        initial = dev.lyo.callrec.settings.AppSettings.DEFAULT_STT_MODEL,
    )
    val cleanupAgeDays by container.settings.autoCleanupMaxAgeDays.collectAsState(initial = null)
    val cleanupSizeGb by container.settings.autoCleanupMaxSizeGb.collectAsState(initial = null)
    val capabilities by container.capabilities.flow.collectAsState(initial = null)
    val snackbar = remember { SnackbarHostState() }
    val cleanupAppliedMsg = stringResource(R.string.settings_cleanup_applied)

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                }
                Spacer(Modifier.size(4.dp))
                Text(
                    stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }

            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // ── Recording behaviour ──────────────────────────────────────
                SectionHeader(stringResource(R.string.settings_section_recording))
                SettingCard {
                    ToggleRow(
                        title = stringResource(R.string.settings_auto_record),
                        desc = stringResource(R.string.settings_auto_record_desc),
                        checked = autoRecord,
                        onCheckedChange = { scope.launch { container.settings.setAutoRecord(it) } },
                    )
                    Divider()
                    ToggleRow(
                        title = stringResource(R.string.settings_ringback),
                        desc = stringResource(R.string.settings_ringback_desc),
                        checked = ringback,
                        onCheckedChange = { scope.launch { container.settings.setRecordIncludingRingback(it) } },
                    )
                    Divider()
                    SampleRateRow(
                        current = sampleRate,
                        onSelect = {
                            scope.launch {
                                container.settings.setSampleRate(it)
                                container.recorder.setSampleRate(it)
                            }
                        },
                    )
                    Divider()
                    FormatRow(
                        current = format,
                        onSelect = { scope.launch { container.settings.setFormat(it) } },
                    )
                }

                // ── Calibration ─────────────────────────────────────────────
                SectionHeader(stringResource(R.string.settings_section_calibration))
                SettingCard {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            stringResource(R.string.settings_calibration_status),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        val preferred = capabilities?.preferredStrategy
                        val statusText = if (preferred != null) {
                            strategyQuality(preferred)
                        } else {
                            stringResource(R.string.settings_calibration_unknown)
                        }
                        Text(
                            statusText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.settings_calibration_reset_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    container.capabilities.clear()
                                    snackbar.showSnackbar(
                                        ctx.getString(R.string.settings_calibration_done),
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.RestartAlt, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.settings_calibration_reset))
                        }
                    }
                }

                // ── Transcription (cloud STT, BYOK) ─────────────────────────
                SectionHeader(stringResource(R.string.settings_section_transcribe))
                SettingCard {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            stringResource(R.string.settings_transcribe_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = sttBaseUrl,
                            onValueChange = { scope.launch { container.settings.setSttBaseUrl(it) } },
                            label = { Text(stringResource(R.string.settings_transcribe_base_url)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = sttApiKey,
                            onValueChange = { scope.launch { container.settings.setSttApiKey(it) } },
                            label = { Text(stringResource(R.string.settings_transcribe_api_key)) },
                            singleLine = true,
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = sttModel,
                            onValueChange = { scope.launch { container.settings.setSttModel(it) } },
                            label = { Text(stringResource(R.string.settings_transcribe_model)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                // ── Auto-cleanup ────────────────────────────────────────────
                SectionHeader(stringResource(R.string.settings_section_cleanup))
                SettingCard {
                    CleanupChooserRow(
                        title = stringResource(R.string.settings_cleanup_age_title),
                        desc = stringResource(R.string.settings_cleanup_age_desc),
                        choices = AGE_CHOICES,
                        labelFor = { value ->
                            if (value == null) stringResource(R.string.settings_cleanup_off)
                            else if (value == 365) stringResource(R.string.settings_cleanup_year_short)
                            else stringResource(R.string.settings_cleanup_days_short, value)
                        },
                        current = cleanupAgeDays,
                        onSelect = { v ->
                            scope.launch {
                                container.settings.setAutoCleanupMaxAgeDays(v)
                                if (v != null) snackbar.showSnackbar(cleanupAppliedMsg)
                            }
                        },
                    )
                    Divider()
                    CleanupChooserRow(
                        title = stringResource(R.string.settings_cleanup_size_title),
                        desc = stringResource(R.string.settings_cleanup_size_desc),
                        choices = SIZE_CHOICES,
                        labelFor = { value ->
                            if (value == null) stringResource(R.string.settings_cleanup_off)
                            else stringResource(R.string.settings_cleanup_gb_short, value)
                        },
                        current = cleanupSizeGb,
                        onSelect = { v ->
                            scope.launch {
                                container.settings.setAutoCleanupMaxSizeGb(v)
                                if (v != null) snackbar.showSnackbar(cleanupAppliedMsg)
                            }
                        },
                    )
                }

                // ── About ───────────────────────────────────────────────────
                SectionHeader(stringResource(R.string.settings_section_about))
                SettingCard {
                    InfoRow(
                        title = stringResource(R.string.settings_version),
                        value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    )
                    Divider()
                    InfoRow(
                        title = stringResource(R.string.settings_storage_path),
                        value = ctx.getExternalFilesDir(null)?.resolve("recordings")?.absolutePath
                            ?: ctx.filesDir.resolve("recordings").absolutePath,
                    )
                }

                Spacer(Modifier.height(40.dp))
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(16.dp),
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp),
    )
}

@Composable
private fun SettingCard(content: @Composable () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column { content() }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun SampleRateRow(
    current: Int,
    onSelect: (Int) -> Unit,
) {
    Column(modifier = Modifier.padding(20.dp)) {
        Text(
            stringResource(R.string.settings_sample_rate),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            stringResource(R.string.settings_sample_rate_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        // Expressive ButtonGroup: ToggleButton's default shapes() animates the
        // corner radius from rounded → square on press → checked-square. Reads
        // as "I committed" rather than the legacy chip's flat colour swap.
        ButtonGroup(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(8_000, 16_000, 48_000).forEach { rate ->
                ToggleButton(
                    checked = current == rate,
                    onCheckedChange = { onSelect(rate) },
                    shapes = ToggleButtonDefaults.shapes(),
                    modifier = Modifier.weight(1f),
                ) { Text("${rate / 1000} кГц") }
            }
        }
    }
}

@Composable
private fun FormatRow(
    current: RecordingFormat,
    onSelect: (RecordingFormat) -> Unit,
) {
    Column(modifier = Modifier.padding(20.dp)) {
        Text(
            stringResource(R.string.settings_format),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            stringResource(R.string.settings_format_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        val options = listOf(
            RecordingFormat.AAC to stringResource(R.string.settings_format_aac),
            RecordingFormat.WAV to stringResource(R.string.settings_format_wav),
        )
        ButtonGroup(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (fmt, label) ->
                ToggleButton(
                    checked = current == fmt,
                    onCheckedChange = { onSelect(fmt) },
                    shapes = ToggleButtonDefaults.shapes(),
                    modifier = Modifier.weight(1f),
                ) { Text(label) }
            }
        }
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Divider() {
    androidx.compose.material3.HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        modifier = Modifier.padding(horizontal = 20.dp),
    )
}

// Cleanup choice sets — first entry (`null`) is always "off" so the user
// can return to the default "no auto-cleanup" state. Day choices match the
// spec: 7 / 14 / 30 / 60 / 90 / 180 / 365.
private val AGE_CHOICES: List<Int?> = listOf(null, 7, 14, 30, 60, 90, 180, 365)

// GB choices: 1 / 2 / 5 / 10 / 20 / 50 + an "off" entry.
private val SIZE_CHOICES: List<Int?> = listOf(null, 1, 2, 5, 10, 20, 50)

/**
 * Generic title + description + horizontally-flowing chip group used by
 * both auto-cleanup rows. `null` in [choices] represents the OFF choice
 * (rendered as "Не очищати" via [labelFor]).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CleanupChooserRow(
    title: String,
    desc: String,
    choices: List<Int?>,
    labelFor: @Composable (Int?) -> String,
    current: Int?,
    onSelect: (Int?) -> Unit,
) {
    Column(modifier = Modifier.padding(20.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            desc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.settings_cleanup_label_keep),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        // FlowRow + ToggleButton — chip-style wrap because seven choices
        // wouldn't fit a single ButtonGroup row. The ToggleButton shape morph
        // still gives the "I committed" tactility of the M3 Expressive
        // canonical ButtonGroup picker.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            choices.forEach { value ->
                ToggleButton(
                    checked = current == value,
                    onCheckedChange = { onSelect(value) },
                    shapes = ToggleButtonDefaults.shapes(),
                ) { Text(labelFor(value)) }
            }
        }
    }
}
