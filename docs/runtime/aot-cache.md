# AOT Cache

JVM desktop applications can suffer from noticeable cold-start latency — the JVM has to load thousands of classes, verify bytecode, and JIT-compile hot paths before the UI feels responsive. **This was the primary motivation behind the creation of Nucleus.**

[Project Leyden](https://openjdk.org/projects/leyden/) is an OpenJDK initiative originally designed to improve startup time for server-side workloads like microservices. Starting with JDK 25, it introduced **single-step AOT cache generation**: the JVM records a training run and produces a cache that dramatically accelerates subsequent launches. Nucleus brings this technology to **desktop applications** — the AOT cache is generated automatically during the build and bundled with the distributed installer, giving your Compose Desktop app near-instant cold boot with zero effort from the end user.

## Build Configuration

!!! failure "JDK 25+ strictly required"
    AOT cache generation requires **JDK 25 or later**. If an older JDK is detected, the build **will fail**. Make sure your toolchain and CI environments use JDK 25+.

Enable AOT cache in your `build.gradle.kts`:

```kotlin
nucleus.application {
    nativeDistributions {
        enableAotCache = true
    }
}
```

That's all you need on the build side. When enabled, the plugin will:

1. Launch your application in **training mode** after `createDistributable`
2. Record class loading and JIT compilation into an `app.aot` cache file (`-XX:AOTCacheOutput`)
3. Inject `-XX:AOTCache=app.aot` into the application launcher configuration
4. Bundle the cache with the final installer (DMG, NSIS, DEB, etc.)

### Platform-specific caches

The AOT cache is **platform- and JDK-specific**. A cache generated on macOS ARM64 with JBR 25.0.2 will not work on Linux x64 or with a different JDK version. This means:

- The cache **must be generated separately on each target platform**
- The JDK used at build time **must be exactly the same** as the one bundled in the final distribution
- **Let the plugin handle everything** — it uses the bundled JDK from `createDistributable` to generate the cache, ensuring a perfect match

!!! tip "Use CI for cross-platform builds"
    The simplest and safest approach is to build on CI with a matrix strategy (one job per OS). The `setup-nucleus` action configures the same JBR version on every platform, ensuring consistent cache generation. See [CI/CD](../ci-cd.md) for a complete workflow example.

## Runtime Library

To detect AOT mode at runtime (e.g. to self-terminate during training or skip heavy initialization), add the runtime library:

```kotlin
dependencies {
    implementation("dev.nucleusframework:nucleus.aot-runtime:<version>")
    // Transitive: nucleus.core-runtime is pulled in via `api`
}
```

```kotlin
import dev.nucleusframework.nucleus.aot.runtime.AotRuntime
import dev.nucleusframework.nucleus.aot.runtime.AotRuntimeMode
```

## Modes

| Method | Returns `true` when... |
|--------|------------------------|
| `AotRuntime.isTraining()` | App is running during AOT cache generation |
| `AotRuntime.isRuntime()` | App is running with an AOT cache loaded |
| `AotRuntime.mode()` | Returns `AotRuntimeMode.TRAINING`, `AotRuntimeMode.RUNTIME`, or `AotRuntimeMode.OFF` |

## Training Mode

During AOT training, the plugin launches your application so the JVM can record which classes are loaded and which methods are compiled. Your application **must self-terminate** during this phase — otherwise the build will hang until the safety timeout (300 seconds) kills the process.

### Basic approach

The simplest strategy is to run the app for a fixed duration (30–45 seconds is usually enough) and exit:

```kotlin
private const val AOT_TRAINING_DURATION_MS = 45_000L

fun main() {
    if (AotRuntime.isTraining()) {
        Thread({
            Thread.sleep(AOT_TRAINING_DURATION_MS)
            System.exit(0)
        }, "aot-timer").apply {
            isDaemon = false
            start()
        }
    }

    application {
        Window(onCloseRequest = ::exitApplication, title = "MyApp") {
            App()
        }
    }
}
```

### Optimized approach

For maximum startup improvement, actively exercise your application's hot paths during training. The more classes the JVM loads during the training run, the more it can pre-compile in the cache:

```kotlin
fun main() {
    if (AotRuntime.isTraining()) {
        Thread({
            Thread.sleep(AOT_TRAINING_DURATION_MS)
            System.exit(0)
        }, "aot-timer").apply {
            isDaemon = false
            start()
        }
    }

    // Eagerly load classes that the user will hit on first launch
    if (AotRuntime.isTraining()) {
        preloadNavigationScreens()
        preloadFontsAndImages()
        initializeDatabase()
    }

    application {
        Window(onCloseRequest = ::exitApplication, title = "MyApp") {
            App()
        }
    }
}
```

The more representative the training run is of a real user session, the better the cold-start performance will be.

## How It Works

The plugin sets the `nucleus.aot.mode` system property:

- `training` — set during the AOT cache generation step
- `runtime` — set when an AOT cache is loaded
- absent — no AOT (`AotRuntime.mode()` returns `AotRuntimeMode.OFF`)

## Requirements

- The training run must exit with code `0`
- The plugin enforces a safety timeout of 300 seconds — if the app hasn't exited by then, the process is force-killed
- On headless Linux, the plugin uses Xvfb automatically

## Further Reading

- [Project Leyden](https://openjdk.org/projects/leyden/) — the OpenJDK initiative behind AOT cache technology
- [CI/CD](../ci-cd.md) — cross-platform build workflows with `setup-nucleus`

## ExecutableRuntime Re-export

The `aot-runtime` module re-exports `ExecutableRuntime` and `ExecutableType` via type aliases, so you can import from either package:

```kotlin
// Both work:
import dev.nucleusframework.nucleus.core.runtime.ExecutableRuntime
import dev.nucleusframework.nucleus.aot.runtime.ExecutableRuntime
```
