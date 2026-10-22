# Earshot roadmap: 0.6 and 0.7, on the way to 1.0

Written 2026-10-20, after 0.5.0 (Controls). Research behind it:
[headset landscape](research/headset-landscape-2026-10.md).

## The goal

**1.0: the one companion app you need for your Bluetooth headset** when you don't like the maker's
app. Open source on GitHub, on Google Play, and on F-Droid/IzzyOnDroid. Earshot stays what it is now:
fast, account-free, no internet permission. Bose stays its deepest integration.

Versions: **0.6** and **0.7** add features and do the research (this document). **0.8** collects
the big changes listed at the end, and the release after it is **1.0**.

## Rules for getting there

1. **The Bose core doesn't change.** `QcUltra2Protocol`, `StateMapping`, `ConnectionFacts`,
   `BmapConnection`, `BoseLinkFactory`, `:protocol/qcultra2` and the Bragi stack move behind a
   driver seam as they are. A refactor that touches their behaviour needs the Bose end-to-end tests
   green before and after, plus a check on the QC Ultra 2.
2. **Every headset gets the Android-level features**, whether or not Earshot knows its protocol.
3. **Only real hardware makes a vendor "Supported".** Built from references plus a virtual device
   only = "Experimental" (the Crusher today).
4. **No internet permission, ever.** Device reports leave the phone only through the share sheet.
5. **Clean-room for GPL references.** Facts from GPL/AGPL projects, code only from MIT/Apache/BSD
   sources, credited in `docs/reference/` and About (see the licence rule in the research file).
6. **Each vendor has its own WriteGuard.** Firmware update, factory reset, clear pairing and
   flash/RAM commands are refused on the single write path, as `bt/WriteGuard` does for BMAP.
   Airoha RACE is allow-list only.
7. **Never probe a headset with another vendor's protocol.** Identify first, then open only that
   vendor's channel.

## Support tiers (used in the app, README and store listing)

| Tier | What you get | Who |
|---|---|---|
| **Any headset** | Pro EQ (with a per-headset preset), battery as Android reports it, codec readout (switching with Shizuku), call audio, mic check, Controls remapper, automations, tile/widget for EQ | every Bluetooth headset |
| **Supported** | Plus the headset's own controls: modes, noise, its EQ, buttons, settings | vendor driver, tested by us on real hardware |
| **Experimental** | Same as Supported, marked "untested on real hardware, report back" | vendor driver from references + virtual device |

## 0.6: "Any headset"

Theme: make Earshot useful and safe on every headset, put the vendor seam in place, and add the
first new vendor we can test (AirPods). User-visible headline: *works with any Bluetooth headset;
deep control for Bose and AirPods.*

### Progress (2026-10-20)

- **Done, first step of A1 + B4:**
  - `DeviceFamily.GENERIC` with `GenericHeadphoneRepository` (audio link, name, battery broadcast;
    never sends anything). Unknown names are no longer Bose.
  - `DevicePrefs.familyFor` (per-device "Headphone type", then the stack that answered on the
    address, then the name).
  - `DevicePick`: the connected headset wins at launch; an explicit pick is pinned for the run.
  - `AudioClass`: car kits, speakers and watches aren't headsets.
  - Neutral device strings.
- **Done (2026-10-21), rest of 0.6 that can be built without new hardware:**
  - A1: `VendorDriver` registry. The router takes a driver list; GENERIC is the mandatory fallback.
  - A3: `AudioLinkMonitor` fills `ConnectionState.audioLinked`. `HeadphoneState.isAudioActive` drives
    the foreground service, automations and the call boost.
  - A4: `ConnectionProblem.ChannelHeld(app)`, no more string matching.
  - A5 (subset): `HeadphoneCapabilities.ownSettings`; Controls, More, Sound, Lab, LE Audio and
    latency are hidden for generic headsets.
  - B1: Android's battery broadcast, plus a starting value from Shizuku dumpsys (`mBatteryLevel`)
    or `getBatteryLevel()` reflection.
  - B2: codec from `CODEC_CONFIG_CHANGED`; `pickSink` prefers the shown headset.
  - B3: `HeadsetEqMemory` (explicit picks only, applied on connect).
  - B5: README and About.
  - C1: `ContinuityAdvert` (`:protocol/apple`) + `AppleAdvertScanner`: BLUETOOTH_SCAN
    neverForLocation, short bursts.
  - C3 (demo only): `DemoDevice.AIRPODS_PRO`.
  - Call boost fix: it waits for voice routing to reach the headset, then checks for 12 s.
- **Deferred to the AirPods-modes driver (C2), on purpose:**
  - A6 (shared session base): it would rewrite working Bose connection code, and pays off only for
    the next protocol driver.
  - A7 (modes by meaning + migration): no second vendor has modes yet, and the migration should be
    tested against one.
- **Spike answers (desk):**
  - D4, Android 16 `hiddenapi-flags.csv`: `createInsecureRfcommSocket(int)` is "unsupported"
    (allowed). `getBatteryLevel()`, `getCodecStatus()` and `setCodecConfigPreference()` are
    "sdk,system-api", so reflection isn't blocked by policy and only runtime permission checks
    apply (worth trying with a CDM association in F4). `createInsecureL2capSocket(int)` is
    max-target-o (blocked).
  - D3, **corrected**: `BluetoothSocketSettings.Builder.setSocketType` accepts only `TYPE_RFCOMM`
    and `TYPE_LE` (AOSP throws "invalid socketType"). `setL2capPsm` is LE-only (128–255), so there
    is **no public way** to open BR/EDR PSM 0x1001. LibrePods gets there through the private
    `BluetoothSocket` constructor after a JNI hidden-API exemption (blocklisted). Android 17's
    change is a stack ERTM fix (issue 371713238), seen on Pixel 16 QPR3 and ColorOS/OxygenOS 16;
    One UI unknown. C2 is a go/no-go (below).
- **Done (2026-10-21): first Soundcore driver (E2, pulled into 0.6).**
  - Sleep A30 (model D1301): `:protocol/soundcore` (framing, `D1301State`, `SoundcoreWriteGuard`
    allow-list), `SoundcoreLinkFactory` (vendor UUID `…d6832e7d1301` only, never a fixed channel),
    `SoundcoreConnection`, `SoundcoreHeadphoneRepository`.
  - Noise cancelling on/off as two modes, per-bud battery, firmware, tap controls.
  - `VirtualSleepA30` + end-to-end tests; demo device.
  - Facts from OpenSCQ30 (GPL, facts only) and soundcorebridge (MIT).
  - **Tested on the user's Sleep A30 (firmware 01.91):** state read, Listening mode switched
    (Local and back), the user confirmed noise cancelling.
  - F1 (vendor controls), done for it: `VendorControl` / `VendorControlsCard` /
    `setVendorControl`. Ten settings; the noise cancelling switch and listening mode are also on Home.
- **Tools never hide settings (user decision, 2026-10-21):** every Tools control stays visible and
  can be tried while a headset is connected. A refusal comes back as a message. A forced
  "Headphone type: Bose" on a device that doesn't look like a Bose gets only the SDP lookups by
  UUID, never blind fixed channels (`BmapCandidates.order(fixedChannels = false)`).
- **Learned from the user's phone (feeds D1/D2):**
  - *soundcore Sleep A30*: class 0x240404; SDP shows the Soundcore UUID
    `0cf12d31-fac3-4553-bd80-d6832e7d1301` and BES's firmware-update UUID `66666666-…`. So it's a
    BES chip: never open that channel.
  - *soundcore Boom 2* (speaker): advertises the Fast Pair Message Stream `df21fe2c-…`.
  - *Bose QC Ultra 2 HP*: class 0x2C4404; advertises `deca-fade…`, BLE Battery Service and LE
    Audio (VCS, BASS).
  - The car (Uconnect, 0x340408 hands-free + MNS) and a Galaxy Watch that is an HFP hands-free unit
    both looked like headsets before the `AudioClass` filter.

### A. Safety and the vendor seam (do first)

| # | Item | Why / where | Size |
|---|---|---|---|
| A1 | **Unknown headsets are no longer treated as Bose.** Introduce a `VendorDriver` registry (id, how it recognises a device, competing-app name, `createRepository()`, demo devices) and a `GENERIC` driver, replacing the `DeviceFamily {BOSE, BRAGI}` enum. Only the Bose driver may run the BMAP channel search. | `bt/DeviceFamily.kt:16` sends every non-Bragi name to BOSE, so the BMAP probe runs against AirPods or Sony today. `ConnectionManager.kt:183,347`, `RoutingHeadphoneRepository.kt:44-87`. | M |
| A2 | **Identify the headset before connecting.** A `DeviceIdentifier` combining the name, SDP service UUIDs (`fetchUuidsWithSdp`), BLE advert company ID/service UUIDs and the Fast Pair model ID gives a ranked vendor/chipset guess (Bose, Apple, Sony, JieLi, Bluetrum, Airoha, Qualcomm, BES…). The router picks the driver from it. | Research file, "Chipsets". Pure function + tests; the Android lookups behind an interface. | M |
| A3 | **Audio link separate from control link.** `ConnectionState.audioLinked` (A2DP/HFP/LE profiles) next to the control status. Automations, the tile, EQ presets and call audio key on the audio link, so they work for headsets without a driver. | `AutomationEngine.kt:57-117` gates every rule on the control link today. | M |
| A4 | **Typed connection problems.** `ConnectionProblem.ChannelHeldBy(appName)` and friends instead of matching "Close the Bose app" strings; the UI stops using `state.crusher == null` to detect the vendor. | `ConnectionManager.kt:351`, `DisconnectedCard.kt:59-63`, `ConnectionScreen.kt:123`. | S |
| A5 | **Capability flags for every optional feature** (EQ bands, sidetone, auto play/pause, multipoint, prompts, buttons, rename, power off, auto-off, LE Audio, latency, volume, noise steps), or "null = absent" made explicit. | `HeadphoneCapabilities` (`HeadphoneModels.kt:233-275`) covers noise and modes only. | M |
| A6 | **Shared session base.** Pull the ~200 lines duplicated between `ServiceHeadphoneRepository` and `CrusherHeadphoneRepository` into one `SessionRepository<Link, Protocol>`. One `DevicePrefs`/`DeviceFinder`, with per-vendor link hints under namespaced keys. Bose behaviour unchanged (end-to-end tests are the gate). | Audit §1. Needed before vendor #3, or AirPods becomes a third copy. | M |
| A7 | **Modes stored by meaning, not slot number.** Rules, the tile and shortcuts store QUIET / AWARE / OFF / ADAPTIVE / custom + device address, with a one-time migration of saved rules and shortcut prefs. Remove the Quiet/Aware/Immersion fallbacks. | `RuleRunner.kt:78`, `TileCycle.kt:7`, `ShortcutAction.kt:48`, `AutomationAction.SetNoise.spatialAudioWire`. AirPods have Off/ANC/Transparency/Adaptive. | M |

### B. "Any headset" features

| # | Item | Why / where | Size |
|---|---|---|---|
| B1 | **Battery for any headset** from `BATTERY_LEVEL_CHANGED`, Apple `+IPHONEACCEV`, Plantronics `+XEVENT` and BLE BAS. The initial value comes from the Shizuku dumpsys when available. Home shows a battery card for driver-less headsets. | All of these reach a normal app with `BLUETOOTH_CONNECT` (research file, "What Android gives"). | M |
| B2 | **Codec readout without Shizuku** from `ACTION_CODEC_CONFIG_CHANGED`. Shizuku stays for switching. Fix `A2dpProxyHolder.pickSink` preferring Bose names. | `codec/A2dpProxyHolder.kt:61-67`. Better story for Play. | S |
| B3 | **Per-headset EQ preset**, applied when that headset connects, with an AutoEq suggestion matched by name (AutoEq is MIT; ship a compact index of popular models, no network). | Uses A3. Makes Pro EQ the reason to install Earshot for any headset. | M |
| B4 | **Neutral onboarding and picker.** List every paired audio device with its tier badge (Supported / Experimental / Any headset) instead of sorting "Bose-looking" first. | `OnboardingScreen.kt:165-200`, `DeviceFinder.NAME_HINTS` (`DeviceFinder.kt:230`) moves into the drivers. | S |
| B5 | **Neutral strings and README.** Product strings name the connected headset; the Bose-only ones stay on Bose screens. A not-affiliated line for every brand named. | 22 Bose strings, `EarshotWidget.kt:216` "No Bose headphones paired", About. | S |

### C. AirPods / Beats (first new vendor; we own a pair)

| # | Item | Notes | Size |
|---|---|---|---|
| C1 | **Battery and case from BLE Continuity adverts** (company `0x004C`): L/R/case, charging, lid, in-ear. No connection needed, works on every Android version. Needs `BLUETOOTH_SCAN` with `neverForLocation`. | Read-only and low-risk. | S–M |
| C2 | **Listening modes over AAP** (L2CAP PSM `0x1001`): Off / ANC / Transparency / Adaptive, conversational awareness, ear detection, press-and-hold, rename. **Gated to Android 17+**, where the L2CAP path reportedly works without root. Experimental until verified on our One UI phone. | References are GPL (LibrePods, CAPod): **clean-room from our own HCI captures** of the AirPods talking to an iPhone/Mac plus documented facts. New L2CAP `ByteTransport`. | M |
| C3 | **Virtual AirPods** (adverts + AAP) and end-to-end tests, same pattern as `VirtualCrusher`. | Also the demo device. | S–M |

### D. Research spikes in 0.6 (answers feed 0.7)

1. **Our generic Chinese buds:** run A2's identifier on them. JieLi (`0xAE00`, company `0x05D6`), Bluetrum (`0xFDB3`) or something else? Is RCSP switched on? This decides 0.7's chipset driver.
2. **Our Soundcore:** exact model, protocol variant, and its state-update layout from a capture.
3. **Android 17 L2CAP on One UI:** can a normal app open PSM `0x1001` to AirPods? (Blocks C2.)
4. **Hidden-API status:** look up `getBatteryLevel`, `BluetoothA2dp.getCodecStatus`/`setCodecConfigPreference` in Android 16/17 `hiddenapi-flags.csv`. Then check whether a Companion Device Manager association lets a normal app switch codecs without Shizuku.
5. **Fast Pair Message Stream:** can a third-party app open RFCOMM `df21fe2c-…` while Google Play services holds it? (Cross-brand battery/ring/ANC-read in 0.7.)
6. **The `deca-fade` UUID:** read the QC Ultra 2's SDP record. If it is iAP2, drop `SDP BMAP` from the BMAP candidates, or move it behind a Bose-only check.

### 0.6 is done when

- A non-Bose headset never sees a BMAP frame (test: AirPods/generic buds through the router with a
  spy transport).
- Bose behaviour is byte-for-byte unchanged (`VirtualEndToEndTest`, `ModelProfilesEndToEndTest`,
  `OlderFirmwareEndToEndTest` green; manual pass on the QC Ultra 2).
- Generic buds and Soundcore show battery, EQ, codec and automations with no driver.
- AirPods battery works on every supported Android version. Modes work on our phone, or are hidden
  with an "Android 17 needed" note.
- Spikes D1–D6 have written answers in `docs/research/`.

## 0.7: "More brands"

Theme: two or three new drivers chosen by what we can test, a cross-brand baseline, and the
groundwork for Play. Headline: *Supported: Bose, AirPods, Soundcore, JieLi-based earbuds.
Experimental: Sony, Crusher.*

### E. Drivers

| # | Item | Notes | Size |
|---|---|---|---|
| E1 | **JieLi RCSP** (if D1 says our buds speak it): battery, EQ, keys, ANC/transparency, find, name. One driver covers QCY, Oraimo, many no-name buds, and is the base for a Redmi dialect later. | **JieLi's own SDKs are Apache-2.0**, so code can be ported with credit, including the connect-time auth. Block OTA, reboot, reset. If our buds turn out to be Bluetrum, do Bluetrum (ABMate commands) instead and block 0x24/0x2F. | M |
| E2 | **Soundcore**, starting with our model and then its family: modes, EQ (incl. HearID if present), touch actions, auto-off. | GPL references (OpenSCQ30): clean-room from captures. Each model has its own state layout, so add models one at a time behind a model table, like `BoseModel`. | M→L |
| E3 | **Sony (Experimental)**: modes, ANC level, EQ, battery, speak-to-chat, touch modes, multipoint. v2 command set (XM5/XM6, WF-1000XM5, LinkBuds) first. | The best-documented non-Bose protocol, **MIT reference** (mos9527/SonyHeadphonesClient). Ship as Experimental with a virtual device and ask owners for reports. | M |
| E4 | **Fast Pair baseline** (if D5 allows): battery L/R/case and "ring my buds" for any Fast Pair headset. | Public spec, CC-BY/Apache. Small, and it lifts Pixel Buds A, JBL and many others above "Any headset". | S |
| E5 | *(stretch)* **Bose QC35 / QC35 II out of UNSUPPORTED**: noise `[1.6]`, auto-off, prompts, name, pairing mode, as Experimental. | Plain BMAP (based-connect facts). Lives in the existing Bose stack and catalogue. | S |

### F. Platform and quality

| # | Item | Notes | Size |
|---|---|---|---|
| F1 | **Vendor extras without core changes:** a generic `VendorControl` list (toggle / slider / choice) rendered by one card, replacing `setCrusherBass` / `HeadphoneState.crusher`. A per-vendor `LabAdapter` for `readRaw`/scan/console. | Audit §6.5. Each new driver otherwise adds fields to core. | M |
| F2 | **Driver conformance suite:** one shared test class every driver's virtual device must pass (connect, refresh, modes, battery push, write refused by its WriteGuard, reconnect). Router tests. | No shared suite today. | M |
| F3 | **Device report:** Lab exports an anonymised report (identifier result, SDP UUIDs, GATT services, a short protocol trace) through the share sheet. Owners of untested headsets can then help without us owning them. | Addresses masked like `captures-galaxy/`. No network. | S |
| F4 | **Companion Device Manager:** associate the headset during onboarding. Use presence events and the background-start exemption instead of the boot/ACL receiver. Codec switching without Shizuku if D4 says yes. | Play-sanctioned path for what `BluetoothConnectionReceiver` does. | M |
| F5 | **Play hygiene (no release yet):** drop `QUERY_ALL_PACKAGES` (use a `<queries>` launcher intent), drop `RECEIVE_BOOT_COMPLETED`, a release upload key with Play App Signing, a privacy policy, an open-source licences screen, lint on, GitHub Actions running the unit tests. | Audit "Play risk list". The licence notice for the bosectl captures in `protocol/src/test/resources/captures/`. | M |

### 0.7 is done when

- Each new Supported driver passed F2 and a manual pass on our hardware. Sony passed F2 on its
  virtual device.
- A device report from each of our headsets is in `docs/reference/`.
- `assembleRelease` is signed with the upload key, lint runs in CI, and no `QUERY_ALL_PACKAGES`.

## 0.8 → 1.0 (outline, plan after 0.7)

- **Module split:** `:vendor-bose`, `:vendor-apple`, `:vendor-jieli` … with a lint/Gradle rule that
  `ui`, `extras`, `shortcut` and `call` never import a vendor's protocol package. Move Bose wire
  codes still in core (`SpatialAudio.wire`, `Sidetone.wire`, `ButtonAction`) into `:protocol`.
- **Distribution:** one Play build that works without Shizuku and keeps it optional (with a
  disclosure), or a separate "full" flavour for GitHub/F-Droid. Decide after D4 and F4.
  Reproducible builds for F-Droid. Developer-verification registration (global in 2027).
  Play foreground-service declaration video.
- **Localisation** (strings out of Kotlin first), store assets, screenshots without brand marks.
- **More drivers** by demand from device reports: Galaxy Buds, Pixel Buds Pro (MIT reference),
  Nothing, Huawei, Sennheiser (GAIA), Xiaomi.
- **Security review of every write path** per vendor before the store release.

## Research update (2026-10-21, "App status overview" session)

Full report: `docs/research/reports/Universal headset control accelerators.md` (notes in
`docs/research/research_notes/`). Headline: no standard or Google channel lets a third-party app
switch ANC across brands. The Bluetooth SIG has no ANC spec; Fast Pair "set ANC" needs a GMS-only
account-key MAC; and AOSP restricts VCS/VOCS/AICS/PACS/ASCS/BASS/HAS/CSIS to BLUETOOTH_PRIVILEGED
(Android 13 through 17). "Universal" = phone-side features plus vendor drivers. Changes:

1. **F4 (Companion Device Manager) moves up, to right after 0.6** as the "universal remote".
   - One association per headset (MAC filter, no re-pair) gives any brand:
     - `BluetoothDevice.connect()/disconnect()` (API 37, CDM or privileged);
     - `setAlias` (31) and `CompanionDeviceManager.removeBond` (36);
     - presence binding (36).
   - Needs compileSdk 37.
   - Also try codec switching by reflection plus the association.
2. **C2 (AirPods modes) is a go/no-go**, decided by a One UI 9 test (see D3). Until then the
   AirPods tier is C1 (advert battery) plus the any-headset features. Point users to
   LibrePods/CAPod for modes.
3. **New B6, "Find my earbuds":**
   - A one-sided stereo tone (`AudioTrack`) routed to the headset, plus "last seen" from the
     connection log.
   - Starts only from the UI, tile, widget or a notification (Android 17 background-audio
     hardening).
4. **B1 extension:** our own GATT client reads BAS `0x180F` (not restricted) for a Shizuku-free
   starting battery on LE-capable headsets (the QC Ultra 2 advertises BAS).
   - New spike **D7**: MICS `0x184D` Mute `0x2BC3`.
   - HFP vendor events use the decimal company ID (".85"); only `+XEVENT/+ANDROID/+CGM*/+XAPL/
     +IPHONEACCEV` are broadcast.
5. **E4 (Fast Pair) shrinks:** AOSP RFCOMM refuses a second client on a channel and GMS opens the
   Message Stream on connect, so expect D5 = no.
   - Keep the `df21fe2c` SDP UUID as an identification signal and the advert battery snapshot.
   - **WriteGuard rule: never write a Fast Pair account key.** The first key becomes the Find Hub
     owner key.
6. **New F6, diagnostics timeline:**
   - A2DP/HFP disconnect reasons (API 37), bond-loss and encryption intents.
   - `BluetoothHeadset.getCodecType` (37.2) as the call-codec label.
   - Feeds F3.
7. **0.8 drivers:**
   - A shared Qualcomm GAIA v3 codec in `:protocol`, with an allow-list that never sends Upgrade
     (feature 6).
   - Sennheiser (MIT momentum4-control) and Pixel Buds Pro (MIT pbpctrl) ahead of Galaxy Buds.
   - E5 (QC35) loses value: Gadgetbridge already covers QC35 and NC 700.
8. **Target-37 checklist:**
   - The call boost's `setStreamVolume` is ignored from a background-started service without
     while-in-use status.
   - No static-final reflection.
   - A separate `.foss` applicationId for GitHub/F-Droid builds.
   - IzzyOnDroid from 0.7.

AVRCP play/next never become input events (they go to the media session), so the Controls
remapper's approach is right; skip AccessibilityService.

## What we still need from you

- **Exact models:** which AirPods/Beats, which Soundcore, and the generic buds' name as Android
  shows it (Settings → Connected devices).
- **Captures for C2 and E2:** a Bluetooth HCI snoop log of the AirPods with an iPhone/Mac (or with
  LibrePods/CAPod), and of the Soundcore with the Soundcore app, changing each setting once. A
  how-to will go in `docs/research/` when D2/D3 start.
- **Decisions now:**
  - Do Sony as Experimental in 0.7 without an owner?
  - Should Play get the Shizuku features at all? This one can wait until 0.8.
