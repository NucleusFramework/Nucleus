# LLM Documentation

Nucleus provides machine-readable documentation files designed for Large Language Models (LLMs). These plain-text files follow the [llms.txt](https://llmstxt.org/) convention and allow AI assistants to quickly understand the project, its APIs, and configuration options.

## Available Files

| File | Description | Use Case |
|------|-------------|----------|
| [`llms.txt`](../llms.txt) | Concise overview with links to all sections | Quick context for simple questions |
| [`llms-full.txt`](../llms-full.txt) | Complete documentation (all pages concatenated) | Full reference for code generation and in-depth tasks |

## How They Stay Up to Date

Both files are **auto-generated** from the MkDocs documentation pages by the [`scripts/generate-llms-docs.py`](https://github.com/kdroidFilter/Nucleus/blob/main/scripts/generate-llms-docs.py) script. A GitHub Actions workflow runs this script on every push to `main` that touches `docs/`, so `llms.txt` and `llms-full.txt` are always in sync with the documentation.

!!! tip "Contributing"
    Never edit `llms.txt` or `llms-full.txt` manually — edit the source `.md` files in `docs/` and the script regenerates them automatically.

## Usage

### ChatGPT, Claude, Gemini, etc.

Paste the URL directly in your prompt:

```
Read https://nucleusframework.dev/llms-full.txt and help me configure
a Nucleus project with NSIS installer, auto-update, and macOS signing.
```

### Cursor, Windsurf, Claude Code

Add the URL as a documentation source in your AI-powered IDE, or reference it in your project instructions:

```
@doc https://nucleusframework.dev/llms-full.txt
```

### Custom Agents / RAG Pipelines

Fetch the files programmatically:

```bash
curl -s https://nucleusframework.dev/llms.txt       # concise
curl -s https://nucleusframework.dev/llms-full.txt   # complete
```

## What's Included

**`llms.txt`** covers:

- Project overview and key features
- Quick start snippet
- Getting started guide with prerequisites and installation
- Runtime libraries summary (all libraries)
- Migration guide from `org.jetbrains.compose`
- Links to all documentation pages

**`llms-full.txt`** covers everything in the documentation:

- Full Gradle DSL reference (all properties and enums)
- Platform-specific configuration (macOS, Windows, Linux)
- macOS 26 Liquid Glass and SDK version patching
- Sandboxing pipeline details
- Code signing and notarization (Windows PFX, Azure Artifact Signing, macOS Developer ID)
- Auto-update runtime API with Compose integration example
- Publishing to GitHub Releases and S3
- CI/CD workflows and all composite actions
- GraalVM Native Image configuration and DSL reference
- Native Access (Kotlin/Native bridge)
- All runtime APIs with code examples:
    - App metadata, executable type, single instance, deep links
    - Decorated window (JBR and JNI backends, fullscreen controls, design system wrappers)
    - System tray (ComposeNativeTray framework, Tray API, Menu DSL, TrayApp)
    - Notifications (macOS, Windows, Linux)
    - Launchers (macOS dock, Windows taskbar, Linux Unity)
    - Taskbar progress, global hotkey, macOS menu
    - Dark mode detector, system color, system info, energy manager
    - Native SSL, native HTTP (java.net.http, OkHttp, Ktor)
    - Linux HiDPI, freedesktop icons
    - GraalVM runtime bootstrap
