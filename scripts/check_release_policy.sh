#!/usr/bin/env bash
#
# Check a release APK against what the design says it is (MILESTONE_51_ANDROID_CI_CD.md section 3,
# the policy job):
#
#   1. minSdkVersion 35 and targetSdkVersion 36 (design D18 and section 12.1)
#   2. not debuggable
#   3. no cleartext traffic: usesCleartextTraffic is false, and no network security config, which
#      would override it, is declared (section 12.3)
#   4. no PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY, the resizability opt-out (section 13.11)
#   5. the requested permissions are exactly policy/permissions.txt's
#   6. nothing leaves the phone by backup or device transfer (section 6.4): allowBackup is false, and
#      every configuration of the data extraction rules, a qualified override such as res/xml-v36/
#      included, excludes every domain under cloud-backup and under device-transfer, and every
#      configuration of the older platforms' full-backup rules every domain too. A rule counts only
#      where a phone reads it (AOSP's FullBackup.java), as a child of its section: a section left
#      empty beside rules outside it excludes nothing, and anything but a rule in a section is refused
#   7. no test key, vector key or fixture is inside: no entry has the name of a file of the vendored
#      contracts/mobile, lies in a fixtures/ directory, or is a key store, key or fixture by its
#      extension; no entry holds the bytes of a vendored file, whatever its name; and no entry holds a
#      key of the vendored vectors, in hex of either case or in base64, as a constant compiled in would
#
# It reads the APK with aapt2, unzip and jq, and prints every check that fails, with what it expected
# and what it found, then exits 1 with the number of checks that failed. Each check prints what it finds
# wrong on stdout, which main collects. A tool that is missing or fails, grep included, and a reference
# that is missing or empty end the script at once with status 2.
#
#   check_release_policy.sh <release.apk>
#
# aapt2 is build tools BUILD_TOOLS_VERSION's: the Android Gradle plugin's default build-tools revision,
# which the build installs, noted beside agp in gradle/libs.versions.toml. The plugin itself links
# resources with an aapt2 of its own from Maven; any aapt2 reads the APK alike. AAPT2 names another.
set -euo pipefail
shopt -s inherit_errexit

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PERMISSIONS_FILE="$ROOT_DIR/policy/permissions.txt"
VENDORED_DIR="$ROOT_DIR/contracts/mobile"
BUILD_TOOLS_VERSION="36.0.0"
# The tools the checks run besides aapt2.
TOOLS="awk base64 basename cut diff find grep jq mktemp paste sed sha256sum sort tr unzip"
# The domains a backup rules file can name; each is excluded whole, path ".", in every section.
DOMAINS="root file database sharedpref external device_root device_file device_database device_sharedpref"
# The extensions of a key store, a key or a fixture, which no entry of a release has.
TEST_EXTENSIONS="jks keystore p12 pk8 pem jsonl"
# The vendored vectors, whose keys no entry of a release holds.
VECTOR_FILES="noise_vectors.json push_vectors.json"
# The scratch directory main makes, which the EXIT trap removes.
work=""

usage() {
  echo "usage: check_release_policy.sh <release.apk>" >&2
  exit 2
}

fatal() {
  echo "check_release_policy: $*" >&2
  exit 2
}

violation() {
  echo "check_release_policy: $*"
}

# grep -q with its outcomes kept apart: 0 is a match, 1 is none, and anything else is a failure that
# ends the script. Inside an if or before ||, grep's own error would otherwise read as "no match".
matches() {
  local status=0
  grep -q "$@" || status=$?
  [ "$status" -le 1 ] || fatal "grep $* failed with status $status"
  return "$status"
}

# A check of [name]: [expected], against what was [found], which may be nothing.
expect() {
  [ "$3" = "$2" ] || violation "$1: expected $2, found ${3:-none}"
}

# The value of each android:[name] attribute of the manifest tree on stdin, one per line.
attribute() {
  sed -n "s|^ *A: http://schemas.android.com/apk/res/android:$1(0x[0-9a-f]*)=\(.*\)$|\1|p"
}

# What aapt2 dump [arguments...] prints, read whole; a failure of aapt2 ends the script.
aapt2_dump() {
  local output
  output="$("$AAPT2" dump "$@" </dev/null)" || fatal "aapt2 dump $* failed"
  echo "$output"
}

# 1 to 4: the manifest's levels and flags.
check_manifest() {
  local badging=$1 manifest=$2 min target cleartext
  min="$(sed -n "s/^minSdkVersion:'\(.*\)'$/\1/p" <<<"$badging")"
  target="$(sed -n "s/^targetSdkVersion:'\(.*\)'$/\1/p" <<<"$badging")"
  cleartext="$(attribute usesCleartextTraffic <<<"$manifest")"
  expect minSdkVersion 35 "$min"
  expect targetSdkVersion 36 "$target"
  if matches "^application-debuggable" <<<"$badging"; then
    violation "debuggable: expected not, found application-debuggable"
  fi
  expect usesCleartextTraffic false "$cleartext"
  if matches ":networkSecurityConfig(" <<<"$manifest"; then
    violation "networkSecurityConfig, which overrides usesCleartextTraffic: expected none," \
      "found $(attribute networkSecurityConfig <<<"$manifest")"
  fi
  if matches "PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY" <<<"$manifest"; then
    violation "resizability: expected no opt-out, found PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY"
  fi
}

# [text] as lines, and nothing at all when it is empty, where echo would write one blank line.
lines() {
  [ -z "$1" ] || printf '%s\n' "$1"
}

# 5. Requested permissions, uses-permission and uses-permission-sdk-23, against the policy file, whose
# lines are permissions, apart from blank lines and # comments.
check_permissions() {
  local apk=$1 dump requested listed difference status=0
  dump="$(aapt2_dump permissions "$apk")"
  requested="$(sed -n "s/^uses-permission\(-sdk-23\)\{0,1\}: name='\([^']*\)'.*/\2/p" <<<"$dump" | LC_ALL=C sort -u)"
  listed="$(sed -e '/^[[:space:]]*#/d' -e '/^[[:space:]]*$/d' "$PERMISSIONS_FILE" | LC_ALL=C sort -u)"
  difference="$(diff <(lines "$listed") <(lines "$requested"))" || status=$?
  [ "$status" -le 1 ] || fatal "diff of the permissions failed with status $status"
  [ "$status" -eq 1 ] || return 0
  violation "permissions: expected exactly those $PERMISSIONS_FILE lists, found others" \
    "(< listed only, > requested only):"
  echo "$difference"
}

# "<config> <path>" for every configuration of the resource [id] in the resource dump on stdin, and
# "<config> -" for one that is not an XML file. It reads its input to the end.
rules_files() {
  awk -v id="$1" '
    $1 == "resource" || $1 == "type" { inside = ($1 == "resource" && $2 == id); next }
    inside && $2 == "(file)" && $NF == "type=XML" { print $1, $3; next }
    inside { print $1, "-" }'
}

# The rules of the rules file at [path] in [apk], read off aapt2's tree of it by each element's depth,
# as a phone reads them: an element at depth [section_depth], the root being 1, is a section, and an
# exclude that is a child of a section is printed as "<section> <domain> <path>". A phone skips a rule
# outside every section, and stops reading a section, or fails the file, at anything inside it but a
# rule, so each such element is a line "! <what>", and so is a root other than [root].
excludes() {
  local apk=$1 path=$2 root=$3 section_depth=$4 tree
  tree="$(aapt2_dump xmltree --file "$path" "$apk")"
  RULES_ROOT="$root" SECTION_DEPTH="$section_depth" awk '
    BEGIN { sections = ENVIRON["SECTION_DEPTH"] + 0 }
    function flush() { if (rule) print section, domain, path; rule = 0 }
    # aapt2 indents an element under its parent, so the elements still open are those indented less.
    function enter(indent) {
      while (depth > 0 && indents[depth] >= indent) depth--
      indents[++depth] = indent
    }
    $1 == "E:" { flush(); enter(match($0, /[^ ]/) - 1) }
    $1 == "E:" && depth == 1 && $2 != ENVIRON["RULES_ROOT"] { print "! the root is " $2 ", not " ENVIRON["RULES_ROOT"] }
    $1 == "E:" && depth <= sections { section = (depth == sections) ? $2 : ""; next }
    $1 == "E:" && (section == "" || ($2 == "include" && depth == sections + 1)) { next }
    $1 == "E:" && $2 == "exclude" && depth == sections + 1 { rule = 1; domain = ""; path = ""; next }
    $1 == "E:" { print "! " $2 " " $3 " in " section ", which holds only include and exclude rules"; next }
    rule && $1 == "A:" {
      split($2, pair, "\"")
      if (pair[1] == "domain=") domain = pair[2]
      if (pair[1] == "path=") path = pair[2]
    }
    END { flush() }' <<<"$tree"
}

# The rules file at [path], named [label] in what it prints, has the root [root], and excludes every
# domain under each section of [sections], at [section_depth].
check_rules_file() {
  local apk=$1 label=$2 path=$3 root=$4 section_depth=$5 sections=$6 listed faults fault section domain
  [ "$path" != "-" ] || { violation "$label: expected an XML file, found another value"; return 0; }
  listed="$(excludes "$apk" "$path" "$root" "$section_depth")"
  faults="$(sed -n 's/^! //p' <<<"$listed")"
  while IFS= read -r fault; do
    violation "$label: $fault"
  done < <(lines "$faults")
  for section in $sections; do
    for domain in $DOMAINS; do
      matches -x "$section $domain \." <<<"$listed" || violation "$label: $section does not exclude the $domain domain"
    done
  done
}

# Every configuration of the rules file that the application attribute [name] points at, each override
# included, since a phone reads the one that fits it; [root], [section_depth] and [sections] are its shape.
check_rules() {
  local apk=$1 manifest=$2 resources=$3 name=$4 root=$5 section_depth=$6 sections=$7 id files config path
  id="$(attribute "$name" <<<"$manifest" | sed -n 's/^@\(0x[0-9a-f]*\)$/\1/p')"
  [ -n "$id" ] || { violation "$name: expected a rules file, found none"; return 0; }
  files="$(rules_files "$id" <<<"$resources")"
  [ -n "$files" ] || { violation "$name: expected a file for resource $id, found none"; return 0; }
  while read -r config path; do
    check_rules_file "$apk" "$name $config $path" "$path" "$root" "$section_depth" "$sections"
  done <<<"$files"
}

# 6. Backup and device transfer: the data extraction rules have their sections under the root, and the
# full-backup rules are one section, the root itself.
check_backup() {
  local apk=$1 manifest=$2 resources=$3 allow
  allow="$(attribute allowBackup <<<"$manifest")"
  expect allowBackup false "$allow"
  check_rules "$apk" "$manifest" "$resources" dataExtractionRules data-extraction-rules 2 "cloud-backup device-transfer"
  check_rules "$apk" "$manifest" "$resources" fullBackupContent full-backup-content 1 full-backup-content
}

# The names of the vendored files; there is at least one.
vendored_names() {
  local names
  names="$(find "$VENDORED_DIR" -type f -exec basename {} \;)" || fatal "could not list $VENDORED_DIR"
  [ -n "$names" ] || fatal "$VENDORED_DIR holds no file to search for"
  LC_ALL=C sort -u <<<"$names"
}

# Every key of the vendored vectors, once, as "<file>:<field> <hex>", the field being the first that holds
# it: the 32-byte hex values of each private or public key, psk, salt, secret and key field. There is at
# least one.
vector_keys() {
  local file found keys=""
  for file in $VECTOR_FILES; do
    found="$(jq -r --arg file "$file" '
      paths(type == "string") as $path
      | select($path[-1] | tostring | test("(^|_)(private|public|psk|salt|secret|key)$"))
      | getpath($path) as $value
      | select($value | test("^[0-9a-f]{64}$"))
      | "\($file):\($path | map(tostring) | join(".")) \($value)"' "$VENDORED_DIR/$file")" ||
      fatal "jq could not read $VENDORED_DIR/$file"
    keys+="$found"$'\n'
  done
  keys="$(awk 'NF && !seen[$2]++' <<<"$keys")"
  [ -n "$keys" ] || fatal "the vendored vectors $VECTOR_FILES hold no key to search for"
  echo "$keys"
}

# What check 7 looks for, as its violations and the pass line say.
searched() {
  echo "entries named as a file of contracts/mobile ($(vendored_names | paste -sd ' ' -)), entries under" \
    "a fixtures/ directory, the extensions $TEST_EXTENSIONS, entries holding the bytes of any non-empty" \
    "file of contracts/mobile, by sha256, and entries holding a key of the vectors, in hex of either case" \
    "or in base64 ($(vector_keys | cut -d ' ' -f 1 | paste -sd ' ' -))"
}

# Every entry named as test material.
named_like_test_material() {
  local apk=$1 entries vendored extensions
  entries="$(unzip -Z1 "$apk")" || fatal "unzip could not list $apk"
  vendored="$(vendored_names)"
  extensions="[.]($(tr ' ' '|' <<<"$TEST_EXTENSIONS"))\$"
  VENDORED="$vendored" EXTENSIONS="$extensions" awk '
    BEGIN { split(ENVIRON["VENDORED"], names, "\n"); for (i in names) wanted[names[i]] = 1 }
    { base = $0; sub(/.*\//, "", base) }
    (base in wanted) || /(^|\/)fixtures\// || $0 ~ ENVIRON["EXTENSIONS"] { print }' <<<"$entries"
}

# "<entry> is <vendored file>" for every entry unpacked under [unpacked] whose bytes are a vendored
# file's, whatever its name. Empty files are left out on both sides: they carry nothing, and an APK has
# empty entries of its own.
copies_of_vendored() {
  local unpacked=$1 vendored inside
  vendored="$(cd "$ROOT_DIR" && find contracts/mobile -type f -size +0 -exec sha256sum {} +)" ||
    fatal "could not hash the files of $VENDORED_DIR"
  [ -n "$vendored" ] || fatal "$VENDORED_DIR holds no non-empty file to search for"
  inside="$(cd "$unpacked" && find . -type f -size +0 -exec sha256sum {} +)" || fatal "could not hash $unpacked"
  awk '!NF { next }
    NR == FNR { copy[substr($0, 1, 64)] = substr($0, 67); next }
    (substr($0, 1, 64) in copy) { print substr($0, 69) " is " copy[substr($0, 1, 64)] }' \
    <(echo "$vendored") <(echo "$inside")
}

# The standard base64 of the bytes the hex [hex] spells, each pair of digits one byte.
base64_of() {
  local hex=$1 escaped="" i
  for ((i = 0; i < ${#hex}; i += 2)); do
    escaped+="\\x${hex:i:2}"
  done
  printf '%b' "$escaped" | base64 -w 0
}

# Each key of the "<field> <hex>" lines on stdin as "<form> <field>", once per form: its hex in lower case
# and in upper case, and its base64.
key_forms() {
  local field hex
  while read -r field hex; do
    printf '%s %s\n' "$hex" "$field" "${hex^^}" "$field" "$(base64_of "$hex")" "$field"
  done | awk '!seen[$1]++'
}

# "<entry> holds <field>" for every entry unpacked under [unpacked] that holds a vendored vector's key in
# any of its forms, as a key compiled in, as a constant or a resource, would.
keys_inside() {
  local unpacked=$1 forms found status=0
  forms="$(vector_keys | key_forms)"
  found="$(cut -d ' ' -f 1 <<<"$forms" | grep -a -r -o -F -f - "$unpacked")" || status=$?
  [ "$status" -le 1 ] || fatal "grep for the vectors' keys failed with status $status"
  [ "$status" -eq 0 ] || return 0
  # grep prints "<path>:<form>", and a form holds no colon, so the path ends at the last one.
  FORMS="$forms" PREFIX="$unpacked/" awk '
    BEGIN {
      n = split(ENVIRON["FORMS"], rows, "\n")
      for (i = 1; i <= n; i++) { split(rows[i], f, " "); field[f[1]] = f[2] }
      skip = length(ENVIRON["PREFIX"])
    }
    { at = match($0, /:[^:]*$/); entry = substr($0, skip + 1, at - skip - 1); key = field[substr($0, at + 1)] }
    !seen[entry, key]++ { print entry " holds " key }' <<<"$found"
}

# 7. Nothing of the tests' inside, by name, by content or by key.
check_contents() {
  local apk=$1 work=$2 named copied keys
  [ -d "$VENDORED_DIR" ] || fatal "no vendored contract at $VENDORED_DIR"
  unzip -q "$apk" -d "$work/apk" || fatal "unzip could not unpack $apk"
  named="$(named_like_test_material "$apk")"
  copied="$(copies_of_vendored "$work/apk")"
  keys="$(keys_inside "$work/apk")"
  if [ -n "$named" ]; then
    violation "test material: expected none, found entries by name; searched for $(searched):"
    echo "$named"
  fi
  if [ -n "$copied" ]; then
    violation "test material: expected none, found entries holding a vendored file's bytes:"
    echo "$copied"
  fi
  if [ -n "$keys" ]; then
    violation "test material: expected none, found entries holding a key of the vendored vectors:"
    echo "$keys"
  fi
}

# Every check, each printing what it finds wrong. The resource dump is read whole into a variable: a
# reader that stopped early would end aapt2 with SIGPIPE, which pipefail turns into a silent exit.
run_checks() {
  local apk=$1 work=$2 badging manifest resources
  badging="$(aapt2_dump badging "$apk")"
  manifest="$(aapt2_dump xmltree --file AndroidManifest.xml "$apk")"
  resources="$(aapt2_dump resources "$apk")"
  check_manifest "$badging" "$manifest"
  check_permissions "$apk"
  check_backup "$apk" "$manifest" "$resources"
  check_contents "$apk" "$work"
}

# Every tool the checks run is there, aapt2 included, before any check starts.
require_tools() {
  local tool
  for tool in $TOOLS; do
    command -v "$tool" >/dev/null || fatal "no $tool on the PATH"
  done
  [ -x "$AAPT2" ] || fatal "no aapt2 at $AAPT2"
}

main() {
  [ $# -eq 1 ] || usage
  local apk=$1 report failed
  [ -f "$apk" ] || fatal "no APK at $apk"
  [ -f "$PERMISSIONS_FILE" ] || fatal "no permissions file at $PERMISSIONS_FILE"
  [ -n "${AAPT2:-}" ] || [ -n "${ANDROID_HOME:-}" ] || fatal "ANDROID_HOME names no SDK, and AAPT2 no aapt2"
  AAPT2="${AAPT2:-$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION/aapt2}"
  readonly AAPT2
  require_tools
  work="$(mktemp -d)" || fatal "could not make a scratch directory"
  trap 'rm -rf -- "${work:?}"' EXIT
  report="$(run_checks "$apk" "$work")"
  if [ -n "$report" ]; then
    failed="$(awk '/^check_release_policy: / { n++ } END { print n + 0 }' <<<"$report")"
    echo "$report" >&2
    echo "check_release_policy: $failed checks failed for $apk" >&2
    exit 1
  fi
  echo "check_release_policy: $apk is as the design says"
  echo "check_release_policy: test material searched for: $(searched)"
}

main "$@"
