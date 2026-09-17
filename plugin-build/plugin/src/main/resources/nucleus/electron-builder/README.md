# Pinned electron-builder toolchain

`package.json` + `package-lock.json` are the integrity boundary for the packaging pipeline. The
plugin stages them into a build-local directory and runs
`npm ci --ignore-scripts` — so these two files decide which ~275 npm tarballs are allowed onto the
machine that holds the code-signing certificates and notarization credentials. A tarball whose
content does not match the recorded `integrity` hash fails the build (`EINTEGRITY`).

They are consumed by
`internal/electronbuilder/ElectronBuilderToolManager.kt`, whose `ELECTRON_BUILDER_VERSION` constant
must stay equal to the pinned version — `ElectronBuilderToolchainLockTest` fails otherwise.

## Regenerating

```bash
scripts/update-electron-builder-lock.sh            # re-resolve the pinned version
scripts/update-electron-builder-lock.sh 26.16.0    # bump
```

**Provenance matters more than convenience here.** The hashes record whatever the connection served
at that moment, and every later `npm ci` — on every user's machine — trusts them. So the script
resolves against `https://registry.npmjs.org/` explicitly (an internal mirror must not leak into
`resolved` URLs) and refuses to run when `NODE_TLS_REJECT_UNAUTHORIZED` has switched certificate
verification off. It cannot detect every tampered path: review `git diff` on this directory before
committing, and treat an unexpected change to an unrelated transitive package as a red flag.

A corporate TLS-terminating proxy is **not** a problem, here or at install time. Such a proxy
inspects the connection but serves the same tarball bytes, which is what an `integrity` hash covers;
with its CA installed (`NODE_EXTRA_CA_CERTS`) verification stays on and both `npm ci` and this script
work normally. An intermediary that actually serves *different* bytes fails the hash check — which is
the intended outcome, since nothing distinguishes that from an attack.

## Not covered

electron-builder downloads a few helper binaries at run time (`app-builder`, 7-Zip, NSIS, the
snap/AppImage templates) from GitHub into `ELECTRON_BUILDER_CACHE`. Those are verified by
electron-builder's own checksums, not by npm, and no lock file here can constrain them.
