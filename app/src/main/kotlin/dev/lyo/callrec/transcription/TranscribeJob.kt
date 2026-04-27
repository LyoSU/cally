// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.transcription

import dev.lyo.callrec.core.L
import dev.lyo.callrec.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** UI-observable state of a single transcription run. */
sealed interface TranscribeState {
    data object Idle : TranscribeState
    data object Running : TranscribeState
    data class Done(val text: String) : TranscribeState
    data class Error(val message: String) : TranscribeState
}

/**
 * Single in-flight transcription per app instance — multiple concurrent
 * uploads of large audio bodies will saturate mobile uplink and exhaust
 * provider rate limits.
 */
class TranscribeJob(
    private val container: AppContainer,
) {
    private val db get() = container.db
    private val scope: CoroutineScope get() = container.appScope
    private val _state = MutableStateFlow<TranscribeState>(TranscribeState.Idle)
    val state: StateFlow<TranscribeState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var currentJob: Job? = null

    fun start(callId: String, audioPath: String) {
        if (currentJob?.isActive == true) return
        currentJob = scope.launch(Dispatchers.IO) {
            mutex.withLock {
                _state.value = TranscribeState.Running
                runCatching {
                    val transcriber = TranscriberFactory.create(container)
                    L.i("TranscribeJob", "starting transcribe callId=$callId")
                    val text = transcriber.transcribe(File(audioPath))
                    L.d("TranscribeJob", "got ${text.length} chars")
                    db.calls().setTranscript(callId, text)
                    _state.value = TranscribeState.Done(text)
                }.onFailure { t ->
                    L.e("TranscribeJob", "transcription failed", t)
                    _state.value = TranscribeState.Error(t.message ?: t.javaClass.simpleName)
                }
            }
        }
    }

    fun reset() {
        currentJob?.cancel()
        _state.value = TranscribeState.Idle
    }
}
