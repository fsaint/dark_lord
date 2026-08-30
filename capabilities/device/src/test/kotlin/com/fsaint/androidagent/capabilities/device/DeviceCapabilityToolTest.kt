package com.fsaint.androidagent.capabilities.device

import android.content.ContextWrapper
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse

class DeviceCapabilityToolTest {
    @Test
    fun deviceCapabilityDoesNotExposeSmsReply() {
        val tools = DeviceCapability(ContextWrapper(null)).tools().map { it.id }

        assertFalse("sms.reply" in tools)
    }
}
