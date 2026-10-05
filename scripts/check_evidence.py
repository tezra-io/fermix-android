#!/usr/bin/env python3
"""promote.yml's evidence job (MILESTONE_51_ANDROID_CI_CD.md section 4.2).

A candidate is promoted only on a complete record of what a person saw it do on real phones:
release-evidence/<tag>.json on main, landed there by pull request. It is refused unless

  1. promote.yml runs on the tag itself, the one ref the release environment lets publish
  2. the file is on main and parses against release-evidence/schema.json
  3. its version is the tag's, and its apk_sha256 is the sha256 of the APK in the tag's draft release
  4. its engine is a published engine release, built from the commit it names, no older than the engine
     release contracts/SOURCE.json pins (C4)
  5. the device gate's three facts, pairing, attestation and the locked-phone push, are true, on a listed
     handset
  6. all eight scenarios of design section 15.3 are there, once each under their own names, each passed,
     each on a listed handset; scenario 8, two devices, names a second listed handset, and no other does

There is no override: no input, variable or flag lets a release past any of these (section 8). CI checks
that the record is complete and names the right build; it cannot check that the record is true (C6).

It asks GitHub through gh (GH_TOKEN) about REPO (owner/name) and the engine, reads the evidence from
origin/main of a checkout with main fetched, and the schema and SOURCE.json from the checkout itself, the
tag's. On success it appends apk_sha256=<hex> to <output> ($GITHUB_OUTPUT) for the jobs after it. Exits 1
with every refusal printed, 2 on a malformed argument, a missing tool or a schema it cannot read.

  check_evidence.py <vX.Y.Z> <ref> <output>
"""
import datetime
import hashlib
import json
import os
import pathlib
import re
import subprocess
import sys

ENGINE_REPOSITORY = "tezra-io/fermix"
SCENARIOS = {
    1: "process_death",
    2: "doze",
    3: "lost_acknowledgement",
    4: "network_change",
    5: "daemon_restart",
    6: "approval_away",
    7: "frozen_socket",
    8: "two_devices",
}
TWO_DEVICES = 8
DEVICE_GATE_FACTS = ("pairing", "attestation", "locked_push")
RELEASE_TAG = re.compile(r"v(\d+)\.(\d+)\.(\d+)")
REPOSITORY = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
# A gh call that takes longer than this is stuck; the APK download is the longest.
GH_TIMEOUT_SECONDS = 300
# The keywords the schema may use: each is checked below, and any other one stops the check rather than
# pass unchecked.
CHECKED_KEYWORDS = {
    "type", "properties", "required", "additionalProperties", "items", "minItems", "maxItems",
    "pattern", "enum", "const", "minimum", "maximum", "minLength", "maxLength", "$ref",
}
ANNOTATIONS = {"$schema", "$id", "title", "description", "$defs"}
JSON_TYPES = {"object": dict, "array": list, "string": str, "boolean": bool, "integer": int}


class Fatal(Exception):
    """A malformed argument, a missing tool or an unreadable schema: status 2, nothing checked."""


# --- the schema, as far as release-evidence/schema.json uses it -----------------------------------------


def resolve(reference, root):
    if not reference.startswith("#/$defs/"):
        raise Fatal(f"the schema refers to {reference}, which is not one of its own $defs")
    name = reference[len("#/$defs/"):]
    if name not in root.get("$defs", {}):
        raise Fatal(f"the schema refers to {reference}, which it does not define")
    return root["$defs"][name]


def matches(pattern, value):
    """Whether [value] matches the schema's anchored [pattern] as JSON Schema reads it: Python's $ also matches
    before a last newline, which "0.14.0\\n" would pass, so the end is \\Z."""
    if not (pattern.startswith("^") and pattern.endswith("$") and not pattern.endswith("\\$")):
        raise Fatal(f"the schema's pattern {pattern} is not anchored ^...$, as every pattern this check reads is")
    return re.search(pattern[:-1] + r"\Z", value) is not None


def scalar_problems(value, schema, where):
    problems = []
    if "enum" in schema and value not in schema["enum"]:
        problems.append(f"{where}: {value!r} is not one of {schema['enum']}")
    if "const" in schema and value != schema["const"]:
        problems.append(f"{where}: {value!r} is not {schema['const']!r}")
    if isinstance(value, str):
        if "pattern" in schema and not matches(schema["pattern"], value):
            problems.append(f"{where}: {value!r} does not match {schema['pattern']}")
        if len(value) < schema.get("minLength", 0) or len(value) > schema.get("maxLength", len(value)):
            problems.append(f"{where}: {len(value)} characters, outside what the schema allows")
    if type(value) is int and not schema.get("minimum", value) <= value <= schema.get("maximum", value):
        problems.append(f"{where}: {value} is outside {schema.get('minimum')} to {schema.get('maximum')}")
    return problems


def object_problems(value, schema, root, where):
    properties = schema.get("properties", {})
    problems = [f"{where}: no {name}" for name in schema.get("required", []) if name not in value]
    if schema.get("additionalProperties", True) is False:
        problems += [f"{where}: {name} is not a field of it" for name in value if name not in properties]
    for name, member in value.items():
        if name in properties:
            problems += schema_problems(member, properties[name], root, f"{where}.{name}")
    return problems


def array_problems(value, schema, root, where):
    problems = []
    if not schema.get("minItems", 0) <= len(value) <= schema.get("maxItems", len(value)):
        bounds = f"{schema.get('minItems')} to {schema.get('maxItems')}"
        problems.append(f"{where}: {len(value)} items, outside {bounds}")
    for index, item in enumerate(value):
        problems += schema_problems(item, schema.get("items", {}), root, f"{where}[{index}]")
    return problems


def schema_problems(value, schema, root, where="evidence"):
    unknown = set(schema) - CHECKED_KEYWORDS - ANNOTATIONS
    if unknown:
        raise Fatal(f"the schema uses {sorted(unknown)} at {where}, which this check does not check")
    problems = schema_problems(value, resolve(schema["$ref"], root), root, where) if "$ref" in schema else []
    expected = schema.get("type")
    if expected is not None and type(value) is not JSON_TYPES[expected]:
        return problems + [f"{where}: expected {expected}, found {type(value).__name__}"]
    problems += scalar_problems(value, schema, where)
    if isinstance(value, dict):
        problems += object_problems(value, schema, root, where)
    if isinstance(value, list):
        problems += array_problems(value, schema, root, where)
    return problems


# --- the release's checks, on facts gathered first (pure) --------------------------------------------------


def version_of(text):
    """[text] as (major, minor, patch), or None when it is no version."""
    match = RELEASE_TAG.fullmatch("v" + text)
    return tuple(int(part) for part in match.groups()) if match else None


def date_problems(evidence):
    dates = [("device_gate.at", evidence["device_gate"]["at"])]
    dates += [(f"handset {handset['id']}'s patch", handset["patch"]) for handset in evidence["handsets"]]
    dates += [(f"scenario {scenario['n']}'s at", scenario["at"]) for scenario in evidence["scenarios"]]
    problems = []
    for where, text in dates:
        try:
            datetime.date.fromisoformat(text)
        except ValueError:
            problems.append(f"{where}, {text}, is not a date")
    return problems


def handset_problems(evidence):
    ids = [handset["id"] for handset in evidence["handsets"]]
    problems = [f"handset {name} is listed twice" for name in sorted(set(ids)) if ids.count(name) > 1]
    if evidence["device_gate"]["handset"] not in ids:
        problems.append(f"the device gate ran on {evidence['device_gate']['handset']}, not a listed handset")
    false = [fact for fact in DEVICE_GATE_FACTS if evidence["device_gate"][fact] is not True]
    if false:
        problems.append(f"the device gate's {', '.join(false)} is not true: the gate is not passed (section 12.6)")
    return problems


def scenario_problems(scenario, handsets):
    n, problems = scenario["n"], []
    if scenario["name"] != SCENARIOS[n]:
        problems.append(f"scenario {n} is {SCENARIOS[n]}, not {scenario['name']}")
    if scenario["passed"] is not True:
        problems.append(f"scenario {n} ({SCENARIOS[n]}) is marked failed: a failed scenario blocks the release")
    if scenario["handset"] not in handsets:
        problems.append(f"scenario {n} ran on {scenario['handset']}, which is not a listed handset")
    second = scenario.get("second_handset")
    if n == TWO_DEVICES and second is None:
        problems.append(f"scenario {n} ({SCENARIOS[n]}) names one handset: it runs on two")
    if n == TWO_DEVICES and second is not None and (second not in handsets or second == scenario["handset"]):
        problems.append(f"scenario {n}'s second handset, {second}, is not another listed handset")
    if n != TWO_DEVICES and second is not None:
        problems.append(f"scenario {n} names a second handset, which only scenario {TWO_DEVICES} runs on")
    return problems


def scenarios_problems(evidence):
    handsets = {handset["id"] for handset in evidence["handsets"]}
    numbers = [scenario["n"] for scenario in evidence["scenarios"]]
    problems = [f"scenario {n} is recorded twice" for n in sorted(set(numbers)) if numbers.count(n) > 1]
    problems += [
        f"scenario {n} ({SCENARIOS[n]}) is missing: a scenario that was not run blocks the release"
        for n in sorted(set(SCENARIOS) - set(numbers))
    ]
    for scenario in evidence["scenarios"]:
        problems += scenario_problems(scenario, handsets)
    return problems


def release_problems(evidence, facts):
    """Every reason [evidence] does not release the candidate [facts] describe."""
    problems = []
    if evidence["version"] != facts["version"]:
        problems.append(f"the evidence is for {evidence['version']}, not {facts['version']}")
    if evidence["apk_sha256"] != facts["draft_apk_sha256"]:
        problems.append(
            f"apk_sha256 {evidence['apk_sha256']} is not the draft's APK, {facts['draft_apk_sha256']}: "
            "the evidence names another build"
        )
    problems += facts["engine_problems"]
    engine = version_of(evidence["engine"]["version"])
    if engine is None:
        problems.append(f"the engine's version {evidence['engine']['version']!r} is no version X.Y.Z")
    elif engine < facts["pinned_engine"]:
        pinned = ".".join(str(part) for part in facts["pinned_engine"])
        problems.append(f"the engine {evidence['engine']['version']} is older than {pinned}, the contract's (C4)")
    return problems + date_problems(evidence) + handset_problems(evidence) + scenarios_problems(evidence)


# --- facts, from git and GitHub (effectful) ----------------------------------------------------------------


def git(*arguments):
    result = subprocess.run(["git", *arguments], capture_output=True, check=False, timeout=GH_TIMEOUT_SECONDS)
    return result.returncode, result.stdout


def gh(*arguments):
    """gh's answer as bytes, or None with its words when it gave none."""
    result = subprocess.run(["gh", *arguments], capture_output=True, check=False, timeout=GH_TIMEOUT_SECONDS)
    if result.returncode != 0:
        return None, result.stderr.decode(errors="replace").strip()
    return result.stdout, ""


def draft_apk_sha256(repository, tag, version):
    """The sha256 of the one APK in the tag's one draft release, or why there is none."""
    answer, words = gh("api", "--paginate", "--slurp", f"repos/{repository}/releases?per_page=100")
    if answer is None:
        return None, f"gh could not list {repository}'s releases: {words}"
    releases = [release for page in json.loads(answer) for release in page if release.get("tag_name") == tag]
    if len(releases) != 1 or releases[0].get("draft") is not True:
        return None, f"{repository} has no one draft release for {tag}: run candidate.yml for it first"
    name = f"fermix-android-{version}.apk"
    assets = [asset for asset in releases[0].get("assets", []) if asset.get("name") == name]
    if len(assets) != 1:
        return None, f"the draft release for {tag} holds {len(assets)} assets named {name}, not one"
    asset = f"repos/{repository}/releases/assets/{assets[0]['id']}"
    apk, words = gh("api", "-H", "Accept: application/octet-stream", asset)
    if apk is None:
        return None, f"gh could not download {name}: {words}"
    return hashlib.sha256(apk).hexdigest(), ""


def engine_problems(engine):
    tag = f"v{engine['version']}"
    answer, words = gh("api", f"repos/{ENGINE_REPOSITORY}/releases/tags/{tag}")
    if answer is None:
        return [f"{ENGINE_REPOSITORY} has no published release {tag}: {words}"]
    release = json.loads(answer)
    if release.get("draft") is not False or release.get("prerelease") is not False:
        return [f"{ENGINE_REPOSITORY}'s {tag} is a draft or a prerelease, not a published release"]
    answer, words = gh("api", f"repos/{ENGINE_REPOSITORY}/commits/{tag}")
    if answer is None:
        return [f"gh could not read the commit of {ENGINE_REPOSITORY}'s {tag}: {words}"]
    commit = json.loads(answer).get("sha")
    if commit != engine["commit"]:
        return [f"{ENGINE_REPOSITORY}'s {tag} is {commit}, not {engine['commit']} as the evidence says"]
    return []


def pinned_engine_of(source):
    """The engine release a contracts/SOURCE.json pins, as a version."""
    release = source.get("upstream", {}).get("release", "")
    match = RELEASE_TAG.fullmatch(release)
    if not match:
        raise Fatal(f"contracts/SOURCE.json names no engine release as upstream.release (found {release!r})")
    return tuple(int(part) for part in match.groups())


def evidence_on_main(tag):
    """The evidence file as main has it, or why it is not there."""
    path = f"release-evidence/{tag}.json"
    status, _ = git("rev-parse", "--verify", "--quiet", "refs/remotes/origin/main")
    if status != 0:
        raise Fatal("the checkout has no origin/main; check out with fetch-depth: 0")
    status, text = git("show", f"refs/remotes/origin/main:{path}")
    if status != 0:
        return None, f"main has no {path}: it lands there by pull request once the candidate is tested"
    try:
        return json.loads(text), ""
    except json.JSONDecodeError as error:
        return None, f"{path} on main is not JSON: {error}"


# --- the run -----------------------------------------------------------------------------------------------


def arguments():
    if len(sys.argv) != 4:
        raise Fatal("usage: check_evidence.py <vX.Y.Z> <ref> <output>")
    tag, ref, output = sys.argv[1:]
    if not RELEASE_TAG.fullmatch(tag):
        raise Fatal(f"{tag!r} is not a release tag vMAJOR.MINOR.PATCH")
    repository = os.environ.get("REPO", "")
    if not REPOSITORY.fullmatch(repository):
        raise Fatal("REPO names no owner/repository")
    return tag, ref, pathlib.Path(output), repository


def refusals(tag, ref, repository):
    """Every refusal, and the draft APK's sha256 when there is none."""
    if ref != f"refs/tags/{tag}":
        why = f"promote.yml runs on {ref}: run it on the tag, --ref {tag}, as only v* tags may publish (C8)"
        return [why], None
    evidence, why = evidence_on_main(tag)
    if evidence is None:
        return [why], None
    schema = json.loads(pathlib.Path("release-evidence/schema.json").read_text())
    problems = schema_problems(evidence, schema, schema)
    if problems:
        return problems, None
    version = tag[1:]
    sha256, why = draft_apk_sha256(repository, tag, version)
    if sha256 is None:
        return [why], None
    facts = {
        "version": version,
        "draft_apk_sha256": sha256,
        "engine_problems": engine_problems(evidence["engine"]),
        "pinned_engine": pinned_engine_of(json.loads(pathlib.Path("contracts/SOURCE.json").read_text())),
    }
    return release_problems(evidence, facts), sha256


def main():
    try:
        tag, ref, output, repository = arguments()
        problems, sha256 = refusals(tag, ref, repository)
    except Fatal as fatal:
        print(f"check_evidence: {fatal}", file=sys.stderr)
        return 2
    for problem in problems:
        print(f"check_evidence: {problem}", file=sys.stderr)
    if problems:
        print(f"check_evidence: {tag} is not promoted", file=sys.stderr)
        return 1
    with open(output, "a", encoding="utf-8") as outputs:
        outputs.write(f"apk_sha256={sha256}\n")
    print(f"check_evidence: release-evidence/{tag}.json releases the draft's APK {sha256}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
