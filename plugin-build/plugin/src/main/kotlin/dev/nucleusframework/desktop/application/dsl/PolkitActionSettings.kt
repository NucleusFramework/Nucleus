package dev.nucleusframework.desktop.application.dsl

import org.gradle.api.Action
import java.io.Serializable

/**
 * DSL block for declaring polkit actions that the Deb, RPM and Pacman packages install under
 * `/usr/share/polkit-1/actions/` and remove on uninstall.
 *
 * polkit only checks actions an installed `.policy` file declares, and its agent shows that
 * file's message: `nucleus.authentication` on Linux needs one, named by
 * `AuthenticationPrompt.linuxActionId`.
 *
 * ```kotlin
 * linux {
 *     polkitActions {
 *         action("com.example.app.unlock") {
 *             message = "Authentication is required to unlock Example"
 *         }
 *     }
 * }
 * ```
 *
 * Flatpak, Snap and AppImage cannot install system files: these actions are not shipped there.
 */
class PolkitActionSettings : Serializable {
    internal val actions: MutableList<PolkitActionDefinition> = mutableListOf()

    /**
     * Declares the polkit action [id], written to `/usr/share/polkit-1/actions/<id>.policy`.
     *
     * @param id reverse-DNS action id (e.g. `"com.example.app.unlock"`): letters, digits, `.`, `-`
     * and `_`, starting with a letter or digit.
     */
    fun action(
        id: String,
        fn: Action<PolkitActionDefinition>,
    ) {
        require(ACTION_ID.matches(id)) { "Invalid polkit action id '$id': use letters, digits, '.', '-' and '_'" }
        require(actions.none { it.id == id }) { "polkit action '$id' is declared twice" }
        val definition = PolkitActionDefinition(id)
        fn.execute(definition)
        require(!definition.message.isNullOrBlank()) { "polkit action '$id' needs a message" }
        // Written as one CDATA each (see LinuxPolkitActions.policyXml): anything that splits
        // it would reach polkitd truncated.
        for (text in listOfNotNull(definition.message, definition.description)) {
            require(text.none { it == '\n' || it == '\r' } && "]]>" !in text) {
                "polkit action '$id': the message and description must be one line without ']]>'"
            }
        }
        actions.add(definition)
    }

    /** Serialization constants. */
    companion object {
        private const val serialVersionUID = 1L
        private val ACTION_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    }
}

/** One polkit action: the text its agent shows and who may perform it. */
class PolkitActionDefinition(
    /** The action id, also the `.policy` file name. */
    val id: String,
) : Serializable {
    /** The dialog text (`<message>`), on one line. Required. */
    var message: String? = null

    /** What `pkaction` lists (`<description>`); [message] when `null`. */
    var description: String? = null

    /** Subjects in an active local session. Defaults to the user's own password. */
    var allowActive: PolkitAuthorization = PolkitAuthorization.AuthSelf

    /** Subjects in an inactive local session. */
    var allowInactive: PolkitAuthorization = PolkitAuthorization.No

    /** Any other subject, remote sessions included. */
    var allowAny: PolkitAuthorization = PolkitAuthorization.No

    /** Serialization constants. */
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** What polkit answers for a subject, a `<defaults>` value of a `.policy` action. */
enum class PolkitAuthorization(
    internal val value: String,
) {
    /** Always denied, without a prompt. */
    No("no"),

    /** Always granted, without a prompt. */
    Yes("yes"),

    /** The user's own password. */
    AuthSelf("auth_self"),

    /** The user's own password, remembered for a few minutes. */
    AuthSelfKeep("auth_self_keep"),

    /** An administrator's password. */
    AuthAdmin("auth_admin"),

    /** An administrator's password, remembered for a few minutes. */
    AuthAdminKeep("auth_admin_keep"),
}
