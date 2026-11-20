package dev.aiauto.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import dev.aiauto.TaskService
import dev.aiauto.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Handles voice replies and "mark as read" from the phone or the car. */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val target = intent.getStringExtra(EXTRA_TARGET) ?: return
        val g = context.graph
        if (intent.action == ACTION_READ) {
            if (target == Notifier.CHAT_TARGET) return
            if (target.startsWith(Notifier.NEW_PREFIX)) g.repos.find(target.removePrefix(Notifier.NEW_PREFIX))?.let(g.notifier::clearNewTask)
            else g.notifier.clear(target)
            return
        }
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()?.trim()
        if (text.isNullOrEmpty()) return
        if (target == Notifier.CHAT_TARGET) {
            // Show the question right away so the car stops waiting, then answer when Claude replies.
            g.notifier.postChat(fromYou = text)
            val pending = goAsync()
            g.scope.launch(Dispatchers.IO) {
                try {
                    val answer = g.assistant.ask(text)
                    g.notifier.postChat(fromClaude = answer)
                    if (g.speaker.inCar) g.speaker.speak(answer)
                } finally {
                    pending.finish()
                }
            }
            return
        }
        if (target.startsWith(Notifier.NEW_PREFIX)) {
            val repo = g.repos.find(target.removePrefix(Notifier.NEW_PREFIX)) ?: return
            g.notifier.clearNewTask(repo)
            val task = g.engine.start(repo, text)
            g.notifier.post(task)
        } else {
            g.engine.prompt(target, text)
            g.tasks.get(target)?.let(g.notifier::post)
        }
        TaskService.ensureRunning(context)
    }

    companion object {
        const val ACTION_REPLY = "dev.aiauto.REPLY"
        const val ACTION_READ = "dev.aiauto.READ"
        const val EXTRA_TARGET = "target"
        const val KEY_TEXT = "text"
    }
}
