import SwiftUI

struct RootView: View {
    @EnvironmentObject var session: WatchSession

    var body: some View {
        TabView {
            StatusView()
            NotificationListView()
            HealthView()
            PairingView()
        }
        .onAppear {
            session.start()
        }
    }
}

struct StatusView: View {
    @EnvironmentObject var session: WatchSession

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: connectionIcon)
                .font(.system(size: 40))
                .foregroundColor(connectionColor)
            Text(connectionText)
                .font(.headline)
                .multilineTextAlignment(.center)
            if case .error(let msg) = session.connectionState {
                Text(msg).font(.caption2).foregroundColor(.red)
            }
        }
        .padding()
    }

    private var connectionText: String {
        switch session.connectionState {
        case .idle: return "Отключено"
        case .scanning: return "Поиск телефона…"
        case .connected: return session.paired ? "Подключено и спарено" : "Подключено (не спарено)"
        case .error(let msg): return msg
        }
    }

    private var connectionIcon: String {
        switch session.connectionState {
        case .connected: return "iphone.radiowaves.left.and.right"
        case .scanning: return "antenna.radiowaves.left.and.right"
        default: return "iphone.slash"
        }
    }

    private var connectionColor: Color {
        switch session.connectionState {
        case .connected: return .green
        case .scanning: return .orange
        default: return .red
        }
    }
}

struct NotificationListView: View {
    @EnvironmentObject var session: WatchSession

    var body: some View {
        if session.notifications.isEmpty {
            Text("Нет уведомлений")
                .font(.headline)
                .foregroundColor(.secondary)
        } else {
            List(session.notifications) { n in
                VStack(alignment: .leading, spacing: 4) {
                    Text(n.title.isEmpty ? "(без заголовка)" : n.title)
                        .font(.headline)
                        .lineLimit(2)
                    if !n.text.isEmpty {
                        Text(n.text).font(.body).lineLimit(3)
                    }
                    Text(n.appName).font(.caption2).foregroundColor(.secondary)

                    // Ответить на уведомление (reply) — если есть действие
                    if let actionId = n.actionId {
                        HStack(spacing: 8) {
                            Button("Ответить") {
                                replyPrompt = n.key
                                replyActionId = actionId
                                replyText = ""
                            }
                            .font(.caption)
                            .disabled(!session.paired)
                        }
                    }
                }
            }
            .sheet(item: replyBinding) { _ in
                ReplySheet(
                    onSend: { text in
                        if let key = replyPrompt, let actionId = replyActionId {
                            session.sendNotificationAction(
                                notificationKey: key,
                                actionId: actionId,
                                replyText: text
                            )
                        }
                        replyPrompt = nil
                        replyActionId = nil
                    }
                )
            }
        }
    }

    @State private var replyPrompt: String?
    @State private var replyActionId: String?
    @State private var replyText = ""

    private var replyBinding: Binding<ReplyTarget?> {
        Binding(
            get: { replyPrompt.map { ReplyTarget(key: $0) } },
            set: { if $0 == nil { replyPrompt = nil } }
        )
    }
}

struct ReplyTarget: Identifiable {
    let key: String
    var id: String { key }
}

struct ReplySheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    let onSend: (String) -> Void

    var body: some View {
        VStack(spacing: 12) {
            TextField("Ответ…", text: $text)
                .font(.body)
            Button("Отправить") {
                onSend(text)
                dismiss()
            }
            .disabled(text.isEmpty)
            Button("Отмена") { dismiss() }
        }
        .padding()
    }
}

struct HealthView: View {
    @EnvironmentObject var session: WatchSession
    @State private var reader: HealthReader?
    @State private var authText = ""

    var body: some View {
        VStack(spacing: 8) {
            Text("Здоровье")
                .font(.headline)
            Text(authText)
                .font(.caption2)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
            Button("Запросить доступ") {
                startHealth()
            }
            .font(.caption)
            Button("Отправлять на телефон") {
                reader?.startSending()
            }
            .font(.caption)
            .disabled(reader?.authState != .authorized)
        }
        .padding()
    }

    private func startHealth() {
        guard reader == nil else { reader?.requestAuthorization(); return }
        reader = HealthReader { metric, value, time in
            session.sendHealthSample(metric: metric, value: value, time: time)
        }
        reader?.requestAuthorization()
        // наблюдаем за состоянием
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
            switch reader?.authState {
            case .authorized: authText = "Доступ разрешён. Нажмите «Отправлять»."
            case .denied: authText = "Доступ запрещён (Настройки → Здоровье)."
            default: authText = "Ожидание ответа…"
            }
        }
    }
}

struct PairingView: View {
    @EnvironmentObject var session: WatchSession
    @State private var pin = ""
    @State private var qrImage: Image?
    @State private var qrPayload = ""

    var body: some View {
        VStack(spacing: 8) {
            if session.paired {
                Label("Часы спарены", systemImage: "checkmark.seal.fill")
                    .foregroundColor(.green)
                    .font(.headline)
            } else {
                Text("Пэйринг с телефоном")
                    .font(.headline)

                if let qrImage {
                    qrImage
                        .interpolation(.none)
                        .resizable()
                        .scaledToFit()
                        .frame(width: 90, height: 90)
                        .padding(4)
                        .background(Color.white)
                        .cornerRadius(6)
                } else {
                    Button("Показать QR-код") {
                        qrPayload = session.generateQrPayload()
                        qrImage = makeQrImage(qrPayload)
                    }
                    .font(.caption)
                }

                Text("Отсканируйте QR в приложении WristRelay на телефоне")
                    .font(.caption2)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
            }
        }
        .padding()
    }

    private func makeQrImage(_ payload: String) -> Image? {
        guard let cgImage = QrCodeGenerator.cgImage(data: payload, size: 90) else { return nil }
        return Image(decorative: cgImage, scale: 1.0)
    }
}
