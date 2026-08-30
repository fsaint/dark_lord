package com.fsaint.androidagent

import android.app.admin.DeviceAdminReceiver

/** Receives explicit administrator lifecycle events; provisioning is performed by Android Settings/ADB. */
class AgentDeviceAdminReceiver : DeviceAdminReceiver()
