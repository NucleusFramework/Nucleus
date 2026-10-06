# Contributing to Nucleus

## Branch

Open pull requests against `nucleus-3.0`. Pull requests against `main` are not accepted.

## Before you start

- Search existing issues and pull requests to avoid duplicates.
- For a new feature, open a [discussion](https://github.com/NucleusFramework/Nucleus/discussions)
  first to agree on the scope, the API and the guidelines that apply. Feature pull requests
  without a prior discussion may be closed.

## Pull requests

- One change per pull request. Split unrelated changes.
- No unrelated reformatting, renames or dependency bumps.
- Bug fixes must include a minimal example that fails without the fix, ideally as a test.
- Describe how you tested, on which OS and desktop environment.
- Changes must work on macOS, Windows and Linux, and in both the JVM and GraalVM pipelines.
  Document any platform limitation in KDoc.
- Keep the description in sync with the code.

## AI tools

Using AI tools is fine and makes no difference to the review. But you take full responsibility
for what you submit: "Claude did this" or "the AI said that" is not an answer. You must understand
every line and be able to answer review questions yourself.

## Code

- Follow the existing style. Run `./gradlew reformatAll`.
- No reflection and no JNA in runtime modules (GraalVM native-image compatibility).
- Logging goes through `java.util.logging`.
- Public API needs KDoc. After an intentional API change, run `./gradlew apiDump` and commit the
  result.
- See [CLAUDE.md](CLAUDE.md) for architecture notes and the native module checklist.

## Build

Use JDK 17 or 21 to run Gradle.

```bash
./gradlew preMerge                                            # full verification, must pass
./gradlew publishDevToMavenLocal --no-configuration-cache     # try your change in an app (version "dev")
```

## Commits

Use [Conventional Commits](https://www.conventionalcommits.org/) with a scope, e.g.
`fix(tao): forward Linux IME preedit into Compose (#558)`.
