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
#   8. no camera is required, as the pasted link is the other way in (section 13.3, step 2): no feature
#      the manifest declares or CAMERA implies names a camera as required, and camera.any is declared
#      not required, so that Play offers the app to a phone without one
#   9. the components the merged manifest exports are exactly policy/exported.txt's, each with the
#      permission it is behind and each of its filters whole, its actions, categories and data and the
#      filter's own attributes (AGENTS.md): a library that exports one fails it as surely as the app's
#      own manifest, and so does a category, a scheme or a type added to an exported filter
#  10. nothing of the debug app's demo (README, "The demo"): no dex entry holds a class of its package,
#      io.tezra.fermix.demo, by the type descriptor a dex carries for every class it defines or names, no
#      attribute of the merged manifest names one, the application's name included, and no resource is
#      named as the debug source set names the demo's, demo_ and its name (the launcher entry's label and
#      the copied link's label among them); an APK with no dex to search is refused too. R8 renames a class
#      that no rule keeps, so the dex shows a class of the demo only by a name a rule kept: demo-daemon's
#      consumer rules keep its own, and the app's (app/proguard-rules.pro) any class of the package that
#      reaches a release from elsewhere. Given R8's mapping of the release, as the policy job and verify
#      give it, the check holds whatever name R8 gave a class: the mapping must be the APK's own, its
#      pg_map_id the one R8 marked the APK's dex with, and must map no class of the package, kept, renamed
#      or removed
#
# It reads the APK with aapt2, unzip and jq, and prints every check that fails, with what it expected
# and what it found, then exits 1 with the number of checks that failed. Each check prints what it finds
# wrong on stdout, which main collects. A tool that is missing or fails, grep included, and a reference
# that is missing or empty end the script at once with status 2.
#
#   check_release_policy.sh <release.apk> [<R8 mapping.txt of it>]
#
# aapt2 is build tools BUILD_TOOLS_VERSION's: the Android Gradle plugin's default build-tools revision,
# which the build installs, noted beside agp in gradle/libs.versions.toml. The plugin itself links
# resources with an aapt2 of its own from Maven; any aapt2 reads the APK alike. AAPT2 names another.
set -euo pipefail
shopt -s inherit_errexit

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PERMISSIONS_FILE="$ROOT_DIR/policy/permissions.txt"
EXPORTED_FILE="$ROOT_DIR/policy/exported.txt"
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
# The package of the debug app's demo, which no release holds a class or a component of (check 10): its
# classes' type descriptors in a dex, and its names as quoted values in aapt2's manifest tree, as extended
# regular expressions.
DEMO_PACKAGE="io.tezra.fermix.demo"
DEMO_CLASS='Lio/tezra/fermix/demo/[A-Za-z0-9_$/-]*;'
DEMO_NAME='"io\.tezra\.fermix\.demo(\.[^"]*)?"'
# The demo's resources as aapt2's resource dump names them, type/name: the debug source set names each demo_.
DEMO_RESOURCE='[a-z]+/demo_[A-Za-z0-9_.]*'
# A class of the demo as R8's mapping maps it: its class line, "<original name> -> <name in the dex>:", the
# original under the demo's package.
DEMO_MAPPED='^io\.tezra\.fermix\.demo\.[^ ]+ -> [^ ]+:$'
# The scratch directory main makes, which the EXIT trap removes.
work=""

usage() {
  echo "usage: check_release_policy.sh <release.apk> [<R8 mapping.txt of it>]" >&2
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

# The components the manifest tree on stdin exports, sorted, one per line: "<kind> <name> <permission>
# <filter>...", "-" for no permission or no filter. A filter is each attribute of its elements, in order and
# comma-separated: "action=<name>" and "category=<name>", "data.<attribute>=<value>" for each of a data
# element's (a scheme, a host, a path, a type), and "filter.<attribute>=<value>" for the filter's own (a
# priority); "(empty)" for a filter with none. A component is exported by its own exported attribute: from
# targetSdkVersion 31 one with a filter must say whether it is, and one without is not. Any value but false
# counts, a resource's among them, so a component whose value this cannot read is listed, never passed over.
# An element's attributes sit one step under it; a filter ends at the next element as far out as it is.
exported_components() {
  awk '
    function end_filter() {
      if (filter) filters = filters (filters == "" ? "" : " ") (items == "" ? "(empty)" : items)
      filter = 0
    }
    function flush() {
      end_filter()
      if (kind != "" && exported != "" && exported != "false") {
        print kind, name, (permission == "" ? "-" : permission), (filters == "" ? "-" : filters)
      }
      kind = ""
    }
    function quoted() { match($0, /="[^"]*"/); return substr($0, RSTART + 2, RLENGTH - 3) }
    # The attribute this line sets, as "<name>=<value>", its namespace and resource id dropped.
    function attribute(  key, value) {
      key = $2; sub(/\(0x.*$/, "", key); sub(/^.*:/, "", key)
      value = $2; sub(/^[^=]*=/, "", value)
      return key "=" ($0 ~ /="/ ? quoted() : value)
    }
    function item(  set) {
      set = attribute()
      if (element == "intent-filter") return "filter." set
      if (element ~ /^(action|category)$/ && set ~ /^name=/) return element substr(set, 5)
      return element "." set
    }
    { indent = match($0, /[^ ]/) - 1 }
    $1 == "E:" && kind != "" && indent <= depth { flush() }
    $1 == "E:" && $2 ~ /^(activity|activity-alias|service|receiver|provider)$/ {
      kind = $2; depth = indent; name = ""; exported = ""; permission = ""; filters = ""; filter = 0; next
    }
    kind == "" { next }
    $1 == "E:" && filter && indent <= at { end_filter() }
    $1 == "E:" && $2 == "intent-filter" { filter = 1; at = indent; items = "" }
    $1 == "E:" { element = $2; element_at = indent; next }
    $1 == "A:" && indent == depth + 2 && $2 ~ /android:name\(/ { name = quoted() }
    $1 == "A:" && indent == depth + 2 && $2 ~ /android:permission\(/ { permission = quoted() }
    $1 == "A:" && indent == depth + 2 && $2 ~ /android:exported\(/ { sub(/.*=/, "", $2); exported = $2 }
    $1 == "A:" && filter && indent == element_at + 2 { items = items (items == "" ? "" : ",") item() }
    END { flush() }' | LC_ALL=C sort
}

# 9. The exported components, each with its permission and its filters whole, against the policy file,
# whose lines are components, apart from blank lines and # comments.
check_exported() {
  local manifest=$1 found listed difference status=0
  found="$(exported_components <<<"$manifest")"
  listed="$(sed -e '/^[[:space:]]*#/d' -e '/^[[:space:]]*$/d' "$EXPORTED_FILE" | LC_ALL=C sort)"
  difference="$(diff <(lines "$listed") <(lines "$found"))" || status=$?
  [ "$status" -le 1 ] || fatal "diff of the exported components failed with status $status"
  [ "$status" -eq 1 ] || return 0
  violation "exported components: expected exactly those $EXPORTED_FILE lists, found others" \
    "(< listed only, > exported only):"
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

# 8. No camera required: badging lists a required feature as uses-feature, and one a permission implies
# as uses-implied-feature, both of which a store filters phones by; a feature declared not required is
# uses-feature-not-required.
check_features() {
  local badging=$1 required
  required="$(sed -n "s/^ *uses-\(implied-\)\{0,1\}feature: name='\(android\.hardware\.camera[^']*\)'.*/\2/p" <<<"$badging" |
    LC_ALL=C sort -u)"
  if [ -n "$required" ]; then
    violation "camera: expected none required, found $(paste -sd ' ' - <<<"$required") required"
  fi
  if ! matches "^ *uses-feature-not-required: name='android\.hardware\.camera\.any'$" <<<"$badging"; then
    violation "camera: expected android.hardware.camera.any declared not required, found no such declaration"
  fi
}

# 10. Nothing of the demo: its classes by their descriptors in every dex, "L<package with slashes>/<name>;",
# its names in the manifest tree, each a quoted attribute value of the package or under it, and its resources
# by their names in the resource dump; and, given R8's [mapping], its classes there (check_demo_mapping).
check_demo() {
  local apk=$1 manifest=$2 resources=$3 work=$4 mapping=$5 classes named held status=0
  unzip -q "$apk" 'classes*.dex' -d "$work/dex" || status=$?
  [ "$status" -eq 0 ] || [ "$status" -eq 11 ] || fatal "unzip could not unpack the dex entries of $apk"
  if [ "$status" -eq 11 ]; then
    violation "demo: expected the app's code in classes*.dex to search for $DEMO_PACKAGE, found no dex"
    return 0
  fi
  status=0
  classes="$(LC_ALL=C grep -a -r -h -o -E "$DEMO_CLASS" "$work/dex" | LC_ALL=C sort -u)" || status=$?
  [ "$status" -le 1 ] || fatal "grep for the demo's classes failed with status $status"
  status=0
  named="$(LC_ALL=C grep -o -E "$DEMO_NAME" <<<"$manifest" | tr -d '"' | LC_ALL=C sort -u)" || status=$?
  [ "$status" -le 1 ] || fatal "grep for the demo's components failed with status $status"
  status=0
  held="$(LC_ALL=C grep -o -E "$DEMO_RESOURCE" <<<"$resources" | LC_ALL=C sort -u)" || status=$?
  [ "$status" -le 1 ] || fatal "grep for the demo's resources failed with status $status"
  if [ -n "$classes" ]; then
    violation "demo: expected no class of $DEMO_PACKAGE in any dex, found:"
    echo "$classes"
  fi
  if [ -n "$named" ]; then
    violation "demo: expected no name of $DEMO_PACKAGE in the merged manifest, found:"
    echo "$named"
  fi
  if [ -n "$held" ]; then
    violation "demo: expected no resource named as the demo's, demo_, found:"
    echo "$held"
  fi
  [ -z "$mapping" ] || check_demo_mapping "$work/dex" "$mapping"
}

# 10, given R8's [mapping]: the mapping is the one R8 wrote as it made the dex in [dex], whose marker names the
# mapping's pg_map_id, and it maps no class of the demo, whatever name R8 gave the class or whether it removed it.
# A mapping that is not R8's or not this APK's ends the script: it would vouch for another build.
check_demo_mapping() {
  local dex=$1 mapping=$2 id marked mapped status=0
  id="$(sed -n -e 's/^# pg_map_id: \([0-9a-f][0-9a-f]*\)$/\1/p' -e '/^[^#]/q' "$mapping")"
  [ -n "$id" ] || fatal "$mapping names no pg_map_id: it is not R8's mapping"
  marked="$(LC_ALL=C grep -a -r -h -o -E '"pg-map-id":"[0-9a-f]+"' "$dex" | LC_ALL=C sort -u)" || status=$?
  [ "$status" -le 1 ] || fatal "grep for R8's marker in the dex failed with status $status"
  [ "$marked" = "\"pg-map-id\":\"$id\"" ] ||
    fatal "$mapping is not the mapping of the APK's dex: its pg_map_id is $id, the dex's R8 marker names ${marked:-none}"
  status=0
  mapped="$(LC_ALL=C grep -E "$DEMO_MAPPED" "$mapping" | LC_ALL=C sort -u)" || status=$?
  [ "$status" -le 1 ] || fatal "grep for the demo's classes in $mapping failed with status $status"
  if [ -n "$mapped" ]; then
    violation "demo: expected R8's mapping to map no class of $DEMO_PACKAGE, found:"
    echo "$mapped"
  fi
}

# Every check, each printing what it finds wrong. The resource dump is read whole into a variable: a
# reader that stopped early would end aapt2 with SIGPIPE, which pipefail turns into a silent exit.
run_checks() {
  local apk=$1 work=$2 mapping=$3 badging manifest resources
  badging="$(aapt2_dump badging "$apk")"
  manifest="$(aapt2_dump xmltree --file AndroidManifest.xml "$apk")"
  resources="$(aapt2_dump resources "$apk")"
  check_manifest "$badging" "$manifest"
  check_features "$badging"
  check_permissions "$apk"
  check_exported "$manifest"
  check_backup "$apk" "$manifest" "$resources"
  check_contents "$apk" "$work"
  check_demo "$apk" "$manifest" "$resources" "$work" "$mapping"
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
  [ $# -eq 1 ] || [ $# -eq 2 ] || usage
  local apk=$1 mapping=${2:-} report failed
  [ -f "$apk" ] || fatal "no APK at $apk"
  [ $# -eq 1 ] || [ -s "$mapping" ] || fatal "no R8 mapping at $mapping"
  [ -f "$PERMISSIONS_FILE" ] || fatal "no permissions file at $PERMISSIONS_FILE"
  [ -f "$EXPORTED_FILE" ] || fatal "no exported components file at $EXPORTED_FILE"
  [ -n "${AAPT2:-}" ] || [ -n "${ANDROID_HOME:-}" ] || fatal "ANDROID_HOME names no SDK, and AAPT2 no aapt2"
  AAPT2="${AAPT2:-$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION/aapt2}"
  readonly AAPT2
  require_tools
  work="$(mktemp -d)" || fatal "could not make a scratch directory"
  trap 'rm -rf -- "${work:?}"' EXIT
  report="$(run_checks "$apk" "$work" "$mapping")"
  if [ -n "$report" ]; then
    failed="$(awk '/^check_release_policy: / { n++ } END { print n + 0 }' <<<"$report")"
    echo "$report" >&2
    echo "check_release_policy: $failed checks failed for $apk" >&2
    exit 1
  fi
  echo "check_release_policy: $apk is as the design says"
  [ -z "$mapping" ] || echo "check_release_policy: $mapping is R8's mapping of it and maps no class of $DEMO_PACKAGE"
  echo "check_release_policy: test material searched for: $(searched)"
}

main "$@"
