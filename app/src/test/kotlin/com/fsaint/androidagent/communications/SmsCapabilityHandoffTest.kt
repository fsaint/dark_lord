package com.fsaint.androidagent.communications

import android.content.ContextWrapper
import com.fsaint.androidagent.capabilities.sms.SmsCapability
import com.fsaint.androidagent.model.AgentEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SmsCapabilityHandoffTest {
    @Test
    fun eventPublishedBeforeCollectionIsRetained() = runTest {
        val capability = SmsCapability(ContextWrapper(null))
        val event = AgentEvent("early-sms", "sms.received", "+14155550100", 1)

        capability.publish(event)

        assertEquals(event, withTimeoutOrNull(100) { capability.events().first() })
    }
}
