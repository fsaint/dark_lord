package com.fsaint.androidagent.communications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Declared separately because Android assigns WAP-push delivery a different broadcast permission. */
class WapPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
