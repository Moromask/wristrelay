import Foundation
import CoreBluetooth

/// Fixed UUIDs shared with the Android app.
/// Must match com.watchbridge.ble.BridgeUuids and docs/PROTOCOL.md.
enum BridgeUuids {
    private static let base = "-C4D1-4A7E-9B3A-9A8E1F2A3B4C"

    static func uuid(_ short: String) -> UUID {
        UUID(uuidString: "0000\(short)\(base)")!
    }

    // Services
    static let serviceMain = uuid("0001")
    static let servicePairing = uuid("0002")

    // Main service characteristics
    static let charStatus = uuid("0101")
    static let charCommand = uuid("0102")
    static let charNotification = uuid("0103")
    static let charHealth = uuid("0104")
    static let charPairing = uuid("0105")

    // Pairing service characteristics
    static let charPairingRequest = uuid("0201")
    static let charPairingResponse = uuid("0202")

    static let cccDescriptor = CBUUID(string: "00002902-0000-1000-8000-00805f9b34fb")
}
