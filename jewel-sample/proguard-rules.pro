-keepclasseswithmembers public class jewelsample.MainKt {
    public static void main(java.lang.String[]);
}

-dontwarn kotlinx.coroutines.debug.*

-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class org.jetbrains.skia.** { *; }
-keep class org.jetbrains.skiko.** { *; }
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.* { public *; }
-keepclassmembers class * implements com.sun.jna.* { public *; }
-dontwarn com.sun.jna.**

# Keep specific JNA Platform classes used in the project
-keep class com.sun.jna.platform.** { *; }
-keep class com.sun.jna.win32.** { *; }
-dontwarn com.sun.jna.platform.**


-assumenosideeffects public class androidx.compose.runtime.ComposerKt {
    void sourceInformation(androidx.compose.runtime.Composer,java.lang.String);
    void sourceInformationMarkerStart(androidx.compose.runtime.Composer,int,java.lang.String);
    void sourceInformationMarkerEnd(androidx.compose.runtime.Composer);
}

# Keep `Companion` object fields of serializable classes.
# This avoids serializer lookup through `getDeclaredClasses` as done for named companion objects.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Keep `serializer()` on companion objects (both default and named) of serializable classes.
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

# @Serializable and @Polymorphic are used at runtime for polymorphic serialization.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt # core serialization annotations
-dontnote kotlinx.serialization.SerializationKt


# OkHttp platform used only on JVM and when Conscrypt and other security providers are available.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Keep Ktor Kotlinx Serialization provider loaded via ServiceLoader
-keep class io.ktor.serialization.kotlinx.json.KotlinxSerializationJsonExtensionProvider { *; }
-keep class io.ktor.serialization.kotlinx.** { *; }

# Keep SQLite JDBC driver and any Driver implementations discoverable by DriverManager
-keep class org.sqlite.** { *; }
-keep class * implements java.sql.Driver { *; }
-dontwarn org.sqlite.**

# Keep GStreamer Java bindings (avoid enum unboxing/optimization)
-keep class org.freedesktop.gstreamer.** { *; }
-keep enum org.freedesktop.gstreamer.** { *; }
-dontwarn org.freedesktop.gstreamer.**

# Coil, OkHttp, and Okio are used for AsyncImage. Keep them to prevent
# release-only issues where fetchers/decoders or compose adapters are removed.
-keep class coil3.** { *; }
-keep class coil3.compose.** { *; }
-keep class coil3.network.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn coil3.**
-dontwarn okhttp3.**
-dontwarn okio.**

# FileKit dialogs (folder/file pickers). Keep providers and dialog implementations
# as they may be loaded reflectively (or via ServiceLoader) and get stripped.
-keep class io.github.vinceglb.filekit.** { *; }
-dontwarn io.github.vinceglb.filekit.**

# D-Bus bindings may be used on Linux via portals. Keep to be safe in release.
-keep class org.freedesktop.dbus.** { *; }
-dontwarn org.freedesktop.dbus.**
#################################### SLF4J #####################################
-dontwarn org.slf4j.**

# Prevent runtime crashes from use of class.java.getName()
-dontwarn javax.naming.**

# Ignore warnings and Don't obfuscate for now
-dontobfuscate
-ignorewarnings


-keep class sun.misc.Unsafe { *; }
-dontnote sun.misc.Unsafe

-keep class com.jetbrains.JBR* { *; }
-dontnote com.jetbrains.JBR*
-keep class com.jetbrains.** { *; }
-dontwarn com.jetbrains.**
-dontnote com.jetbrains.**

-keep class com.sun.jna** { *; }
-dontnote com.sun.jna**

-keep class androidx.compose.ui.input.key.KeyEvent_desktopKt { *; }
-dontnote androidx.compose.ui.input.key.KeyEvent_desktopKt

-keep class androidx.compose.ui.input.key.KeyEvent_skikoKt { *; }
-dontnote androidx.compose.ui.input.key.KeyEvent_skikoKt
-dontwarn androidx.compose.ui.input.key.KeyEvent_skikoKt

-dontnote org.jetbrains.jewel.intui.markdown.standalone.styling.extensions.**
-dontwarn org.jetbrains.jewel.intui.markdown.standalone.styling.extensions.**

-dontnote org.jetbrains.jewel.foundation.lazy.**
-dontwarn org.jetbrains.jewel.foundation.lazy.**

-dontnote org.jetbrains.jewel.foundation.util.**
-dontwarn org.jetbrains.jewel.foundation.util.**

# Preserve sealed interface metadata so R8/ProGuard doesn't break sealed hierarchies (Java 17)
-keepattributes PermittedSubclasses

# Keep Jewel painter classes to prevent ICCE with sealed interface PainterHint
-keep class org.jetbrains.jewel.ui.painter.** { *; }
-dontwarn org.jetbrains.jewel.ui.painter.**

# --- Fix crash: org.nibor.autolink.LinkType not an enum (ProGuard altering enums) ---
# Keep Autolink library and its enums intact so EnumSet.* works at runtime.
-keep class org.nibor.autolink.** { *; }
-keep enum org.nibor.autolink.** { *; }
-dontwarn org.nibor.autolink.**

# Keep CommonMark autolink extension classes used by Jewel Markdown
-keep class org.commonmark.ext.autolink.** { *; }
-dontwarn org.commonmark.ext.autolink.**

# --- Fix crash: OSHI/JNA WMI enums being altered by ProGuard causing EnumMap NPE ---
# OSHI uses many enums for WMI queries on Windows. If ProGuard/R8 optimizes or rewrites
# these enums, java.util.EnumMap will see a non-enum key type and crash with
# "Cannot read the array length because this.keyUniverse is null" at runtime.
# Preserve OSHI classes and especially its enums intact.
-keep class oshi.** { *; }
-keep enum oshi.** { *; }
-dontwarn oshi.**


# --- Fix crash: Lucene MMapDirectory provider removed by R8/ProGuard in release builds ---
# Lucene's MMapDirectory reflectively loads MemorySegmentIndexInputProvider (Class.forName).
# When code shrinking is enabled, that provider (and related classes) can be removed
# because there are no direct references. Keep Lucene store classes to prevent
# LinkageError/ClassNotFoundException at runtime.
-keep class org.apache.lucene.store.MemorySegmentIndexInputProvider { *; }
-keep class org.apache.lucene.store.MMapDirectory { *; }
-keep class org.apache.lucene.store.** { *; }
-dontwarn org.apache.lucene.**


# Lucene analyzer factories discovered via SPI or reflection
-keep class org.apache.lucene.analysis.util.*Factory { *; }



# --- Fix crash: Lucene Codec SPI providers removed in release builds ---
# Lucene discovers Codec implementations via Java ServiceLoader/NamedSPILoader.
# When shrinking is enabled, concrete codec implementations (e.g., Lucene103Codec)
# can be removed because they are not directly referenced in code, causing:
# ServiceConfigurationError: org.apache.lucene.codecs.Codec: Provider ... not found
# Keep all codec implementations and explicitly the versioned default codec.
-keep class org.apache.lucene.codecs.Codec { *; }
-keep class org.apache.lucene.codecs.** { *; }
-keep class * extends org.apache.lucene.codecs.Codec { *; }
-keep class org.apache.lucene.codecs.lucene103.Lucene103Codec { *; }
# If using a different Lucene version, also keep the corresponding codec package/class
# e.g., lucene90.Lucene90Codec, lucene100.Lucene100Codec, lucene101.Lucene101Codec, etc.
# -keep class org.apache.lucene.codecs.lucene90.Lucene90Codec { *; }
# -keep class org.apache.lucene.codecs.lucene100.Lucene100Codec { *; }
# -keep class org.apache.lucene.codecs.lucene101.Lucene101Codec { *; }



# -----------------------------------------------------------------------------
# Completely disable shrinking/obfuscation for ALL Lucene classes (user request)
# Rationale: Lucene uses SPI/ServiceLoader (e.g., Codecs, Analyzers), reflection,
# and versioned packages. To avoid release-only crashes, keep everything under
# the Lucene namespace intact.
# -----------------------------------------------------------------------------
-keep class org.apache.lucene.** { *; }
-keep interface org.apache.lucene.** { *; }
# Already present above, but keep it close to these rules for clarity:
-dontwarn org.apache.lucene.**


# --- Fix crash: Jsoup Entities$EscapeMode is not an enum (ProGuard altering enums) ---
# Jsoup's cleaner and output settings rely on enums (e.g., Entities$EscapeMode) resolved
# at runtime via Enum.valueOf. If R8/ProGuard rewrites or unboxes these enums, it will crash
# with: IllegalArgumentException: org.jsoup.nodes.Entities$EscapeMode is not an enum class
# Keep Jsoup classes and especially its enums intact.
-keep class org.jsoup.** { *; }
-keep enum org.jsoup.** { *; }
-dontwarn org.jsoup.**

# --- Fix crash: HebrewDateFormatter EnumMap NPE (ProGuard altering enums) ---
# Zmanim's HebrewDateFormatter builds EnumMap<JewishCalendar.Parsha, String> at runtime.
# If R8/ProGuard rewrites or unboxes these enums, EnumMap will crash with:
# "Cannot read the array length because this.keyUniverse is null".
-keep class com.kosherjava.zmanim.** { *; }
-keep enum com.kosherjava.zmanim.** { *; }
-dontwarn com.kosherjava.zmanim.**

# --- Fix: Community enum used with valueOf() for Kiddush Levana opinion selection ---
# The Community enum is resolved at runtime via Enum.valueOf(code) where code is stored
# in AppSettings. If R8/ProGuard optimizes or unboxes this enum, valueOf() will fail.
-keep enum dev.nucleusframework.seforimapp.features.onboarding.userprofile.Community { *; }


# =============================================================================
# Nucleus JNI keep rules (must be added manually because Zayit overrides the
# default ProGuard config via configurationFiles.from(...), which prevents the
# Nucleus plugin from auto-injecting its own rules)
# =============================================================================

# Nucleus decorated-window JNI (macOS)
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

# Nucleus native-ssl JNI (macOS)
-keep class dev.nucleusframework.nucleus.nativessl.mac.NativeSslBridge {
    native <methods>;
}

# Nucleus native-ssl JNI (Windows)
-keep class dev.nucleusframework.nucleus.nativessl.windows.WindowsSslBridge {
    native <methods>;
}

# Nucleus system-color JNI (macOS)
-keep class dev.nucleusframework.nucleus.systemcolor.mac.NativeMacSystemColorBridge {
    native <methods>;
    static void onAccentColorChanged(float, float, float);
    static void onAccentColorCleared();
    static void onContrastChanged(boolean);
}

# Nucleus system-color JNI (Linux)
-keep class dev.nucleusframework.nucleus.systemcolor.linux.NativeLinuxSystemColorBridge {
    native <methods>;
    static void onAccentColorChanged(float, float, float);
    static void onHighContrastChanged(boolean);
}

# Nucleus system-color JNI (Windows)
-keep class dev.nucleusframework.nucleus.systemcolor.windows.NativeWindowsSystemColorBridge {
    native <methods>;
    static void onAccentColorChanged(int, int, int);
    static void onHighContrastChanged(boolean);
}
-keep class dev.nucleusframework.nucleus.systemcolor.** { *; }

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

# Nucleus linux-hidpi JNI
-keep class dev.nucleusframework.nucleus.hidpi.HiDpiLinuxBridge {
    native <methods>;
}

# --- Sentry crash reporting SDK ---
# Sentry uses reflection for serialization and event processing.
-keep class io.sentry.** { *; }
-dontwarn io.sentry.**
