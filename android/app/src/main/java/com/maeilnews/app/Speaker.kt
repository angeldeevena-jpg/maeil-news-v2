package com.maeilnews.app

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Reads text aloud with the phone's own English and Korean voices (Android text-to-speech).
 * "Slow" is a lower speech rate.
 */
object Speaker {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: (() -> Unit)? = null

    fun speak(context: Context, text: String, locale: Locale, rate: Float) {
        if (text.isBlank()) return
        val app = context.applicationContext
        val action = { play(app, text, locale, rate) }
        if (tts == null) {
            tts = TextToSpeech(app) { status ->
                ready = status == TextToSpeech.SUCCESS
                pending?.invoke()
                pending = null
            }
        }
        if (ready) action() else pending = action
    }

    private fun play(context: Context, text: String, locale: Locale, rate: Float) {
        val engine = tts ?: return
        val result = engine.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Downloader.toast(
                context,
                "This phone has no ${locale.displayLanguage} voice. Install it in Settings → Text-to-speech."
            )
            return
        }
        engine.setSpeechRate(rate)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "maeil-news")
    }
}
