package ai.kairo.gallery.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the voice search UI shows. */
sealed interface VoiceState {
    data object Idle : VoiceState
    /** Microphone is open. [partial] = words heard so far, [level] = 0..1 loudness for the animation. */
    data class Listening(val partial: String = "", val level: Float = 0f, val speaking: Boolean = false) : VoiceState
    data class Done(val text: String) : VoiceState
    data class Error(val message: String) : VoiceState
}

/**
 * Speech -> text for voice search, English (US) only.
 *
 * Uses Android's on-device recognizer (Android System Intelligence on the iQOO 15, whose en-US pack is
 * installed), so audio never leaves the phone. Falls back to the default recognizer only on phones without one.
 *
 * Must be created and used on the main thread. One recognizer is kept for the whole screen: destroying one and
 * creating another straight away dropped the service connection (error 11) in testing.
 */
class VoiceRecognizer(private val context: Context) {

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state

    /** True when speech is recognised on the phone (on-device recognizer). */
    val onDevice: Boolean = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    private var recognizer: SpeechRecognizer? = null

    /** [hints]: gallery words (titles, venues, names) the recognizer should expect, e.g. "Irumudi". */
    fun start(hints: List<String> = emptyList()) {
        val r = try {
            recognizer ?: (if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)).also { recognizer = it }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not create recognizer", t)
            _state.value = VoiceState.Error("Speech recognition isn't available on this phone")
            return
        }
        runCatching { r.cancel() }  // end any previous session, keep the connection
        r.setRecognitionListener(listener)
        _state.value = VoiceState.Listening()
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                // Bias towards the gallery's own words so names like "Irumudi" aren't heard as "hero modi".
                if (Build.VERSION.SDK_INT >= 33 && hints.isNotEmpty()) {
                    putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(hints))
                }
            }
        )
        Log.i(TAG, "Listening ($LANGUAGE, onDevice=$onDevice, ${hints.size} hints)")
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {
            (_state.value as? VoiceState.Listening)?.let { _state.value = it.copy(speaking = true) }
        }
        override fun onRmsChanged(rmsdB: Float) {
            // rmsdB is roughly -2 (silence) .. 10 (loud speech).
            val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            (_state.value as? VoiceState.Listening)?.let { _state.value = it.copy(level = level) }
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            (_state.value as? VoiceState.Listening)?.let { _state.value = it.copy(speaking = false, level = 0f) }
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults.firstText() ?: return
            (_state.value as? VoiceState.Listening)?.let { _state.value = it.copy(partial = text) }
        }
        override fun onResults(results: Bundle?) {
            val text = results.firstText()?.trim().orEmpty()
            _state.value = if (text.isNotEmpty()) VoiceState.Done(text) else VoiceState.Error("Didn't catch that. Tap the mic and try again")
            Log.i(TAG, "Heard: \"$text\"")
        }
        override fun onError(error: Int) {
            Log.w(TAG, "Recognizer error $error")
            _state.value = VoiceState.Error(message(error))
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /** Stop listening and use what was heard so far. */
    fun stop() {
        recognizer?.stopListening()
    }

    fun cancel() {
        recognizer?.let { runCatching { it.cancel() } }
        _state.value = VoiceState.Idle
    }

    fun reset() {
        _state.value = VoiceState.Idle
    }

    fun release() {
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }

    private fun Bundle?.firstText(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    private fun message(error: Int) = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that. Tap the mic and try again"
        SpeechRecognizer.ERROR_AUDIO -> "Microphone problem. Try again"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow microphone access to search by voice"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "English voice isn't available offline on this phone"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Voice is busy. Try again in a moment"
        else -> "Voice search stopped (code $error). Try again"
    }

    companion object {
        private const val TAG = "KairoVoice"
        const val LANGUAGE = "en-US"  // the pack installed on-device on the iQOO 15
    }
}
