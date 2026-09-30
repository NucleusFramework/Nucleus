---
name: tag-dev
description: Create and push a timestamped dev tag for the Nucleus 2.6 branch in the format v2.6.0-dev-YYYYMMDDHHMM, publishing runtime modules to Maven Central and the plugin to the Gradle Plugin Portal without running preMerge. Use when the user asks to "publish a dev build", "cut a dev", "tag dev", "publier une version dev", or similar on this project.
---

# Tag dev — Nucleus 2.6

Creates a timestamped dev tag on the current HEAD and pushes it to `origin`. The tag publishes
**unverified** artifacts: `.github/workflows/publish-maven.yaml` and `publish-plugin.yaml` skip
`preMerge` for dev tags, and the desktop / GraalVM release workflows ignore them entirely.

## Format

`v2.6.0-dev-YYYYMMDDHHMM` — e.g. `v2.6.0-dev-202609200830` for Sep 20 2026, 08:30 UTC.

Timestamp components come from `date -u +%Y%m%d%H%M` (UTC, no separators, 12 chars).

The published Maven version is the tag without the leading `v` (`2.6.0-dev-202609200830`), which
orders below `2.6.0` for Gradle and Maven, so a dev build can never shadow the real release.

## Procedure

1. **Verify the branch is a 2.6 line branch** — `nucleus-2.6` or a feature branch cut from it.
   A dev tag on `main` or on the 2.5 line would publish a `2.6.0-dev-*` version from the wrong
   code; abort and say so.
2. **Verify the working tree is clean** — `git status --porcelain` empty. If dirty, ask the user
   whether to commit first or abort. Never tag a dirty tree: the tag is what CI builds.
3. **Verify HEAD is pushed** — `git fetch origin` then check the commit exists on the remote
   (`git branch -r --contains HEAD`). A tag on an unpushed commit makes CI check out a commit
   nobody else has; push the branch first (ask before pushing).
4. **Generate the timestamp** with `date -u +%Y%m%d%H%M`.
5. **Check the tag doesn't already exist** — `git tag -l v2.6.0-dev-<ts>`. If it does, use the
   next minute; Maven Central versions are immutable, a retag would publish nothing.
6. **Create an annotated tag**:
   ```bash
   git tag -a "v2.6.0-dev-<ts>" -m "v2.6.0-dev-<ts>"
   ```
   Annotated (not lightweight) because the published history uses annotated tags.
7. **Push the tag**:
   ```bash
   git push origin "v2.6.0-dev-<ts>"
   ```
8. **Report** the tag name, the commit SHA, the resulting Maven version, and the coordinates a
   consumer needs, e.g.:
   ```kotlin
   implementation("dev.nucleusframework:nucleus.decorated-window-tao:2.6.0-dev-<ts>")
   ```
   Mention that Central takes ~15 minutes to expose the version after the workflow goes green.

## Hard rules

- Never tag `main` or the 2.5 line with this format.
- Never overwrite or force-push a tag — the version is already on Central and cannot be replaced.
- Never add `Co-Authored-By` or AI attribution to the tag message (per the project's CLAUDE.md).
- Tag message body is just the tag name itself — matches the existing convention.
- The tag must stay `v<major>.<minor>.<patch>-dev-<id>`: `.github/actions/release-tag-info`
  rejects anything else, because every publish task derives its version by stripping
  `refs/tags/v` from `GITHUB_REF`.

## When NOT to use this skill

- Stable releases (`v2.6.0`) — those go through the full `preMerge` gate and cut GitHub releases.
- Alpha/beta/rc prereleases — see the `tag-alpha` skill and `.github/actions/validate-release-ref`.
- Backporting onto an old commit — this skill always tags `HEAD`.
