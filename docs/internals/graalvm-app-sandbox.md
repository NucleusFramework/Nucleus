# GraalVM native image under the macOS App Sandbox

A macOS App Store PKG of a GraalVM app (`graalvm { isEnabled = true }` + `TargetFormat.Pkg` with
`macOS { pkg { appStore = true } }`) is packaged from a **second, sandboxed image**, built beside
the regular one only when such a format is configured. The regular image, its command line and its
inputs are unchanged; DMG, ZIP and Developer ID PKG keep using it.

## Why a regular image cannot run sandboxed

Three things kill or cripple a regular native image under `com.apple.security.app-sandbox`:

1. **Signal handling.** At start-up Substrate's `SunMiscSupport` creates the named POSIX semaphore
   `/cSunMiscSignal-<pid>`; the sandbox denies `ipc-posix-sem-create` and the image aborts before
   `main`. The sandboxed image is compiled with `-R:-EnableSignalHandling` (a runtime option baked
   as the default). Consequence: **SIGINT / SIGTERM do not run shutdown hooks** in the sandboxed
   image. A store app quits through the app menu / `requestQuit`, which is unaffected.
2. **`user.home`.** SubstrateVM reads it from the password database (`/Users/<name>`), where
   HotSpot follows `HOME` — the container. Every `~/Library/…` path then lands outside the
   container and is denied. `GraalVmInitializer` sets `user.home` from `HOME` when
   `APP_SANDBOX_CONTAINER_ID` is present.
3. **Native code extracted at run time** (JNA's `jnidispatch`, zstd-kmp, compose-webview, …) is
   quarantined inside the sandbox and refused by Gatekeeper behind a prompt. The JVM store pipeline
   already solves this; the GraalVM one reuses it.

## Pipeline (`configureGraalvmApplication.kt`, `registerSandboxedMacGraalvmApp`)

| Task | Does |
|---|---|
| `stripGraalvmNativeLibsForSandboxing` | `AbstractStripNativeLibsFromJarsTask` on the stripped uber JAR: third-party native libs replaced by markers, their `System.load` call sites rewritten to `NucleusSandboxLoader`, redirect manifest written |
| `stageGraalvmSandboxedJars` | rewritten JAR as `app.jar` + `nucleus-sandbox-shim.jar` |
| `extractGraalvmNativeLibsForSandboxing` | the same libs, extracted at build time for the target arch |
| `nativeImageCompileSandboxed` | `-cp <shim> -jar app.jar -R:-EnableSignalHandling` into `graalvm/nativeCompileSandboxed` |
| `createSandboxedGraalvmNativeDistributable` | `AbstractAssembleSandboxedGraalvmMacAppTask`: `ditto` of the regular `.app`, sandboxed binary swapped in (build-version patch, rpath, `strip -x`), libs signed in `Contents/Frameworks`, manifest in `Contents/Resources`, provisioning profile embedded, ad hoc signature with the sandbox entitlements |
| `runSandboxedGraalvmNativeDistributable` | runs it |

The App Store PKG task packages that bundle and re-signs it with the real identity, like the JVM
store pipeline. Flatpak (the other sandboxed format) stays unsupported for GraalVM.

## Run time (`GraalVmInitializer.applyMacOsAppSandbox`)

The sandboxed bundle is recognized by `Contents/Resources/nucleus-sandbox-manifest.properties`
(a data file, kept out of `Contents/MacOS` / `Contents/Frameworks`, where Gatekeeper expects only
code). Then, without overriding any explicit setting: `Contents/Frameworks` joins
`java.library.path`, `nucleus.sandbox.manifest` points the shim at the manifest, and JNA is told
not to unpack (`jna.nounpack`, `jna.nosys`, `jna.boot.library.path`, `jna.library.path`) — the same
properties the sandboxed JVM launcher passes. Any other bundle, and any unsandboxed run, is left
untouched.

## Verified (Lab, macOS 27, arm64, GraalVM CE 25.4)

Launched through LaunchServices with the default sandbox entitlements: the Lab reports
`sandboxed`, `checks.json` lands in `~/Library/Containers/<bundle id>/Data/Library/Application
Support/NucleusLab` and survives a restart, and the four libraries extracted at build time load from
`Contents/Frameworks` — `libjnidispatch` (Jewel), `libzstd-kmp` (`system.zstd`: round trip
identical, the image resource is the marker), `libcompose_webview_macos` (`rendering.webview`),
`libMacTray` (`shell.tray`). No file access is denied (only `system-info vfs.disk-space`, the
`system.info` probe's own business). `packageGraalvmPkg` packages this bundle.

**Test-launch trap**: start the sandboxed binary by its absolute path (or `open`). Started as
`./nucleus-lab`, the image inside the sandbox resolves its executable directory wrong, the font
configuration is not found, and composition dies with "Fontconfig head is null". Finder, `open` and
the Dock always pass the absolute path.

**Memory**: the sandboxed variant doubles native-image work (two compiles, ~8.5 GiB each, run one
after the other). To iterate on packaging only, exclude both compiles:
`-x :app:nativeImageCompile -x :app:nativeImageCompileSandboxed`.
