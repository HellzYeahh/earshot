# Earshot roadmap: 0.9 shipped, now 1.0 gets ready for Google Play

Written 2026-10-20, after 0.5.0 (Controls). Updated 2026-10-21 for release 0.75. Research behind
it: [headset landscape](research/headset-landscape-2026-10.md) and the reports in
[research/reports](research/reports).

**0.6 and 0.7 were merged into 0.75.** 0.6 was never released and 0.7 was never started as its own
release: what shipped is the "0.75.0" section of [CHANGELOG.md](../CHANGELOG.md). The original 0.6
and 0.7 plan stays below as the record, with every item marked **Done**, **Partly**, **Not done**
or **Dropped**.

## After 0.8.0: the road to 1.0 (set 2026-10-26)

0.8.0 shipped on 2026-10-26: the JBL driver on Bluetooth LE, Find my earbuds, the Bluetooth
history, codec switching through the companion link, presence wake-up, modes saved by meaning, one
shared session base for every driver and the driver conformance suite ([CHANGELOG](../CHANGELOG.md)).

The user's plan for the last two versions:

- **0.9, "Tie it together":** make everything smooth and coherent across brands and screens, and
  add the ideas that make Earshot stand out and feel special. The plan is under
  [0.9 plan](#09-plan-tie-it-together) below.
- **1.0, the final release:** optimise the app, its code and its performance, then the store
  work. Google Play preparation (the old item 7: target 37, `QUERY_ALL_PACKAGES` and
  `RECEIVE_BOOT_COMPLETED`, an upload key, CI, a licences screen, the `.foss` application ID),
  F-Droid / IzzyOnDroid, localisation and the per-vendor write-path security review all move here.
  **Since 2026-10-27 this comes first:** see [1.0 plan](#10-plan-ready-for-google-play-set-2026-10-27).

## 1.0 plan: ready for Google Play (set 2026-10-27)

The user's decisions on 2026-10-27, right after 0.9.0 shipped:
- Get Earshot ready for its official release on Google Play **now**. That covers signing, a full
  code audit, optimising the app and working out the kinks.
- Split it into a **Google Play version** and a **GitHub version**.
- Later, the Play version gets a **paid unlock**, which must not be started before the release
  work is done.
- The rest of the 0.9 plan below follows where it helps the release.

**What 1.0 promises:** everything Earshot shows either works or says why not, it never harms a
headset, and updates never lose your settings. Still no internet permission, no account and no
analytics.

### Two versions from one codebase

| | Google Play | GitHub (also F-Droid / IzzyOnDroid) |
|---|---|---|
| Gradle flavour | `play` | `foss` |
| Application ID | `dev.earshot` | `dev.earshot.foss` |
| Signing | an upload key; Google keeps the app-signing key (Play App Signing) | our own release key, kept and backed up by the user |
| Price | free download; later one $3.99 unlock for the Sleep features and extra themes | everything free; no billing code in the APK |

**Why flavours instead of two git branches:**
- With two branches, every fix would have to land twice and the branches would drift apart.
- With flavours, one `main` builds both versions.
- Code for one version only lives in `app/src/play/` or `app/src/foss/`, so the GitHub APK never
  contains the paid-unlock code.

**Leaving today's builds** (`dev.earshot`, signed with the debug key):
- Android can't update an app across a signing-key change.
- So a last debug-signed 0.9.x on GitHub adds a settings export first.
- People then install the new build, import their settings and remove the old app. The new GitHub
  build spots the old app and offers that.

### The work, in order

1. **Signing and identity.**
   - The flavours and app IDs above.
   - Signing configs read `keystore.properties`, which is never committed. Without that file,
     release builds come out unsigned, never debug-signed.
   - The user makes the keys with `keytool`, so the passwords never pass through Claude, and backs
     them up. The steps are in `docs/release-signing.md`.
   - Settings export and import: one file through the system file picker, and a restart to apply
     it. Moving to a new phone is allowed; cloud backup stays off.
2. **Release gate (CI).** GitHub Actions runs on every push and tag:
   - the protocol, dsp and app tests and lint;
   - both release builds;
   - an APK check: no INTERNET permission, the version matches the tag, What's New is bumped, and
     the APK stays under its size budget.

   A release only goes out from a green gate.
3. **Reliability and a full code audit.**
   - Audit every package for:
     - correctness;
     - every vendor's write path against its guard;
     - Android 17's background rules;
     - leaks and threading;
     - logs without personal data.
   - Verify each finding, then fix them.
   - Measure on the Fold7, and keep the numbers as 1.0 targets:
     - connect time and success rate;
     - background survival on One UI;
     - Earshot's own battery cost;
     - a full overnight Sleep run.
4. **Honest support.**
   - One tier label (Tested / Experimental / Battery only / Any headset) wherever it matters.
   - A report loop through the share sheet.
   - Owners' reports move drivers to Tested.
5. **Play Store readiness.**
   - Target API 37.
   - Permissions:
     - replace `QUERY_ALL_PACKAGES` with `<queries>`;
     - justify or drop location, usage access and the other sensitive permissions;
     - the alarm still works when Play doesn't grant `USE_FULL_SCREEN_INTENT`.
   - Play Console forms:
     - the foreground-service declarations (connected device, media playback, microphone);
     - a privacy policy and the Data safety form (no data collected);
     - the content rating;
     - developer verification.
   - In the app: a licences screen (the OFL fonts and the libraries).
   - The listing:
     - text and screenshots with plain brand wording and "not affiliated";
     - a decision on Shizuku in the Play version.
6. **Performance and size.**
   - Startup (a baseline profile).
   - Smooth scrolling on the Fold.
   - The cost of the always-on service.
   - APK size: re-encode the sleep recordings.
   - R8.
7. **Kept out of 1.0:**
   - new drivers;
   - big new features;
   - translations beyond what someone maintains (the strings are ready to translate).

### Decisions for item 5 (user, 2026-10-29)

Research: [research/play-readiness.md](research/play-readiness.md).
- **Wi-Fi trigger:** kept in both versions. Earshot explains before asking for location, and the
  user files Play's precise-location declaration when it opens (November 2026).
- **Shizuku:** GitHub version only. Play users switch codecs through the companion link
  (Android 13+).
- **Target API 37 for 1.0:** done 2026-10-30. The phone pass on the Fold7 passed (research section
  H, numbers in `docs/reliability.md`).
- **Play account:** new and personal, so a 14-day closed test with 12 testers comes before
  production (`docs/store/closed-test.md`).

### After the release work: the paid unlock (Play only, not started)

- **What it is:** a one-time $3.99 in-app product unlocks the Sleep tab's features and extra
  themes. Everything else stays free. The GitHub version has no paywall and no billing code.
- **To check first:**
  - The Play Billing library must not add the INTERNET permission to our manifest; purchases go
    through the Play Store app.
  - Purchases are restored after a reinstall.
  - What the free Play version shows on the Sleep tab.
- **To build:** the extra themes still need designing. The five theme mockups from 0.75 are a
  starting point.

## 0.9 plan: tie it together

**0.9.0 shipped on 2026-10-27 with the Sleep tab (C9) and a new What's New sheet.** The rest of
this plan follows in 0.9.x updates, after the 1.0 store-readiness work above (user decision
2026-10-27). A small 0.9.x on GitHub carries the settings export (1.0 plan, item 1).

Drafted 2026-10-26 from three inputs, all in [research/plan-0.9](research/plan-0.9):
- a code audit of every screen for rough edges ([ux-audit.md](research/plan-0.9/ux-audit.md), ~50 findings);
- a scan of competitors, community requests and Android 13–17 APIs, which gave 39 ideas
  ([ideas.md](research/plan-0.9/ideas.md));
- Jev's scores for each idea's rarity, everyday value and offline fit
  ([idea-scores.md](research/plan-0.9/idea-scores.md)).

The picks below are Claude's calls on top of those scores.

**What 0.9 is:** no new protocol drivers. Everything Earshot already does should feel like one
app, the same for every brand, and a few features should make it the app people recommend. 0.9 is
done when a new user with any headset finds every feature without help, every screen speaks the
same language, and the standout set below ships.

### A. Housekeeping first (small, ship in the first week)
- **What's New:** done in 0.9.0 (the sheet carries 0.9 and 0.8, which shipped without one). Still to
  do: a release-checklist step so a stale sheet can't be missed again.
- **Mark the Sleep A30 as Tested** (it is `verified = false` with "hasn't been tested" text, but it
  is tested on our own pair).
- **Demo mode works without the Bluetooth permission** (today `gated` blocks it, so a reviewer
  without a headset sees nothing).
- **Codec banner:** offer "Link this headset" (the no-Shizuku path) next to the Shizuku hint.
- Unused strings, the history clear button without a confirm, the 24-hour-only time format, preset
  chips without selected semantics (ux-audit "quick wins").

### B. Tie it together (the audit's themes)
1. **One Headset page per device.** Identity, Headphone type, rename / power off, the universal
   remote, EQ memory, button shortcuts, its Bluetooth history and "what works here" in one place,
   reachable from Home. Today they are spread over six screens.
2. **One status language.** "Connected" means three things today (control link, audio link,
   companion link). Name them once and use the same words on Home, the tile, the widget and the
   notification. Home stops saying "not connected" while music plays.
3. **Tier as a component.** Tested / Experimental / Battery only / Any headset, shown where it
   matters with a one-line "what works", the same component for every brand.
4. **Errors that carry a remedy.** Typed causes with an action button ("Close the JBL app", "Use as
   any headset", "Open Headphone type") instead of 4-second snackbars. Move the service-layer English
   into `strings.xml` on the way (it also prepares 1.0's localisation).
5. **"Link once" as the setup step.** It unlocks connect / disconnect, auto-start and codec switching
   without Shizuku; say so in onboarding and on the Headset page.
6. **Discoverability:** Find my earbuds and the Bluetooth history get their own entries (history is
   hidden in the Lab today), Tools is regrouped, and the app offers the tile and the widget.
7. **One mode shortlist** that feeds the notification, the tile and the widget (today favourites
   and the tile choice are separate).
8. **A report loop for Experimental headsets:** share sheet instead of copy-only, and a guided
   checklist ("test pilot") that turns owners into verifiers; it is how drivers move to Tested.

### C. Standout features
Picked for 0.9, in this order:
1. **Headset profiles + "My headsets".** Per-headset EQ, start volume and ceiling, default mode,
   codec and the rules bound to it, with a home for every headset showing last battery, last seen
   and its profile, even when none is connected. It builds on B1 and on the per-headset memory and
   A7's per-address refs, and the ideas below hang off it.
2. **Tuned on arrival (AutoEq by name).** Earshot recognises the headset by name and offers its
   correction curve in one tap. It ships first with a user-imported AutoEq folder, because the
   measurement data's licence is unsettled (ideas §1 and §6); bundling waits for that answer.
3. **EQ follows what you hear:** separate curves for music, video, calls / voice, per app and per
   listening mode (`eq-follows`, the top score).
4. **Volume guard:** start volume per headset, a hard ceiling, a quieter floor for headsets whose
   lowest step is too loud.
5. **Battery health and smart alerts:** life per charge over time, one bud draining faster, case low,
   buds left out of the case.
6. **Private listening log:** weekly hours, time at high volume, codec and mode per headset, on the
   phone only, with nudges you control (an honest estimate: Android has no app API for its sound dose).
7. **Automations upgrade:** ready-made recipes (Commute, Gym, Sleep, Work call…), a rule explainer
   ("why didn't it fire?"), and more triggers (battery, charging, mode change, call, another Bluetooth
   device such as the car).
8. **Everyday shortcuts:** launcher shortcuts, Live Updates while Find or a sleep timer runs,
   optional earcons for mode and EQ changes on headsets that stay silent.
9. **Sleep tab** (the user's request, 2026-10-26; grows the old "sleep kit" idea): a fifth tab with
   a 15 min – 6 h sleep timer, recorded and generated sounds plus the user's own files, noise
   cancelling off during the night, an end action (stop, disconnect or power off) and an alarm
   that rings in the earbuds, on the phone or both, with the phone as a safety net. Any headset;
   the Sleep A30's own timer and alarm come last. Design and build order:
   [sleep-mode.md](sleep-mode.md).

If time allows: Android's **Device controls** panel, a **switch headset** tap, **last
seen** on Find, a **connection doctor** on top of the Bluetooth history, read-back **confirmed
badges**, a **privacy receipt** screen (it shares work with 1.0's licences screen) and a
**codec explainer** on the Codec screen: why a codec such as aptX Adaptive isn't offered, with the
LE Audio, USB-C and dongle routes ([research/aptx-adaptive-samsung.md](research/aptx-adaptive-samsung.md)).

Parked: personal hearing EQ (needs careful non-medical framing), blind A/B tests, latency lab,
head-tracking lab, AppFunctions and a Tasker API, dictation, read-aloud, the phone as remote mic,
Auracast status, fold-adaptive layouts (with 1.0's performance work), settings backup files.

### D. Decisions for the user
- Does the Headset page (B1) replace Tools → Controls, or sit next to it?
- The tier names users see (keep Tested / Experimental / Battery only / Any headset?).
- "Tools never hide settings" vs rows that can never work on a headset: the audit suggests keeping
  them visible but opening an explanation instead of sending a command that is sure to fail.
- AutoEq: ask the measurement sources about bundling, or stay import-only?

### E. Order and gate
1. A (housekeeping), then B2–B4 (status language, tier component, remedies): they touch every screen.
2. B1 + C1 (Headset page and profiles) together: the structural change.
3. C2–C4 (sound), then C5–C6 (battery and log), then C7–C8 (automations, shortcuts) and the rest of B.
   C9 (Sleep tab) runs alongside: its sound engine and screen touch no shared files (sleep-mode.md §8).
4. Gate for 0.9.0: the full test suite, a manual pass on the QC Ultra 2 and the Sleep A30, demo mode
   for every driver, and the What's New sheet.

## Status after 0.75

0.75 headline: *Earshot works with any Bluetooth headset. Tested: Bose QC Ultra (2nd Gen) and the
Soundcore Sleep A30. Experimental: the other Bose models, Sennheiser, Sony, 35 Soundcore models and
Skullcandy Crusher 1080 + Method 360/540. Battery only: AirPods and Beats.* Also new: the universal
remote (Companion Device Manager), the Field theme and the "Strike" icon.

### Done

- **A1** `VendorDriver` registry with `GENERIC` as the mandatory fallback. Unknown headsets are no
  longer treated as Bose, and a headset forced to "Headphone type: Bose" no longer gets the
  `deca-fade` lookup unless it looks like, or answered as, a Bose (D6 acted on).
- **A3** `ConnectionState.audioLinked`: calls, automations and the background service follow the
  audio link.
- **A4** Typed `ConnectionProblem.ChannelHeld(app)`; "Close the … app" names the real app.
- **A5** Capability flags, enough for generic headsets (`ownSettings`) and per vendor. Tools never
  hide a setting (user decision): a refusal comes back as a message.
- **B1** Battery for any headset (Android's broadcast, Shizuku dumpsys), **plus** the standard
  Battery Service read over our own GATT client, so LE-capable headsets show a level without Shizuku
  (research update item 4).
- **B2** Codec readout without Shizuku (switching still needs Shizuku).
- **B3** A Pro EQ preset per headset, applied on connect (explicit picks only). The AutoEq
  suggestion matched by name was not built; AutoEq import is still manual.
- **B5** Brand-neutral strings, README and About, with a not-affiliated line and trademarks for
  every brand named.
- **C1** AirPods / Beats left, right and case battery from Continuity adverts. **C3** is a demo
  device only (no AAP).
- **E3** Sony MDR v2 driver, Experimental (WH-1000XM5 / XM6, WF-1000XM4 / XM5 / XM6, LinkBuds, ULT
  WEAR, WH-CH720N, WF-C510 / C700N / C710N). The WH-1000XM4 (v1) stays generic.
- **Sennheiser** GAIA v3 driver, Experimental, pulled in from 0.8 together with a shared GAIA v3
  codec in `:protocol` (research update item 7).
- **Skullcandy Method 360 / 540 ANC** on the Bragi stack (ranked #2 in the brand research).
- **F1** `VendorControl` / `VendorControlsCard` / `setVendorControl`: vendor extras without core changes.
- **Not in the plan:** the Field theme, the "Strike" icon, the stuck-socket fix (GitHub #1) and the
  call-boost fix.

### Partly done

- **A2** Identify before connecting: each driver identifies by its own name map and service ID, and
  connects by that UUID only. The shared ranked `DeviceIdentifier` (SDP, DIS, Fast Pair, LE
  Appearance) is not built.
- **B4** The device switcher lists every paired headset and leaves out car kits, speakers and
  watches. Tier badges in onboarding and the picker are not built.
- **E2** Soundcore: the Sleep A30 driver is tested on one pair, 35 models are recognised on the
  vendor UUID, and a per-model write allow-list covers the documented families. Writes ship from
  references (OpenSCQ30 facts, soundcorebridge), not from owner captures, so every model except
  the Sleep A30 is Experimental. Still open: Space 2 writes with its init script, EQ writes, the
  TLV generation (Liberty 5 Pro and Pro Max).
- **F3** Copy connection report has existed since 0.3 and Lab now scans each supported stack
  read-only. The anonymised export with SDP / GATT lists and a diagnostics timeline is not built.
- **F4 / research update item 1** The **universal remote** is in: link once, then connect and
  disconnect (Android 17), rename (13) and forget (16) for any brand; `compileSdk` is 37. Not done:
  presence binding, and codec switching without Shizuku by reflection plus the association (D4).

### Not done, or decided against for now

- **A6** Shared session base and **A7** modes stored by meaning. The brand research said to attach
  both to the first of Sennheiser and Sony; they were not done. Sennheiser, Sony and Soundcore each
  have their own session and state mapping, so both are now overdue (0.8, before another driver).
- **C2** AirPods modes: a go/no-go that stays "no-go" until a One UI 9 test (D3). Point users to
  LibrePods / CAPod for modes.
- **E1** JieLi RCSP: waits for D1 (our generic buds).
- **E4** Fast Pair baseline: expected to shrink to identification plus advert battery (D5).
- **E5** QC35 out of UNSUPPORTED: stays a stretch goal; Gadgetbridge covers it.
- **F2** Shared driver conformance suite: each driver has its own end-to-end test on its virtual
  device (`VirtualEndToEndTest`, `SennheiserEndToEndTest`, `SonyEndToEndTest`,
  `SoundcoreEndToEndTest`, `MethodEndToEndTest` and others), not one shared class.
- **F5** Play hygiene: `QUERY_ALL_PACKAGES` and `RECEIVE_BOOT_COMPLETED` are still in the manifest;
  no release upload key, no CI, no licences screen.
- **B6** Find my earbuds, **F6** diagnostics timeline, **D7** MICS spike: not started.
- **Spikes:** D1 (generic buds), D3 on One UI 9, D4 codec through an association, D5 (Fast Pair
  stream) and D7 are open. D2 is answered by the Sleep A30; the desk answers for D3 and D4 are in
  the progress notes below.

### What moved

- Sennheiser: 0.8 → 0.75, with the shared GAIA codec.
- Sony and the Skullcandy Method buds: planned for 0.7 → shipped in 0.75.
- Universal remote (F4): "after 0.6" → in 0.75.
- JieLi, AirPods modes, Fast Pair, Find my earbuds, diagnostics, Play hygiene: 0.7 → 0.8 or later.

### Next: 0.8

1. **GATT transport** (scan, MTU, write-with-response, notify reassembly, rotating LE addresses).
   It unlocks JBL (H-AA, then H-P4: 104 of the 123 products in JBL's app connect over BLE),
   Sennheiser CX Plus / CX 500 and Sony over LE Audio.
   **Built (2026-10-25), untested on hardware:** `bt/GattTransport` (Android-free core behind
   `GattPort`), `bt/GattLink` (the Android side, plus `GattTargets` for a `<name>-LE` address by
   a near-only scan), `GattLinkSpec.HARMAN` / `.AIROHA`, firmware services refused. Frames are
   reassembled by each vendor's frame buffer, as on RFCOMM. No driver uses it yet: JBL H-AA is next.
2. **Airoha RACE allow-list: the decision.** An allow-listed codec is the only way to JBL's Airoha
   models and Skullcandy's 2022–25 line. Keep "never" for Sony, Bose and Bragi BOS-RPC. Build only
   after an explicit yes.
   **Decided yes and built (2026-10-25):** `:protocol/airoha` (`RacePacket`, `RaceFrameBuffer`,
   `RaceRequestQueue`, `RaceWriteGuard` with the Airoha-wide never list and per-vendor lists),
   `bt/RaceConnection` and `bt/AirohaRaceLink` (SPP by UUID or Airoha GATT; refused on Sony, Bose
   and Bragi). The relay (`0x0D01`) and the Harman tunnel (`0x0093`) are refused outright until a
   capture shows their wrappers. JBL A-RACE and the Skullcandy line still need their drivers.
3. **CDM presence** (replaces the boot / ACL receiver) and the codec-through-association experiment.
   **Built and tested on the Fold7 (One UI 9, Android 17), 2026-10-25:** `bt/CompanionPresence` +
   `service/HeadsetPresenceService` for linked headsets: Android 16 events by association id
   (verified: `EVENT_BT_CONNECTED` → connect), 12–15 wait for the audio link (best effort, the ACL
   receiver stays the fallback). Android rebinds the service ~10 s after the process dies; a fresh
   start reconnects unless the last exit was the user's force-stop (verified after a reinstall). The
   ACL receiver stays for unlinked headsets, and BOOT_COMPLETED stays too: process start re-arms
   the schedule alarms. **D4 answered yes:** codec read and switch through the association
   (`codec/AssociationCodecPath`) work on the Fold7 without Shizuku; Shizuku stays the fallback.
4. **Find my earbuds** (B6): a one-sided tone started from the UI, tile, widget or notification.
5. **Diagnostics timeline** (F6): disconnect reasons, bond loss, encryption changes, call-codec label.
6. **Spikes:** D1, D3 (One UI 9), D4, D5, D6 (read the QC Ultra 2's SDP record), D7.
   **Status 2026-10-25:** D4 answered yes on the Fold7 (item 3). D6 read from the Fold7's cache:
   the QC Ultra 2 lists `deca-fade`, SPP and four 128-bit UUIDs (`346d5c4b…`, `77265d41…`,
   `88b0ee3c…`, `9b26d8c0…`) that the QC Ultra Earbuds 2 in GitHub issue #3 list too: a Bose
   fingerprint worth checking for that issue. D7 so far: the QC Ultra 2's LE record shows BAS,
   VCS, LE Audio and BASS, no MICS. Open: D1 (needs the generic buds), D3 (needs the AirPods),
   D5 (Fast Pair stream; ask before opening it, GMS holds it).
7. **Moved to 1.0 (2026-10-26; before that to 0.9).** **Play hygiene** (F5) and the target-37 checklist: drop `QUERY_ALL_PACKAGES` and
   `RECEIVE_BOOT_COMPLETED`, an upload key, CI, a licences screen, a separate `.foss` application ID.
   `setStreamVolume` from a background-started service needs while-in-use status at target 37, so
   the call boost has to be checked.
8. **Done (2026-10-25).** **Module split** (`:vendor-*`), with A6 and A7 first, and the shared conformance suite (F2).
   The split is option 2: `arch/VendorBoundaryTest` keeps vendor stacks out of the neutral packages with an
   empty baseline, and the Sidetone and ButtonAction wire codes moved to the Bose stack; real `:vendor-*`
   Gradle modules wait until a vendor needs to ship on its own.
9. **Promoting drivers:** owner reports and captures move Soundcore, Sennheiser and Sony from
   Experimental to Tested; then Soundcore Space 2 and EQ writes, Sony v1 (WH-1000XM4), Sennheiser BLE.
10. **More drivers by demand:** Pixel Buds Pro (MIT pbpctrl) ahead of Galaxy Buds. **Dropped from 0.8**
    (2026-10-25): a driver that serves one product line waits until there is demand.

### Order for the rest of 0.8 (set 2026-10-25)

Release 0.8.0 once these are done; no partial release.

1. **Item 4, Find my earbuds** and **item 5, diagnostics timeline**: no new hardware needed, both
   testable on the Fold7 with the QC Ultra 2.
2. **Item 6, the spikes** that need no other device (write up D4, D6, D7); D1 and D3 when the
   generic buds and the AirPods are at hand; D5 only after an explicit yes.
3. **Item 7, Play hygiene and target 37: moved to 1.0** (user decisions 2026-10-25 and 2026-10-26: no
   Google Play preparation before 1.0). It stays as listed under "Next: 0.8" item 7; the OFL notices for the
   bundled fonts and the dependencies' licences belong in the licences screen it adds.
4. **Done (2026-10-25).** **Item 8, A6 + A7 + F2, then the module split**, before any new driver, as this roadmap
   already says. Gate: the Bose end-to-end tests stay green and a manual pass on the QC Ultra 2.
5. **Done (2026-10-26).** **Drivers on the new platform:** JBL H-AA over GATT (jbl-control, MIT), Experimental: no
   hardware here. 33 H-AA models by exact name and product id; Protocol 4 (H-P4) and JBL's Airoha
   models are refused by product id and wait for their own drivers. Item 10's Pixel Buds Pro driver is dropped from 0.8 (user decision 2026-10-25):
   its protocol serves Pixel Buds only.
6. **Item 9** moves drivers to Tested only with owner reports; the reference-only writes (Soundcore
   Space 2 and EQ, Sony v1, Sennheiser BLE) can ship as Experimental before that.

## The goal

**1.0: the one companion app you need for your Bluetooth headset** when you don't like the maker's
app. Open source on GitHub, on Google Play, and on F-Droid/IzzyOnDroid. Earshot stays what it is now:
fast, account-free, no internet permission. Bose stays its deepest integration.

Versions: **0.75** (done: the 0.6 and 0.7 plan below) added features and drivers and did the
research. **0.8** (done, 2026-10-26) collected the big platform changes. **0.9** ties everything
together and adds what makes Earshot special; **1.0** optimises the app and ships it to the stores.

## Rules for getting there

1. **The Bose core doesn't change.** `QcUltra2Protocol`, `StateMapping`, `ConnectionFacts`,
   `BmapConnection`, `BoseLinkFactory`, `:protocol/qcultra2` and the Bragi stack move behind a
   driver seam as they are. A refactor that touches their behaviour needs the Bose end-to-end tests
   green before and after, plus a check on the QC Ultra 2.
2. **Every headset gets the Android-level features**, whether or not Earshot knows its protocol.
3. **Only real hardware makes a vendor "Tested".** Built from references plus a virtual device
   only = "Experimental" (the Crusher, Sennheiser, Sony and most Soundcore models today).
4. **No internet permission, ever.** Device reports leave the phone only through the share sheet.
5. **Clean-room for GPL references.** Facts from GPL/AGPL projects, code only from MIT/Apache/BSD
   sources, credited in `docs/reference/` and About (see the licence rule in the research file).
6. **Each vendor has its own WriteGuard.** Firmware update, factory reset, clear pairing and
   flash/RAM commands are refused on the single write path, as `bt/WriteGuard` does for BMAP.
   Airoha RACE is allow-list only.
7. **Never probe a headset with another vendor's protocol.** Identify first, then open only that
   vendor's channel.

## Support tiers (used in the app, README and store listing)

The README now calls the middle tiers **Tested** and **Experimental**, and adds **Battery only**.

| Tier | What you get | Who |
|---|---|---|
| **Any headset** | Pro EQ (with a per-headset preset), battery as Android reports it, codec readout (switching with Shizuku), call audio, mic check, Controls remapper, automations, tile/widget for EQ, universal remote | every Bluetooth headset |
| **Battery only** | Any headset, plus left / right / case battery from the headset's adverts | AirPods, Beats |
| **Tested** (was "Supported") | Plus the headset's own controls: modes, noise, its EQ, buttons, settings | vendor driver, tested by us on real hardware: Bose QC Ultra 2, Soundcore Sleep A30 |
| **Experimental** | Same as Tested, marked "untested on real hardware, report back" | vendor driver from references + virtual device: other Bose models, Sennheiser, Sony, Soundcore models, Skullcandy |

## 0.6 (merged into 0.75): "Any headset"

Theme: make Earshot useful and safe on every headset, put the vendor seam in place, and add the
first new vendor we can test (AirPods). User-visible headline: *works with any Bluetooth headset;
deep control for Bose and AirPods.* (In 0.75 the AirPods part became battery only.)

### Progress (2026-10-20)

- **Done, first step of A1 + B4:**
  - `DeviceFamily.GENERIC` with `GenericHeadphoneRepository` (audio link, name, battery broadcast;
    never changes its settings). Unknown names are no longer Bose.
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
    latency are hidden for generic headsets. (Superseded in 0.75: Tools never hide a setting.)
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
    the next protocol driver. (0.75 added three more drivers without it: now overdue.)
  - A7 (modes by meaning + migration): no second vendor has modes yet, and the migration should be
    tested against one. (Sennheiser, Sony and Soundcore now have modes: now overdue.)
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
  (Update: the `deca-fade` lookup is also gone for such a device; see the CHANGELOG, "Fixed".)
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
| A1 | **Done.** **Unknown headsets are no longer treated as Bose.** Introduce a `VendorDriver` registry (id, how it recognises a device, competing-app name, `createRepository()`, demo devices) and a `GENERIC` driver, replacing the `DeviceFamily {BOSE, BRAGI}` enum. Only the Bose driver may run the BMAP channel search. | `bt/DeviceFamily.kt:16` sends every non-Bragi name to BOSE, so the BMAP probe runs against AirPods or Sony today. `ConnectionManager.kt:183,347`, `RoutingHeadphoneRepository.kt:44-87`. | M |
| A2 | **Partly.** **Identify the headset before connecting.** A `DeviceIdentifier` combining the name, SDP service UUIDs (`fetchUuidsWithSdp`), BLE advert company ID/service UUIDs and the Fast Pair model ID gives a ranked vendor/chipset guess (Bose, Apple, Sony, JieLi, Bluetrum, Airoha, Qualcomm, BES…). The router picks the driver from it. | Research file, "Chipsets". Pure function + tests; the Android lookups behind an interface. | M |
| A3 | **Done.** **Audio link separate from control link.** `ConnectionState.audioLinked` (A2DP/HFP/LE profiles) next to the control status. Automations, the tile, EQ presets and call audio key on the audio link, so they work for headsets without a driver. | `AutomationEngine.kt:57-117` gates every rule on the control link today. | M |
| A4 | **Done.** **Typed connection problems.** `ConnectionProblem.ChannelHeldBy(appName)` and friends instead of matching "Close the Bose app" strings; the UI stops using `state.crusher == null` to detect the vendor. | `ConnectionManager.kt:351`, `DisconnectedCard.kt:59-63`, `ConnectionScreen.kt:123`. | S |
| A5 | **Done** (as capability flags per feature, with "Tools never hide a setting"). **Capability flags for every optional feature** (EQ bands, sidetone, auto play/pause, multipoint, prompts, buttons, rename, power off, auto-off, LE Audio, latency, volume, noise steps), or "null = absent" made explicit. | `HeadphoneCapabilities` (`HeadphoneModels.kt:233-275`) covers noise and modes only. | M |
| A6 | **Done (0.8, 2026-10-25).** `service/session/SessionRepository` over a `LinkSource`, `StateCommands` for the replace-state stacks (Bose, Crusher), one `DevicePrefs`/`DeviceFinder` in AppContainer, `hint_*` link-hint keys next to the legacy ones; Bose checked by a characterization test of the old class. **Shared session base.** Pull the ~200 lines duplicated between `ServiceHeadphoneRepository` and `CrusherHeadphoneRepository` into one `SessionRepository<Link, Protocol>`. One `DevicePrefs`/`DeviceFinder`, with per-vendor link hints under namespaced keys. Bose behaviour unchanged (end-to-end tests are the gate). | Audit §1. Needed before vendor #3, or AirPods becomes a third copy. | M |
| A7 | **Done (0.8, 2026-10-25).** `core/ModeMeaning` (`ModeMeaning`, `ModeRef`, `ModeResolver`); `ModeRefMigration` binds 0.75 slots on the first headset with modes and keeps them as `legacySlot`, never landing on Off. Shortcuts store Bose button codes, not modes, so they needed no migration. **Modes stored by meaning, not slot number.** Rules, the tile and shortcuts store QUIET / AWARE / OFF / ADAPTIVE / custom + device address, with a one-time migration of saved rules and shortcut prefs. Remove the Quiet/Aware/Immersion fallbacks. | `RuleRunner.kt:78`, `TileCycle.kt:7`, `ShortcutAction.kt:48`, `AutomationAction.SetNoise.spatialAudioWire`. AirPods have Off/ANC/Transparency/Adaptive. | M |

### B. "Any headset" features

| # | Item | Why / where | Size |
|---|---|---|---|
| B1 | **Done** (plus own-GATT Battery Service, research update item 4). **Battery for any headset** from `BATTERY_LEVEL_CHANGED`, Apple `+IPHONEACCEV`, Plantronics `+XEVENT` and BLE BAS. The initial value comes from the Shizuku dumpsys when available. Home shows a battery card for driver-less headsets. | All of these reach a normal app with `BLUETOOTH_CONNECT` (research file, "What Android gives"). | M |
| B2 | **Done.** **Codec readout without Shizuku** from `ACTION_CODEC_CONFIG_CHANGED`. Shizuku stays for switching. Fix `A2dpProxyHolder.pickSink` preferring Bose names. | `codec/A2dpProxyHolder.kt:61-67`. Better story for Play. | S |
| B3 | **Done**, except the AutoEq suggestion by name. **Per-headset EQ preset**, applied when that headset connects, with an AutoEq suggestion matched by name (AutoEq is MIT; ship a compact index of popular models, no network). | Uses A3. Makes Pro EQ the reason to install Earshot for any headset. | M |
| B4 | **Partly** (the switcher lists every paired headset; no tier badges yet). **Neutral onboarding and picker.** List every paired audio device with its tier badge (Supported / Experimental / Any headset) instead of sorting "Bose-looking" first. | `OnboardingScreen.kt:165-200`, `DeviceFinder.NAME_HINTS` (`DeviceFinder.kt:230`) moves into the drivers. | S |
| B5 | **Done.** **Neutral strings and README.** Product strings name the connected headset; the Bose-only ones stay on Bose screens. A not-affiliated line for every brand named. | 22 Bose strings, `EarshotWidget.kt:216` "No Bose headphones paired", About. | S |

### C. AirPods / Beats (first new vendor; we own a pair)

| # | Item | Notes | Size |
|---|---|---|---|
| C1 | **Done.** **Battery and case from BLE Continuity adverts** (company `0x004C`): L/R/case, charging, lid, in-ear. No connection needed, works on every Android version. Needs `BLUETOOTH_SCAN` with `neverForLocation`. | Read-only and low-risk. | S–M |
| C2 | **Not done (no-go until the One UI 9 test).** **Listening modes over AAP** (L2CAP PSM `0x1001`): Off / ANC / Transparency / Adaptive, conversational awareness, ear detection, press-and-hold, rename. **Gated to Android 17+**, where the L2CAP path reportedly works without root. Experimental until verified on our One UI phone. | References are GPL (LibrePods, CAPod): **clean-room from our own HCI captures** of the AirPods talking to an iPhone/Mac plus documented facts. New L2CAP `ByteTransport`. | M |
| C3 | **Partly** (the demo device only; no AAP to emulate). **Virtual AirPods** (adverts + AAP) and end-to-end tests, same pattern as `VirtualCrusher`. | Also the demo device. | S–M |

### D. Research spikes in 0.6 (answers feed 0.7)

1. **Our generic Chinese buds:** run A2's identifier on them. JieLi (`0xAE00`, company `0x05D6`), Bluetrum (`0xFDB3`) or something else? Is RCSP switched on? This decides 0.7's chipset driver. **Open.**
2. **Our Soundcore:** exact model, protocol variant, and its state-update layout from a capture. **Answered:** Sleep A30 (D1301), read and written on the real pair.
3. **Android 17 L2CAP on One UI:** can a normal app open PSM `0x1001` to AirPods? (Blocks C2.) **Open** (the desk answer says no public way).
4. **Hidden-API status:** look up `getBatteryLevel`, `BluetoothA2dp.getCodecStatus`/`setCodecConfigPreference` in Android 16/17 `hiddenapi-flags.csv`. Then check whether a Companion Device Manager association lets a normal app switch codecs without Shizuku. **Desk part answered; the association test is open.**
5. **Fast Pair Message Stream:** can a third-party app open RFCOMM `df21fe2c-…` while Google Play services holds it? (Cross-brand battery/ring/ANC-read in 0.7.) **Open**, expect no.
6. **The `deca-fade` UUID:** read the QC Ultra 2's SDP record. If it is iAP2, drop `SDP BMAP` from the BMAP candidates, or move it behind a Bose-only check. **Acted on:** only a headset that looks like or answered as a Bose gets the lookup. Reading the record is still open.

### 0.6 is done when (outcome in 0.75)

- A non-Bose headset never sees a BMAP frame (test: AirPods/generic buds through the router with a
  spy transport). **Met:** unknown headsets are generic, and a forced Bose type only looks up the
  serial service.
- Bose behaviour is byte-for-byte unchanged (`VirtualEndToEndTest`, `ModelProfilesEndToEndTest`,
  `OlderFirmwareEndToEndTest` green; manual pass on the QC Ultra 2). **Tests in place;** the manual
  pass belongs to the release check.
- Generic buds and Soundcore show battery, EQ, codec and automations with no driver. **Met.**
- AirPods battery works on every supported Android version. Modes work on our phone, or are hidden
  with an "Android 17 needed" note. **Battery met; modes not built (C2).**
- Spikes D1–D6 have written answers in `docs/research/`. **Partly:** see D above.

## 0.7 (merged into 0.75): "More brands"

Theme: two or three new drivers chosen by what we can test, a cross-brand baseline, and the
groundwork for Play. Headline: *Supported: Bose, AirPods, Soundcore, JieLi-based earbuds.
Experimental: Sony, Crusher.* (In 0.75: Tested Bose QC Ultra 2 and Soundcore Sleep A30;
Experimental Sennheiser, Sony, Soundcore models and Skullcandy; AirPods battery only; no JieLi.)

### E. Drivers

| # | Item | Notes | Size |
|---|---|---|---|
| E1 | **Not done.** **JieLi RCSP** (if D1 says our buds speak it): battery, EQ, keys, ANC/transparency, find, name. One driver covers QCY, Oraimo, many no-name buds, and is the base for a Redmi dialect later. | **JieLi's own SDKs are Apache-2.0**, so code can be ported with credit, including the connect-time auth. Block OTA, reboot, reset. If our buds turn out to be Bluetrum, do Bluetrum (ABMate commands) instead and block 0x24/0x2F. | M |
| E2 | **Partly** (Sleep A30 tested; 35 models recognised; writes for the documented families; read-only for the rest). **Soundcore**, starting with our model and then its family: modes, EQ (incl. HearID if present), touch actions, auto-off. | GPL references (OpenSCQ30): clean-room from captures. Each model has its own state layout, so add models one at a time behind a model table, like `BoseModel`. | M→L |
| E3 | **Done** (Experimental, v2 only). **Sony (Experimental)**: modes, ANC level, EQ, battery, speak-to-chat, touch modes, multipoint. v2 command set (XM5/XM6, WF-1000XM5, LinkBuds) first. | The best-documented non-Bose protocol, **MIT reference** (mos9527/SonyHeadphonesClient). Ship as Experimental with a virtual device and ask owners for reports. | M |
| E4 | **Not done.** **Fast Pair baseline** (if D5 allows): battery L/R/case and "ring my buds" for any Fast Pair headset. | Public spec, CC-BY/Apache. Small, and it lifts Pixel Buds A, JBL and many others above "Any headset". | S |
| E5 | **Not done** (stretch; Gadgetbridge covers it). *(stretch)* **Bose QC35 / QC35 II out of UNSUPPORTED**: noise `[1.6]`, auto-off, prompts, name, pairing mode, as Experimental. | Plain BMAP (based-connect facts). Lives in the existing Bose stack and catalogue. | S |

Added by the brand research and shipped in 0.75 (not in the original table):
**Sennheiser GAIA v3** (with the shared GAIA codec) and **Skullcandy Method 360 / 540 ANC**.

### F. Platform and quality

| # | Item | Notes | Size |
|---|---|---|---|
| F1 | **Done.** **Vendor extras without core changes:** a generic `VendorControl` list (toggle / slider / choice) rendered by one card, replacing `setCrusherBass` / `HeadphoneState.crusher`. A per-vendor `LabAdapter` for `readRaw`/scan/console. | Audit §6.5. Each new driver otherwise adds fields to core. | M |
| F2 | **Done (0.8, 2026-10-25), session level.** `conformance/DriverConformanceTest`: 17 demo devices × checks C1–C7 (+C4b); repository-level rigs over A6's `FakeLinkSource` are the next step. **Driver conformance suite:** one shared test class every driver's virtual device must pass (connect, refresh, modes, battery push, write refused by its WriteGuard, reconnect). Router tests. | No shared suite today. | M |
| F3 | **Partly.** **Device report:** Lab exports an anonymised report (identifier result, SDP UUIDs, GATT services, a short protocol trace) through the share sheet. Owners of untested headsets can then help without us owning them. | Addresses masked like `captures-galaxy/`. No network. | S |
| F4 | **Partly** (the universal remote is in; presence and codec switching are not). **Companion Device Manager:** associate the headset during onboarding. Use presence events and the background-start exemption instead of the boot/ACL receiver. Codec switching without Shizuku if D4 says yes. | Play-sanctioned path for what `BluetoothConnectionReceiver` does. | M |
| F5 | **Not done → 0.8.** **Play hygiene (no release yet):** drop `QUERY_ALL_PACKAGES` (use a `<queries>` launcher intent), drop `RECEIVE_BOOT_COMPLETED`, a release upload key with Play App Signing, a privacy policy, an open-source licences screen, lint on, GitHub Actions running the unit tests. | Audit "Play risk list". The licence notice for the bosectl captures in `protocol/src/test/resources/captures/`. | M |

### 0.7 is done when (outcome in 0.75)

- Each new Supported driver passed F2 and a manual pass on our hardware. Sony passed F2 on its
  virtual device. **Partly:** the Soundcore Sleep A30 had a manual pass on real hardware; every
  other new driver passed its own end-to-end test on a virtual device. No shared F2 suite.
- A device report from each of our headsets is in `docs/reference/`. **Not done.**
- `assembleRelease` is signed with the upload key, lint runs in CI, and no `QUERY_ALL_PACKAGES`.
  **Not done (F5 → 0.8).**

## 0.8 → 1.0 (outline, plan after 0.75)

See "Next: 0.8" above for the order. The longer outline:

- **Module split:** `:vendor-bose`, `:vendor-apple`, `:vendor-jieli` … with a lint/Gradle rule that
  `ui`, `extras`, `shortcut` and `call` never import a vendor's protocol package. Move Bose wire
  codes still in core (`SpatialAudio.wire`, `Sidetone.wire`, `ButtonAction`) into `:protocol`.
  **0.8:** the rule is `VendorBoundaryTest` (nine neutral packages); Sidetone and ButtonAction moved,
  `SpatialAudio.wire` stays until StateMapping maps the `[31.10]` byte itself.
- **Distribution:** one Play build that works without Shizuku and keeps it optional (with a
  disclosure), or a separate "full" flavour for GitHub/F-Droid. Decide after D4 and F4.
  Reproducible builds for F-Droid. Developer-verification registration (global in 2027).
  Play foreground-service declaration video.
- **Localisation** (strings out of Kotlin first), store assets, screenshots without brand marks
  (the README screenshots still show the pre-0.75 look).
- **More drivers** by demand from device reports: JBL (needs the GATT transport), Galaxy Buds,
  Pixel Buds Pro (MIT reference), Nothing, Huawei, Xiaomi. Sennheiser (GAIA) shipped in 0.75.
- **Security review of every write path** per vendor before the store release.

## Research update (2026-10-21, "App status overview" session)

Full report: `docs/research/reports/Universal headset control accelerators.md` (notes in
`docs/research/research_notes/`). Headline: no standard or Google channel lets a third-party app
switch ANC across brands. The Bluetooth SIG has no ANC spec; Fast Pair "set ANC" needs a GMS-only
account-key MAC; and AOSP restricts VCS/VOCS/AICS/PACS/ASCS/BASS/HAS/CSIS to BLUETOOTH_PRIVILEGED
(Android 13 through 17). "Universal" = phone-side features plus vendor drivers. Changes, with their
status after 0.75:

1. **F4 (Companion Device Manager) moves up, to right after 0.6** as the "universal remote".
   - One association per headset (MAC filter, no re-pair) gives any brand:
     - `BluetoothDevice.connect()/disconnect()` (API 37, CDM or privileged);
     - `setAlias` (31) and `CompanionDeviceManager.removeBond` (36);
     - presence binding (36).
   - Needs compileSdk 37.
   - Also try codec switching by reflection plus the association.
   - **Status: partly done in 0.75.** Connect / disconnect, rename and forget are in, and
     `compileSdk` is 37. Presence binding and the codec experiment are 0.8.
2. **C2 (AirPods modes) is a go/no-go**, decided by a One UI 9 test (see D3). Until then the
   AirPods tier is C1 (advert battery) plus the any-headset features. Point users to
   LibrePods/CAPod for modes.
   - **Status: the interim tier shipped** (battery only). The test is open.
3. **New B6, "Find my earbuds":**
   - A one-sided stereo tone (`AudioTrack`) routed to the headset, plus "last seen" from the
     connection log.
   - Starts only from the UI, tile, widget or a notification (Android 17 background-audio
     hardening).
   - **Status: not started → 0.8.**
4. **B1 extension:** our own GATT client reads BAS `0x180F` (not restricted) for a Shizuku-free
   starting battery on LE-capable headsets (the QC Ultra 2 advertises BAS).
   - New spike **D7**: MICS `0x184D` Mute `0x2BC3`.
   - HFP vendor events use the decimal company ID (".85"); only `+XEVENT/+ANDROID/+CGM*/+XAPL/
     +IPHONEACCEV` are broadcast.
   - **Status: BAS done in 0.75.** D7 is open.
5. **E4 (Fast Pair) shrinks:** AOSP RFCOMM refuses a second client on a channel and GMS opens the
   Message Stream on connect, so expect D5 = no.
   - Keep the `df21fe2c` SDP UUID as an identification signal and the advert battery snapshot.
   - **WriteGuard rule: never write a Fast Pair account key.** The first key becomes the Find Hub
     owner key.
   - **Status: not started.** The rule stands; no driver writes account keys.
6. **New F6, diagnostics timeline:**
   - A2DP/HFP disconnect reasons (API 37), bond-loss and encryption intents.
   - `BluetoothHeadset.getCodecType` (37.2) as the call-codec label.
   - Feeds F3.
   - **Status: not started → 0.8.**
7. **0.8 drivers:**
   - A shared Qualcomm GAIA v3 codec in `:protocol`, with an allow-list that never sends Upgrade
     (feature 6).
   - Sennheiser (MIT momentum4-control) and Pixel Buds Pro (MIT pbpctrl) ahead of Galaxy Buds.
   - E5 (QC35) loses value: Gadgetbridge already covers QC35 and NC 700.
   - **Status: the GAIA codec and Sennheiser are in 0.75.** Pixel Buds Pro is open.
8. **Target-37 checklist:**
   - The call boost's `setStreamVolume` is ignored from a background-started service without
     while-in-use status.
   - No static-final reflection.
   - A separate `.foss` applicationId for GitHub/F-Droid builds.
   - IzzyOnDroid from 0.7.
   - **Status: open → 0.8** (`targetSdk` is still 36; `compileSdk` is 37).

AVRCP play/next never become input events (they go to the media session), so the Controls
remapper's approach is right; skip AccessibilityService.

### Brand research (same day): drivers ranked

Full report: `docs/research/reports/Top brand headset protocols.md`. Its ranking, with the status:

| Rank | Driver | Status after 0.75 |
|---|---|---|
| 1 | Soundcore generic read-only view | **Done** (35 models recognised) |
| 2 | Skullcandy Method 360 / 540 ANC | **Done** |
| 3 | Sennheiser GAIA v3 over RFCOMM | **Done** (Experimental) |
| 4 | Sony v2 over RFCOMM | **Done** (Experimental) |
| 5 | Soundcore gen-2 earbud writes | **Done from references, Experimental** (no owner captures yet) |
| 6 | BLE GATT byte transport | 0.8 |
| 7, 8 | JBL H-AA (done in 0.8), then H-P4 | H-AA 0.8; H-P4 after a capture of a 2025 model |
| 9 | Soundcore headphones (Space One, One Pro, Q45; Space 2 with its init script) | Space One / One Pro / Q45 done; Space 2 read-only → 0.8 |
| 10 | Airoha RACE allow-list codec → JBL A-RACE | 0.8, after a decision |
| 11 | Sony v1 (WH-1000XM4); Sennheiser BLE (CX Plus, CX 500) | 0.8 |

Not worth building: the Skullcandy Dime 540 ANC (BES TOTA), the Hesh ANC (no control channel) and
Sennheiser's legacy GAIA v1 models. They stay generic.

## What we still need from you

- **Exact models:** which AirPods/Beats, which Soundcore, and the generic buds' name as Android
  shows it (Settings → Connected devices).
- **Owners' reports for the Experimental drivers:** Sennheiser, Sony, the other Soundcore models
  and the Skullcandy headsets. Tools → Lab → **Copy connection report** is what moves a driver from
  Experimental to Tested.
- **Captures for C2 and E2:** a Bluetooth HCI snoop log of the AirPods with an iPhone/Mac (or with
  LibrePods/CAPod), and of each Soundcore with the Soundcore app, changing each setting once. A
  how-to will go in `docs/research/` when D2/D3 start.
- **Decisions now:**
  - Airoha RACE: build an allow-listed codec for JBL's Airoha models and Skullcandy's Airoha line,
    or keep "never"? (Gates the rest of 0.8's driver work.)
  - Should Play get the Shizuku features at all? This one can wait until 0.8.
  - Answered in 0.75: Sony ships as Experimental without an owner.
