fn main() {
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("macos") {
        cc::Build::new()
            .file("objc/main_thread_dispatch.m")
            .file("objc/a11y.m")
            .flag("-fobjc-arc")
            .flag("-mmacosx-version-min=10.15")
            .compile("nucleus_tao_objc_helpers");
        println!("cargo:rustc-link-lib=framework=Cocoa");
        println!("cargo:rerun-if-changed=objc/main_thread_dispatch.m");
        println!("cargo:rerun-if-changed=objc/a11y.m");
    }
}
