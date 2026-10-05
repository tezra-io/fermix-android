#!/usr/bin/env python3
"""promote.yml's play job (MILESTONE_51_ANDROID_CI_CD.md C10, section 4.3): the published release's app bundle
to Play's internal testing track, which only invited testers install (design D16), through the Google Play
Developer API with the service account the release environment holds in PLAY_SERVICE_ACCOUNT_JSON. A short
script and no Fastlane (section 8): the account's OAuth token, then one edit of io.tezra.fermix,

  1. POST   edits                          opens the edit
  2. GET    edits/{id}/bundles             the bundles Play already has
  3. POST   upload .../edits/{id}/bundles  the bundle, unless Play has this versionCode with this sha256 already,
                                           as it does after the first release, which is put on the track by hand
                                           (docs/RELEASING.md); this versionCode with another sha256 is refused
  4. PUT    edits/{id}/tracks/internal     a release named X.Y.Z of that versionCode, completed, so testers get it
  5. POST   edits/{id}:commit              Play takes it

and the bundle is never rebuilt or signed again (C7): it is the release's file, which check_release.sh has
verified. Moving a build from internal testing to any other track is the owner's act in the Play Console,
never this script's.

--dry-run does everything but talk to Google: it reads the bundle, version.properties and the service account,
signs the token request with the account's key, and prints what it would send. It shows that the key is whole
and signs; only a call to Google shows that Google takes it and that the account may release the app. Nothing
it prints is a credential.

  PLAY_SERVICE_ACCOUNT_JSON="$(cat <the account's key file>)" play_upload.py <vX.Y.Z> <aab> [--dry-run]

PLAY_SERVICE_ACCOUNT_JSON holds the key file's text, as the release environment's secret does, never its path.

Run from the tag's checkout, for version.properties. Exits 1 on a refusal, Play's included, 2 on a malformed
argument or a missing tool.
"""
import base64
import hashlib
import json
import os
import pathlib
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

PACKAGE = "io.tezra.fermix"
TRACK = "internal"
API = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PACKAGE}"
UPLOAD = f"https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/{PACKAGE}"
TOKEN_URI = "https://oauth2.googleapis.com/token"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"
# How long the signed token request may be exchanged for a token: Google takes at most an hour.
ASSERTION_SECONDS = 600
# A call that takes longer than this is stuck; the upload of the bundle gets longer.
CALL_TIMEOUT_SECONDS = 60
UPLOAD_TIMEOUT_SECONDS = 900
RELEASE_TAG = re.compile(r"v(\d+\.\d+\.\d+)")


class Refusal(Exception):
    """Status 1: the upload is refused, by this script or by Play."""


class Fatal(Exception):
    """Status 2: a malformed argument or a missing tool."""


def base64url(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def version_code():
    text = pathlib.Path("version.properties").read_text()
    codes = re.findall(r"^versionCode=(\d+)$", text, re.MULTILINE)
    if len(codes) != 1:
        raise Fatal("version.properties holds no one versionCode")
    return int(codes[0])


def service_account():
    """The account from PLAY_SERVICE_ACCOUNT_JSON, refused unless it is a service account's key for Google's
    token endpoint."""
    text = os.environ.get("PLAY_SERVICE_ACCOUNT_JSON", "")
    if not text:
        raise Refusal("PLAY_SERVICE_ACCOUNT_JSON is empty: the release environment holds the Play account")
    try:
        account = json.loads(text)
    except json.JSONDecodeError as error:
        raise Refusal(f"PLAY_SERVICE_ACCOUNT_JSON is not JSON: {error.msg}") from None
    missing = [field for field in ("client_email", "private_key", "token_uri") if not account.get(field)]
    if account.get("type") != "service_account" or missing:
        raise Refusal(f"PLAY_SERVICE_ACCOUNT_JSON is not a service account's key: no {missing or ['type']}")
    if account["token_uri"] != TOKEN_URI:
        raise Refusal(f"the service account's token_uri is {account['token_uri']}, not Google's {TOKEN_URI}")
    return account


def sign_rs256(private_key, message):
    """RS256 of [message] with [private_key], by openssl; the key is on disk only in a directory of this
    process's own, for as long as openssl reads it."""
    with tempfile.TemporaryDirectory(prefix="play-key-") as scratch:
        key = pathlib.Path(scratch) / "key.pem"
        key.touch(mode=0o600)
        key.write_text(private_key)
        signed = subprocess.run(
            ["openssl", "dgst", "-sha256", "-binary", "-sign", str(key)],
            input=message, capture_output=True, check=False, timeout=CALL_TIMEOUT_SECONDS,
        )
    if signed.returncode != 0 or not signed.stdout:
        raise Refusal("openssl could not sign with the service account's private_key")
    return signed.stdout


def assertion(account, now):
    """The signed JWT Google exchanges for the account's token (RFC 7523)."""
    header = {"alg": "RS256", "typ": "JWT"}
    claims = {
        "iss": account["client_email"], "scope": SCOPE, "aud": TOKEN_URI, "iat": now, "exp": now + ASSERTION_SECONDS,
    }
    signing_input = f"{base64url(json.dumps(header).encode())}.{base64url(json.dumps(claims).encode())}"
    return f"{signing_input}.{base64url(sign_rs256(account['private_key'], signing_input.encode()))}"


def call(method, url, token=None, body=None, content_type="application/json", timeout=CALL_TIMEOUT_SECONDS):
    """One HTTPS call; Play's or Google's own words on a refusal."""
    headers = {"Content-Type": content_type}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, data=body, method=method, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            text = response.read().decode()
    except urllib.error.HTTPError as error:
        raise Refusal(f"{method} {url} answered {error.code}: {error.read().decode(errors='replace')}") from None
    return json.loads(text) if text.strip() else {}


def access_token(account):
    form = urllib.parse.urlencode({
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
        "assertion": assertion(account, int(time.time())),
    }).encode()
    return call("POST", TOKEN_URI, body=form, content_type="application/x-www-form-urlencoded")["access_token"]


def bundle_decision(held, code, digest):
    """("reuse", Play's bundle) when Play's bundles [held] have versionCode [code] as these bytes, [digest], as
    after the first release's upload by hand; ("upload", None) when none has [code]. A bundle of [code] with
    other bytes is refused: Play never takes a versionCode twice, and C7 never rebuilds."""
    same_code = [bundle for bundle in held if bundle.get("versionCode") == code]
    others = [bundle.get("sha256") for bundle in same_code if bundle.get("sha256") != digest]
    if others:
        raise Refusal(f"Play holds versionCode {code} as another bundle, {others[0]}, not this one")
    return ("reuse", same_code[0]) if same_code else ("upload", None)


def check_taken(bundle, code, digest):
    """Play's answer to the upload, or the bundle it held, is this bundle: [code] and [digest]."""
    if bundle.get("versionCode") != code or bundle.get("sha256") != digest:
        raise Refusal(f"Play took the bundle as versionCode {bundle.get('versionCode')}, {bundle.get('sha256')}")


def bundle_on_play(edit, token, code, digest, aab):
    """Play's bundle of [code]: the one it has when it is this bundle, else this one, uploaded."""
    held = call("GET", f"{API}/edits/{edit}/bundles", token).get("bundles", [])
    action, bundle = bundle_decision(held, code, digest)
    if action == "reuse":
        print(f"play_upload: Play holds versionCode {code} as this bundle already; it is not uploaded again")
        return bundle
    return call("POST", f"{UPLOAD}/edits/{edit}/bundles?uploadType=media", token, aab.read_bytes(),
                "application/octet-stream", UPLOAD_TIMEOUT_SECONDS)


def upload(account, aab, version, code, digest):
    token = access_token(account)
    edit = call("POST", f"{API}/edits", token, b"{}")["id"]
    check_taken(bundle_on_play(edit, token, code, digest, aab), code, digest)
    release = {"name": version, "versionCodes": [str(code)], "status": "completed"}
    track = json.dumps({"track": TRACK, "releases": [release]}).encode()
    call("PUT", f"{API}/edits/{edit}/tracks/{TRACK}", token, track)
    call("POST", f"{API}/edits/{edit}:commit", token, b"")
    print(f"play_upload: {PACKAGE} {version}, versionCode {code}, is on the {TRACK} testing track")


def dry_run(account, aab, version, code, digest):
    assertion(account, int(time.time()))
    print(f"play_upload: dry run as {account['client_email']}: the token request is signed")
    print(f"play_upload: would open an edit of {PACKAGE} at {API}/edits")
    print(f"play_upload: would upload {aab.name}, {aab.stat().st_size} bytes, sha256 {digest},")
    print(f"play_upload:   as versionCode {code}, unless Play holds that versionCode as these bytes already")
    print(f"play_upload: would put release {version} of versionCode {code}, completed, on the {TRACK} track")
    print("play_upload: would commit the edit")


def arguments():
    rest = sys.argv[1:]
    dry = "--dry-run" in rest
    rest = [argument for argument in rest if argument != "--dry-run"]
    if len(rest) != 2:
        raise Fatal("usage: play_upload.py <vX.Y.Z> <aab> [--dry-run]")
    match = RELEASE_TAG.fullmatch(rest[0])
    if not match:
        raise Fatal(f"{rest[0]!r} is not a release tag vMAJOR.MINOR.PATCH")
    aab = pathlib.Path(rest[1])
    if not aab.is_file():
        raise Fatal(f"no app bundle at {aab}")
    return match.group(1), aab, dry


def main():
    try:
        version, aab, dry = arguments()
        code = version_code()
        account = service_account()
        digest = hashlib.sha256(aab.read_bytes()).hexdigest()
        (dry_run if dry else upload)(account, aab, version, code, digest)
    except Fatal as fatal:
        print(f"play_upload: {fatal}", file=sys.stderr)
        return 2
    except Refusal as refusal:
        print(f"play_upload: {refusal}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
