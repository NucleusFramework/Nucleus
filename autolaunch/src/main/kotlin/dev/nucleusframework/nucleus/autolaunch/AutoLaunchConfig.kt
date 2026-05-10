package dev.nucleusframework.nucleus.autolaunch

/**
 * Optional overrides for auto-launch. Defaults fall back to [dev.nucleusframework.nucleus.core.runtime.NucleusApp].
 *
 * Configure before the first call to [AutoLaunch.state]/`enable`/`disable`:
 * ```
 * AutoLaunchConfig.taskId = "com.example.MyAppStartup"
 * AutoLaunchConfig.executablePath = "C:\\Program Files\\MyApp\\MyApp.exe"
 * ```
 */
public object AutoLaunchConfig {
    /**
     * MSIX StartupTask TaskId. Must match `<uap5:StartupTask TaskId="...">` in the manifest.
     *
     * Resolution order: this property → `NucleusApp.startupTaskId` (auto-injected by the
     * Nucleus Gradle plugin when `appx.addAutoLaunchExtension = true`) → `NucleusApp.appId`.
     */
    @JvmStatic
    public var taskId: String? = null

    /**
     * Absolute path to the executable to register in `HKCU\...\Run` (Win32/MSI/NSIS path only).
     * If `null`, resolved from `ProcessHandle.current().info().command()`.
     */
    @JvmStatic
    public var executablePath: String? = null

    /**
     * CLI argument appended to the `Run` registry value. The runtime does not act on this —
     * your `main()` is free to inspect `args` for this flag. Set to `null` to omit.
     */
    @JvmStatic
    public var autostartArgument: String? = "--nucleus-autostart"

    /**
     * Registry value name / Run entry key under `HKCU\...\Run`. If `null`, defaults to
     * `NucleusApp.appName` then `NucleusApp.appId`.
     */
    @JvmStatic
    public var registryValueName: String? = null

    /**
     * Human-readable reason displayed by `org.freedesktop.portal.Background` when
     * prompting for autostart consent (Linux Flatpak only). If `null`, defaults to
     * `"Launch <appName> at login"`.
     */
    @JvmStatic
    public var backgroundReason: String? = null
}
