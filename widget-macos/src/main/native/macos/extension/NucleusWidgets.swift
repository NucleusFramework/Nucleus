// NucleusWidgets.swift
//
// The generic WidgetKit extension of nucleus.widget-macos, built with the shared renderer
// (widget-runtime/src/main/native/swiftui/NucleusWidgetUI.swift). It holds no widget of its own: the
// Gradle plugin lists the app's widget kinds in the extension's Info.plist (`NucleusWidgets`), the
// JVM app composes each widget into a JSON tree in a store directory both processes reach, and
// this extension draws that tree with SwiftUI. Clicks go back to the app through an AppIntent: a
// request file, a Darwin notification, and a launch of the app (not activated) when it is not
// running; the intent waits for the app's acknowledgement so the timeline WidgetKit reloads right
// after it shows the result.
//
// Prebuilt once for every app: the widget kinds are read at launch, and the entry point picks the
// WidgetBundle with that many slots (WidgetBundleBuilder takes no run-time `if`).

import AppIntents
import AppKit
import Darwin
import notify
import SwiftUI
import WidgetKit

// MARK: - Configuration (Info.plist)

struct WidgetDefinition {
    let kind: String
    let displayName: String
    let description: String
    let families: [WidgetFamily]
}

enum Config {
    static let info = Bundle.main.object(forInfoDictionaryKey: "NucleusWidgets") as? [String: Any] ?? [:]

    static let definitions: [WidgetDefinition] =
        (info["Widgets"] as? [[String: Any]] ?? []).compactMap { entry in
            guard let kind = entry["Kind"] as? String else { return nil }
            let families = (entry["Families"] as? [String] ?? []).compactMap(NucleusFamilies.family(named:))
            return WidgetDefinition(
                kind: kind,
                displayName: entry["DisplayName"] as? String ?? kind,
                description: entry["Description"] as? String ?? "",
                families: families.isEmpty ? [.systemSmall, .systemMedium, .systemLarge] : families
            )
        }

    /// The containing app: PlugIns/<extension>.appex → Contents → <app>.app.
    static let appURL = Bundle.main.bundleURL.deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent()

    static let appBundleIdentifier = info["AppBundleIdentifier"] as? String
        ?? Bundle(url: appURL)?.bundleIdentifier ?? "app"

    /// The store shared with the app: an App Group container, or a path under the user's real home
    /// (the extension's sandbox reaches it through a temporary-exception entitlement).
    static let store: URL? = {
        if let group = info["StoreAppGroup"] as? String {
            return FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group)
        }
        guard let relative = info["StoreHomePath"] as? String, let pw = getpwuid(getuid()), let home = pw.pointee.pw_dir
        else { return nil }
        return URL(fileURLWithPath: String(cString: home), isDirectory: true).appendingPathComponent(relative, isDirectory: true)
    }()

    static let requestNotification = "\(appBundleIdentifier).nucleus-widgets.request"
}

enum Store {
    static func tree(_ folder: String, kind: String, family: String) -> Tree? {
        guard let url = Config.store?.appendingPathComponent(folder).appendingPathComponent(kind)
            .appendingPathComponent("\(family).json"),
            let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(Tree.self, from: data)
    }

    static func image(_ relative: String) -> NSImage? {
        guard let url = Config.store?.appendingPathComponent(relative) else { return nil }
        return NSImage(contentsOf: url)
    }
}

// MARK: - Renderer hooks (NucleusWidgetUI.swift)

func nucleusLoadImage(_ src: String) -> PlatformImage? { Store.image(src) }

func nucleusPerform(widget: String, key: String, signature: String, inputs: String) async {
    // WidgetKit reloads the timeline once the intent returns: wait for the app's new render.
    await Channel.send(
        ["type": "action", "widget": widget, "key": key, "signature": signature, "inputs": inputs],
        timeout: 15
    )
}

/// The app's icon and name, until the app renders the widget.
struct NucleusPlaceholderView: View {
    var body: some View {
        VStack(spacing: 6) {
            Image(nsImage: NSWorkspace.shared.icon(forFile: Config.appURL.path)).resizable().frame(width: 32, height: 32)
            Text(FileManager.default.displayName(atPath: Config.appURL.path).replacingOccurrences(of: ".app", with: ""))
                .font(.caption).foregroundStyle(.secondary)
        }
    }
}

// MARK: - Channel to the app

enum Channel {
    private static let pollInterval: UInt64 = 50_000_000

    /// Hands [fields] to the app and waits for its acknowledgement, launching the app if needed.
    /// Returns whether the app answered within [timeout] seconds.
    @discardableResult
    static func send(_ fields: [String: String], timeout: TimeInterval, launch: Bool = true) async -> Bool {
        guard let store = Config.store else { return false }
        let id = UUID().uuidString
        let requests = store.appendingPathComponent("requests", isDirectory: true)
        let done = store.appendingPathComponent("done", isDirectory: true).appendingPathComponent(id)
        do {
            try FileManager.default.createDirectory(at: requests, withIntermediateDirectories: true)
            var request = fields
            request["id"] = id
            let data = try JSONSerialization.data(withJSONObject: request)
            // Written aside then renamed: the app never reads half a request.
            let partial = requests.appendingPathComponent(".\(id).tmp")
            try data.write(to: partial)
            try FileManager.default.moveItem(at: partial, to: requests.appendingPathComponent("\(id).json"))
        } catch {
            NSLog("NucleusWidgets: cannot write a request: %@", String(describing: error))
            return false
        }
        notify_post(Config.requestNotification)
        if !isAppRunning {
            guard launch else { return false }
            launchApp(store: store)
        }
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if FileManager.default.fileExists(atPath: done.path) {
                try? FileManager.default.removeItem(at: done)
                return true
            }
            try? await Task.sleep(nanoseconds: pollInterval)
        }
        return false
    }

    static var isAppRunning: Bool {
        !NSRunningApplication.runningApplications(withBundleIdentifier: Config.appBundleIdentifier).isEmpty
    }

    private static func launchApp(store: URL) {
        // The app reads this to know the widget host started it (isWidgetHostOnly).
        try? String(Date().timeIntervalSince1970).write(
            to: store.appendingPathComponent("launched-by-widget"), atomically: true, encoding: .utf8)
        let configuration = NSWorkspace.OpenConfiguration()
        configuration.activates = false
        configuration.addsToRecentItems = false
        configuration.promptsUserIfNeeded = false
        NSWorkspace.shared.openApplication(at: Config.appURL, configuration: configuration) { _, error in
            if let error { NSLog("NucleusWidgets: cannot launch the app: %@", String(describing: error)) }
        }
    }
}

// MARK: - Timeline

struct NucleusProvider: TimelineProvider {
    let kind: String

    func placeholder(in context: Context) -> NucleusEntry {
        let family = NucleusFamilies.name(of: context.family)
        return NucleusEntry(date: .now, widget: "\(kind)/\(family)", tree: Store.tree("previews", kind: kind, family: family))
    }

    func getSnapshot(in context: Context, completion: @escaping (NucleusEntry) -> Void) {
        let family = NucleusFamilies.name(of: context.family)
        let tree = context.isPreview
            ? Store.tree("previews", kind: kind, family: family) ?? Store.tree("renders", kind: kind, family: family)
            : Store.tree("renders", kind: kind, family: family)
        completion(NucleusEntry(date: .now, widget: "\(kind)/\(family)", tree: tree))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<NucleusEntry>) -> Void) {
        let family = NucleusFamilies.name(of: context.family)
        let widget = "\(kind)/\(family)"
        Task {
            var tree = Store.tree("renders", kind: kind, family: family)
            let fields = ["type": tree == nil ? "render" : "timeline", "widget": widget]
            if tree == nil {
                // First time on screen: the app composes it, started if needed.
                await Channel.send(fields, timeout: 20)
                tree = Store.tree("renders", kind: kind, family: family)
            } else if Channel.isAppRunning {
                // Tells a running app the widget is on screen; never starts it for that.
                await Channel.send(fields, timeout: 0, launch: false)
            }
            // The app reloads the timeline itself whenever the widget changes.
            completion(Timeline(entries: [NucleusEntry(date: .now, widget: widget, tree: tree)], policy: .never))
        }
    }
}

// MARK: - Widgets and entry point

protocol Slot { static var index: Int { get } }

struct NucleusWidget<S: Slot>: Widget {
    init() {}

    var body: some WidgetConfiguration {
        // Bundle1 also runs with nothing declared: an inert widget the gallery does not offer.
        let definition = S.index < Config.definitions.count
            ? Config.definitions[S.index]
            : WidgetDefinition(kind: "NucleusWidget", displayName: "", description: "", families: [])
        return StaticConfiguration(kind: definition.kind, provider: NucleusProvider(kind: definition.kind)) { entry in
            NucleusWidgetView(entry: entry)
        }
        .configurationDisplayName(definition.displayName)
        .description(definition.description)
        .supportedFamilies(definition.families)
    }
}
