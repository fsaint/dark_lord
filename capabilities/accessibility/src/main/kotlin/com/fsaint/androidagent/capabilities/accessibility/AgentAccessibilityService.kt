package com.fsaint.androidagent.capabilities.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

class AgentAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityServiceBridge.connect(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        AccessibilityServiceBridge.disconnect(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        AccessibilityServiceBridge.disconnect(this)
        super.onDestroy()
    }

    internal fun inspectTarget(target: AccessibilityTarget): AccessibilityInspectOutcome = withMatchingNodes(target) { matches ->
        when (matches.size) {
            0 -> AccessibilityInspectOutcome.NotFound
            1 -> AccessibilityInspectOutcome.Success(matches.single().snapshot())
            else -> AccessibilityInspectOutcome.Ambiguous
        }
    } ?: AccessibilityInspectOutcome.ServiceUnavailable

    internal fun performTarget(request: AccessibilityActionRequest): AccessibilityActionOutcome =
        withMatchingNodes(request.target) { matches ->
            when (matches.size) {
                0 -> AccessibilityActionOutcome.NotFound
                1 -> if (matches.single().performExplicit(request)) {
                    AccessibilityActionOutcome.Performed
                } else {
                    AccessibilityActionOutcome.Rejected
                }
                else -> AccessibilityActionOutcome.Ambiguous
            }
        } ?: AccessibilityActionOutcome.ServiceUnavailable

    @Suppress("DEPRECATION")
    private fun <T> withMatchingNodes(target: AccessibilityTarget, block: (List<AccessibilityNodeInfo>) -> T): T? {
        val root = rootInActiveWindow ?: return null
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        try {
            while (pending.isNotEmpty()) {
                val node = pending.removeFirst()
                nodes += node
                for (index in 0 until node.childCount) {
                    node.getChild(index)?.let(pending::addLast)
                }
            }
            return block(nodes.filter { node -> node.matches(target) })
        } finally {
            nodes.asReversed().forEach(AccessibilityNodeInfo::recycle)
            while (pending.isNotEmpty()) {
                pending.removeFirst().recycle()
            }
        }
    }
}

internal object AccessibilityServiceBridge {
    @Volatile
    private var current: AgentAccessibilityService? = null

    fun connect(service: AgentAccessibilityService) {
        current = service
    }

    fun disconnect(service: AgentAccessibilityService) {
        if (current === service) current = null
    }

    fun connected(): Boolean = current != null

    fun service(): AgentAccessibilityService? = current
}

private fun AccessibilityNodeInfo.matches(target: AccessibilityTarget): Boolean =
    packageName?.toString() == target.packageName &&
        (target.viewId.isNullOrBlank() || viewIdResourceName == target.viewId) &&
        (target.text.isNullOrBlank() || text?.toString() == target.text) &&
        (target.contentDescription.isNullOrBlank() || contentDescription?.toString() == target.contentDescription)

private fun AccessibilityNodeInfo.snapshot() = AccessibilityNodeSnapshot(
    packageName = packageName?.toString().orEmpty(),
    className = className?.toString().orEmpty(),
    viewId = viewIdResourceName,
    text = text?.toString(),
    contentDescription = contentDescription?.toString(),
    clickable = isClickable,
    enabled = isEnabled,
)

private fun AccessibilityNodeInfo.performExplicit(request: AccessibilityActionRequest): Boolean = when (request.action) {
    AccessibilityAction.CLICK -> isEnabled && isClickable && performAction(AccessibilityNodeInfo.ACTION_CLICK)
    AccessibilityAction.FOCUS -> isEnabled && isFocusable && performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    AccessibilityAction.SET_TEXT -> {
        val value = request.value ?: return false
        isEnabled && isEditable && performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            },
        )
    }
}
