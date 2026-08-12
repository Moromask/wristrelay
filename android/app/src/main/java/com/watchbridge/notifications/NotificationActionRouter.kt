package com.watchbridge.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.os.Bundle
import android.util.Log

/**
 * Выполняет действие уведомления (ответ текстом или открытие приложения),
 * которое пришло с часов. Ищет активное уведомление по ключу и отправляет
 * его action PendingIntent с заполненным RemoteInput.
 */
object NotificationActionRouter {

    private const val TAG = "NotificationActionRouter"

    /**
     * @param context контекст для PendingIntent.send
     * @param packageName пакет-источник
     * @param sbnKey системный ключ уведомления (StatusBarNotification.key)
     * @param actionIndex индекс действия в уведомлении
     * @param replyText текст ответа (или null)
     * @return true если действие найдено и отправлено
     */
    fun dispatch(
        context: Context,
        packageName: String,
        sbnKey: String,
        actionIndex: Int,
        replyText: String?
    ): Boolean {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val active = manager.activeNotifications
        val target = active.firstOrNull { it.key == sbnKey || it.packageName == packageName }
            ?: active.firstOrNull { it.key == sbnKey }

        val sbn = target ?: run {
            Log.w(TAG, "уведомление не найдено: $sbnKey")
            return false
        }

        val actions = sbn.notification.actions ?: run {
            Log.w(TAG, "у уведомления нет действий")
            return false
        }
        if (actionIndex < 0 || actionIndex >= actions.size) {
            Log.w(TAG, "нет действия с индексом $actionIndex")
            return false
        }

        val action = actions[actionIndex]
        val intent = action.actionIntent ?: run {
            Log.w(TAG, "у действия нет actionIntent")
            return false
        }

        return try {
            if (replyText != null && action.remoteInputs?.isNotEmpty() == true) {
                val filled = Bundle()
                val remoteInputs = action.remoteInputs
                for (ri in remoteInputs) {
                    if (ri.getAllowFreeFormInput()) {
                        filled.putCharSequence(ri.resultKey, replyText)
                    }
                }
                val sendIntent = android.content.Intent()
                android.app.RemoteInput.addResultsToIntent(remoteInputs, sendIntent, filled)
                intent.send(context, 0, sendIntent)
            } else {
                intent.send()
            }
            Log.d(TAG, "действие отправлено: $packageName / $actionIndex")
            true
        } catch (e: PendingIntent.CanceledException) {
            Log.w(TAG, "PendingIntent отменён: ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "не удалось выполнить действие: ${e.message}")
            false
        }
    }
}
