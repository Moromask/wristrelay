import SwiftUI

@main
struct WristRelayWatchOSApp: App {
    @StateObject private var session = WatchSession.shared

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(session)
        }
    }
}
