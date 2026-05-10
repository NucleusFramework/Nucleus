// swift_view.swift
//
// SwiftUI demo for the sample-tao "SwiftUI" tab. Wraps a SwiftUI view in
// `NSHostingView` (Apple's official AppKit↔SwiftUI bridge) and exposes
// it as a raw `NSView*` pointer to Java via `@_cdecl`-exported C symbols.
//
// **No JNI shim.** The Java side calls these symbols directly via the
// Foreign Function & Memory API (`java.lang.foreign.*`, JEP 442/454) —
// no `JavaVM*`, no `JNIEnv*`, no Objective-C trampoline. Works the same
// way native code is consumed from a typical "modern Java + native
// library" application today.
//
// Built into `libsample_tao_swiftui.dylib`. Loaded by Java's `Linker`/
// `SymbolLookup` (see `SampleSwiftUIBridge.kt`).
//
// Threading: every entry point is intended for the macOS main thread
// (= Tao event-loop thread = Compose dispatcher thread). FFM doesn't
// add any thread checks; the caller is responsible.

import AppKit
import SwiftUI

// MARK: - Model

final class NucleusSampleModel: ObservableObject {
    @Published var counter: Int = 0
    @Published var hue: Double = 0.55
}

// MARK: - View

struct NucleusSampleSwiftUIView: View {
    @ObservedObject var model: NucleusSampleModel

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [
                    Color(hue: model.hue,           saturation: 0.55, brightness: 0.30),
                    Color(hue: (model.hue + 0.12).truncatingRemainder(dividingBy: 1.0),
                          saturation: 0.65, brightness: 0.45),
                ],
                startPoint: .topLeading,
                endPoint: .bottomTrailing,
            )
            VStack(spacing: 12) {
                Text("Hello from SwiftUI")
                    .font(.system(size: 22, weight: .semibold, design: .rounded))
                    .foregroundColor(.white)
                Text("counter = \(model.counter)")
                    .font(.system(size: 16, design: .monospaced))
                    .foregroundColor(.white.opacity(0.85))
                Image(systemName: "sparkles")
                    .font(.system(size: 28))
                    .foregroundColor(.white.opacity(0.9))
            }
        }
        .ignoresSafeArea()
    }
}

// MARK: - Handle (held alive by FFM caller via `Unmanaged.passRetained`)

final class NucleusSampleSwiftUIHandle {
    let model: NucleusSampleModel
    let host: NSHostingView<NucleusSampleSwiftUIView>

    init() {
        let m = NucleusSampleModel()
        self.model = m
        let view = NucleusSampleSwiftUIView(model: m)
        let h = NSHostingView(rootView: view)
        h.wantsLayer = true
        h.translatesAutoresizingMaskIntoConstraints = true
        // Compose owns the frame end-to-end via `nativeSetSubviewFrame`;
        // an autoresizingMask would let AppKit fight Compose's explicit
        // frame on every parent-bounds change.
        h.autoresizingMask = []
        self.host = h
    }
}

// MARK: - C-callable exports (consumed directly by Java FFM, no JNI shim)
//
// Each function is annotated `@_cdecl` so the Swift compiler emits the
// raw symbol names below in the dylib's symbol table. Java's
// `SymbolLookup.find(...)` resolves them; a `MethodHandle` is built
// from each via `Linker.downcallHandle` on the Java side.

@_cdecl("nucleus_sample_swiftui_create")
public func nucleus_sample_swiftui_create() -> UnsafeMutableRawPointer {
    let handle = NucleusSampleSwiftUIHandle()
    return Unmanaged.passRetained(handle).toOpaque()
}

@_cdecl("nucleus_sample_swiftui_view")
public func nucleus_sample_swiftui_view(_ ptr: UnsafeMutableRawPointer) -> UnsafeMutableRawPointer {
    let handle = Unmanaged<NucleusSampleSwiftUIHandle>.fromOpaque(ptr).takeUnretainedValue()
    return Unmanaged.passUnretained(handle.host).toOpaque()
}

@_cdecl("nucleus_sample_swiftui_set_counter")
public func nucleus_sample_swiftui_set_counter(_ ptr: UnsafeMutableRawPointer, _ value: Int32) {
    let handle = Unmanaged<NucleusSampleSwiftUIHandle>.fromOpaque(ptr).takeUnretainedValue()
    handle.model.counter = Int(value)
}

@_cdecl("nucleus_sample_swiftui_set_hue")
public func nucleus_sample_swiftui_set_hue(_ ptr: UnsafeMutableRawPointer, _ hue: Float) {
    let handle = Unmanaged<NucleusSampleSwiftUIHandle>.fromOpaque(ptr).takeUnretainedValue()
    let clamped = max(0.0, min(1.0, Double(hue)))
    handle.model.hue = clamped
}

@_cdecl("nucleus_sample_swiftui_release")
public func nucleus_sample_swiftui_release(_ ptr: UnsafeMutableRawPointer) {
    Unmanaged<NucleusSampleSwiftUIHandle>.fromOpaque(ptr).release()
}
