// NucleusWidgets.swift
//
// The generic WidgetKit extension of nucleus.widget-macos. It holds no widget of its own: the
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
            let families = (entry["Families"] as? [String] ?? []).compactMap(family(named:))
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

    static func family(named name: String) -> WidgetFamily? {
        switch name {
        case "systemSmall": return .systemSmall
        case "systemMedium": return .systemMedium
        case "systemLarge": return .systemLarge
        case "systemExtraLarge": return .systemExtraLarge
        default: return nil
        }
    }

    static func name(of family: WidgetFamily) -> String {
        switch family {
        case .systemSmall: return "systemSmall"
        case .systemMedium: return "systemMedium"
        case .systemLarge: return "systemLarge"
        case .systemExtraLarge: return "systemExtraLarge"
        default: return "systemMedium"
        }
    }
}

// MARK: - Rendered tree (written by the app)

final class Node: Decodable {
    let t: String
    let c: [Node]?
    let s: String?
    let sp: String?
    let ha: String?
    let va: String?
    let fs: String?
    let fw: String?
    let fc: String?
    let ml: Int?
    let en: Bool?
    let src: String?
    let cd: String?
    let sz: String?
    let id: String?
    let v: String?
    let on: Bool?
    let label: String?
    let ch: [ChoiceItem]?
    let a: NodeAction?
    let m: NodeModifier?
}

struct ChoiceItem: Decodable {
    let t: String
    let v: String
    let a: NodeAction?
}

/// What a click does: run app code ([k], checked against [sig] by the app), write inputs ([in]),
/// or open [url].
struct NodeAction: Decodable {
    let k: String?
    let sig: String?
    let `in`: [String: String]?
    let url: String?
}

struct NodeModifier: Decodable {
    let fw: Bool?
    let fh: Bool?
    let w: Int?
    let bg: String?
    let click: NodeAction?
}

struct Tree: Decodable {
    let root: Node
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

// MARK: - Action intent

struct NucleusWidgetAction: AppIntent {
    static let title: LocalizedStringResource = "Widget action"
    static let isDiscoverable: Bool = false

    @Parameter(title: "Widget") var widget: String
    @Parameter(title: "Action") var key: String
    @Parameter(title: "Signature") var signature: String
    @Parameter(title: "Inputs") var inputs: String

    init() {}

    init(widget: String, action: NodeAction) {
        self.widget = widget
        key = action.k ?? ""
        signature = action.sig ?? ""
        let encoded = (try? JSONSerialization.data(withJSONObject: action.in ?? [:])).flatMap {
            String(data: $0, encoding: .utf8)
        }
        inputs = encoded ?? "{}"
    }

    func perform() async throws -> some IntentResult {
        // WidgetKit reloads the timeline once this returns: wait for the app's new render.
        await Channel.send(
            ["type": "action", "widget": widget, "key": key, "signature": signature, "inputs": inputs],
            timeout: 15
        )
        return .result()
    }
}

// MARK: - Timeline

struct NucleusEntry: TimelineEntry {
    let date: Date
    let widget: String
    let tree: Tree?
}

struct NucleusProvider: TimelineProvider {
    let kind: String

    func placeholder(in context: Context) -> NucleusEntry {
        let family = Config.name(of: context.family)
        return NucleusEntry(date: .now, widget: "\(kind)/\(family)", tree: Store.tree("previews", kind: kind, family: family))
    }

    func getSnapshot(in context: Context, completion: @escaping (NucleusEntry) -> Void) {
        let family = Config.name(of: context.family)
        let tree = context.isPreview
            ? Store.tree("previews", kind: kind, family: family) ?? Store.tree("renders", kind: kind, family: family)
            : Store.tree("renders", kind: kind, family: family)
        completion(NucleusEntry(date: .now, widget: "\(kind)/\(family)", tree: tree))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<NucleusEntry>) -> Void) {
        let family = Config.name(of: context.family)
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

// MARK: - Views

struct NucleusWidgetView: View {
    let entry: NucleusEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        content.containerBackground(for: .widget) { background }
    }

    @ViewBuilder private var content: some View {
        if let root = entry.tree?.root {
            NodeView(node: root, widget: entry.widget, parentAlignment: .leading, isRoot: true)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: Alignment(
                    horizontal: Style.horizontal(root.ha), vertical: .top))
        } else {
            VStack(spacing: 6) {
                if let icon = NSWorkspace.shared.icon(forFile: Config.appURL.path) as NSImage? {
                    Image(nsImage: icon).resizable().frame(width: 32, height: 32)
                }
                Text(FileManager.default.displayName(atPath: Config.appURL.path).replacingOccurrences(of: ".app", with: ""))
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    @ViewBuilder private var background: some View {
        if let color = Style.background(entry.tree?.root.m?.bg) { color } else { Color.clear.background(.background) }
    }
}

struct NodeView: View {
    let node: Node
    let widget: String
    let parentAlignment: HorizontalAlignment
    /// The root's background is the widget's container background, not a rounded box.
    var isRoot = false

    var body: some View {
        decorated(element)
    }

    @ViewBuilder private var element: some View {
        switch node.t {
        case "col":
            VStack(alignment: Style.horizontal(node.ha), spacing: Style.spacing(node.sp)) {
                ForEach(Array((node.c ?? []).enumerated()), id: \.offset) { _, child in
                    NodeView(node: child, widget: widget, parentAlignment: Style.horizontal(node.ha))
                }
            }
        case "row":
            HStack(alignment: Style.vertical(node.va), spacing: Style.spacing(node.sp)) {
                ForEach(Array((node.c ?? []).enumerated()), id: \.offset) { _, child in
                    NodeView(node: child, widget: widget, parentAlignment: parentAlignment)
                }
            }
        case "text":
            Text(node.s ?? "")
                .font(Style.font(node.fs))
                .fontWeight(Style.weight(node.fw))
                .foregroundStyle(Style.color(node.fc))
                .lineLimit(node.ml)
                .multilineTextAlignment(Style.textAlignment(parentAlignment))
        case "button":
            button
        case "img":
            image
        case "toggle":
            Toggle(
                isOn: node.on ?? false,
                intent: NucleusWidgetAction(widget: widget, action: node.a ?? NodeAction(k: nil, sig: nil, in: nil, url: nil))
            ) { Text(node.s ?? "") }
                .toggleStyle(.switch)
        case "choice":
            choice
        default:
            spacer
        }
    }

    // Empty space: a fixed gap, or all the room left when it fills (the push-apart idiom).
    @ViewBuilder private var spacer: some View {
        if node.m?.fh == true || node.m?.fw == true || node.m?.w != nil {
            Spacer(minLength: 0)
        } else {
            Color.clear.frame(width: 8, height: 8)
        }
    }

    @ViewBuilder private var button: some View {
        if let url = node.a?.url.flatMap(URL.init(string:)) {
            Link(destination: url) { Text(node.s ?? "") }.fixedSize()
        } else {
            Button(intent: NucleusWidgetAction(widget: widget, action: node.a ?? NodeAction(k: nil, sig: nil, in: nil, url: nil))) {
                Text(node.s ?? "")
            }
            .disabled(node.en == false)
            // A weighted sibling (a field beside it) must not squeeze the label away.
            .fixedSize()
        }
    }

    @ViewBuilder private var image: some View {
        if let image = node.src.flatMap(Store.image) {
            let picture = Image(nsImage: image).resizable().aspectRatio(contentMode: .fit)
                .accessibilityLabel(node.cd ?? "")
            switch node.sz {
            case "stretch": picture.frame(maxWidth: .infinity)
            case "auto": picture.frame(maxWidth: image.size.width, maxHeight: image.size.height)
            default:
                let side = Style.imageSide(node.sz)
                picture.frame(width: side, height: side)
            }
        }
    }

    // WidgetKit has no picker: each choice is a button that writes its value.
    @ViewBuilder private var choice: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let label = node.label { Text(label).font(.caption).foregroundStyle(.secondary) }
            let layout = node.on == true
                ? AnyLayout(VStackLayout(alignment: .leading, spacing: 4)) : AnyLayout(HStackLayout(spacing: 4))
            layout {
                ForEach(Array((node.ch ?? []).enumerated()), id: \.offset) { _, item in
                    let intent = NucleusWidgetAction(widget: widget, action: item.a ?? NodeAction(k: nil, sig: nil, in: nil, url: nil))
                    if item.v == node.v {
                        Button(intent: intent) { Text(item.t) }.buttonStyle(.borderedProminent)
                    } else {
                        Button(intent: intent) { Text(item.t) }.buttonStyle(.bordered)
                    }
                }
            }
        }
    }

    @ViewBuilder private func decorated(_ view: some View) -> some View {
        let modifier = node.m
        // A filling column places its children by its own alignment (Compose's Column does), anything
        // else sits in the room it fills as its parent aligns it.
        let alignment = node.t == "col" ? Style.horizontal(node.ha) : parentAlignment
        let filled = view
            .frame(
                maxWidth: modifier?.fw == true || modifier?.w != nil ? .infinity : nil,
                maxHeight: modifier?.fh == true ? .infinity : nil,
                alignment: Alignment(horizontal: alignment, vertical: .top)
            )
            .layoutPriority(Double(modifier?.w ?? 0))
        let backed = Group {
            if let color = Style.background(modifier?.bg), !isRoot {
                filled.padding(6).background(RoundedRectangle(cornerRadius: 8, style: .continuous).fill(color))
            } else {
                filled
            }
        }
        if let click = modifier?.click {
            if let url = click.url.flatMap(URL.init(string:)) {
                Link(destination: url) { backed }
            } else {
                Button(intent: NucleusWidgetAction(widget: widget, action: click)) { backed }.buttonStyle(.plain)
            }
        } else {
            backed
        }
    }
}

enum Style {
    static func spacing(_ name: String?) -> CGFloat {
        switch name {
        case "none": return 0
        case "small": return 4
        case "medium": return 12
        case "large": return 16
        case "extraLarge": return 24
        default: return 8
        }
    }

    static func horizontal(_ name: String?) -> HorizontalAlignment {
        switch name {
        case "center": return .center
        case "end": return .trailing
        default: return .leading
        }
    }

    static func vertical(_ name: String?) -> VerticalAlignment {
        switch name {
        case "center": return .center
        case "bottom": return .bottom
        default: return .top
        }
    }

    static func textAlignment(_ alignment: HorizontalAlignment) -> TextAlignment {
        switch alignment {
        case .center: return .center
        case .trailing: return .trailing
        default: return .leading
        }
    }

    static func font(_ name: String?) -> Font {
        switch name {
        case "small": return .caption
        case "medium": return .title3
        case "large": return .title2
        case "extraLarge": return .system(size: 34)
        default: return .body
        }
    }

    static func weight(_ name: String?) -> Font.Weight {
        switch name {
        case "light": return .light
        case "bold": return .bold
        default: return .regular
        }
    }

    static func color(_ name: String?) -> AnyShapeStyle {
        switch name {
        case "subtle": return AnyShapeStyle(.secondary)
        case "accent": return AnyShapeStyle(.tint)
        case "good": return AnyShapeStyle(.green)
        case "warning": return AnyShapeStyle(.orange)
        case "attention": return AnyShapeStyle(.red)
        default: return AnyShapeStyle(.primary)
        }
    }

    static func background(_ name: String?) -> Color? {
        switch name {
        case "emphasis": return Color.primary.opacity(0.08)
        case "accent": return Color.accentColor.opacity(0.25)
        case "good": return Color.green.opacity(0.2)
        case "warning": return Color.orange.opacity(0.2)
        case "attention": return Color.red.opacity(0.2)
        default: return nil
        }
    }

    static func imageSide(_ name: String?) -> CGFloat {
        switch name {
        case "icon": return 16
        case "small": return 32
        case "large": return 96
        default: return 56
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
