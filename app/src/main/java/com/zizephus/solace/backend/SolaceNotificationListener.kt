package com.zizephus.solace.backend

import android.service.notification.NotificationListenerService

/** Notification access shared by DSH's notification and media capabilities. */
class SolaceNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: SolaceNotificationListener? = null
            private set
    }
}
