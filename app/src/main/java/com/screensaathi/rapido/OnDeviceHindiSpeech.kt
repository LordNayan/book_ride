package com.screensaathi.rapido

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Android's on-device recognizer. This class never creates a network recognizer. */
class OnDeviceHindiSpeech(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null

    fun start(onResult: (String) -> Unit, onFailure: (String) -> Unit) {
        cancel()
        if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            onFailure("इस फ़ोन पर ऑफ़लाइन आवाज़ पहचान उपलब्ध नहीं है।")
            return
        }
        val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        val service = try { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }
        catch (_: Exception) {
            onFailure("ऑफ़लाइन आवाज़ पहचान शुरू नहीं हो सकी।")
            return
        }
        recognizer = service

        fun fail(message: String) {
            if (recognizer !== service) return
            cancel()
            onFailure(message)
        }
        service.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) = fail("आवाज़ समझ नहीं आई। दोबारा बोलें।")
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                if (text.isBlank()) fail("आवाज़ समझ नहीं आई। दोबारा बोलें।")
                else if (recognizer === service) {
                    cancel()
                    onResult(text)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        if (Build.VERSION.SDK_INT >= 33) {
            service.checkRecognitionSupport(request, context.mainExecutor,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        if (recognizer !== service) return
                        val installed = support.installedOnDeviceLanguages.any { it.startsWith("hi", true) }
                        if (installed) service.startListening(request)
                        else {
                            val downloadable = support.supportedOnDeviceLanguages.any { it.startsWith("hi", true) }
                            if (downloadable) service.triggerModelDownload(request)
                            fail(if (downloadable) "हिंदी ऑफ़लाइन भाषा पैक डाउनलोड करें, फिर दोबारा बोलें।"
                                else "इस फ़ोन के ऑफ़लाइन पहचानकर्ता में हिंदी उपलब्ध नहीं है।")
                        }
                    }
                    override fun onError(error: Int) = fail("हिंदी ऑफ़लाइन पहचान जाँची नहीं जा सकी।")
                })
        } else service.startListening(request)
    }

    fun stop() { recognizer?.stopListening() }

    fun cancel() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }
}
