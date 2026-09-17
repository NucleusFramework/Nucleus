/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.internal

import java.io.File

/**
 * Passwordless, signature-verified self-update for signed Linux DEB/RPM installs (issue #158).
 *
 * Install model (single source of truth):
 * - **Package payload** ships [HELPER_FILE_NAME] next to the launcher and
 *   [PUBLIC_KEY_RELATIVE_PATH] under the app dir. Those paths must be package-owned so
 *   `dpkg -S` / `rpm -qf` can attribute the helper to this app.
 * - **afterInstall** only hardens permissions and installs a polkit policy scoped to that
 *   helper path (`allow_active=yes`). It must not rewrite the helper.
 * - **afterRemove** deletes that polkit policy on uninstall.
 *
 * Runtime: the app downloads `<pkg>.asc` next to the package and runs
 * `pkexec /opt/<app>/nucleus-update-helper <pkg>` (see updater `PlatformInstaller`).
 *
 * Exit codes of the helper: 2 usage, 3 package mismatch, 4 missing key/signature,
 * gpgv's own non-zero on a failed signature.
 *
 * The helper file name is mirrored in `updater-runtime` (`PlatformInstaller.UPDATE_HELPER_NAME`);
 * keep both in sync.
 */
internal object LinuxUpdateHelper {
    const val HELPER_FILE_NAME: String = "nucleus-update-helper"
    const val PUBLIC_KEY_RELATIVE_PATH: String = "resources/nucleus-update.pub.asc"

    /**
     * Self-contained bash helper: copy the caller-supplied package and signature into a
     * root-owned working directory, verify the detached signature of that copy against the
     * bundled public key, ensure the package upgrades only the app that owns this helper and is
     * not a downgrade, then `dpkg -i` / `rpm -U` the verified copy.
     *
     * Security notes:
     * - The helper runs as **root** via pkexec, but the caller-supplied path is writable by the
     *   unprivileged invoking user. Verifying that path and then re-opening it for install would
     *   be a TOCTOU: the bytes could be swapped between `gpg --verify` and `dpkg -i`. Copying into
     *   a root-owned dir *before* verifying, then installing the copy, removes that window.
     * - `dpkg -i` will install an older, still-validly-signed release (a rollback to a known-
     *   vulnerable version), so the deb path refuses anything that is not a strict version
     *   increase. `rpm -U` (without `--oldpackage`) already refuses downgrades.
     */
    val SCRIPT: String =
        $$"""
        #!/usr/bin/env bash
        # Installed by Nucleus as a package-owned file. Verifies a signed update against the
        # bundled public key and, if valid, for this same app and a strict upgrade, installs it.
        # Invoked via pkexec (see polkit policy installed by afterInstall).
        set -eu
        if [ "$#" -lt 1 ]; then echo "usage: nucleus-update-helper <package>" >&2; exit 2; fi
        SRC_PKG="$1"
        [ -f "$SRC_PKG" ] || { echo "package not found: $SRC_PKG" >&2; exit 2; }
        SRC_SIG="$SRC_PKG.asc"
        [ -f "$SRC_SIG" ] || { echo "missing signature: $SRC_SIG" >&2; exit 4; }
        SELF="$(readlink -f "$0")"
        APPDIR="$(dirname "$SELF")"
        PUBKEY="$APPDIR/resources/nucleus-update.pub.asc"
        [ -f "$PUBKEY" ] || { echo "missing public key: $PUBKEY" >&2; exit 4; }

        # Copy into a root-owned working dir BEFORE verifying, then verify and install the copies.
        # The source path is writable by the unprivileged caller, so verifying it and re-opening it
        # for install would let the bytes be swapped in between (TOCTOU); the root-owned copy cannot.
        WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT; chmod 700 "$WORK"
        PKG="$WORK/$(basename "$SRC_PKG")"
        SIG="$PKG.asc"
        cp -- "$SRC_PKG" "$PKG"
        cp -- "$SRC_SIG" "$SIG"

        # Verify the detached signature of the copy against the bundled key in a throwaway keyring.
        # gpgv never starts a gpg-agent; gpg --import/--verify would daemonize one for this homedir
        # and, having no idle timeout, it would keep the app's systemd scope alive forever (#567).
        # gpg --dearmor is a pure filter: no keyring or agent access.
        KR="$WORK/keyring"; mkdir -p "$KR"; chmod 700 "$KR"
        gpg --homedir "$KR" --batch --quiet --dearmor < "$PUBKEY" > "$KR/pub.gpg"
        gpgv --keyring "$KR/pub.gpg" "$SIG" "$PKG"

        # Only upgrade THIS app: new package name must match the package that owns the helper.
        # The helper is shipped in the package payload so package managers track this path.
        case "$PKG" in
          *.deb)
            OWNER="$(dpkg -S "$SELF" 2>/dev/null | cut -d: -f1 | head -n1)"
            NEW="$(dpkg-deb -f "$PKG" Package)"
            [ -n "$OWNER" ] && [ "$NEW" = "$OWNER" ] || { echo "package mismatch: $NEW != $OWNER" >&2; exit 3; }
            # Refuse downgrades: dpkg -i does not, so a signed older release could roll the app back.
            CUR="$(dpkg-query -W -f='${Version}' "$OWNER" 2>/dev/null || true)"
            NEWVER="$(dpkg-deb -f "$PKG" Version)"
            if [ -n "$CUR" ] && ! dpkg --compare-versions "$NEWVER" gt "$CUR"; then
              echo "refusing non-upgrade: $NEWVER is not newer than installed $CUR" >&2; exit 5
            fi
            # Not exec: the EXIT trap must still run to remove the root-owned work dir (#567).
            dpkg -i "$PKG"
            ;;
          *.rpm)
            OWNER="$(rpm -qf --qf '%{NAME}' "$SELF" 2>/dev/null || true)"
            NEW="$(rpm -qp --qf '%{NAME}' "$PKG")"
            [ -n "$OWNER" ] && [ "$NEW" = "$OWNER" ] || { echo "package mismatch: $NEW != $OWNER" >&2; exit 3; }
            # rpm -U (without --oldpackage) already refuses downgrades.
            # Not exec: the EXIT trap must still run to remove the root-owned work dir (#567).
            rpm -U "$PKG"
            ;;
          *) echo "unsupported package: $PKG" >&2; exit 2 ;;
        esac
        """.trimIndent()

    /** Writes the package-owned helper next to the launcher inside the app image. */
    fun writeHelper(appDir: File) {
        val helper = appDir.resolve(HELPER_FILE_NAME)
        helper.writeText(SCRIPT + "\n")
        helper.setExecutable(true)
    }

    /**
     * afterInstall fragment: harden the **already packaged** helper and install a polkit policy
     * that lets an active local session run only that path without a password.
     *
     * Uses electron-builder macros `${sanitizedProductName}` / `${executable}` —
     * substituted at package time. Does **not** rewrite the helper body.
     */
    fun polkitAfterInstallFragment(): String =
        $$"""

        # --- Nucleus passwordless self-update (signature-verified) ---
        # Helper + public key are package payload files (see LinuxUpdateHelper.writeHelper).
        NUCLEUS_HELPER='/opt/${sanitizedProductName}/nucleus-update-helper'
        NUCLEUS_POLKIT_ACTION='dev.nucleusframework.${executable}.update'

        if [ -f "$NUCLEUS_HELPER" ]; then
          chmod 0755 "$NUCLEUS_HELPER"
          chown root:root "$NUCLEUS_HELPER" 2>/dev/null || true
        else
          echo "Nucleus silent update: missing $NUCLEUS_HELPER (not bundled in package)" >&2
        fi

        # polkit: an ACTIVE local session may run ONLY this helper without a password.
        POLKIT_DIR='/usr/share/polkit-1/actions'
        if mkdir -p "$POLKIT_DIR" 2>/dev/null; then
        cat > "$POLKIT_DIR/$NUCLEUS_POLKIT_ACTION.policy" <<NUCLEUS_POLKIT_EOF
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE policyconfig PUBLIC "-//freedesktop//DTD PolicyKit Policy Configuration 1.0//EN"
         "http://www.freedesktop.org/standards/PolicyKit/1/policyconfig.dtd">
        <policyconfig>
          <action id="$NUCLEUS_POLKIT_ACTION">
            <description>Install ${sanitizedProductName} updates</description>
            <message>Authentication is required to install ${sanitizedProductName} updates</message>
            <defaults>
              <allow_any>auth_admin</allow_any>
              <allow_inactive>auth_admin</allow_inactive>
              <allow_active>yes</allow_active>
            </defaults>
            <annotate key="org.freedesktop.policykit.exec.path">$NUCLEUS_HELPER</annotate>
            <annotate key="org.freedesktop.policykit.exec.allow_gui">true</annotate>
          </action>
        </policyconfig>
        NUCLEUS_POLKIT_EOF
        fi
        """.trimIndent() + "\n"

    /**
     * afterRemove fragment: drop the polkit policy installed by [polkitAfterInstallFragment].
     * The helper binary is removed with the package payload; only the policy lives outside `/opt`.
     */
    fun polkitAfterRemoveFragment(): String =
        $$"""

        # --- Nucleus passwordless self-update cleanup ---
        rm -f '/usr/share/polkit-1/actions/dev.nucleusframework.${executable}.update.policy' 2>/dev/null || true
        """.trimIndent() + "\n"
}
