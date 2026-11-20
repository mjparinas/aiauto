package dev.aiauto.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import dev.aiauto.MainActivity
import dev.aiauto.R
import dev.aiauto.core.RepoRef
import dev.aiauto.core.Speaker as Who
import dev.aiauto.core.Task
import dev.aiauto.core.TaskMessage
import dev.aiauto.data.Settings

/**
 * Posts each task as a messaging conversation. Android Auto reads these aloud and lets the
 * driver reply by voice; a reply becomes the next prompt for that task.
 */
class Notifier(private val context: Context, private val settings: Settings, private val speaker: Speaker) {
    private val manager = NotificationManagerCompat.from(context)
    private val me = Person.Builder().setName("You").setKey("me").build()
    private val claude = Person.Builder()
        .setName("Claude")
        .setKey("claude")
        .setIcon(IconCompat.createWithResource(context, R.drawable.ic_spark))
        .build()

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_TASKS, "Task updates", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_WORKING, "Claude is working", NotificationManager.IMPORTANCE_LOW))
    }

    fun onMessage(task: Task, message: TaskMessage) {
        if (message.speaker == Who.You) return
        post(task)
        if (settings.readAloudInCar && speaker.inCar) speaker.speak(message.text)
    }

    /** Shows (or refreshes) the task's conversation so it pops up on the car screen. */
    fun post(task: Task) {
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle("${task.repo.name}: ${task.title}")
            .setGroupConversation(false)
        task.messages.takeLast(MAX_MESSAGES).forEach { m ->
            style.addMessage(m.text, m.timeMillis, if (m.speaker == Who.You) me else claude)
        }
        notify(task.id.hashCode(), style, replyTo = task.id)
    }

    /** Opens a fresh conversation asking what to do in [repo]. Replying by voice starts a task. */
    fun askForNewTask(repo: RepoRef) {
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle("New task: ${repo.name}")
            .addMessage("What should I work on in ${repo.name}? Reply with your request.", System.currentTimeMillis(), claude)
        notify(newTaskId(repo), style, replyTo = NEW_PREFIX + repo.fullName)
    }

    fun clearNewTask(repo: RepoRef) = manager.cancel(newTaskId(repo))

    fun clear(taskId: String) = manager.cancel(taskId.hashCode())

    fun workingNotification() = NotificationCompat.Builder(context, CH_WORKING)
        .setSmallIcon(R.drawable.ic_spark)
        .setContentTitle("Claude is working")
        .setContentText("Updates will arrive as messages.")
        .setOngoing(true)
        .setContentIntent(openApp(null))
        .build()

    private fun notify(id: Int, style: NotificationCompat.MessagingStyle, replyTo: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) return

        val replyIntent = PendingIntent.getBroadcast(
            context, id,
            Intent(context, ReplyReceiver::class.java).setAction(ReplyReceiver.ACTION_REPLY).putExtra(ReplyReceiver.EXTRA_TARGET, replyTo),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val reply = NotificationCompat.Action.Builder(R.drawable.ic_spark, "Reply", replyIntent)
            .addRemoteInput(RemoteInput.Builder(ReplyReceiver.KEY_TEXT).setLabel("Tell Claude").build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
        val readIntent = PendingIntent.getBroadcast(
            context, id,
            Intent(context, ReplyReceiver::class.java).setAction(ReplyReceiver.ACTION_READ).putExtra(ReplyReceiver.EXTRA_TARGET, replyTo),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val markRead = NotificationCompat.Action.Builder(R.drawable.ic_spark, "Mark as read", readIntent)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()

        val notification = NotificationCompat.Builder(context, CH_TASKS)
            .setSmallIcon(R.drawable.ic_spark)
            .setColor(0xFFC96F4A.toInt())
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(false)
            .addAction(reply)
            .addInvisibleAction(markRead)
            .setContentIntent(openApp(replyTo.takeUnless { it.startsWith(NEW_PREFIX) }))
            .build()
        manager.notify(id, notification)
    }

    private fun openApp(taskId: String?): PendingIntent = PendingIntent.getActivity(
        context, taskId?.hashCode() ?: 0,
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TASK, taskId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun newTaskId(repo: RepoRef) = (NEW_PREFIX + repo.fullName).hashCode()

    companion object {
        const val CH_TASKS = "tasks"
        const val CH_WORKING = "working"
        const val WORKING_ID = 1
        const val NEW_PREFIX = "new:"
        private const val MAX_MESSAGES = 8
    }
}
