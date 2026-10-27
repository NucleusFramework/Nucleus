package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.dsl.PolkitActionDefinition

/**
 * The `linux.polkitActions` of a Deb/RPM/Pacman package: afterInstall writes one `.policy` per
 * action under [ACTIONS_DIR], afterRemove deletes them on a real uninstall.
 *
 * Both fragments go through electron-builder's template pass, which substitutes (or rejects)
 * every `${name}`: user text reaches the script XML-escaped, `$` included, inside a quoted
 * heredoc the shell does not expand.
 */
internal object LinuxPolkitActions {
    const val ACTIONS_DIR: String = "/usr/share/polkit-1/actions"
    private const val HEREDOC_END = "NUCLEUS_POLKIT_ACTION_EOF"

    fun policyPath(id: String): String = "$ACTIONS_DIR/$id.policy"

    /** The `.policy` document declaring [action]. */
    fun policyXml(action: PolkitActionDefinition): String {
        val message = action.message.orEmpty()
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE policyconfig PUBLIC "-//freedesktop//DTD PolicyKit Policy Configuration 1.0//EN"
             "http://www.freedesktop.org/standards/PolicyKit/1/policyconfig.dtd">
            <policyconfig>
              <action id="${action.id}">
                <description>${xml(action.description ?: message)}</description>
                <message>${xml(message)}</message>
                <defaults>
                  <allow_any>${action.allowAny.value}</allow_any>
                  <allow_inactive>${action.allowInactive.value}</allow_inactive>
                  <allow_active>${action.allowActive.value}</allow_active>
                </defaults>
              </action>
            </policyconfig>
            """.trimIndent()
    }

    /** afterInstall fragment writing every action's `.policy`; empty without actions. */
    fun afterInstallFragment(actions: List<PolkitActionDefinition>): String {
        if (actions.isEmpty()) return ""
        return buildString {
            append("\n# --- Nucleus polkit actions (linux.polkitActions) ---\n")
            append("mkdir -p '$ACTIONS_DIR'\n")
            for (action in actions) {
                val path = policyPath(action.id)
                append("cat > '$path' <<'$HEREDOC_END'\n")
                append(policyXml(action))
                append("\n$HEREDOC_END\n")
                append("chmod 0644 '$path'\n")
            }
        }
    }

    /** afterRemove fragment deleting every action's `.policy`; empty without actions. */
    fun afterRemoveFragment(actions: List<PolkitActionDefinition>): String {
        if (actions.isEmpty()) return ""
        return "\n# --- Nucleus polkit actions cleanup ---\n" + removeUnlessUpgrade(actions.map { policyPath(it.id) })
    }

    /**
     * Deletes [paths] unless this remove script runs for an upgrade: dpkg passes `upgrade`, RPM
     * the count of installs left (≥ 1). RPM runs the old package's script after the new one's
     * afterInstall, so an unconditional delete would drop the files the upgrade just wrote.
     * Pacman passes the old version (`1.0-1`), which never matches.
     */
    fun removeUnlessUpgrade(paths: List<String>): String =
        """
        case "$1" in
          upgrade|failed-upgrade|[1-9]|[1-9][0-9]) ;;
          *) rm -f ${paths.joinToString(" ") { "'$it'" }} 2>/dev/null || true ;;
        esac
        """.trimIndent() + "\n"

    private fun xml(text: String): String =
        buildString {
            for (c in text) {
                when (c) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    // electron-builder's template pass would take `${…}` for a macro.
                    '$' -> append("&#36;")
                    // One line per element: no user text can end the heredoc.
                    '\n' -> append("&#10;")
                    '\r' -> append("&#13;")
                    else -> append(c)
                }
            }
        }
}
