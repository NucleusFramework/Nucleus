# Tasks & CI/CD

## Gradle Tasks

| Task | Description |
|------|-------------|
| `runWithNativeAgent` | Run the app with the GraalVM tracing agent to collect reflection metadata |
| `analyzeGraalvmStaticMetadata` | Scan compiled bytecode for reflection/JNI/resource patterns (runs automatically) |
| `filterGraalvmLibraryMetadata` | Filter per-library metadata based on actual classpath (runs automatically) |
| `resolveGraalvmReachabilityMetadata` | Resolve Oracle Reachability Metadata Repository for classpath dependencies (runs automatically) |
| `generateGraalvmPlatformMetadata` | Generate platform-specific metadata for the current OS (runs automatically) |
| `cleanupGraalvmMetadata` | Remove manual entries already covered by automatic metadata |
| `packageGraalvmNative` | Compile and package the application as a native binary |
| `runGraalvmNative` | Build and run the native image directly |
| `packageGraalvmDeb` | Package the native image as a `.deb` installer (Linux) |
| `packageGraalvmDmg` | Package the native image as a `.dmg` installer (macOS) |
| `packageGraalvmNsis` | Package the native image as an NSIS `.exe` installer (Windows) |

The tasks marked "runs automatically" are dependencies of `packageGraalvmNative` — you don't need to invoke them manually. They are listed here for reference and debugging.

```bash
# Build the raw native image (triggers all automatic metadata tasks)
./gradlew packageGraalvmNative

# Build and run the native image
./gradlew runGraalvmNative

# Build platform-specific installers (requires Node.js for electron-builder)
./gradlew packageGraalvmDeb    # Linux
./gradlew packageGraalvmDmg    # macOS
./gradlew packageGraalvmNsis   # Windows

# NOTE: The `homepage` property is required in nativeDistributions for DEB packaging.
# electron-builder will fail without it. See Configuration > Package Metadata.

# Optional: collect agent metadata as a final check
./gradlew runWithNativeAgent

# Optional: clean up redundant manual entries
./gradlew cleanupGraalvmMetadata
```

Use `-PnativeMarch=compatibility` for binaries that should run on older CPUs:

```bash
./gradlew packageGraalvmNative -PnativeMarch=compatibility
```

## Output location

The raw native binary and its companion shared libraries are generated in:

```
<project>/build/compose/tmp/<project>/graalvm/output/
```

| Platform | Output |
|----------|--------|
| **macOS** | `output/MyApp.app/` (full `.app` bundle with `Info.plist`, icons, signed dylibs) |
| **Windows** | `output/my-app.exe` + companion DLLs (`awt.dll`, `fontmanager.dll`, etc.) |
| **Linux** | `output/my-app` + companion `.so` files (`libawt.so`, `libfontmanager.so`, etc.) |

The `packageGraalvm<Format>` tasks produce installers in:

```
<project>/build/compose/binaries/<buildType>/graalvm-<format>/
```

## CI/CD

Native image compilation must happen **on each target platform**. Use `setup-nucleus` with `graalvm: 'true'`:

```yaml
name: Build GraalVM Native Image

on:
  push:
    tags: ["v*"]

jobs:
  build-natives:
    uses: ./.github/workflows/build-natives.yaml

  graalvm:
    needs: build-natives
    name: GraalVM - ${{ matrix.name }}
    runs-on: ${{ matrix.os }}
    timeout-minutes: 60
    strategy:
      fail-fast: false
      matrix:
        include:
          - name: Linux x64
            os: ubuntu-latest
          - name: macOS ARM64
            os: macos-latest
          - name: Windows x64
            os: windows-latest

    steps:
      - uses: actions/checkout@v4

      # Download pre-built JNI native libraries here...

      - name: Setup Nucleus (GraalVM)
        uses: kdroidFilter/Nucleus/.github/actions/setup-nucleus@main
        with:
          graalvm: 'true'
          setup-gradle: 'true'
          setup-node: 'true'  # Required for packageGraalvm<Format> tasks

      - name: Build GraalVM native packages
        shell: bash
        run: |
          if [ "$RUNNER_OS" = "Linux" ]; then
            ./gradlew :myapp:packageGraalvmDeb \
              -PnativeMarch=compatibility --no-daemon
          elif [ "$RUNNER_OS" = "macOS" ]; then
            ./gradlew :myapp:packageGraalvmDmg \
              -PnativeMarch=compatibility --no-daemon
          elif [ "$RUNNER_OS" = "Windows" ]; then
            ./gradlew :myapp:packageGraalvmNsis \
              -PnativeMarch=compatibility --no-daemon
          fi

      - uses: actions/upload-artifact@v4
        with:
          name: graalvm-${{ runner.os }}
          path: myapp/build/compose/binaries/**/graalvm-*/**
```

See [CI/CD](../ci-cd.md#graalvm-native-image-release) for the full release workflow with publishing to GitHub Releases.

## Debugging

### Missing reflection at runtime

Run your native binary from the terminal. Reflection failures produce clear error messages like `Class not found` or `No such field`. If you encounter a crash:

1. Run `./gradlew runWithNativeAgent`, navigate through the failing code path, and let the agent capture the missing entry
2. Agent output is automatically deduplicated — only truly new entries are added
3. Rebuild with `./gradlew packageGraalvmNative`

### Cleaning up accumulated metadata

Over time, manual `reachability-metadata.json` entries may become redundant as Nucleus adds coverage for more libraries. Run the cleanup task periodically:

```bash
./gradlew cleanupGraalvmMetadata
```

The task reports exactly which entries were removed and which remain, so you can verify the cleanup is safe before committing.

## Best Practices

### Test on all platforms early

Don't wait until the end to test native-image on all three platforms. Each platform has its own set of reflection requirements and quirks. Test early and often.

### Run the agent once before release

Even though the automatic metadata covers the vast majority of cases, running `runWithNativeAgent` once before a release is a good habit. In most cases it will find nothing new, but it costs little and provides confidence.

### Use the Jewel sample as reference

The [`jewel-sample`](https://github.com/kdroidFilter/Nucleus/tree/main/jewel-sample) in the Nucleus repository demonstrates a more complex native-image setup with the Jewel UI library. It is an excellent reference for advanced use cases.

## Further Reading

- [GraalVM Native Image documentation](https://www.graalvm.org/latest/reference-manual/native-image/)
- [BellSoft Liberica NIK](https://bell-sw.com/liberica-native-image-kit/)
- [Oracle GraalVM Reachability Metadata Repository](https://github.com/oracle/graalvm-reachability-metadata)
- [Nucleus example app](https://github.com/kdroidFilter/Nucleus/tree/main/example) — minimal Compose Desktop + native-image setup
- [Nucleus Jewel sample](https://github.com/kdroidFilter/Nucleus/tree/main/jewel-sample) — advanced setup with reflection-heavy dependencies
