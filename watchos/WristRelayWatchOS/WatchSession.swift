import Foundation
import Combine
import Security

/// High-level session: connects the watch to the phone, sends/receives messages,
/// handles pairing state.
final class WatchSession: ObservableObject {

    enum ConnectionState {
        case idle
        case scanning
        case connected
        case error(String)
    }

    static let shared = WatchSession()

    @Published private(set) var connectionState: ConnectionState = .idle
    @Published private(set) var paired = false
    @Published private(set) var notifications: [WBNotification] = []
    @Published private(set) var pinRequired = false
    @Published private(set) var pairingNonce: String?   // hex nonce для QR

    private var central: CentralManager?
    private var pendingFragments: [UInt64: [Data]] = [:]
    private var sequence: UInt64 = 1
    private var nonceBytes: Data?
    private var sessionKey: Data? {
        didSet { paired = sessionKey != nil }
    }

    private init() {
        // paired state persistence would live here (Keychain)
    }

    func start() {
        let central = CentralManager(
            onMessage: { [weak self] data in
                self?.handleRawMessage(data)
            },
            onReady: { [weak self] in
                // Подключены и готовы: отправляем WATCH_HELLO с nonce из QR.
                self?.sendHello()
            }
        )
        self.central = central
        connectionState = .scanning
        central.startScanning()
    }

    func stop() {
        central?.disconnect()
        central = nil
        connectionState = .idle
    }

    /// Генерирует 32-байтовый nonce и payload для QR-кода.
    /// Формат: "WRISTRELAY:1:<hex nonce>" (см. docs/PROTOCOL.md).
    func generateQrPayload() -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        let data = Data(bytes)
        nonceBytes = data
        let hex = data.map { String(format: "%02x", $0) }.joined()
        pairingNonce = hex
        return "WRISTRELAY:1:" + hex
    }

    // MARK: - Receive

    private func handleRawMessage(_ data: Data) {
        guard let message = Fragmentation.accept(fragment: data, pending: &pendingFragments) else {
            return
        }
        // message is an Envelope (protobuf). Without generated code we parse the
        // type field manually: field 2 varint => tag byte 0x10.
        guard message.count >= 3 else { return }
        // Parse MessageType (field 2, varint). Tag = 0x10, then the value.
        let tag = message[message.startIndex + 1]
        guard tag == 0x10 else { return }
        var typeValue = Int(message[message.startIndex + 2])
        let type = MessageType(rawValue: typeValue) ?? .unknown
        let payloadStart = message.startIndex + 3

        switch type {
        case .notification:
            let n = parseNotification(Data(message[payloadStart..<message.endIndex]))
            notifications.insert(n, at: 0)
            if notifications.count > 50 { notifications.removeLast() }
        case .notificationRemoved:
            break
        case .pairing:
            handlePairing(Data(message[payloadStart..<message.endIndex]))
        case .pong:
            break
        default:
            break
        }
    }

    private func handlePairing(_ payload: Data) {
        // Parse PairingMessage minimal: step is field 1 varint (tag 0x08).
        // We don't have full generated code yet, so we inspect the first bytes.
        if payload.count >= 3, payload[payload.startIndex + 1] == 0x08 {
            let step = Int(payload[payload.startIndex + 2])
            if step == 2 { // PHONE_PIN
                pinRequired = true
            } else if step == 4 { // PHONE_VERIFIED
                pinRequired = false
                // session key present
            }
        }
    }

    // MARK: - Send

    func sendNotificationAction(notificationKey: String, actionId: String, replyText: String?) {
        // WBNotificationAction minimal encoding is deferred to protobuf codegen.
        // Placeholder: log only.
        NSLog("action \(notificationKey)/\(actionId) reply=\(replyText ?? "-")")
    }

    func sendPairing(pin: String) {
        // QR-поток: nonce сгенерирован и показан в QR. При подключении по BLE
        // телефон сверяет nonce из QR с nonce в WATCH_HELLO.
        sendHello()
    }

    /// Watch -> Phone: WATCH_HELLO c nonce из QR.
    /// Ручная protobuf-сериализация (см. proto/bridge.proto):
    ///   Envelope{sequence=1, type=PAIRING(5), payload=PairingMessage}
    ///   PairingMessage{step=WATCH_HELLO(1), nonce}
    private func sendHello() {
        guard let central = central, let nonce = nonceBytes else {
            NSLog("WATCH_HELLO пропущен: нет соединения или nonce")
            return
        }
        let payload = encodePairingMessage(step: 1, nonce: nonce)
        let envelope = encodeEnvelope(sequence: sequence, type: 5, payload: payload)
        sequence += 1
        // WATCH_HELLO шлём в характеристику Pairing (служба Main).
        central.write(envelope, to: BridgeUuids.charPairing)
        NSLog("WATCH_HELLO отправлен nonce=\(nonceHex(nonce))")
    }

    /// Envelope{1: sequence (varint), 2: type (varint), 3: payload (bytes)}
    private func encodeEnvelope(sequence: UInt64, type: Int, payload: Data) -> Data {
        var out = Data()
        out.append(0x08)                       // field 1, wire 0 (varint)
        out.append(varint(sequence))
        out.append(0x10)                       // field 2, wire 0 (varint)
        out.append(varint(UInt64(type)))
        out.append(0x1A)                       // field 3, wire 2 (length-delimited)
        out.append(varint(UInt64(payload.count)))
        out.append(payload)
        return out
    }

    /// PairingMessage{1: step (varint), 2: nonce (bytes)}
    private func encodePairingMessage(step: Int, nonce: Data) -> Data {
        var out = Data()
        out.append(0x08)                       // field 1, wire 0
        out.append(varint(UInt64(step)))
        out.append(0x12)                       // field 2, wire 2 (length-delimited)
        out.append(varint(UInt64(nonce.count)))
        out.append(nonce)
        return out
    }

    private func varint(_ value: UInt64) -> Data {
        var v = value
        var out = Data()
        while v >= 0x80 {
            out.append(UInt8(v & 0x7F) | 0x80)
            v >>= 7
        }
        out.append(UInt8(v))
        return out
    }

    private func nonceHex(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }

    // MARK: - Parsing (minimal, no codegen)

    private func parseNotification(_ payload: Data) -> WBNotification {
        // Simplified: iterate varint fields. Full parse requires SwiftProtobuf.
        // Fields of interest: 3 title (string), 4 text (string), 5 app_name (string).
        var fields: [Int: Data] = [:]
        var index = payload.startIndex
        while index < payload.endIndex {
            guard let (fieldNumber, wireType, newIndex) = readTag(payload, index) else { break }
            index = newIndex
            switch wireType {
            case 0: // varint
                index = skipVarint(payload, index)
            case 2: // length-delimited
                guard index + 1 <= payload.endIndex else { break }
                let len = Int(payload[index])
                index += 1
                guard index + len <= payload.endIndex else { break }
                fields[fieldNumber] = payload.subdata(in: index..<index + len)
                index += len
            default:
                break
            }
        }
        let str = { (n: Int) -> String in
            guard let data = fields[n] else { return "" }
            return String(data: data, encoding: .utf8) ?? ""
        }
        return WBNotification(
            key: str(2),
            title: str(3),
            text: str(4),
            appName: str(5)
        )
    }

    private func readTag(_ data: Data, _ index: Int) -> (Int, Int, Int)? {
        guard index < data.endIndex else { return nil }
        var shift = 0
        var value = 0
        var i = index
        while i < data.endIndex {
            let byte = data[i]
            value |= Int(byte & 0x7F) << shift
            i += 1
            if byte & 0x80 == 0 { break }
            shift += 7
        }
        return (value >> 3, value & 0x07, i)
    }

    private func skipVarint(_ data: Data, _ index: Int) -> Int {
        var i = index
        while i < data.endIndex && data[i] & 0x80 != 0 { i += 1 }
        return i + 1
    }
}

struct WBNotification: Identifiable {
    let key: String
    let title: String
    let text: String
    let appName: String
    var id: String { key }
}

enum MessageType: Int {
    case unknown = 0
    case notification = 1
    case notificationRemoved = 2
    case notificationAction = 3
    case healthSample = 4
    case pairing = 5
    case command = 6
    case ping = 7
    case pong = 8
}
