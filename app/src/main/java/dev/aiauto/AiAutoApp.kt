package dev.aiauto

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.aiauto.update.Updater

class AiAutoApp : Application() {
    lateinit var graph: Graph
        private set
    lateinit var updater: Updater
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
        graph.notifier.createChannels()
        graph.engine.resumeAll()
        graph.watchNetwork(this)
        updater = Updater(this, graph.scope)
        // Fires at startup and each time the app comes back from the background.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = updater.check()
        })
    }
}

val android.content.Context.graph: Graph get() = (applicationContext as AiAutoApp).graph
