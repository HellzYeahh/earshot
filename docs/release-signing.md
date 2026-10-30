# Release signing

Earshot ships two versions from one codebase (roadmap, "1.0 plan"):

| Version | Gradle flavour | App ID | Signed with |
|---|---|---|---|
| Google Play | `play` | `dev.earshot` | the **Play upload key**. Google keeps the real app-signing key (Play App Signing) and re-signs what you upload. If the upload key is lost, Google can reset it. |
| GitHub, IzzyOnDroid | `foss` | `dev.earshot.foss` | the **GitHub key**. Every future GitHub update must be signed with this exact key: lose it and nobody can update, ever. Back it up. |

Debug builds (`assemblePlayDebug`, `assembleFossDebug`) still use the PC's debug key. A release
build without its key comes out **unsigned**, never debug-signed, so a debug-key APK can't be
published by mistake. The one exception is the opt-in 0.9.1 bridge. Releases up to 0.9.0 were
debug-signed `dev.earshot` builds; see "Moving existing users" below.

**Never publish a debug build** (`playDebug`, `fossDebug`, or what `install.ps1` builds by
default). Debug builds are debuggable: anyone with USB debugging can read the app's private files
with `adb shell run-as`, the Bragi pairing keys among them. They also skip R8. On a tag,
`check_release.py` fails any debuggable APK.

## 1. Make the two keys (once, by you)

Run these yourself in PowerShell. keytool asks for a password and your name details. Choose the
passwords yourself, and never paste them into a chat, an issue or a commit.

```powershell
New-Item -ItemType Directory -Force C:\Users\Hellz\keys
& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v -keystore C:\Users\Hellz\keys\earshot-github.jks -alias earshot -keyalg RSA -keysize 4096 -validity 36500
& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v -keystore C:\Users\Hellz\keys\earshot-play-upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 36500
```

The keystores are PKCS12, so each key's password is the same as its keystore's password.

## 2. Tell the build where they are

Create `keystore.properties` in the repo root. It is in `.gitignore`, like `*.jks`:

```properties
foss.storeFile=C:/Users/Hellz/keys/earshot-github.jks
foss.storePassword=<GitHub key password>
foss.keyAlias=earshot
foss.keyPassword=<GitHub key password>
play.storeFile=C:/Users/Hellz/keys/earshot-play-upload.jks
play.storePassword=<upload key password>
play.keyAlias=upload
play.keyPassword=<upload key password>
```

CI or another machine can set the same values as environment variables instead:
`EARSHOT_FOSS_STORE_FILE`, `EARSHOT_FOSS_STORE_PASSWORD`, `EARSHOT_FOSS_KEY_ALIAS`,
`EARSHOT_FOSS_KEY_PASSWORD`, and the same with `PLAY`.

The variables also keep the passwords out of the repo folder. Any tool given the whole project
folder can read `keystore.properties`, an AI coding agent for example. To use them, leave the
password lines out of the file (or delete the file) and set them in the PowerShell window you build
from. Typed this way, a password isn't shown or kept in the history:

```powershell
$p = Read-Host "GitHub key password" -AsSecureString
$env:EARSHOT_FOSS_STORE_PASSWORD = [Net.NetworkCredential]::new('', $p).Password
$env:EARSHOT_FOSS_KEY_PASSWORD = $env:EARSHOT_FOSS_STORE_PASSWORD
```

## 3. Back them up (do this before the first release)

Back up the two `.jks` files in two places that are not this PC. For example:
- an encrypted USB stick kept somewhere else;
- an encrypted archive (7-Zip, AES-256, its own strong password) in a cloud drive.

A second drive inside this PC (such as `E:`) doesn't count, because it fails along with the PC.
Never put the `.jks` files unencrypted in a cloud drive, and never in the repo.

Keep the passwords **only in a password manager**. `keystore.properties` is not a backup item: it
holds every password in plain text, so a copy next to the keys gives away both. On a new PC, write
it again from step 2.

## 4. Build and check

Build one variant per Gradle run:

```powershell
.\gw.cmd :app:assembleFossRelease --console=plain -q   # GitHub APK: app\build\outputs\apk\foss\release\app-foss-release.apk
.\gw.cmd :app:bundlePlayRelease --console=plain -q     # Play bundle: app\build\outputs\bundle\playRelease\app-play-release.aab
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\<version>\apksigner.bat" verify --print-certs app\build\outputs\apk\foss\release\app-foss-release.apk
Copy-Item app\build\outputs\apk\foss\release\app-foss-release.apk app\build\earshot-X.Y.Z.apk
python tools\release\check_release.py --apk app\build\earshot-X.Y.Z.apk --tag vX.Y.Z --installs-over <the previous earshot-X.Y.Z.apk>
python tools\release\check_release.py --aab app\build\outputs\bundle\playRelease\app-play-release.aab --tag vX.Y.Z
```

On a tag, a signed APK also has to pass `zipalign -c -P 16` (16 KB page alignment), so the
build-tools must be 35 or newer (this PC has 36.0.0). The `.aab` check reads its base manifest and
refuses INTERNET, QUERY_ALL_PACKAGES, AD_ID, Shizuku and a debuggable or wrongly versioned build.

The GitHub release asset is named `earshot-X.Y.Z.apk`. Publish the file that passed the check.

The certificate checks run only here. CI builds unsigned APKs and skips them. On a signed APK,
`check_release.py` requires the following:
- apksigner is found (through `local.properties` or `ANDROID_HOME`);
- `apksigner verify` accepts the APK;
- the APK isn't debug-signed.

`--installs-over` checks that the new APK has the same app ID and key as the previous GitHub release
and a higher versionCode, so it really updates it. Leave it out for the first `dev.earshot.foss`
release. If apksigner can't find Java, run this first:
`$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"`.

For Play, create the app in the Play Console with package `dev.earshot`, keep Play App Signing on
(the default) and let Google create the app-signing key, then upload the `.aab`.

## Moving existing users (once)

Builds up to 0.9.0 are `dev.earshot` signed with the debug key. Android refuses to update an app
when the signing key changes:
- **GitHub users:** one last debug-signed `dev.earshot`, 0.9.1 (the bridge, below), updates them
  in place and adds **Back up and restore** (More). Export there, install the new
  `dev.earshot.foss` build next to it, import, then remove the old app. The new build notices the
  old one and offers to open its App info page.
- **Play:** a phone that still has the GitHub `dev.earshot` can't install the Play version until
  that is removed (same app ID, different key). Say so in the listing's first release notes.

### The 0.9.1 bridge build

The bridge is the `foss` flavour, so About says GitHub and there's no store code. It differs in two
ways: it keeps the old app ID `dev.earshot`, and it is signed with this PC's debug key, the key 0.9.0
and earlier were signed with, so it installs over them. Only the Gradle property
`earshot.legacyBridge` builds it this way (`app/build.gradle.kts`); without the property nothing
changes.

Build it on **this PC**. Any other PC, and CI, has a different debug key, and an APK signed with it
updates nobody.

1. **Set the version.** In `app/build.gradle.kts` set `versionCode = 11` and
   `versionName = "0.9.1"`. 0.9.0 is 10, and Android updates only to a higher code. 11 is also
   `Distribution.LEGACY_MAX_VERSION_CODE` in `app/src/foss` (and `app/src/play`); a bridge respin
   with a higher versionCode must raise that constant to the same value (and `LegacyAppTest`), or
   the GitHub build won't recognise it as the old app.
2. **Write the release notes.** Write the 0.9.1 What's New (`WhatsNew.RELEASE = "0.9.1"`) and a
   dated `## 0.9.1 — YYYY-MM-DD` section in `CHANGELOG.md`. The gate checks both on the tag.
   Everything on `main` at that commit ships in the bridge.
3. **Commit and tag** `v0.9.1`.
4. **Build only this variant:**
   ```powershell
   .\gw.cmd :app:assembleFossRelease -Pearshot.legacyBridge=true --console=plain -q
   ```
   The APK is `app\build\outputs\apk\foss\release\app-foss-release.apk`: `dev.earshot`,
   debug-signed, not debuggable. Gradle prints a note that the bridge is on (not when it reuses
   the configuration cache); step 5's `check_release.py --bridge` is the real check.
5. **Check it** against the published 0.9.0:
   ```powershell
   & "$env:LOCALAPPDATA\Android\Sdk\build-tools\<version>\apksigner.bat" verify --print-certs app\build\outputs\apk\foss\release\app-foss-release.apk
   Copy-Item app\build\outputs\apk\foss\release\app-foss-release.apk app\build\earshot-0.9.1.apk
   gh release download v0.9.0 -p earshot-0.9.0.apk -D app\build
   python tools\release\check_release.py --apk app\build\earshot-0.9.1.apk --tag v0.9.1 --bridge --installs-over app\build\earshot-0.9.0.apk
   ```
   apksigner must print `CN=Android Debug`. With `--bridge`, the script checks four things:
   - the app ID is `dev.earshot`;
   - the manifest is the foss flavour's;
   - there's no billing permission;
   - the APK is debug-signed.

   `--installs-over` proves that the bridge has 0.9.0's app ID and key and a higher versionCode.
6. **Publish** `earshot-0.9.1.apk` on the `v0.9.1` GitHub release. CI's gate on that tag checks the
   normal unsigned builds instead; that is expected.
7. **Afterwards:** build without the property again, which gives the normal `dev.earshot.foss`. Give
   the next release a versionCode above 11.

`playDebug` and `fossDebug` are never the bridge, nor any other release. `playDebug` is
`dev.earshot` and debug-signed too, so it would install over 0.9.0, but it is debuggable and
unminified (see the top of this page).

## Developer verification

Google requires apps installed outside Play on certified phones to come from a verified developer.
Some countries started in September 2026; the rest follow in 2027. Register the GitHub package
(`dev.earshot.foss`) and the GitHub key's certificate when the developer console asks for them.
Play apps are covered by the Play Console account.
