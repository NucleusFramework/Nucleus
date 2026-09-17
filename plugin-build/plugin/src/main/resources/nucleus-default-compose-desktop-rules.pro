-keep class kotlin.** { *; }
-keep class org.jetbrains.skia.** { *; }
-keep class org.jetbrains.skiko.** { *; }

-assumenosideeffects public class androidx.compose.runtime.ComposerKt {
    void sourceInformation(androidx.compose.runtime.Composer,java.lang.String);
    void sourceInformationMarkerStart(androidx.compose.runtime.Composer,int,java.lang.String);
    void sourceInformationMarkerEnd(androidx.compose.runtime.Composer);
    boolean isTraceInProgress();
    void traceEventStart(int, java.lang.String);
    void traceEventEnd();
}

# Kotlinx Coroutines Rules
# https://github.com/Kotlin/kotlinx.coroutines/blob/master/kotlinx-coroutines-core/jvm/resources/META-INF/proguard/coroutines.pro
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keepclassmembers class kotlin.coroutines.SafeContinuation {
    volatile <fields>;
}
-dontwarn java.lang.instrument.ClassFileTransformer
-dontwarn sun.misc.SignalHandler
-dontwarn java.lang.instrument.Instrumentation
-dontwarn sun.misc.Signal
-dontwarn java.lang.ClassValue
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

# https://youtrack.jetbrains.com/issue/CMP-3818/Update-ProGuard-to-version-7.4-to-support-new-Java-versions
# https://youtrack.jetbrains.com/issue/CMP-7577/Desktop-runRelease-crash-when-upgrade-to-CMP-1.8.0-alpha02
-keep,allowshrinking,allowobfuscation class kotlinx.coroutines.flow.FlowKt** { *; }
-keep,allowshrinking,allowobfuscation class kotlinx.coroutines.Job { *; }
-dontnote kotlinx.coroutines.**

# org.jetbrains.kotlinx:kotlinx-coroutines-swing
-keep class kotlinx.coroutines.swing.SwingDispatcherFactory

# Kotlinx Datetime
#   Material3 depends on it, and it references `kotlinx.serialization`, which is optional
#   Copied from https://github.com/Kotlin/kotlinx-datetime/blob/v0.6.2/core/jvm/resources/META-INF/proguard/datetime.pro
#   with one additional rule
-dontwarn kotlinx.serialization.KSerializer
-dontwarn kotlinx.serialization.Serializable
-dontwarn kotlinx.datetime.serializers.**

# https://github.com/Kotlin/kotlinx.coroutines/issues/2046
-dontwarn android.annotation.SuppressLint

# https://github.com/JetBrains/compose-jb/issues/2393
-dontnote kotlin.coroutines.jvm.internal.**
-dontnote kotlin.internal.**
-dontnote kotlin.jvm.internal.**
-dontnote kotlin.reflect.**
-dontnote kotlinx.coroutines.debug.internal.**
-dontnote kotlinx.coroutines.internal.**
-keep class kotlin.coroutines.Continuation
-keep class kotlinx.coroutines.CancellableContinuation
-keep class kotlinx.coroutines.channels.Channel
-keep class kotlinx.coroutines.CoroutineDispatcher
-keep class kotlinx.coroutines.CoroutineScope
# this is a weird one, but breaks build on some combinations of OS and JDK (reproduced on Windows 10 + Corretto 16)
-dontwarn org.graalvm.compiler.core.aarch64.AArch64NodeMatchRules_MatchStatementSet*

# Androidx
-keep,allowshrinking,allowobfuscation class androidx.compose.runtime.SnapshotStateKt__DerivedStateKt { *; }
-keep class androidx.compose.material3.SliderDefaults { *; }
-dontnote androidx.**

# Kotlinx serialization, included by androidx.navigation
# https://github.com/Kotlin/kotlinx.serialization/blob/master/rules/common.pro
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$* Companion;
}
-keepnames @kotlinx.serialization.internal.NamedCompanion class *
-if @kotlinx.serialization.internal.NamedCompanion class *
-keepclassmembernames class * {
    static <1> *;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
# Keep `INSTANCE.serializer()` of serializable objects.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-dontnote kotlinx.serialization.**
-dontwarn kotlinx.serialization.internal.ClassValueReferences
-keepclassmembers public class **$$serializer {
    private ** descriptor;
}

# Kotlinx serialization, additional rules

# Fixes:
#   Exception in thread "main" kotlinx.serialization.SerializationException: Serializer for class 'SomeClass' is not found.
#   Please ensure that class is marked as '@Serializable' and that the serialization compiler plugin is applied.
-keep class **$$serializer {
    *;
}
-dontnote **$$serializer

# Fixes:
#   Exception in thread "main" kotlinx.a.g: Serializer for class 'MyClass' is not found
# When `@InternalSerializationApi kotlinx.serialization.serializer` is used with obfuscation enabled
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1>$** {
    kotlinx.serialization.KSerializer serializer(...);
}

# org.jetbrains.runtime:jbr-api
# JBR API uses reflection and dynamic proxies extensively to bridge to the JBR.
# If jbr-api is on the runtime classpath (implementation dependency), ProGuard
# must keep ALL its classes intact — not just JBR itself.
-keep class com.jetbrains.** { *; }
-dontwarn com.jetbrains.**
-dontnote com.jetbrains.**

# JNA (Java Native Access)
# JNA uses JNI callbacks from native code (e.g. dispose, newJavaStructure) that
# ProGuard cannot detect. Keep JNA core classes and user-defined Callback/Structure
# implementations so JNI and reflection-based method lookup works.
-keep class com.sun.jna.* { *; }
-keep class com.sun.jna.ptr.* { *; }
-keep class com.sun.jna.internal.* { *; }
-keep class * implements com.sun.jna.Callback { *; }
-keep class * implements com.sun.jna.Library { *; }
-keep class * implements com.sun.jna.Structure { *; }
-keep class * extends com.sun.jna.NativeLong { *; }
-dontwarn com.sun.jna.**
-dontnote com.sun.jna.**

# Nucleus modules — project dependencies in multi-module builds can resolve to
# class directories that JvmApplicationRuntimeFiles.kt (line 48) skips with its
# .jar filter. Suppress unresolved references for Nucleus packages to avoid
# false-positive ProGuard failures. The classes are present at runtime.
-dontwarn dev.nucleusframework.**
-dontnote dev.nucleusframework.**

# ── Nucleus JNI bridges ─────────────────────────────────────────────
# Native entry points are resolved by symbol name (Java_<pkg>_<class>_<method>),
# so renaming a class or a method that declares `native` breaks the lookup at the
# first call — an UnsatisfiedLinkError deep inside composition at runtime, never a
# build failure. The rules below are deliberately generic: enumerating bridges
# module by module is what left autolaunch, launcher-linux, notification-linux,
# notification-macos, service-management-macos, system-info, taskbar-progress and
# graalvm-runtime's locale bridge unprotected, and every new native module would
# have to remember to add itself here.

# Any Nucleus class declaring native methods keeps its own name and the names of
# those methods. Unused classes can still be shrunk away.
-keepclasseswithmembernames,includedescriptorclasses class dev.nucleusframework.** {
    native <methods>;
}

# Bridge objects are additionally looked up from native code via FindClass +
# GetStaticMethodID for callbacks (onThemeChanged, onHotKey, onToastActivated,
# onMenuItemClicked, …). Those callbacks are ordinary JVM methods with no
# reachable caller, so ProGuard cannot see them — bridges are kept whole. They
# are thin JNI shims, so the size cost is negligible.
-keep class dev.nucleusframework.**Bridge { *; }
-keep class dev.nucleusframework.**Jni { *; }

# Callback interfaces implemented by application code: native code invokes the
# interface method on instances whose construction ProGuard never observes.
-keep interface dev.nucleusframework.launcher.windows.ThumbBarClickListener {
    void onThumbButtonClick(int);
}
-keep class * implements dev.nucleusframework.launcher.windows.ThumbBarClickListener {
    void onThumbButtonClick(int);
}

# API surfaces whose types cross the JNI boundary or are resolved reflectively
# beyond the bridge objects themselves.
-keep class dev.nucleusframework.window.** { *; }
-keep class dev.nucleusframework.darkmodedetector.** { *; }
-keep class dev.nucleusframework.systemcolor.** { *; }
-keep class dev.nucleusframework.energymanager.** { *; }
-keep class dev.nucleusframework.notification.** { *; }
-keep class dev.nucleusframework.media.control.** { *; }
-keep class dev.nucleusframework.scheduler.** { *; }

-dontwarn sun.misc.Unsafe
-dontwarn sun.awt.**

# Nucleus graalvm-runtime — GraalVM SVM annotations and platform classes are compile-only
-dontwarn com.oracle.svm.core.**
-dontwarn org.graalvm.nativeimage.**
