package ai.openclaw.android.voice

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Always-on wake word manager.
 *
 * Architecture:
 *   AudioRecord (VOICE_RECOGNITION, silent) — continuous idle monitoring
 *     ↓ RMS energy detected
 *   Stop AudioRecord → startListening() on PERSISTENT SpeechRecognizer
 *     ↓ result / error
 *   200ms gap → restart AudioRecord VAD
 *
 * Key properties:
 * - Silence: only AudioRecord is active (no OEM bing)
 * - Speech detected: SpeechRecognizer activates (bing fires once — expected UX)
 * - Recognizer created ONCE at start(), reused via startListening()/stopListening()
 * - AudioRecord stopped before startListening() to avoid mic conflict
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

  // --- AudioRecord VAD ---
  private val sampleRate = 16000
  private val channelConfig = AudioFormat.CHANNEL_IN_MONO
  private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
  private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    .coerceAtLeast(3200) // ~100ms at 16kHz

  // RMS 500 is easily crossed by normal speech; background noise rarely exceeds ~150–200
  private val speechRmsThreshold = 500f
  // 2 consecutive speech frames (~200ms) before triggering — avoids single-frame pops
  private val speechFramesToTrigger = 2
  // Silence frames before resetting the speech counter
  private val silenceFramesToReset = 5

  private var vadJob: Job? = null

  // --- SpeechRecognizer (persistent — created once, reused across speech events) ---
  private var recognizer: SpeechRecognizer? = null
  @Volatile private var recognizerActive = false
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

      // Create ONCE — reused via startListening() for all subsequent speech events
      recognizer = createRecognizer().also { it.setRecognitionListener(recognitionListener) }
      recognizerActive = false
      _isListening.value = true
      _statusText.value = "Listening"
      startVad()
    }
  }

  fun stop(statusText: String = "Off") {
    stopRequested = true
    vadJob?.cancel()
    vadJob = null
    mainHandler.post {
      recognizer?.cancel()
      recognizer?.destroy()
      recognizer = null
      recognizerActive = false
      _isListening.value = false
      _statusText.value = statusText
    }
  }

  // ---------------------------------------------------------------------------
  // VAD loop — silent AudioRecord, no OEM sounds during idle
  // ---------------------------------------------------------------------------

  private fun startVad() {
    vadJob?.cancel()
    vadJob = scope.launch {
      val ar = try {
        AudioRecord(
          MediaRecorder.AudioSource.VOICE_RECOGNITION,
          sampleRate, channelConfig, audioFormat, bufferSize * 4,
        ).also { it.startRecording() }
      } catch (e: Exception) {
        _statusText.value = "Mic unavailable: ${e.message}"
        _isListening.value = false
        return@launch
      }

      val buf = ShortArray(bufferSize)
      var speechFrames = 0
      var silenceFrames = 0

      while (isActive && !stopRequested && !recognizerActive) {
        val read = ar.read(buf, 0, bufferSize)
        if (read <= 0) { delay(30); continue }

        val rms = computeRms(buf, read)
        if (rms > speechRmsThreshold) {
          silenceFrames = 0
          speechFrames++
          if (speechFrames >= speechFramesToTrigger) {
            // Release mic BEFORE SpeechRecognizer claims it
            ar.stop()
            ar.release()
            activateSpeechRecognizer()
            return@launch   // VAD loop ends; restartVad() called after recognizer completes
          }
        } else {
          silenceFrames++
          if (silenceFrames >= silenceFramesToReset) speechFrames = 0
        }
      }

      ar.stop()
      ar.release()
    }
  }

  private fun computeRms(buf: ShortArray, count: Int): Float {
    var sum = 0.0
    for (i in 0 until count) sum += buf[i].toDouble() * buf[i]
    return sqrt(sum / count).toFloat()
  }

  // ---------------------------------------------------------------------------
  // SpeechRecognizer — activated only when VAD detects sustained speech energy
  // ---------------------------------------------------------------------------

  private fun activateSpeechRecognizer() {
    if (stopRequested) return
    recognizerActive = true
    mainHandler.post {
      if (stopRequested) { recognizerActive = false; return@post }
      val r = recognizer ?: run { recognizerActive = false; restartVad(); return@post }
      val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
      }
      try {
        r.startListening(intent)   // OEM bing fires here — expected, user is speaking
      } catch (e: Exception) {
        recognizerActive = false
        restartVad()
      }
    }
  }

  private fun restartVad() {
    if (stopRequested) return
    recognizerActive = false
    scope.launch {
      delay(200) // brief gap before re-arming mic
      if (!stopRequested) startVad()
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

  private fun handleTranscription(text: String) {
    val command = VoiceWakeCommandExtractor.extractCommand(text, triggerWords) ?: return
    if (command == lastDispatched) return
    lastDispatched = command
    scope.launch { onCommand(command) }
    _statusText.value = "Triggered"
  }

  private val recognitionListener = object : RecognitionListener {
    override fun onReadyForSpeech(params: Bundle?) {
      _statusText.value = "Listening"
    }
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onError(error: Int) {
      if (stopRequested) return
      if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
        stop("Microphone permission required")
        return
      }
      _statusText.value = "Listening"
      restartVad()
    }
    override fun onResults(results: Bundle?) {
      results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull()?.let(::handleTranscription)
      restartVad()
    }
    override fun onPartialResults(partialResults: Bundle?) {
      partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull()?.let(::handleTranscription)
    }
    override fun onEvent(eventType: Int, params: Bundle?) {}
  }
}
