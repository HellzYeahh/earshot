"""Tests for check_release.py, the release gate's checks on the APKs and the Play bundle (no Android SDK needed).

aapt2, apksigner, zipalign and bundletool are replaced by canned text, so the rules themselves are
tested: the flavour from the app ID, the bridge, the permissions (INTERNET, QUERY_ALL_PACKAGES,
AD_ID, billing, Shizuku in the Play build), 16 KB alignment, the certificate checks on a tag,
--installs-over and --aab. The canned dumps of a proto-format manifest are real aapt2 / bundletool
output for one test manifest, so both readers are held to the same answer. CI runs them before the
gate (.github/workflows/ci.yml):

    python3 -B -m unittest discover -s tools/release -p 'test_*.py'
"""

from __future__ import annotations

import contextlib
import io
import os
import struct
import subprocess
import tempfile
import unittest
import zipfile
from dataclasses import dataclass
from pathlib import Path
from unittest import mock

import check_release
from check_release import Installed, Manifest, Permission

FOSS = "dev.earshot.foss"
PLAY = "dev.earshot"
BILLING = "com.android.vending.BILLING"
INTERNET = "android.permission.INTERNET"
SHIZUKU = check_release.SHIZUKU_PERMISSION
SHIZUKU_PROVIDER = "rikka.shizuku.ShizukuProvider"
RELEASE_DIGEST = "aa" * 32
OTHER_DIGEST = "dd" * 32
DEBUG_DIGEST = "bb" * 32
NS = "http://schemas.android.com/apk/res/android"
CI_AAB = "app/build/outputs/bundle/playRelease/app-play-release.aab"


def certs(dn: str, digest: str) -> str:
    """What `apksigner verify --print-certs` prints for one signer."""
    return (
        f"Signer #1 certificate DN: {dn}\n"
        f"Signer #1 certificate SHA-256 digest: {digest}\n"
        f"Signer #1 certificate SHA-1 digest: {'cc' * 20}\n"
    )


RELEASE_CERTS = certs("CN=Earshot, O=Earshot", RELEASE_DIGEST)
DEBUG_CERTS = certs("CN=Android Debug, O=Android, C=US", DEBUG_DIGEST)

# `zipalign -c -P 16 -v 4`, real output (build-tools 36.0.0).
ZIPALIGN_OK = (
    "Verifying alignment of app.apk (4)...\n"
    " 5029888 lib/arm64-v8a/libandroidx.graphics.path.so (OK)\n"
    "11943660 resources.arsc (OK)\n"
    "Verification successful\n"
)
ZIPALIGN_BAD = (
    "Verifying alignment of bad.apk (4)...\n"
    "      49 AndroidManifest.xml (BAD - 1)\n"
    "     109 lib/arm64-v8a/libfoo.so (BAD - 109)\n"
    "Verification FAILED\n"
)
# A zipalign from before build-tools 35 doesn't know -P and prints its usage.
ZIPALIGN_OLD = (
    "zipalign: invalid option -- 'P'\n"
    "Zip alignment utility\n"
    "Usage: zipalign [-f] [-p] [-v] [-z] <align> infile.zip outfile.zip\n"
)

# Real aapt2 (build-tools 36.0.0) and bundletool (1.18.3, from the Gradle cache) output for one test
# manifest compiled with `aapt2 link --proto-format`, the format of a bundle's base manifest.
PROTO_BADGING = """\
package: name='dev.earshot' versionCode='13' versionName='1.0.0' platformBuildVersionName='16' platformBuildVersionCode='36' compileSdkVersion='36' compileSdkVersionCodename='16'
minSdkVersion:'28'
targetSdkVersion:'36'
uses-permission: name='android.permission.BLUETOOTH' maxSdkVersion='30'
uses-permission: name='android.permission.BLUETOOTH_CONNECT'
uses-permission: name='moe.shizuku.manager.permission.API_V23'
uses-permission-sdk-23: name='android.permission.ACCESS_FINE_LOCATION'
application-label:'Earshot'
application: label='Earshot' icon=''
application-debuggable
feature-group: label=''
  uses-feature: name='android.hardware.bluetooth'
  uses-implied-feature: name='android.hardware.bluetooth' reason='requested android.permission.BLUETOOTH permission, and targetSdkVersion > 4'
supports-screens: 'small' 'normal' 'large' 'xlarge'
"""
PROTO_TREE = """\
N: android=http://schemas.android.com/apk/res/android (line=2)
  E: manifest (line=2)
    A: package="dev.earshot" (Raw: "dev.earshot")
    A: http://schemas.android.com/apk/res/android:versionCode(0x0101021b)=13 (Raw: "13")
    A: http://schemas.android.com/apk/res/android:versionName(0x0101021c)="1.0.0" (Raw: "1.0.0")
      T: '\\n    '
      E: uses-sdk (line=3)
        A: http://schemas.android.com/apk/res/android:minSdkVersion(0x0101020c)=28 (Raw: "28")
        A: http://schemas.android.com/apk/res/android:targetSdkVersion(0x01010270)=36 (Raw: "36")
      E: uses-permission (line=4)
        A: http://schemas.android.com/apk/res/android:maxSdkVersion(0x01010271)=30 (Raw: "30")
        A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.BLUETOOTH" (Raw: "android.permission.BLUETOOTH")
      E: uses-permission (line=6)
        A: http://schemas.android.com/apk/res/android:name(0x01010003)="moe.shizuku.manager.permission.API_V23" (Raw: "moe.shizuku.manager.permission.API_V23")
      E: queries (line=8)
          T: '\\n        '
          E: package (line=9)
            A: http://schemas.android.com/apk/res/android:name(0x01010003)="moe.shizuku.privileged.api" (Raw: "moe.shizuku.privileged.api")
          E: package (line=10)
            A: http://schemas.android.com/apk/res/android:name(0x01010003)="dev.earshot" (Raw: "dev.earshot")
      E: application (line=12)
        A: http://schemas.android.com/apk/res/android:debuggable(0x0101000f)=true (Raw: "true")
        A: http://schemas.android.com/apk/res/android:label(0x01010001)=@string/app_name (Raw: "@string/app_name")
          T: '\\n        '
          E: provider (line=13)
            A: http://schemas.android.com/apk/res/android:authorities(0x01010018)="dev.earshot.shizuku" (Raw: "dev.earshot.shizuku")
            A: http://schemas.android.com/apk/res/android:exported(0x01010010)=true (Raw: "true")
            A: http://schemas.android.com/apk/res/android:name(0x01010003)="rikka.shizuku.ShizukuProvider" (Raw: "rikka.shizuku.ShizukuProvider")
      T: '\\n'
"""
BUNDLETOOL_XML = """\
<manifest xmlns:android="http://schemas.android.com/apk/res/android" android:compileSdkVersion="36" android:compileSdkVersionCodename="16" android:versionCode="13" android:versionName="1.0.0" package="dev.earshot" platformBuildVersionCode="36" platformBuildVersionName="16">

  <uses-sdk android:minSdkVersion="28" android:targetSdkVersion="36"/>

  <uses-permission android:maxSdkVersion="30" android:name="android.permission.BLUETOOTH"/>

  <uses-permission android:name="android.permission.BLUETOOTH_CONNECT"/>

  <uses-permission android:name="moe.shizuku.manager.permission.API_V23"/>

  <uses-permission-sdk-23 android:name="android.permission.ACCESS_FINE_LOCATION"/>

  <queries>
    <package android:name="moe.shizuku.privileged.api"/>
    <package android:name="dev.earshot"/>
  </queries>

  <application android:debuggable="true" android:label="@string/app_name">
    <provider android:authorities="dev.earshot.shizuku" android:exported="true" android:name="rikka.shizuku.ShizukuProvider"/>
  </application>

</manifest>
"""
# A real binary (APK) dump, cut: attributes in resource-ID order, and elements whose name attribute
# must not count as a provider or a lookup (the application, a meta-data, an intent's action).
APK_TREE = """\
N: android=http://schemas.android.com/apk/res/android (line=2)
  E: manifest (line=2)
    A: package="dev.earshot" (Raw: "dev.earshot")
      E: queries (line=89)
          E: intent (line=96)
              E: action (line=97)
                A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.intent.action.MAIN" (Raw: "android.intent.action.MAIN")
          E: package (line=101)
            A: http://schemas.android.com/apk/res/android:name(0x01010003)="moe.shizuku.privileged.api" (Raw: "moe.shizuku.privileged.api")
      E: application (line=149)
        A: http://schemas.android.com/apk/res/android:label(0x01010001)=@0x7f0f0034
        A: http://schemas.android.com/apk/res/android:name(0x01010003)="dev.earshot.EarshotApp" (Raw: "dev.earshot.EarshotApp")
        A: http://schemas.android.com/apk/res/android:debuggable(0x0101000f)=(type 0x12)0xffffffff
          E: provider (line=338)
            A: http://schemas.android.com/apk/res/android:name(0x01010003)="rikka.shizuku.ShizukuProvider" (Raw: "rikka.shizuku.ShizukuProvider")
            A: http://schemas.android.com/apk/res/android:authorities(0x01010018)="dev.earshot.shizuku" (Raw: "dev.earshot.shizuku")
          E: provider (line=386)
            A: http://schemas.android.com/apk/res/android:name(0x01010003)="androidx.startup.InitializationProvider" (Raw: "androidx.startup.InitializationProvider")
              E: meta-data (line=390)
                A: http://schemas.android.com/apk/res/android:name(0x01010003)="androidx.work.WorkManagerInitializer" (Raw: "androidx.work.WorkManagerInitializer")
      E: uses-feature (line=400)
        A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.hardware.bluetooth" (Raw: "android.hardware.bluetooth")
"""


def badging(app_id: str, version_code: int, version_name: str, perms: tuple[str, ...], debuggable: bool) -> str:
    """What `aapt2 dump badging` prints, cut to the lines the gate reads."""
    lines = [f"package: name='{app_id}' versionCode='{version_code}' versionName='{version_name}' compileSdkVersion='37'"]
    lines += [f"uses-permission: name='{p}'" for p in perms]
    lines.append("application-label:'Earshot'")
    if debuggable:
        lines.append("application-debuggable")
    return "\n".join(lines) + "\n"


def name_attr(indent: int, name: str, rid: str, value: str) -> str:
    return " " * indent + f'A: {NS}:{name}({rid})="{value}" (Raw: "{value}")'


def tree(app_id: str, looks_up: tuple[str, ...] = (), providers: tuple[str, ...] = ()) -> str:
    """What `aapt2 dump xmltree --file AndroidManifest.xml` prints for an APK, cut to the elements the gate reads."""
    lines = [f"N: android={NS} (line=2)", "  E: manifest (line=2)", f'    A: package="{app_id}" (Raw: "{app_id}")']
    if looks_up:
        lines.append("      E: queries (line=10)")
        for p in looks_up:
            lines += ["          E: package (line=11)", name_attr(12, "name", "0x01010003", p)]
    lines += ["      E: application (line=20)", name_attr(8, "name", "0x01010003", "dev.earshot.EarshotApp")]
    for p in providers:
        lines += ["          E: provider (line=30)", name_attr(12, "name", "0x01010003", p), name_attr(12, "authorities", "0x01010018", f"{app_id}.x")]
    return "\n".join(lines) + "\n"


def manifest_xml(
    app_id: str, version_code: int, version_name: str, perms: tuple[str, ...], debuggable: bool, providers: tuple[str, ...] = ()
) -> str:
    """What `bundletool dump manifest` prints, cut to the elements the gate reads."""
    lines = [f'<manifest xmlns:android="{NS}" android:versionCode="{version_code}" android:versionName="{version_name}" package="{app_id}">']
    lines += [f'  <uses-permission android:name="{p}"/>' for p in perms]
    flag = ' android:debuggable="true"' if debuggable else ""
    lines.append(f'  <application android:label="@string/app_name"{flag}>')
    lines += [f'    <provider android:authorities="{app_id}.x" android:name="{p}"/>' for p in providers]
    lines += ["  </application>", "</manifest>"]
    return "\n".join(lines) + "\n"


def elf64(*load_aligns: int, little: bool = True) -> bytes:
    """A 64-bit ELF header and program headers: a PT_PHDR, then one PT_LOAD per alignment."""
    e = "<" if little else ">"
    phoff, phentsize, phnum = 64, 56, len(load_aligns) + 1
    header = bytearray(64)
    header[:7] = b"\x7fELF" + bytes([2, 1 if little else 2, 1])
    struct.pack_into(e + "HHIQQQIHHHHHH", header, 16, 3, 183, 1, 0, phoff, 0, 0, 64, phentsize, phnum, 0, 0, 0)
    segments = ((6, 8),) + tuple((check_release.PT_LOAD, a) for a in load_aligns)
    return bytes(header) + b"".join(struct.pack(e + "IIQQQQQQ", t, 5, 0, 0, 0, 0x100, 0x100, a) for t, a in segments)


ELF32 = b"\x7fELF" + bytes([1, 1, 1]) + bytes(57)


def write_zip(path: Path, entries: dict[str, bytes]) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w") as z:
        for name, data in entries.items():
            z.writestr(name, data)
    return path


@dataclass
class FakeApk:
    badging: str
    tree: str
    verify_passes: bool
    certs: str
    aligned: bool
    align_output: str


@dataclass
class FakeBundle:
    badging: str
    tree: str
    xml: str
    aapt2_fails: bool


class GateTestCase(unittest.TestCase):
    def setUp(self) -> None:
        tmp = tempfile.TemporaryDirectory(prefix="gate-")
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.fakes: dict[str, FakeApk] = {}
        self.bundles: dict[str, FakeBundle] = {}
        self.mocks: dict[str, mock.MagicMock] = {}
        self.out = ""

        def aapt2_dumps(aapt2: str, aab: Path) -> tuple[str, str]:
            fake = self.bundles[str(aab)]
            if fake.aapt2_fails:
                raise subprocess.CalledProcessError(1, [aapt2], output="", stderr="error: failed to open APK\n")
            return fake.badging, fake.tree

        for name, fake in (
            ("badging", lambda aapt2, apk: self.fakes[str(apk)].badging),
            ("xmltree", lambda aapt2, apk: self.fakes[str(apk)].tree),
            ("verify_signature", lambda apksigner, apk: (self.fakes[str(apk)].verify_passes, self.fakes[str(apk)].certs)),
            ("verify_alignment", lambda zipalign, apk: (self.fakes[str(apk)].aligned, self.fakes[str(apk)].align_output)),
            ("bundle_aapt2_dumps", aapt2_dumps),
            ("bundletool_manifest", lambda bundletool, aab: self.bundles[str(aab)].xml),
        ):
            patcher = mock.patch.object(check_release, name, side_effect=fake)
            self.mocks[name] = patcher.start()
            self.addCleanup(patcher.stop)

    def apk(
        self,
        rel: str,
        app_id: str,
        version_code: int = 12,
        version_name: str = "1.0.0",
        perms: tuple[str, ...] = (),
        debuggable: bool = False,
        certs: str = RELEASE_CERTS,
        verify_passes: bool = True,
        foss_manifest: bool | None = None,
        providers: tuple[str, ...] = (),
        looks_up: tuple[str, ...] = (),
        libs: dict[str, bytes] | None = None,
        aligned: bool = True,
        align_output: str = ZIPALIGN_OK,
    ) -> Path:
        """A small zip at rel under the temp folder, with canned aapt2/apksigner/zipalign answers."""
        path = write_zip(self.root / rel, {"classes.dex": b"dex\n035\0", **(libs or {})})
        if foss_manifest is None:
            foss_manifest = app_id == FOSS  # only the foss flavour's manifest looks up dev.earshot
        looks_up = looks_up + ((check_release.LEGACY_LOOKUP,) if foss_manifest else ())
        self.fakes[str(path)] = FakeApk(
            badging(app_id, version_code, version_name, perms, debuggable), tree(app_id, looks_up, providers), verify_passes, certs, aligned, align_output
        )
        return path

    def aab(
        self,
        rel: str = CI_AAB,
        app_id: str = PLAY,
        version_code: int = 12,
        version_name: str = "1.0.0",
        perms: tuple[str, ...] = (),
        debuggable: bool = False,
        providers: tuple[str, ...] = (),
        libs: dict[str, bytes] | None = None,
        aapt2_fails: bool = False,
    ) -> Path:
        """A small bundle at rel, with the same canned manifest from aapt2 and from bundletool."""
        path = write_zip(self.root / rel, {check_release.BUNDLE_MANIFEST: b"\n\x00", check_release.BUNDLE_RESOURCES: b"\n\x00", **(libs or {})})
        self.bundles[str(path)] = FakeBundle(
            badging(app_id, version_code, version_name, perms, debuggable),
            tree(app_id, providers=providers),
            manifest_xml(app_id, version_code, version_name, perms, debuggable, providers),
            aapt2_fails,
        )
        return path

    def check(
        self,
        apk: Path,
        tag: str | None = None,
        bridge: bool = False,
        over: Installed | None = None,
        apksigner: str | None = "apksigner",
        zipalign: str | None = "zipalign",
    ) -> list[str]:
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            errors = check_release.check_apk(apk, "aapt2", apksigner, tag, bridge, over, zipalign)
        self.out = buf.getvalue()
        return errors

    def check_bundle(
        self, aab: Path, tag: str | None = None, aapt2: str | None = "aapt2", bundletool: list[str] | None = None
    ) -> list[str]:
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            errors = check_release.check_aab(aab, aapt2, bundletool, tag)
        self.out = buf.getvalue()
        return errors

    def assertFailsWith(self, errors: list[str], *needles: str) -> None:
        for needle in needles:
            self.assertTrue(any(needle in e for e in errors), f"no error mentions {needle!r}: {errors}")


class PathFlavourTest(unittest.TestCase):
    def test_gradle_outputs_name_their_flavour(self) -> None:
        self.assertEqual(check_release.path_flavour(Path("app/build/outputs/apk/foss/release/app-foss-release-unsigned.apk")), "foss")
        self.assertEqual(check_release.path_flavour(Path("app/build/outputs/apk/play/release/app-play-release-unsigned.apk")), "play")
        self.assertEqual(check_release.path_flavour(Path(CI_AAB)), "play")

    def test_the_github_asset_name_is_foss(self) -> None:
        self.assertEqual(check_release.path_flavour(Path("app/build/earshot-1.0.0.apk")), "foss")
        self.assertEqual(check_release.path_flavour(Path("earshot-0.9.1.apk")), "foss")

    def test_any_other_name_names_nothing(self) -> None:
        self.assertIsNone(check_release.path_flavour(Path("app/build/something.apk")))
        self.assertIsNone(check_release.path_flavour(Path("app/build/earshot-1.0.0-rc1.apk")))


class ManifestReadingTest(unittest.TestCase):
    EXPECTED = Manifest(
        app_id=PLAY,
        version_code="13",
        version_name="1.0.0",
        permissions=(
            Permission("android.permission.BLUETOOTH", "maxSdkVersion=30"),
            Permission("android.permission.BLUETOOTH_CONNECT"),
            Permission(SHIZUKU),
            Permission("android.permission.ACCESS_FINE_LOCATION", "API 23+"),
        ),
        debuggable=True,
        providers=(SHIZUKU_PROVIDER,),
        looks_up=frozenset({"moe.shizuku.privileged.api", "dev.earshot"}),
    )

    def test_aapt2_reads_a_proto_manifest(self) -> None:
        self.assertEqual(check_release.manifest_from_aapt2(PROTO_BADGING, PROTO_TREE), self.EXPECTED)

    def test_bundletool_reads_the_same_manifest(self) -> None:
        self.assertEqual(check_release.manifest_from_xml(BUNDLETOOL_XML), self.EXPECTED)

    def test_binary_tree_counts_only_providers_and_query_packages(self) -> None:
        elements = check_release.parse_xmltree(APK_TREE)
        m = check_release.manifest_from_aapt2(badging(PLAY, 12, "1.0.0", (), False), APK_TREE)
        assert m is not None
        self.assertEqual(m.providers, (SHIZUKU_PROVIDER, "androidx.startup.InitializationProvider"))
        self.assertEqual(m.looks_up, frozenset({"moe.shizuku.privileged.api"}))
        meta = [a for path, a in elements if path[-1] == "meta-data"]
        self.assertEqual(meta, [{"name": "androidx.work.WorkManagerInitializer"}])
        app = next(a for path, a in elements if path == ("manifest", "application"))
        self.assertEqual(app["debuggable"], "(type 0x12)0xffffffff")

    def test_no_package_line_reads_nothing(self) -> None:
        self.assertIsNone(check_release.manifest_from_aapt2("ERROR: dump failed\n", ""))
        self.assertIsNone(check_release.manifest_from_xml("Error: not a bundle"))
        self.assertIsNone(check_release.manifest_from_xml("<resources/>"))


class FlavourFromAppIdTest(GateTestCase):
    def test_foss_app_id_is_the_github_build(self) -> None:
        self.assertEqual(self.check(self.apk("out/app.apk", FOSS)), [])
        # Classified as foss, so the GitHub billing check applies.
        self.assertFailsWith(self.check(self.apk("out/billing.apk", FOSS, perms=(BILLING,))), f"the GitHub build declares {BILLING}")

    def test_play_app_id_is_the_play_build(self) -> None:
        self.assertEqual(self.check(self.apk("out/app.apk", PLAY, perms=(BILLING,))), [])

    def test_unknown_app_id_fails(self) -> None:
        self.assertFailsWith(self.check(self.apk("out/app.apk", "dev.example")), "app ID dev.example is neither")

    def test_ci_outputs_of_both_flavours_pass(self) -> None:
        foss = self.apk("app/build/outputs/apk/foss/release/app-foss-release-unsigned.apk", FOSS)
        play = self.apk("app/build/outputs/apk/play/release/app-play-release-unsigned.apk", PLAY)
        self.assertEqual(self.check(foss), [])
        self.assertEqual(self.check(play), [])

    def test_foss_path_with_the_play_app_id_fails_without_bridge(self) -> None:
        apk = self.apk("app/build/outputs/apk/foss/release/app-foss-release.apk", PLAY, foss_manifest=True)
        self.assertFailsWith(self.check(apk), "built as foss, but its app ID dev.earshot is the play build's", "--bridge")

    def test_play_build_under_the_github_asset_name_fails(self) -> None:
        # The X05 case: a signed play build copied to the GitHub asset name, later with billing.
        apk = self.apk("app/build/earshot-1.0.0.apk", PLAY, perms=(BILLING,))
        self.assertFailsWith(self.check(apk, tag="1.0.0"), "built as foss, but its app ID dev.earshot is the play build's")

    def test_foss_build_under_the_github_asset_name_passes(self) -> None:
        self.assertEqual(self.check(self.apk("app/build/earshot-1.0.0.apk", FOSS), tag="1.0.0"), [])


class PermissionsTest(GateTestCase):
    def test_internet_query_all_packages_and_ad_id_fail_in_every_build(self) -> None:
        for app_id in (FOSS, PLAY):
            apk = self.apk(f"out/{app_id}.apk", app_id, perms=(INTERNET, check_release.QUERY_ALL_PACKAGES, check_release.AD_ID))
            self.assertFailsWith(
                self.check(apk), f"declares {INTERNET}", f"declares {check_release.QUERY_ALL_PACKAGES}", f"declares {check_release.AD_ID}"
            )

    def test_every_permission_is_listed(self) -> None:
        info = badging(PLAY, 12, "1.0.0", (), False) + (
            "uses-permission: name='android.permission.BLUETOOTH' maxSdkVersion='30'\n"
            "uses-permission: name='android.permission.BLUETOOTH_SCAN' usesPermissionFlags='neverForLocation'\n"
            "uses-permission-sdk-23: name='android.permission.ACCESS_FINE_LOCATION'\n"
            "uses-implied-permission: name='android.permission.READ_EXTERNAL_STORAGE' reason='x'\n"
        )
        apk = self.apk("out/app.apk", PLAY, perms=("android.permission.RECORD_AUDIO",))
        self.fakes[str(apk)].badging = info
        self.assertEqual(self.check(apk), [])
        listed = [line.strip() for line in self.out.splitlines() if line.startswith("      ")]
        self.assertEqual(
            listed,
            [
                "android.permission.ACCESS_FINE_LOCATION (API 23+)",
                "android.permission.BLUETOOTH (maxSdkVersion=30)",
                "android.permission.BLUETOOTH_SCAN (usesPermissionFlags=neverForLocation)",
            ],
        )
        self.assertIn("3 permissions", self.out)

    def test_permission_list_of_a_plain_apk(self) -> None:
        self.check(self.apk("out/app.apk", FOSS, perms=("android.permission.VIBRATE", "android.permission.BLUETOOTH_CONNECT")))
        self.assertIn("      android.permission.BLUETOOTH_CONNECT\n      android.permission.VIBRATE\n", self.out)


class ShizukuTest(GateTestCase):
    def test_play_build_with_the_shizuku_permission_fails(self) -> None:
        apk = self.apk("app/build/outputs/apk/play/release/app-play-release-unsigned.apk", PLAY, perms=(SHIZUKU,))
        self.assertFailsWith(self.check(apk), f"the Google Play build declares {SHIZUKU}")

    def test_play_build_with_any_shizuku_permission_fails(self) -> None:
        self.assertFailsWith(self.check(self.apk("out/app.apk", PLAY, perms=("moe.shizuku.manager.permission.API",))), "moe.shizuku.manager.permission.API")

    def test_play_build_with_a_shizuku_provider_fails(self) -> None:
        for provider in (SHIZUKU_PROVIDER, "moe.shizuku.api.ShizukuProvider"):
            apk = self.apk(f"out/{provider}.apk", PLAY, providers=(provider, "androidx.startup.InitializationProvider"))
            errors = self.check(apk)
            self.assertFailsWith(errors, f"the Shizuku provider {provider}")
            self.assertEqual(len(errors), 1, errors)

    def test_github_build_keeps_shizuku(self) -> None:
        apk = self.apk("app/build/outputs/apk/foss/release/app-foss-release-unsigned.apk", FOSS, perms=(SHIZUKU,), providers=(SHIZUKU_PROVIDER,))
        self.assertEqual(self.check(apk), [])

    def test_bridge_keeps_shizuku(self) -> None:
        apk = self.apk("app/build/earshot-0.9.1.apk", PLAY, 11, "0.9.1", perms=(SHIZUKU,), providers=(SHIZUKU_PROVIDER,), certs=DEBUG_CERTS, foss_manifest=True)
        self.assertEqual(self.check(apk, tag="0.9.1", bridge=True), [])

    def test_shizuku_manager_lookup_in_play_is_only_a_note(self) -> None:
        apk = self.apk("out/app.apk", PLAY, looks_up=(check_release.SHIZUKU_MANAGER,))
        self.assertEqual(self.check(apk), [])
        self.assertIn(f"note: the Google Play build still looks up {check_release.SHIZUKU_MANAGER}", self.out)
        self.check(self.apk("out/foss.apk", FOSS, looks_up=(check_release.SHIZUKU_MANAGER,)))
        self.assertNotIn("note:", self.out)


class NativeAlignmentTest(GateTestCase):
    def test_elf_load_alignment(self) -> None:
        self.assertEqual(check_release.elf_load_alignment(elf64(0x4000, 0x4000)), 0x4000)
        self.assertEqual(check_release.elf_load_alignment(elf64(0x4000, 0x1000)), 0x1000)
        self.assertEqual(check_release.elf_load_alignment(elf64(0x10000, little=False)), 0x10000)
        self.assertIsNone(check_release.elf_load_alignment(ELF32))
        self.assertIsNone(check_release.elf_load_alignment(b"not an ELF file at all, but long enough to read a header" * 2))
        self.assertIsNone(check_release.elf_load_alignment(elf64(0x4000)[:80]))  # cut off in its program headers

    def test_16_kb_libraries_pass(self) -> None:
        libs = {"lib/arm64-v8a/libfoo.so": elf64(0x4000, 0x4000), "lib/x86_64/libfoo.so": elf64(0x10000)}
        self.assertEqual(self.check(self.apk("out/app.apk", PLAY, libs=libs)), [])

    def test_4_kb_library_fails(self) -> None:
        apk = self.apk("out/app.apk", PLAY, libs={"lib/arm64-v8a/libold.so": elf64(0x4000, 0x1000)})
        self.assertFailsWith(self.check(apk), "lib/arm64-v8a/libold.so has 4096 byte LOAD alignment, not 16 KB")

    def test_32_bit_abis_are_not_checked(self) -> None:
        libs = {"lib/armeabi-v7a/libfoo.so": ELF32, "lib/x86/libfoo.so": ELF32, "lib/armeabi-v7a/libbar.so": elf64(0x1000)}
        self.assertEqual(self.check(self.apk("out/app.apk", PLAY, libs=libs)), [])

    def test_a_64_bit_abi_holding_something_else_fails(self) -> None:
        apk = self.apk("out/app.apk", PLAY, libs={"lib/arm64-v8a/libfoo.so": ELF32})
        self.assertFailsWith(self.check(apk), "lib/arm64-v8a/libfoo.so isn't a 64-bit ELF library")

    def test_not_a_zip_fails(self) -> None:
        path = self.root / "broken.apk"
        path.write_bytes(b"PK\x03\x04 not a real APK")
        self.assertEqual(check_release.check_native_alignment(path, "lib/"), ["broken.apk: not a zip file"])


class ZipAlignTest(GateTestCase):
    def test_aligned_signed_apk_passes(self) -> None:
        self.assertEqual(self.check(self.apk("app/build/earshot-1.0.0.apk", FOSS), tag="1.0.0"), [])
        self.mocks["verify_alignment"].assert_called_once()

    def test_misaligned_apk_fails_with_the_bad_entries(self) -> None:
        apk = self.apk("app/build/earshot-1.0.0.apk", FOSS, aligned=False, align_output=ZIPALIGN_BAD)
        self.assertFailsWith(self.check(apk, tag="1.0.0"), "zipalign -c -P 16 -v 4 failed", "lib/arm64-v8a/libfoo.so (BAD - 109)")

    def test_signed_apk_needs_zipalign(self) -> None:
        self.assertFailsWith(self.check(self.apk("app/build/earshot-1.0.0.apk", FOSS), tag="1.0.0", zipalign=None), "zipalign not found")

    def test_old_zipalign_is_named(self) -> None:
        apk = self.apk("app/build/earshot-1.0.0.apk", FOSS, aligned=False, align_output=ZIPALIGN_OLD)
        self.assertFailsWith(self.check(apk, tag="1.0.0"), "build-tools 35 or later")

    def test_only_signed_apks_on_a_tag_are_checked(self) -> None:
        unsigned = self.apk("app/build/outputs/apk/play/release/app-play-release-unsigned.apk", PLAY, aligned=False, align_output=ZIPALIGN_BAD)
        self.assertEqual(self.check(unsigned, tag="1.0.0", zipalign=None), [])
        self.assertEqual(self.check(self.apk("app/build/earshot-1.0.0.apk", FOSS, aligned=False, align_output=ZIPALIGN_BAD)), [])
        self.mocks["verify_alignment"].assert_not_called()


class BridgeTest(GateTestCase):
    def bridge_apk(self, app_id: str = PLAY, **kw) -> Path:
        """The 0.9.1 bridge as step 4 builds it, with kw changing one thing."""
        args = dict(version_code=11, version_name="0.9.1", certs=DEBUG_CERTS, foss_manifest=True)
        args.update(kw)
        return self.apk("app/build/earshot-0.9.1.apk", app_id, **args)

    def test_the_real_bridge_passes(self) -> None:
        over = Installed("earshot-0.9.0.apk", PLAY, 10, frozenset({DEBUG_DIGEST}))
        self.assertEqual(self.check(self.bridge_apk(), tag="0.9.1", bridge=True, over=over), [])

    def test_bridge_needs_the_old_app_id(self) -> None:
        self.assertFailsWith(self.check(self.bridge_apk(app_id=FOSS), tag="0.9.1", bridge=True), "the bridge must be dev.earshot")

    def test_bridge_needs_the_foss_manifest(self) -> None:
        self.assertFailsWith(self.check(self.bridge_apk(foss_manifest=False), tag="0.9.1", bridge=True), "this is the play build")

    def test_bridge_from_a_play_path_fails(self) -> None:
        apk = self.apk("app/build/outputs/apk/play/release/app-play-release.apk", PLAY, version_code=11, version_name="0.9.1", certs=DEBUG_CERTS, foss_manifest=True)
        self.assertFailsWith(self.check(apk, tag="0.9.1", bridge=True), "this is the play build")

    def test_bridge_keeps_the_github_billing_check(self) -> None:
        self.assertFailsWith(self.check(self.bridge_apk(perms=(BILLING,)), tag="0.9.1", bridge=True), f"the GitHub build declares {BILLING}")

    def test_bridge_must_be_debug_signed_on_a_tag(self) -> None:
        self.assertFailsWith(self.check(self.bridge_apk(certs=RELEASE_CERTS), tag="0.9.1", bridge=True), "must be signed with the debug key")

    def test_bridge_with_another_key_than_0_9_0_fails(self) -> None:
        over = Installed("earshot-0.9.0.apk", PLAY, 10, frozenset({OTHER_DIGEST}))
        self.assertFailsWith(self.check(self.bridge_apk(), tag="0.9.1", bridge=True, over=over), "signed with a different key than earshot-0.9.0.apk")


class SignatureOnTagTest(GateTestCase):
    def test_signed_release_passes(self) -> None:
        self.assertEqual(self.check(self.apk("app/build/earshot-1.0.0.apk", FOSS), tag="1.0.0"), [])

    def test_debug_certificate_fails_outside_the_bridge(self) -> None:
        apk = self.apk("app/build/earshot-1.0.0.apk", FOSS, certs=DEBUG_CERTS)
        self.assertFailsWith(self.check(apk, tag="1.0.0"), "signed with a debug certificate")

    def test_signed_apk_needs_apksigner(self) -> None:
        apk = self.apk("app/build/earshot-1.0.0.apk", FOSS)
        self.assertFailsWith(self.check(apk, tag="1.0.0", apksigner=None), "apksigner not found")

    def test_failed_verify_fails(self) -> None:
        apk = self.apk("app/build/earshot-1.0.0.apk", FOSS, verify_passes=False, certs="DOES NOT VERIFY\nERROR: JAR signer CERT.RSA: no signature\n")
        self.assertFailsWith(self.check(apk, tag="1.0.0"), "apksigner verify failed")

    def test_unsigned_apk_skips_the_certificate_checks(self) -> None:
        apk = self.apk("app/build/outputs/apk/foss/release/app-foss-release-unsigned.apk", FOSS, verify_passes=False, certs="")
        self.assertEqual(self.check(apk, tag="1.0.0", apksigner=None), [])
        self.mocks["verify_signature"].assert_not_called()
        self.mocks["verify_alignment"].assert_not_called()

    def test_debuggable_apk_fails_on_a_tag(self) -> None:
        apk = self.apk("app/build/outputs/apk/foss/debug/app-foss-debug.apk", FOSS, debuggable=True)
        self.assertEqual(self.check(apk), [])  # fine for a local check without a tag
        self.assertFailsWith(self.check(apk, tag="1.0.0"), "a debuggable build")

    def test_version_name_must_match_the_tag(self) -> None:
        apk = self.apk("app/build/earshot-1.0.0.apk", FOSS, version_name="1.0.0-dev")
        self.assertFailsWith(self.check(apk, tag="1.0.0"), "versionName 1.0.0-dev, but the tag says 1.0.0")


class InstallsOverTest(GateTestCase):
    def previous(self) -> Installed:
        return Installed("earshot-1.0.0.apk", FOSS, 12, frozenset({RELEASE_DIGEST}))

    def test_read_installed_takes_app_id_code_and_signer(self) -> None:
        old = self.apk("app/build/earshot-1.0.0.apk", FOSS, version_code=12)
        with contextlib.redirect_stdout(io.StringIO()):
            installed, why = check_release.read_installed(old, "aapt2", "apksigner")
        self.assertIsNone(why)
        self.assertEqual(installed, self.previous())

    def test_read_installed_needs_apksigner_and_a_signer(self) -> None:
        old = self.apk("app/build/earshot-1.0.0.apk", FOSS)
        self.assertIsNone(check_release.read_installed(old, "aapt2", None)[0])
        unsigned = self.apk("app/build/earshot-0.0.1.apk", FOSS, verify_passes=False, certs="")
        installed, why = check_release.read_installed(unsigned, "aapt2", "apksigner")
        self.assertIsNone(installed)
        self.assertIn("can't read its signer", why or "")

    def test_a_real_update_passes(self) -> None:
        apk = self.apk("app/build/earshot-1.0.1.apk", FOSS, version_code=13, version_name="1.0.1")
        self.assertEqual(self.check(apk, tag="1.0.1", over=self.previous()), [])

    def test_other_app_id_fails(self) -> None:
        over = Installed("earshot-0.9.0.apk", PLAY, 10, frozenset({RELEASE_DIGEST}))
        apk = self.apk("app/build/earshot-1.0.1.apk", FOSS, version_code=13, version_name="1.0.1")
        self.assertFailsWith(self.check(apk, tag="1.0.1", over=over), "so it installs next to it, not over it")

    def test_other_signer_fails(self) -> None:
        apk = self.apk("app/build/earshot-1.0.1.apk", FOSS, version_code=13, version_name="1.0.1", certs=certs("CN=Someone else", OTHER_DIGEST))
        self.assertFailsWith(self.check(apk, tag="1.0.1", over=self.previous()), "signed with a different key than earshot-1.0.0.apk")

    def test_version_code_not_above_fails(self) -> None:
        apk = self.apk("app/build/earshot-1.0.1.apk", FOSS, version_code=12, version_name="1.0.1")
        self.assertFailsWith(self.check(apk, tag="1.0.1", over=self.previous()), "versionCode 12 isn't above earshot-1.0.0.apk's 12")


class BundleTest(GateTestCase):
    def test_the_ci_bundle_passes(self) -> None:
        self.assertEqual(self.check_bundle(self.aab(perms=("android.permission.RECORD_AUDIO",)), tag="1.0.0"), [])
        self.assertIn("read with aapt2", self.out)
        self.assertIn("      android.permission.RECORD_AUDIO\n", self.out)
        self.mocks["bundletool_manifest"].assert_not_called()

    def test_bundletool_reads_it_without_aapt2(self) -> None:
        self.assertEqual(self.check_bundle(self.aab(), aapt2=None, bundletool=["bundletool"]), [])
        self.assertIn("read with bundletool", self.out)
        self.mocks["bundle_aapt2_dumps"].assert_not_called()

    def test_bundletool_takes_over_when_aapt2_fails(self) -> None:
        self.assertEqual(self.check_bundle(self.aab(aapt2_fails=True), bundletool=["bundletool"]), [])
        self.assertIn("read with bundletool", self.out)

    def test_without_either_tool_it_fails_and_says_what_to_set(self) -> None:
        errors = self.check_bundle(self.aab(), aapt2=None, bundletool=None)
        self.assertFailsWith(errors, "can't read its manifest", "aapt2 not found (set ANDROID_HOME)", "set BUNDLETOOL to bundletool-all.jar")
        errors = self.check_bundle(self.aab(aapt2_fails=True), bundletool=None)
        self.assertFailsWith(errors, "aapt2 can't dump the base manifest (error: failed to open APK)", "bundletool not found")

    def test_bundletool_that_cant_run_fails_clearly(self) -> None:
        self.mocks["bundletool_manifest"].side_effect = FileNotFoundError(2, "No such file or directory", "java")
        self.assertFailsWith(self.check_bundle(self.aab(), aapt2=None, bundletool=["java", "-jar", "x.jar"]), "bundletool can't run")

    def test_it_must_be_the_play_build(self) -> None:
        aab = self.aab("app/build/outputs/bundle/fossRelease/app-foss-release.aab", app_id=FOSS)
        self.assertFailsWith(self.check_bundle(aab), "app ID dev.earshot.foss, but the bundle goes to Google Play")
        self.assertFailsWith(self.check_bundle(self.aab("out/other.aab", app_id="dev.example")), "must be the play build")

    def test_it_keeps_the_apk_permission_rules(self) -> None:
        aab = self.aab(perms=(INTERNET, check_release.QUERY_ALL_PACKAGES, check_release.AD_ID))
        self.assertFailsWith(self.check_bundle(aab), f"declares {INTERNET}", f"declares {check_release.QUERY_ALL_PACKAGES}", f"declares {check_release.AD_ID}")

    def test_it_never_carries_shizuku(self) -> None:
        aab = self.aab(perms=(SHIZUKU,), providers=(SHIZUKU_PROVIDER,))
        for bundletool in (None, ["bundletool"]):
            errors = self.check_bundle(aab, aapt2=None if bundletool else "aapt2", bundletool=bundletool)
            self.assertFailsWith(errors, f"the Google Play build declares {SHIZUKU}", f"the Shizuku provider {SHIZUKU_PROVIDER}")

    def test_a_debuggable_bundle_fails_even_without_a_tag(self) -> None:
        self.assertFailsWith(self.check_bundle(self.aab(debuggable=True)), "a debuggable bundle")
        self.assertFailsWith(self.check_bundle(self.aab(debuggable=True), aapt2=None, bundletool=["bundletool"]), "a debuggable bundle")

    def test_version_name_must_match_the_tag(self) -> None:
        aab = self.aab(version_name="1.0.0-dev")
        self.assertEqual(self.check_bundle(aab), [])
        self.assertFailsWith(self.check_bundle(aab, tag="1.0.0"), "versionName 1.0.0-dev, but the tag says 1.0.0")

    def test_its_native_libraries_must_be_16_kb_aligned(self) -> None:
        aab = self.aab(libs={"base/lib/arm64-v8a/libold.so": elf64(0x1000), "base/lib/x86_64/libok.so": elf64(0x4000)})
        errors = self.check_bundle(aab)
        self.assertFailsWith(errors, "base/lib/arm64-v8a/libold.so has 4096 byte LOAD alignment")
        self.assertEqual(len(errors), 1, errors)

    def test_not_a_bundle(self) -> None:
        apk = write_zip(self.root / "out/app.aab", {"AndroidManifest.xml": b"\x03\x00"})
        self.assertFailsWith(self.check_bundle(apk), "isn't an Android App Bundle")
        broken = self.root / "out/broken.aab"
        broken.write_bytes(b"not a zip")
        self.assertFailsWith(self.check_bundle(broken), "not a zip file")
        self.assertFailsWith(self.check_bundle(self.root / "missing.aab"), "missing.aab: not found")

    def test_proto_apk_holds_the_base_manifest_and_resources(self) -> None:
        aab = write_zip(
            self.root / "app.aab",
            {check_release.BUNDLE_MANIFEST: b"manifest", check_release.BUNDLE_RESOURCES: b"table", "base/dex/classes.dex": b"dex", "BundleConfig.pb": b""},
        )
        out = self.root / "proto.apk"
        check_release.write_proto_apk(aab, out)
        with zipfile.ZipFile(out) as z:
            self.assertEqual(sorted(z.namelist()), ["AndroidManifest.xml", "resources.pb"])
            self.assertEqual(z.read("AndroidManifest.xml"), b"manifest")
            self.assertEqual(z.read("resources.pb"), b"table")


class ToolLookupTest(unittest.TestCase):
    def setUp(self) -> None:
        tmp = tempfile.TemporaryDirectory(prefix="gate-tools-")
        self.addCleanup(tmp.cleanup)
        self.home = Path(tmp.name)
        self.files = self.home / "caches" / "modules-2" / "files-2.1"
        env = mock.patch.dict(os.environ, {"GRADLE_USER_HOME": str(self.home)})
        env.start()
        self.addCleanup(env.stop)
        os.environ.pop("BUNDLETOOL", None)
        # No bundletool on PATH, and a java that the commands below name as plain "java".
        for patcher in (mock.patch.object(check_release.shutil, "which", return_value=None), mock.patch.object(check_release, "find_java", return_value="java")):
            patcher.start()
            self.addCleanup(patcher.stop)

    def cached(self, group: str, artifact: str, version: str, manifest: str | None = None, suffix: str = "") -> str:
        path = self.files / group / artifact / version / "0123abcd" / f"{artifact}-{version}{suffix}.jar"
        write_zip(path, {"META-INF/MANIFEST.MF": manifest.encode()} if manifest else {"x.class": b""})
        return str(path)

    def bundletool_jar(self, version: str) -> str:
        # Wrapped at 72 bytes like a real jar manifest, here in the middle of a name.
        manifest = "Manifest-Version: 1.0\r\nClass-Path: aapt2-proto-7.3.0-alpha07-8248216.jar guava-32.0.1-jr\r\n e.jar\r\n\r\n"
        return self.cached("com.android.tools.build", "bundletool", version, manifest)

    def test_artifact_name(self) -> None:
        self.assertEqual(check_release.artifact_name("guava-32.0.1-jre.jar"), "guava")
        self.assertEqual(check_release.artifact_name("aapt2-proto-7.3.0-alpha07-8248216.jar"), "aapt2-proto")
        self.assertEqual(check_release.artifact_name("j2objc-annotations-2.8.jar"), "j2objc-annotations")
        self.assertEqual(check_release.artifact_name("javax.inject-1.jar"), "javax.inject")
        self.assertEqual(check_release.artifact_name("listenablefuture-9999.0-empty-to-avoid-conflict-with-guava.jar"), "listenablefuture")

    def test_version_key_never_compares_numbers_with_text(self) -> None:
        versions = ["35.0.0-rc3", "36.0.0", "35.0.0", "9.0"]
        self.assertEqual(sorted(versions, key=check_release.version_key)[-1], "36.0.0")

    def test_bundletool_from_the_gradle_cache_with_its_dependencies(self) -> None:
        self.bundletool_jar("1.18.1")
        jar = self.bundletool_jar("1.18.3")
        proto = self.cached("com.android.tools.build", "aapt2-proto", "9.4.1-15978811")
        self.cached("com.android.tools.build", "aapt2-proto", "9.4.1-15978811", suffix="-sources")
        self.cached("com.google.guava", "guava", "32.0.1-jre")
        guava = self.cached("com.google.guava", "guava", "33.5.0-jre")
        self.assertEqual(
            check_release.find_bundletool(), ["java", "-cp", os.pathsep.join([jar, proto, guava]), check_release.BUNDLETOOL_MAIN]
        )

    def test_a_missing_dependency_means_no_bundletool(self) -> None:
        self.bundletool_jar("1.18.3")
        self.cached("com.google.guava", "guava", "33.5.0-jre")
        self.assertIsNone(check_release.find_bundletool())

    def test_bundletool_env_wins(self) -> None:
        with mock.patch.dict(os.environ, {"BUNDLETOOL": "/opt/bundletool-all-1.18.3.jar"}):
            self.assertEqual(check_release.find_bundletool(), ["java", "-jar", "/opt/bundletool-all-1.18.3.jar"])
        with mock.patch.dict(os.environ, {"BUNDLETOOL": "/usr/local/bin/bundletool"}):
            self.assertEqual(check_release.find_bundletool(), ["/usr/local/bin/bundletool"])


if __name__ == "__main__":
    unittest.main()
