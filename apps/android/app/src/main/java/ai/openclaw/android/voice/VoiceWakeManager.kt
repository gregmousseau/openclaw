package ai.openclaw.android.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
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

class VoiceWakeManager(
  private val context: Context,
  private val scope: CoroutineScope,
  private val onCommand: suspend (String) -> Unit,
) {
  private val mainHandler = Handler(Looper.getMainLooper())
  private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

  private val _isListening = MutableStateFlow(false)
  val isListening: StateFlow<Boolean> = _isListening

  private val _statusText = MutableStateFlow("Off")
  val statusText: StateFlow<String> = _statusText

  var triggerWords: List<String> = emptyList()
    private set

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
        _isListening.value = false
        _statusText.value = "Speech recognizer unavailable"
        return@post
      }

      try {
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { it.setRecognitionListener(listener) }
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
      // Always restore audio in case we stopped mid-mute
      audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
    }
  }

  private fun startListeningInternal() {
    val r = recognizer ?: return
    val intent =
      Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        // Prefer on-device recognition: avoids network round-trip and suppresses
        // Google's system bing/boop UI sounds that fire on every session start/end.
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
      }

    // Belt-and-suspenders: mute STREAM_MUSIC briefly to catch any residual
    // system sounds before the recognizer confirms it's ready.
    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
    _statusText.value = "Listening"
    _isListening.value = true
    r.startListening(intent)
  }

  /** Silence the recognizer's end-of-session boop before scheduling a restart. */
  private fun silentRestart(delayMs: Long = 350) {
    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
    scheduleRestart(delayMs)
  }

  private fun scheduleRestart(delayMs: Long = 350) {
    if (stopRequested) return
    restartJob?.cancel()
    restartJob =
      scope.launch {
        delay(delayMs)
        mainHandler.post {
          if (stopRequested) return@post
          try {
            recognizer?.cancel()
            startListeningInternal()
          } catch (_: Throwable) {
            // Will be picked up by onError and retry again.
          }
        }
      }
  }

  private fun handleTranscription(text: String) {
    val command = VoiceWakeCommandExtractor.extractCommand(text, triggerWords) ?: return
    if (command == lastDispatched) return
    lastDispatched = command

    scope.launch { onCommand(command) }
    _statusText.value = "Triggered"
    scheduleRestart(delayMs = 650)
  }

  private val listener =
    object : RecognitionListener {
      override fun onReadyForSpeech(params: Bundle?) {
        // Recognizer is ready — safe to unmute now (startup sound has passed)
        mainHandler.postDelayed({
          audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        }, 100)
        _statusText.value = "Listening"
      }

      override fun onBeginningOfSpeech() {}

      override fun onRmsChanged(rmsdB: Float) {}

      override fun onBufferReceived(buffer: ByteArray?) {}

      override fun onEndOfSpeech() {
        silentRestart()
      }

      override fun onError(error: Int) {
        if (stopRequested) return
        _isListening.value = false
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
          audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
          _statusText.value = "Microphone permission required"
          return
        }

        _statusText.value =
          when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio error"
            SpeechRecognizer.ERROR_CLIENT -> "Client error"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "Listening"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Listening"
            else -> "Speech error ($error)"
          }
        silentRestart(delayMs = 600)
      }

      override fun onResults(results: Bundle?) {
        val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        list.firstOrNull()?.let(::handleTranscription)
        silentRestart()
      }

      override fun onPartialResults(partialResults: Bundle?) {
        val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        list.firstOrNull()?.let(::handleTranscription)
      }

      override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
