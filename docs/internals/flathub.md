# Flathub

The Lab ships on Flathub as `dev.nucleusframework.Lab`, the way a closed-source Nucleus app would:
Flathub compiles nothing, its manifest repackages the GraalVM native image the release already
publishes (`nucleus-lab-<version>-linux-{x64,arm64}.tar.gz`, built by `packageGraalvmTar` in
`release-desktop`).

## `linux { flathub { } }` and `generateFlathubRepository`

The plugin writes the Flathub repository of any app from its DSL. Flathub's review wants nothing
there but the manifest ("this does not belong here" on everything else, flathub/flathub#10975):
the AppStream metainfo, the desktop entry, the icon and the shared-mime-info XML ship upstream.
So `packageTar` / `packageGraalvmTar` write them into the archive under `share/`
(`FlathubRepository.archiveFiles`) whenever `flathub { appId }` is set, and
`generateFlathubRepository` fills `build/flathub/<appId>/` with the manifest and, for a subset of
architectures, `flathub.json` — the content of `github.com/flathub/<appId>`. The manifest copies
the archive's `share/` to `/app/share` and drops it from `/app/lib/<command>`. `flathub { }` only holds what Flathub alone needs (`appId`, `summary`,
`projectLicense`, `archiveUrl`, screenshots, finish-args…); the name, description, vendor,
homepage, categories, icon, `fileAssociation` (MIME types) and `protocol` (`x-scheme-handler`)
come from the rest of the DSL, the version from `packageVersion`, the executable from the GraalVM
image name (`bin/<packageName>` for a jpackage build). It is independent of `flatpak { }`, which
builds a `.flatpak` bundle through electron-builder that Flathub does not accept.

- **Sources.** `archiveUrl` is a template (`{version}`, `{arch}` → `x64` / `arm64`); the task
  downloads each archive once to hash it, so the release must exist first. It reads the tar's
  entries on the way (`FlathubArchive`) and fails when the metadata is missing: an archive packaged
  before this, or without `flathub { }`, has to be packaged and published again. With
  `-Pnucleus.flathub.localArchive=<tar.gz>` it uses that file instead (host architecture only),
  copied next to the manifest: the local loop below.
- **Checks** before writing (the generator) and before packaging (the `Tar` task, the icon
  included), failing with every problem at once: reverse-DNS app id, a summary of at most 35
  characters without a trailing period, description, license, developer, categories, at least one
  screenshot, `#rrggbb` branding, and a square PNG icon of 256 px or more.
  The icon is installed in `hicolor/512x512`, scaled down when larger: `flatpak build-export`
  refuses a 1024 px icon there ("Image too large").
- `-Pnucleus.flathub.releaseDate=YYYY-MM-DD` pins the metainfo release date (default: today, UTC).
- **Output.** `flathub { outputDir }` (default `build/flathub/<appId>`), or
  `-Pnucleus.flathub.outputDir=<dir>` for one run, may be a clone of `github.com/flathub/<appId>`:
  the task only replaces the files it writes, removes `flathub.json` when it no longer applies and
  the metadata files older plugins wrote next to the manifest. It never wipes the directory, so
  `.git` and files of your own stay.

The Lab's block is the reference; `packaging/flathub/` keeps only its square icon and the
screenshots.

## Manifest

- **Runtime `org.gnome.Platform`**, not `org.freedesktop.Platform`: Tao, the notification and the
  launcher bridges link GTK 3, which only the GNOME runtime carries.
- The archive goes to `/app/lib/nucleus-lab` unchanged, the executable is symlinked into
  `/app/bin`. The plugin's loose JNI libraries ([native-libraries-layout.md](native-libraries-layout.md))
  are found next to the executable as in any other format.
- `finish-args` stay minimal: the Lab keeps the DSL default (`ipc`, `wayland`, `fallback-x11`,
  `dri`). Review asks for each extra one: a demo app has no reason to reach the network, and
  `--talk-name=org.freedesktop.Notifications` is refused in favour of the portal ("Use the
  portal."), which `notification-linux` now uses inside a sandbox (below). The MPRIS name needs no
  `--own-name`: Flatpak grants `org.mpris.MediaPlayer2.<app id>` by default and the linter rejects
  the redundant grant.
- An app that may not be redistributed swaps the `archive` source for `extra-data` (downloaded
  from the vendor at install time, unpacked by `apply_extra`); nothing else changes.

## What a Flathub repackaging changes at run time

Each of these was found by running the Lab from a local Flatpak repository.

- **Executable type.** The archive's marker says `tar`. `ExecutableRuntime.type()` checks
  `/.flatpak-info`, which Flatpak mounts in every sandbox, before the marker, so the app reports
  `FLATPAK` (and `isSandboxed()`), and `NucleusUpdater` stays out of the way.
- **Wayland `app_id`.** GTK sends the program name, by default the executable's file name
  (`nucleus-lab`), which matches no desktop entry. Tao sets it to `FLATPAK_ID` before GTK starts
  (`event_loop.rs`), so desktops pair the window with `<app id>.desktop`. GNOME would find it through
  the sandbox anyway; KDE and the others go by the `app_id`.
- **Title bar buttons.** GSettings inside a Flatpak reads the app's own keyfile, never the host's
  dconf: `button-layout` came back as the schema default `appmenu:close`, and minimize/maximize
  vanished on Ubuntu. `nucleus_layout_direction_linux.c` reads and watches the key through
  `org.freedesktop.portal.Settings` (`ReadOne`, then `Read`, `SettingChanged`) inside a sandbox,
  which is also GTK's own path there.
- **The host's CAs.** `/etc/ssl` inside the sandbox is the runtime's static bundle (118 CAs on
  GNOME 51): a company or filtering proxy's CA installed on the host (netfree here) is missing, and
  every TLS connection through it failed with `PKIX path building failed`. Flatpak swaps the
  runtime's p11-kit trust module for a client of the host's, so `LinuxCertificateProvider` adds
  the output of `trust extract --filter=ca-anchors --purpose=server-auth` when `/.flatpak-info`
  exists. A test on the JVM must empty its own trust store first
  (`-Djavax.net.ssl.trustStore=<empty.p12>`): a host JDK's `cacerts` may already carry the CA, which
  a native image's does not.
- **`java.util.prefs`.** Its user tree defaults to `user.home`/`.java/.userPrefs`, and a Flatpak
  without `--filesystem=home` mounts a tmpfs on the home: every preference written through
  `Preferences.userRoot()` (multiplatform-settings' `Settings()` on the JVM) was gone after a
  restart, Zayit's `onboarding_finished` included. `GraalVmInitializer.initialize()` sets
  `java.util.prefs.userRoot` to `XDG_CONFIG_HOME` (the app's persisted `~/.var/app/<id>/config`)
  inside a Flatpak, before anything reads the preferences; an explicit `-D` wins.

- **Notifications.** `notification-linux` switches to `org.freedesktop.portal.Notification` when
  `/.flatpak-info` exists: `Notify` is `AddNotification` (ids are Nucleus's own counter, sent as
  decimal strings; `replacesId` reuses one), `CloseNotification` is `RemoveNotification`, urgency
  maps to `priority`, the `default` action to `default-action`, the others to `buttons`, and the
  portal's `ActionInvoked(s id, s action, av parameter)` feeds the same callbacks (a version 2
  portal's `activation-token` in the platform data first). An image path (or `file://` URI) goes
  as a `bytes` icon, anything else as a themed name. The portal has no `NotificationClosed` (so
  `onDismissed` never fires), no expiry timeout, no sound and no raw `image-data`; capabilities and
  server information are answered locally (`actions`, `body`; `xdg-desktop-portal`).
  Actions carry no `app.` prefix: GNOME Shell then emits `org.gtk.Notifications.ActionInvoked`
  (with an activation token) instead of activating the app over D-Bus, `xdg-desktop-portal-gnome`
  turns it into the impl signal and `xdg-desktop-portal` into the app's. A version 1 frontend drops
  the token on the way. The frontend only forwards actions of notifications it still holds, and it
  forgets **every** notification of an app as soon as any D-Bus connection of that app disconnects
  (`on_peer_disconnect`, 1.21.1): a helper process of the same app id that quits, or a second
  instance killed while testing, silently cuts the actions of the others. Verified on GNOME 50.1 /
  xdg-desktop-portal 1.21.1 by activating a real notification (uinput `Super+V`, `Enter`).

## Local loop

The same code path as Flathub (a real remote, real OSTree commits, the real `flatpak-portal`),
minutes per iteration. `R=~/.cache/nucleus-flathub`:

```bash
RELEASE_VERSION=1.0.1 ./gradlew :examples:lab:app:packageGraalvmTar
RELEASE_VERSION=1.0.1 ./gradlew :examples:lab:app:generateFlathubRepository \
  -Pnucleus.flathub.localArchive=examples/lab/app/build/compose/binaries/main/graalvm-tar.gz/nucleus-lab-1.0.1-linux-x64.tar.gz
G=examples/lab/app/build/flathub/dev.nucleusframework.Lab
flatpak-builder --user --install-deps-from=flathub --repo=$R/repo --default-branch=stable \
  --force-clean --state-dir=$R/state $R/build $G/dev.nucleusframework.Lab.yml
flatpak --user remote-add --if-not-exists --no-gpg-verify nucleus-local file://$R/repo
flatpak --user install nucleus-local dev.nucleusframework.Lab
```

Building the next version into the same repository makes it an update of the installed one
(`flatpak remote-ls --updates`), which is what the portal's `UpdateMonitor` reports.

## Lint

Flathub's checks only pass on a build made with Flathub's flags (`--compose-url-policy=full`,
`--mirror-screenshots-url`): a plain `flatpak-builder` repository fails on the media URLs, and the
host's `appstreamcli` ignores the URL policy. Use the builder's own wrapper on a copy of the
generated repository (the screenshots must be reachable: generate it for a pushed tag, e.g.
`RELEASE_VERSION=<released version>`):

```bash
cp -r $G $R/generated && cd $R/generated && flatpak run --filesystem=$R --share=network --command=flathub-build \
  org.flatpak.Builder --disable-rofiles-fuse dev.nucleusframework.Lab.yml
flatpak run --filesystem=$R --command=flatpak-builder-lint org.flatpak.Builder manifest dev.nucleusframework.Lab.yml
flatpak run --filesystem=$R --command=flatpak-builder-lint org.flatpak.Builder repo repo
```

Flathub's CI lints with `--exceptions --exceptions-repo stable` and with `REPO` pointing at
`https://github.com/flathub/flathub`: the linter only applies its new-submission rules when that
variable says so. Without it a local lint passes builds the CI refuses, such as a pre-release
version (`appstream-latest-release-is-prerelease`, never granted an exception: any release
version containing `alpha`, `beta`, `rc`, `pre` or `dev`). Reproduce the CI with
`flatpak run --env=REPO=https://github.com/flathub/flathub --command=flatpak-builder-lint
org.flatpak.Builder --exceptions --exceptions-repo stable repo repo`. A pre-release build
therefore needs `flathub { releaseVersion }` (the Lab uses the date of its dev tag,
`2026.11.01`), the archive URL keeping the package version; `generateFlathubRepository` refuses
a pre-release version itself. The `beta` channel accepts them, but only once the app's own
repository exists, after the submission is accepted.

`--disable-rofiles-fuse` is only for machines where FUSE fails inside the sandbox. The linter
fetches Flathub's summary: behind a TLS-inspecting proxy, give it the host bundle
(`--filesystem=host-etc --env=REQUESTS_CA_BUNDLE=/run/host/etc/ssl/certs/ca-certificates.crt`).
Inputs must sit next to the manifest, which is why a local archive is copied there.

## Screenshots

1600×1000, the window alone, taken in a headless nested Weston so nothing of the desktop shows
and focus-stealing prevention does not hide the window:

```bash
weston --backend=headless --renderer=gl --width=1600 --height=1000 --shell=kiosk --socket=wayland-lab --debug &
WAYLAND_DISPLAY=wayland-lab flatpak run --env=GDK_BACKEND=wayland --env=LAB_PROBE=<probe id> dev.nucleusframework.Lab &
WAYLAND_DISPLAY=wayland-lab weston-screenshooter   # GL readback is upside down: magick … -flip
```

The Lab's metainfo points at the screenshots of its release tag, so they must be committed
before the tag is cut.

## In-app updates (`flatpak` module)

`Flatpak.updates` is the Flathub counterpart of `MicrosoftStore.updates`, over
`org.freedesktop.portal.Flatpak` (version 2+, flatpak 1.6). Read from the portal's source
(`portal/flatpak-portal.c`), and all of it shaped the API:

- **No "check now".** The portal checks every update monitor of the session on one timer,
  `--poll-timeout` seconds apart (default 30 min), and a monitor's first `UpdateAvailable` comes on
  the first tick after it is created. Hence `status` is a `Flow`, not a `check()`. It reports
  `running-commit` / `local-commit` / `remote-commit`, which gives three states: remote ≠ installed
  (`Available`), installed ≠ running (`Installed`: updated by us, the store or `flatpak update`,
  restart needed), or neither.
- **`Update` asks first.** The first one shows the Access portal's "Update <app>?" dialog over the
  `parent_window`; the answer is stored in the permission store, table **`flatpak`**, id
  **`updates`** (`flatpak permission-set flatpak updates <app> yes|no` presets it). A refusal fails
  every later update with `AccessDenied`.
- **Progress** carries `op` (0-based), `n_ops`, `progress` (percentage of the current op) and
  `status` (0 running, 1 nothing to do, 2 done, 3 error). The error messages are translated, so
  errors are told apart by D-Bus name only; `NotSupported` is both "the new version needs new
  permissions" (raised while planning: the signal has operations) and "no Access portal" (before
  it: none).
- **Closing a monitor cancels its update** (the portal `SIGINT`s the transaction): cancelling the
  `install` collection interrupts the update.
- **`Spawn` must be given fds 0–2.** The portal numbers the fd carrying the environment after the
  highest fd the caller passed; with none it becomes fd 0, `flatpak run --env-fd=0` refuses it
  ("Cannot use reserved file descriptor 0, 1 or 2") and nothing starts, after the app already
  quit. `flatpak-spawn` always passes its stdio, which is why nobody hits it; the bridge passes the
  process' own, `/dev/null` for a closed one.
- **Restart** releases the single-instance lock first (`SingleInstanceManager.releaseForHandoff`, as
  the NSIS hot update does), then `Spawn`s the same command line with
  `FLATPAK_SPAWN_FLAGS_LATEST_VERSION` and exits.

### E2E on the local repository

The Lab's `system.flatpak-updates` probe takes deep-link flags that run the whole update without a
click: `LAB_PROBE='system.flatpak-updates?watch&install&restart'` (watch → install on `Available` →
restart once installed). With the local loop above:

```bash
# Poll every 10 s instead of 30 min (a clean environment: systemd-run, like the real service)
systemd-run --user --collect --unit=flatpak-portal-e2e --setenv=G_MESSAGES_DEBUG=all \
  /usr/libexec/flatpak-portal --replace --poll-timeout=10
flatpak run --env=LAB_PROBE='system.flatpak-updates?watch&install&restart' dev.nucleusframework.Lab &
# build the next version into $R/repo, then watch the portal and the running commit
journalctl --user -u flatpak-portal-e2e -f | grep flatpak-INFO
flatpak ps --columns=pid,application,commit
```

`systemctl --user stop flatpak-portal-e2e` hands the bus name back to normal activation.
Background updates (`?watch&restart`, then `flatpak update` from a terminal) exercise `Installed`.

## App id verification

`dev.nucleusframework.Lab` is verified through the domain: Flathub hands out a token to serve at
`https://nucleusframework.dev/.well-known/org.flathub.VerifiedApps.txt` once the submission is open.
