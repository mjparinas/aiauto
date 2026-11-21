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
import java.util.concurrent.ConcurrentHashMap
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
    private val pending = mutableListOf<Pair<String, (() -> Unit)?>>()
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()
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
            override fun onDone(utteranceId: String?) = finished(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished(utteranceId)
        })
        ready = true
        pending.forEach { (text, onDone) -> say(text, onDone) }
        pending.clear()
    }

    /** Queues [text]; [onDone] runs on a background thread once it has been read out, unless [stop] cancels it. */
    @Synchronized
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (text.isBlank()) return
        if (ready) say(text, onDone)
        else if (pending.size < MAX_PENDING) pending.add(text to onDone)
    }

    @Synchronized
    fun stop() {
        pending.clear()
        callbacks.clear()
        if (ready) tts.stop()
        queued.set(0)
        audio.abandonAudioFocusRequest(focus)
    }

    private fun say(text: String, onDone: (() -> Unit)?) {
        if (queued.getAndIncrement() == 0) audio.requestAudioFocus(focus)
        val id = "u" + utteranceIds.incrementAndGet()
        onDone?.let { callbacks[id] = it }
        val result = runCatching { tts.speak(text, TextToSpeech.QUEUE_ADD, null, id) }
        // If the engine refused it, no onDone will come, so give the music its volume back now.
        if (result.getOrDefault(TextToSpeech.ERROR) == TextToSpeech.ERROR) finished(id)
    }

    private fun finished(utteranceId: String?) {
        if (queued.decrementAndGet() <= 0) {
            queued.set(0)
            audio.abandonAudioFocusRequest(focus)
        }
        utteranceId?.let { callbacks.remove(it)?.invoke() }
    }

    private companion object {
        const val MAX_PENDING = 5
    }
}
