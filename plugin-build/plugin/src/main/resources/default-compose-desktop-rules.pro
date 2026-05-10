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

# Nucleus - Silence warnings and notes for all modules (many are optional at runtime)
-dontwarn dev.nucleusframework.nucleus.**
-dontnote dev.nucleusframework.nucleus.**

# Nucleus decorated-window JNI
-keep class dev.nucleusframework.nucleus.window.utils.macos.NativeMacBridge {
    native <methods>;
}
-keep class dev.nucleusframework.nucleus.window.** { *; }

# Nucleus darkmode-detector JNI (macOS)
# NativeDarkModeBridge is looked up by name from native code (FindClass + GetStaticMethodID)
-keep class dev.nucleusframework.nucleus.darkmodedetector.mac.NativeDarkModeBridge {
    native <methods>;
    static void onThemeChanged(boolean);
}

# Nucleus darkmode-detector JNI (Linux)
# NativeLinuxBridge is looked up by name from native code (FindClass + GetStaticMethodID)
-keep class dev.nucleusframework.nucleus.darkmodedetector.linux.NativeLinuxBridge {
    native <methods>;
    static void onThemeChanged(boolean);
}

# Nucleus darkmode-detector JNI (Windows)
-keep class dev.nucleusframework.nucleus.darkmodedetector.windows.NativeWindowsBridge {
    native <methods>;
}
-keep class dev.nucleusframework.nucleus.darkmodedetector.** { *; }
-dontwarn dev.nucleusframework.nucleus.darkmodedetector.**

# Nucleus native-ssl JNI (macOS)
-keep class dev.nucleusframework.nucleus.nativessl.mac.NativeSslBridge {
    native <methods>;
}

# Nucleus native-ssl JNI (Windows)
-keep class dev.nucleusframework.nucleus.nativessl.windows.WindowsSslBridge {
    native <methods>;
}
-dontwarn dev.nucleusframework.nucleus.nativessl.**

# Nucleus system-color JNI (macOS)
# NativeMacSystemColorBridge is looked up by name from native code (FindClass + GetStaticMethodID)
-keep class dev.nucleusframework.nucleus.systemcolor.mac.NativeMacSystemColorBridge {
    native <methods>;
    static void onAccentColorChanged(float, float, float);
    static void onAccentColorCleared();
    static void onContrastChanged(boolean);
}

# Nucleus system-color JNI (Linux)
# NativeLinuxSystemColorBridge is looked up by name from native code (FindClass + GetStaticMethodID)
-keep class dev.nucleusframework.nucleus.systemcolor.linux.NativeLinuxSystemColorBridge {
    native <methods>;
    static void onAccentColorChanged(float, float, float);
    static void onHighContrastChanged(boolean);
}

# Nucleus system-color JNI (Windows)
# NativeWindowsSystemColorBridge is looked up by name from native code (FindClass + GetStaticMethodID)
-keep class dev.nucleusframework.nucleus.systemcolor.windows.NativeWindowsSystemColorBridge {
    native <methods>;
    static void onAccentColorChanged(int, int, int);
    static void onHighContrastChanged(boolean);
}
-keep class dev.nucleusframework.nucleus.systemcolor.** { *; }
-dontwarn dev.nucleusframework.nucleus.systemcolor.**

# Nucleus energy-manager JNI (macOS)
-keep class dev.nucleusframework.nucleus.energymanager.macos.NativeMacOsEnergyBridge {
    native <methods>;
}

# Nucleus energy-manager JNI (Linux)
-keep class dev.nucleusframework.nucleus.energymanager.linux.NativeLinuxEnergyBridge {
    native <methods>;
}

# Nucleus energy-manager JNI (Windows)
-keep class dev.nucleusframework.nucleus.energymanager.windows.NativeWindowsEnergyBridge {
    native <methods>;
}
-keep class dev.nucleusframework.nucleus.energymanager.** { *; }
-dontwarn dev.nucleusframework.nucleus.energymanager.**

# Nucleus linux-hidpi JNI
-keep class dev.nucleusframework.nucleus.hidpi.HiDpiLinuxBridge {
    native <methods>;
}
-dontwarn dev.nucleusframework.nucleus.hidpi.**

# Nucleus notification-windows JNI — static callbacks invoked from native via FindClass/GetStaticMethodID
-keep class dev.nucleusframework.nucleus.notification.windows.NativeWindowsNotificationBridge {
    native <methods>;
    static void onToastActivated(java.lang.String, java.lang.String, java.lang.String, java.lang.String[], java.lang.String[]);
    static void onToastDismissed(java.lang.String, java.lang.String, int);
    static void onToastFailed(java.lang.String, java.lang.String, int);
    static void onToastShown(long, java.lang.String);
    static void onToastUpdated(long, java.lang.String);
    static void onHistoryResult(long, java.lang.String[], java.lang.String[], java.lang.String);
}
-keep class dev.nucleusframework.nucleus.notification.windows.** { *; }
-keep class dev.nucleusframework.nucleus.notification.common.** { *; }
-dontwarn dev.nucleusframework.nucleus.notification.**

# Nucleus media-control JNI — native code uses FindClass(BRIDGE_CLASS) + static callbacks
-keep class dev.nucleusframework.nucleus.mediacontrol.** { *; }
-dontwarn dev.nucleusframework.nucleus.mediacontrol.**

# Nucleus scheduler JNI
-keep class dev.nucleusframework.nucleus.scheduler.** { *; }
-dontwarn dev.nucleusframework.nucleus.scheduler.**

# Nucleus global-hotkey JNI — onHotKey is invoked from native code via JNI
-keep class dev.nucleusframework.nucleus.globalhotkey.windows.NativeWindowsHotKeyBridge {
    native <methods>;
    static void onHotKey(long, int, int);
}
-keep class dev.nucleusframework.nucleus.globalhotkey.macos.NativeMacOsHotKeyBridge {
    native <methods>;
    static void onHotKey(long, int, int);
}
-keep class dev.nucleusframework.nucleus.globalhotkey.linux.NativeLinuxHotKeyBridge {
    native <methods>;
    static void onHotKey(long, int, int);
}
-dontwarn dev.nucleusframework.nucleus.globalhotkey.**

# Nucleus launcher-windows JNI — ThumbBarClickListener.onThumbButtonClick is invoked from native
-keep class dev.nucleusframework.nucleus.launcher.windows.NativeWindowsBadgeBridge { native <methods>; }
-keep class dev.nucleusframework.nucleus.launcher.windows.NativeWindowsJumpListBridge { native <methods>; }
-keep class dev.nucleusframework.nucleus.launcher.windows.NativeWindowsTaskbarBridge { native <methods>; }
-keep interface dev.nucleusframework.nucleus.launcher.windows.ThumbBarClickListener {
    void onThumbButtonClick(int);
}
-keep class * implements dev.nucleusframework.nucleus.launcher.windows.ThumbBarClickListener {
    void onThumbButtonClick(int);
}
-dontwarn dev.nucleusframework.nucleus.launcher.**

-dontwarn dev.nucleusframework.nucleus.core.runtime.**

-dontwarn sun.misc.Unsafe
-dontwarn sun.awt.**

# Nucleus graalvm-runtime — GraalVM SVM annotations and platform classes are compile-only
-dontwarn com.oracle.svm.core.**
-dontwarn org.graalvm.nativeimage.**
