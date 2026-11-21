package dev.aiauto.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import dev.aiauto.R
import dev.aiauto.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The car's home screen: one big mic button. Tap it, say anything, and Claude's answer is read aloud
 * and shown here. Claude can start or answer coding tasks from the conversation too. The task list is
 * one tap away in the corner.
 */
class MicScreen(carContext: CarContext) : Screen(carContext) {
    private val g = carContext.graph
    private val voice = VoiceInput(carContext)
    private var state = State.Idle
    private var text = if (g.isConfigured) HELLO else SETUP
    private var heard: String? = null
    private var thinking: Job? = null

    private enum class State { Idle, Listening, Thinking }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                if (state == State.Listening) stopListening()
            }
            override fun onDestroy(owner: LifecycleOwner) = voice.cancel()
        })
    }

    private fun talk() {
        if (!g.isConfigured) {
            show(State.Idle, SETUP)
            return
        }
        thinking?.cancel()
        g.speaker.stop()
        heard = null
        show(State.Listening, "Listening…")
        voice.listen(
            onText = { spoken -> answer(spoken) },
            onError = { reason -> show(State.Idle, reason) },
        )
    }

    private fun stopListening() {
        voice.cancel()
        show(State.Idle, HELLO)
    }

    private fun answer(spoken: String) {
        heard = spoken
        show(State.Thinking, "Thinking…")
        thinking = lifecycleScope.launch {
            val reply = withContext(Dispatchers.IO) {
                runCatching { g.assistant.ask(spoken) }.getOrElse { "Sorry, that didn't go through. Try again." }
            }
            show(State.Idle, reply)
            runCatching { g.speaker.speak(reply) }
        }
    }

    private fun show(newState: State, message: String) {
        state = newState
        text = message
        invalidate()
    }

    override fun onGetTemplate(): Template {
        val body = heard?.takeIf { state != State.Listening }?.let { "You: $it\n\n$text" } ?: text
        val main = when (state) {
            State.Listening -> Action.Builder().setTitle("Stop").setOnClickListener { stopListening() }
            State.Thinking -> Action.Builder().setTitle("Ask again").setOnClickListener { talk() }
            State.Idle -> Action.Builder()
                .setTitle("Talk")
                .setIcon(micIcon())
                .setBackgroundColor(CarColor.PRIMARY)
                .setOnClickListener { talk() }
        }.build()
        val tasks = Action.Builder().setTitle("Tasks").setOnClickListener { screenManager.push(TasksScreen(carContext)) }.build()
        val fresh = Action.Builder().setTitle("New chat").setOnClickListener {
            thinking?.cancel()
            voice.cancel()
            g.speaker.stop()
            g.assistant.reset()
            heard = null
            show(State.Idle, HELLO)
        }.build()
        return MessageTemplate.Builder(body)
            .setTitle("Claude")
            .setHeaderAction(Action.APP_ICON)
            .setIcon(micIcon())
            .addAction(main)
            .setActionStrip(ActionStrip.Builder().addAction(fresh).addAction(tasks).build())
            .build()
    }

    private fun micIcon(): CarIcon = CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_mic)).build()

    private companion object {
        const val HELLO = "Tap Talk and ask Claude anything."
        const val SETUP = "Open AIAuto on your phone and add your API key first."
    }
}
