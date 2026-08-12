import Foundation
import Combine
import Security
import HealthKit

/// High-level session: connects the watch to the phone, sends/receives messages,
/// handles pairing state, notification actions and health samples.
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
    @Published private(set) var notifications: [WatchNotification] = []
    @Published private(set) var pairingNonce: String?   // hex nonce для QR

    private var central: CentralManager?
    private var pendingFragments: [UInt64: [Data]] = [:]
    private var sequence: UInt64 = 1
    private var nonceBytes: Data?
    private var sessionKey: Data? {
        didSet { paired = sessionKey != nil }
    }
    private var isPhoneConnected = false
    private var reconnectAttempt = 0
    private var reconnectTimer: Timer?

    private init() {
        // paired state persistence would live here (Keychain)
    }

    func start() {
        let central = CentralManager(
            onMessage: { [weak self] data in
                self?.handleRawMessage(data)
            },
            onReady: { [weak self] in
                self?.isPhoneConnected = true
                self?.reconnectAttempt = 0
                self?.connectionState = .connected
                // Подключены: отправляем WATCH_HELLO с nonce из QR (или ping, если уже спарены).
                if self?.nonceBytes != nil {
                    self?.sendHello()
                } else {
                    self?.sendPing()
                }
            },
            onDisconnect: { [weak self] in
                self?.isPhoneConnected = false
                self?.connectionState = .idle
                self?.scheduleReconnect()
            }
        )
        self.central = central
        connectionState = .scanning
        central.startScanning()
    }

    func stop() {
        reconnectTimer?.invalidate()
        reconnectTimer = nil
        central?.disconnect()
        central = nil
        connectionState = .idle
    }

    /// Переподключение с экспоненциальным backoff: 2, 4, 8, ... до 60с.
    private func scheduleReconnect() {
        reconnectTimer?.invalidate()
        let delay = min(pow(2.0, Double(reconnectAttempt)), 60.0)
        reconnectAttempt += 1
        reconnectTimer = Timer.scheduledTimer(withTimeInterval: delay, repeats: false) { [weak self] _ in
            guard let self else { return }
            self.connectionState = .scanning
            self.central?.startScanning()
        }
    }

    // MARK: - QR payload

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
        guard let envelope = try? WBEnvelope(serializedData: message) else { return }

        switch envelope.type {
        case .notification:
            if let n = try? WBNotification(serializedData: envelope.payload) {
                addNotification(n)
            }
        case .notificationRemoved:
            if let r = try? WBNotificationRemoved(serializedData: envelope.payload) {
                notifications.removeAll { $0.key == r.key }
            }
        case .pairing:
            handlePairing(try? WBPairingMessage(serializedData: envelope.payload))
        case .pong:
            break
        default:
            break
        }
    }

    private func addNotification(_ n: WBNotification) {
        let item = WatchNotification(
            key: n.key,
            title: n.title,
            text: n.text,
            appName: n.appName,
            actionId: n.actions.first?.id
        )
        notifications.removeAll { $0.key == item.key }
        notifications.insert(item, at: 0)
        if notifications.count > 50 { notifications.removeLast() }
    }

    private func handlePairing(_ msg: WBPairingMessage?) {
        guard let msg else { return }
        switch msg.step {
        case .watchVerified, .phoneVerified:
            // success=true от телефона
            if msg.step == .phoneVerified && msg.success {
                sessionKey = msg.sessionKey
                nonceBytes = nil   // nonce одноразовый
            }
        default:
            break
        }
    }

    // MARK: - Send

    /// Ответ на уведомление (reply) или запуск действия — Watch → Phone.
    func sendNotificationAction(notificationKey: String, actionId: String, replyText: String?) {
        guard let central, isPhoneConnected else { return }
        var action = WBNotificationAction()
        action.notificationKey = notificationKey
        action.actionId = actionId
        action.replyText = replyText ?? ""
        send(.notificationAction, payload: action)
    }

    /// Отправка health-сэмпла — Watch → Phone.
    func sendHealthSample(metric: WBHealthMetric, value: Double, time: Date) {
        guard let central, isPhoneConnected, paired else { return }
        var sample = WBHealthSample()
        sample.metric = metric
        sample.value = value
        sample.timeMs = Int64(time.timeIntervalSince1970 * 1000)
        sample.source = "watch"
        send(.healthSample, payload: sample)
    }

    func sendPairing(pin: String) {
        sendHello()
    }

    /// Watch -> Phone: WATCH_HELLO c nonce из QR.
    private func sendHello() {
        guard let central, let nonce = nonceBytes else {
            NSLog("WATCH_HELLO пропущен: нет соединения или nonce")
            return
        }
        var msg = WBPairingMessage()
        msg.step = .watchHello
        msg.nonce = nonce
        send(.pairing, payload: msg)
        NSLog("WATCH_HELLO отправлен nonce=\(nonceHex(nonce))")
    }

    private func sendPing() {
        send(.ping, payload: Data())
    }

    /// Сериализация Envelope + отправка в характеристику Pairing.
    private func send<T: SwiftProtobuf.Message>(_ type: WBMessageType, payload: T) {
        guard let data = try? payload.serializedData() else { return }
        send(type, payload: data)
    }

    private func send(_ type: WBMessageType, payload: Data) {
        guard let central, isPhoneConnected else { return }
        var envelope = WBEnvelope()
        envelope.sequence = sequence
        envelope.type = type
        envelope.payload = payload
        sequence += 1
        guard let bytes = try? envelope.serializedData() else { return }
        central.write(bytes, to: BridgeUuids.charPairing)
    }

    private func nonceHex(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }
}

struct WatchNotification: Identifiable {
    let key: String
    let title: String
    let text: String
    let appName: String
    let actionId: String?
    var id: String { key }
}
