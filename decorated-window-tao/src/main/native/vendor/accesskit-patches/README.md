# accesskit patches

Local changes applied directly to the vendored AccessKit crates
(`../accesskit_atspi_common/`, `../accesskit_unix/`, `../accesskit_windows/`).

Unlike tao, these are edited in place rather than kept as a `.patch` series —
they are small and few. Every one carries a `PATCH(nucleus)` comment at the
site, so `grep -rn 'PATCH(nucleus)' ../accesskit_*` lists the whole set before
a version bump.

## Pinned upstream versions

- **accesskit_atspi_common**, **accesskit_unix**, **accesskit_windows**: as
  vendored; see `../../Cargo.toml` for the versions the tree was copied from.

## Patch list

| Crate | File | Summary |
| ----- | ---- | ------- |
| `accesskit_atspi_common` | `src/context.rs` | `AppContext::push_adapter` inserts at the position `adapter_index` searched for instead of pushing to the end. The list is looked up with `binary_search_by`, which is only defined on a sorted slice; adapters are created on the toolkit thread and registered from the AT-SPI worker, so an out-of-order registration made every later lookup unreliable. A duplicate id now replaces its entry rather than shadowing it. |
| `accesskit_atspi_common` | `src/adapter.rs` | The two `adapter_index(...).unwrap()` calls (in `add_subtree`'s root branch and in `register_tree`) skip the root announcement when the adapter is not in the app context, instead of panicking. The crate is built with `panic = "abort"`, so that miss aborted the whole JVM (`called Result::unwrap() on an Err value`, SIGABRT / exit 134) while a client was walking the AT-SPI tree of an application creating several windows at once. `src/node.rs` already treats the same miss as `Error::Defunct`, which is the behaviour these two sites now share. |

## Bump procedure

1. Copy the new crate sources over the vendored trees.
2. `grep -rn 'PATCH(nucleus)' vendor/accesskit_*` on the *previous* tree to
   recover the list, and re-apply each one, checking whether upstream fixed it
   first (upstream issue for the abort:
   push/`binary_search` mismatch in `AppContext`).
3. `cargo check` from `src/main/native`, then run
   `./gradlew :decorated-window-tao:taoHeadfulTest` with the a11y bus enabled
   (`busctl --user set-property org.a11y.Bus /org/a11y/bus org.a11y.Status
   IsEnabled b true`) — the abort only shows up while an assistive client is
   attached.
