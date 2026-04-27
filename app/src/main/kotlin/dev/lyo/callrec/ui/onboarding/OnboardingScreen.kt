// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.ui.onboarding

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.provider.Settings
import dev.lyo.callrec.R
import dev.lyo.callrec.di.AppContainer
import dev.lyo.callrec.permissions.AppPermissions
import dev.lyo.callrec.permissions.BatteryOptimizations
import dev.lyo.callrec.permissions.rememberPermissionsState
import dev.lyo.callrec.recorder.ShizukuState
import kotlinx.coroutines.launch

/**
 * Four ordered steps:
 *   1. Install Shizuku        (NotRunning   → ≥ NeedPermission)
 *   2. Activate it            (NotRunning   → Ready/NeedPermission)
 *   3. Grant Shizuku to us    (NeedPermission/Denied → Ready)
 *   4. OS runtime permissions (RECORD_AUDIO + POST_NOTIFICATIONS + READ_PHONE_STATE)
 *
 * "Continue" enables only when all four are green. We detect Shizuku install
 * presence by trying to open its provider authority — cheaper than a
 * PackageManager.getPackageInfo on tight cold-starts.
 */
@Composable
fun OnboardingScreen(
    container: AppContainer,
    onDone: () -> Unit,
) {
    val ctx = LocalContext.current
    val state by container.shizuku.state.collectAsState()
    val scope = rememberCoroutineScope()
    val (perms, requestPerms) = rememberPermissionsState()
    val grantedSet by perms.granted.collectAsState()
    val allRuntimeGranted = grantedSet.containsAll(AppPermissions.essential)

    val shizukuInstalled = state != ShizukuState.NotRunning ||
        runCatching { ctx.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0) }.isSuccess
    val shizukuActivated = state != ShizukuState.NotRunning && state != ShizukuState.Outdated
    val shizukuPermitted = state == ShizukuState.Ready

    // canDrawOverlays / battery have no change broadcast — re-read on every
    // RESUME instead of an 800 ms infinite poll. The system Settings page is
    // a separate Activity, so coming back to onboarding always passes through
    // RESUMED → that's our refresh trigger.
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    var batteryExempt by remember { mutableStateOf(BatteryOptimizations.isIgnoring(ctx)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.currentStateFlow.collect {
            if (it.isAtLeast(Lifecycle.State.RESUMED)) {
                overlayGranted = Settings.canDrawOverlays(ctx)
                batteryExempt = BatteryOptimizations.isIgnoring(ctx)
            }
        }
    }

    val readyToContinue = shizukuInstalled && shizukuActivated && shizukuPermitted &&
        allRuntimeGranted && overlayGranted && batteryExempt

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 24.dp, vertical = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.onboarding_title),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                stringResource(R.string.app_tagline),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))

            StepCard(
                index = 1,
                icon = Icons.Outlined.Download,
                title = stringResource(R.string.onboarding_step_install),
                desc = stringResource(R.string.onboarding_step_install_desc),
                done = shizukuInstalled,
                action = if (!shizukuInstalled) {
                    { openShizukuStorePage(ctx) }
                } else null,
                actionLabel = stringResource(R.string.onboarding_open_shizuku),
            )
            StepCard(
                index = 2,
                icon = Icons.Outlined.Bolt,
                title = stringResource(R.string.onboarding_step_activate),
                desc = stringResource(R.string.onboarding_step_activate_desc),
                done = shizukuActivated,
                action = if (shizukuInstalled && !shizukuActivated) {
                    { openShizukuApp(ctx) }
                } else null,
                actionLabel = stringResource(R.string.onboarding_open_shizuku),
            )
            StepCard(
                index = 3,
                icon = Icons.Outlined.Verified,
                title = stringResource(R.string.onboarding_step_grant),
                desc = stringResource(R.string.onboarding_step_grant_desc),
                done = shizukuPermitted,
                action = if (shizukuActivated && !shizukuPermitted) {
                    { scope.launch { container.shizuku.requestPermission() } }
                } else null,
                actionLabel = stringResource(R.string.onboarding_step_grant),
            )
            StepCard(
                index = 4,
                icon = Icons.Outlined.LockOpen,
                title = "Дозволи системи",
                desc = "Мікрофон, сповіщення, статус телефону, журнал дзвінків, контакти — щоб запис стартував і поряд із записом було видно, з ким говорили.",
                done = allRuntimeGranted,
                action = if (!allRuntimeGranted) requestPerms else null,
                actionLabel = "Дозволити",
            )
            StepCard(
                index = 5,
                icon = Icons.Outlined.Layers,
                title = stringResource(R.string.onboarding_step_overlay),
                desc = stringResource(R.string.onboarding_step_overlay_desc),
                done = overlayGranted,
                action = if (!overlayGranted) {
                    {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${ctx.packageName}"),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { ctx.startActivity(intent) }
                    }
                } else null,
                actionLabel = stringResource(R.string.onboarding_step_overlay_action),
            )
            StepCard(
                index = 6,
                icon = Icons.Outlined.BatteryFull,
                title = stringResource(R.string.onboarding_step_battery),
                desc = stringResource(R.string.onboarding_step_battery_desc),
                done = batteryExempt,
                action = if (!batteryExempt) {
                    { BatteryOptimizations.requestExemption(ctx) }
                } else null,
                actionLabel = stringResource(R.string.onboarding_step_battery_action),
            )

            Spacer(Modifier.height(8.dp))

            // Manual recheck — re-polls all sources at once.
            OutlinedButton(
                onClick = {
                    container.shizuku.refresh()
                    overlayGranted = Settings.canDrawOverlays(ctx)
                    batteryExempt = BatteryOptimizations.isIgnoring(ctx)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Перевірити статус")
            }

            AnimatedVisibility(
                visible = state == ShizukuState.Denied,
                enter = fadeIn(spring(stiffness = 200f)),
                exit = fadeOut(tween(150)),
            ) {
                Text(
                    text = stringResource(R.string.err_shizuku_denied) +
                        " — відкрий Shizuku та надай дозвіл вручну.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Button(
                onClick = {
                    scope.launch {
                        container.settings.setOnboardingDone(true)
                        onDone()
                    }
                },
                enabled = readyToContinue,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.onboarding_continue)) }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StepCard(
    index: Int,
    icon: ImageVector,
    title: String,
    desc: String,
    done: Boolean,
    action: (() -> Unit)?,
    actionLabel: String,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (done) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (done) {
                        Icon(
                            Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp),
                        )
                    } else {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "$index. $title",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        desc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (action != null) {
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = action) { Text(actionLabel) }
                }
            }
        }
    }
}

private fun openShizukuStorePage(ctx: android.content.Context) {
    // Try Play Store first; if not present (de-Googled devices), fall back to
    // Shizuku's official site.
    val pkg = "moe.shizuku.privileged.api"
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(market) }.onFailure {
        runCatching { ctx.startActivity(web) }
    }
}

private fun openShizukuApp(ctx: android.content.Context) {
    val launch = ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
    if (launch != null) {
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(launch) }
    } else {
        openShizukuStorePage(ctx)
    }
}
