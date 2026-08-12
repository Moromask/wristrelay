import SwiftUI
import CoreImage.CIFilterBuiltins
import CoreImage

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
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(payload.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 8, y: 8))
        let context = CIContext()
        guard let cgImage = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        return Image(decorative: cgImage, scale: 1.0)
    }
}
