import SwiftUI

@main
struct KeepGoingApp: App {
    @StateObject private var controller = EllipticalController()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(controller)
                .environmentObject(controller.workout)
        }
    }
}

/// Bottom tabs, same labels and order as the Android app: Workout, History, Settings.
struct RootView: View {
    @State private var tab = 0

    var body: some View {
        TabView(selection: $tab) {
            WorkoutTab()
                .tabItem { Label(L("tab_workout"), systemImage: "figure.elliptical") }
                .tag(0)
            HistoryTab()
                .tabItem { Label(L("tab_history"), systemImage: "calendar") }
                .tag(1)
            SettingsTab()
                .tabItem { Label(L("tab_settings"), systemImage: "gearshape") }
                .tag(2)
        }
        .tint(Theme.primary)
    }
}
