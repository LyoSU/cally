// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.ui

import android.os.Bundle
import android.util.Log
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.content.Intent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dev.lyo.callrec.App
import dev.lyo.callrec.notify.CompletedRecordingNotification
import dev.lyo.callrec.permissions.SetupStatus
import dev.lyo.callrec.ui.nav.CallrecApp
import dev.lyo.callrec.ui.theme.CallrecTheme

class MainActivity : ComponentActivity() {

    private var pendingCallId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i("Callrec", "[MainActivity] onCreate begin")
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)

        var keepSplash = true
        splash.setKeepOnScreenCondition { keepSplash }

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.Transparent.value.toInt(), Color.Transparent.value.toInt()),
            navigationBarStyle = SystemBarStyle.auto(Color.Transparent.value.toInt(), Color.Transparent.value.toInt()),
        )

        val container = (application as App).container
        container.shizuku.attach()
        container.shizuku.refresh()
        keepSplash = false

        val initialStatus = SetupStatus.probe(this, container)
        Log.i("Callrec", "[MainActivity] initial setup status: $initialStatus")
        pendingCallId = intent?.getStringExtra(CompletedRecordingNotification.EXTRA_OPEN_CALL_ID)

        setContent {
            CallrecTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    CallrecApp(
                        container = container,
                        startWithOnboarding = !initialStatus.allReady,
                        initialPlaybackCallId = pendingCallId,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Activity is `singleTop` per the manifest — when the user taps the
        // completed-recording notif while we're already running, we land
        // here instead of onCreate. Forward the deep-link extra so nav
        // jumps to the right playback.
        intent.getStringExtra(CompletedRecordingNotification.EXTRA_OPEN_CALL_ID)?.let {
            pendingCallId = it
        }
    }

    override fun onResume() {
        super.onResume()
        (application as App).container.shizuku.refresh()
    }

    override fun onDestroy() {
        (application as App).container.shizuku.detach()
        super.onDestroy()
    }
}
