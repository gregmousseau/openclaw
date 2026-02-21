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
 *   AudioRecord (silent, continuous) → RMS VAD → SpeechRecognizer (only on speech)
 *
 * AudioRecord uses VOICE_RECOGNITION source and never triggers the OEM
 * mic-activation sound. SpeechRecognizer is only started when the VAD detects
 * real speech energy, so the bing (if any) fires at most once per actual
 * utterance — not every 4-5 seconds of silence.
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

  // RMS threshold — adjust if too sensitive / not sensitive enough
  private val speechRmsThreshold = 800f
  // How many consecutive speech frames before triggering recognizer
  private val speechFramesToTrigger = 3
  // Silence frames before resetting speech detection
  private val silenceFramesToReset = 8

  private var vadJob: Job? = null
  private var audioRecord: AudioRecord? = null

  // --- SpeechRecognizer ---
  private var recognizer: SpeechRecognizer? = null
  private var recognizerActive = false
  private var recognizerRestartJob: Job? = null
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
    recognizerRestartJob?.cancel()
    recognizerRestartJob = null
    audioRecord?.stop()
    audioRecord?.release()
    audioRecord = null
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
  // VAD loop — runs entirely on a background coroutine, no system sounds
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
      audioRecord = ar

      val buf = ShortArray(bufferSize)
      var speechFrames = 0
      var silenceFrames = 0

      while (isActive && !stopRequested) {
        val read = ar.read(buf, 0, bufferSize)
        if (read <= 0) { delay(50); continue }

        val rms = computeRms(buf, read)

        if (rms > speechRmsThreshold) {
          silenceFrames = 0
          speechFrames++
          if (speechFrames >= speechFramesToTrigger && !recognizerActive) {
            triggerRecognizer()
          }
        } else {
          silenceFrames++
          if (silenceFrames >= silenceFramesToReset) {
            speechFrames = 0
          }
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
  // SpeechRecognizer — only activated on detected speech
  // ---------------------------------------------------------------------------

  private fun triggerRecognizer() {
    if (stopRequested) return
    recognizerActive = true
    mainHandler.post {
      if (stopRequested) { recognizerActive = false; return@post }
      try {
        recognizer?.destroy()
        recognizer = createRecognizer().also { it.setRecognitionListener(recognitionListener) }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
          putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
          putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
          putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
          putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        recognizer?.startListening(intent)
      } catch (e: Exception) {
        recognizerActive = false
      }
    }
  }

  private fun releaseRecognizer(restartDelayMs: Long = 200) {
    mainHandler.post {
      recognizer?.cancel()
      recognizer?.destroy()
      recognizer = null
      recognizerActive = false
    }
    // Brief delay so VAD can re-arm before next speech burst
    recognizerRestartJob?.cancel()
    recognizerRestartJob = scope.launch { delay(restartDelayMs) }
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
    override fun onEndOfSpeech() {
      releaseRecognizer()
    }
    override fun onError(error: Int) {
      if (stopRequested) return
      if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
        _statusText.value = "Microphone permission required"
        stop("Microphone permission required")
        return
      }
      // Reset status but keep VAD running — it will re-trigger on next speech
      _statusText.value = "Listening"
      releaseRecognizer()
    }
    override fun onResults(results: Bundle?) {
      val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
      list.firstOrNull()?.let(::handleTranscription)
      releaseRecognizer()
    }
    override fun onPartialResults(partialResults: Bundle?) {
      val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
      list.firstOrNull()?.let(::handleTranscription)
    }
    override fun onEvent(eventType: Int, params: Bundle?) {}
  }
}
