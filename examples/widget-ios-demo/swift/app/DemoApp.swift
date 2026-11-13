// The widget-ios demo app: starts the widgets in Kotlin and pushes app-side updates.

import SwiftUI
import WidgetIosDemo

@main
struct DemoApp: App {
    init() {
        DemoWidgetsKt.startDemoWidgets()
    }

    var body: some Scene {
        WindowGroup { ContentView() }
    }
}

struct ContentView: View {
    @State private var count: Int32 = 0
    @State private var pinned = "…"

    var body: some View {
        VStack(spacing: 16) {
            Text("Small counter: \(count)").font(.title2)
            Button("Push +10 to the widget") {
                DispatchQueue.global().async {
                    DemoWidgetsKt.pushFromApp(family: "systemSmall")
                    refresh()
                }
            }
            Button("Refresh") { refresh() }
            Text("Pinned: \(pinned)").font(.caption).foregroundStyle(.secondary)
        }
        .padding()
        .onAppear { refresh() }
    }

    // Off the main thread: WidgetCenter answers through a callback the Kotlin side waits for.
    private func refresh() {
        DispatchQueue.global().async {
            let value = DemoWidgetsKt.countOf(family: "systemSmall")
            let widgets = DemoWidgetsKt.pinnedDescription()
            print("[E2E] count=\(value) pinned=\(widgets)")
            DispatchQueue.main.async {
                count = value
                pinned = widgets
            }
        }
    }
}
