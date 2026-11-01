# Flathub

The Lab ships on Flathub as `dev.nucleusframework.Lab`, the way a closed-source Nucleus app would:
Flathub compiles nothing, its manifest repackages the GraalVM native image the release already
publishes (`nucleus-lab-<version>-linux-{x64,arm64}.tar.gz`, built by `packageGraalvmTar` in
`release-desktop`). Everything lives in `examples/lab/app/packaging/flathub/`;
`render-manifest.sh` turns it into the content of `github.com/flathub/dev.nucleusframework.Lab`.

## Manifest

- **Runtime `org.gnome.Platform`**, not `org.freedesktop.Platform`: Tao, the notification and the
  launcher bridges link GTK 3, which only the GNOME runtime carries.
- The archive goes to `/app/lib/nucleus-lab` unchanged, the executable is symlinked into
  `/app/bin`. The plugin's loose JNI libraries ([native-libraries-layout.md](native-libraries-layout.md))
  are found next to the executable as in any other format.
- `finish-args` stay minimal (`ipc`, `wayland`, `fallback-x11`, `dri`, `network`, the notification
  server). The MPRIS name needs no `--own-name`: Flatpak grants `org.mpris.MediaPlayer2.<app id>`
  by default and the linter rejects the redundant grant.
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

## Local loop

The same code path as Flathub (a real remote, real OSTree commits, the real `flatpak-portal`),
minutes per iteration. `R=~/.cache/nucleus-flathub`:

```bash
RELEASE_VERSION=1.0.1 ./gradlew :examples:lab:app:packageGraalvmTar
examples/lab/app/packaging/flathub/render-manifest.sh $R/render --local 1.0.1 \
  examples/lab/app/build/compose/binaries/main/graalvm-tar.gz/nucleus-lab-1.0.1-linux-x64.tar.gz
flatpak-builder --user --install-deps-from=flathub --repo=$R/repo --default-branch=stable \
  --force-clean --state-dir=$R/state $R/build $R/render/dev.nucleusframework.Lab.yml
flatpak --user remote-add --if-not-exists --no-gpg-verify nucleus-local file://$R/repo
flatpak --user install nucleus-local dev.nucleusframework.Lab
```

Building the next version into the same repository makes it an update of the installed one
(`flatpak remote-ls --updates`), which is what the portal's `UpdateMonitor` reports.

## Lint

Flathub's checks only pass on a build made with Flathub's flags (`--compose-url-policy=full`,
`--mirror-screenshots-url`): a plain `flatpak-builder` repository fails on the media URLs, and the
host's `appstreamcli` ignores the URL policy. Use the builder's own wrapper, with the screenshots
served (`--local` points them at `$SCREENSHOTS_URL`, default `http://localhost:8765`):

```bash
python3 -m http.server 8765 --directory examples/lab/app/packaging/flathub/screenshots &
cd $R/render && flatpak run --filesystem=$R --share=network --command=flathub-build \
  org.flatpak.Builder --disable-rofiles-fuse dev.nucleusframework.Lab.yml
flatpak run --filesystem=$R --command=flatpak-builder-lint org.flatpak.Builder manifest dev.nucleusframework.Lab.yml
flatpak run --filesystem=$R --command=flatpak-builder-lint org.flatpak.Builder repo repo
```

`--disable-rofiles-fuse` is only for machines where FUSE fails inside the sandbox. The linter
fetches Flathub's summary: behind a TLS-inspecting proxy, give it the host bundle
(`--filesystem=host-etc --env=REQUESTS_CA_BUNDLE=/run/host/etc/ssl/certs/ca-certificates.crt`).
Inputs must sit next to the manifest, which is why `--local` copies the archives there.

## Screenshots

1600×1000, the window alone, taken in a headless nested Weston so nothing of the desktop shows
and focus-stealing prevention does not hide the window:

```bash
weston --backend=headless --renderer=gl --width=1600 --height=1000 --shell=kiosk --socket=wayland-lab --debug &
WAYLAND_DISPLAY=wayland-lab flatpak run --env=GDK_BACKEND=wayland --env=LAB_PROBE=<probe id> dev.nucleusframework.Lab &
WAYLAND_DISPLAY=wayland-lab weston-screenshooter   # GL readback is upside down: magick … -flip
```

In `--release` mode the metainfo points at the screenshots of the release tag, so they must be
committed before the tag is cut.

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
