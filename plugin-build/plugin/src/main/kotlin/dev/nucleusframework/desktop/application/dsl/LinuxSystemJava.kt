package dev.nucleusframework.desktop.application.dsl

/**
 * Minimum JRE the Linux package manager must install, used instead of a bundled jlink runtime.
 *
 * Set [LinuxPlatformSettings.systemJava] to one of these values. Nucleus then omits
 * `lib/runtime` from **deb / rpm / pacman** packages, ships a launcher that execs the
 * system `java`, and adds the matching package dependency so apt/dnf/pacman pulls a
 * full (non-headless) JRE at install time if one is missing.
 *
 * AppImage, Snap and Flatpak keep the bundled runtime — those formats cannot depend
 * on the host's Java. JDK 25+ AOT cache generation is skipped on Linux when every
 * package for this OS uses system Java: the cache is bound to a bundled JVM.
 *
 * The packaging JDK (`JAVA_HOME` / `javaHome`) must not be newer than [major]:
 * building with JDK 25 while `systemJava = Java21` fails the build, because that
 * JRE cannot load JDK 25 class files.
 *
 * ```kotlin
 * linux {
 *     systemJava = LinuxSystemJava.Java21
 * }
 * ```
 */
@Suppress("MagicNumber") // The majors *are* the API: Java 17 / 21 / 25.
enum class LinuxSystemJava(
    /** Language major version required at runtime (17, 21 or 25). */
    val major: Int,
) {
    /** Require a Java 17+ full JRE (`java.desktop`). */
    Java17(17),

    /** Require a Java 21+ full JRE (`java.desktop`). */
    Java21(21),

    /** Require a Java 25+ full JRE (`java.desktop`). */
    Java25(25),
    ;

    /**
     * Debian/Ubuntu `Depends` entry. Newer LTS virtual packages are listed as
     * alternatives so a machine that only has Java 25 still satisfies a Java 21 app.
     */
    internal val debDepends: String
        get() =
            when (this) {
                // javaN-runtime is Debian Java Policy; java-runtime (= N) is what
                // current Ubuntu OpenJDK JRE packages actually Provide (25 has no
                // java25-runtime virtual yet).
                Java17 -> "java17-runtime | java-runtime (>= 17)"
                Java21 -> "java21-runtime | java-runtime (>= 21)"
                Java25 -> "java25-runtime | java-runtime (>= 25)"
            }

    /** Fedora/RHEL `Requires` entry. Boolean deps pick any matching GUI OpenJDK. */
    internal val rpmRequires: String
        get() =
            when (this) {
                Java17 -> "(java-17-openjdk or java-21-openjdk or java-25-openjdk)"
                Java21 -> "(java-21-openjdk or java-25-openjdk)"
                Java25 -> "java-25-openjdk"
            }

    /** Arch `depends` entry. `java-runtime=N` is the versioned virtual provide. */
    internal val pacmanDepends: String
        get() = "java-runtime>=$major"

    internal companion object {
        fun fromMajor(major: Int): LinuxSystemJava? = entries.find { it.major == major }
    }
}
