package com.fsaint.androidagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class AgentRuntimeService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val foreground by lazy { AndroidAgentRuntimeForegroundController(this) }
    private val handler by lazy {
        AgentRuntimeServiceCommandHandler(
            coordinator = BootRecoveryDependencies.coordinator,
            foreground = foreground,
            scope = serviceScope,
            stopSelfResult = { startId -> stopSelfResult(startId) },
            notificationsAvailable = { BackgroundRuntimeNotificationGate(this).canShowRuntimeNotification() },
        )
    }

    override fun onCreate() {
        super.onCreate()
        foreground.ensureChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        handler.handle(intent, startId)

    override fun onDestroy() {
        runBlocking { handler.shutdown() }
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.fsaint.androidagent.runtime.START"
        const val ACTION_STOP = "com.fsaint.androidagent.runtime.STOP"
        const val ACTION_RESTART = "com.fsaint.androidagent.runtime.RESTART"
        const val CHANNEL_ID = "agent_runtime"
        const val SPECIAL_USE_SUBTYPE = "user_authorized_persistent_agent_runtime"

        internal const val NOTIFICATION_ID = 7101

        fun startIntent(context: Context): Intent =
            Intent(context, AgentRuntimeService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent =
            Intent(context, AgentRuntimeService::class.java).setAction(ACTION_STOP)

        fun restartIntent(context: Context): Intent =
            Intent(context, AgentRuntimeService::class.java).setAction(ACTION_RESTART)
    }
}

internal class AgentRuntimeServiceCommandHandler(
    private val coordinator: AgentRuntimeRecovery,
    private val foreground: AgentRuntimeForegroundController,
    private val scope: CoroutineScope,
    private val stopSelfResult: (Int) -> Boolean,
    private val notificationsAvailable: () -> Boolean = { true },
) {
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val commandJob = scope.launch {
        for (command in commands) {
            when (command) {
                Command.Start -> coordinator.start()
                is Command.Stop -> {
                    coordinator.stop()
                    stopSelfResult(command.startId)
                }
                Command.Restart -> {
                    coordinator.stop()
                    coordinator.start()
                }
            }
        }
    }
    private val lifecycleLock = Any()
    private var destroyed = false

    fun handle(intent: Intent?, startId: Int): Int {
        return when (intent?.action ?: AgentRuntimeService.ACTION_START) {
            AgentRuntimeService.ACTION_STOP -> {
                enqueue(Command.Stop(startId))
                Service.START_NOT_STICKY
            }
            AgentRuntimeService.ACTION_RESTART -> {
                startOrReject(Command.Restart, startId)
            }
            else -> {
                startOrReject(Command.Start, startId)
            }
        }
    }

    private fun startOrReject(command: Command, startId: Int): Int {
        if (!notificationsAvailable()) {
            enqueue(Command.Stop(startId))
            return Service.START_NOT_STICKY
        }
        foreground.start()
        enqueue(command)
        return Service.START_STICKY
    }

    private fun enqueue(command: Command) {
        synchronized(lifecycleLock) {
            if (!destroyed) commands.trySend(command).getOrThrow()
        }
    }

    /** Stops all runtime work before removing the notification during service destruction. */
    suspend fun shutdown() {
        synchronized(lifecycleLock) {
            if (destroyed) return
            destroyed = true
            commands.close()
        }
        commandJob.cancelAndJoin()
        coordinator.stop()
        foreground.stop()
    }

    private sealed interface Command {
        data object Start : Command
        data class Stop(val startId: Int) : Command
        data object Restart : Command
    }
}

internal interface AgentRuntimeForegroundController {
    fun ensureChannel()
    fun start()
    fun stop()
}

private class AndroidAgentRuntimeForegroundController(
    private val service: Service,
) : AgentRuntimeForegroundController {
    private val notifications = AgentRuntimeNotificationFactory(service)

    override fun ensureChannel() = notifications.ensureChannel()

    override fun start() {
        ensureChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.startForeground(
                AgentRuntimeService.NOTIFICATION_ID,
                notifications.build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            service.startForeground(AgentRuntimeService.NOTIFICATION_ID, notifications.build())
        }
    }

    override fun stop() {
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
    }
}

internal class AgentRuntimeNotificationFactory(
    private val context: Context,
) {
    fun ensureChannel() {
        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(
                AgentRuntimeService.CHANNEL_ID,
                "Agent runtime",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    fun build(): Notification =
        Notification.Builder(context, AgentRuntimeService.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Dark Lord background access")
            .setContentText("Agent runtime is active for Telegram and queued work")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setShowWhen(false)
            .addAction(stopAction())
            .addAction(restartAction())
            .build()

    private fun stopAction(): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(context, android.R.drawable.ic_media_pause),
            "Stop",
            servicePendingIntent(AgentRuntimeService.ACTION_STOP, REQUEST_STOP),
        ).build()

    private fun restartAction(): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(context, android.R.drawable.ic_popup_sync),
            "Restart",
            servicePendingIntent(AgentRuntimeService.ACTION_RESTART, REQUEST_RESTART),
        ).build()

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            context,
            requestCode,
            Intent(context, AgentRuntimeService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private companion object {
        const val REQUEST_STOP = 1
        const val REQUEST_RESTART = 2
    }
}
