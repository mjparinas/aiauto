package dev.aiauto.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import dev.aiauto.TaskService
import dev.aiauto.graph

/** Picks the repo for a new task, then listens for the spoken request on the car screen. */
class PickRepoScreen(carContext: CarContext) : Screen(carContext) {
    private val g = carContext.graph

    override fun onGetTemplate(): Template {
        val items = ItemList.Builder().setNoItemsMessage("Add a repo on your phone first.")
        g.repos.repos.value.take(6).forEach { repo ->
            items.addItem(
                Row.Builder()
                    .setTitle(repo.name)
                    .addText(repo.owner)
                    .setOnClickListener {
                        screenManager.push(
                            ListenScreen(
                                carContext,
                                title = "New task in ${repo.name}",
                                fallback = {
                                    g.notifier.askForNewTask(repo)
                                    CarToast.makeText(carContext, "Reply to the AIAuto message with your request", CarToast.LENGTH_LONG).show()
                                    screenManager.pop()
                                },
                            ) { text ->
                                val task = g.engine.start(repo, text)
                                TaskService.ensureRunning(carContext)
                                screenManager.pop()
                                screenManager.push(TaskDetailScreen(carContext, task.id))
                            },
                        )
                    }
                    .build(),
            )
        }
        return ListTemplate.Builder()
            .setTitle("Which repo?")
            .setHeaderAction(Action.BACK)
            .setSingleList(items.build())
            .build()
    }
}
