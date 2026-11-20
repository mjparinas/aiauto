package dev.aiauto.notify

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.Observer
import java.util.Locale

/**
 * Reads updates aloud through the phone's text-to-speech, which Android Auto routes to the
 * car speakers. Only speaks while the phone is connected to a car.
 */
class Speaker(context: Context) {
    @Volatile var inCar = false
        private set
    private var ready = false
    private val tts = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
    }.apply { language = Locale.getDefault() }

    init {
        CarConnection(context.applicationContext).type.observeForever(Observer { type ->
            inCar = type == CarConnection.CONNECTION_TYPE_PROJECTION || type == CarConnection.CONNECTION_TYPE_NATIVE
        })
    }

    fun speak(text: String) {
        if (ready) tts.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
    }
}
