package dev.aiauto.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarText
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import dev.aiauto.core.Task
import dev.aiauto.core.TaskStatus
import dev.aiauto.graph
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** The home screen in the car: recent tasks with their status, and a way to start a new one. */
class TasksScreen(carContext: CarContext) : Screen(carContext) {
    private val g = carContext.graph

    init {
        lifecycleScope.launch {
            combine(g.tasks.tasks, g.repos.repos) { _, _ -> }.collect { invalidate() }
        }
    }

    override fun onGetTemplate(): Template {
        val items = ItemList.Builder()
        val tasks = g.tasks.tasks.value.filterNot { it.archived }.take(MAX_ROWS)
        if (tasks.isEmpty()) {
            items.setNoItemsMessage(if (g.isConfigured) "No tasks yet. Tap New task to start one." else "Finish setup on your phone first.")
        }
        tasks.forEach { task ->
            items.addItem(
                Row.Builder()
                    .setTitle(task.title)
                    .addText(CarText.create("${task.repo.name} · ${statusLabel(task)}"))
                    .apply { task.lastMessage?.let { addText(it.text) } }
                    .setOnClickListener { screenManager.push(TaskDetailScreen(carContext, task.id)) }
                    .build(),
            )
        }
        val newTask = Action.Builder()
            .setTitle("New task")
            .setOnClickListener { screenManager.push(PickRepoScreen(carContext)) }
            .build()
        return ListTemplate.Builder()
            .setTitle("Claude tasks")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(items.build())
            .setActionStrip(ActionStrip.Builder().addAction(newTask).build())
            .build()
    }

    companion object {
        private const val MAX_ROWS = 6

        fun statusLabel(task: Task) = when (task.status) {
            TaskStatus.Starting -> "Starting"
            TaskStatus.Working -> "Working"
            TaskStatus.NeedsInput -> "Needs your reply"
            TaskStatus.Done -> if (task.prNumber != null) "PR ${task.prNumber} ready" else "Done"
            TaskStatus.Failed -> "Stopped"
        }
    }
}
