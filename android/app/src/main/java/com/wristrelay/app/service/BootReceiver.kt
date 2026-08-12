package com.wristrelay.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "boot completed, starting service")
            BridgeService.start(context)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
