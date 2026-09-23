package com.example.agentchat.data.voice

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

interface SpeechRecognizerListener {
    fun onReadyForSpeech(params: Bundle?) {}
    fun onBeginningOfSpeech() {}
    fun onResults(results: Bundle?) {}
    fun onError(error: Int) {}
}

interface SpeechRecognizerClient {
    fun setRecognitionListener(listener: SpeechRecognizerListener?)
    fun startListening(intent: Intent)
    fun stopListening()
    fun cancel()
    fun destroy()
}

fun interface ScheduledVoiceTask {
    fun cancel()
}

interface VoiceInputScheduler {
    fun schedule(delayMs: Long, task: () -> Unit): ScheduledVoiceTask
}

private object MainThreadVoiceInputScheduler : VoiceInputScheduler {
    private val handler = Handler(Looper.getMainLooper())
    override fun schedule(delayMs: Long, task: () -> Unit): ScheduledVoiceTask {
        val runnable = Runnable(task)
        handler.postDelayed(runnable, delayMs)
        return ScheduledVoiceTask { handler.removeCallbacks(runnable) }
    }
}

class VoiceInputController(
    private val context: Context? = null,
    private val recognizerFactory: (() -> SpeechRecognizerClient?)? = null,
    private val onTranscript: (String) -> Unit = {},
    private val scheduler: VoiceInputScheduler = MainThreadVoiceInputScheduler,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(VoiceInputState.IDLE)
    val state: StateFlow<VoiceInputState> = _state.asStateFlow()
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var generation = 0L
    private var activeSession: Session? = null

    fun start() {
        if (!isMainThread()) {
            mainHandler.post(::start)
            return
        }
        releaseRecognizer()
        val next = recognizerFactory?.invoke() ?: createPlatformRecognizer()
        if (next == null) {
            setFailure(VoiceInputState.UNAVAILABLE, "当前设备不支持语音输入")
            return
        }
        val session = createSession(++generation, next)
        activeSession = session
        try {
            next.setRecognitionListener(session.listener)
            _errorMessage.value = null
            _state.value = VoiceInputState.LISTENING
            next.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                },
            )
        } catch (error: Throwable) {
            if (error is CancellationException) {
                try {
                    releaseRecognizer(session.token)
                } finally {
                    throw error
                }
            }
            releaseRecognizer(session.token)
            _errorMessage.value = "语音识别失败，请重试"
            _state.value = VoiceInputState.ERROR
        }
    }

    fun startOrRequestPermission(hasPermission: Boolean, requestPermission: () -> Unit) {
        if (hasPermission) start() else requestPermission()
    }

    fun stop() {
        if (!isMainThread()) {
            mainHandler.post(::stop)
            return
        }
        val session = activeSession ?: return
        _state.value = VoiceInputState.TRANSCRIBING
        try {
            session.recognizer.stopListening()
            if (isCurrent(session)) {
                session.timeout = scheduler.schedule(STOP_TIMEOUT_MS) {
                    dispatchToMain {
                        if (isCurrent(session)) {
                            try {
                                _errorMessage.value = "语音识别超时，请改用文字输入"
                                _state.value = VoiceInputState.ERROR
                            } finally {
                                releaseRecognizer(session.token)
                            }
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) {
                try {
                    releaseRecognizer(session.token)
                } finally {
                    throw error
                }
            }
            releaseRecognizer(session.token)
            _errorMessage.value = "语音识别失败，请重试"
            _state.value = VoiceInputState.ERROR
        }
    }

    fun cancel() {
        if (!isMainThread()) {
            mainHandler.post(::cancel)
            return
        }
        try {
            releaseRecognizer()
        } finally {
            _state.value = VoiceInputState.IDLE
        }
    }

    fun reportPermissionDenied() = setFailure(VoiceInputState.ERROR, "未获得麦克风权限，已切换为文字输入")

    fun release() {
        if (!isMainThread()) {
            mainHandler.post(::release)
            return
        }
        try {
            releaseRecognizer()
        } finally {
            _state.value = VoiceInputState.IDLE
        }
    }

    fun dispose() = release()

    suspend fun releaseAndWait() = suspendCancellableCoroutine<Unit> { continuation ->
        if (isMainThread()) {
            release()
            continuation.resume(Unit)
        } else {
            mainHandler.post {
                release()
                continuation.resume(Unit)
            }
        }
    }

    private fun createPlatformRecognizer(): SpeechRecognizerClient? {
        val appContext = context ?: return null
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) return null
        return AndroidSpeechRecognizerClient(SpeechRecognizer.createSpeechRecognizer(appContext))
    }

    private fun releaseRecognizer(expectedToken: Long? = null) {
        val current = activeSession ?: return
        if (expectedToken != null && current.token != expectedToken) return
        activeSession = null
        current.timeout?.cancel()
        try {
            current.recognizer.setRecognitionListener(null)
        } finally {
            try {
                current.recognizer.cancel()
            } finally {
                current.recognizer.destroy()
            }
        }
    }

    private fun setFailure(state: VoiceInputState, message: String) {
        releaseRecognizer()
        _errorMessage.value = message
        _state.value = state
    }

    private fun createSession(token: Long, recognizer: SpeechRecognizerClient): Session = Session(token, recognizer).also { session ->
        session.listener = object : SpeechRecognizerListener {
            override fun onReadyForSpeech(params: Bundle?) = dispatchToMain { if (isCurrent(session)) _state.value = VoiceInputState.LISTENING }
            override fun onBeginningOfSpeech() = dispatchToMain { if (isCurrent(session)) _state.value = VoiceInputState.LISTENING }
            override fun onResults(results: Bundle?) = dispatchToMain { if (isCurrent(session)) handleResults(session, results) }
            override fun onError(error: Int) = dispatchToMain { if (isCurrent(session)) handleError(session, error) }
        }
    }

    private fun handleResults(session: Session, results: Bundle?) {
        try {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            if (text.isEmpty()) {
                _errorMessage.value = "没有识别到语音，请改用文字输入"
                _state.value = VoiceInputState.ERROR
                return
            }
            _state.value = VoiceInputState.RESULT
            onTranscript(text)
        } finally {
            releaseRecognizer(session.token)
        }
    }

    private fun handleError(session: Session, error: Int) {
        try {
            _errorMessage.value = errorMessageFor(error)
            _state.value = VoiceInputState.ERROR
        } finally {
            releaseRecognizer(session.token)
        }
    }

    private fun isCurrent(session: Session): Boolean = activeSession?.token == session.token

    private fun dispatchToMain(action: () -> Unit) {
        if (isMainThread()) action() else mainHandler.post(action)
    }

    private fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    private class Session(val token: Long, val recognizer: SpeechRecognizerClient) {
        lateinit var listener: SpeechRecognizerListener
        var timeout: ScheduledVoiceTask? = null
    }

    private fun errorMessageFor(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "未获得麦克风权限，已切换为文字输入"
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别网络不可用，请改用文字输入"
        SpeechRecognizer.ERROR_NO_MATCH -> "没有识别到语音，请改用文字输入"
        else -> "语音识别失败，请重试"
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 10_000L
    }
}

class VoiceInputLifecycleObserver(
    private val controller: VoiceInputController,
) : androidx.lifecycle.LifecycleEventObserver {
    override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: androidx.lifecycle.Lifecycle.Event) {
        when (event) {
            androidx.lifecycle.Lifecycle.Event.ON_STOP -> controller.cancel()
            androidx.lifecycle.Lifecycle.Event.ON_DESTROY -> controller.dispose()
            else -> Unit
        }
    }
}

private class AndroidSpeechRecognizerClient(
    private val delegate: SpeechRecognizer,
) : SpeechRecognizerClient {
    override fun setRecognitionListener(listener: SpeechRecognizerListener?) {
        if (listener == null) {
            delegate.setRecognitionListener(null)
            return
        }
        delegate.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = listener.onReadyForSpeech(params)
            override fun onBeginningOfSpeech() = listener.onBeginningOfSpeech()
            override fun onResults(results: Bundle?) = listener.onResults(results)
            override fun onError(error: Int) = listener.onError(error)
            override fun onEndOfSpeech() = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
    }
    override fun startListening(intent: Intent) = delegate.startListening(intent)
    override fun stopListening() = delegate.stopListening()
    override fun cancel() = delegate.cancel()
    override fun destroy() = delegate.destroy()
}
