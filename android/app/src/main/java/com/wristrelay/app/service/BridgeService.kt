package com.wristrelay.app.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.wristrelay.app.MainActivity
import com.wristrelay.app.R
import com.wristrelay.app.ble.BridgeUuids
import com.wristrelay.app.ble.GattServer
import com.wristrelay.app.ble.protocol.EnvelopeCodec
import com.wristrelay.app.ble.protocol.MessageAssembler
import com.wristrelay.app.health.HealthSync
import com.wristrelay.app.notifications.NotificationActionRouter
import com.wristrelay.app.notifications.Outgoing
import com.wristrelay.app.pairing.PairingManager
import com.wristrelay.app.proto.Bridge.HealthSample
import com.wristrelay.app.proto.Bridge.PairingMessage
import com.wristrelay.app.proto.Bridge.Envelope
import com.wristrelay.app.proto.Bridge.MessageType
import com.wristrelay.app.proto.Bridge.NotificationAction
import com.wristrelay.app.storage.SecureStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the BLE stack alive and routes messages.
 */
@SuppressLint("MissingPermission")
class BridgeService : Service(), GattServer.Listener, MessageAssembler.Listener, PairingManager.Callback {

    private lateinit var gattServer: GattServer
    private lateinit var pairingManager: PairingManager
    private lateinit var healthSync: HealthSync
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createChannels()
        gattServer = GattServer(this, this)
        gattServer.setMessageListener(this)
        pairingManager = PairingManager(SecureStorage(this))
        pairingManager.setCallback(this)
        healthSync = HealthSync(this)
        startForegroundCompat()
        gattServer.start()

        Outgoing.setForwarder { bytes ->
            gattServer.notifyAll(BridgeUuids.CHAR_NOTIFICATION, bytes)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            gattServer.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Outgoing.clearForwarder()
        gattServer.stop()
        super.onDestroy()
    }

    // --- GattServer.Listener ---

    override fun onDeviceConnected(device: BluetoothDevice) {
        Log.d(TAG, "device connected: " + (device.name ?: device.address))
    }

    override fun onDeviceDisconnected(device: BluetoothDevice) {
        Log.d(TAG, "device disconnected: " + device.address)
    }

    override fun onServerError(message: String) {
        Log.w(TAG, message)
    }

    // --- MessageAssembler.Listener ---

    override fun onEnvelope(device: BluetoothDevice, characteristicUuid: String, envelope: Envelope) {
        Log.d(TAG, "envelope: seq=" + envelope.sequence + " type=" + envelope.type)
        when (envelope.type) {
            MessageType.PAIRING -> handlePairing(device, envelope)
            MessageType.NOTIFICATION_ACTION -> handleNotificationAction(envelope)
            MessageType.HEALTH_SAMPLE -> handleHealthSample(envelope)
            MessageType.PONG -> Log.d(TAG, "pong")
            MessageType.PING -> sendPong()
            else -> Log.d(TAG, "unknown type: " + envelope.type)
        }
    }

    private fun handlePairing(device: BluetoothDevice, envelope: Envelope) {
        val msg = try {
            PairingMessage.parseFrom(envelope.payload)
        } catch (e: Exception) {
            Log.w(TAG, "bad pairing message: " + e.message)
            return
        }
        val reply = pairingManager.handleWatchMessage(msg) ?: return
        val bytes = EnvelopeCodec.wrap(
            MessageType.PAIRING, reply, System.currentTimeMillis()
        )
        gattServer.notifyTo(device, BridgeUuids.CHAR_PAIRING, bytes)
    }

    private fun handleNotificationAction(envelope: Envelope) {
        val action = try {
            NotificationAction.parseFrom(envelope.payload)
        } catch (e: Exception) {
            Log.w(TAG, "bad action: " + e.message)
            return
        }
        Log.d(TAG, "action: key=" + action.notificationKey + " id=" + action.actionId)

        // Наш ключ: "package:sbnKey". Разделяем для поиска активного уведомления.
        val idx = action.notificationKey.indexOf(':')
        if (idx <= 0) {
            Log.w(TAG, "неверный формат ключа: " + action.notificationKey)
            return
        }
        val packageName = action.notificationKey.substring(0, idx)
        val sbnKey = action.notificationKey.substring(idx + 1)
        val actionIndex = action.actionId.toIntOrNull() ?: -1

        val handled = NotificationActionRouter.dispatch(
            this, packageName, sbnKey, actionIndex,
            if (action.replyText.isEmpty()) null else action.replyText
        )
        if (!handled) {
            Log.w(TAG, "действие не выполнено")
        }
    }

    private fun handleHealthSample(envelope: Envelope) {
        val sample = try {
            HealthSample.parseFrom(envelope.payload)
        } catch (e: Exception) {
            Log.w(TAG, "bad health sample: " + e.message)
            return
        }
        Log.d(TAG, "health sample: " + sample.metric + " value=" + sample.value)
        if (sample.metric.name == "HEART_RATE") {
            scope.launch {
                healthSync.writeHeartRate(
                    listOf(
                        HealthSync.HeartRateSample(
                            java.time.Instant.ofEpochMilli(sample.timeMs),
                            sample.value
                        )
                    )
                )
            }
        }
    }

    private fun sendPong() {
        val bytes = EnvelopeCodec.wrap(MessageType.PONG, ByteArray(0), System.currentTimeMillis())
        gattServer.notifyAll(BridgeUuids.CHAR_PAIRING, bytes)
    }

    // --- PairingManager.Callback ---

    override fun onPinRequired(pin: String) {
        Log.d(TAG, "PIN для часов: $pin")
        // TODO: показать PIN в UI (event в MainActivity)
    }

    override fun onPaired(sessionKey: ByteArray) {
        Log.d(TAG, "устройство спарено")
    }

    override fun onPairingFailed(reason: String) {
        Log.w(TAG, "pairing failed: $reason")
    }

    // --- Foreground ---

    private fun startForegroundCompat() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_active))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.service_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "BridgeService"
        private const val CHANNEL_ID = "wristrelay_service"
        private const val NOTIFICATION_ID = 9417
        const val ACTION_STOP = "com.wristrelay.app.action.STOP"

        fun start(context: Context) {
            val intent = Intent(context, BridgeService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, BridgeService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
