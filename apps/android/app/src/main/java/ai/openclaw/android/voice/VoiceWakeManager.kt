package ai.openclaw.android.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Always-on wake word manager.
 *
 * Uses a single persistent SpeechRecognizer instance that is created once
 * and reused across all restarts. OxygenOS (and other OEMs) play the
 * mic-access sound on session *creation*, not on repeated startListening()
 * calls to the same instance — so the bing fires only once when wake mode
 * is enabled, not every 5 seconds.
 */
class VoiceWakeManager(
  private val context: Context,
  private val scope: CoroutineScope,
  private val onCommand: suspend (String) -> Unit,
) {
  private val mainHandler = Handler(Looper.getMainLooper())

  private val _isListening = MutableStateFlow(false)
  val isListening: StateFlow<Boolean> = _isListening

  private val _statusText = MutableStateFlow("Off")
  val statusText: StateFlow<String> = _statusText

  var triggerWords: List<String> = emptyList()
    private set

  // Single persistent recognizer — never destroyed while wake mode is on
  private var recognizer: SpeechRecognizer? = null
  private var restartJob: Job? = null
  private var lastDispatched: String? = null
  private var stopRequested = false

  fun setTriggerWords(words: List<String>) {
    triggerWords = words
  }

  fun start() {
    mainHandler.post {
      if (_isListening.value) return@post
      stopRequested = false

      if (!SpeechRecognizer.isRecognitionAvailable(context)) {
        _statusText.value = "Speech recognizer unavailable"
        return@post
      }

      try {
        // Create ONCE — reused for all subsequent restarts
        recognizer = createRecognizer().also { it.setRecognitionListener(listener) }
        startListeningInternal()
      } catch (err: Throwable) {
        _isListening.value = false
        _statusText.value = "Start failed: ${err.message ?: err::class.simpleName}"
      }
    }
  }

  fun stop(statusText: String = "Off") {
    stopRequested = true
    restartJob?.cancel()
    restartJob = null
    mainHandler.post {
      _isListening.value = false
      _statusText.value = statusText
      recognizer?.cancel()
      recognizer?.destroy()
      recognizer = null
    }
  }

  private fun createRecognizer(): SpeechRecognizer =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    ) {
      SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
    } else {
      SpeechRecognizer.createSpeechRecognizer(context)
    }

  private fun startListeningInternal() {
    val r = recognizer ?: return
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
      putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
      putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
      putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
      putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
      // Keep session open; don't cut off mid-phrase
      putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000L)
      putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
    }
    _statusText.value = "Listening"
    _isListening.value = true
    // Reuse existing instance — no new session creation, no OEM mic sound
    r.startListening(intent)
  }

  private fun scheduleRestart(delayMs: Long = 300) {
    if (stopRequested) return
    restartJob?.cancel()
    restartJob = scope.launch {
      delay(delayMs)
      mainHandler.post {
        if (stopRequested) return@post
        // Call startListening() on the SAME instance — no destroy/recreate
        startListeningInternal()
      }
    }
  }

  private fun handleTranscription(text: String) {
    val command = VoiceWakeCommandExtractor.extractCommand(text, triggerWords) ?: return
    if (command == lastDispatched) return
    lastDispatched = command
    scope.launch { onCommand(command) }
    _statusText.value = "Triggered"
    scheduleRestart(delayMs = 600)
  }

  private val listener = object : RecognitionListener {
    override fun onReadyForSpeech(params: Bundle?) { _statusText.value = "Listening" }
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() { scheduleRestart() }
    override fun onError(error: Int) {
      if (stopRequested) return
      _isListening.value = false
      if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
        stop("Microphone permission required"); return
      }
      _statusText.value = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Listening"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
        else -> "Listening"
      }
      scheduleRestart(delayMs = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 800L else 300L)
    }
    override fun onResults(results: Bundle?) {
      results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull()?.let(::handleTranscription)
      scheduleRestart()
    }
    override fun onPartialResults(partialResults: Bundle?) {
      partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull()?.let(::handleTranscription)
    }
    override fun onEvent(eventType: Int, params: Bundle?) {}
  }
}
