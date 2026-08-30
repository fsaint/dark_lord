package com.fsaint.androidagent.capabilities.environment

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidEnvironmentAdapterConnectedTest {
    @Test fun reportsEnvironmentStatusWithoutCrashing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = EnvironmentCapability(AndroidEnvironmentAdapter(context)).status()
        assertTrue(result.details.containsKey("supported"))
        assertTrue(result.details.containsKey("permission"))
    }
}
