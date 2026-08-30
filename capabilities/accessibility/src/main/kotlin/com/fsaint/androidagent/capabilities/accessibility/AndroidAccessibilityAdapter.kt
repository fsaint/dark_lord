package com.fsaint.androidagent.capabilities.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.view.accessibility.AccessibilityManager

class AndroidAccessibilityAdapter(context: Context) : AccessibilityAdapter {
    private val applicationContext = context.applicationContext

    override fun status(): AccessibilityServiceState {
        val enabled = isServiceEnabled()
        return AccessibilityServiceState(
            enabled = enabled,
            connected = enabled && AccessibilityServiceBridge.connected(),
        )
    }

    override suspend fun inspect(target: AccessibilityTarget): AccessibilityInspectOutcome {
        if (!isServiceEnabled()) return AccessibilityInspectOutcome.PermissionRequired
        val service = AccessibilityServiceBridge.service() ?: return AccessibilityInspectOutcome.ServiceUnavailable
        return service.inspectTarget(target)
    }

    override suspend fun perform(request: AccessibilityActionRequest): AccessibilityActionOutcome {
        if (!isServiceEnabled()) return AccessibilityActionOutcome.PermissionRequired
        val service = AccessibilityServiceBridge.service() ?: return AccessibilityActionOutcome.ServiceUnavailable
        return service.performTarget(request)
    }

    private fun isServiceEnabled(): Boolean {
        val component = ComponentName(applicationContext, AgentAccessibilityService::class.java)
        val manager = applicationContext.getSystemService(AccessibilityManager::class.java)
        return manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                val serviceInfo = info.resolveInfo.serviceInfo
                ComponentName(serviceInfo.packageName, serviceInfo.name) == component
            }
    }
}
