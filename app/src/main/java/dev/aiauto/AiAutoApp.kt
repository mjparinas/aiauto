package dev.aiauto

import android.app.Application

class AiAutoApp : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
        graph.notifier.createChannels()
        graph.engine.resumeAll()
    }
}

val android.content.Context.graph: Graph get() = (applicationContext as AiAutoApp).graph
