package com.watchbridge.notifications

import android.app.Notification
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.text.TextUtils

/**
 * Safe notification content extraction.
 * Uses only public NotificationCompat extras API (no reflection).
 */
object ContentExtractor {

    data class Content(
        val title: String,
        val text: String,
        val subText: String,
        val category: String?,
        val isOngoing: Boolean,
        val whenMs: Long,
        val postedAtMs: Long
    )

    fun extract(sbn: StatusBarNotification, appName: String): Content? {
        val n = sbn.notification ?: return null
        val extras = n.extras ?: Bundle()

        val title = firstNonEmpty(
            extras.getCharSequence(Notification.EXTRA_TITLE),
            extras.getCharSequence(Notification.EXTRA_TITLE_BIG)
        )
        val text = firstNonEmpty(
            extras.getCharSequence(Notification.EXTRA_TEXT),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
            extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)
        )
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

        if (TextUtils.isEmpty(title) && TextUtils.isEmpty(text) && TextUtils.isEmpty(subText)) {
            return null
        }

        val eventTime = notificationTime(n, sbn.postTime)
        return Content(
            title = title ?: "",
            text = text ?: "",
            subText = subText ?: "",
            category = n.category,
            isOngoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0,
            whenMs = eventTime,
            postedAtMs = sbn.postTime
        )
    }

    fun extractActions(n: Notification): List<WatchAction> {
        val actions = n.actions ?: return emptyList()
        return actions.mapIndexedNotNull { index, action ->
            if (action.title == null) return@mapIndexedNotNull null
            WatchAction(
                id = "$index",
                title = action.title.toString(),
                hasReply = hasRemoteInput(action),
                isInlineReply = hasInlineReply(action),
                opensApp = action.actionIntent != null
            )
        }
    }

    private fun notificationTime(n: Notification, fallback: Long): Long {
        val t = n.`when`
        return if (t > 0) t else fallback
    }

    private fun hasRemoteInput(action: Notification.Action): Boolean =
        action.remoteInputs != null && action.remoteInputs.isNotEmpty()

    private fun hasInlineReply(action: Notification.Action): Boolean =
        action.remoteInputs?.any { it.getAllowFreeFormInput() } == true

    private fun firstNonEmpty(vararg values: CharSequence?): String? {
        for (v in values) {
            if (!TextUtils.isEmpty(v)) return v.toString()
        }
        return null
    }
}

data class WatchAction(
    val id: String,
    val title: String,
    val hasReply: Boolean,
    val isInlineReply: Boolean,
    val opensApp: Boolean
)
