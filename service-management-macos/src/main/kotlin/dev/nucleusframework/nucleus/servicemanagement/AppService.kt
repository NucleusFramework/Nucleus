package dev.nucleusframework.nucleus.servicemanagement

/**
 * Describes an app service to manage with [AppServiceManager].
 *
 * Maps to the `SMAppService` factory methods and properties:
 * - [MainApp] — `SMAppService.mainApp` (registers the app itself as a login item)
 * - [LoginItem] — `SMAppService.loginItem(identifier:)`
 * - [Agent] — `SMAppService.agent(plistName:)` (plist in `Contents/Library/LaunchAgents/`)
 * - [Daemon] — `SMAppService.daemon(plistName:)` (plist in `Contents/Library/LaunchDaemons/`)
 */
public sealed class AppService(
    internal val type: Int,
    internal val identifier: String,
) {
    /**
     * Registers the main application itself as a login item.
     *
     * This is the simplest way to make the app launch at user login —
     * no helper app or plist required.
     */
    public object MainApp : AppService(TYPE_MAIN_APP, "")

    /**
     * A login item helper that launches at user login.
     *
     * @param bundleIdentifier the bundle identifier of the login item helper
     *        (located in `Contents/Library/LoginItems/`)
     */
    public class LoginItem(
        bundleIdentifier: String,
    ) : AppService(TYPE_LOGIN_ITEM, bundleIdentifier)

    /**
     * A launch agent that runs in the user session.
     *
     * @param label the agent label matching the Gradle DSL `agent()` declaration
     *        (e.g. `"com.myapp.agent"`). The `.plist` suffix is added automatically if missing.
     */
    public class Agent(
        label: String,
    ) : AppService(TYPE_AGENT, label.ensurePlistSuffix())

    /**
     * A launch daemon that runs as root.
     *
     * @param label the daemon label matching the plist filename in `Contents/Library/LaunchDaemons/`
     *        (e.g. `"com.myapp.daemon"`). The `.plist` suffix is added automatically if missing.
     */
    public class Daemon(
        label: String,
    ) : AppService(TYPE_DAEMON, label.ensurePlistSuffix())

    internal companion object {
        const val TYPE_MAIN_APP = 3
        const val TYPE_LOGIN_ITEM = 0
        const val TYPE_AGENT = 1
        const val TYPE_DAEMON = 2

        private fun String.ensurePlistSuffix(): String = if (endsWith(".plist")) this else "$this.plist"
    }
}
