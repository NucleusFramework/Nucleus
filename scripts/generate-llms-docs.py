#!/usr/bin/env python3
"""
Generate llms.txt and llms-full.txt from MkDocs documentation.

Reads the nav order from mkdocs.yml, concatenates all doc pages (excluding
llm.md, changelog.md, and comparison.md), strips MkDocs-specific markup,
and writes:
  - docs/llms-full.txt  — complete documentation
  - docs/llms.txt       — concise overview (index + getting-started + runtime index + migration)

Run from the project root:
    python scripts/generate-llms-docs.py
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------

PROJECT_ROOT = Path(__file__).resolve().parent.parent
DOCS_DIR = PROJECT_ROOT / "docs"
MKDOCS_YML = PROJECT_ROOT / "mkdocs.yml"
OUTPUT_FULL = DOCS_DIR / "llms-full.txt"
OUTPUT_SHORT = DOCS_DIR / "llms.txt"

# Pages to skip entirely
SKIP_PAGES = {"llm.md", "changelog.md", "comparison.md"}

# Pages included in the short version (llms.txt)
SHORT_PAGES = {
    "index.md",
    "getting-started.md",
    "migration.md",
    "runtime/index.md",
}

SITE_URL = "https://nucleus.nucleusframework.com"

# ---------------------------------------------------------------------------
# Nav extraction (no PyYAML needed)
# ---------------------------------------------------------------------------


def extract_nav_pages(mkdocs_path: Path) -> list[str]:
    """Extract ordered list of .md paths from mkdocs.yml nav section."""
    text = mkdocs_path.read_text(encoding="utf-8")

    # Find the nav: block
    nav_match = re.search(r"^nav:\s*\n", text, re.MULTILINE)
    if not nav_match:
        print("ERROR: Could not find nav: section in mkdocs.yml", file=sys.stderr)
        sys.exit(1)

    nav_text = text[nav_match.end():]

    # nav ends at the next top-level key (line starting without indent)
    end_match = re.search(r"^\S", nav_text, re.MULTILINE)
    if end_match:
        nav_text = nav_text[: end_match.start()]

    # Extract all .md references
    pages = re.findall(r":\s*(\S+\.md)\s*$", nav_text, re.MULTILINE)
    return pages


# ---------------------------------------------------------------------------
# MkDocs markup cleaning
# ---------------------------------------------------------------------------


def clean_mkdocs_markup(content: str) -> str:
    """Strip MkDocs-specific markup to produce clean plain-text markdown."""

    # Remove HTML tags (badges, figures, images with HTML, align, etc.)
    content = re.sub(r"<p\b[^>]*>.*?</p>", "", content, flags=re.DOTALL)
    content = re.sub(r"<figure\b[^>]*>.*?</figure>", "", content, flags=re.DOTALL)
    content = re.sub(r"<figcaption>.*?</figcaption>", "", content, flags=re.DOTALL)
    content = re.sub(r"<br\s*/?>", "\n", content)
    content = re.sub(r"</?(?:div|span|a|img|br|hr|sup|sub)[^>]*>", "", content)

    # Remove badge lines: [![...](...)(...) pattern on its own line
    content = re.sub(r"^\[!\[[^\]]*\]\([^)]*\)\]\([^)]*\)\s*$", "", content, flags=re.MULTILINE)

    # Remove image-only lines: ![alt](path)
    content = re.sub(r"^!\[[^\]]*\]\([^)]*\)\s*$", "", content, flags=re.MULTILINE)

    # Convert MkDocs admonitions to plain text blocks
    # !!! type "Title"  or  !!! type
    def convert_admonition(m: re.Match) -> str:
        adm_type = m.group(1).upper()
        title = m.group(2).strip(' "') if m.group(2) else adm_type
        body = m.group(3)
        # Dedent body by 4 spaces
        body_lines = []
        for line in body.split("\n"):
            if line.startswith("    "):
                body_lines.append(line[4:])
            else:
                body_lines.append(line)
        body_clean = "\n".join(body_lines).strip()
        return f"**{title}:** {body_clean}"

    content = re.sub(
        r"^!!!?\s+(\w+)([^\n]*)\n((?:^    .*\n?)*)",
        convert_admonition,
        content,
        flags=re.MULTILINE,
    )

    # Convert tabbed content: === "Tab Name" -> ### Tab Name
    content = re.sub(r'^===\s+"([^"]+)"\s*$', r"### \1", content, flags=re.MULTILINE)

    # Remove attribute lists: {: .class } or { .class }
    content = re.sub(r"\{:?\s*\.[^}]*\}", "", content)

    # Remove mermaid diagram blocks (useless in plain text)
    content = re.sub(r"```mermaid\n.*?```", "", content, flags=re.DOTALL)

    # Remove markdown="1" or markdown attributes
    content = re.sub(r'\s+markdown(?:="[^"]*")?', "", content)

    # Clean up excessive blank lines (3+ -> 2)
    content = re.sub(r"\n{3,}", "\n\n", content)

    return content.strip()


# ---------------------------------------------------------------------------
# Preamble (prepended before the first page)
# ---------------------------------------------------------------------------

PREAMBLE = """\
# Nucleus

> Nucleus is the all-in-one toolkit for shipping JVM desktop applications. \
It combines a Gradle plugin, runtime libraries, and GitHub Actions to handle \
performance (AOT), distribution (16 packaging formats), native look & feel \
(decorated windows, dark mode, system colors), and GraalVM Native Image support. \
Compatible with any JVM application, optimized for Compose Desktop.

- Docs: {site_url}
- GitHub: https://github.com/nucleusframework/Nucleus
- Gradle Plugin Portal: https://plugins.gradle.org/plugin/dev.nucleusframework.nucleus
- Maven Central: https://central.sonatype.com/search?q=dev.nucleusframework.nucleus
- License: MIT

""".format(site_url=SITE_URL)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def build_doc(pages: list[str], page_filter: set[str] | None = None) -> str:
    """Concatenate doc pages into a single cleaned document."""
    sections: list[str] = []

    for page_path in pages:
        if page_path in SKIP_PAGES:
            continue
        if page_filter is not None and page_path not in page_filter:
            continue

        full_path = DOCS_DIR / page_path

        if not full_path.exists():
            print(f"WARNING: {page_path} not found, skipping", file=sys.stderr)
            continue

        content = full_path.read_text(encoding="utf-8")
        cleaned = clean_mkdocs_markup(content)

        if cleaned:
            sections.append(cleaned)

    # The first section (index.md) starts with "# Nucleus" which duplicates the
    # preamble title.  Strip the leading title from it.
    if sections:
        sections[0] = re.sub(r"^# Nucleus\s*\n+", "", sections[0])

    return PREAMBLE + "\n\n---\n\n".join(sections)


def main() -> None:
    pages = extract_nav_pages(MKDOCS_YML)
    print(f"Found {len(pages)} pages in mkdocs.yml nav")

    # Generate llms-full.txt (all pages except skipped ones)
    full_doc = build_doc(pages)
    OUTPUT_FULL.write_text(full_doc + "\n", encoding="utf-8")
    full_lines = full_doc.count("\n") + 1
    print(f"Generated {OUTPUT_FULL.name}: {full_lines} lines")

    # Generate llms.txt (short version)
    short_doc = build_doc(pages, page_filter=SHORT_PAGES)

    # Append documentation links section
    short_doc += "\n\n---\n\n## Documentation\n\n"
    for page_path in pages:
        if page_path in SKIP_PAGES or page_path == "index.md":
            continue
        # Read the first heading from the file
        fp = DOCS_DIR / page_path
        if fp.exists():
            first_line = fp.read_text(encoding="utf-8").split("\n", 1)[0]
            title = re.sub(r"^#+\s*", "", first_line).strip()
            if title:
                url = f"{SITE_URL}/{page_path.replace('.md', '/')}"
                short_doc += f"- [{title}]({url})\n"

    short_doc += f"\n## Full LLM Documentation\n\n- [{OUTPUT_FULL.name}]({SITE_URL}/{OUTPUT_FULL.name}): Complete documentation in a single file\n"

    OUTPUT_SHORT.write_text(short_doc + "\n", encoding="utf-8")
    short_lines = short_doc.count("\n") + 1
    print(f"Generated {OUTPUT_SHORT.name}: {short_lines} lines")


if __name__ == "__main__":
    main()
