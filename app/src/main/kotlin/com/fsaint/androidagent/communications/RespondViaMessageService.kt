package com.fsaint.androidagent.communications

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

/** Qualifies the SMS role without sending or granting any message permission itself. */
class RespondViaMessageService : Service() {
    override fun onBind(intent: Intent): IBinder = Binder()
}
