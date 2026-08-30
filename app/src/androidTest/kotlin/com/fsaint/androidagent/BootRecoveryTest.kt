package com.fsaint.androidagent

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull

@RunWith(AndroidJUnit4::class)
class BootRecoveryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun bootReceiverAndDeviceAdminAreExplicitlyRegistered() {
        val packageManager = context.packageManager
        val receiver = packageManager.getReceiverInfo(ComponentName(context, BootReceiver::class.java), 0)
        assertEquals(true, receiver.exported)
        assertNotNull(packageManager.getReceiverInfo(ComponentName(context, AgentDeviceAdminReceiver::class.java), 0))
    }

    @Test
    fun unrelatedBroadcastDoesNotCreateRestoreWork() {
        BootReceiver().onReceive(context, Intent(Intent.ACTION_TIME_CHANGED))
        assertEquals("dark-lord-runtime-restore", RuntimeRestoreWorker.UNIQUE_WORK_NAME)
    }
}
