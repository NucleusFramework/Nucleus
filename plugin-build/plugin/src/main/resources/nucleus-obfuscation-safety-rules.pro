# Obfuscation-safety rules — injected by Nucleus ONLY when proguard { obfuscate = true }.
#
# Compose and the Kotlin(x) runtimes are referenced by their original names at runtime (the
# Compose compiler plugin, reflection, ServiceLoader, kotlinx.serialization, coroutines
# internals). Renaming them breaks the app — e.g. `ClassNotFoundException:
# androidx.compose.runtime.Composer` on the first recomposition. These frameworks are
# open-source and carry no IP to protect, so their *names* are preserved while everything the
# application declares (its own packages) is still obfuscated.
#
# `allowshrinking` keeps the shrinking pass fully active on these classes (dead Compose code
# is still stripped) — renaming AND optimization are held back. Optimization must stay off for
# kept-by-name framework classes: ProGuard's method specialization/class merging rewrites
# signatures inside kotlinx.coroutines inconsistently with their call sites, producing
# `VerifyError: Bad type on operand stack` in ChannelsKt.trySendBlocking at runtime.
-keep,allowshrinking class androidx.** { *; }
-keep,allowshrinking class kotlinx.** { *; }
