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
 *   AudioRecord (silent VAD) → stop AudioRecord → SpeechRecognizer → restart AudioRecord
 *
 * AudioRecord holds the mic silently (no OEM privacy sounds).
 * When speech is detected, AudioRecord is STOPPED before SpeechRecognizer starts
 * so both never compete for the mic simultaneously.
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

  private val sampleRate = 16000
  private val bufferSize = AudioRecord.getMinBufferSize(
    sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
  ).coerceAtLeast(3200)

  // Low threshold: fires on pre-speech ambient noise/breathing so SpeechRecognizer
  // is armed before the wake word finishes, not after it.
  private val speechRmsThreshold = 100f
  private val speechFramesToTrigger = 1

  private var vadJob: Job? = null
  @Volatile private var recognizerActive = false
  private var recognizer: SpeechRecognizer? = null
  private var lastDispatched: String? = null
  private var stopRequested = false

  fun setTriggerWords(words: List<String>) {
    triggerWords = words
  }

  fun start() {
    if (_isListening.value) return
    stopRequested = false
    _isListening.value = true
    _statusText.value = "Listening"
    startVad()
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
    }
    _isListening.value = false
    _statusText.value = statusText
  }

  // ---------------------------------------------------------------------------
  // VAD — AudioRecord loop, silent, no system mic sounds
  // ---------------------------------------------------------------------------

  private fun startVad() {
    vadJob?.cancel()
    vadJob = scope.launch {
      val ar = try {
        AudioRecord(
          MediaRecorder.AudioSource.VOICE_RECOGNITION,
          sampleRate,
          AudioFormat.CHANNEL_IN_MONO,
          AudioFormat.ENCODING_PCM_16BIT,
          bufferSize * 4,
        ).also { it.startRecording() }
      } catch (e: Exception) {
        _statusText.value = "Mic unavailable: ${e.message}"
        _isListening.value = false
        return@launch
      }

      val buf = ShortArray(bufferSize)
      var speechFrames = 0

      while (isActive && !stopRequested) {
        val read = ar.read(buf, 0, bufferSize)
        if (read <= 0) { delay(30); continue }

        val rms = computeRms(buf, read)

        if (rms > speechRmsThreshold) {
          speechFrames++
          if (speechFrames >= speechFramesToTrigger && !recognizerActive) {
            // Stop AudioRecord BEFORE starting SpeechRecognizer to avoid mic conflict
            ar.stop()
            ar.release()
            triggerRecognizer()
            return@launch  // VAD loop ends; restartVad() called after recognizer finishes
          }
        } else {
          speechFrames = 0
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
  // SpeechRecognizer — only runs when speech detected, mic is free
  // ---------------------------------------------------------------------------

  private fun triggerRecognizer() {
    if (stopRequested) return
    recognizerActive = true
    mainHandler.post {
      if (stopRequested) { recognizerActive = false; restartVad(); return@post }
      try {
        recognizer?.destroy()
        recognizer = createRecognizer().also { it.setRecognitionListener(recognitionListener) }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
          putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
          putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
          putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
          putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
          // Keep session open long enough to capture the full wake word + command.
          // Min speech length prevents instant cutoff on short words.
          putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
          putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
          putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
        }
        recognizer?.startListening(intent)
      } catch (e: Exception) {
        recognizerActive = false
        restartVad()
      }
    }
  }

  private fun releaseRecognizer() {
    recognizerActive = false
    mainHandler.post {
      recognizer?.cancel()
      recognizer?.destroy()
      recognizer = null
    }
    restartVad()
  }

  private fun restartVad() {
    if (stopRequested) return
    scope.launch {
      delay(300) // brief gap so mic is fully released before reopening
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
    override fun onReadyForSpeech(params: Bundle?) { _statusText.value = "Listening" }
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() { releaseRecognizer() }
    override fun onError(error: Int) {
      if (stopRequested) return
      if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
        stop("Microphone permission required"); return
      }
      _statusText.value = "Listening"
      releaseRecognizer()
    }
    override fun onResults(results: Bundle?) {
      results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull()?.let(::handleTranscription)
      releaseRecognizer()
    }
    override fun onPartialResults(partialResults: Bundle?) {
      partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull()?.let(::handleTranscription)
    }
    override fun onEvent(eventType: Int, params: Bundle?) {}
  }
}
