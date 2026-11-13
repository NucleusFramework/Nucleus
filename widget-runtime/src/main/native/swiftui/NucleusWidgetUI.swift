// NucleusWidgetUI.swift
//
// The SwiftUI renderer of the Nucleus WidgetKit hosts, shared by macOS (widget-macos's prebuilt
// extension) and iOS (the sources an app adds to its widget extension, widget-ios). It draws the
// JSON tree `WidgetTreeEncoder` writes and turns clicks into `NucleusWidgetAction`, an AppIntent.
//
// Each platform file provides the hooks this file calls:
//   nucleusPerform(widget:key:signature:inputs:)  runs a click (macOS: through the app; iOS: in Kotlin)
//   nucleusLoadImage(_:)                          an image of the tree (`src`)
//   NucleusPlaceholderView                        what a widget shows before its first render

import AppIntents
import SwiftUI
import WidgetKit

#if os(macOS)
import AppKit
typealias PlatformImage = NSImage
extension Image {
    init(platformImage: NSImage) { self.init(nsImage: platformImage) }
}
#else
import UIKit
typealias PlatformImage = UIImage
extension Image {
    init(platformImage: UIImage) { self.init(uiImage: platformImage) }
}
#endif

// MARK: - Rendered tree (WidgetTreeEncoder)

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

// MARK: - Families

enum NucleusFamilies {
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
        // WidgetKit reloads the timeline once this returns: the platform waits for the new state.
        await nucleusPerform(widget: widget, key: key, signature: signature, inputs: inputs)
        return .result()
    }
}

// MARK: - Timeline entry

struct NucleusEntry: TimelineEntry {
    let date: Date
    let widget: String
    let tree: Tree?
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
            NucleusPlaceholderView()
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
            // A button drawn as a switch: the system switch style is a UIKit / AppKit control, which
            // WidgetKit replaces with its "unsupported view" placeholder. The click writes the
            // flipped value the tree carries (`a.in`), like a ChoiceField option.
            Button(intent: NucleusWidgetAction(widget: widget, action: node.a ?? NodeAction(k: nil, sig: nil, in: nil, url: nil))) {
                HStack(spacing: 8) {
                    Text(node.s ?? "")
                    Spacer(minLength: 0)
                    NucleusSwitch(isOn: node.on ?? false)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
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
        if let image = node.src.flatMap(nucleusLoadImage) {
            let picture = Image(platformImage: image).resizable().aspectRatio(contentMode: .fit)
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

/// A switch in plain SwiftUI shapes, the only kind of view WidgetKit draws.
struct NucleusSwitch: View {
    let isOn: Bool

    var body: some View {
        Capsule()
            .fill(isOn ? AnyShapeStyle(Color.green) : AnyShapeStyle(Color.secondary.opacity(0.3)))
            .frame(width: 40, height: 24)
            .overlay(alignment: isOn ? .trailing : .leading) {
                Circle().fill(Color.white).padding(2).shadow(radius: 1)
            }
            .accessibilityLabel(isOn ? "On" : "Off")
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
