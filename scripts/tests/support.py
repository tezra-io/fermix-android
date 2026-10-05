"""What the release scripts' tests share: a scratch directory, a fake GitHub and cosign on the PATH, git
repositories made for a test, and throwaway signing keys and packages made with the SDK's own tools.

No key, keystore or package is kept in the tree: each is made in the test's scratch directory and removed
with it (AGENTS.md: no secret in the tree).
"""
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / "scripts"
FAKES = pathlib.Path(__file__).resolve().parent / "fakes"
REPO = "tezra-io/fermix-android"
ENGINE = "tezra-io/fermix"
# build-tools' revision, as scripts/check_release_policy.sh names it.
BUILD_TOOLS_VERSION = "36.0.0"
# The platform aapt2 links a test's manifest against, for the android: attributes; the app compiles against it.
PLATFORM = "android-36"
# A script that runs longer than this is stuck, not slow.
SCRIPT_TIMEOUT_SECONDS = 300
# The identity of every commit and annotated tag a test's own set-up makes (Repository), its tagger included. A
# script under test is given none, as CI's runner names none, so a script that comes to need one fails its tests.
GIT_IDENTITY = {
    "GIT_AUTHOR_NAME": "Probe", "GIT_AUTHOR_EMAIL": "probe@example.com",
    "GIT_COMMITTER_NAME": "Probe", "GIT_COMMITTER_EMAIL": "probe@example.com",
}
PACKAGE = "io.tezra.fermix"


def sha256_of(path):
    return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()


def push_access(workflow, job):
    """The fake GitHub's FAKE_GH_CAN_PUSH for a script that [workflow]'s [job] runs: GitHub shows a draft release
    only to a token that can push, so a job that reads a draft holds contents: write, and its script's tests run
    with the token the job holds as the workflow says it, read from its own permissions: block."""
    text = (ROOT / ".github" / "workflows" / workflow).read_text()
    block = re.search(rf"^  {re.escape(job)}:\n((?:(?:    .*|\s*)\n)*)", text, re.MULTILINE)
    if block is None:
        raise AssertionError(f"{workflow} has no job {job}")
    permissions = re.search(r"^    permissions:\n((?:      .*\n)*)", block.group(1), re.MULTILINE)
    if permissions is None:
        raise AssertionError(f"{workflow}'s {job} names no permissions of its own")
    contents = re.search(r"^      contents: (\w+)", permissions.group(1), re.MULTILINE)
    return {"FAKE_GH_CAN_PUSH": "1" if contents and contents.group(1) == "write" else "0"}


def tool_environment():
    """The environment with the JDK of JAVA_HOME first on the PATH, as setup-java leaves a CI job."""
    env = dict(os.environ)
    if env.get("JAVA_HOME"):
        env["PATH"] = os.pathsep.join([str(pathlib.Path(env["JAVA_HOME"]) / "bin"), env["PATH"]])
    return env


def git_environment(env, scratch):
    """[env] as a test hands it to a process that runs git (AGENTS.md), in [scratch] or under it, an absolute path,
    as git ignores a relative ceiling. Nothing of the machine's configuration: no system or global file, which can
    name an identity, sign every tag with a program that fails or run hooks; no system attributes file; a HOME under
    [scratch], so none of the machine's own ignore and attributes files either, a HOME rather than none, as the
    scripts a test runs, unlike git, take the passwd entry's, the developer's own, when there is none; and no
    template, from which init copies hooks into the repository, as an empty GIT_TEMPLATE_DIR copies none, whatever
    the machine's git holds or its configuration names. No identity: git guesses none from the machine's user and
    host name (user.useConfigOnly), so a commit or an annotated tag made without GIT_IDENTITY fails here as on CI's
    runner. None of the caller's GIT_ variables: git takes the repository from GIT_DIR, GIT_INDEX_FILE,
    GIT_OBJECT_DIRECTORY and their kin before -C, and exports GIT_DIR to a hook or an alias in a linked worktree, so
    a test run from there would commit into and tag the caller's repository. Looking for a repository, git climbs no
    higher than [scratch], its ceiling being the directory above, as a ceiling stops git only from climbing into it:
    it finds the test's own or none."""
    scratch = pathlib.Path(scratch)
    if not scratch.is_absolute():
        raise AssertionError(f"{scratch} is not absolute, and git would climb past a relative ceiling")
    given = {name: value for name, value in env.items() if not name.startswith("GIT_") and name != "XDG_CONFIG_HOME"}
    return {
        **given, "HOME": str(scratch / "home"), "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull,
        "GIT_ATTR_NOSYSTEM": "1", "GIT_TEMPLATE_DIR": "", "GIT_CEILING_DIRECTORIES": str(scratch.parent),
        "GIT_CONFIG_COUNT": "1", "GIT_CONFIG_KEY_0": "user.useConfigOnly", "GIT_CONFIG_VALUE_0": "true",
    }


def run(command, cwd=None, env=None, stdin=None):
    """Runs [command], bounded, with [stdin] as its input when given, and returns what it did; a test asserts on
    its status and words."""
    return subprocess.run(
        [str(part) for part in command],
        cwd=cwd,
        env=env if env is not None else tool_environment(),
        input=stdin,
        capture_output=True,
        text=True,
        timeout=SCRIPT_TIMEOUT_SECONDS,
        check=False,
    )


def checked(command, cwd=None, env=None):
    """Runs a step of a test's own set-up, which must work."""
    result = run(command, cwd=cwd, env=env)
    if result.returncode != 0:
        raise AssertionError(f"{command} failed: {result.stdout}{result.stderr}")
    return result.stdout


def build_tool(name):
    home = os.environ.get("ANDROID_HOME")
    if not home:
        raise AssertionError("ANDROID_HOME names no SDK, and the tests sign with its build tools")
    return pathlib.Path(home) / "build-tools" / BUILD_TOOLS_VERSION / name


def platform_jar():
    jar = pathlib.Path(os.environ.get("ANDROID_HOME", "")) / "platforms" / PLATFORM / "android.jar"
    if not jar.is_file():
        raise AssertionError(f"no {jar}: the tests link manifests against platforms;{PLATFORM}")
    return jar


def script_tree(scratch, *names):
    """A copy of the repository's [names] under scripts/ of a tree of its own in [scratch], for a script that
    reads its own repository (version.properties, gradlew, app/): the test makes the rest of the tree."""
    tree = pathlib.Path(scratch) / "tree"
    (tree / "scripts").mkdir(parents=True)
    for name in names:
        shutil.copy2(SCRIPTS / name, tree / "scripts" / name)
    return tree


def java_tool(name):
    home = os.environ.get("JAVA_HOME")
    found = pathlib.Path(home) / "bin" / name if home else shutil.which(name)
    if not found or not pathlib.Path(found).exists():
        raise AssertionError(f"no {name}: set JAVA_HOME or put a JDK on the PATH")
    return pathlib.Path(found)


class ScriptTest(unittest.TestCase):
    """A scratch directory, and a fake GitHub that answers from files under it."""

    def setUp(self):
        self.scratch = pathlib.Path(tempfile.mkdtemp(prefix="release-scripts-"))
        self.addCleanup(shutil.rmtree, self.scratch)
        # The HOME of what the test runs (git_environment).
        (self.scratch / "home").mkdir()
        self.github = self.scratch / "github"
        (self.github / "api").mkdir(parents=True)

    def environment(self, **extra):
        env = git_environment(tool_environment(), self.scratch)
        env["PATH"] = os.pathsep.join([str(FAKES), env["PATH"]])
        env["FAKE_GH_ROOT"] = str(self.github)
        env["GH_TOKEN"] = "fake"
        env["REPO"] = REPO
        env.update({key: str(value) for key, value in extra.items()})
        return env

    def script(self, name, *arguments, cwd=None, stdin=None, **extra):
        """Runs the script [name] in [cwd], the scratch directory unless the test names one under it: a git the
        script starts finds the test's repository or none (git_environment), never the developer's checkout."""
        where = pathlib.Path(cwd or self.scratch)
        if not where.resolve().is_relative_to(self.scratch.resolve()):
            raise AssertionError(f"{where} is not under the test's scratch directory {self.scratch}")
        return run([SCRIPTS / name, *arguments], cwd=where, env=self.environment(**extra), stdin=stdin)

    def answer(self, path, body):
        """GitHub answers GET [path] with [body]: JSON for a value, the bytes for bytes."""
        target = self.github / "api" / path / "_"
        target.parent.mkdir(parents=True, exist_ok=True)
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
        target.write_bytes(data)

    def calls(self):
        log = self.github / "calls.jsonl"
        if not log.exists():
            return []
        return [json.loads(line) for line in log.read_text().splitlines()]

    def assertRefused(self, result, *sentences):
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        for sentence in sentences:
            self.assertIn(sentence, result.stderr)

    def assertPassed(self, result):
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)


class Repository:
    """A git repository made for one test in its scratch directory, with main as its branch."""

    def __init__(self, path):
        self.path = pathlib.Path(path)
        self.path.mkdir(parents=True)
        self.env = {**git_environment({"PATH": os.environ["PATH"]}, self.path.parent), **GIT_IDENTITY}
        self.git("init", "--quiet")
        self.git("symbolic-ref", "HEAD", "refs/heads/main")

    def git(self, *arguments):
        return checked(["git", "-C", self.path, *arguments], env=self.env).strip()

    def commit(self, files, message="change"):
        for name, text in files.items():
            target = self.path / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(text)
            self.git("add", name)
        self.git("commit", "--quiet", "--allow-empty", "-m", message)
        return self.git("rev-parse", "HEAD")

    def tag(self, name, annotated=False, commit="HEAD"):
        if annotated:
            self.git("tag", "-a", name, "-m", name, commit)
        else:
            self.git("tag", name, commit)

    def main_at(self, commit):
        """origin/main is [commit], as a checkout with fetch-depth 0 has it."""
        self.git("update-ref", "refs/remotes/origin/main", commit)


class Keys:
    """Throwaway keystores and packages, signed as the release pipeline signs them."""

    PASSWORD = "throwaway-password"

    def __init__(self, scratch):
        self.scratch = pathlib.Path(scratch)

    def keystore(self, name):
        store = self.scratch / f"{name}.jks"
        checked([
            java_tool("keytool"), "-genkeypair", "-noprompt", "-keystore", store, "-alias", name,
            "-keyalg", "RSA", "-keysize", "2048", "-validity", "1", "-dname", f"CN={name}",
            "-storepass", self.PASSWORD, "-keypass", self.PASSWORD,
        ])
        return store

    def digest(self, store):
        listing = checked([java_tool("keytool"), "-list", "-v", "-keystore", store, "-storepass", self.PASSWORD])
        line = next(line for line in listing.splitlines() if line.strip().startswith("SHA256:"))
        return line.split("SHA256:", 1)[1].strip().replace(":", "").lower()

    def unsigned_apk(self, name, package=PACKAGE):
        """The smallest APK apksigner reads: a manifest that names [package], linked by aapt2."""
        manifest = self.scratch / f"{name}-manifest.xml"
        manifest.write_text(
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
            f'package="{package}"/>\n'
        )
        apk = self.scratch / f"{name}-unsigned.apk"
        checked([build_tool("aapt2"), "link", "-o", apk, "--manifest", manifest])
        return apk

    def linked_apk(self, name, package=PACKAGE, code=7, version="0.2.0", debuggable=False, proto=False,
                   project="fermix-release"):
        """An APK linked by aapt2 as the release build links one: [package], versionCode [code], versionName
        [version], minSdk 35, an application, debuggable when told, and Firebase's project_id string; aligned as
        the build aligns it, or in the protocol-buffer form a bundle's base module holds when [proto]."""
        work = self.scratch / f"{name}-link"
        (work / "res" / "values").mkdir(parents=True)
        (work / "manifest.xml").write_text(
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
            f'package="{package}"><application/></manifest>\n'
        )
        (work / "res" / "values" / "values.xml").write_text(
            f'<resources><string name="project_id">{project}</string></resources>\n'
        )
        checked([build_tool("aapt2"), "compile", "-o", work / "compiled.zip", "--dir", work / "res"])
        linked = work / "linked.apk"
        checked([
            build_tool("aapt2"), "link", "-I", platform_jar(), "-o", linked, "--manifest", work / "manifest.xml",
            "--version-code", code, "--version-name", version, "--min-sdk-version", "35",
            "--target-sdk-version", "36", *(["--debug-mode"] if debuggable else []),
            *(["--proto-format"] if proto else []), work / "compiled.zip",
        ])
        if proto:
            return linked
        aligned = self.scratch / f"{name}-unsigned.apk"
        checked([build_tool("zipalign"), "-P", "16", "-f", "4", linked, aligned])
        return aligned

    def linked_aab(self, name, **apk):
        """An app bundle whose base module is linked_apk's, its manifest and resource table as aapt2 writes them
        in protocol-buffer form: what verify_candidate.sh reads of a bundle."""
        proto = self.linked_apk(f"{name}-base", proto=True, **apk)
        content = self.scratch / f"{name}-bundle"
        (content / "proto").mkdir(parents=True)
        (content / "base" / "manifest").mkdir(parents=True)
        checked(["unzip", "-q", proto, "-d", content / "proto"])
        shutil.move(content / "proto" / "AndroidManifest.xml", content / "base" / "manifest" / "AndroidManifest.xml")
        shutil.move(content / "proto" / "resources.pb", content / "base" / "resources.pb")
        aab = self.scratch / f"{name}-unsigned.aab"
        checked(["zip", "-q", "-r", aab, "base"], cwd=content)
        return aab

    def unsigned_aab(self, name):
        """A zip, as an app bundle is one; jarsigner signs it as it signs a bundle."""
        content = self.scratch / f"{name}-bundle"
        (content / "base" / "manifest").mkdir(parents=True)
        (content / "base" / "manifest" / "AndroidManifest.xml").write_bytes(b"not read by these tests")
        aab = self.scratch / f"{name}-unsigned.aab"
        checked(["zip", "-q", "-r", aab, "base"], cwd=content)
        return aab

    def sign_apk(self, unsigned, store, out, preserve_alignment=False):
        """[unsigned] signed by [store]; apksigner aligns as it signs, unless told to keep the alignment it found."""
        checked([
            build_tool("apksigner"), "sign", "--ks", store, "--ks-pass", f"pass:{self.PASSWORD}",
            "--min-sdk-version", "35", "--v4-signing-enabled", "false",
            "--alignment-preserved", "true" if preserve_alignment else "false", "--out", out, unsigned,
        ])
        return out

    def sign_aab(self, unsigned, store, out):
        alias = pathlib.Path(store).stem
        checked([
            java_tool("jarsigner"), "-keystore", store, "-storepass", self.PASSWORD, "-keypass", self.PASSWORD,
            "-signedjar", out, unsigned, alias,
        ])
        return out


def signers(release_digest, development_digest=None, package=PACKAGE):
    """An android_signers.json as onboarding section 2.5 shapes it."""
    entries = [{"role": "release", "sha256": release_digest, "added": "2026-10-05"}]
    if development_digest:
        entries.append({"role": "development", "sha256": development_digest, "added": "2026-10-05", "machine": "ci"})
    return {"v": 1, "package": package, "signers": entries}
