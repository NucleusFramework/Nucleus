use std::{env, fs, path::{Path, PathBuf}, process::Command};

fn run(command: &mut Command) {
    let output = command.output().expect("failed to launch Android speech bridge compiler");
    assert!(output.status.success(), "Android speech bridge compilation failed:\n{}\n{}",
        String::from_utf8_lossy(&output.stdout), String::from_utf8_lossy(&output.stderr));
}

fn newest_child(root: &Path, required: &str) -> Option<PathBuf> {
    let mut paths: Vec<_> = fs::read_dir(root).ok()?
        .filter_map(Result::ok).map(|entry| entry.path())
        .filter(|path| path.join(required).is_file()).collect();
    paths.sort_by_key(|path| {
        path.file_name().unwrap().to_string_lossy().split(|c: char| !c.is_ascii_digit())
            .filter_map(|part| part.parse::<u32>().ok()).collect::<Vec<_>>()
    });
    paths.pop()
}

pub fn build() {
    println!("cargo:rerun-if-changed=java");
    for name in ["ANDROID_HOME", "ANDROID_SDK_ROOT", "ANDROID_PLATFORM", "ANDROID_BUILD_TOOLS_VERSION", "JAVA_HOME", "PATH"] {
        println!("cargo:rerun-if-env-changed={name}");
    }
    let unavailable = || println!("cargo:warning=Android native dictation disabled: an Android SDK, platform jar, d8, and JDK are required. Configure ANDROID_HOME and JAVA_HOME to enable it.");
    let Some(sdk) = env::var_os("ANDROID_HOME").or_else(|| env::var_os("ANDROID_SDK_ROOT")).map(PathBuf::from) else {
        unavailable();
        return;
    };
    let platform = env::var_os("ANDROID_PLATFORM").map(|name| sdk.join("platforms").join(name))
        .or_else(|| newest_child(&sdk.join("platforms"), "android.jar"));
    let build_tools = env::var_os("ANDROID_BUILD_TOOLS_VERSION").map(|name| sdk.join("build-tools").join(name))
        .or_else(|| newest_child(&sdk.join("build-tools"), "lib/d8.jar"));
    let java_bin = |name: &str| {
        let name = if cfg!(windows) { format!("{name}.exe") } else { name.to_owned() };
        env::var_os("JAVA_HOME").map(|root| PathBuf::from(root).join("bin").join(&name))
            .unwrap_or_else(|| PathBuf::from(name))
    };
    let (Some(platform), Some(build_tools)) = (platform, build_tools) else {
        unavailable();
        return;
    };
    if !platform.join("android.jar").is_file() || !build_tools.join("lib/d8.jar").is_file()
        || ["javac", "java"].iter().any(|name| {
            !Command::new(java_bin(name)).arg("-version").output().is_ok_and(|output| output.status.success())
        })
    {
        unavailable();
        return;
    }
    let output = PathBuf::from(env::var_os("OUT_DIR").unwrap());
    let classes = output.join("speech-java");
    if classes.exists() { fs::remove_dir_all(&classes).unwrap(); }
    fs::create_dir_all(&classes).unwrap();
    run(Command::new(java_bin("javac")).args(["-source", "8", "-target", "8", "-classpath"])
        .arg(platform.join("android.jar")).arg("-d").arg(&classes)
        .arg("java/dev/robius/speech/NativeSpeech.java")
        .arg("java/dev/robius/speech/SpeechPermissionFragment.java"));
    let mut class_files: Vec<_> = fs::read_dir(classes.join("dev/robius/speech")).unwrap()
        .filter_map(Result::ok).map(|entry| entry.path())
        .filter(|path| path.extension().is_some_and(|ext| ext == "class")).collect();
    class_files.sort();
    run(Command::new(java_bin("java")).arg("-cp").arg(build_tools.join("lib/d8.jar"))
        .args(["com.android.tools.r8.D8", "--min-api", "26", "--lib"])
        .arg(platform.join("android.jar")).arg("--output").arg(&output).args(class_files));
    println!("cargo:rustc-cfg=native_speech_android");
}
