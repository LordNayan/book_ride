package com.screensaathi.sarvam

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Uses an installed on-device voice only; it never selects a network voice. */
class DeviceTts(context: Context) {
    @Volatile private var ready = false
    @Volatile private var pending: Spoken? = null
    private var engine: TextToSpeech? = null

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) pending?.let {
                pending = null
                speak(it)
            }
        }
    }

    fun speak(spoken: Spoken): Boolean {
        if (!ready) {
            pending = spoken
            return false
        }
        val tts = engine ?: return false
        val locale = Locale.forLanguageTag(spoken.language)
        val voice = tts.voices?.firstOrNull {
            it.locale.language == locale.language && !it.isNetworkConnectionRequired
        } ?: return false
        tts.voice = voice
        return tts.speak(spoken.text, TextToSpeech.QUEUE_FLUSH, null, "saathi-${System.nanoTime()}") ==
            TextToSpeech.SUCCESS
    }

    fun stop() { engine?.stop() }
    fun close() { engine?.shutdown() }
}
