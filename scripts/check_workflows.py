#!/usr/bin/env python3
"""The house rules for GitHub workflows (MILESTONE_51_ANDROID_CI_CD.md C12, F7, section 8; AGENTS.md), which
actionlint does not hold. Each workflow file given

  1. names its permissions at the top, so a job holds only what it says it needs
  2. runs on no pull_request_target, which hands a fork's code the repository's token and secrets
  3. gives every job a timeout-minutes of its own, a whole number of minutes
  4. pins every action it uses by a full 40-hex commit, with the version it is in a comment after it
  5. takes no input named for a way around a check: skip, force, override, bypass, ignore or no-verify
     (section 8: "a skip evidence input, a force publish input ... is a way to be green without being right");
     and the release workflows take what section 4 gives them and nothing else, promote.yml the tag alone and
     candidate.yml no input at all, so that no other name carries a way around the evidence either

It reads the files as text and holds them to the block style this repository writes them in, two spaces a
level: a job two spaces in under jobs: with its keys four in, an input two spaces in under its inputs:.
Outside a block scalar's text (run: |), every line is a comment, a plain key or a list item, and every value
is whole on its own line in a form that means what it shows: a plain value, a quoted one closed on its line
with no escape, or a flow sequence [a, "b"] of such words. Anything else fails it: a quoted, complex or merged
key; an anchor, an alias or a tag; a flow mapping; a quoted value left open, or a value continued on the next
line; a top-level key given twice; a tab or a control character; a job or an input at another depth. Each is
valid YAML that a parser reads past what the text shows (an alias carries an input into on:, an open quote
hides the key after it, an escape spells pull_request_target), so what the check cannot place it refuses
rather than pass unread.
Exits 1 with every finding printed, 2 on a missing file.

  check_workflows.py <workflow.yml>...
"""
import pathlib
import re
import sys

USAGE = "usage: check_workflows.py <workflow.yml>..."
# A tab, a control character, a byte-order mark, or a space or line break other than ASCII's: YAML and this check
# would split or indent the line differently.
UNPLACEABLE_CHARACTER = re.compile(r"[^\S ]|[\x00-\x1f\x7f\ufeff]")
TOP_KEY = re.compile(r"^([A-Za-z0-9_.-]+):(\s.*)?$")
# The two forms a line outside a block scalar's text takes, besides a comment: a plain key, a list item's first
# included, with its value or none, and a list item that is a value.
KEY_LINE = re.compile(r"^( *)(- +)?[A-Za-z0-9_.-]+:(?:\s+(.*))?$")
ITEM_LINE = re.compile(r"^( *)- +(\S.*)$")
# The value forms a line holds whole: a block scalar's header, whose text follows; a quoted value, with no
# escape and closed on its line; a flow sequence of plain or quoted words; and a plain value, which starts with
# no indicator and holds no key.
TRAILING = r"(?:\s+#.*|\s*)$"
DOUBLE_QUOTED = r'"[^"\\]*"'
SINGLE_QUOTED = r"'(?:[^']|'')*'"
FLOW_WORD = rf"(?:{DOUBLE_QUOTED}|{SINGLE_QUOTED}|[A-Za-z0-9_][A-Za-z0-9_./-]*)"
BLOCK_HEADER = re.compile(rf"[|>][0-9+-]*{TRAILING}")
QUOTED = re.compile(rf"(?:{DOUBLE_QUOTED}|{SINGLE_QUOTED}){TRAILING}")
FLOW_SEQUENCE = re.compile(rf"\[\s*(?:{FLOW_WORD}(?:\s*,\s*{FLOW_WORD})*\s*)?\]{TRAILING}")
PLAIN_FIRST = re.compile(r"[^\s?:,\[\]{}#&*!|>'\"%@`-]|[-?:]\S")
PLAIN_COMMENT = re.compile(r"\s#")
# A key whose value is a block scalar: its text is every line after it indented past the key.
BLOCK_SCALAR = re.compile(r"^( *)(- +)?[A-Za-z0-9_.-]+:\s+[|>][0-9+-]*\s*(#.*)?$")
JOB = re.compile(r"^  ([A-Za-z0-9_-]+):\s*(#.*)?$")
JOB_CONTENT = "    "
TIMEOUT = re.compile(r"^    timeout-minutes:\s+([1-9][0-9]*)\s*(#.*)?$")
USES = re.compile(r"\buses[\"']?\s*:")
PINNED_USES = re.compile(
    r"^\s*(-\s+)?uses:\s+[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(/[A-Za-z0-9_./-]+)?@[0-9a-f]{40}"
    r"\s+#\s*v[0-9]+(\.[0-9]+)*\S*\s*$"
)
INPUTS = re.compile(r"^( *)inputs:\s*(#.*)?$")
INPUTS_KEY = re.compile(r"^ *(- +)?inputs:")
INPUT = re.compile(r"^ *([A-Za-z0-9_-]+):\s*(#.*)?$")
WAY_AROUND = re.compile(r"skip|force|override|bypass|ignore|no[-_]?verify", re.IGNORECASE)
# Section 4: a tag runs candidate.yml, and promote.yml is run by hand with the tag as its one input.
EXACT_INPUTS = {"candidate.yml": [], "promote.yml": ["tag"]}


def indent_of(line):
    return len(line) - len(line.lstrip(" "))


def blank_or_comment(line):
    return not line.strip() or line.lstrip().startswith("#")


def block_scalar_text(lines):
    """The indexes of the lines that are a block scalar's text, run: | and the like: words, not keys."""
    text, parent = set(), None
    for index, line in enumerate(lines):
        if parent is not None and (not line.strip() or indent_of(line) > parent):
            text.add(index)
            continue
        block = BLOCK_SCALAR.match(line)
        parent = len(block.group(1)) + len(block.group(2) or "") if block else None
    return text


def top_level_blocks(lines):
    """({key: (first line index, end index)} of each top-level key, [findings]): a key given twice is one, as
    a parser that keeps the last of the two would read past the first."""
    starts = [(index, TOP_KEY.match(line).group(1)) for index, line in enumerate(lines) if TOP_KEY.match(line)]
    ends = [start for start, _ in starts[1:]] + [len(lines)]
    blocks, findings = {}, []
    for (start, key), end in zip(starts, ends):
        if key in blocks:
            findings.append((start, f"a second top-level {key}:, which a parser may read in place of the first"))
        blocks.setdefault(key, (start, end))
    return blocks, findings


def value_finding(value):
    """None when [value], the words after a key's colon or an item's dash, is whole on its line and means what
    it shows; else what it is."""
    if QUOTED.match(value) or FLOW_SEQUENCE.match(value):
        return None
    if value[0] in "\"'":
        return "a quoted value left open or with an escape, or a quoted key"
    if value[0] == "[":
        return "a flow sequence of anything but plain or quoted words"
    if value[0] == "{":
        return "a flow mapping, which this check cannot read"
    if value[0] in "&*!":
        return "an anchor, an alias or a tag, whose value is written elsewhere"
    if not PLAIN_FIRST.match(value):
        return "a value that starts with an indicator"
    words = PLAIN_COMMENT.split(value, maxsplit=1)[0].rstrip()
    return "a key inside a value" if ": " in words or words.endswith(":") else None


def placed(line):
    """(the column the next line may not pass, or None when a block may follow; a finding or None) of a line
    outside a block scalar's text. A line with a value is whole: the next one deeper would continue it."""
    key = KEY_LINE.match(line)
    if key:
        column, value = len(key.group(1)) + len(key.group(2) or ""), (key.group(3) or "").strip()
        if not value or value.startswith("#"):
            return None, None
        if BLOCK_HEADER.match(value):
            return column, None
        finding = value_finding(value)
        return (None, finding) if finding else (column, None)
    item = ITEM_LINE.match(line)
    if item:
        finding = value_finding(item.group(2).strip())
        return (None, finding) if finding else (len(item.group(1)), None)
    return None, "a line this check cannot place: a quoted, complex or merged key, or a document marker"


def line_findings(lines, text):
    findings, limit = [], 0
    for index, line in enumerate(lines):
        if UNPLACEABLE_CHARACTER.search(line):
            findings.append((index, "a tab, a control character or a space other than ASCII's, which this check"
                                    " cannot place"))
        if blank_or_comment(line):
            continue
        if USES.search(line) and not PINNED_USES.match(line):
            findings.append((index, "an action not pinned by a full commit with its version in a comment"))
        if "pull_request_target" in line:
            findings.append((index, "pull_request_target, which runs a fork's code with the repository's secrets"))
        if index in text:
            continue
        if limit is not None and indent_of(line) > limit:
            findings.append((index, "a value continued from the line before"))
        limit, finding = placed(line)
        findings += [(index, finding)] if finding else []
    return findings


def job_findings(lines, start, end, text):
    """Every job under jobs: is two spaces in, its keys four in, and has its own timeout-minutes."""
    findings, job, job_line, timed, orphaned = [], None, 0, False, False
    for index in range(start + 1, end):
        line = lines[index]
        if blank_or_comment(line) or index in text:
            continue
        if JOB.match(line):
            findings += [(job_line, f"job {job} has no timeout-minutes")] if job and not timed else []
            job, job_line, timed = JOB.match(line).group(1), index, False
        elif not line.startswith(JOB_CONTENT):
            findings.append((index, "a line in jobs: that is neither a job nor indented under one"))
        elif job is None and not orphaned:
            findings.append((index, "a line in jobs: before any job: a job is two spaces in, its keys four"))
            orphaned = True
        timed = timed or bool(TIMEOUT.match(line))
    if job and not timed:
        findings.append((job_line, f"job {job} has no timeout-minutes"))
    return findings


def input_line(line, inputs_indent, named):
    """(the input's name or None, a finding or None) for a line under an inputs: [inputs_indent] in. A line
    past the inputs' depth is one of an input's own, once there is an input."""
    indent = indent_of(line)
    if indent == inputs_indent + 2:
        input_name = INPUT.match(line)
        return (input_name.group(1), None) if input_name else (None, "an input that is not a plain key in block style")
    if indent < inputs_indent + 2:
        return None, "a line of inputs: that is not two spaces in"
    return None, None if named else "a line of inputs: deeper than an input, which is two spaces in"


def input_findings(lines, start, end, text):
    """([findings], [(index, input name)]) of on:'s lines; line_findings refuses a flow mapping in them."""
    findings, names, inputs_indent, named = [], [], None, False
    for index in range(start, end):
        line = lines[index]
        if blank_or_comment(line) or index in text:
            continue
        if inputs_indent is not None and indent_of(line) <= inputs_indent:
            inputs_indent = None
        if INPUTS.match(line):
            inputs_indent, named = indent_of(line), False
        elif INPUTS_KEY.search(line):
            findings.append((index, "inputs in a form this check cannot read: inputs: is a block of its own"))
        elif inputs_indent is not None:
            name, finding = input_line(line, inputs_indent, named)
            findings += [(index, finding)] if finding else []
            names += [(index, name)] if name else []
            named = named or bool(name)
    return findings, names


def name_findings(path, names):
    """No input is named for a way around a check, and a release workflow takes its own inputs alone."""
    findings = [
        (index, f"input {name} is named for a way around a check (section 8)")
        for index, name in names if WAY_AROUND.search(name)
    ]
    expected = EXACT_INPUTS.get(path.name)
    found = sorted(name for _, name in names)
    if expected is not None and found != expected:
        taken = ", ".join(found) or "none"
        findings.append((0, f"{path.name} takes the inputs {taken}, not {', '.join(expected) or 'none'} (section 4)"))
    return findings


def findings_of(path):
    # Split at line feeds alone, as YAML does, where splitlines would split at a form feed or a separator too.
    lines = path.read_text(encoding="utf-8").split("\n")
    text = block_scalar_text(lines)
    blocks, findings = top_level_blocks(lines)
    findings += line_findings(lines, text)
    for key in ("on", "permissions", "jobs"):
        if key not in blocks:
            findings.append((0, f"no top-level {key}:"))
    if "jobs" in blocks:
        findings += job_findings(lines, *blocks["jobs"], text)
    names = []
    if "on" in blocks:
        on_findings, names = input_findings(lines, *blocks["on"], text)
        findings += on_findings
    findings += name_findings(path, names)
    return [f"{path}:{index + 1}: {message}" for index, message in sorted(findings)]


def main():
    paths = [pathlib.Path(argument) for argument in sys.argv[1:]]
    if not paths or sys.argv[1] in ("-h", "--help"):
        print(USAGE, file=sys.stderr)
        return 2
    missing = [str(path) for path in paths if not path.is_file()]
    if missing:
        print(f"check_workflows: no such file: {', '.join(missing)}", file=sys.stderr)
        return 2
    findings = [finding for path in paths for finding in findings_of(path)]
    for finding in findings:
        print(f"check_workflows: {finding}", file=sys.stderr)
    if findings:
        return 1
    print(f"check_workflows: {len(paths)} workflows pin every action, time every job and take no way around a check")
    return 0


if __name__ == "__main__":
    sys.exit(main())
