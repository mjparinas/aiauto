package dev.aiauto.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import dev.aiauto.core.Speaker
import dev.aiauto.core.TaskStatus
import dev.aiauto.TaskService
import dev.aiauto.graph
import kotlinx.coroutines.launch

/** One task's latest updates, with buttons to hear them or reply by voice. */
class TaskDetailScreen(carContext: CarContext, private val taskId: String) : Screen(carContext) {
    private val g = carContext.graph

    init {
        lifecycleScope.launch { g.tasks.tasks.collect { invalidate() } }
    }

    override fun onGetTemplate(): Template {
        val task = g.tasks.get(taskId)
            ?: return MessageTemplate.Builder("This task was removed.").setHeaderAction(Action.BACK).build()

        val pane = Pane.Builder()
        task.messages.filter { it.speaker != Speaker.You }.takeLast(4).forEach { m ->
            pane.addRow(Row.Builder().setTitle(if (m.speaker == Speaker.Claude) "Claude" else "Status").addText(m.text).build())
        }
        if (task.messages.none { it.speaker != Speaker.You }) {
            pane.addRow(Row.Builder().setTitle("Waiting for Claude").addText(TasksScreen.statusLabel(task)).build())
        }

        val stopped = task.status == TaskStatus.Failed
        if (stopped) {
            pane.addAction(
                Action.Builder()
                    .setTitle("Try again")
                    .setBackgroundColor(CarColor.PRIMARY)
                    .setOnClickListener {
                        g.engine.retry(taskId)
                        TaskService.ensureRunning(carContext)
                        CarToast.makeText(carContext, "Trying again", CarToast.LENGTH_SHORT).show()
                    }
                    .build(),
            )
        }
        pane.addAction(
            Action.Builder()
                .setTitle("Reply")
                .apply { if (!stopped) setBackgroundColor(CarColor.PRIMARY) }
                .setOnClickListener {
                    screenManager.push(
                        ListenScreen(
                            carContext,
                            title = "Reply to Claude",
                            fallback = {
                                g.notifier.post(task)
                                CarToast.makeText(carContext, "Open the AIAuto message and reply by voice", CarToast.LENGTH_LONG).show()
                            },
                        ) { text ->
                            g.engine.prompt(taskId, text)
                            TaskService.ensureRunning(carContext)
                        },
                    )
                }
                .build(),
        )
        val readAloud = Action.Builder()
            .setTitle("Read aloud")
            .setOnClickListener {
                // When stopped, the latest status line is the reason, which is what the driver needs to hear.
                val latest = if (stopped) task.lastMessage else task.messages.lastOrNull { it.speaker == Speaker.Claude } ?: task.lastMessage
                g.speaker.speak("${task.title}. ${TasksScreen.statusLabel(task)}. ${latest?.text.orEmpty()}")
            }
            .build()
        // A pane holds two actions; when stopped, Try again takes one of them and Read aloud moves to the header.
        if (!stopped) pane.addAction(readAloud)

        return PaneTemplate.Builder(pane.build())
            .setTitle(task.title)
            .setHeaderAction(Action.BACK)
            .apply { if (stopped) setActionStrip(ActionStrip.Builder().addAction(readAloud).build()) }
            .build()
    }
}
