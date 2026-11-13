// NucleusWidgetKit.swift
//
// The iOS side of the Nucleus widget extension, built with the shared renderer
// (widget-runtime/src/main/native/swiftui/NucleusWidgetUI.swift). Unlike macOS, the widgets compose
// right here: the extension links the app's Kotlin framework (which must export widget-ios) and
// calls its NucleusWidgetKitEntryPoints, found through the Objective-C runtime by name, so neither
// a render nor a click needs the app to run.
//
// Add NucleusWidgetUI.swift and this file to the widget extension target, and declare the widgets
// in a WidgetBundle whose init starts them in Kotlin:
//
//     @main struct AppWidgets: WidgetBundle {
//         init() { AppWidgetsKt.startWidgets() }   // startIosWidgets { widget("Counter") { … } }
//         var body: some Widget {
//             NucleusWidget(kind: "Counter", displayName: "Counter", families: [.systemSmall])
//         }
//     }

import SwiftUI
import UIKit
import WidgetKit

/// A widget the app's Kotlin declares with `startIosWidgets { widget(kind) { … } }`.
struct NucleusWidget: Widget {
    let kind: String
    let displayName: String
    let description: String
    let families: [WidgetFamily]

    /// Required by `Widget`; declare widgets with the initializer below.
    init() {
        self.init(kind: "NucleusWidget")
    }

    init(kind: String, displayName: String? = nil, description: String = "", families: [WidgetFamily] = [.systemSmall, .systemMedium, .systemLarge]) {
        self.kind = kind
        self.displayName = displayName ?? kind
        self.description = description
        self.families = families
    }

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: NucleusProvider(kind: kind)) { entry in
            NucleusWidgetView(entry: entry)
        }
        .configurationDisplayName(displayName)
        .description(description)
        .supportedFamilies(families)
    }
}

/// Composes in Kotlin, on WidgetKit's background queue.
struct NucleusProvider: TimelineProvider {
    let kind: String

    func placeholder(in context: Context) -> NucleusEntry {
        entry(context, preview: true)
    }

    func getSnapshot(in context: Context, completion: @escaping (NucleusEntry) -> Void) {
        completion(entry(context, preview: context.isPreview))
    }

    // The widget changes on a click (WidgetKit reloads after the intent) or when the app reloads it.
    func getTimeline(in context: Context, completion: @escaping (Timeline<NucleusEntry>) -> Void) {
        completion(Timeline(entries: [entry(context, preview: false)], policy: .never))
    }

    private func entry(_ context: Context, preview: Bool) -> NucleusEntry {
        let family = NucleusFamilies.name(of: context.family)
        return NucleusEntry(date: .now, widget: "\(kind)/\(family)", tree: NucleusKotlin.render(kind: kind, family: family, preview: preview))
    }
}

/// widget-ios's NucleusWidgetKitEntryPoints (a Kotlin object): one string request, fields
/// separated by the ASCII unit separator.
enum NucleusKotlin {
    private static let separator = "\u{1F}"

    private static let entryPoints: NSObject? = {
        guard let type = NSClassFromString("NucleusWidgetKitEntryPoints") as AnyObject?,
              type.responds(to: NSSelectorFromString("shared")) else {
            NSLog("NucleusWidgets: no NucleusWidgetKitEntryPoints: the app's Kotlin framework must export widget-ios")
            return nil
        }
        return type.perform(NSSelectorFromString("shared"))?.takeUnretainedValue() as? NSObject
    }()

    private static func call(_ fields: [String]) -> String? {
        entryPoints?.perform(NSSelectorFromString("callRequest:"), with: fields.joined(separator: separator))?
            .takeUnretainedValue() as? String
    }

    static func render(kind: String, family: String, preview: Bool) -> Tree? {
        guard let json = call(["render", kind, family, preview ? "1" : "0"]) else { return nil }
        return try? JSONDecoder().decode(Tree.self, from: Data(json.utf8))
    }

    static func perform(kind: String, family: String, key: String, signature: String, inputs: String) {
        _ = call(["perform", kind, family, key, signature, inputs])
    }

    /// `kind/family` of a rendered widget.
    static func split(_ widget: String) -> (String, String)? {
        let parts = widget.split(separator: "/", maxSplits: 1).map(String.init)
        return parts.count == 2 ? (parts[0], parts[1]) : nil
    }
}

// MARK: - Renderer hooks (NucleusWidgetUI.swift)

func nucleusPerform(widget: String, key: String, signature: String, inputs: String) async {
    guard let (kind, family) = NucleusKotlin.split(widget) else { return }
    // Off the intent's executor: the call blocks until Kotlin has run the action and saved the state.
    await Task.detached {
        NucleusKotlin.perform(kind: kind, family: family, key: key, signature: signature, inputs: inputs)
    }.value
}

func nucleusLoadImage(_ src: String) -> PlatformImage? { UIImage(contentsOfFile: src) }

/// The app's name, when Kotlin has nothing to render (the widgets were not started).
struct NucleusPlaceholderView: View {
    var body: some View {
        Text(Bundle.main.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String ?? "")
            .font(.caption).foregroundStyle(.secondary)
    }
}
