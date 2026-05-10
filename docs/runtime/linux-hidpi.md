# Linux HiDPI

Standard OpenJDK does not detect the native display scale factor on Linux. On HiDPI screens this results in a tiny, blurry UI rendered at 1x resolution.

The `linux-hidpi` module provides native scale factor detection using JNI, mirroring the detection logic built into JetBrains Runtime (`systemScale.c`). It queries multiple system sources and returns the correct scale so you can apply it via `sun.java2d.uiScale` before AWT initialises.

This module was originally designed for running Compose Desktop applications compiled with **GraalVM Native Image** (alpha), where JBR is not available and scale detection must be handled manually.

!!! tip "JBR recommended for JVM-based applications"
    If your application runs on a standard JVM (not a native image), prefer using **JetBrains Runtime (JBR)** which handles HiDPI detection natively and provides stable, battle-tested support across Linux desktop environments. This module is only necessary when JBR is not an option — typically with GraalVM Native Image or other non-JBR runtimes.

!!! note "Already handled by `GraalVmInitializer`"
    If you use the [`graalvm-runtime`](../graalvm/runtime-bootstrap.md) module, `GraalVmInitializer.initialize()` already calls `getLinuxNativeScaleFactor()` and applies the scale factor automatically. You do **not** need to add `linux-hidpi` as a separate dependency or call the function manually — it is included transitively.

## Installation

```kotlin
dependencies {
    implementation("dev.nucleusframework:nucleus.linux-hidpi:<version>")
}
```

## Usage

Call `getLinuxNativeScaleFactor()` **before** `application {}` (i.e. before AWT initialises):

```kotlin
import dev.nucleusframework.nucleus.hidpi.getLinuxNativeScaleFactor

fun main() {
    if (System.getProperty("sun.java2d.uiScale") == null) {
        val scale = getLinuxNativeScaleFactor()
        if (scale > 0.0) {
            System.setProperty("sun.java2d.uiScale", scale.toString())
        }
    }

    application {
        // Your Compose Desktop app
    }
}
```

The function is a no-op on non-Linux platforms and returns `0.0`.

## Detection Sources

The scale factor is resolved from the first available source, in priority order:

| Priority | Source | Description |
|----------|--------|-------------|
| 1 | `J2D_UISCALE` | Explicit JVM override (environment variable) |
| 2 | GSettings | GNOME `org.gnome.desktop.interface` → `scaling-factor` (via libgio) |
| 3 | `GDK_SCALE` | GTK / GNOME session variable |
| 4 | `GDK_DPI_SCALE` | GTK fractional DPI multiplier |
| 5 | `Xft.dpi` | X Resource Manager (KDE, legacy GNOME, …) |

If the JNI library cannot be loaded (e.g. on a minimal container), the function falls back to reading `J2D_UISCALE`, `GDK_SCALE`, and `GDK_DPI_SCALE` environment variables from pure Java.

## Native Libraries

The module ships pre-built native binaries for:

- Linux x64: `libnucleus_linux_hidpi_jni.so`
- Linux aarch64: `libnucleus_linux_hidpi_jni.so`

The native code uses `dlopen` to load optional dependencies (libgio for GSettings, libX11 for Xft.dpi) at runtime, so there are no hard link-time dependencies beyond libc.

## ProGuard

When ProGuard is enabled, preserve the JNI bridge class:

```proguard
-keep class dev.nucleusframework.nucleus.hidpi.HiDpiLinuxBridge {
    native <methods>;
}
```
