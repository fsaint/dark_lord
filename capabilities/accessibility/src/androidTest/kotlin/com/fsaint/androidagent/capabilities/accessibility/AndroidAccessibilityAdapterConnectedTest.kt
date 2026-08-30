package com.fsaint.androidagent.capabilities.accessibility

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidAccessibilityAdapterConnectedTest {
    @Test
    fun serviceIsDeclaredWithAndroidBindingPermissionAndConfiguration() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val component = ComponentName(context, AgentAccessibilityService::class.java)

        val info = context.packageManager.getServiceInfo(component, PackageManager.GET_META_DATA)

        assertEquals(Manifest.permission.BIND_ACCESSIBILITY_SERVICE, info.permission)
        assertFalse(info.exported)
        assertNotNull(info.metaData)
        assertTrue(info.metaData.containsKey("android.accessibilityservice"))
    }

    @Test
    fun statusMatchesAndroidEnabledServiceListWithoutChangingTheGrant() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(AccessibilityManager::class.java)
        val component = ComponentName(context, AgentAccessibilityService::class.java)
        val platformEnabled = manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                val serviceInfo = info.resolveInfo.serviceInfo
                ComponentName(serviceInfo.packageName, serviceInfo.name) == component
            }

        val state = AndroidAccessibilityAdapter(context).status()

        assertEquals(platformEnabled, state.enabled)
        if (!platformEnabled) {
            assertFalse(state.connected)
        }
    }

    @Test
    fun disabledServiceReturnsPermissionRequiredWithoutSelfEnabling() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val adapter = AndroidAccessibilityAdapter(context)
        val before = adapter.status()
        if (before.enabled) return@runBlocking

        val result = AccessibilityCapability(adapter).inspect(
            AccessibilityTarget(
                packageName = context.packageName,
                viewId = "${context.packageName}:id/nonexistent",
            ),
        )

        assertFalse(result.success)
        assertEquals(com.fsaint.androidagent.model.ToolError.PERMISSION_REQUIRED, result.error)
        assertEquals(before, adapter.status())
    }
}
