package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.dsl.PolkitActionDefinition
import java.io.File

/**
 * The `linux.polkitActions` of a Deb/RPM/Pacman package: the `.policy` files ship in the payload
 * under [PAYLOAD_DIR], afterInstall copies them to [ACTIONS_DIR], afterRemove deletes them on a
 * real uninstall.
 *
 * In the payload rather than inlined in afterInstall: electron-builder's template pass
 * substitutes (or rejects) every `${name}` of the scripts, and the text cannot be escaped for it,
 * see [policyXml].
 */
internal object LinuxPolkitActions {
    const val ACTIONS_DIR: String = "/usr/share/polkit-1/actions"

    /** Relative to the app dir, installed as `/opt/<sanitizedProductName>/`. */
    const val PAYLOAD_DIR: String = "resources/polkit-actions"

    fun policyPath(id: String): String = "$ACTIONS_DIR/$id.policy"

    /**
     * The `.policy` document declaring [action]. Texts go in CDATA, never escaped: polkitd keeps
     * only the last character-data chunk expat hands it, and expat splits text at every entity,
     * so `A &amp; B` reads as ` B`. `PolkitActionSettings.action` rejects what would split a CDATA.
     */
    fun policyXml(action: PolkitActionDefinition): String {
        val message = action.message.orEmpty()
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE policyconfig PUBLIC "-//freedesktop//DTD PolicyKit Policy Configuration 1.0//EN"
             "http://www.freedesktop.org/standards/PolicyKit/1/policyconfig.dtd">
            <policyconfig>
              <action id="${action.id}">
                <description><![CDATA[${action.description ?: message}]]></description>
                <message><![CDATA[$message]]></message>
                <defaults>
                  <allow_any>${action.allowAny.value}</allow_any>
                  <allow_inactive>${action.allowInactive.value}</allow_inactive>
                  <allow_active>${action.allowActive.value}</allow_active>
                </defaults>
              </action>
            </policyconfig>
            """.trimIndent() + "\n"
    }

    /** Writes every action's `.policy` into the app image, under [PAYLOAD_DIR]. */
    fun writePolicies(
        appDir: File,
        actions: List<PolkitActionDefinition>,
    ) {
        if (actions.isEmpty()) return
        val dir = appDir.resolve(PAYLOAD_DIR).apply { mkdirs() }
        for (action in actions) {
            dir.resolve("${action.id}.policy").writeText(policyXml(action))
        }
    }

    /** afterInstall fragment copying every action's `.policy` out of the payload; empty without actions. */
    fun afterInstallFragment(actions: List<PolkitActionDefinition>): String {
        if (actions.isEmpty()) return ""
        return buildString {
            append("\n# --- Nucleus polkit actions (linux.polkitActions) ---\n")
            append("mkdir -p '$ACTIONS_DIR'\n")
            for (action in actions) {
                val source = "/opt/\${sanitizedProductName}/$PAYLOAD_DIR/${action.id}.policy"
                append("install -m 0644 '$source' '${policyPath(action.id)}'\n")
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
}
