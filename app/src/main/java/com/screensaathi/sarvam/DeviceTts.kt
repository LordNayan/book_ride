package com.screensaathi.sarvam

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Uses an installed on-device voice only; it never selects a network voice. */
class DeviceTts(context: Context) {
    @Volatile private var ready = false
    @Volatile private var pending: Pair<Spoken, (() -> Unit)?>? = null
    private var engine: TextToSpeech? = null
    private val completed = ConcurrentHashMap<String, () -> Unit>()
    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) {
            utteranceId?.let { completed.remove(it)?.invoke() }
        }
        override fun onError(utteranceId: String?) {
            utteranceId?.let { completed.remove(it) }
        }
        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            utteranceId?.let { completed.remove(it) }
        }
    }

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) pending?.let {
                pending = null
                speak(it.first, it.second)
            }
        }
    }

    fun speak(spoken: Spoken, onComplete: (() -> Unit)? = null): Boolean {
        if (!ready) {
            pending = spoken to onComplete
            return false
        }
        val tts = engine ?: return false
        val locale = Locale.forLanguageTag(spoken.language)
        val voice = tts.voices?.firstOrNull {
            it.locale.language == locale.language && !it.isNetworkConnectionRequired
        } ?: return false
        tts.voice = voice
        tts.setOnUtteranceProgressListener(listener)
        completed.clear() // QUEUE_FLUSH invalidates any older prompt.
        val id = "saathi-${System.nanoTime()}"
        if (onComplete != null) completed[id] = onComplete
        val started = tts.speak(spoken.text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.SUCCESS
        if (!started) completed.remove(id)
        return started
    }

    fun stop() {
        pending = null
        completed.clear()
        engine?.stop()
    }
    fun close() {
        stop()
        engine?.shutdown()
    }
}
