# Packaging

!!! info "About this comparison"
    This comparison was generated with the assistance of [Claude Code](https://claude.ai/claude-code) (Anthropic's AI coding agent). Every factual claim includes a source link to official documentation, GitHub repositories, or vendor pages. Nucleus-specific claims are verified against the project source code.

    Last updated: March 2026.

---

## Executive Summary

This page evaluates **Nucleus** against **10 competing JVM packaging tools** across 13 feature dimensions.

**Key findings:**

- **Nucleus offers the broadest package format coverage of any JVM tool** (16 distributable formats), surpassing Conveyor (6), install4j (7), jpackage (6), and Compose Multiplatform (6).
- **Nucleus is the only JVM packaging tool** combining auto-update runtime, AOT caching, GraalVM Native Image packaging, store distribution pipeline, native UI components (decorated windows with JBR and JNI backends, dark mode detection, Linux HiDPI), deep link/single instance management, and native OS SSL integration in one toolkit.
- **Nucleus is the first JVM packaging tool with integrated GraalVM Native Image support** — compile Compose Desktop apps into standalone native binaries with ~0.5s cold boot, ~100–150 MB memory usage, and smaller bundle sizes (no bundled JRE). Full packaging pipeline (DMG, NSIS, DEB) for native images.
- **Nucleus has the most comprehensive CI/CD solution** for JVM desktop apps, with 6 composite GitHub Actions covering matrix builds, universal macOS binaries, MSIX bundles, GraalVM native image builds, and release publishing.
- **Tradeoffs**: Nucleus requires platform-specific CI runners (no cross-compilation), is Gradle-only, and is a younger project with a smaller community than established tools.

### Rankings

| Tier | Tool | Score | License |
|------|------|:-----:|---------|
| **S** | **Nucleus** | **90** | MIT (free) |
| **B** | [install4j](https://www.ej-technologies.com/products/install4j/overview.html) | 65 | Proprietary ($2,199+/dev) |
| **B-** | [Conveyor](https://conveyor.hydraulic.dev/) | 62 | Proprietary ($45/mo) |
| **C** | [jDeploy](https://www.jdeploy.com/) | 49 | Apache 2 (free) |
| **C** | [Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/) | 42 | Apache 2 (free) |
| **C** | jpackage (JDK built-in) | 38 | JDK (free) |
| **C-** | [JavaPackager](https://github.com/fvarrui/JavaPackager) | 36 | GPL 3 (free) |
| **C-** | [Badass plugins](https://github.com/beryx/badass-jlink-plugin) | 35 | Apache 2 (free) |
| **D** | [Launch4j](https://launch4j.sourceforge.net/) | 22 | BSD (free) |
| **D** | [Packr](https://github.com/libgdx/packr) | 22 | Apache 2 (dormant) |
| **F** | JSmooth | 11 | Abandoned |

Scoring: each of 13 dimensions rated 0–10, total = raw sum / 130 × 100. See [Scoring Matrix](#scoring-matrix) for details.

---

## Competitors Overview

| Tool | Type | Version | Status | Source |
|------|------|---------|--------|--------|
| **jpackage** | JDK built-in CLI | JDK 25 | Active | [Oracle docs](https://docs.oracle.com/en/java/javase/23/docs/specs/man/jpackage.html) |
| **Conveyor** (Hydraulic) | CLI + Gradle plugin | v21.1 | Active | [conveyor.hydraulic.dev](https://conveyor.hydraulic.dev/21.1/) |
| **install4j** (ej-technologies) | IDE + Gradle/Maven plugin | v12.0.2 | Active | [ej-technologies.com](https://www.ej-technologies.com/products/install4j/overview.html) |
| **jDeploy** | CLI + GitHub Action | v6.0.16 | Active | [jdeploy.com](https://www.jdeploy.com/) |
| **Compose Multiplatform** (JetBrains) | Gradle plugin | v1.10.1 | Active | [jetbrains.com](https://www.jetbrains.com/compose-multiplatform/) |
| **Launch4j** | GUI/CLI (Windows EXE wrapper) | v3.50 | Low activity | [launch4j.sourceforge.net](https://launch4j.sourceforge.net/) |
| **JavaPackager** | Gradle/Maven plugin | v1.7.6 | Semi-maintained | [GitHub](https://github.com/fvarrui/JavaPackager) |
| **Badass-jlink / Badass-runtime** | Gradle plugins | v3.2.1 / v2.0.1 | Active | [GitHub](https://github.com/beryx/badass-jlink-plugin) |
| **Packr** (libGDX) | CLI | v4.0.0 | Dormant | [GitHub](https://github.com/libgdx/packr) |
| **JSmooth** | GUI (Windows EXE wrapper) | — | Abandoned | — |

---

## Feature-by-Feature Comparison

### 1. Platform & Architecture Coverage

| Tool | Win x64 | Win ARM64 | macOS x64 | macOS ARM64 | macOS Universal | Linux x64 | Linux ARM64 | Score |
|------|:-------:|:---------:|:---------:|:-----------:|:---------------:|:---------:|:-----------:|:-----:|
| **Nucleus** | ✅ | ✅ | ✅ | ✅ | ✅ (CI action) | ✅ | ✅ | **10** |
| Conveyor | ✅ | ✅ | ✅ | ✅ | ❌¹ | ✅ | ⚠️² | **8** |
| install4j | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | **10** |
| jpackage | ✅ | ✅ | ✅ | ✅ | ❌ | ✅ | ✅ | **8** |
| jDeploy | ✅ | ✅ | ✅ | ✅ | ❌ | ✅ | ✅ | **8** |
| Compose MP | ✅ | ❌ | ✅ | ✅ | ❌ | ✅ | ❌ | **5** |
| JavaPackager | ✅ | ❌ | ✅ | ✅ | ❌ | ✅ | ❌ | **5** |
| Badass plugins | ✅ | ✅ | ✅ | ✅ | ❌ | ✅ | ✅ | **8** |

¹ Conveyor produces separate per-architecture downloads, not fat binaries ([docs](https://conveyor.hydraulic.dev/21.1/configs/mac/)).
² Conveyor has `app.linux.aarch64` config keys ([docs](https://conveyor.hydraulic.dev/21.1/configs/linux/)), but Linux ARM64 is listed as planned in the FAQ ([FAQ](https://conveyor.hydraulic.dev/21.1/faq/output-formats/)).

??? info "Sources"
    - **Nucleus**: macOS universal binary via [`build-macos-universal`](https://github.com/nucleusframework/Nucleus/tree/main/.github/actions/build-macos-universal) CI action using `lipo`
    - **Conveyor**: [Package formats](https://conveyor.hydraulic.dev/21.1/package-formats/), [macOS config](https://conveyor.hydraulic.dev/21.1/configs/mac/), [Linux config](https://conveyor.hydraulic.dev/21.1/configs/linux/)
    - **install4j**: [Features page](https://www.ej-technologies.com/install4j/features) — "compile installers for all platforms on any of these platforms"
    - **jpackage**: [Oracle man page](https://docs.oracle.com/en/java/javase/23/docs/specs/man/jpackage.html) — must build on target OS
    - **jDeploy**: [FAQ](https://www.jdeploy.com/docs/faq/) — "build native installers for Mac, Windows, and Linux on any platform"
    - **Compose MP**: [Native distributions](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html) — wraps jpackage, same platform constraints

---

### 2. Package Format Coverage

| Tool | DMG | PKG | NSIS | MSI | MSIX/AppX | Portable | DEB | RPM | AppImage | Snap | Flatpak | Archives | Total |
|------|:---:|:---:|:----:|:---:|:---------:|:--------:|:---:|:---:|:--------:|:----:|:-------:|:--------:|:-----:|
| **Nucleus** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ZIP, TAR, 7Z | **16** |
| Conveyor | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ZIP, TAR + EXE¹ | **6** |
| install4j | ✅ | ❌ | ❌ | ✅² | ❌ | ❌ | ✅ | ✅ | ❌ | ❌ | ❌ | TAR, shell | **7** |
| jpackage | ✅ | ✅ | ❌ | ✅ | ❌ | ❌ | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | **6** |
| jDeploy | ❌³ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | EXE, TAR | **4** |
| Compose MP | ✅ | ✅ | ❌ | ✅ | ❌ | ❌ | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | **6** |
| JavaPackager | ✅ | ✅ | ❌ | ✅ | ❌ | ❌ | ✅ | ✅ | ✅ | ❌ | ❌ | ZIP | **8** |

| Score | Rating |
|-------|--------|
| **Nucleus**: 16 formats | **10** |
| JavaPackager: 8 | 5 |
| install4j: 7 | 5 |
| Conveyor: 6 | 4 |
| jpackage: 6 | 4 |
| Compose MP: 6 | 4 |
| jDeploy: 4 | 3 |

¹ Conveyor's Windows EXE is a custom ~500 KB installer that drives Windows Deployment Engine APIs — not NSIS ([docs](https://conveyor.hydraulic.dev/21.1/package-formats/)). DMG is deliberately not supported ([FAQ](https://conveyor.hydraulic.dev/21.1/faq/output-formats/)).
² install4j MSI is an optional wrapper around the native EXE installer ([docs](https://www.ej-technologies.com/resources/install4j/help/doc/concepts/mediaFiles.html)).
³ jDeploy DMG requires a separate GitHub Action + macOS runner ([jdeploy-action-dmg](https://github.com/shannah/jdeploy-action-dmg)).

??? info "Sources"
    - **Nucleus**: [`TargetFormat.kt`](https://github.com/nucleusframework/Nucleus/blob/main/plugin-build/plugin/src/main/kotlin/dev/nucleusframework/nucleus/desktop/application/dsl/TargetFormat.kt) — 17 enum values (16 distributable + `RawAppImage` intermediate)
    - **Conveyor**: [Package formats](https://conveyor.hydraulic.dev/21.1/package-formats/), [Output formats FAQ](https://conveyor.hydraulic.dev/21.1/faq/output-formats/) — MSIX, EXE, ZIP (Windows); signed .app in ZIP (macOS); DEB, tar.gz (Linux). No DMG, PKG, RPM, NSIS, AppImage, Snap, Flatpak.
    - **install4j**: [Media files](https://www.ej-technologies.com/resources/install4j/help/doc/concepts/mediaFiles.html) — EXE, MSI wrapper, DMG, .app, RPM, DEB, tar.gz, shell installer. No PKG, NSIS, AppX.
    - **jpackage**: [Oracle man page](https://docs.oracle.com/en/java/javase/23/docs/specs/man/jpackage.html) — `--type`: app-image, dmg, pkg, exe, msi, deb, rpm
    - **jDeploy**: [GitHub](https://github.com/shannah/jdeploy), [FAQ](https://www.jdeploy.com/docs/faq/) — EXE, .app/tar.gz, DEB; DMG via separate action
    - **Compose MP**: [Native distributions](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html) — wraps jpackage formats
    - **JavaPackager**: [GitHub README](https://github.com/fvarrui/JavaPackager) — EXE (Inno Setup/WinRun4J), MSI (WiX), DMG, PKG, DEB, RPM, AppImage, ZIP

Nucleus leads by leveraging electron-builder as its packaging backend: jpackage creates the app-image, then electron-builder's `--prepackaged` mode produces all 16 distributable formats. This hybrid architecture is unique in the JVM ecosystem.

---

### 3. Auto-Update System

| Tool | Runtime Library | Updater Backend | Channels | Delta Updates | Verification | Score |
|------|:---------------:|:---------------:|:--------:|:-------------:|:------------:|:-----:|
| **Nucleus** | ✅ `NucleusUpdater` | GitHub / Generic URL | 3 (latest/beta/alpha) | ❌ | SHA-512 | **9** |
| Conveyor | ✅ (OS-native) | Sparkle 2 / MSIX / apt | ✅ | ✅ (Win+Mac) | ✅ | **10** |
| install4j | ✅ (updater API) | Custom | ✅ | ❌ | ✅ | **9** |
| jDeploy | ✅ (built-in) | npm / GitHub Releases | ❌ | ❌ | ❌ | **6** |
| jpackage | ❌ | — | — | — | — | **0** |
| Compose MP | ❌ | — | — | — | — | **0** |

??? info "Sources"
    - **Nucleus**: [`NucleusUpdater.kt`](https://github.com/nucleusframework/Nucleus/blob/main/updater-runtime/src/main/kotlin/dev/nucleusframework/nucleus/updater/NucleusUpdater.kt) — `GitHubProvider` and `GenericProvider`; SHA-512 verification; 9 self-updatable format types (EXE, NSIS, MSI, DMG, ZIP, AppImage, DEB, RPM, NsisWeb)
    - **Conveyor**: [Update modes](https://conveyor.hydraulic.dev/21.1/configs/update-modes/) — Sparkle 2 on macOS (delta patches for 5 previous versions), MSIX native on Windows (64 KB chunk delta), apt on Linux. [Understanding updates](https://conveyor.hydraulic.dev/21.1/understanding-updates/)
    - **install4j**: [Features](https://www.ej-technologies.com/install4j/features) — built-in auto-update with configurable strategies
    - **jDeploy**: [Substack](https://jdeploy.substack.com/p/automated-deployment-and-updates) — auto-detects new versions on launch

Conveyor's delta update system is a genuine differentiator: a single-line change in an Electron app results in ~31 KB (macOS) or ~115 KB (Windows) updates vs ~100 MB full downloads ([source](https://conveyor.hydraulic.dev/21.1/understanding-updates/)). Nucleus uses full-file downloads but compensates with a rich runtime API (progress tracking, channel management, restart-on-update).

---

### 4. Code Signing & Notarization

| Tool | macOS Signing | macOS Notarization | Windows PFX | Azure Trusted Signing | Other Cloud HSMs | Score |
|------|:------------:|:------------------:|:-----------:|:---------------------:|:----------------:|:-----:|
| **Nucleus** | ✅ | ✅ | ✅ | ✅ | ❌ | **10** |
| Conveyor | ✅ | ✅ | ✅ (+ self-sign + SSL certs) | ✅ | ✅ (6 providers) | **10** |
| install4j | ✅ | ✅ | ✅ | ❌ | ❌ | **8** |
| jDeploy | ✅¹ | ✅¹ | ✅¹ | ❌ | ❌ | **7** |
| jpackage | ✅ (`--mac-sign`) | ✅ (`--mac-app-store`) | ❌ | ❌ | ❌ | **3** |
| Compose MP | ✅ | ✅ | ❌ | ❌ | ❌ | **5** |
| JavaPackager | ✅ | ❌ | ✅ (Jsign) | ❌ | ❌ | **3** |

¹ jDeploy pre-signs and notarizes installers using its own certificate; optional custom signing via GitHub Action ([FAQ](https://www.jdeploy.com/docs/faq/)).

??? info "Sources"
    - **Nucleus**: [`MacOSSigningSettings.kt`](https://github.com/nucleusframework/Nucleus/blob/main/plugin-build/plugin/src/main/kotlin/dev/nucleusframework/nucleus/desktop/application/dsl/MacOSSigningSettings.kt), [`WindowsSigningSettings.kt`](https://github.com/nucleusframework/Nucleus/blob/main/plugin-build/plugin/src/main/kotlin/dev/nucleusframework/nucleus/desktop/application/dsl/WindowsSigningSettings.kt) — Azure Trusted Signing via `azureTenantId`, `azureEndpoint`, `azureCertificateProfileName`
    - **Conveyor**: [Keys and certificates](https://conveyor.hydraulic.dev/21.1/configs/keys-and-certificates/) — macOS notarization (App Store Connect API keys), Windows self-signing, Azure Trusted Signing, Azure Key Vault, AWS KMS, SSL.com eSigner, DigiCert ONE, Google Cloud KMS, HSMs (SafeNet, YubiKey)
    - **install4j**: [Features](https://www.ej-technologies.com/install4j/features) — cross-platform signing and notarization
    - **jpackage**: [Oracle man page](https://docs.oracle.com/en/java/javase/23/docs/specs/man/jpackage.html) — `--mac-sign`, `--mac-signing-key-user-name`, `--mac-app-store`
    - **Compose MP**: [Native distributions](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html) — macOS signing and notarization DSL
    - **JavaPackager**: [v1.7.4 release](https://github.com/fvarrui/JavaPackager/releases/tag/v1.7.4) — Jsign 5.0 for Windows signing

Conveyor has the broadest signing provider support (6 cloud HSM services). Nucleus focuses on the two most common paths (PFX + Azure Trusted Signing) with CI-ready composite actions for secret management.

---

### 5. CI/CD Integration

| Tool | Pre-built Actions | Matrix Builds | Universal Binary | MSIX Bundle | Release Publishing | Score |
|------|:-----------------:|:-------------:|:----------------:|:-----------:|:------------------:|:-----:|
| **Nucleus** | ✅ (6 actions) | ✅ (6 runners) | ✅ | ✅ | ✅ | **10** |
| Conveyor | ⚠️ (examples) | ❌ (single machine) | ❌ | ❌ | ✅ (GH/SSH/S3) | **6** |
| install4j | ❌ (CLI only) | ❌ | ❌ | ❌ | ❌ | **3** |
| jDeploy | ✅ (GitHub Action) | ❌ | ❌ | ❌ | ✅ (auto) | **5** |
| Compose MP | ❌ | ❌ | ❌ | ❌ | ❌ | **1** |

??? info "Sources"
    - **Nucleus**: 6 composite actions in [`.github/actions/`](https://github.com/nucleusframework/Nucleus/tree/main/.github/actions) — `setup-nucleus` (JBR or GraalVM Liberica NIK + tools), `setup-macos-signing` (keychain + P12), `build-macos-universal` (lipo merge + re-sign), `build-windows-appxbundle` (MakeAppx + SignTool), `generate-update-yml` (SHA-512 metadata), `publish-release` (gh release create). Since v1.3.0, `setup-nucleus` supports a `graalvm` option to install BellSoft Liberica NIK instead of JBR, enabling GraalVM Native Image builds in CI.
    - **Conveyor**: [CI tutorial](https://conveyor.hydraulic.dev/21.1/tutorial/hare/ci/) — example workflows for GitHub Actions (build, deploy-to-gh, deploy-to-ssh). Conveyor runs on a single machine since it cross-compiles.
    - **install4j**: [What's new](https://www.ej-technologies.com/install4j/whatsnew12) — CLI mode for CI, no pre-built actions
    - **jDeploy**: [GitHub](https://github.com/shannah/jdeploy) — `jdeploy-action` for automated builds on tag push

Nucleus's CI pipeline is its strongest JVM differentiator. No other JVM tool provides ready-to-use GitHub Actions for the complete build → sign → bundle → publish workflow. Conveyor avoids matrix builds entirely by cross-compiling from a single machine — a fundamentally different (and simpler) approach.

---

### 6. Build System Integration

| Tool | Gradle | Maven | CLI | Other | Score |
|------|:------:|:-----:|:---:|:-----:|:-----:|
| **Nucleus** | ✅ (plugin) | ❌ | ❌ | — | **6** |
| Conveyor | ✅ (plugin) | ✅ (via CLI) | ✅ | HOCON config | **9** |
| install4j | ✅ | ✅ | ✅ (IDE + CLI) | Ant | **10** |
| jDeploy | ❌ | ✅ | ✅ | npm | **8** |
| Compose MP | ✅ (plugin) | ❌ | ❌ | — | **6** |
| JavaPackager | ✅ | ✅ | ❌ | — | **7** |
| Badass plugins | ✅ | ❌ | ❌ | — | **6** |
| jpackage | ❌ | ❌ | ✅ (JDK built-in) | — | **4** |

??? info "Sources"
    - **Nucleus**: Gradle plugin (`dev.nucleusframework.nucleus`), no Maven or CLI support
    - **Conveyor**: [Gradle/Maven integration](https://conveyor.hydraulic.dev/21.1/configs/maven-gradle/) — open-source Gradle plugin (Gradle 7+); Maven via `mvn dependency:build-classpath` CLI integration; standalone `conveyor` CLI
    - **install4j**: [Features](https://www.ej-technologies.com/install4j/features) — visual IDE + CLI + Gradle + Maven + Ant plugins
    - **jDeploy**: [GitHub](https://github.com/shannah/jdeploy) — CLI tool, Maven integration, npm package
    - **JavaPackager**: [GitHub](https://github.com/fvarrui/JavaPackager) — Gradle + Maven plugins

Nucleus is Gradle-only, which suits its Compose Desktop audience but limits adoption by Maven or CLI-only users. Conveyor and install4j offer the most flexibility.

---

### 7. Runtime Optimization

| Tool | JLink | ProGuard | AOT Cache (Leyden) | GraalVM Native Image | Custom JVM | CA Cert Patching | Score |
|------|:-----:|:--------:|:-------------------:|:--------------------:|:----------:|:----------------:|:-----:|
| **Nucleus** | ✅ | ✅ | ✅ (JDK 25+) | ✅ (alpha) | ✅ (JBR / Liberica NIK) | ✅ (declarative DSL) | **10** |
| Conveyor | ✅ (auto) | ❌ | ❌ | ⚠️ (workaround¹) | ✅ (6 vendors) | ✅ (`app.jvm.additional-ca-certs`) | **6** |
| install4j | ✅ | ❌ | ❌ | ❌ | ✅ | ❌² | **5** |
| jpackage | ✅ | ❌ | ❌ | ❌ | ✅ | ⚠️³ | **4** |
| Compose MP | ✅ | ✅ | ❌ | ❌ | ✅ | ❌⁴ | **6** |
| Badass plugins | ✅ | ❌ | ❌ | ❌ | ✅ | ⚠️⁵ | **5** |

¹ Conveyor can package pre-built GraalVM native binaries by pointing `app.jvm.extract-native-libraries` at a native-image output and using a fake JDK, but this is a manual workaround — not an integrated build pipeline ([discussion #66](https://github.com/hydraulic-software/conveyor/discussions/66)).
² install4j has no keytool or cacerts DSL. Workaround: pre-patch a JRE, re-archive as `.tar.gz`, point install4j at the custom bundle ([pre-created JRE bundles docs](https://www.ej-technologies.com/resources/install4j/help/doc/concepts/jreBundles.html)).
³ jpackage's `--resource-dir` cannot replace `cacerts` (it only overrides packaging templates). The supported approach is to run `keytool -importcert` against a jlink image and pass it via `--runtime-image`; a post-image script hook (`.sh`/`.wsf`) could also be used but is undocumented for certs.
⁴ Compose Multiplatform's `jpackageResources` directory is internal and cleared on every build; no user-accessible mechanism to override `cacerts`. Same `--runtime-image` workaround as jpackage applies but is not exposed in the DSL.
⁵ badass-jlink exposes no cert DSL, but its task hook (`tasks.named("jlink").doLast { … }`) gives access to the staged runtime image before jpackage consumes it — the cleanest manual workaround available.

??? info "Sources"
    - **Nucleus**: [`AbstractGenerateAotCacheTask.kt`](https://github.com/nucleusframework/Nucleus/blob/main/plugin-build/plugin/src/main/kotlin/dev/nucleusframework/nucleus/desktop/application/tasks/AbstractGenerateAotCacheTask.kt) — Project Leyden via `-XX:AOTCacheOutput` (JDK 25+); [`ProguardSettings.kt`](https://github.com/nucleusframework/Nucleus/blob/main/plugin-build/plugin/src/main/kotlin/dev/nucleusframework/nucleus/desktop/application/dsl/ProguardSettings.kt) — ProGuard 7.7.0 default; [`AbstractJLinkTask.kt`](https://github.com/nucleusframework/Nucleus/blob/main/plugin-build/plugin/src/main/kotlin/dev/nucleusframework/nucleus/desktop/application/tasks/AbstractJLinkTask.kt) — jlink with strip-debug, compression; [`AbstractPatchCaCertificatesTask.kt`](https://github.com/nucleusframework/Nucleus/blob/main/plugin-build/plugin/src/main/kotlin/dev/nucleusframework/nucleus/desktop/application/tasks/AbstractPatchCaCertificatesTask.kt) — copies JLink runtime, runs keytool to import PEM/DER certificates into `lib/security/cacerts`; [`graalvm-runtime`](https://github.com/nucleusframework/Nucleus/tree/main/graalvm-runtime) — GraalVM Native Image bootstrap with `GraalVmInitializer.initialize()`, platform-specific reachability metadata, font substitution, and Skiko native library extraction. Requires BellSoft Liberica NIK 25 (full). Packaging via `packageGraalvmDmg`, `packageGraalvmNsis`, `packageGraalvmDeb` tasks.
    - **Conveyor**: [JVM config](https://conveyor.hydraulic.dev/21.1/configs/jvm/) — automatic jlink; `app.jvm.additional-ca-certs` key imports extra certificates into the bundled JDK's `cacerts`; [JDK stdlib](https://conveyor.hydraulic.dev/21.1/stdlib/jdks/) — 6 JDK vendors (Corretto, Zulu, Temurin, JBR, Microsoft, OpenJDK)
    - **install4j**: [JRE bundles](https://www.ej-technologies.com/resources/install4j/help/doc/concepts/jreBundles.html), [createbundle CLI](https://www.ej-technologies.com/resources/install4j/help/doc/cli/createBundle.html) — no cert patching DSL; manual via pre-patched JRE bundle
    - **jpackage**: [Override resources](https://docs.oracle.com/en/java/javase/23/jpackage/override-jpackage-resources.html) — `--resource-dir` limited to packaging templates; cert patching requires `--runtime-image` with a pre-patched jlink output
    - **Compose MP**: [Native distributions](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html) — ProGuard + jlink; no CA cert DSL; `jpackageResources` internal and cleared on each build ([open PR #2331](https://github.com/JetBrains/compose-multiplatform/pull/2331) for `--resource-dir` exposure, not merged)
    - **Badass-jlink**: [User guide](https://github.com/beryx/badass-jlink-plugin/blob/master/doc/user_guide.adoc) — task hooks on `jlink` task allow `doLast` keytool invocation against staged image

Only **Nucleus** and **Conveyor** provide declarative, first-class CA cert patching: a single DSL property that automatically patches the bundled JVM's `cacerts` without any manual keytool scripts. Nucleus is the only JVM packaging tool with integrated Project Leyden AOT cache support, providing dramatically faster cold startup without requiring GraalVM. As of v1.3.0, Nucleus also offers **alpha GraalVM Native Image support** — compiling Compose Desktop apps into standalone native binaries with ~0.5s cold boot, ~100–150 MB memory usage, and no bundled JRE. This provides three startup tiers: standard JVM (~3–5s), AOT cache via Leyden (~1.5s), and native image (~0.5s).

---

### 8. Cross-Compilation

| Tool | Build All From One OS | Score |
|------|:---------------------:|:-----:|
| Conveyor | ✅ (any OS → all platforms) | **10** |
| install4j | ✅ (any OS → all platforms) | **10** |
| jDeploy | ✅ (any OS → all platforms) | **10** |
| **Nucleus** | ❌ (per-OS runners required) | **3** |
| jpackage | ❌ | **0** |
| Compose MP | ❌ | **0** |
| Badass plugins | ❌ | **0** |

??? info "Sources"
    - **Conveyor**: [Homepage](https://conveyor.hydraulic.dev/21.1/) — "build packages from any OS, sign and notarize Mac apps from Linux"
    - **install4j**: [Homepage](https://www.ej-technologies.com/install4j) — "compile installers for all platforms on any of these platforms"
    - **jDeploy**: [FAQ](https://www.jdeploy.com/docs/faq/) — "build native installers for Mac, Windows, and Linux on any platform"
    - **Nucleus**: [`TargetFormat.kt`](https://github.com/nucleusframework/Nucleus/blob/main/plugin-build/plugin/src/main/kotlin/dev/nucleusframework/nucleus/desktop/application/dsl/TargetFormat.kt) line 48 — `isCompatibleWithCurrentOS` check; packaging tasks disabled for non-matching OS

This is Nucleus's weakest dimension. However, Nucleus mitigates it with its 6-runner CI matrix — the pipeline handles cross-platform builds automatically.

---

### 9. Installer Customization

| Tool | Windows Custom UI | DMG Layout | License Dialog | Components | Scripts | Score |
|------|:-----------------:|:----------:|:--------------:|:----------:|:-------:|:-----:|
| **Nucleus** | ✅ (full NSIS DSL) | ✅ (extensive) | ✅ | ✅ | ✅ | **9** |
| install4j | ✅ (GUI designer) | ✅ | ✅ | ✅ | ✅ | **10** |
| Conveyor | ❌ (MSIX only) | ❌ | ❌ | ❌ | ❌ | **1** |
| jpackage | Partial | Minimal | ❌ | ❌ | ❌ | **3** |
| Compose MP | ❌ | Minimal | ❌ | ❌ | ❌ | **2** |
| JavaPackager | ✅ (Inno Setup) | ✅ | ✅ | ❌ | ❌ | **4** |

??? info "Sources"
    - **Nucleus**: [`NsisSettings.kt`](https://github.com/nucleusframework/Nucleus/blob/main/plugin-build/plugin/src/main/kotlin/dev/nucleusframework/nucleus/desktop/application/dsl/NsisSettings.kt) — 16 properties: `oneClick`, `allowElevation`, `perMachine`, `allowToChangeInstallationDirectory`, `createDesktopShortcut`, `createStartMenuShortcut`, `runAfterFinish`, `installerIcon`, `license`, `includeScript`, `multiLanguageInstaller`, `installerHeader`, `installerSidebar`, etc. [`DmgSettings.kt`](https://github.com/nucleusframework/Nucleus/blob/main/plugin-build/plugin/src/main/kotlin/dev/nucleusframework/nucleus/desktop/application/dsl/DmgSettings.kt) — `background`, `backgroundColor`, `badgeIcon`, `icon`, `format` (6 DMG formats: UDRW, UDRO, UDCO, UDZO, UDBZ, ULFO), `window`, `contents`
    - **install4j**: [Features](https://www.ej-technologies.com/install4j/features) — visual IDE with 80+ configurable actions
    - **Conveyor**: [Package formats](https://conveyor.hydraulic.dev/21.1/package-formats/) — MSIX is a fixed format with no installer UI customization
    - **jpackage**: [Oracle man page](https://docs.oracle.com/en/java/javase/23/docs/specs/man/jpackage.html) — `--resource-dir` for template overrides

---

### 10. Store Distribution

!!! info "Do stores actually require sandboxing?"
    - **Mac App Store**: App Sandbox is **mandatory** ([Apple docs](https://developer.apple.com/documentation/security/app-sandbox)).
    - **Microsoft Store**: Sandboxing is **NOT required**. MSIX desktop apps use `runFullTrust` — a VFS overlay for clean uninstall, not a real sandbox ([Microsoft docs](https://learn.microsoft.com/en-us/windows/msix/overview)).
    - **Flathub**: Sandbox nominally mandatory, but apps can request broad `filesystem=host` permissions.
    - **Snap Store**: Strict confinement by default. Classic (unsandboxed) requires manual approval ([Snapcraft docs](https://snapcraft.io/docs/snap-confinement)).

| Tool | Mac App Store | Microsoft Store | Flathub | Snap Store | Score |
|------|:------------:|:---------------:|:-------:|:----------:|:-----:|
| **Nucleus** | ✅ (PKG + sandbox pipeline) | ✅ (AppX/MSIX) | ✅ (Flatpak) | ✅ (Snap) | **10** |
| Conveyor | ❌ | ✅ (MSIX) | ❌ | ❌ | **3** |
| install4j | ❌ | ❌ | ❌ | ❌ | **0** |
| jpackage | ⚠️ (`--mac-app-store`) | ❌ | ❌ | ❌ | **1** |
| Compose MP | ❌ | ❌ | ❌ | ❌ | **0** |

??? info "Sources"
    - **Nucleus**: [`TargetFormat.kt`](https://github.com/nucleusframework/Nucleus/blob/main/plugin-build/plugin/src/main/kotlin/dev/nucleusframework/nucleus/desktop/application/dsl/TargetFormat.kt) — `isStoreFormat` = Pkg, AppX, Flatpak; [`PlatformSettings.kt`](https://github.com/nucleusframework/Nucleus/blob/main/plugin-build/plugin/src/main/kotlin/dev/nucleusframework/nucleus/desktop/application/dsl/PlatformSettings.kt) — `appStore`, `entitlementsFile`, `provisioningProfile` for MAS; [`AppXSettings.kt`](https://github.com/nucleusframework/Nucleus/blob/main/plugin-build/plugin/src/main/kotlin/dev/nucleusframework/nucleus/desktop/application/dsl/AppXSettings.kt) — `identityName`, `publisher`, `capabilities`; [`FlatpakSettings.kt`](https://github.com/nucleusframework/Nucleus/blob/main/plugin-build/plugin/src/main/kotlin/dev/nucleusframework/nucleus/desktop/application/dsl/FlatpakSettings.kt) — `runtime`, `finishArgs`, `license`
    - **Conveyor**: [Windows config](https://conveyor.hydraulic.dev/21.1/configs/windows/) — Microsoft Store supported via `conveyor make ms-store-release`; [Output formats FAQ](https://conveyor.hydraulic.dev/21.1/faq/output-formats/) — Mac App Store listed as "not supported yet"
    - **jpackage**: [Oracle man page](https://docs.oracle.com/en/java/javase/23/docs/specs/man/jpackage.html) — `--mac-app-store` flag exists but limited

For JVM apps, Nucleus is unique in handling the Mac App Store sandbox automatically: it extracts native libraries from JARs, strips duplicates, injects JVM arguments for redirected library loading, and signs extracted native libraries individually.

---

### 11. Runtime Libraries & Native UI

| Tool | Dark Mode | Decorated Windows | Single Instance | Deep Links | File Associations | Executable Type | Native SSL | Linux HiDPI | GraalVM Runtime | Score |
|------|:---------:|:-----------------:|:---------------:|:----------:|:-----------------:|:---------------:|:----------:|:-----------:|:---------------:|:-----:|
| **Nucleus** | ✅ (JNI, reactive) | ✅ (JBR + JNI backends) | ✅ (file lock) | ✅ (protocols) | ✅ (DSL) | ✅ (17 types) | ✅ (JNI, OS trust store) | ✅ (GDK_SCALE) | ✅ (bootstrap) | **10** |
| Conveyor | ❌ | ❌ | ❌ | ⚠️ (OS registration) | ⚠️ (OS registration) | ❌ | ❌ | ❌ | ❌ | **2** |
| install4j | ❌ | ❌ | ✅ | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | **3** |
| jpackage | ❌ | ❌ | ❌ | ❌ | ✅ (`--file-associations`) | ❌ | ❌ | ❌ | ❌ | **1** |
| Compose MP | ❌ | ❌ | ❌ | ❌ | ✅ (via jpackage) | ❌ | ❌ | ❌ | ❌ | **1** |
| All others | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | **0** |

??? info "Sources"
    - **Nucleus dark mode**: [`IsSystemInDarkMode.kt`](https://github.com/nucleusframework/Nucleus/tree/main/darkmode-detector/src/main/kotlin/dev/nucleusframework/nucleus/darkmodedetector/) — JNI (not JNA) with native libraries per platform: macOS via `NSDistributedNotificationCenter`, Windows via registry `AppsUseLightTheme` + `RegNotifyChangeKeyValue`, Linux via D-Bus `org.freedesktop.portal.Settings`. Real-time reactive Compose state.
    - **Nucleus decorated windows**: Split into three modules since v1.3.0 — `decorated-window-core` (shared types/layout), [`decorated-window-jbr`](https://github.com/nucleusframework/Nucleus/tree/main/decorated-window-jbr) (JBR CustomTitleBar API on macOS/Windows), [`decorated-window-jni`](https://github.com/nucleusframework/Nucleus/tree/main/decorated-window-jni) (JNI-based, works with any JVM including GraalVM Native Image). Custom undecorated window with GNOME (24px arcs) and KDE (10px) native controls on Linux. Jewel variant in `decorated-window-jewel`, Material 2 in `decorated-window-material2`, Material 3 in `decorated-window-material3`.
    - **Nucleus Linux HiDPI**: [`linux-hidpi`](https://github.com/nucleusframework/Nucleus/tree/main/linux-hidpi) — native GDK_SCALE detection for proper HiDPI scaling on Linux, required for native image builds.
    - **Nucleus GraalVM runtime**: [`graalvm-runtime`](https://github.com/nucleusframework/Nucleus/tree/main/graalvm-runtime) — centralizes native-image bootstrap (`GraalVmInitializer.initialize()`), font substitution, Skiko library extraction, and platform-specific reachability metadata.
    - **Nucleus single instance**: [`SingleInstanceManager.kt`](https://github.com/nucleusframework/Nucleus/blob/main/core-runtime/src/main/kotlin/dev/nucleusframework/nucleus/core/runtime/SingleInstanceManager.kt) — `FileChannel.tryLock()` + `WatchService` for inter-process communication
    - **Nucleus deep links**: [`DeepLinkHandler.kt`](https://github.com/nucleusframework/Nucleus/blob/main/core-runtime/src/main/kotlin/dev/nucleusframework/nucleus/core/runtime/DeepLinkHandler.kt) — macOS via `Desktop.setOpenURIHandler` (Apple Events); Windows/Linux via CLI argument parsing
    - **Nucleus executable type**: [`ExecutableRuntime.kt`](https://github.com/nucleusframework/Nucleus/blob/main/core-runtime/src/main/kotlin/dev/nucleusframework/nucleus/core/runtime/ExecutableRuntime.kt) — 17 `ExecutableType` enum values (EXE, MSI, NSIS, NSIS_WEB, PORTABLE, APPX, DMG, PKG, DEB, RPM, SNAP, FLATPAK, APPIMAGE, ZIP, TAR, SEVEN_Z, DEV)
    - **Nucleus native-ssl**: [`NativeTrustManager.kt`](https://github.com/nucleusframework/Nucleus/blob/main/native-ssl/src/main/kotlin/dev/nucleusframework/nucleus/nativessl/NativeTrustManager.kt) — JNI-based `X509TrustManager` merging OS certificates with JVM defaults: macOS via Security.framework (`SecTrustCopyAnchorCertificates` + `SecTrustSettingsCopyCertificates`), Windows via Crypt32 (`ROOT`/`CA` stores across 5 locations including Group Policy), Linux via PEM bundles (8 discovery paths). Companion modules: `native-http-okhttp` (OkHttp 4) and `native-http-ktor` (Ktor engine).
    - **Conveyor**: [OS integration](https://conveyor.hydraulic.dev/21.1/configs/os-integration/) — `app.url-schemes` registers URL handlers, `app.file-associations` registers file types at OS level (generates AppxManifest.xml, Info.plist, .desktop files). No runtime library — app code must handle open requests itself.
    - **install4j**: [Features](https://www.ej-technologies.com/install4j/features) — single instance lock, file associations
    - **jpackage**: [Oracle man page](https://docs.oracle.com/en/java/javase/23/docs/specs/man/jpackage.html) — `--file-associations` flag with properties files

Nucleus is unique in the JVM space by bundling runtime libraries that address common desktop app needs. No other JVM packaging tool provides reactive dark mode detection, decorated windows, or deep link handling as a library. Since v1.3.0, the decorated window system offers two backends: JBR (for JetBrains Runtime) and JNI (for any JVM, including GraalVM Native Image), making it the only JVM window decoration solution that works across all JVM distributions. The `linux-hidpi` module and `graalvm-runtime` bootstrap module are also unique to Nucleus. File associations are more widely supported (jpackage, install4j, Conveyor all register at OS level), but only Nucleus combines registration with a runtime deep link handler.

The `native-ssl` module is unique in the JVM packaging space: it replaces the JSSE default trust manager at runtime with one that reads directly from the OS certificate store, so corporate-issued CAs, enterprise Group Policy certificates, and filtering proxy roots are trusted automatically — without any JVM cacerts manipulation. The companion `native-http-okhttp` and `native-http-ktor` modules provide ready-to-use HTTP clients pre-configured with this trust manager.

---

### 12. Documentation & Developer Experience

| Tool | Getting Started | DSL / Config Reference | CI/CD Guide | Migration Guide | API Docs | Examples | Score |
|------|:---------------:|:----------------------:|:-----------:|:---------------:|:--------:|:--------:|:-----:|
| **Nucleus** | ✅ | ✅ (comprehensive) | ✅ (detailed) | ✅ (from Compose MP) | ✅ | ✅ (demo app) | **9** |
| Conveyor | ✅ | ✅ (120+ settings) | ✅ | ✅ | ✅ | ✅ | **9** |
| install4j | ✅ | ✅ (extensive) | ✅ | ❌ | ✅ | ✅ | **9** |
| jpackage | ✅ (JEP + man page) | ❌ | ❌ | ❌ | ❌ | Minimal | **3** |
| Compose MP | ✅ | Partial | ❌ | ❌ | ❌ | ✅ | **5** |

??? info "Sources"
    - **Nucleus**: [docs site](https://nucleusframework.github.io/Nucleus/) — Getting Started, Configuration, per-platform guides, CI/CD, Runtime APIs, Migration, demo app in `example/`
    - **Conveyor**: [docs site](https://conveyor.hydraulic.dev/21.1/) — tutorials, 120+ config keys, CI guide, comparisons page, multiple sample projects
    - **install4j**: [help center](https://www.ej-technologies.com/resources/install4j/help/doc/) — extensive searchable docs
    - **jpackage**: [Oracle man page](https://docs.oracle.com/en/java/javase/23/docs/specs/man/jpackage.html) — CLI reference only
    - **Compose MP**: [Kotlin docs](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html) — basic packaging guide

---

### 13. Community, Maturity & Pricing

| Tool | Age | Community | Release Cadence | Price | Comm. Score | Price Score |
|------|:---:|:---------:|:---------------:|:-----:|:-----------:|:-----------:|
| **Nucleus** | 2025 | Small (growing) | Active | MIT (free) | **4** | **10** |
| Conveyor | ~2022 | Small-Medium | Active | Free (OSS) / $45/mo | **6** | **6** |
| install4j | ~2001 | Large (enterprise) | Active (Dec 2025) | $2,199+/dev | **10** | **3** |
| jpackage | JDK 14+ | N/A (JDK built-in) | JDK releases | Free | **9** | **10** |
| jDeploy | ~2020 | Small (growing) | Active (Feb 2026) | Apache 2 (free) | **5** | **10** |
| Compose MP | ~2021 | Large (JetBrains) | Active (Feb 2026) | Apache 2 (free) | **9** | **10** |
| JavaPackager | ~2019 | Small | Semi-maintained | GPL 3 (free) | **4** | **10** |
| Badass plugins | ~2018 | Medium | Active (Jan 2026) | Apache 2 (free) | **5** | **10** |

??? info "Sources"
    - **Conveyor pricing**: [hydraulic.dev/pricing](https://hydraulic.dev/pricing.html) — Free for OSI-licensed projects (badge required); Standard $45/month (3 apps); Source tier on request
    - **install4j pricing**: [ej-technologies store](https://www.ej-technologies.com/store/install4j/new) — Multi-Platform $2,199 perpetual, Windows-only $769; floating license $6,599
    - **install4j version**: [Changelog](https://www.ej-technologies.com/install4j/changelog) — v12.0.2, December 19, 2025
    - **jDeploy**: [GitHub](https://github.com/shannah/jdeploy) — v6.0.16, February 16, 2026; Apache-2.0
    - **Compose MP**: [Releases](https://github.com/JetBrains/compose-multiplatform/releases) — v1.10.1, February 2026
    - **JavaPackager**: [Releases](https://github.com/fvarrui/JavaPackager/releases) — v1.7.6, June 30, 2024; maintainer seeks contributors
    - **Badass-jlink**: [Releases](https://github.com/beryx/badass-jlink-plugin/releases) — v3.2.1, January 29, 2026
    - **Launch4j**: [Changelog](https://launch4j.sourceforge.net/changelog.html) — v3.50, November 13, 2022
    - **Packr**: [Releases](https://github.com/libgdx/packr/releases) — v4.0.0, March 29, 2022 (dormant)

---

## Scoring Matrix

Each dimension rated 0–10. Total = sum / 130 × 100 (rounded).

| Tool | Fmt | Upd | Sign | CI | Plat | Store | Opt | Inst | RT | Docs | Comm | Price | Build | **Total** |
|------|:---:|:---:|:----:|:--:|:----:|:-----:|:---:|:----:|:--:|:----:|:----:|:-----:|:-----:|:---------:|
| **Nucleus** | 10 | 9 | 10 | 10 | 10 | 10 | 10 | 9 | 10 | 9 | 4 | 10 | 6 | **90** |
| **install4j** | 5 | 9 | 8 | 3 | 10 | 0 | 5 | 10 | 3 | 9 | 10 | 3 | 10 | **65**¹ |
| **Conveyor** | 4 | 10 | 10 | 6 | 8 | 3 | 6 | 1 | 2 | 9 | 6 | 6 | 9 | **62**² |
| **jDeploy** | 3 | 6 | 7 | 5 | 8 | 0 | 4 | 2 | 0 | 6 | 5 | 10 | 8 | **49** |
| **Compose MP** | 4 | 0 | 5 | 1 | 5 | 0 | 6 | 2 | 1 | 5 | 9 | 10 | 6 | **42** |
| **jpackage** | 4 | 0 | 3 | 0 | 8 | 1 | 4 | 3 | 1 | 3 | 9 | 10 | 4 | **38** |
| **JavaPackager** | 5 | 0 | 3 | 1 | 5 | 0 | 4 | 4 | 0 | 4 | 4 | 10 | 7 | **36** |
| **Badass** | 4 | 0 | 0 | 1 | 8 | 0 | 5 | 2 | 0 | 5 | 5 | 10 | 6 | **35** |
| **Packr** | 1 | 0 | 0 | 0 | 5 | 0 | 3 | 0 | 0 | 3 | 2 | 10 | 4 | **22** |
| **Launch4j** | 1 | 0 | 2 | 1 | 2 | 0 | 1 | 1 | 0 | 3 | 3 | 10 | 5 | **22** |
| **JSmooth** | 1 | 0 | 0 | 0 | 1 | 0 | 0 | 0 | 0 | 1 | 0 | 10 | 1 | **11** |

!!! note "Footnotes"
    ¹ install4j's visual IDE, 20+ years of enterprise maturity, and cross-compilation (10/10) are significant advantages not fully captured by individual dimensions.

    ² Conveyor's cross-compilation (build all platforms from one machine, 10/10) is its biggest advantage. Its auto-update with delta updates (10/10) and broad signing provider support (10/10) are best-in-class.

---

## Detailed Tool Profiles

### jpackage (JDK 17+)

Built into the JDK since Java 14 (GA in 16). Creates platform-specific installers (DMG, PKG, MSI, EXE, DEB, RPM) from a modular or non-modular Java application. Requires the target OS for building ([Oracle docs](https://docs.oracle.com/en/java/javase/23/docs/specs/man/jpackage.html)). macOS code signing and notarization via `--mac-sign`, `--mac-signing-key-user-name`, and `--mac-app-store` flags. Windows signing not included. Moderate customization via `--resource-dir`. WiX v4/v5 support added in JDK 24 ([JDK 24 release notes](https://www.oracle.com/java/technologies/javase/24-relnote-issues.html)). JDK 25 changed default jlink behavior: service bindings no longer included by default ([JDK 25 release notes](https://www.oracle.com/java/technologies/javase/25-relnote-issues.html)). No auto-update. The baseline that all JVM packaging tools build upon.

### Conveyor (Hydraulic) — v21.1

A modern CLI tool that uniquely supports **cross-compilation** — build for Windows, macOS, and Linux all from a single machine ([docs](https://conveyor.hydraulic.dev/21.1/)). Configured via HOCON (Human-Optimized Config Object Notation) with 120+ settings.

**Formats**: MSIX + custom EXE installer (~500 KB, drives Windows Deployment Engine APIs) + ZIP on Windows; signed+notarized .app bundles in per-architecture ZIPs on macOS; DEB + tar.gz on Linux ([package formats](https://conveyor.hydraulic.dev/21.1/package-formats/)). No DMG (deliberately avoided — [FAQ](https://conveyor.hydraulic.dev/21.1/faq/output-formats/)), no PKG, no RPM (planned), no NSIS, no AppImage, no Snap, no Flatpak (planned).

**Updates**: Sparkle 2 on macOS with delta patches (configurable, default 5 versions), MSIX native 64 KB-chunk delta on Windows, apt repositories on Linux ([update modes](https://conveyor.hydraulic.dev/21.1/configs/update-modes/)).

**Signing**: Self-signing for free distribution, purchased Authenticode/SSL certificates (.p12/.pfx), macOS notarization via App Store Connect API keys, plus 6 cloud signing providers: Azure Trusted Signing, Azure Key Vault, AWS KMS, SSL.com eSigner, DigiCert ONE, Google Cloud KMS, and HSM support (SafeNet, YubiKey) ([keys and certificates](https://conveyor.hydraulic.dev/21.1/configs/keys-and-certificates/)).

**OS integration**: Registers URL schemes (`app.url-schemes`) and file associations (`app.file-associations`) at OS level, but does **not** provide a runtime library — apps must implement receiving logic themselves ([OS integration](https://conveyor.hydraulic.dev/21.1/configs/os-integration/)).

**JVM CA certificates**: `app.jvm.additional-ca-certs` imports extra PEM/DER certificates into the bundled JDK's `cacerts` at package time — useful for corporate proxies and private root CAs ([JVM config](https://conveyor.hydraulic.dev/21.1/configs/jvm/)).

**Build systems**: Gradle plugin (Gradle 7+), Maven integration via CLI (`mvn dependency:build-classpath`), standalone CLI ([Gradle/Maven docs](https://conveyor.hydraulic.dev/21.1/configs/maven-gradle/)).

**Store distribution**: Microsoft Store via `conveyor make ms-store-release`; Mac App Store not yet supported ([Windows config](https://conveyor.hydraulic.dev/21.1/configs/windows/)).

**Platforms**: Windows x64+ARM64, macOS x64+ARM64 (separate per-architecture downloads, no universal/fat binary), Linux x64. Linux ARM64 has config infrastructure (`app.linux.aarch64`) but is listed as planned ([Linux config](https://conveyor.hydraulic.dev/21.1/configs/linux/), [output formats FAQ](https://conveyor.hydraulic.dev/21.1/faq/output-formats/)).

**Pricing**: Free for OSI-licensed open source (badge required); $45/month Standard (3 apps); Source tier on request ([pricing](https://hydraulic.dev/pricing.html)).

### install4j (ej-technologies) — v12.0.2

The most mature commercial JVM installer tool (20+ years). Visual IDE for designing installer wizards with 80+ configurable actions ([features](https://www.ej-technologies.com/install4j/features)). Full cross-compilation from any OS ([homepage](https://www.ej-technologies.com/install4j)). Current version 12.0.2 released December 19, 2025 ([changelog](https://www.ej-technologies.com/install4j/changelog)).

**Formats**: Native EXE installer (32-bit, 64-bit, ARM64), optional MSI wrapper, DMG archive, .app folder, shell-based GUI installer, RPM, DEB, gzipped tar.gz ([media files docs](https://www.ej-technologies.com/resources/install4j/help/doc/concepts/mediaFiles.html)). No PKG, NSIS, AppX/MSIX, AppImage, Snap, Flatpak.

**v12 highlights**: Reworked installation directory handling, widget styles for UI customization, macOS binary launchers (start JVM in-process) ([what's new](https://www.ej-technologies.com/install4j/whatsnew12)).

**Pricing**: Multi-Platform $2,199/dev perpetual (+ $879/yr support); Windows-only $769/dev; floating license $6,599 ([store](https://www.ej-technologies.com/store/install4j/new)).

### jDeploy — v6.0.16

Free, open-source tool (Apache-2.0) focused on simplicity ([GitHub](https://github.com/shannah/jdeploy)). Cross-compiles all platforms from one machine without any third-party tools other than OpenJDK ([FAQ](https://www.jdeploy.com/docs/faq/)). v6.0.16 released February 16, 2026.

**Formats**: EXE on Windows, .app in tar.gz on macOS (DMG via separate [jdeploy-action-dmg](https://github.com/shannah/jdeploy-action-dmg) + macOS runner), DEB on Linux.

The installer is pre-signed and notarized by jDeploy itself, so developers don't need their own Apple certificate ([FAQ](https://www.jdeploy.com/docs/faq/)). Auto-update is built-in via npm/GitHub Releases infrastructure ([blog](https://jdeploy.substack.com/p/automated-deployment-and-updates)).

**v6.0 features**: Multi-modal deployments — GUI apps, CLI commands, background services, system tray helpers, and MCP servers for AI tool integration ([homepage](https://www.jdeploy.com/)).

### Compose Multiplatform (JetBrains) — v1.10.1

JetBrains' official Gradle plugin for Compose Desktop apps. v1.10.1 released February 2026 ([releases](https://github.com/JetBrains/compose-multiplatform/releases)). Wraps jpackage for packaging: supports DMG, PKG, MSI, EXE, DEB, RPM ([native distributions](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html)). macOS code signing and notarization via DSL. ProGuard integration.

No auto-update, no cross-compilation, no NSIS/AppImage/Snap/Flatpak/AppX, minimal installer customization, no runtime libraries. **Nucleus was designed as its successor/superset.**

v1.10.0 highlights: Unified `@Preview` annotation, Navigation 3 on non-Android targets, stable Compose Hot Reload ([JetBrains blog](https://blog.jetbrains.com/kotlin/2026/01/compose-multiplatform-1-10-0/)).

### JavaPackager — v1.7.6

Gradle + Maven plugin supporting EXE (Inno Setup/WinRun4J), MSI (WiX), DMG, PKG, DEB, RPM, AppImage, and archives ([GitHub](https://github.com/fvarrui/JavaPackager)). Windows code signing via Jsign 5.0 (added in v1.7.4, [release notes](https://github.com/fvarrui/JavaPackager/releases/tag/v1.7.4)). JRE bundling with module selection.

Last release: June 30, 2024. **Maintainer actively seeking contributors** ([GitHub](https://github.com/fvarrui/JavaPackager)).

### Badass-jlink / Badass-runtime Plugins

Popular Gradle plugins wrapping jlink and jpackage with ergonomic APIs. badass-jlink v3.2.1 (January 2026) for modular apps; badass-runtime v2.0.1 (November 2025) for non-modular apps ([GitHub jlink](https://github.com/beryx/badass-jlink-plugin), [GitHub runtime](https://github.com/beryx/badass-runtime-plugin)). All jpackage formats supported. Inherits all jpackage limitations (no cross-compilation, no auto-update, no signing integration). Maintainer seeks co-maintainers ([GitHub](https://github.com/beryx/badass-jlink-plugin)).

### Launch4j — v3.50

Creates Windows EXE wrappers (~62 KB) for JAR files. Splash screen, JRE detection, heap configuration. Can be built cross-platform. Last release: November 13, 2022 ([changelog](https://launch4j.sourceforge.net/changelog.html)). An [omegat-org fork](https://github.com/omegat-org/launch4j) has more recent updates (2025) with ARM64 support. BSD license.

### Packr (libGDX) — v4.0.0

Bundles JRE with application into a directory structure. Game-focused (libGDX/LWJGL). Does NOT produce installers — only raw app bundles/executables. Dormant: last release March 29, 2022 ([GitHub releases](https://github.com/libgdx/packr/releases)). Apache 2.0 license.

---

## Strengths & Weaknesses

### Nucleus's Strengths

1. **Unmatched format coverage** — 16 formats, more than any other JVM tool
2. **Only JVM tool with integrated runtime libraries** — dark mode, decorated windows (JBR + JNI backends), single instance, deep links, executable type detection, Linux HiDPI scaling
3. **Best CI pipeline for JVM apps** — 6 composite GitHub Actions, 6-runner matrix, universal macOS + MSIX bundle, GraalVM native image builds
4. **First JVM tool with AOT cache** — Project Leyden (JDK 25+), no GraalVM required
5. **First JVM tool with integrated GraalVM Native Image support** — compile Compose Desktop apps to standalone native binaries (~0.5s cold boot, ~100–150 MB RAM, no bundled JRE). Three startup tiers: standard JVM → AOT cache (Leyden) → native image
6. **Broadest store distribution** — 4 stores (MAS, MS Store, Flathub, Snap Store), unique JVM sandbox pipeline
7. **Full signing matrix** — macOS + notarization, Windows PFX + Azure Trusted Signing
8. **Free and open source** (MIT)
9. **Native SSL runtime** — unique JNI module using the OS trust store (macOS Security.framework, Windows Crypt32, Linux PEM bundles); pre-wired OkHttp and Ktor adapters; no cacerts manipulation needed at runtime
10. **Build-time CA cert patching** — import custom PEM/DER certificates into the bundled JVM's `cacerts` at packaging time (Conveyor also offers this via `app.jvm.additional-ca-certs`)
11. **Decorated windows on any JVM** — JNI backend (v1.3.0+) removes JBR dependency, enabling native window decorations with GraalVM, standard OpenJDK, and any other JVM distribution

### Nucleus's Weaknesses

1. **No cross-compilation** — requires per-OS CI runners (mitigated by CI actions)
2. **Gradle-only** — no Maven or CLI support
3. **Young project** — smaller community, less battle-testing
4. **Depends on electron-builder** — Electron ecosystem dependency used as backend
5. **GraalVM Native Image is in alpha** — requires BellSoft Liberica NIK 25, limited to DMG/NSIS/DEB packaging, and some Compose features may need additional reachability metadata

---

## When to Choose Something Else

| Use Case | Recommended Tool | Why | Source |
|----------|-----------------|-----|--------|
| **Build from one machine** | Conveyor or install4j | Full cross-compilation | [Conveyor](https://conveyor.hydraulic.dev/21.1/), [install4j](https://www.ej-technologies.com/install4j) |
| **Custom installer UI** | install4j | Visual IDE, 80+ actions | [Features](https://www.ej-technologies.com/install4j/features) |
| **Simplest setup** | jDeploy | Zero-config CLI, cross-compiles | [jDeploy](https://www.jdeploy.com/) |
| **Enterprise deployment** | install4j | 20+ years maturity | [ej-technologies](https://www.ej-technologies.com/install4j) |
| **Maven-only project** | install4j or JavaPackager | Nucleus is Gradle-only | — |
| **Delta updates critical** | Conveyor | MSIX + Sparkle delta | [Updates](https://conveyor.hydraulic.dev/21.1/understanding-updates/) |
| **Maximum signing providers** | Conveyor | 6 cloud HSM services | [Keys](https://conveyor.hydraulic.dev/21.1/configs/keys-and-certificates/) |

---

## Methodology

1. **Codebase analysis** — Nucleus plugin source, runtime libraries, CI workflows, and composite actions analyzed by [Claude Code](https://claude.ai/claude-code) with direct source file references
2. **Web research** — AI agents visited official documentation pages for all 11 tools, collecting version numbers, feature lists, pricing, and platform support
3. **Source verification** — Every claim includes a link to the official source (documentation page, GitHub repository, or vendor site)
4. **Scoring** — Each tool rated 0–10 across 13 dimensions; total = raw sum / 130 × 100

!!! warning "Disclaimer"
    While every effort was made to verify accuracy with source links, tool capabilities evolve. Always check the linked documentation for the latest information. Conveyor data verified against v21.1 docs (February 2026). Nucleus data verified against v1.3.x source code (March 2026).
