package dev.aiauto.notify

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.Observer
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reads updates aloud through the phone's text-to-speech, which Android Auto routes to the
 * car speakers. Only speaks while the phone is connected to a car. Music is ducked while it talks,
 * and anything said before the speech engine is ready is queued instead of dropped.
 */
class Speaker(context: Context) {
    @Volatile var inCar = false
        private set
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()
    private val queued = AtomicInteger(0)
    private val utteranceIds = AtomicInteger(0)
    private val pending = mutableListOf<String>()
    private var ready = false
    private val tts = TextToSpeech(context.applicationContext) { status -> onInit(status) }

    init {
        CarConnection(context.applicationContext).type.observeForever(Observer { type ->
            inCar = type == CarConnection.CONNECTION_TYPE_PROJECTION || type == CarConnection.CONNECTION_TYPE_NATIVE
        })
    }

    @Synchronized
    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts.language = Locale.getDefault()
        tts.setAudioAttributes(attributes)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finished()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished()
        })
        ready = true
        pending.forEach(::say)
        pending.clear()
    }

    @Synchronized
    fun speak(text: String) {
        if (text.isBlank()) return
        if (ready) say(text)
        else if (pending.size < MAX_PENDING) pending.add(text)
    }

    @Synchronized
    fun stop() {
        pending.clear()
        if (ready) tts.stop()
        queued.set(0)
        audio.abandonAudioFocusRequest(focus)
    }

    private fun say(text: String) {
        if (queued.getAndIncrement() == 0) audio.requestAudioFocus(focus)
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "u" + utteranceIds.incrementAndGet())
    }

    private fun finished() {
        if (queued.decrementAndGet() <= 0) {
            queued.set(0)
            audio.abandonAudioFocusRequest(focus)
        }
    }

    private companion object {
        const val MAX_PENDING = 5
    }
}
