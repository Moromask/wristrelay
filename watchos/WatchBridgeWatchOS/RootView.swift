import SwiftUI

struct RootView: View {
    @EnvironmentObject var session: WatchSession

    var body: some View {
        TabView {
            StatusView()
            NotificationListView()
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
                }
            }
        }
    }
}

struct PairingView: View {
    @EnvironmentObject var session: WatchSession
    @State private var pin = ""

    var body: some View {
        VStack(spacing: 12) {
            if session.paired {
                Label("Часы спарены", systemImage: "checkmark.seal.fill")
                    .foregroundColor(.green)
                    .font(.headline)
            } else {
                Text("Пэйринг с телефоном")
                    .font(.headline)
                TextField("PIN с телефона", text: $pin)
                    .textContentType(.oneTimeCode)
                    .keyboardType(.numberPad)
                    .font(.title2)
                    .multilineTextAlignment(.center)
                Button("Подтвердить") {
                    session.sendPairing(pin: pin)
                }
                .disabled(pin.count != 6)
            }
        }
        .padding()
    }
}
