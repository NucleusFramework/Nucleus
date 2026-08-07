#!/usr/bin/env python3
"""
Apply mechanical fixes for Kotlin explicitApi() diagnostics.

Reads kotlinc error lines from stdin or a file and, for each:
  - "Visibility must be specified" → inserts `public ` at the reported column
  - "Return type must be specified" → inserts an inferred type annotation when
    the RHS is a simple constructor call / reference / literal

Idempotent: skips lines that already start (after indent) with a visibility
modifier or already have a type annotation for the reported property/function.
"""

from __future__ import annotations

import re
import sys
from collections import defaultdict
from pathlib import Path

ERROR_RE = re.compile(
    r"^e: file://(?P<path>[^:]+):(?P<line>\d+):(?P<col>\d+) (?P<msg>.*)$"
)

VISIBILITY_PREFIXES = (
    "public ",
    "private ",
    "internal ",
    "protected ",
)

# Matches a simple RHS constructor / object ref for type inference:
#   Foo(…)  /  Foo.Bar  /  some.name.Foo(…)
SIMPLE_CTOR_RE = re.compile(
    r"^(?P<type>(?:[A-Za-z_][\w.]*\.)*[A-Za-z_][\w]*)\s*(?:\(|$)"
)
LITERAL_TYPE = {
    "true": "Boolean",
    "false": "Boolean",
    "null": "Nothing?",
}


def parse_errors(lines: list[str]) -> list[tuple[Path, int, int, str]]:
    out: list[tuple[Path, int, int, str]] = []
    for raw in lines:
        m = ERROR_RE.match(raw.strip())
        if not m:
            continue
        out.append(
            (
                Path(m.group("path")),
                int(m.group("line")),
                int(m.group("col")),
                m.group("msg"),
            )
        )
    return out


def leading_ws(s: str) -> str:
    return s[: len(s) - len(s.lstrip(" \t"))]


def has_visibility(s: str) -> bool:
    stripped = s.lstrip(" \t")
    # Skip annotation-only lines; visibility may be on a later line.
    if stripped.startswith("@"):
        return True  # don't touch annotation lines; caller handles multi-line
    return any(stripped.startswith(p) for p in VISIBILITY_PREFIXES)


def insert_public(line: str, col: int) -> str:
    """Insert 'public ' at 1-based column, or after leading indent if col is 1."""
    if has_visibility(line):
        return line
    stripped = line.lstrip(" \t")
    # If the line is only annotations, leave it — visibility goes on the decl line.
    if stripped.startswith("@") and not re.search(
        r"\b(class|object|interface|fun|val|var|enum|typealias|constructor)\b",
        stripped,
    ):
        return line
    indent = leading_ws(line)
    # Prefer inserting after indent rather than absolute column when the
    # compiler points at column 1 on an indented member (col can be indent+1).
    body = line[len(indent) :]
    if has_visibility(body):
        return line
    # Handle labels like "override fun" / "open class" / "abstract fun" /
    # "suspend fun" / "operator fun" / "inline fun" / "data class" etc.
    return indent + "public " + body


def infer_type_from_rhs(rhs: str) -> str | None:
    rhs = rhs.strip()
    if not rhs:
        return None
    # Strip trailing comments
    if "//" in rhs:
        rhs = rhs.split("//", 1)[0].rstrip()
    if rhs in LITERAL_TYPE:
        return LITERAL_TYPE[rhs]
    # Numeric literals
    if re.fullmatch(r"-?\d+", rhs):
        return "Int"
    if re.fullmatch(r"-?\d+[lL]", rhs):
        return "Long"
    if re.fullmatch(r"-?\d+\.\d+[fF]?", rhs):
        return "Float" if rhs[-1] in "fF" else "Double"
    if re.fullmatch(r'".*"', rhs) or re.fullmatch(r'"""[\s\S]*"""', rhs):
        return "String"
    if re.fullmatch(r"'.'", rhs):
        return "Char"
    # ::property reference for property delegates — type unknown
    if rhs.startswith("::") or " by " in rhs:
        return None
    m = SIMPLE_CTOR_RE.match(rhs)
    if m:
        t = m.group("type")
        # Reject factory/function calls whose simple name is camelCase starting
        # with a lowercase letter (mutableStateOf, listOf, …) — those are not types.
        simple = t.rsplit(".", 1)[-1]
        if simple and simple[0].islower():
            return None
        return t
    return None


def insert_return_type(line: str) -> str:
    """
    Insert `: Type` on a fun/val/var line that lacks an explicit type.

    Handles:
      fun name(...) = expr
      fun name(...) {          # → : Unit (only when we can tell it's Unit — skip)
      val name = expr
      var name = expr
      val name by expr
    """
    # Already has a type annotation before = or { or by?
    # Rough check: `) : Type` or `name: Type` after the identifier.
    if re.search(r"\)\s*:\s*[\w.<]", line):
        return line
    if re.search(r"\b(val|var)\s+[A-Za-z_]\w*\s*:\s*", line):
        return line

    # fun name(...): Type already
    if re.search(r"\bfun\b.*=", line) and re.search(r"\)\s*:", line):
        return line

    # Expression-bodied function: fun foo(...) = rhs
    m = re.match(
        r"^(?P<pre>.*\bfun\b\s+(?:[A-Za-z_]\w*\.)?[A-Za-z_]\w*\s*\([^)]*\))\s*=\s*(?P<rhs>.+?)(?P<trail>\s*)$",
        line,
    )
    if m:
        inferred = infer_type_from_rhs(m.group("rhs"))
        if inferred:
            return f"{m.group('pre')}: {inferred} = {m.group('rhs')}{m.group('trail')}"
        return line

    # Property: val/var name = rhs   or   val/var name by rhs
    m = re.match(
        r"^(?P<pre>.*\b(?P<kv>val|var)\s+(?P<name>[A-Za-z_]\w*))\s*"
        r"(?P<op>=|by)\s*(?P<rhs>.+?)(?P<trail>\s*)$",
        line,
    )
    if m:
        rhs = m.group("rhs")
        op = m.group("op")
        if op == "by":
            # Delegated property: try `by ::other` → copy type if we can see it
            # Leave to a later manual/smarter pass if unknown.
            ref = re.match(r"::([A-Za-z_]\w*)\s*$", rhs.strip())
            if ref:
                # Same-file sibling often has an explicit type; caller can re-run.
                return line
            inferred = infer_type_from_rhs(rhs)
        else:
            inferred = infer_type_from_rhs(rhs)
        if inferred:
            return (
                f"{m.group('pre')}: {inferred} {op} {rhs}{m.group('trail')}"
            )
        return line

    # Block-bodied fun with no type — Unit is optional in explicit API for Unit,
    # so if the compiler still complained, annotate Unit.
    m = re.match(
        r"^(?P<pre>.*\bfun\b\s+(?:[A-Za-z_]\w*\.)?[A-Za-z_]\w*\s*\([^)]*\))\s*(?P<body>\{.*)$",
        line,
    )
    if m:
        return f"{m.group('pre')}: Unit {m.group('body')}"

    return line


def apply_fixes(errors: list[tuple[Path, int, int, str]]) -> dict[str, int]:
    # Group by file; apply bottom-up so line numbers stay valid within a file.
    by_file: dict[Path, list[tuple[int, int, str]]] = defaultdict(list)
    for path, line, col, msg in errors:
        by_file[path].append((line, col, msg))

    stats = {"visibility": 0, "return_type": 0, "skipped": 0, "files": 0}

    for path, items in by_file.items():
        if not path.is_file():
            print(f"skip missing file: {path}", file=sys.stderr)
            stats["skipped"] += len(items)
            continue
        text = path.read_text(encoding="utf-8")
        lines = text.splitlines(keepends=True)
        # Sort by line desc, then prefer visibility before return-type on same line
        # so we insert public first, then type on the modified line.
        items_sorted = sorted(
            items,
            key=lambda t: (t[0], 0 if "Visibility" in t[2] else 1),
            reverse=True,
        )
        changed = False
        # Track which physical lines we already inserted public on (1-based)
        public_done: set[int] = set()

        for line_no, col, msg in items_sorted:
            if line_no < 1 or line_no > len(lines):
                stats["skipped"] += 1
                continue
            idx = line_no - 1
            original = lines[idx]
            # Preserve newline style
            newline = ""
            body = original
            if original.endswith("\r\n"):
                newline = "\r\n"
                body = original[:-2]
            elif original.endswith("\n"):
                newline = "\n"
                body = original[:-1]

            if "Visibility must be specified" in msg:
                if line_no in public_done:
                    stats["skipped"] += 1
                    continue
                # If this line is only an annotation, put public on the next
                # non-annotation declaration line instead.
                target_idx = idx
                target_body = body
                if body.lstrip().startswith("@") and not re.search(
                    r"\b(class|object|interface|fun|val|var|enum|typealias)\b",
                    body,
                ):
                    j = idx + 1
                    while j < len(lines):
                        candidate = lines[j].rstrip("\r\n")
                        if candidate.lstrip().startswith("@"):
                            j += 1
                            continue
                        if candidate.strip() == "":
                            j += 1
                            continue
                        target_idx = j
                        target_body = candidate
                        break
                new_body = insert_public(target_body, col)
                if new_body != target_body:
                    t_nl = (
                        "\r\n"
                        if lines[target_idx].endswith("\r\n")
                        else "\n"
                        if lines[target_idx].endswith("\n")
                        else ""
                    )
                    t_body = lines[target_idx].rstrip("\r\n")
                    lines[target_idx] = insert_public(t_body, col) + t_nl
                    public_done.add(target_idx + 1)
                    changed = True
                    stats["visibility"] += 1
                else:
                    stats["skipped"] += 1
            elif "Return type must be specified" in msg:
                new_body = insert_return_type(body)
                if new_body != body:
                    lines[idx] = new_body + newline
                    changed = True
                    stats["return_type"] += 1
                else:
                    stats["skipped"] += 1
            else:
                stats["skipped"] += 1

        if changed:
            path.write_text("".join(lines), encoding="utf-8")
            stats["files"] += 1
            print(f"updated {path}")

    return stats


def main() -> int:
    if len(sys.argv) > 1:
        raw = Path(sys.argv[1]).read_text(encoding="utf-8").splitlines()
    else:
        raw = sys.stdin.read().splitlines()
    errors = parse_errors(raw)
    if not errors:
        print("no explicitApi diagnostics found", file=sys.stderr)
        return 1
    stats = apply_fixes(errors)
    print(
        f"done: files={stats['files']} visibility={stats['visibility']} "
        f"return_type={stats['return_type']} skipped={stats['skipped']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
