// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.permissions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Compose-friendly handle that re-evaluates permissions whenever the activity
 * resumes — this is critical because the user may have toggled permissions
 * in system Settings while we were backgrounded, and we want the UI to reflect
 * that without a full restart.
 */
class PermissionsState internal constructor() {
    private val _granted = MutableStateFlow<Set<String>>(emptySet())
    val granted: StateFlow<Set<String>> = _granted

    internal fun update(g: Set<String>) { _granted.value = g }

    fun isGranted(p: String): Boolean = p in _granted.value
    fun allGranted(required: List<String>): Boolean = required.all { it in _granted.value }
}

@Composable
fun rememberPermissionsState(
    required: List<String> = AppPermissions.essential,
    onResult: (granted: Boolean) -> Unit = {},
): Pair<PermissionsState, () -> Unit> {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state = remember { PermissionsState() }
    var pendingLaunch by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        // Refresh full picture: result only contains what we asked for, but we
        // care about any state change (e.g. a denied permission user re-granted
        // via Settings on the same trip).
        val granted = required.filter { AppPermissions.isGranted(ctx, it) }.toSet()
        state.update(granted)
        onResult(granted.containsAll(required))
        pendingLaunch = false
    }

    // Re-poll on every RESUMED transition.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.currentStateFlow.collect { stage ->
            if (stage.isAtLeast(Lifecycle.State.RESUMED)) {
                state.update(required.filter { AppPermissions.isGranted(ctx, it) }.toSet())
            }
        }
    }

    val request: () -> Unit = remember(launcher, required) {
        {
            val toAsk = required.filterNot { AppPermissions.isGranted(ctx, it) }
            if (toAsk.isEmpty()) {
                onResult(true)
            } else if (!pendingLaunch) {
                pendingLaunch = true
                launcher.launch(toAsk.toTypedArray())
            }
        }
    }

    return state to request
}
