# tao patches

Local patch series applied on top of vendored tao (see `../tao/`).

## Pinned upstream version

- **tao**: `0.35.0` (crates.io)
- Copied from: `~/.cargo/registry/src/index.crates.io-*/tao-0.35.0/`

## Patch series

Patches are applied in numeric order. Each patch is paired with the removal
of an external workaround in the parent module — see `../../../../VENDORING_PLAN.md`.

| #     | File                              | Phase | Platform | Summary |
| ----- | --------------------------------- | ----- | -------- | ------- |
| 0001  | `0001-linux-resize-zones.patch`           | 1     | Linux    | Widen resize edge band to 8 px and corner zone to 16 px (logical), set the resize cursor before `begin_resize_drag` so it persists during the drag. Adds a `corner` parameter to `crate::window::hit_test`. |
| 0002  | `0002-linux-cursor-preserve-on-motion.patch` | 2 | Linux    | Only override the cursor on edge-zone entry / exit. Outside resize zones the application-level cursor (text I-beam, hand, custom icon) is preserved across motion events. |

## Bump procedure (e.g. 0.35 → 0.36)

```bash
# 1. Pull the new upstream version into the vendor dir
rm -rf vendor/tao
cp -r ~/.cargo/registry/src/index.crates.io-*/tao-0.36.0 vendor/tao

# 2. Re-apply patches in order
cd vendor/tao
for p in ../tao-patches/*.patch; do
  git apply --3way "$p" || echo "CONFLICT: $p — resolve, then regenerate"
done

# 3. For each conflict: resolve by hand, then regenerate the patch
git diff > ../tao-patches/000X-foo.patch

# 4. Re-run all E2E gates from Phase 0 onward (see VENDORING_PLAN.md).
```

## Regenerating a single patch

The vendored tree is not a git repo. To regenerate `000X-foo.patch` after
editing files under `vendor/tao/`, diff against the pristine upstream:

```bash
PRISTINE=~/.cargo/registry/src/index.crates.io-*/tao-0.35.0
diff -ruN "$PRISTINE" vendor/tao \
  --exclude=Cargo.lock --exclude=target \
  > vendor/tao-patches/000X-foo.patch
```
