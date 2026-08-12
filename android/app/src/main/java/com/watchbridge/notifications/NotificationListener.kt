package com.watchbridge.notifications

import android.app.NotificationManager
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.watchbridge.proto.Bridge.Action
import com.watchbridge.proto.Bridge.Notification

/**
 * Слушает уведомления Android и отправляет их на часы через BridgeService.
 */
class NotificationListener : NotificationListenerService() {

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "created")
    }

    override fun onDestroy() {
        Log.d(TAG, "destroyed")
        super.onDestroy()
    }

    override fun onListenerConnected() {
        Log.d(TAG, "listener connected")
    }

    override fun onListenerDisconnected() {
        Log.d(TAG, "listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return

        val appName = safeAppName(sbn.packageName)
        val content = ContentExtractor.extract(sbn, appName) ?: return

        val actions = sbn.notification.actions?.mapIndexedNotNull { index, a ->
            Action.newBuilder()
                .setId(index.toString())
                .setTitle(a.title?.toString() ?: "")
                .setHasReply(a.remoteInputs?.isNotEmpty() == true)
                .setIsInlineReply(a.remoteInputs?.any { it.getAllowFreeFormInput() } == true)
                .setOpensApp(a.actionIntent != null)
                .build()
        } ?: emptyList()

        val proto = Notification.newBuilder()
            .setPackageName(sbn.packageName)
            .setKey(notificationKey(sbn))
            .setTitle(content.title)
            .setText(content.text)
            .setAppName(appName)
            .setPostedAtMs(content.postedAtMs)
            .setWhenMs(content.whenMs)
            .setCategory(content.category ?: "")
            .setOngoing(content.isOngoing)
            .addAllActions(actions)
            .build()

        Outgoing.sendNotification(proto)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap?, reason: Int) {
        Outgoing.sendRemoved(notificationKey(sbn), reason)
    }

    private fun notificationKey(sbn: StatusBarNotification): String =
        sbn.packageName + ":" + sbn.key

    @Suppress("DEPRECATION")
    private fun safeAppName(pkg: String): String {
        return try {
            val pm = applicationContext.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    companion object {
        private const val TAG = "NotificationListener"

        fun isEnabled(context: Context): Boolean {
            val flat = android.provider.Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            )
            return flat?.contains("com.watchbridge/.notifications.NotificationListener") == true
        }
    }
}
