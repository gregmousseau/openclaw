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
import android.util.Log
import kotlin.math.sqrt

/**
 * Always-on wake word manager, refactored for reliability.
 *
 * Key improvements:
 * - Robust SpeechRecognizer lifecycle: recreates recognizer on fatal errors.
 * - Partial results processing: faster wake word detection.
 * - State management: clearer, less prone to race conditions.
 * - Error handling: detailed error logging and recovery.
 */
class VoiceWakeManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onCommand: suspend (String) -> Unit,
) {
  companion object {
    private const val tag = "VoiceWakeManager"
  }
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
        .coerceAtLeast(3200)

    // Lowered RMS threshold for better sensitivity in quieter environments.
    private val speechRmsThreshold = 600f
    // Increased frame count to prevent triggers from short, sharp noises.
    private val speechFramesToTrigger = 4
    private val silenceFramesToReset = 5

    private var vadJob: Job? = null

    // --- SpeechRecognizer ---
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var recognizerActive = false
    private var commandJustDispatched = false
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
            
            destroyRecognizer() // Ensure clean state
            recognizer = createRecognizer()
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
            destroyRecognizer()
            recognizerActive = false
            _isListening.value = false
            _statusText.value = statusText
        }
    }
    
    private fun destroyRecognizer() {
        recognizer?.cancel()
        recognizer?.setRecognitionListener(null)
        recognizer?.destroy()
        recognizer = null
    }

    private var suppressedByTalk = false

    fun setSuppressedByTalk(suppressed: Boolean) {
        if (suppressedByTalk == suppressed) return
        suppressedByTalk = suppressed
        if (suppressed) {
            vadJob?.cancel()
            vadJob = null
            mainHandler.post {
                recognizer?.cancel()
                recognizerActive = false
                _isListening.value = false
                _statusText.value = "Paused"
            }
        } else {
            scope.launch {
                delay(1500L) // Shorter delay after TTS
                mainHandler.post {
                    if (!stopRequested && !suppressedByTalk) {
                        _isListening.value = true
                        _statusText.value = "Listening"
                        startVad()
                    }
                }
            }
        }
    }

    private fun startVad() {
        if (suppressedByTalk || vadJob?.isActive == true) return
        vadJob = scope.launch {
            val ar = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate, channelConfig, audioFormat, bufferSize * 4,
                ).also { it.startRecording() }
            } catch (e: Exception) {
                Log.e(tag, "VAD AudioRecord creation failed")
                _statusText.value = "Mic unavailable"
                _isListening.value = false
                return@launch
            }

            val buf = ShortArray(bufferSize)
            var speechFrames = 0
            var silenceFrames = 0

            while (isActive && !stopRequested && !recognizerActive) {
                try {
                    val read = ar.read(buf, 0, bufferSize)
                    if (read <= 0) {
                        delay(30)
                        continue
                    }

                    val rms = computeRms(buf, read)
                    if (rms > speechRmsThreshold) {
                        silenceFrames = 0
                        speechFrames++
                        if (speechFrames >= speechFramesToTrigger) {
                            ar.stop()
                            ar.release()
                            activateSpeechRecognizer()
                            return@launch
                        }
                    } else {
                        silenceFrames++
                        if (silenceFrames >= silenceFramesToReset) speechFrames = 0
                    }
                } catch (e: Exception) {
                    Log.e(tag, "VAD read loop error")
                    break 
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

    private fun activateSpeechRecognizer() {
        if (stopRequested || recognizerActive) return
        recognizerActive = true
        mainHandler.post {
            if (stopRequested) {
                recognizerActive = false
                return@post
            }
            val r = recognizer ?: run {
                recognizerActive = false
                restartVad(500L)
                return@post
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            }
            try {
                r.startListening(intent)
            } catch (e: Exception) {
                Log.e(tag, "startListening failed")
                recognizerActive = false
                restartVad(500L)
            }
        }
    }

    private fun restartVad(delayMs: Long) {
        if (stopRequested) return
        recognizerActive = false
        scope.launch {
            delay(delayMs)
            if (!stopRequested && !suppressedByTalk) {
                startVad()
            }
        }
    }
    
    private fun scheduleVadRestart(delayMs: Long = 200L) {
        // Centralized restart logic
        if (recognizerActive) {
            mainHandler.post { recognizer?.cancel() }
        }
        restartVad(delayMs)
    }

    private fun createRecognizer(): SpeechRecognizer {
        val recognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer.setRecognitionListener(recognitionListener)
        return recognizer
    }
    
    private fun handleTranscription(text: String, isPartial: Boolean): Boolean {
        if (commandJustDispatched) return false
        _statusText.value = "Heard: $text"
        val command = VoiceWakeCommandExtractor.extractCommand(text, triggerWords) ?: return false
        
        commandJustDispatched = true
        scope.launch { onCommand(command) }
        _statusText.value = "Triggered: $command"
        
        // After dispatching, give a long pause before re-arming VAD
        mainHandler.postDelayed({ commandJustDispatched = false }, 10_000L)
        scheduleVadRestart(delayMs = 10_000L)
        return true
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _statusText.value = "Listening..."
        }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
             _statusText.value = "Thinking..."
        }

        override fun onError(error: Int) {
            if (stopRequested) return
            
            val errorMessage = when (error) {
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_NETWORK -> "Network error"
                SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                SpeechRecognizer.ERROR_SERVER -> "Server error"
                SpeechRecognizer.ERROR_CLIENT -> "Client error"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
                SpeechRecognizer.ERROR_NO_MATCH -> "No match"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mic permission required"
                else -> "Error: $error"
            }
            Log.w(tag, "SpeechRecognizer error: $errorMessage")
            _statusText.value = "Listening"

            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                stop("Microphone permission required")
                return
            }

            // For busy errors, especially on newer Android, a full reset is often needed.
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                mainHandler.post {
                    destroyRecognizer()
                    recognizer = createRecognizer()
                    scheduleVadRestart(500L)
                }
            } else {
                scheduleVadRestart(500L)
            }
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrEmpty()) {
                handleTranscription(text, isPartial = false)
            }
            if (!commandJustDispatched) {
                 scheduleVadRestart(3000L)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrEmpty()) {
                handleTranscription(text, isPartial = true)
            }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
