# Runtime Bootstrap

## `graalvm-runtime` module

The `graalvm-runtime` module provides everything needed to bootstrap a Compose Desktop application in a GraalVM native image. Add it to your dependencies:

```kotlin
dependencies {
    implementation("dev.nucleusframework:nucleus.graalvm-runtime:<version>")
}
```

Then call `GraalVmInitializer.initialize()` as the **first line** of your `main()` function, before any AWT or Compose usage:

```kotlin
import dev.nucleusframework.nucleus.graalvm.GraalVmInitializer

fun main() {
    GraalVmInitializer.initialize()

    application {
        Window(onCloseRequest = ::exitApplication, title = "MyApp") {
            App()
        }
    }
}
```

The initializer handles all of the following automatically:

| Concern | What it does |
|---------|--------------|
| **Metal L&F** | Sets `swing.defaultlaf` to avoid unsupported platform modules |
| **`java.home`** | Points to the executable directory so Skiko finds jawt |
| **`java.library.path`** | Sets `execDir` + `execDir/bin` so fontmanager/freetype/awt are discoverable |
| **Charset init** | Forces early `Charset.defaultCharset()` to prevent `InternalError: platform encoding not initialized` |
| **Fontmanager preload** | Calls `System.loadLibrary("fontmanager")` early to avoid crashes in `Font.createFont()` |
| **Linux HiDPI** | Detects and applies the native scale factor via [`linux-hidpi`](../runtime/linux-hidpi.md) (works in both JVM and native image) |

The native-image-specific steps only run when `org.graalvm.nativeimage.imagecode` is set. The Linux HiDPI detection runs unconditionally (it's a no-op on non-Linux platforms).

You can also check `GraalVmInitializer.isNativeImage` at any point to branch on native-image vs JVM execution.

## Font substitutions

The module ships GraalVM `@TargetClass` substitutions (Java source files) that fix font-related crashes in native image on Windows and Linux:

- **`FontCreateFontSubstitution`** — Buffers `Font.createFont(int, InputStream)` to a temp file on Windows, working around streams that lack mark/reset support in native image.
- **`Win32FontManagerSubstitution`** — Replaces `Win32FontManager.getFontPath()` with a pure-Java implementation, fixing `InternalError: platform encoding not initialized`.
- **`FcFontManagerSubstitution`** — Fixes `FcFontManager.getFontPath()` on Linux native image.

These substitutions are automatically picked up by the native-image compiler — no configuration needed.

## Automatic Resource Inclusion

One of the most common pitfalls with GraalVM native-image is **missing resources at runtime**. Icons, fonts, and service descriptors must be explicitly registered — otherwise `Class.getResource()` returns `null` and your UI renders blank icons.

The `graalvm-runtime` module solves this automatically. It ships a `native-image.properties` file that registers broad resource patterns at compile time:

| Pattern | What it covers |
|---------|----------------|
| `.*\.(svg\|ttf\|otf)` | All SVG icons and font files on the classpath — Jewel, IntelliJ Platform icons, Compose resources, your own icons |
| `composeResources/.*` | All Compose Multiplatform resources (images, strings, fonts loaded via `Res.*`) |
| `nucleus/native/.*` | All Nucleus JNI native libraries (`.dll`, `.dylib`, `.so`) |
| `META-INF/services/.*` | All `ServiceLoader` descriptors (ktor, coil, SLF4J, etc.) |

This means:

- **All SVG icons work out of the box** — Jewel's `PathIconKey`, `AllIconsKeys`, dark/light variants, `@2x` retina variants — everything is included automatically.
- **All fonts are embedded** — Inter, JetBrains Mono, or any custom `.ttf`/`.otf` in your dependencies.
- **All Compose Multiplatform resources are included** — images, strings, and other resources loaded via the `Res` API.
- **Service loaders resolve correctly** — ktor engines, coil fetchers, SLF4J providers, etc.

!!! note "Binary size trade-off"
    The glob pattern `.*\.(svg|ttf|otf)` includes **all** SVGs and fonts from **all** JARs on the classpath. If you depend on the IntelliJ Platform icons library, this may add several megabytes of icons you don't actually use. For most applications, the convenience far outweighs the size increase. If binary size is critical, you can override with more targeted patterns in your own `resource-config.json`.

## Decorated Window

The [`decorated-window-jni`](../runtime/decorated-window.md) module was specifically designed to work with GraalVM Native Image (no JBR dependency). Use it instead of `decorated-window-jbr` for native-image builds.
