package com.fsaint.androidagent

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Telephony
import android.service.notification.StatusBarNotification
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fsaint.androidagent.capabilities.notifications.AgentNotificationListenerService
import com.fsaint.androidagent.capabilities.notifications.NotificationEventSink
import com.fsaint.androidagent.capabilities.sms.SmsBroadcastReceiver
import com.fsaint.androidagent.capabilities.sms.SmsEventSink
import com.fsaint.androidagent.model.AgentEvent
import com.fsaint.androidagent.runtime.TelegramMessagingClient
import com.fsaint.androidagent.runtime.TelegramResult
import com.fsaint.androidagent.runtime.TelegramUpdate
import com.fsaint.androidagent.telegram.SharedPreferencesTelegramUpdateCheckpointStore
import com.fsaint.androidagent.telegram.TelegramInboundEventSink
import com.fsaint.androidagent.telegram.TelegramUpdateService
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class LockedFoldedRuntimeAcceptanceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun keepDeviceInteractiveForInstrumentation() {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
    }

    @After
    fun stopRuntime() {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        runCatching {
            runBlocking { (context.applicationContext as DarkLordApplication).stopTelegramUpdates() }
            context.startService(AgentRuntimeService.stopIntent(context))
        }
    }

    @Test
    fun foregroundRuntimeShowsPersistentNotificationWithOperatorControls() {
        grantPostNotificationsIfNeeded()
        val application = context.applicationContext as DarkLordApplication

        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                waitUntil("runtime service did not become foreground") {
                    runtimeServiceDump().contains("isForeground=true")
                }
            }

            val dump = runtimeServiceDump()
            assertTrue(dump, dump.contains("isForeground=true"))
            assertTrue(dump, dump.contains("channel=agent_runtime"))
            assertTrue(dump, dump.contains("foregroundId=7101"))

            val notification = AgentRuntimeNotificationFactory(context).build()
            assertTrue(notification.flags.and(Notification.FLAG_ONGOING_EVENT) != 0)
            assertEquals(listOf("Stop", "Restart"), notification.actions.map { it.title.toString() })
        } finally {
            application.stopBackgroundRuntime()
        }
    }

    @Test
    fun notificationActionsStopAndRestartTheForegroundRuntimeService() {
        grantPostNotificationsIfNeeded()
        val application = context.applicationContext as DarkLordApplication
        val notification = AgentRuntimeNotificationFactory(context).build()

        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                waitUntil("runtime service did not become foreground before action test") {
                    runtimeServiceDump().contains("isForeground=true")
                }
            }

            notification.action("Stop").actionIntent.send()
            waitUntil("Stop action did not remove the foreground runtime service") {
                !runtimeServiceDump().contains("isForeground=true")
            }

            notification.action("Restart").actionIntent.send()
            waitUntil("Restart action did not return the runtime service to foreground") {
                runtimeServiceDump().contains("isForeground=true")
            }
        } finally {
            application.stopBackgroundRuntime()
        }
    }

    @Test
    fun lockedKeyguardAcceptsSmsAndNotificationEventsThroughAndroidEntryPoints() {
        grantPostNotificationsIfNeeded()
        val application = context.applicationContext as DarkLordApplication
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val smsSink = RecordingSmsSink()
        val notificationSink = RecordingNotificationSink()

        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                waitUntil("runtime service did not become foreground before lock test") {
                    runtimeServiceDump().contains("isForeground=true")
                }
                shell("input keyevent KEYCODE_SLEEP")
                waitUntil("device did not enter keyguard lock state", timeoutMillis = 10_000) {
                    keyguard.isKeyguardLocked
                }
                assertTrue("locked entry-path check must execute while keyguard is locked", keyguard.isKeyguardLocked)

                SmsBroadcastReceiver(smsSink).onReceive(context, smsDeliverIntent(PDU_BATTERY))
                AgentNotificationListenerService(notificationSink).apply {
                    onListenerConnected()
                    onNotificationPosted(notification("com.example.owner", "Owner", "Locked check"))
                }
            }
        } finally {
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            application.stopBackgroundRuntime()
        }

        assertEquals(1, smsSink.events.size)
        assertEquals("sms.received", smsSink.events.single().type)
        assertEquals("+14155550100", smsSink.events.single().source)
        assertEquals("battery", smsSink.events.single().payload["body"])
        assertEquals(1, notificationSink.events.size)
        assertEquals("notification.posted", notificationSink.events.single().type)
        assertEquals("com.example.owner", notificationSink.events.single().source)
    }

    @Test
    fun telegramTransportCheckpointRestoresOffsetForRelaunchedPoller() = runBlocking {
        val preferences = context.getSharedPreferences(TELEGRAM_CHECKPOINT_PREFERENCES, Context.MODE_PRIVATE)
        val previousOffset = preferences.takeIf { it.contains(TELEGRAM_OFFSET_KEY) }?.getLong(TELEGRAM_OFFSET_KEY, 0L)
        val checkpoint = SharedPreferencesTelegramUpdateCheckpointStore(context)
        val client = RecordingTelegramClient(TelegramUpdate(40, "42", "resume"))
        val accepted = CopyOnWriteArrayList<AgentEvent>()

        try {
            preferences.edit().clear().commit()
            checkpoint.saveOffset(40)

            TelegramUpdateService(
                client = client,
                scope = CoroutineScope(Dispatchers.Default),
                eventSink = TelegramInboundEventSink { event, _ -> accepted += event },
                checkpointStore = checkpoint,
                pollTimeoutSeconds = 0,
            ).pollOnce()

            assertEquals(listOf(40L), client.offsets)
            assertEquals(listOf("telegram:40"), accepted.map(AgentEvent::id))
            assertEquals(41L, preferences.getLong(TELEGRAM_OFFSET_KEY, -1L))
        } finally {
            if (previousOffset == null) {
                preferences.edit().clear().commit()
            } else {
                preferences.edit().putLong(TELEGRAM_OFFSET_KEY, previousOffset).commit()
            }
        }
    }

    private fun Notification.action(title: String): Notification.Action =
        actions.firstOrNull { it.title.toString() == title }
            ?: error("Notification action '$title' was not present")

    @Test
    fun secondRuntimeStartDoesNotCreateDuplicateReplyWork() = runBlocking {
        val updates = ReplyCountingUpdates()
        val coordinator = AgentRuntimeCoordinator(updates)

        coordinator.start()
        coordinator.start()
        coordinator.stop()

        assertEquals(1, updates.starts)
        assertEquals(1, updates.replies)
    }

    private fun grantPostNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
            .close()
        waitUntil("POST_NOTIFICATIONS was not granted") {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun runtimeServiceDump(): String {
        val serviceName = "${context.packageName}/.AgentRuntimeService"
        return shell("dumpsys activity services $serviceName")
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
    }

    private fun waitUntil(message: String, timeoutMillis: Long = 5_000, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            SystemClock.sleep(50)
        }
        assertTrue(message, predicate())
    }

    private fun smsDeliverIntent(vararg pdus: ByteArray): Intent = Intent(Telephony.Sms.Intents.SMS_DELIVER_ACTION).apply {
        putExtras(Bundle().apply {
            putSerializable("pdus", pdus)
            putString("format", "3gpp")
            putInt(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, 7)
        })
    }

    @Suppress("DEPRECATION")
    private fun notification(packageName: String, title: String, body: String): StatusBarNotification =
        StatusBarNotification(
            packageName,
            packageName,
            7,
            "locked-folded",
            1000,
            1000,
            0,
            Notification.Builder(context)
                .setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build(),
            android.os.Process.myUserHandle(),
            1234L,
        )
}

private class RecordingSmsSink : SmsEventSink {
    val events = CopyOnWriteArrayList<AgentEvent>()

    override fun publish(event: AgentEvent) {
        events += event
    }
}

private class RecordingNotificationSink : NotificationEventSink {
    val events = CopyOnWriteArrayList<AgentEvent>()

    override fun publish(event: AgentEvent) {
        events += event
    }
}

private class RecordingTelegramClient(private val update: TelegramUpdate) : TelegramMessagingClient {
    val offsets = CopyOnWriteArrayList<Long?>()

    override suspend fun getUpdates(offset: Long?, timeoutSeconds: Int): List<TelegramUpdate> {
        offsets += offset
        return if (offset == update.updateId) listOf(update) else emptyList()
    }

    override suspend fun sendMessage(chatId: String, text: String): TelegramResult = TelegramResult.Success()
}

private class ReplyCountingUpdates : TelegramUpdatesLifecyclePort {
    var starts = 0
    var replies = 0
    private var activeJob: CompletableJob? = null

    override fun start(): Job {
        starts += 1
        replies += 1
        return Job().also { activeJob = it as CompletableJob }
    }

    override suspend fun stop() {
        activeJob?.complete()
    }
}

private const val TELEGRAM_CHECKPOINT_PREFERENCES = "telegram_update_checkpoint"
private const val TELEGRAM_OFFSET_KEY = "next_offset"

private val PDU_BATTERY = byteArrayOf(
    0x00, 0x00, 0x0B, 0x91.toByte(), 0x41, 0x51, 0x55, 0x05, 0x01, 0xF0.toByte(), 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x07, 0xE2.toByte(), 0x30, 0x9D.toByte(), 0x5E,
    0x96.toByte(), 0xE7.toByte(), 0x01,
)
