import Foundation
import CoreBluetooth
import Combine

/// BLE central: scans for the phone, connects, discovers services.
final class CentralManager: NSObject, ObservableObject, CBCentralManagerDelegate, CBPeripheralDelegate {

    enum State {
        case idle
        case scanning
        case connecting
        case connected
        case error(String)
    }

    @Published private(set) var state: State = .idle
    @Published private(set) var connectedPeripheral: CBPeripheral?

    private var manager: CBCentralManager!
    private var characteristicWrites: [CBUUID: CBCharacteristic] = [:]
    private let onMessage: (Data) -> Void
    private var discoveredServices = false

    init(onMessage: @escaping (Data) -> Void) {
        self.onMessage = onMessage
        super.init()
        manager = CBCentralManager(delegate: self, queue: nil)
    }

    func startScanning() {
        guard manager.state == .poweredOn else { return }
        state = .scanning
        let services: [CBUUID] = [CBUUID(nsuuid: BridgeUuids.serviceMain), CBUUID(nsuuid: BridgeUuids.servicePairing)]
        manager.scanForPeripherals(withServices: services, options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
    }

    func stopScanning() {
        manager.stopScan()
    }

    func disconnect() {
        if let p = connectedPeripheral {
            manager.cancelPeripheralConnection(p)
        }
        state = .idle
    }

    // MARK: - CentralManagerDelegate

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            if case .scanning = state { startScanning() }
        case .poweredOff:
            state = .error("Bluetooth выключен")
        case .unauthorized:
            state = .error("Нет доступа к Bluetooth")
        default:
            break
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        // Connect to the first WatchBridge peripheral.
        stopScanning()
        connectedPeripheral = peripheral
        peripheral.delegate = self
        manager.connect(peripheral, options: nil)
        state = .connecting
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        state = .connected
        peripheral.discoverServices([CBUUID(nsuuid: BridgeUuids.serviceMain), CBUUID(nsuuid: BridgeUuids.servicePairing)])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        state = .error("Не удалось подключиться: \(error?.localizedDescription ?? "unknown")")
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        connectedPeripheral = nil
        characteristicWrites = [:]
        discoveredServices = false
        state = .idle
    }

    // MARK: - PeripheralDelegate

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil else {
            state = .error(error!.localizedDescription)
            return
        }
        for service in peripheral.services ?? [] {
            let chars: [CBUUID]
            if service.uuid == CBUUID(nsuuid: BridgeUuids.serviceMain) {
                chars = [
                    CBUUID(nsuuid: BridgeUuids.charStatus),
                    CBUUID(nsuuid: BridgeUuids.charNotification),
                    CBUUID(nsuuid: BridgeUuids.charHealth),
                    CBUUID(nsuuid: BridgeUuids.charPairing),
                    CBUUID(nsuuid: BridgeUuids.charCommand)
                ]
            } else {
                chars = [
                    CBUUID(nsuuid: BridgeUuids.charPairingRequest),
                    CBUUID(nsuuid: BridgeUuids.charPairingResponse)
                ]
            }
            peripheral.discoverCharacteristics(chars, for: service)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard error == nil else { return }
        for characteristic in service.characteristics ?? [] {
            characteristicWrites[characteristic.uuid] = characteristic

            // Enable notifications on notifiable characteristics.
            if characteristic.properties.contains(.notify) || characteristic.properties.contains(.indicate) {
                peripheral.setNotifyValue(true, for: characteristic)
            }
        }
    }

    // MARK: - Write / notify

    func write(_ data: Data, to characteristicUUID: UUID) {
        guard let peripheral = connectedPeripheral,
              let characteristic = characteristicWrites[CBUUID(nsuuid: characteristicUUID)] else {
            return
        }
        peripheral.writeValue(data, for: characteristic, type: .withResponse)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard let value = characteristic.value else { return }
        onMessage(value)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        // ready to receive
    }
}
