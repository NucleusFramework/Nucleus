# Tao backend refactor — verification procedure

Safety harness for the JetBrains-like reorganization of `decorated-window-tao`
(FFI quarantine → subsystem packages → `explicitApi()`). The contract: **a
framework user must observe zero change** — same Maven coordinates, same public
FQNs, same binary API, same runtime behavior.

Inspired by JetBrains practice:
- **kotlinx binary-compatibility-validator** (`apiDump`/`apiCheck`) — same tool
  kotlinx / Compose use to freeze the public ABI.
- **kotlin-desktop-toolkit**: generated/native bindings quarantined and never
  public; per-platform test harnesses; strict `explicitApi()`.
- **compose-multiplatform** `tutorials/checker`: real consumer projects compiled
  as a regression gate — here the role is played by `nucleus-application`,
  `taskbar-progress-tao` and the example apps.

## Invariants

| # | Invariant | Verified by |
|---|-----------|-------------|
| I1 | Public ABI frozen: every public FQN, member and signature identical to the baseline | BCV `apiCheck` against `api/decorated-window-tao.api` (zero diff in phases 1–2; additive-only never allowed either) |
| I2 | JNI linkage consistent: every `Java_*` symbol defined in native sources (`.rs`/`.c`/`.m`, vendor excluded) maps to a compiled Kotlin `external fun`, and every Kotlin `external fun` has a native definition; macOS dylibs in `src/main/resources` export no stale symbols | `scripts/check-tao-refactor-invariants.sh` (sections 1–2) |
| I3 | Reflective FQNs resolve: `FindClass`/method-signature literals in native sources, `Class.forName` literals in Kotlin, `META-INF/services/*`, ProGuard `-keep` rules, GraalVM `reachability-metadata.json` (module **and** plugin `platform-metadata/*.json`) all point at classes that exist in the compiled output | `scripts/check-tao-refactor-invariants.sh` (sections 3–6) |
| I4 | Consumers compile **without any source change**: `nucleus-application`, `taskbar-progress-tao`, `examples/lab` (every probe module, the swing-tao fixture included) | Gradle compile of the consumers; `git diff --stat` on them must be empty (exceptions must be listed in the phase commit message — e.g. a test doing reflection on an `internal` bridge) |
| I5 | Runtime intact on the host OS: unit tests, dispatcher handoff tests, real-window smoke test, and a manual/scripted launch of the Lab (window opens, first frame renders, popups & titlebar OK) | `:decorated-window-tao:test` + `:examples:lab:app:run` |
| I6 | Other OSes: natives rebuild from the renamed sources and the full matrix passes | CI on the PR branch: `build-natives.yaml` + `pre-merge.yaml` (Windows/Linux/macOS, x64+aarch64 verify arrays) |

## One-time setup (baseline)

```bash
export JAVA_HOME=/Users/eliegambache/Library/Java/JavaVirtualMachines/jbr-21.0.10/Contents/Home

# 1. Dump the public ABI baseline (BEFORE any refactor commit)
./gradlew :decorated-window-tao:apiDump          # writes decorated-window-tao/api/decorated-window-tao.api
git add decorated-window-tao/api && git commit   # baseline is part of the harness commit

# 2. Sanity: harness must be green on the unmodified tree
./scripts/check-tao-refactor-invariants.sh
./gradlew :decorated-window-tao:test
```

## Per-phase procedure

Run **after every phase**, in this order (fail fast, cheapest first):

```bash
export JAVA_HOME=/Users/eliegambache/Library/Java/JavaVirtualMachines/jbr-21.0.10/Contents/Home

# 0. If native sources changed (phase 1): rebuild macOS dylibs + clear loader cache
(cd decorated-window-tao/src/main/native && ./build.sh)   # or the module's native build task
rm -rf ~/.cache/nucleus/native

# 1. ABI freeze
./gradlew :decorated-window-tao:apiCheck

# 2. FQN / JNI / metadata consistency (compiles the module first)
./scripts/check-tao-refactor-invariants.sh

# 3. Unit + smoke tests
./gradlew :decorated-window-tao:test

# 4. Consumers compile, sources untouched
./gradlew :nucleus-application:compileKotlin :taskbar-progress-tao:compileKotlin \
          :examples:lab:app:compileKotlin
git diff --stat nucleus-application taskbar-progress-tao examples   # expected: empty

# 5. Live run (host OS) — window must open, render, close cleanly
./gradlew :examples:lab:app:run --no-configuration-cache           # inspect, then quit

# 6. Commit the phase, push, let CI run the 3-OS matrix before the next phase
```

### Phase-specific gates

- **Phase 1 (FFI quarantine, `window.tao.ffi`)** — the only phase touching native
  sources. Extra gates: `nm -gU` on the freshly built `darwin-*` dylibs shows only
  `Java_dev_nucleusframework_window_tao_ffi_*` symbols (script section 2 enforces
  this); the Lab must be exercised beyond startup (window state, popups, drag & drop and keyboard & IME probes): resize, fullscreen, popup,
  drag-and-drop, IME. Linux/Windows symbol renames are textual and cannot be
  linked locally — CI is the authority (I6): do not merge before `build-natives`
  + `pre-merge` are green.
- **Phase 2 (subsystem packages)** — Kotlin-only moves of `internal` declarations.
  Extra gates: `apiCheck` diff must be **exactly zero** (if a move drags a public
  symbol along, the move is wrong — split the file instead); GraalVM/ProGuard/
  services files updated in the same commit (script sections 3–6 catch misses).
- **Phase 3 (`explicitApi()`)** — no declaration moves. Extra gates: `apiDump`
  after the change must be byte-identical to the baseline (adding explicit
  `public` modifiers must not alter the dump); Detekt/KtLint (`reformatAll`,
  `preMerge`) still pass.

## Rollback

Each phase is a single commit on `refactor/tao-jetbrains-layout`. Any red gate →
`git revert` the phase commit; phases are independent enough that reverting one
does not require reverting the following ones except phase 2 depends on phase 1
package names in metadata files.
