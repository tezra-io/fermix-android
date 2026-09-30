#!/usr/bin/env bash
#
# Verify the vendored mobile wire contract.
#
# The canonical source is `FermixChannels.Mobile.Protocol` in the fermix repo,
# exported to `apps/fermix_core/priv/mobile/`. This repo carries a byte-identical
# copy in `contracts/mobile/`, pinned by two records in `contracts/`:
#
#   CHECKSUMS.txt  the digest of every vendored file, in `shasum -a 256 -c` form
#   SOURCE.json    where each file came from and what it hashed to upstream
#
# Three checks always run:
#
#   1. the vendored bytes match CHECKSUMS.txt
#   2. the tree and CHECKSUMS.txt list exactly the same files, so a file cannot
#      be added or dropped without the pin noticing
#   3. CHECKSUMS.txt and SOURCE.json agree, so regenerating the checksums over a
#      locally edited file does not verify clean. SOURCE.json also names the
#      engine repository and a full commit id, records one contract, the
#      engine's mobile export vendored at contracts/mobile, lists each file once,
#      as a plain relative path that mirrors its engine path, and states the
#      protocol window the vendored schema declares; and no contract is a draft
#
# One comparison with upstream is optional and explicit:
#
#   verify_protocol_contract.sh --source <path-to-fermix-checkout>
#   verify_protocol_contract.sh --pinned
#
# --source byte-compares every vendored file against the path SOURCE.json
# records in a local engine checkout; a re-vendor is verified with it.
# --pinned first proves that the engine branch SOURCE.json names carries the
# pinned commit, then fetches that commit from the public engine repository and
# makes the same comparison against it; CI runs it on every pull request (CI/CD
# design C4). The repository and the directory compared are always the engine's
# mobile export, never whatever SOURCE.json says. Both also refuse an upstream
# contract directory that holds a file the vendored copy lacks, because the copy
# is of the whole directory, and an upstream path that resolves outside the tree.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONTRACTS_DIR="$ROOT_DIR/contracts"
# The one repository the copy is compared with (C4). SOURCE.json must name it too.
ENGINE_REPOSITORY="tezra-io/fermix"
ENGINE_URL="https://github.com/$ENGINE_REPOSITORY.git"
# The one engine directory the copy is of, and where it sits under contracts/
# (design section 12.6). SOURCE.json must name both too.
ENGINE_CONTRACT_DIRECTORY="apps/fermix_core/priv/mobile"
VENDORED_DIRECTORY="mobile"
# A fetch slower than this many bytes per second for this many seconds is abandoned.
FETCH_LOW_SPEED_BYTES=1000
FETCH_LOW_SPEED_SECONDS=30

fail() {
  echo "verify_protocol_contract: $*" >&2
  exit 1
}

usage() {
  echo "usage: verify_protocol_contract.sh [--source <fermix-checkout> | --pinned]" >&2
  exit 2
}

# 1. The vendored bytes are the pinned bytes.
check_checksums() {
  (cd "$CONTRACTS_DIR" && shasum -a 256 --check --strict CHECKSUMS.txt) ||
    fail "the vendored bytes do not match CHECKSUMS.txt"
}

# 2. The manifest covers the tree exactly. Anything that is not a directory
# counts, so a symlink cannot slip past the pin either.
check_listing() {
  local present pinned
  present="$(cd "$CONTRACTS_DIR" &&
    find . ! -type d ! -path ./CHECKSUMS.txt ! -path ./SOURCE.json |
    sed 's|^\./||' | LC_ALL=C sort)" || fail "could not list the vendored tree"
  pinned="$(awk '{ print $2 }' "$CONTRACTS_DIR/CHECKSUMS.txt" | LC_ALL=C sort)" ||
    fail "could not read CHECKSUMS.txt"
  [ -n "$pinned" ] || fail "CHECKSUMS.txt lists no file"
  [ "$present" = "$pinned" ] && return 0
  echo "vendored files (- pinned only, + present only):" >&2
  diff <(echo "$pinned") <(echo "$present") >&2 || true
  fail "CHECKSUMS.txt does not list exactly the files in the tree"
}

# 3. The two records agree, file for file and digest for digest, and SOURCE.json
# describes the engine, the layout and the protocol the vendored files carry.
check_provenance() {
  python3 - "$CONTRACTS_DIR" "$ENGINE_REPOSITORY" "$ENGINE_CONTRACT_DIRECTORY" \
    "$VENDORED_DIRECTORY" <<'PY'
import json
import pathlib
import re
import sys

FULL_COMMIT = re.compile(r"[0-9a-f]{40}")
BRANCH_NAME = re.compile(r"[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*")


def read_checksums(root):
    pinned = {}
    for line in (root / "CHECKSUMS.txt").read_text().splitlines():
        digest, path = line.split()
        pinned[path] = digest
    return pinned


def read_recorded(provenance):
    return {
        entry["path"]: entry["sha256"]
        for contract in provenance["contracts"]
        for entry in contract["files"]
    }


def disagreements(pinned, recorded):
    problems = []
    for path in sorted(set(pinned) | set(recorded)):
        if path not in pinned:
            problems.append(f"{path}: in SOURCE.json but not in CHECKSUMS.txt")
        elif path not in recorded:
            problems.append(f"{path}: in CHECKSUMS.txt but not in SOURCE.json")
        elif pinned[path] != recorded[path]:
            problems.append(
                f"{path}: CHECKSUMS.txt has {pinned[path][:12]}, "
                f"SOURCE.json records {recorded[path][:12]}"
            )
    return problems


def matches(value, pattern):
    return isinstance(value, str) and pattern.fullmatch(value) is not None


def upstream_problems(upstream, engine_repository):
    problems = []
    if upstream.get("repository") != engine_repository:
        problems.append(
            f"upstream.repository is {upstream.get('repository')!r}, but the copy is only "
            f"ever compared with the engine repository, {engine_repository}"
        )
    if not matches(upstream.get("commit"), FULL_COMMIT):
        problems.append("upstream.commit is not a full 40-character commit id")
    if not matches(upstream.get("branch"), BRANCH_NAME):
        problems.append("upstream.branch is not a branch name")
    return problems


def contract_problems(contracts, source_directory, vendored_directory):
    """One contract: the engine's mobile export, vendored at contracts/mobile."""
    if len(contracts) != 1:
        return [f"SOURCE.json records {len(contracts)} contracts; the copy is of {source_directory} alone"]
    contract = contracts[0]
    problems = []
    if contract.get("source_directory") != source_directory:
        problems.append(
            f"source_directory is {contract.get('source_directory')!r}, but the copy is only ever "
            f"of the engine's {source_directory}"
        )
    if contract.get("vendored_directory") != vendored_directory:
        problems.append(
            f"vendored_directory is {contract.get('vendored_directory')!r}, but the copy only ever "
            f"sits at contracts/{vendored_directory}"
        )
    return problems


def is_plain_relative(value):
    """A relative path of named components: not absolute, and no empty, '.' or '..' component."""
    if not isinstance(value, str) or value.startswith("/"):
        return False
    return all(part not in ("", ".", "..") for part in value.split("/"))


def repeated(values):
    return sorted({value for value in values if values.count(value) > 1})


def layout_problems(contract, source_directory, vendored_directory):
    """Each file is recorded once, as a plain path, and its vendored path mirrors its engine path."""
    entries = contract["files"]
    prefix = source_directory + "/"
    problems = [f"{path}: recorded twice as a path" for path in repeated([e["path"] for e in entries])]
    problems += [
        f"{path}: recorded twice as a source_path"
        for path in repeated([e["source_path"] for e in entries])
    ]
    for entry in entries:
        source_path = entry["source_path"]
        if not (is_plain_relative(source_path) and is_plain_relative(entry["path"])):
            problems.append(f"{entry['path']!r} from {source_path!r}: not a plain relative path")
        elif not source_path.startswith(prefix):
            problems.append(f"{source_path}: outside {source_directory}")
        elif entry["path"] != vendored_directory + "/" + source_path[len(prefix):]:
            problems.append(f"{entry['path']}: is not the vendored path of {source_path}")
    return problems


def is_version(value):
    # bool is an int in Python, and never a protocol version.
    return type(value) is int


def declared_window(schema):
    """(version, min, max) from the schema, or None when it does not state all three."""
    window = schema.get("x-supported-version-range")
    if not isinstance(window, dict):
        return None
    declared = (schema.get("x-protocol-version"), window.get("min"), window.get("max"))
    return declared if all(is_version(value) for value in declared) else None


def recorded_window(contract):
    """(version, minimum, maximum) from SOURCE.json, or None when it does not record all three."""
    range_ = contract.get("supported_version_range")
    if not isinstance(range_, dict):
        return None
    recorded = (contract.get("protocol_version"), range_.get("minimum"), range_.get("maximum"))
    return recorded if all(is_version(value) for value in recorded) else None


def version_problems(root, contract, vendored_directory):
    """Both sides state a protocol window, and it is the same one."""
    schema_file = root / vendored_directory / "protocol.schema.json"
    if not schema_file.is_file():
        return [f"the {contract['name']} contract has no protocol.schema.json to read its version from"]
    declared = declared_window(json.loads(schema_file.read_text()))
    recorded = recorded_window(contract)
    problems = []
    if declared is None:
        problems.append(
            "protocol.schema.json does not declare an integer x-protocol-version and an "
            "x-supported-version-range with integer min and max"
        )
    if recorded is None:
        problems.append(
            f"SOURCE.json does not record the {contract['name']} contract's protocol_version and "
            "supported_version_range {minimum, maximum} as integers"
        )
    if problems or recorded == declared:
        return problems
    return [
        f"the {contract['name']} contract records protocol {recorded[0]} on the {recorded[1]} to "
        f"{recorded[2]} window, but its protocol.schema.json declares protocol {declared[0]} on "
        f"the {declared[1]} to {declared[2]} window"
    ]


def drafts(provenance):
    return [
        f"the {c['name']} contract declares itself a draft; a draft has no upstream "
        f"to compare against, so it is not shippable. Re-vendor it from the engine."
        for c in provenance["contracts"]
        if c.get("draft", False)
    ]


root = pathlib.Path(sys.argv[1])
engine_repository, source_directory, vendored_directory = sys.argv[2:5]
provenance = json.loads((root / "SOURCE.json").read_text())
problems = upstream_problems(provenance["upstream"], engine_repository)
problems += disagreements(read_checksums(root), read_recorded(provenance))
problems += contract_problems(provenance["contracts"], source_directory, vendored_directory)
for contract in provenance["contracts"]:
    problems += layout_problems(contract, source_directory, vendored_directory)
    problems += version_problems(root, contract, vendored_directory)
problems += drafts(provenance)
for problem in problems:
    print(f"verify_protocol_contract: {problem}", file=sys.stderr)
if problems:
    sys.exit(1)
for contract in provenance["contracts"]:
    if not contract.get("committed_upstream", True):
        print(
            f"verify_protocol_contract: note: the {contract['name']} contract was vendored "
            f"from an uncommitted upstream working tree "
            f"({provenance['upstream']['commit'][:12]}); re-take the pin from the commit "
            f"that publishes it before release"
        )
PY
}

# Byte-compare the vendored tree against an upstream tree laid out like the
# engine repository: every recorded file, and nothing upstream left out.
# check_provenance has already held SOURCE.json to one contract of plain paths
# under the engine's contract directory; the containment check stands anyway.
compare_upstream() {
  local upstream="$1"
  python3 - "$CONTRACTS_DIR" "$upstream" "$ENGINE_CONTRACT_DIRECTORY" <<'PY'
import json
import pathlib
import sys


def upstream_listing(upstream, directory):
    base = upstream / directory
    return {
        str(path.relative_to(upstream))
        for path in base.rglob("*")
        if path.is_file() or path.is_symlink()
    }


def entry_drift(root, upstream, entry):
    source = upstream / entry["source_path"]
    if not source.resolve().is_relative_to(upstream.resolve()):
        return [f"{entry['source_path']}: resolves outside the upstream tree"]
    if not source.is_file():
        return [f"{entry['source_path']}: missing from the upstream tree"]
    if source.read_bytes() != (root / entry["path"]).read_bytes():
        return [f"{entry['path']}: differs from {entry['source_path']}"]
    return []


def contract_drift(root, upstream, contract, source_directory):
    if not (upstream / source_directory).is_dir():
        return [f"{source_directory}: missing from the upstream tree"]
    source_paths = {entry["source_path"] for entry in contract["files"]}
    unvendored = upstream_listing(upstream, source_directory) - source_paths
    drift = [f"{path}: upstream carries it, the vendored copy does not" for path in sorted(unvendored)]
    for entry in contract["files"]:
        drift += entry_drift(root, upstream, entry)
    return drift


root = pathlib.Path(sys.argv[1])
upstream = pathlib.Path(sys.argv[2])
source_directory = sys.argv[3]
provenance = json.loads((root / "SOURCE.json").read_text())
drift = [
    line
    for contract in provenance["contracts"]
    for line in contract_drift(root, upstream, contract, source_directory)
]
for line in drift:
    print(f"verify_protocol_contract: {line}", file=sys.stderr)
if drift:
    print(
        "verify_protocol_contract: re-vendor the contract tree and regenerate "
        "CHECKSUMS.txt and SOURCE.json in the same change",
        file=sys.stderr,
    )
    sys.exit(1)
PY
}

# One field of SOURCE.json: `upstream.<key>`.
source_field() {
  python3 - "$CONTRACTS_DIR/SOURCE.json" "$1" <<'PY'
import json
import pathlib
import sys

provenance = json.loads(pathlib.Path(sys.argv[1]).read_text())
print(provenance["upstream"][sys.argv[2]])
PY
}

verify_against_source() {
  local checkout="$1" pinned_commit head_commit
  [ -d "$checkout" ] || fail "fermix checkout not found at $checkout"
  compare_upstream "$checkout"
  pinned_commit="$(source_field commit)" || fail "SOURCE.json has no upstream commit"
  head_commit="$(git -C "$checkout" rev-parse HEAD)" ||
    fail "$checkout is not a git checkout, so the pinned commit cannot be compared with it"
  if [ "$pinned_commit" != "$head_commit" ]; then
    echo "verify_protocol_contract: note: the checkout is at ${head_commit:0:12}," \
      "the pin records ${pinned_commit:0:12}; the bytes match, so update the pin" \
      "when you next re-vendor"
  fi
  echo "vendored wire contract: byte-identical to $checkout"
}

# git fetch <refspec> from the engine repository into the repository at $1, with
# the options that follow; a stalled transfer is abandoned.
engine_fetch() {
  local dest="$1" refspec="$2"
  shift 2
  git -C "$dest" -c "http.lowSpeedLimit=$FETCH_LOW_SPEED_BYTES" \
    -c "http.lowSpeedTime=$FETCH_LOW_SPEED_SECONDS" \
    fetch --quiet "$@" "$ENGINE_URL" "$refspec"
}

# The engine branch SOURCE.json names carries the pinned commit. GitHub serves
# any commit by its id, one that only a pull request or a deleted branch holds
# included, so fetching the commit alone proves nothing about its publication.
# Only the branch's commits are fetched, no tree and no file.
check_pinned_on_branch() {
  local dest="$1" commit="$2" branch="$3"
  git init --quiet "$dest" || fail "could not create a scratch repository in $dest"
  engine_fetch "$dest" "refs/heads/$branch" --filter=tree:0 ||
    fail "could not fetch the $branch branch from github.com/$ENGINE_REPOSITORY"
  git -C "$dest" merge-base --is-ancestor "$commit" FETCH_HEAD ||
    fail "the engine's $branch branch does not carry $commit; pin a commit the branch holds"
  echo "$ENGINE_REPOSITORY $branch carries ${commit:0:12}"
}

# Fetch only the pinned commit's contract directory into $1.
fetch_pinned() {
  local dest="$1" commit="$2" fetched
  git init --quiet "$dest" || fail "could not create a scratch repository in $dest"
  git -C "$dest" sparse-checkout set --no-cone "/$ENGINE_CONTRACT_DIRECTORY/" ||
    fail "could not limit the scratch checkout to $ENGINE_CONTRACT_DIRECTORY"
  engine_fetch "$dest" "$commit" --depth 1 --filter=blob:none ||
    fail "could not fetch $commit from github.com/$ENGINE_REPOSITORY"
  git -C "$dest" -c advice.detachedHead=false checkout --quiet FETCH_HEAD ||
    fail "could not check out $commit"
  fetched="$(git -C "$dest" rev-parse HEAD)" || fail "the scratch checkout has no HEAD"
  [ "$fetched" = "$commit" ] || fail "fetched $fetched where the pin records $commit"
  echo "fetched $ENGINE_REPOSITORY at ${commit:0:12}"
}

# The body is a subshell, so its EXIT trap removes the scratch clones on every
# exit path, failures included, while $scratch is still in scope.
verify_against_pinned() (
  local scratch commit branch
  scratch="$(mktemp -d)" || fail "could not create a scratch directory"
  trap 'rm -rf -- "$scratch"' EXIT
  commit="$(source_field commit)" || fail "SOURCE.json has no upstream commit"
  branch="$(source_field branch)" || fail "SOURCE.json has no upstream branch"
  check_pinned_on_branch "$scratch/history" "$commit" "$branch"
  fetch_pinned "$scratch/fermix" "$commit"
  compare_upstream "$scratch/fermix"
  echo "vendored wire contract: byte-identical to the pinned engine commit"
)

main() {
  local mode="local" checkout=""
  case "${1:-}" in
    "")
      [ $# -le 1 ] || usage
      ;;
    --source)
      [ $# -eq 2 ] || fail "--source needs exactly one path to a fermix checkout"
      mode="source"
      checkout="$2"
      ;;
    --pinned)
      [ $# -eq 1 ] || usage
      mode="pinned"
      ;;
    *) usage ;;
  esac
  [ -d "$CONTRACTS_DIR" ] || fail "vendored contract tree is missing at $CONTRACTS_DIR"
  [ -f "$CONTRACTS_DIR/CHECKSUMS.txt" ] || fail "contracts/CHECKSUMS.txt is missing"
  [ -f "$CONTRACTS_DIR/SOURCE.json" ] || fail "contracts/SOURCE.json is missing"

  check_checksums
  check_listing
  check_provenance
  echo "vendored wire contract: checksums and provenance OK"

  case "$mode" in
    source) verify_against_source "$checkout" ;;
    pinned) verify_against_pinned ;;
    local) ;;
  esac
}

main "$@"
