package dev.nucleusframework.autolaunch

/**
 * Test double for [primePlatformIntegrations]. The production class lives in
 * the optional `autolaunch` module; this stand-in is only on the test classpath
 * so the reflection path can be exercised without that dependency.
 */
object AutoLaunch {
    @JvmField
    @Volatile
    var lastArgs: Array<String>? = null

    @JvmField
    @Volatile
    var calls: Int = 0

    @JvmStatic
    fun reset() {
        lastArgs = null
        calls = 0
    }

    @JvmStatic
    fun wasStartedAtLogin(args: Array<String>): Boolean {
        lastArgs = args
        calls++
        return args.contains("--autostart")
    }
}
