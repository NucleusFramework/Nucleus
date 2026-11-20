use std::{env, path::PathBuf, process::Command};

mod android_build;

fn main() {
    println!("cargo:rustc-check-cfg=cfg(native_speech_android)");
    let target_os = env::var("CARGO_CFG_TARGET_OS").unwrap();
    if target_os == "android" {
        android_build::build();
        return;
    }
    if target_os != "macos" && target_os != "ios" { return; }
    apple_build(&target_os);
}

/// Compiles and links the Swift bridge. Dictation ships in every Apple build, so
/// a missing Swift toolchain fails the build rather than quietly dropping the
/// feature on you.
fn apple_build(target_os: &str) {
    // The iOS SDKs only come with full Xcode, whereas macOS needs nothing more
    // than the command line tools. Suggest whichever one fits the target.
    let install_hint = if target_os == "macos" {
        "Install the Xcode command line tools with `xcode-select --install`."
    } else {
        "The iOS SDK ships only with full Xcode. Install it, then select it with \
         `sudo xcode-select -s /Applications/Xcode.app`."
    };
    // The toolchain is missing or misconfigured, so installing it will fix things.
    let missing_toolchain = |reason: &str| -> ! {
        panic!("robius-speech could not build its Swift bridge: {reason}.\n\
            Native dictation is part of every Apple build. {install_hint}\n\
            `xcode-select -p` prints which developer directory is currently selected.");
    };
    // The toolchain ran fine and rejected our own source, so installing
    // something won't help. Don't send anyone off to the App Store for this.
    let bridge_failed = |reason: &str| -> ! {
        panic!("robius-speech could not build its Swift bridge: {reason}");
    };

    println!("cargo:rerun-if-changed=swift/NativeSpeech.swift");
    for key in ["MACOSX_DEPLOYMENT_TARGET", "IPHONEOS_DEPLOYMENT_TARGET", "IPHONESIMULATOR_DEPLOYMENT_TARGET", "DEVELOPER_DIR", "SDKROOT"] {
        println!("cargo:rerun-if-env-changed={key}");
    }
    let out = PathBuf::from(env::var_os("OUT_DIR").unwrap());
    let arch = match env::var("CARGO_CFG_TARGET_ARCH").unwrap().as_str() {
        "aarch64" => "arm64",
        "x86_64" => "x86_64",
        other => bridge_failed(&format!("unsupported Apple architecture {other}")),
    };
    let simulator = target_os == "ios" && (env::var("CARGO_CFG_TARGET_ABI").as_deref() == Ok("sim") || arch == "x86_64");
    let (sdk, deployment_key, default_deployment, platform) = match (target_os, simulator) {
        ("macos", _) => ("macosx", "MACOSX_DEPLOYMENT_TARGET", "11.0", "macosx"),
        (_, true) => ("iphonesimulator", "IPHONESIMULATOR_DEPLOYMENT_TARGET", "13.0", "ios"),
        _ => ("iphoneos", "IPHONEOS_DEPLOYMENT_TARGET", "13.0", "ios"),
    };
    let configured_deployment = env::var(deployment_key).ok();
    if target_os == "macos" && arch == "x86_64" && configured_deployment.is_none() {
        panic!("robius-speech requires macOS 11.0 or newer. Set MACOSX_DEPLOYMENT_TARGET=11.0 (or newer) for the entire Cargo invocation, including the final executable. Rust's default Intel deployment target is too old for this Swift bridge.");
    }
    let deployment = configured_deployment.unwrap_or_else(|| default_deployment.into());
    if target_os == "macos" {
        let major = deployment.split('.').next().and_then(|part| part.parse::<u32>().ok()).unwrap_or(0);
        assert!(major >= 11, "robius-speech requires MACOSX_DEPLOYMENT_TARGET=11.0 or newer for the entire Cargo invocation");
    }
    let target = format!("{arch}-apple-{platform}{deployment}{}", if simulator { "-simulator" } else { "" });
    let Ok(sdk_result) = Command::new("xcrun").args(["--sdk", sdk, "--show-sdk-path"]).output() else {
        missing_toolchain("xcrun is not installed");
    };
    if !sdk_result.status.success() {
        missing_toolchain(&format!("cannot locate the {sdk} SDK: {}", String::from_utf8_lossy(&sdk_result.stderr).trim()));
    }
    let sdk_path = String::from_utf8(sdk_result.stdout).unwrap();
    let sdk_path = sdk_path.trim();
    let library = out.join("librobius_speech.a");
    let result = Command::new("xcrun").args([
        "--sdk", sdk, "swiftc", "-swift-version", "5", "-O", "-emit-library", "-static", "-parse-as-library",
        "-module-name", "RobiusSpeech", "-target", &target, "-sdk", sdk_path,
        "-module-cache-path",
    ]).arg(out.join("swift-module-cache")).arg("swift/NativeSpeech.swift").arg("-o").arg(&library).output();
    let Ok(result) = result else { missing_toolchain("the Swift compiler is not installed") };
    if !result.status.success() {
        bridge_failed(&format!("the Swift bridge did not compile:\n{}", String::from_utf8_lossy(&result.stderr).trim()));
    }
    println!("cargo:rustc-link-search=native={}", out.display());
    println!("cargo:rustc-link-search=native={sdk_path}/usr/lib/swift");
    println!("cargo:rustc-link-search=native=/usr/lib/swift");
    println!("cargo:rustc-link-lib=static=robius_speech");
    for framework in ["Speech", "AVFoundation", "Foundation"] {
        println!("cargo:rustc-link-lib=framework={framework}");
    }
    // Swift's compiler supplies compatibility libraries from its toolchain.
    let Ok(info) = Command::new("xcrun").args(["--sdk", sdk, "swiftc", "-target", &target, "-sdk", sdk_path, "-print-target-info"]).output() else {
        missing_toolchain("the Swift compiler is not installed");
    };
    if !info.status.success() {
        missing_toolchain("cannot query the Swift runtime search paths");
    }
    for line in String::from_utf8_lossy(&info.stdout).lines() {
        let path = line.trim().trim_end_matches(',').trim_matches('"');
        if path.starts_with('/') && path.contains("/lib/swift") {
            println!("cargo:rustc-link-search=native={path}");
        }
    }
}
