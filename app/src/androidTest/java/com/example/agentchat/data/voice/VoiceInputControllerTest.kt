package com.example.agentchat.data.voice

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceInputControllerTest {
    @Test
    fun resultIsReturnedWithoutSendingAndRecognizerIsDestroyedOnRelease() {
        val recognizer = FakeRecognizer()
        var transcript = ""
        val controller = VoiceInputController(
            recognizerFactory = { recognizer },
            onTranscript = { transcript = it },
        )

        controller.start()
        assertEquals(VoiceInputState.LISTENING, controller.state.value)
        recognizer.emitResults("hello world")

        assertEquals("hello world", transcript)
        assertEquals(VoiceInputState.RESULT, controller.state.value)
        controller.release()
        assertTrue(recognizer.destroyed)
        assertTrue(recognizer.listenerCleared)
    }

    @Test
    fun recognizerErrorUsesReadableMessageAndReturnsToTextFallbackState() {
        val recognizer = FakeRecognizer()
        val controller = VoiceInputController(recognizerFactory = { recognizer })

        controller.start()
        recognizer.emitError(7)

        assertEquals(VoiceInputState.ERROR, controller.state.value)
        assertEquals("语音识别失败，请重试", controller.errorMessage.value)
        controller.release()
        assertTrue(recognizer.cancelled)
        assertTrue(recognizer.destroyed)
        assertTrue(recognizer.listenerCleared)
    }

    @Test
    fun firstClickRequestsPermissionWithoutCreatingRecognizer() {
        val recognizer = FakeRecognizer()
        var requested = false
        val controller = VoiceInputController(recognizerFactory = { recognizer })

        controller.startOrRequestPermission(hasPermission = false) { requested = true }

        assertTrue(requested)
        assertFalse(recognizer.started)
        assertEquals(VoiceInputState.IDLE, controller.state.value)
    }

    @Test
    fun unavailableDeviceFallsBackWithoutRecognizer() {
        val controller = VoiceInputController(recognizerFactory = { null })

        controller.start()

        assertEquals(VoiceInputState.UNAVAILABLE, controller.state.value)
        assertEquals("当前设备不支持语音输入", controller.errorMessage.value)
    }

    @Test
    fun stopAlwaysUnbindsAndDestroysRecognizer() {
        val recognizer = FakeRecognizer()
        val scheduler = FakeScheduler()
        var transcript = ""
        val controller = VoiceInputController(
            recognizerFactory = { recognizer },
            onTranscript = { transcript = it },
            scheduler = scheduler,
        )
        controller.start()

        controller.stop()

        assertTrue(recognizer.stopCalled)
        assertFalse(recognizer.listenerCleared)
        assertFalse(recognizer.destroyed)
        recognizer.emitResults("final words")
        assertEquals("final words", transcript)
        assertTrue(recognizer.listenerCleared)
        assertTrue(recognizer.destroyed)
    }

    @Test
    fun stopErrorReleasesRecognizerInCallbackFinally() {
        val recognizer = FakeRecognizer()
        val scheduler = FakeScheduler()
        val controller = VoiceInputController(recognizerFactory = { recognizer }, scheduler = scheduler)
        controller.start()

        controller.stop()
        recognizer.emitError(7)

        assertTrue(recognizer.listenerCleared)
        assertTrue(recognizer.destroyed)
    }

    @Test
    fun stopRuntimeExceptionBecomesReadableErrorWithoutEscapingClickHandler() {
        val recognizer = FakeRecognizer().apply { stopFailure = IllegalStateException("stop failed") }
        val controller = VoiceInputController(recognizerFactory = { recognizer })
        controller.start()

        controller.stop()

        assertEquals(VoiceInputState.ERROR, controller.state.value)
        assertEquals("语音识别失败，请重试", controller.errorMessage.value)
        assertTrue(recognizer.destroyed)
        assertTrue(recognizer.listenerCleared)
    }

    @Test
    fun stopCancellationExceptionPropagatesAfterRecognizerCleanup() {
        val cancellation = CancellationException("stop cancelled")
        val recognizer = FakeRecognizer().apply { stopFailure = cancellation }
        val controller = VoiceInputController(recognizerFactory = { recognizer })
        controller.start()

        var caught: CancellationException? = null
        try {
            controller.stop()
        } catch (error: CancellationException) {
            caught = error
        }

        assertTrue(caught === cancellation)
        assertTrue(recognizer.listenerCleared)
        assertTrue(recognizer.destroyed)
    }

    @Test
    fun stopWithoutTerminalCallbackUsesInjectableTimeoutToRelease() {
        val recognizer = FakeRecognizer()
        val scheduler = FakeScheduler()
        val controller = VoiceInputController(recognizerFactory = { recognizer }, scheduler = scheduler)
        controller.start()

        controller.stop()
        assertFalse(recognizer.destroyed)
        scheduler.runPending()

        assertTrue(recognizer.listenerCleared)
        assertTrue(recognizer.destroyed)
    }

    @Test
    fun cancelAlwaysUnbindsAndDestroysRecognizer() {
        val recognizer = FakeRecognizer()
        val controller = VoiceInputController(recognizerFactory = { recognizer })
        controller.start()

        controller.cancel()

        assertTrue(recognizer.cancelled)
        assertTrue(recognizer.listenerCleared)
        assertTrue(recognizer.destroyed)
    }

    @Test
    fun disposeAlwaysUnbindsAndDestroysRecognizer() {
        val recognizer = FakeRecognizer()
        val controller = VoiceInputController(recognizerFactory = { recognizer })
        controller.start()

        controller.dispose()

        assertTrue(recognizer.listenerCleared)
        assertTrue(recognizer.destroyed)
    }

    @Test
    fun cancellationFromTranscriptIsPropagatedAfterCleanup() {
        val recognizer = FakeRecognizer()
        val cancellation = CancellationException("test cancellation")
        val controller = VoiceInputController(
            recognizerFactory = { recognizer },
            onTranscript = { throw cancellation },
        )
        controller.start()

        var caught: CancellationException? = null
        try {
            recognizer.emitResults("hello")
        } catch (error: CancellationException) {
            caught = error
        }

        assertTrue(caught === cancellation)
        assertTrue(recognizer.listenerCleared)
        assertTrue(recognizer.destroyed)
    }

    @Test
    fun stopThenRestartIgnoresLateCallbacksFromOldSession() {
        val first = FakeRecognizer()
        val second = FakeRecognizer()
        val recognizers = ArrayDeque(listOf(first, second))
        val transcript = mutableListOf<String>()
        val controller = VoiceInputController(
            recognizerFactory = { recognizers.removeFirst() },
            onTranscript = { transcript += it },
            scheduler = FakeScheduler(),
        )

        controller.start()
        controller.stop()
        controller.start()
        first.emitLateResults("stale")
        first.emitLateError(7)
        assertFalse(second.destroyed)
        second.emitResults("current")

        assertEquals(listOf("current"), transcript)
        assertTrue(second.destroyed)
        assertTrue(first.destroyed)
    }

    @Test
    fun cancelIgnoresLateCallbackAndDoesNotReleaseNextSession() {
        val first = FakeRecognizer()
        val second = FakeRecognizer()
        val recognizers = ArrayDeque(listOf(first, second))
        val transcript = mutableListOf<String>()
        val controller = VoiceInputController(
            recognizerFactory = { recognizers.removeFirst() },
            onTranscript = { transcript += it },
        )

        controller.start()
        controller.cancel()
        controller.start()
        first.emitLateResults("stale")
        assertFalse(second.destroyed)
        second.emitResults("current")

        assertEquals(listOf("current"), transcript)
        assertTrue(second.destroyed)
    }

    @Test
    fun timeoutIgnoresLateCallbackAndDoesNotReleaseNextSession() {
        val first = FakeRecognizer()
        val second = FakeRecognizer()
        val recognizers = ArrayDeque(listOf(first, second))
        val scheduler = FakeScheduler()
        val transcript = mutableListOf<String>()
        val controller = VoiceInputController(
            recognizerFactory = { recognizers.removeFirst() },
            onTranscript = { transcript += it },
            scheduler = scheduler,
        )

        controller.start()
        controller.stop()
        scheduler.runPending()
        controller.start()
        first.emitLateResults("stale")
        assertFalse(second.destroyed)
        second.emitResults("current")

        assertEquals(listOf("current"), transcript)
        assertTrue(second.destroyed)
    }

    @Test
    fun startFailureBecomesReadableErrorAndCancellationStillPropagates() {
        val failure = FakeRecognizer().apply { startFailure = IllegalStateException("boom") }
        val failedController = VoiceInputController(recognizerFactory = { failure })

        failedController.start()

        assertEquals(VoiceInputState.ERROR, failedController.state.value)
        assertEquals("语音识别失败，请重试", failedController.errorMessage.value)
        assertTrue(failure.destroyed)

        val cancellation = CancellationException("cancelled")
        val cancelled = FakeRecognizer().apply { startFailure = cancellation }
        val cancelledController = VoiceInputController(recognizerFactory = { cancelled })
        var caught: CancellationException? = null
        try {
            cancelledController.start()
        } catch (error: CancellationException) {
            caught = error
        }
        assertTrue(caught === cancellation)
        assertTrue(cancelled.destroyed)
    }

    @Test
    fun lifecycleStopAndDestroyReleaseVoiceSession() {
        val recognizer = FakeRecognizer()
        val controller = VoiceInputController(recognizerFactory = { recognizer })
        val owner = TestLifecycleOwner()
        val observer = VoiceInputLifecycleObserver(controller)
        owner.lifecycle.addObserver(observer)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        controller.start()

        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        assertTrue(recognizer.destroyed)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        assertTrue(recognizer.destroyed)
    }

    private class FakeRecognizer : SpeechRecognizerClient {
        private var listener: SpeechRecognizerListener? = null
        var destroyed = false
        var cancelled = false
        var listenerCleared = false
        var started = false
        var stopCalled = false
        var startFailure: Throwable? = null
        var stopFailure: Throwable? = null
        private val callbacks = mutableListOf<SpeechRecognizerListener>()
        override fun setRecognitionListener(listener: SpeechRecognizerListener?) {
            this.listener = listener
            if (listener == null) listenerCleared = true else callbacks += listener
        }
        override fun startListening(intent: Intent) {
            startFailure?.let { throw it }
            started = true
            listener?.onReadyForSpeech(Bundle())
        }
        override fun stopListening() {
            stopCalled = true
            stopFailure?.let { throw it }
        }
        override fun cancel() { cancelled = true }
        override fun destroy() { destroyed = true }
        fun emitResults(text: String) {
            val bundle = Bundle().apply { putStringArrayList("results_recognition", arrayListOf(text)) }
            listener?.onResults(bundle)
        }
        fun emitError(code: Int) { listener?.onError(code) }
        fun emitLateResults(text: String) { callbacks.lastOrNull()?.onResults(Bundle().apply { putStringArrayList("results_recognition", arrayListOf(text)) }) }
        fun emitLateError(code: Int) { callbacks.lastOrNull()?.onError(code) }
    }

    private class FakeScheduler : VoiceInputScheduler {
        private var task: (() -> Unit)? = null
        override fun schedule(delayMs: Long, task: () -> Unit): ScheduledVoiceTask {
            this.task = task
            return ScheduledVoiceTask { this.task = null }
        }
        fun runPending() { task?.invoke() }
    }

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry
    }
}
