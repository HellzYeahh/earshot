"""The release gate's checks on built APKs, the Google Play bundle and the source tree.

docs/roadmap.md, 1.0 plan item 2; docs/research/play-readiness.md F1-F3. Runs locally and in CI (.github/workflows/ci.yml). Exit code 1 if any check fails.

    # CI: the unsigned release builds of both flavours, and the bundle that goes to Google Play
    python tools/release/check_release.py --apk app/build/outputs/apk/foss/release/app-foss-release-unsigned.apk \
        --apk app/build/outputs/apk/play/release/app-play-release-unsigned.apk \
        --aab app/build/outputs/bundle/playRelease/app-play-release.aab \
        [--tag v1.0.0] [--lint app/build/reports/lint-results-fossDebug.xml]
    # The developer's PC: the signed asset that is published (docs/release-signing.md, step 4)
    python tools/release/check_release.py --apk app/build/earshot-1.0.1.apk --tag v1.0.1 [--installs-over earshot-1.0.0.apk]
    # ... and the bundle that is uploaded to Google Play
    python tools/release/check_release.py --aab app/build/outputs/bundle/playRelease/app-play-release.aab --tag v1.0.1
    # Once: the 0.9.1 bridge (docs/release-signing.md, "Moving existing users")
    python tools/release/check_release.py --apk app/build/earshot-0.9.1.apk --tag v0.9.1 --bridge --installs-over app/build/earshot-0.9.0.apk

Checks, per APK:
  - the flavour comes from the application ID: dev.earshot.foss is the GitHub build (foss),
    dev.earshot the Google Play build (play). Any other ID fails, and so does a build path or file
    name that names the other flavour. earshot-X.Y.Z.apk is the GitHub asset and must be the foss
    build, or the bridge with --bridge;
  - no INTERNET permission (Earshot's promise), no QUERY_ALL_PACKAGES (Google Play refuses it; the
    manifest's <queries> names each app Earshot looks up) and no com.google.android.gms.permission.AD_ID
    (Earshot shows no ads, and its answer to Play's Advertising ID form is No), in any build;
  - no billing permission in the GitHub build. No Shizuku in the Google Play build: no moe.shizuku.*
    permission (moe.shizuku.manager.permission.API_V23) and no rikka.shizuku / moe.shizuku provider,
    because Shizuku ships in the GitHub build only (the user's decision of 2026-10-29,
    play-readiness.md B8);
  - every 64-bit native library (arm64-v8a, x86_64) has LOAD segments aligned to 16 KB, so it loads
    on devices with 16 KB pages (Google Play requires that from apps that target Android 15 or later);
  - the size stays under the budget;
  - on a tag: versionName equals the tag and the build isn't debuggable (playDebug and fossDebug
    are never published). An APK whose name doesn't end in -unsigned.apk must also pass
    `apksigner verify` (so apksigner must be found), must not be signed with a debug certificate,
    and must pass `zipalign -c -P 16 -v 4` (4-byte zip alignment, uncompressed native libraries on
    16 KB boundaries; zipalign from build-tools 35 or later). CI only has -unsigned builds, so these
    checks run on the developer's PC against the asset that is published;
  - with --installs-over OLD.apk (needs --tag): the same app ID and signing key as OLD.apk and a
    higher versionCode, so Android installs it as an update of OLD.apk.
Each APK's and bundle's full permission list is printed, so a permission that a library merges in
shows up in the log (play-readiness.md F2).

Per --aab, the Android App Bundle that is uploaded to Google Play: the same manifest checks on its
base module. It must be the play build, it must never be debuggable (a bundle exists only to be
uploaded), its 64-bit native libraries must be 16 KB aligned, and on a tag its versionName equals the
tag. Its manifest is read with aapt2 (aapt2 can't open an .aab, so the base manifest and resource
table are copied into a proto-format APK, which it dumps like any other), else with bundletool:
$BUNDLETOOL (bundletool-all.jar or a launcher), `bundletool` on PATH, or the jar that the Android
Gradle plugin left in the Gradle cache, run with its dependencies from the same cache. If neither
works, the bundle fails. Play checks the upload key itself, so no certificate checks run on it.

And for the tree, on a tag: WhatsNew.RELEASE equals the version and CHANGELOG.md has a dated
section for it. With --lint, the lint report must hold no errors.

--bridge checks the one-time 0.9.1 bridge instead of a normal build: the foss flavour built with
-Pearshot.legacyBridge=true. Its app ID must be dev.earshot, its manifest must look up dev.earshot
as only the foss flavour's does (so it isn't the play build), the GitHub billing check applies, and
on a tag it must be signed with a debug certificate, the key of every release up to 0.9.0
(--installs-over with the published 0.9.0 proves it is that same key).
"""

from __future__ import annotations

import argparse
import glob
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP_IDS = {"play": "dev.earshot", "foss": "dev.earshot.foss"}
FLAVOUR_BY_APP_ID = {app_id: flavour for flavour, app_id in APP_IDS.items()}
# The one-time bridge (--bridge): the foss flavour under the pre-1.0 app ID, debug-signed like every
# release up to 0.9.0 so that it updates them in place.
BRIDGE_APP_ID = "dev.earshot"
# Only the foss flavour's manifest names this package in <queries> (app/src/foss/AndroidManifest.xml,
# the old-install lookup). That's how the bridge, which has the play build's app ID, still shows that
# it is the foss flavour.
LEGACY_LOOKUP = "dev.earshot"
# The subject of the debug key that Android Studio / the Android Gradle plugin generates.
DEBUG_CERT = "CN=Android Debug"
SIZE_BUDGET_MB = 16.0  # 0.9.0 was 12.6 MB; the sleep recordings are most of it.
FORBIDDEN = {"android.permission.INTERNET"}
FORBIDDEN_FOSS = {"com.android.vending.BILLING", "com.android.vending.CHECK_LICENSE"}
# Refused in every build, with its own message: Google Play allows it only for apps whose core
# purpose needs every installed app, and a library's manifest merge could bring it back unnoticed.
# The GitHub build is refused too, so both flavours keep one package-visibility story.
QUERY_ALL_PACKAGES = "android.permission.QUERY_ALL_PACKAGES"
# Refused in every build: Earshot shows no ads and answers No on Play's Advertising ID form, which a
# library that merges this permission in would make false (play-readiness.md C and F2).
AD_ID = "com.google.android.gms.permission.AD_ID"
# Shizuku ships in the GitHub build only (play-readiness.md B8): neither its permission
# (moe.shizuku.manager.permission.API_V23) nor its provider (rikka.shizuku.ShizukuProvider) may reach
# the Google Play build.
SHIZUKU_PERMISSION = "moe.shizuku.manager.permission.API_V23"
SHIZUKU_PERMISSION_PREFIX = "moe.shizuku."
SHIZUKU_PROVIDER_PREFIXES = ("rikka.shizuku.", "moe.shizuku.")
# The Shizuku manager app. Looking it up isn't a Play problem in itself, so it's only a note.
SHIZUKU_MANAGER = "moe.shizuku.privileged.api"
# 16 KB pages (https://developer.android.com/guide/practices/page-sizes): only 64-bit ABIs run on them.
PAGE_16K = 16 * 1024
ABIS_64 = ("arm64-v8a", "x86_64")
PT_LOAD = 1
# Where an .aab keeps the base module's manifest and resource table (protobuf: aapt2's proto format).
BUNDLE_MANIFEST = "base/manifest/AndroidManifest.xml"
BUNDLE_RESOURCES = "base/resources.pb"
BUNDLETOOL_MAIN = "com.android.tools.build.bundletool.BundleToolMain"
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
# One attribute line of `aapt2 dump xmltree`: `A: <namespace URI>:name(0x01010003)="value" (Raw: "value")`,
# or `A: package="dev.earshot" (Raw: ...)` without a namespace.
XMLTREE_ATTR = re.compile(r"^A: (?:[^\s=]*:)?([\w.-]+)(?:\(0x[0-9a-fA-F]+\))?=(.*)$")
# One permission line of `aapt2 dump badging`, with its extra attributes (maxSdkVersion, usesPermissionFlags).
# The -sdk-23 form counts too: a permission declared only for API 23+ is still requested there.
BADGING_PERMISSION = re.compile(r"^uses-permission(-sdk-23)?: name='([^']+)'(.*)$", re.M)


@dataclass(frozen=True)
class Installed:
    """The release a new APK must install over (--installs-over)."""

    name: str
    app_id: str
    version_code: int
    signers: frozenset[str]


@dataclass(frozen=True)
class Permission:
    name: str
    note: str = ""  # "maxSdkVersion=30", "API 23+": shown in the permission list only


@dataclass(frozen=True)
class Manifest:
    """What the gate reads from one built manifest: an APK's, or the base module's of a bundle."""

    app_id: str
    version_code: str
    version_name: str
    permissions: tuple[Permission, ...]
    debuggable: bool
    providers: tuple[str, ...]  # <provider android:name>, in manifest order
    looks_up: frozenset[str]  # the packages named in <queries>

    @property
    def permission_names(self) -> frozenset[str]:
        return frozenset(p.name for p in self.permissions)


def version_key(version: str) -> list[tuple[int, int | str]]:
    """Sorts 36.0.0 above 35.0.0 and 33.5.0-jre above 32.0.1-jre, never comparing a number with text."""
    return [(0, int(x)) if x.isdigit() else (1, x) for x in re.split(r"[.\-]", version)]


def find_tool(name: str) -> str | None:
    """aapt2 / apksigner / zipalign from the newest Android SDK build-tools, or from PATH."""
    found = shutil.which(name)
    if found:
        return found
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        local = ROOT / "local.properties"
        if local.is_file():
            m = re.search(r"^sdk\.dir=(.+)$", local.read_text(encoding="utf-8"), re.M)
            if m:
                sdk = m.group(1).replace("\\:", ":").replace("\\\\", "\\").strip()
    if not sdk:
        return None
    exts = [".exe", ".bat", ""] if os.name == "nt" else [""]
    candidates = []
    for ext in exts:
        candidates += glob.glob(os.path.join(sdk, "build-tools", "*", name + ext))
    return sorted(candidates, key=lambda p: version_key(Path(p).parent.name))[-1] if candidates else None


def find_java() -> str | None:
    """java from JAVA_HOME, PATH or Android Studio's bundled JDK (the one gw.cmd uses)."""
    exe = "java.exe" if os.name == "nt" else "java"
    candidates = []
    if os.environ.get("JAVA_HOME"):
        candidates.append(Path(os.environ["JAVA_HOME"]) / "bin" / exe)
    on_path = shutil.which("java")
    if on_path:
        candidates.append(Path(on_path))
    candidates += [
        Path(r"C:\Program Files\Android\Android Studio\jbr\bin\java.exe"),
        Path("/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java"),
        Path("/opt/android-studio/jbr/bin/java"),
    ]
    return next((str(c) for c in candidates if c.is_file()), None)


def artifact_name(jar: str) -> str:
    """guava-32.0.1-jre.jar -> guava, aapt2-proto-7.3.0-alpha07-8248216.jar -> aapt2-proto."""
    m = re.match(r"^(.+?)-\d", jar)
    return m.group(1) if m else jar.removesuffix(".jar")


def jar_class_path(jar: str) -> list[str]:
    """The Class-Path entries of a jar's manifest (continuation lines start with one space)."""
    with zipfile.ZipFile(jar) as z:
        text = z.read("META-INF/MANIFEST.MF").decode("utf-8", "replace")
    m = re.search(r"^Class-Path: *(.*)$", re.sub(r"\r?\n ", "", text), re.M)
    return m.group(1).split() if m else []


def newest_jar(paths: list[str]) -> str | None:
    """The jar in the highest version folder of the Gradle cache (files-2.1/group/artifact/VERSION/hash/x.jar)."""
    jars = [p for p in paths if not p.endswith(("-sources.jar", "-javadoc.jar"))]
    return max(jars, key=lambda p: version_key(Path(p).parent.parent.name)) if jars else None


def gradle_cache_classpath() -> list[str] | None:
    """bundletool from the Gradle cache: the Android Gradle plugin resolves it there, but as a plain jar
    whose Class-Path names its dependencies, so each one is taken from the same cache by artifact name
    (Gradle keeps the versions it resolved, often newer than the ones the jar names)."""
    home = Path(os.environ.get("GRADLE_USER_HOME") or Path.home() / ".gradle")
    files = home / "caches" / "modules-2" / "files-2.1"
    tools = glob.glob(str(files / "com.android.tools.build" / "bundletool" / "*" / "*" / "bundletool-*.jar"))
    for jar in sorted(tools, key=lambda p: version_key(Path(p).parent.parent.name), reverse=True):
        if jar.endswith(("-sources.jar", "-javadoc.jar")):
            continue
        try:
            entries = jar_class_path(jar)
        except (OSError, KeyError, zipfile.BadZipFile):
            continue
        deps = []
        for entry in entries:
            name = artifact_name(entry)
            dep = newest_jar(glob.glob(str(files / "*" / name / "*" / "*" / f"{name}-*.jar")))
            if dep is None:
                break
            deps.append(dep)
        else:
            return [jar, *deps]
    return None


def find_bundletool() -> list[str] | None:
    """The command that runs bundletool: $BUNDLETOOL (bundletool-all.jar or a launcher), `bundletool`
    on PATH, or the jar in the Gradle cache with its dependencies."""
    env = os.environ.get("BUNDLETOOL")
    if env:
        if env.lower().endswith(".jar"):
            java = find_java()
            return [java, "-jar", env] if java else None
        return [env]
    on_path = shutil.which("bundletool")
    if on_path:
        return [on_path]
    java = find_java()
    classpath = gradle_cache_classpath() if java else None
    return [java, "-cp", os.pathsep.join(classpath), BUNDLETOOL_MAIN] if java and classpath else None


def badging(aapt2: str, apk: Path) -> str:
    return subprocess.run([aapt2, "dump", "badging", str(apk)], capture_output=True, text=True, check=True).stdout


def xmltree(aapt2: str, apk: Path) -> str:
    return subprocess.run(
        [aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", str(apk)], capture_output=True, text=True, check=True
    ).stdout


def package_line(info: str) -> tuple[str, str, str] | None:
    """(app ID, versionCode, versionName) from aapt2's badging, or None."""
    pkg = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", info)
    return pkg.groups() if pkg else None  # type: ignore[return-value]


def badging_permissions(info: str) -> tuple[Permission, ...]:
    out = []
    for sdk23, name, rest in BADGING_PERMISSION.findall(info):
        notes = ["API 23+"] if sdk23 else []
        notes += [f"{k}={v}" for k, v in re.findall(r"(\w+)='([^']*)'", rest)]
        out.append(Permission(name, ", ".join(notes)))
    return tuple(out)


def xmltree_value(raw: str) -> str:
    """`"x" (Raw: "x")` -> x, `13 (Raw: "13")` -> 13, `(type 0x12)0xffffffff` stays as it is."""
    if raw.startswith('"'):
        end = raw.find('"', 1)
        return raw[1:end] if end > 0 else raw[1:]
    return raw.split(" (Raw: ", 1)[0].strip()


def parse_xmltree(tree: str) -> list[tuple[tuple[str, ...], dict[str, str]]]:
    """`aapt2 dump xmltree` as (element names from the root down, attributes) per element, in document
    order. Attribute names lose their namespace (android:name -> name). Children sit four columns
    deeper than their element and attributes two, in binary (APK) and proto (bundle) dumps alike;
    only the attribute order differs, so nothing here depends on it."""
    elements: list[tuple[tuple[str, ...], dict[str, str]]] = []
    stack: list[tuple[int, str]] = []
    for line in tree.splitlines():
        text = line.lstrip()
        indent = len(line) - len(text)
        if text.startswith("E: "):
            while stack and stack[-1][0] >= indent:
                stack.pop()
            stack.append((indent, text[3:].split(" ", 1)[0]))
            elements.append((tuple(name for _, name in stack), {}))
        elif text.startswith("A: ") and elements:
            m = XMLTREE_ATTR.match(text)
            if m:
                elements[-1][1][m.group(1)] = xmltree_value(m.group(2))
    return elements


def manifest_from_aapt2(info: str, tree: str) -> Manifest | None:
    """The manifest from aapt2's badging (package, permissions, debuggable) and xmltree (providers,
    <queries>), or None without a package line."""
    pkg = package_line(info)
    if not pkg:
        return None
    elements = parse_xmltree(tree)
    providers = tuple(a["name"] for path, a in elements if path[-2:] == ("application", "provider") and "name" in a)
    looks_up = frozenset(a["name"] for path, a in elements if path[-2:] == ("queries", "package") and "name" in a)
    return Manifest(pkg[0], pkg[1], pkg[2], badging_permissions(info), "application-debuggable" in info, providers, looks_up)


def manifest_from_xml(text: str) -> Manifest | None:
    """The manifest from `bundletool dump manifest` (plain XML), or None if it isn't one."""
    try:
        root = ET.fromstring(text.strip())
    except ET.ParseError:
        return None
    if root.tag != "manifest" or not root.get("package"):
        return None

    def attr(el: ET.Element, name: str) -> str | None:
        return el.get(ANDROID_NS + name)

    perms = []
    for el in root:
        name = attr(el, "name")
        if el.tag in ("uses-permission", "uses-permission-sdk-23") and name:
            notes = ["API 23+"] if el.tag.endswith("-sdk-23") else []
            notes += [f"{k}={attr(el, k)}" for k in ("maxSdkVersion", "usesPermissionFlags") if attr(el, k)]
            perms.append(Permission(name, ", ".join(notes)))
    app = root.find("application")
    debuggable = app is not None and (attr(app, "debuggable") or "").lower() in ("true", "-1", "0xffffffff")
    providers = tuple(n for p in (app.findall("provider") if app is not None else []) if (n := attr(p, "name")))
    looks_up = frozenset(n for q in root.findall("queries") for p in q.findall("package") if (n := attr(p, "name")))
    return Manifest(
        root.get("package") or "", attr(root, "versionCode") or "?", attr(root, "versionName") or "?", tuple(perms), debuggable, providers, looks_up
    )


def path_flavour(path: Path) -> str | None:
    """The flavour a Gradle output path or file name names (apk/foss/release/app-foss-release.apk), if any.

    earshot-X.Y.Z.apk is the GitHub release asset, so it names foss: a play build copied to that name
    then fails on its app ID instead of passing as play (the bridge passes with --bridge)."""
    name = path.name.lower()
    if re.fullmatch(r"earshot-\d+\.\d+\.\d+\.apk", name):
        return "foss"
    parts = {p.lower() for p in path.parts}
    for f in APP_IDS:
        if f in parts or f"-{f}-" in name:
            return f
    return None


def verify_signature(apksigner: str, apk: Path) -> tuple[bool, str]:
    """`apksigner verify --print-certs`: (passed, its output). It exits non-zero on an unsigned or broken APK."""
    run = subprocess.run([apksigner, "verify", "--print-certs", str(apk)], capture_output=True, text=True, errors="replace")
    return run.returncode == 0, (run.stdout or "") + (run.stderr or "")


def verify_alignment(zipalign: str, apk: Path) -> tuple[bool, str]:
    """`zipalign -c -P 16 -v 4`: (passed, its output). It exits non-zero if any entry is misaligned."""
    run = subprocess.run([zipalign, "-c", "-P", "16", "-v", "4", str(apk)], capture_output=True, text=True, errors="replace")
    return run.returncode == 0, (run.stdout or "") + (run.stderr or "")


def signer_digests(certs: str) -> frozenset[str]:
    return frozenset(d.lower() for d in re.findall(r"certificate SHA-256 digest: ([0-9a-fA-F]+)", certs))


def first_lines(text: str, n: int = 2) -> str:
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    return "; ".join(lines[:n]) or "no output"


def elf_load_alignment(data: bytes) -> int | None:
    """The smallest p_align of a 64-bit ELF file's LOAD segments, or None if it isn't one."""
    if len(data) < 64 or data[:4] != b"\x7fELF" or data[4] != 2:  # EI_CLASS 2 = 64-bit
        return None
    endian = "<" if data[5] == 1 else ">"  # EI_DATA 1 = little-endian
    (phoff,) = struct.unpack_from(endian + "Q", data, 0x20)
    phentsize, phnum = struct.unpack_from(endian + "HH", data, 0x36)
    aligns = []
    for i in range(phnum):
        at = phoff + i * phentsize
        if phentsize < 56 or at + 56 > len(data):
            return None
        (p_type,) = struct.unpack_from(endian + "I", data, at)
        if p_type == PT_LOAD:
            aligns.append(struct.unpack_from(endian + "Q", data, at + 48)[0])
    return min(aligns) if aligns else None


def check_native_alignment(archive: Path, lib_dir: str) -> list[str]:
    """Every 64-bit native library under lib_dir (lib/ in an APK, base/lib/ in a bundle) must have
    16 KB LOAD alignment, or Android 15+ devices with 16 KB pages can't load it."""
    errors = []
    try:
        with zipfile.ZipFile(archive) as z:
            for entry in z.infolist():
                if not entry.filename.startswith(lib_dir) or not entry.filename.endswith(".so"):
                    continue
                if entry.filename[len(lib_dir) :].split("/")[0] not in ABIS_64:
                    continue
                align = elf_load_alignment(z.read(entry))
                if align is None:
                    errors.append(f"{archive.name}: {entry.filename} isn't a 64-bit ELF library")
                elif align < PAGE_16K:
                    errors.append(
                        f"{archive.name}: {entry.filename} has {align} byte LOAD alignment, not 16 KB, so it won't load "
                        "on Android 15+ devices with 16 KB pages and Google Play refuses the update (update the library that ships it)"
                    )
    except zipfile.BadZipFile:
        errors.append(f"{archive.name}: not a zip file")
    return errors


def check_zip_alignment(apk: Path, zipalign: str | None) -> list[str]:
    """`zipalign -c -P 16 -v 4` on a signed APK: the zip alignment that installs and 16 KB pages need."""
    if not zipalign:
        return [f"{apk.name}: zipalign not found (set ANDROID_HOME), so its 16 KB alignment can't be checked"]
    passed, out = verify_alignment(zipalign, apk)
    if passed:
        return []
    bad = [line.strip() for line in out.splitlines() if "(BAD" in line]
    if not bad and "Usage:" in out:
        return [f"{apk.name}: this zipalign can't check 16 KB pages (-P needs build-tools 35 or later)"]
    return [
        f"{apk.name}: zipalign -c -P 16 -v 4 failed ({'; '.join(bad[:5]) if bad else first_lines(out)}); "
        "uncompressed native libraries must sit on 16 KB boundaries"
    ]


def print_permissions(m: Manifest) -> None:
    for p in sorted(m.permissions, key=lambda p: (p.name, p.note)):
        print(f"      {p.name}" + (f" ({p.note})" if p.note else ""))


def check_manifest(name: str, m: Manifest, flavour: str | None) -> list[str]:
    """The permission and component rules for every build, APK or bundle."""
    perms = m.permission_names
    errors = [f"{name}: declares {p} (Earshot never talks to a server)" for p in sorted(perms & FORBIDDEN)]
    if QUERY_ALL_PACKAGES in perms:
        errors.append(
            f"{name}: declares {QUERY_ALL_PACKAGES} (Google Play refuses it; name the apps in the "
            "manifest's <queries> instead, and look for a library that merges it in)"
        )
    if AD_ID in perms:
        errors.append(
            f"{name}: declares {AD_ID} (Earshot shows no ads and tells Google Play so; find the library that "
            'merges it in and remove it with tools:node="remove")'
        )
    if flavour == "foss":
        errors += [f"{name}: the GitHub build declares {p}" for p in sorted(perms & FORBIDDEN_FOSS)]
    if flavour == "play":
        where = "Shizuku is in the GitHub build only: app/src/foss/AndroidManifest.xml"
        for p in sorted(p for p in perms if p.startswith(SHIZUKU_PERMISSION_PREFIX)):
            errors.append(f"{name}: the Google Play build declares {p} ({where})")
        for p in m.providers:
            if p.startswith(SHIZUKU_PROVIDER_PREFIXES):
                errors.append(f"{name}: the Google Play build declares the Shizuku provider {p} ({where})")
    return errors


def print_notes(m: Manifest, flavour: str | None) -> None:
    """What doesn't fail the gate but is worth a look."""
    if flavour == "play" and SHIZUKU_MANAGER in m.looks_up:
        print(f"    note: the Google Play build still looks up {SHIZUKU_MANAGER} in <queries>")


def read_installed(apk: Path, aapt2: str, apksigner: str | None) -> tuple[Installed | None, str | None]:
    """The --installs-over APK, or (None, why not)."""
    if not apk.is_file():
        return None, f"{apk}: not found"
    if not apksigner:
        return None, "apksigner not found (set ANDROID_HOME); --installs-over needs it"
    pkg = package_line(badging(aapt2, apk))
    if not pkg:
        return None, f"{apk.name}: aapt2 found no package line"
    passed, certs = verify_signature(apksigner, apk)
    signers = signer_digests(certs)
    if not passed or not signers:
        return None, f"{apk.name}: apksigner can't read its signer ({first_lines(certs)})"
    return Installed(apk.name, pkg[0], int(pkg[1]), signers), None


def check_signature(apk: Path, apksigner: str | None, bridge: bool, over: Installed | None) -> list[str]:
    """The certificate checks on a tag. Only a signed APK has a certificate to check."""
    if apk.name.endswith("-unsigned.apk"):
        print(f"  {apk.name}: unsigned, so the certificate and zipalign checks run on the signed asset only")
        return []
    if not apksigner:
        return [f"{apk.name}: apksigner not found (set ANDROID_HOME), so its signature can't be checked"]
    passed, certs = verify_signature(apksigner, apk)
    if not passed:
        return [f"{apk.name}: apksigner verify failed ({first_lines(certs)}); Android won't install it"]
    errors: list[str] = []
    debug_signed = DEBUG_CERT in certs
    if bridge and not debug_signed:
        errors.append(f"{apk.name}: the bridge must be signed with the debug key of 0.9.0 and earlier, or it can't update those installs")
    elif not bridge and debug_signed:
        errors.append(f"{apk.name}: signed with a debug certificate")
    if over is not None and signer_digests(certs) != over.signers:
        errors.append(f"{apk.name}: signed with a different key than {over.name}, so Android won't update {over.name} to it")
    return errors


def check_apk(
    apk: Path,
    aapt2: str,
    apksigner: str | None,
    tag_version: str | None,
    bridge: bool = False,
    over: Installed | None = None,
    zipalign: str | None = None,
) -> list[str]:
    errors: list[str] = []
    if not apk.is_file():
        return [f"{apk}: not found"]
    m = manifest_from_aapt2(badging(aapt2, apk), xmltree(aapt2, apk))
    if m is None:
        return [f"{apk.name}: aapt2 found no package line"]

    # The flavour is what the APK is, not what it's called: the published asset is earshot-X.Y.Z.apk.
    named = path_flavour(apk)
    if bridge:
        flavour: str | None = "foss"
        if m.app_id != BRIDGE_APP_ID:
            errors.append(f"{apk.name}: app ID {m.app_id}, but the bridge must be {BRIDGE_APP_ID} (build it with -Pearshot.legacyBridge=true)")
        if named == "play" or LEGACY_LOOKUP not in m.looks_up:
            errors.append(f"{apk.name}: the bridge is the foss flavour (:app:assembleFossRelease), but this is the play build")
    else:
        flavour = FLAVOUR_BY_APP_ID.get(m.app_id)
        if flavour is None:
            errors.append(f"{apk.name}: app ID {m.app_id} is neither {APP_IDS['foss']} (GitHub) nor {APP_IDS['play']} (Google Play)")
        elif named and named != flavour:
            hint = " (pass --bridge for the 0.9.1 bridge)" if named == "foss" and m.app_id == BRIDGE_APP_ID else ""
            errors.append(f"{apk.name}: built as {named}, but its app ID {m.app_id} is the {flavour} build's{hint}")

    errors += check_manifest(apk.name, m, flavour)
    errors += check_native_alignment(apk, "lib/")
    size_mb = apk.stat().st_size / 1_000_000
    # Debug builds skip R8, so only release counts.
    if size_mb > SIZE_BUDGET_MB and not m.debuggable:
        errors.append(f"{apk.name}: {size_mb:.1f} MB is over the {SIZE_BUDGET_MB:.0f} MB budget")
    print(
        f"  {apk.name}: {m.app_id} ({flavour or '?'}) {m.version_name} ({m.version_code}), {size_mb:.1f} MB, "
        f"{len(m.permission_names)} permissions:"
    )
    print_permissions(m)
    print_notes(m, flavour)

    if tag_version is not None:
        if m.version_name != tag_version:
            errors.append(f"{apk.name}: versionName {m.version_name}, but the tag says {tag_version}")
        if m.debuggable:
            errors.append(f"{apk.name}: a debuggable build; debug variants (playDebug, fossDebug) are never published")
        if over is not None:
            if m.app_id != over.app_id:
                errors.append(f"{apk.name}: app ID {m.app_id}, but {over.name} is {over.app_id}, so it installs next to it, not over it")
            if int(m.version_code) <= over.version_code:
                errors.append(f"{apk.name}: versionCode {m.version_code} isn't above {over.name}'s {over.version_code}, so Android won't update to it")
        errors += check_signature(apk, apksigner, bridge, over)
        if not apk.name.endswith("-unsigned.apk"):
            errors += check_zip_alignment(apk, zipalign)
    return errors


def bundle_problem(aab: Path) -> str | None:
    """Why aab isn't an Android App Bundle, or None."""
    try:
        with zipfile.ZipFile(aab) as z:
            if BUNDLE_MANIFEST not in z.namelist():
                return f"no {BUNDLE_MANIFEST}, so it isn't an Android App Bundle (build it with :app:bundlePlayRelease)"
    except zipfile.BadZipFile:
        return "not a zip file, so it isn't an Android App Bundle"
    return None


def write_proto_apk(aab: Path, out: Path) -> None:
    """The base module's manifest and resource table at the top of a zip: a proto-format APK, which
    aapt2 dumps like any APK. It can't open the .aab itself, which has no manifest at the top."""
    with zipfile.ZipFile(aab) as bundle, zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as apk:
        apk.writestr("AndroidManifest.xml", bundle.read(BUNDLE_MANIFEST))
        if BUNDLE_RESOURCES in bundle.namelist():
            apk.writestr("resources.pb", bundle.read(BUNDLE_RESOURCES))


def bundle_aapt2_dumps(aapt2: str, aab: Path) -> tuple[str, str]:
    """aapt2's badging and xmltree of the bundle's base manifest."""
    with tempfile.TemporaryDirectory(prefix="earshot-gate-") as tmp:
        proto = Path(tmp) / "base-proto.apk"
        write_proto_apk(aab, proto)
        return badging(aapt2, proto), xmltree(aapt2, proto)


def bundletool_manifest(bundletool: list[str], aab: Path) -> str:
    """`bundletool dump manifest`: the base module's manifest as plain XML."""
    return subprocess.run(
        [*bundletool, "dump", "manifest", f"--bundle={aab}"], capture_output=True, text=True, check=True, errors="replace"
    ).stdout


def read_bundle_manifest(aab: Path, aapt2: str | None, bundletool: list[str] | None) -> tuple[Manifest | None, str]:
    """The bundle's base manifest and the tool that read it, or (None, why it couldn't be read)."""
    problem = bundle_problem(aab)
    if problem:
        return None, problem
    tried = []
    if aapt2:
        try:
            m = manifest_from_aapt2(*bundle_aapt2_dumps(aapt2, aab))
            if m is not None:
                return m, "aapt2"
            tried.append("aapt2 found no package line in the base manifest")
        except subprocess.CalledProcessError as e:
            tried.append(f"aapt2 can't dump the base manifest ({first_lines(e.stderr or e.stdout or '')})")
    else:
        tried.append("aapt2 not found (set ANDROID_HOME)")
    if bundletool:
        try:
            m = manifest_from_xml(bundletool_manifest(bundletool, aab))
            if m is not None:
                return m, "bundletool"
            tried.append("bundletool printed no manifest")
        except subprocess.CalledProcessError as e:
            tried.append(f"bundletool failed ({first_lines(e.stderr or e.stdout or '')})")
        except OSError as e:
            tried.append(f"bundletool can't run ({e})")
    else:
        tried.append("bundletool not found (set BUNDLETOOL to bundletool-all.jar, or build the bundle once so the Gradle cache holds it)")
    return None, "can't read its manifest: " + "; ".join(tried)


def check_aab(aab: Path, aapt2: str | None, bundletool: list[str] | None, tag_version: str | None) -> list[str]:
    """The bundle that is uploaded to Google Play: the APKs' manifest checks on its base module."""
    if not aab.is_file():
        return [f"{aab}: not found"]
    m, how = read_bundle_manifest(aab, aapt2, bundletool)
    if m is None:
        return [f"{aab.name}: {how}"]
    errors: list[str] = []
    flavour = FLAVOUR_BY_APP_ID.get(m.app_id)
    named = path_flavour(aab)
    if flavour != "play":
        errors.append(
            f"{aab.name}: app ID {m.app_id}, but the bundle goes to Google Play, so it must be the play build "
            f"({APP_IDS['play']}, :app:bundlePlayRelease)"
        )
    elif named and named != "play":
        errors.append(f"{aab.name}: built as {named}, but its app ID {m.app_id} is the play build's")
    errors += check_manifest(aab.name, m, flavour)
    if m.debuggable:
        errors.append(f"{aab.name}: a debuggable bundle; Google Play refuses those (upload :app:bundlePlayRelease)")
    errors += check_native_alignment(aab, "base/lib/")
    size_mb = aab.stat().st_size / 1_000_000
    print(
        f"  {aab.name}: {m.app_id} ({flavour or '?'}) {m.version_name} ({m.version_code}), {size_mb:.1f} MB, "
        f"{len(m.permission_names)} permissions (base manifest read with {how}):"
    )
    print_permissions(m)
    print_notes(m, flavour)
    if tag_version is not None and m.version_name != tag_version:
        errors.append(f"{aab.name}: versionName {m.version_name}, but the tag says {tag_version}")
    return errors


def check_tree(version: str) -> list[str]:
    errors: list[str] = []
    whats_new = (ROOT / "app/src/main/kotlin/dev/earshot/ui/whatsnew/WhatsNew.kt").read_text(encoding="utf-8")
    m = re.search(r'const val RELEASE = "([^"]+)"', whats_new)
    if not m or m.group(1) != version:
        errors.append(f"WhatsNew.RELEASE is {m.group(1) if m else '?'}, not {version}: write the sheet for this release")
    changelog = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
    if not re.search(rf"^## {re.escape(version)} — \d{{4}}-\d{{2}}-\d{{2}}\s*$", changelog, re.M):
        errors.append(f"CHANGELOG.md has no dated '## {version} - YYYY-MM-DD' section (em dash)")
    return errors


def check_lint(report: Path) -> list[str]:
    if not report.is_file():
        return [f"lint report {report} not found"]
    issues = ET.parse(report).getroot().findall("issue")
    fatal = [i for i in issues if i.get("severity") in ("Error", "Fatal")]
    for i in fatal[:20]:
        loc = i.find("location")
        where = f"{loc.get('file')}:{loc.get('line')}" if loc is not None else "?"
        print(f"  lint {i.get('severity')}: {i.get('id')} at {where}: {i.get('message')}")
    print(f"  lint: {len(issues)} issues, {len(fatal)} errors")
    return [f"lint reports {len(fatal)} errors"] if fatal else []


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--apk", action="append", default=[], type=Path)
    ap.add_argument(
        "--aab",
        action="append",
        default=[],
        type=Path,
        help="the bundle uploaded to Google Play (app/build/outputs/bundle/playRelease/app-play-release.aab): the APKs' manifest checks on its base module",
    )
    ap.add_argument("--tag", help="the release tag, e.g. v1.0.0 (enables the release-only checks)")
    ap.add_argument("--lint", type=Path, help="lint-results XML that must hold no errors")
    ap.add_argument(
        "--bridge",
        action="store_true",
        help="the APKs are the one-time 0.9.1 bridge: foss built with -Pearshot.legacyBridge=true (dev.earshot, debug-signed)",
    )
    ap.add_argument(
        "--installs-over",
        type=Path,
        metavar="OLD_APK",
        help="with --tag: each APK must update OLD_APK (same app ID and signing key, higher versionCode)",
    )
    args = ap.parse_args()

    tag_version = args.tag[1:] if args.tag and args.tag.startswith("v") else args.tag
    if (args.bridge or args.installs_over) and not args.apk:
        ap.error("--bridge and --installs-over check APKs: pass --apk")
    if args.bridge and args.aab:
        ap.error("--bridge checks the bridge APK only: check the .aab in a run of its own")
    if args.installs_over and not tag_version:
        ap.error("--installs-over needs --tag (it checks the signed release asset)")
    errors: list[str] = []
    aapt2 = find_tool("aapt2") if args.apk or args.aab else None
    if args.apk:
        if not aapt2:
            print("aapt2 not found (set ANDROID_HOME)")
            return 1
        apksigner = find_tool("apksigner")
        zipalign = find_tool("zipalign")
        over = None
        if args.installs_over:
            over, why = read_installed(args.installs_over, aapt2, apksigner)
            if over is None:
                print(why)
                return 1
            print(f"Installs over: {over.name}: {over.app_id} ({over.version_code})")
        print("APKs:" if not args.bridge else "APKs (the 0.9.1 bridge):")
        for apk in args.apk:
            errors += check_apk(apk, aapt2, apksigner, tag_version, args.bridge, over, zipalign)
    if args.aab:
        bundletool = find_bundletool()
        print("Bundles (Google Play):")
        for aab in args.aab:
            errors += check_aab(aab, aapt2, bundletool, tag_version)
    if tag_version:
        errors += check_tree(tag_version)
    if args.lint:
        errors += check_lint(args.lint)

    if errors:
        print("\nRelease gate FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1
    print("\nRelease gate passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
