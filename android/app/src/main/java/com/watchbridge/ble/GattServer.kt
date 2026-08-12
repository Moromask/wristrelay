package com.watchbridge.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.watchbridge.ble.protocol.Fragmentation
import com.watchbridge.ble.protocol.MessageAssembler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * GATT server + BLE advertising. Phone acts as a peripheral for Apple Watch.
 * All notify calls are serialized through a Channel (Android GATT server does
 * not tolerate concurrent notify invocations).
 */
@SuppressLint("MissingPermission")
class GattServer(
    private val context: Context,
    private val listener: Listener
) : BluetoothGattServerCallback() {

    interface Listener {
        fun onDeviceConnected(device: BluetoothDevice)
        fun onDeviceDisconnected(device: BluetoothDevice)
        fun onServerError(message: String)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notifyQueue = Channel<NotifyTask>(capacity = Channel.BUFFERED)

    private var bluetoothManager: BluetoothManager? = null
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertising = false
    private val connectedDevices = mutableSetOf<String>()

    private val assembler = MessageAssembler()

    fun setMessageListener(l: MessageAssembler.Listener) {
        assembler.setListener(l)
    }

    private data class NotifyTask(
        val device: BluetoothDevice?,
        val characteristic: BluetoothGattCharacteristic,
        val data: ByteArray
    )

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            advertising = true
            Log.d(TAG, "advertising started")
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            listener.onServerError("advertising failed: $errorCode")
        }
    }

    fun start() {
        scope.launch {
            for (task in notifyQueue) {
                try {
                    if (task.device != null) {
                        server?.notifyCharacteristicChanged(task.device, task.characteristic, false, task.data)
                    } else {
                        val devices = synchronized(connectedDevices) { connectedDevices.toList() }
                        devices.forEach { address ->
                            val device = bluetoothManager?.adapter?.getRemoteDevice(address)
                            if (device != null) {
                                server?.notifyCharacteristicChanged(device, task.characteristic, false, task.data)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "notify error: " + e.message)
                }
            }
        }
        openServer()
    }

    private fun openServer() {
        bluetoothManager = context.getSystemService(BluetoothManager::class.java)
        server = bluetoothManager?.openGattServer(context, this)
        if (server == null) {
            listener.onServerError("GATT server creation failed")
            return
        }
        server?.let {
            val mainService = BluetoothGattService(BridgeUuids.SERVICE_MAIN, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            addEncryptedCharacteristic(
                mainService, BridgeUuids.CHAR_STATUS,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY
            )
            addEncryptedCharacteristic(
                mainService, BridgeUuids.CHAR_COMMAND,
                BluetoothGattCharacteristic.PROPERTY_WRITE
            )
            addEncryptedCharacteristic(
                mainService, BridgeUuids.CHAR_NOTIFICATION,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY
            )
            addEncryptedCharacteristic(
                mainService, BridgeUuids.CHAR_HEALTH,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY
            )
            addEncryptedCharacteristic(
                mainService, BridgeUuids.CHAR_PAIRING,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY
            )

            val pairingService = BluetoothGattService(BridgeUuids.SERVICE_PAIRING, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            addEncryptedCharacteristic(
                pairingService, BridgeUuids.CHAR_PAIRING_REQUEST,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY
            )
            addEncryptedCharacteristic(
                pairingService, BridgeUuids.CHAR_PAIRING_RESPONSE,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY
            )

            it.addService(mainService)
            it.addService(pairingService)
        }
        startAdvertising()
    }

    private fun addEncryptedCharacteristic(service: BluetoothGattService, uuid: UUID, properties: Int) {
        val permissions = BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED_MITM or
            BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED_MITM
        val characteristic = BluetoothGattCharacteristic(uuid, properties, permissions)
        val isNotifiable = (properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
            BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0
        if (isNotifiable) {
            characteristic.addDescriptor(
                BluetoothGattDescriptor(
                    CCC_DESCRIPTOR_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                )
            )
        }
        service.addCharacteristic(characteristic)
    }

    fun startAdvertising() {
        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            listener.onServerError("adapter unavailable")
            return
        }
        val adv = adapter.bluetoothLeAdvertiser
        if (adv == null) {
            listener.onServerError("advertiser unavailable")
            return
        }
        advertiser = adv
        if (advertising) return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(BridgeUuids.SERVICE_MAIN))
            .build()

        adv.startAdvertising(settings, data, advertiseCallback)
    }

    fun stopAdvertising() {
        advertiser?.stopAdvertising(advertiseCallback)
        advertising = false
    }

    fun stop() {
        stopAdvertising()
        server?.close()
        server = null
        synchronized(connectedDevices) { connectedDevices.clear() }
    }

    fun notifyAll(characteristic: BluetoothGattCharacteristic, message: ByteArray) {
        notifyTo(null, characteristic, message)
    }

    fun notifyAll(characteristicUuid: UUID, message: ByteArray) {
        val characteristic = findCharacteristic(characteristicUuid) ?: return
        notifyAll(characteristic, message)
    }

    private fun findCharacteristic(uuid: UUID): BluetoothGattCharacteristic? {
        val srv = server ?: return null
        return srv.getService(BridgeUuids.SERVICE_MAIN)?.getCharacteristic(uuid)
            ?: srv.getService(BridgeUuids.SERVICE_PAIRING)?.getCharacteristic(uuid)
    }

    fun notifyTo(device: BluetoothDevice?, characteristic: BluetoothGattCharacteristic, message: ByteArray) {
        val sequence = sequenceCounter.getAndIncrement()
        val fragments = Fragmentation.split(sequence, message)
        for (fragment in fragments) {
            notifyQueue.trySend(NotifyTask(device, characteristic, fragment))
        }
    }

    fun notifyTo(device: BluetoothDevice?, characteristicUuid: UUID, message: ByteArray) {
        val characteristic = findCharacteristic(characteristicUuid) ?: return
        notifyTo(device, characteristic, message)
    }

    // --- GATT callbacks ---

    @SuppressLint("MissingPermission")
    override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                synchronized(connectedDevices) { connectedDevices.add(device.address) }
                listener.onDeviceConnected(device)
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                synchronized(connectedDevices) { connectedDevices.remove(device.address) }
                listener.onDeviceDisconnected(device)
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onCharacteristicReadRequest(
        device: BluetoothDevice,
        requestId: Int,
        offset: Int,
        characteristic: BluetoothGattCharacteristic
    ) {
        val value = characteristic.value ?: ByteArray(0)
        if (offset > value.size) {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
            return
        }
        val result = value.copyOfRange(offset, value.size)
        server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, result)
    }

    @SuppressLint("MissingPermission")
    override fun onCharacteristicWriteRequest(
        device: BluetoothDevice,
        requestId: Int,
        characteristic: BluetoothGattCharacteristic,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray
    ) {
        if (preparedWrite || offset != 0) {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
            return
        }
        characteristic.value = value
        if (responseNeeded) {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
        }
        // Reassemble fragments and dispatch the Envelope.
        assembler.handle(device, characteristic.uuid.toString(), value)
    }

    @SuppressLint("MissingPermission")
    override fun onDescriptorWriteRequest(
        device: BluetoothDevice,
        requestId: Int,
        descriptor: BluetoothGattDescriptor,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray
    ) {
        if (preparedWrite || offset != 0) {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
            return
        }
        descriptor.value = value
        if (responseNeeded) {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
        }
    }

    @SuppressLint("MissingPermission")
    override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
        Log.d(TAG, "service added: $status " + service?.uuid)
    }

    companion object {
        private const val TAG = "GattServer"
        private val CCC_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val sequenceCounter = AtomicLong(1)
    }
}
