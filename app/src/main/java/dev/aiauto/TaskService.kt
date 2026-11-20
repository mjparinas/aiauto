package dev.aiauto

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the app alive while Claude is working, so status keeps arriving with the phone
 * locked in a mount or after Android Auto disconnects. Stops itself once no task is running.
 */
class TaskService : Service() {
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = graph.notifier.workingNotification()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        // Android can refuse a foreground start (background limits, missing permission). Tasks still run
        // while the app is open, and resume when it next starts, so stop quietly instead of crashing.
        val started = runCatching { ServiceCompat.startForeground(this, dev.aiauto.notify.Notifier.WORKING_ID, notification, type) }
        if (started.isFailure) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (watcher?.isActive != true) {
            watcher = CoroutineScope(Dispatchers.Default).launch {
                delay(5_000)
                var sinceRefresh = 0L
                // Stay up while Claude works, and while a new pull request's checks are running so the
                // driver hears the result and green pull requests get merged.
                while (graph.tasks.all().any { graph.engine.isRunning(it.id) } || graph.engine.isWatchingPullRequests()) {
                    if (sinceRefresh >= PR_REFRESH_MILLIS) {
                        graph.engine.refreshPullRequests()
                        sinceRefresh = 0
                    }
                    delay(5_000)
                    sinceRefresh += 5_000
                }
                stopSelf()
            }
        }
        // If Android kills the app mid-task, restart it; the app re-attaches to running tasks on startup.
        return START_STICKY
    }

    override fun onDestroy() {
        watcher?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val PR_REFRESH_MILLIS = 30_000L

        fun ensureRunning(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, TaskService::class.java)) }
        }
    }
}
