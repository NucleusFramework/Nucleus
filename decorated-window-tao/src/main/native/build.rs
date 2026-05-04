fn main() {
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("macos") {
        cc::Build::new()
            .file("macos/main_thread_dispatch.m")
            .file("macos/a11y.m")
            .file("macos/apple_events.m")
            .file("macos/window_drag.m")
            .flag("-fobjc-arc")
            .flag("-mmacosx-version-min=10.15")
            .compile("nucleus_tao_objc_helpers");
        println!("cargo:rustc-link-lib=framework=Cocoa");
        println!("cargo:rerun-if-changed=macos/main_thread_dispatch.m");
        println!("cargo:rerun-if-changed=macos/a11y.m");
        println!("cargo:rerun-if-changed=macos/apple_events.m");
        println!("cargo:rerun-if-changed=macos/window_drag.m");
    }
}
