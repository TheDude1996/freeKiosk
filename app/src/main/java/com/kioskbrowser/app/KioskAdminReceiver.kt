package com.kioskbrowser.app

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class KioskAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        EventHub.emit("deviceAdminEnabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        EventHub.emit("deviceAdminDisabled")
    }
}
