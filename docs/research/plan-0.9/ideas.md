# Earshot 0.9: candidate ideas (research report)

Written 2026-10-26 against the 0.8.0 tree. Nothing in the repo was edited; no Gradle was run.

## 0. How to read this

- **39 ideas**, grouped by area in section 4. Each has: pitch, why it would make Earshot special, how it works offline, what it builds on, effort, risks and sources.
- **Kind.** *polish* = ties together or finishes something Earshot already has. *standout* = a new capability people would mention when they recommend the app.
- **Effort.** S = a few days, M = one to two weeks, L = several weeks, for one developer including tests on the virtual devices and a pass on the Fold7. Where I was torn the range is in the heading line (for example 'S-M'); the JSON uses the upper end.
- **Hard rules checked for every idea:** no INTERNET permission and nothing downloaded at runtime; Kotlin and Compose; works from Android 9 (features that need more say so and hide below it); no root (Shizuku optional); no native code of our own; no GPL code (competitor facts only). New library dependencies and manifest changes are flagged because the integrator owns them: `androidx.appfunctions` (app-functions), `kotlinx-coroutines-jdk9` (device-controls), a material3 adaptive library (fold-adaptive), `FOREGROUND_SERVICE_MEDIA_PLAYBACK` (sleep-kit).

**Limits of this research.** The search tool could not reach Reddit (blocked for its crawler), and XDA pages returned 403 to the fetch tool, so XDA evidence comes from search-result summaries, not from reading the threads. Play and Exodus figures are reused from the 2026-10-20 notes in `docs/research/research_notes/` instead of being scraped again. The Android `Sensor` and `Spatializer` reference pages are too large for the fetch tool, so a few API details are marked *unverified*. Reviews and community posts are anecdotes, not statistics.

## 1. The short list

### Ten strongest

| # | Idea | Why |
|---|---|---|
| 1 | **Tuned on arrival** (`autoeq-match`, polish, M) | Closes the biggest known parity gap (Wavelet, 5M+ installs) and makes the Pro EQ a reason to install for any headset. Check the data licence first; the folder-import path is safe to ship now. |
| 2 | **Headset profiles** (`headset-profiles`, standout, M-L) | The 'profile that follows the device' idea: EQ plus volume plus mode plus codec plus rules per headset. XDA users ask for exactly this and say no app covers it. It is also the foundation for volume-guard, eq-follows and the garage. |
| 3 | **Sonar** (`sonar-finder`, standout, M) | Finds a lost earbud when it is NOT connected, with no account and no cloud. CAPod users asked for it and Find Hub needs a Google account. Builds straight on 0.8's Find and the Apple advert scanner. |
| 4 | **Match your ears** (`match-your-ears`, standout, L) | A cross-brand version of something only makers offer today, for their own models: a personal EQ from loudness matching, offline, honest about being a preference tool. Large but memorable. |
| 5 | **Test pilot** (`test-pilot`, standout, M) | Turns the project's real bottleneck (no hardware for experimental drivers) into a community pipeline and makes 'Experimental to Tested' visible and honest. |
| 6 | **Look, don't touch** (`look-only`, standout, S-M) | A read-only switch plus a ledger of what Earshot sent. Lets owners of expensive headsets try experimental drivers risk-free and proves the privacy promise in a way maker apps do not. |
| 7 | **Sleep kit** (`sleep-kit`, standout, M) | Timer, fade, generated noise, quiet mode and a final disconnect (Android 17). Fits the owner's own Sleep A30 and gives any headset a night mode. |
| 8 | **More triggers, and conditions** (`triggers-plus`, polish, M) | With recipes and a rule explainer, this is the automation upgrade: Sony's learned 'adaptive scenes' done deterministically, offline and editable, on every brand with modes. |
| 9 | **Battery health** (`battery-health`, standout, M) | Long-term battery health and per-bud drain that none of the maker apps I checked shows, cross-brand because the battery data already flows into Earshot; the basis for the garage and smart alerts. |
| 10 | **Connection doctor** (`connection-doctor`, polish, M) | 'Can't connect to my headset' is the top complaint class in maker apps. 0.8's Bluetooth history already holds the evidence; this turns it into a plain-language diagnosis and one-tap fixes. |

Runners-up: **Listening log** (`listening-log`), **Live listen** (`live-listen`), **Headset garage** (`headset-garage`), **Automation API** (`automation-api`), **EQ that follows what you do** (`eq-follows`), **Music dips when you listen out** (`aware-dim`), **Volume that behaves** (`volume-guard`).

### Quick wins (effort S, a day or three each)

**Sound check pack** (`sound-check`), **Where they last were** (`last-seen`), **Recipes** (`recipes`), **Why didn't it fire** (`rule-explainer`), **Long-press shortcuts** (`shortcuts`), **Live status chip** (`live-status`), **Confirmed by the headset** (`confirmed-badges`), **Privacy receipt** (`privacy-receipt`), **Earcons** (`earcons`), **Head tracker lab** (`head-tracker-lab`), **Auracast status** (`auracast-status`).

### A possible shape for 0.9

The roadmap already moved Play hygiene and the target-37 checklist into 0.9, so scope has to stay modest. A release with one clear story per theme:

- **Tune:** autoeq-match, headset-profiles, eq-follows. Stretch: volume-guard, match-your-ears.
- **Trust:** look-only, test-pilot, confirmed-badges, privacy-receipt (shares work with the Play licences screen), connection-doctor.
- **Automate:** triggers-plus, recipes, rule-explainer, shortcuts. Later: actions-plus, automation-api, device-controls, app-functions.
- **Live:** sonar-finder, sleep-kit, battery-health. Later: headset-garage, listening-log, live-listen.

**If only five:** autoeq-match, headset-profiles, look-only + test-pilot (one story: 'honest, safe, community-verified'), triggers-plus + recipes (one story: 'smart automations'), sonar-finder.

**Dependencies worth knowing.** headset-profiles first (volume-guard, eq-follows, garage and recipes plug into it). A shared action layer (the same commands the tile uses) feeds shortcuts, automation-api, device-controls and app-functions. look-only should land before automation-api and test-pilot. battery-health feeds smart-alerts and the garage. sleep-kit and live-status share one timer.

## 2. Competitor and community scan

### The field in one paragraph

Three camps, and nobody sits in the middle. **Makers' apps** (Bose, Sony Sound Connect, Sennheiser Smart Control, JBL Headphones, Soundcore, Samsung Galaxy Wearable, Nothing X) go deep on their own models and carry accounts, trackers and bloat: the Bose app shows 8 trackers and 29 permissions on Exodus ([Exodus](https://reports.exodus-privacy.eu.org/en/reports/com.bose.bosemusic/latest/)), and a reviewer counted eight headphone apps on one iPhone ([iMore, 2023](https://imore.com/music-movies-tv/please-stop-making-headphone-apps-my-phone-is-full)). **Audio-only apps** (Wavelet 5M+, Poweramp Equalizer 5M+, RootlessJamesDSP, Bluetooth Codec Changer 500K+) work on every headset but control none. **Single-brand open source** (LibrePods and CAPod for AirPods, OpenSCQ30 for Soundcore, BudsClient, OpenFreebuds) is deep and narrow. Gadgetbridge is the closest strategic rival: 85 headphone models across 20 brands, monthly releases, quick-settings tiles for several brands, and a generic-headphones mode, but no EQ, codec or call features and it is not on Play ([prior notes](docs/research/research_notes/Universal%20headset%20control%20accelerators/competitors_and_demand.md)). Earshot's moat is the combination of drivers and phone-side audio features; every idea below tries to widen that.

### What people love, pay for, or copy

- **Per-headphone EQ that loads by itself.** Wavelet advertises '5000+ AutoEq profiles', equal-loudness compensation (Pro), channel balance and a limiter ([Wavelet release notes](https://www.apkmirror.com/?p=13969488), [Play](https://play.google.com/store/apps/details?id=com.pittvandewitt.wavelet)). Poweramp and RootlessJamesDSP do the same. -> autoeq-match, headset-profiles, eq-follows.
- **Quick access.** Gadgetbridge added Quick Settings tiles for Sony, Shokz, Bose, Nothing, Anker and Oppo/Realme in 2026; the Bose widget needs the app running plus a companion permission ([Bose](https://www.bose.co.nz/en_nz/support/article/setting-up-your-system-bma.html)). Earshot has a tile and widget; Device controls and shortcuts extend them. -> device-controls, shortcuts.
- **Find my earbuds.** CAPod's third most-wanted item is finding the AirPods and the case; Google's Find Hub needs Fast Pair, a Google account and location history ([CAPod issues](https://github.com/d4rken-org/capod/issues), [Google](https://support.google.com/googlepixelbuds/answer/9642885)). -> sonar-finder, last-seen.
- **Head gestures and assistant tricks people pay for.** LibrePods charges for head gestures, and its README says it has no hearing test and takes a typed audiogram instead ([LibrePods](https://github.com/librepods-org/librepods)). -> head-tracker-lab, match-your-ears.
- **Personalisation and hearing features in the makers' own apps.** JBL Personi-Fi runs an 18-tone test ([JBL](https://support.jbl.com/howto/personi-fi-2-0-us/000040242.html)); Samsung's five-minute test and hearing-aid mode are FDA-cleared and slated for Galaxy Buds in late 2026 (reports differ on the exact models) ([Samsung](https://news.samsung.com/ca/samsungs-hearing-aid-feature-on-galaxy-buds-cleared-by-fda)); Nothing X has a fit test and a Mimi sound profile ([What Hi-Fi](https://www.whathifi.com/news/nothing-announces-updated-ear-2-wireless-earbuds-promising-hi-res-audio-better-battery-life-and-adaptive-anc)); Sennheiser has Sound Check and location-based Sound Zones ([Sennheiser](https://sennheiser-hearing.com/nl-BE/smartcontrol)). All are brand-locked. -> match-your-ears, sound-check.
- **Context scenes and logs.** Sony's Adaptive Sound Control learns places and activities from location, version 12 added a Scene tab, and the app keeps a listening log with a WHO comparison on compatible devices ([7.4.0 notes](https://www.apkmirror.com/?p=1859022), [12.0.0 notes](https://apkmirror.com/apk/sony-corporation/sony-headphones-connect)). -> triggers-plus, recipes, listening-log.
- **Automation hooks.** Gadgetbridge's opt-in Intent API is aimed at Tasker, Automate and MacroDroid ([docs](https://gadgetbridge.org/internals/automations/intents)). -> automation-api.
- **Generic-headphone extras.** Gadgetbridge 0.85 added read-aloud notifications, automatic call pickup and battery tracking for any paired headphones ([release](https://gadgetbridge.org/blog/release-0_85_0/)). -> read-aloud, battery-health.
- **Sleep earbuds.** Reviews of the Soundcore Sleep A30 (the owner's tested pair) praise local sounds, layered white noise and snore masking ([Notebookcheck](https://www.notebookcheck.net/Sleep-better-Soundcore-Sleep-A30-by-Anker-review.1174412.0.html), [TechRadar](https://www.techradar.com/health-fitness/sleep/ive-been-testing-the-new-soundcore-sleep-earbuds-heres-why-their-white-noise-feature-is-a-dream)). -> sleep-kit.

### What people complain about or ask for

- **App fatigue, accounts, ads, trackers.** [iMore](https://imore.com/music-movies-tv/please-stop-making-headphone-apps-my-phone-is-full) wants features in system settings; the prior notes list Bose's 8 trackers, Nothing X's 92 permissions and a Soundcore ad that delays ANC control. -> privacy-receipt, garage.
- **Per-device EQ, volume and profile switching are missing.** XDA threads ask Wavelet to key presets to the Bluetooth device, ask how to make Android remember volume per Bluetooth device, and ask for a profile that switches when a headset connects; Precise Volume is cited as the closest tool ([Wavelet thread](https://xdaforums.com/t/app-9-0-wavelet-headphone-specific-equalization.4097957/page-6), [per-device volume](https://xdaforums.com/t/bluetooth-audio-volume-per-device-setting.3867368/), [EQ by device](https://xdaforums.com/t/equalizer-change-based-on-bluetooth-device.3967653/), [Precise Volume](https://xdaforums.com/t/app-9-0-precise-volume-fine-tune-volume-steps-auto-eq-automation-replace-volume-interface-more-no-root.3573562/page-9)); Bluetooth Volume Manager still has 1M+ installs ([Play](https://play.google.com/store/apps/details?id=eu.darken.bluemusic)). -> headset-profiles, volume-guard.
- **Absolute-volume bugs.** An Android 15 report of every second volume press doing nothing, and guides on switching absolute volume off ([Fairphone forum](https://forum.fairphone.com/t/bluetooth-volume-is-louder-after-android-15/122201), [Engadget](https://engadget.com/2240704/android-disable-absolute-bluetooth-volume-how-to-guide/)). -> volume-guard.
- **Maker EQ breaks after firmware or app updates.** A Soundcore Life Q30 owner reports the EQ doing nothing after firmware 1.9 ([Soundcore community](https://community.soundcore.com/t/life-q30-equalizer-not-working/1824833)); phone-side EQ is immune. -> autoeq-match.
- **Small requests that makers ignore.** A Sonos Ace owner asks for a distinct sound when ANC toggles and for music to dim in ambient mode with a slider ([Sonos community](https://en.community.sonos.com/headphones-229146/ace-anc-fix-request-6897784)); Sennheiser reviewers want auto-off only when idle and a one-bud-not-charging alert (prior notes). -> earcons, aware-dim, smart-alerts.
- **Multipoint and connection failures.** 'Whack-a-mole' multipoint (Bose Connect), wrong-device priority (Sony), 'doesn't recognise them' (JBL), apps that cannot connect at all (prior notes). -> switch-headset, connection-doctor.
- **Hardware is the bottleneck for open-source drivers.** Gadgetbridge closed a Jabra PR because the contributor no longer owned the earbuds; OpenSCQ30 asks users to thumbs-up their model ([OpenSCQ30](https://github.com/Oppzippy/OpenSCQ30)). -> test-pilot.

### Where nobody seems to deliver (and Earshot can)

As far as this scan found:

1. A headset profile that follows the device across EQ, volume, mode, codec and rules (XDA gap).
2. Cross-brand personalisation that needs no calibrated hardware.
3. A find tool that works when the earbud is not connected and needs no account.
4. Honest verification: showing what was sent, what the headset confirmed, and which drivers are really tested.
5. Local, honest hearing-health data (Android has no sound-dose API for apps and Sony covers only its own devices).
6. Sleep and night routines that work on any headset and end by disconnecting it.

## 3. Platform scan: Android 13 to 17

'Unprivileged' means a normal Play app can use it with at most a runtime permission or a user-granted special access. Rows without a source link are platform knowledge that was not re-verified in this pass. Background-audio rules for Android 17 are the main constraint on anything that plays sound or sets volume ([source](https://developer.android.com/about/versions/17/changes/bg-audio)).

| Capability | API | Min Android | Unprivileged? | Used by | Source |
|---|---|---|---|---|---|
| Per-session EQ, limiter, input gain, per-channel config | DynamicsProcessing | 9 (API 28) | Yes, per audio session that Earshot can reach | autoeq-match, match-your-ears, aware-dim, volume-guard, eq-follows | [Android: DynamicsProcessing reference (API 28)](https://developer.android.com/reference/android/media/audiofx/DynamicsProcessing) |
| Session announcement with content type | ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION, EXTRA_CONTENT_TYPE (music, movie, game, voice) | API 9 | Yes, but only players that broadcast it | eq-follows | [Android: AudioEffect reference (control-session intent, EXTRA_CONTENT_TYPE)](https://developer.android.com/reference/android/media/audiofx/AudioEffect) |
| Playback activity and routed device | AudioManager.registerAudioPlaybackCallback, getActivePlaybackConfigurations | 8 (API 26) | Yes; how much is visible about other apps' players is unverified | listening-log | [Android: AudioManager.AudioPlaybackCallback (API 26)](https://developer.android.com/reference/android/media/AudioManager.AudioPlaybackCallback) |
| Sound dose (safe listening) | none for apps | 14 (system feature) | No: AOSP says 'no external-facing APIs' | listening-log must estimate | [AOSP: Sound dose (CSD, 'no external-facing APIs')](https://source.android.com/docs/core/audio/sound-dose) |
| Pixel sound-exposure warnings | system feature; Google says they are automatic on Pixel (reports of a hidden toggle conflict) | 14+ | n/a | context for listening-log | [Google: Pixel sound exposure notifications](https://support.google.com/pixelphone/answer/16785703) |
| Spatializer state and head tracker | Spatializer (read-only), Sensor.TYPE_HEAD_TRACKER dynamic sensor, HID head-tracker protocol v1.0 / v2.0 (LE Audio) | 13 / 15 | Reading yes (sensor permission unverified); enabling no; the headset and phone maker must support it | head-tracker-lab | [AOSP: Head tracker HID protocol (v1.0 Android 13, v2.0 LE Audio Android 15)](https://source.android.com/docs/core/sensors/head-tracker-hid-protocol) |
| Companion Device Manager association | setAlias 31, removeBond 36, connect/disconnect 37, presence 36, codec calls proven on the Fold7 | per API | Yes after one system dialog per headset | switch-headset, sleep-kit, connection-doctor | [Android: BluetoothDevice reference (connect/disconnect 37, setAlias 31, removeBond via CDM 36)](https://developer.android.com/reference/android/bluetooth/BluetoothDevice) |
| Ranging (BLE Channel Sounding, BLE RSSI, UWB, Wi-Fi RTT) | RangingManager | 16 (API 36) | Permissions not verified here; needs hardware on both ends | sonar-finder (later) | [Android: RangingManager reference (BLE channel sounding, BLE RSSI, UWB, Wi-Fi RTT)](https://developer.android.com/reference/android/ranging/RangingManager) |
| Live Update notifications | Notification.ProgressStyle, POST_PROMOTED_NOTIFICATIONS, setRequestPromotedOngoing | 16 (QPR1 rules); page gives no min API | Yes (normal permission); policy-limited use | live-status | [Android: Create Live Update notifications](https://developer.android.com/develop/ui/views/notifications/live-update) |
| Device Controls | ControlsProviderService, BIND_CONTROLS | 11 (API 30) | Yes | device-controls | [Android: Control external devices (Device Controls)](https://developer.android.com/develop/ui/views/device-control) |
| AppFunctions | androidx.appfunctions, EXECUTE_APP_FUNCTIONS for callers | 16 (API 36) | Yes to expose; Gemini access is a private preview (May 2026) | app-functions | [Android: Overview of AppFunctions](https://developer.android.com/ai/appfunctions) |
| On-device speech recognition | SpeechRecognizer.createOnDeviceSpeechRecognizer, checkRecognitionSupport, triggerModelDownload; RecognizerIntent.EXTRA_AUDIO_SOURCE | 12 (API 31) / 13 (API 33) | Yes with RECORD_AUDIO; language packs are managed by the system | headset-dictation | [Android: SpeechRecognizer reference (on-device recognizer, model download)](https://developer.android.com/reference/android/speech/SpeechRecognizer) |
| App-owned Do Not Disturb rules | NotificationManager.addAutomaticZenRule | 9 (API 28) | After the user grants notification-policy access | actions-plus | [Android: NotificationManager reference (automatic zen rules, policy access)](https://developer.android.com/reference/android/app/NotificationManager) |
| LE Audio and Auracast | BluetoothAdapter feature checks (API 33); broadcast classes are SystemApi; TYPE_BLE_HEARING_AID (Android 17) | 13 / 16 / 17 | Checks yes; control no | auracast-status | [TechCrunch: Android 16 adds Auracast support](https://techcrunch.com/2025/03/13/android-is-adding-auracast-support-which-allows-hearing-aids-to-connect-to-public-audio-broadcasts/) |
| Voice Broadcast (phone mic to LE Audio) | Pixel system feature, Android 17 QPR2 beta | 17 QPR2 | Not an app API | context for live-listen | [SoundGuys: Android 17 QPR2 beta, Pixel 'Voice Broadcast'](https://www.soundguys.com/android-17-qpr2-beta-4-pixel-voice-broadcast-162201/) |
| Background audio hardening | Android 17 behaviour change | 17 | Applies to every app that plays audio or sets volume | constraint for sleep-kit, volume-guard, earcons, live-listen | [Android 17: background audio hardening](https://developer.android.com/about/versions/17/changes/bg-audio) |
| Large-screen rules | Android 16: orientation and resizability opt-outs ignored at 600 dp for targetSdk 36 | 16 | Applies to Earshot already | fold-adaptive | [Android Developers Blog: orientation and resizability changes in Android 16](https://android-developers.googleblog.com/2025/01/orientation-and-resizability-changes-in-android-16.html) |
| Bluetooth Quality Report, ACL RSSI/link quality | SystemApi | n/a | No | rejected | - |

## 4. The 39 ideas

| # | Id | Name | Kind | Effort | Area |
|---|---|---|---|---|---|
| 1 | `autoeq-match` | Tuned on arrival | polish | M | Tune |
| 2 | `match-your-ears` | Match your ears | standout | L | Tune |
| 3 | `blind-ab` | Blind A/B for your ears | standout | M | Tune |
| 4 | `eq-follows` | EQ that follows what you do | polish | S-M | Tune |
| 5 | `aware-dim` | Music dips when you listen out | polish | S-M | Tune |
| 6 | `volume-guard` | Volume that behaves | standout | M | Tune |
| 7 | `sound-check` | Sound check pack | polish | S | Tune |
| 8 | `latency-lab` | Latency lab | polish | M | Tune |
| 9 | `listening-log` | Listening log | standout | M | Ears |
| 10 | `live-listen` | Live listen | standout | M | Ears |
| 11 | `sleep-kit` | Sleep kit | standout | M | Ears |
| 12 | `sonar-finder` | Sonar | standout | M | Find |
| 13 | `last-seen` | Where they last were | polish | S | Find |
| 14 | `battery-health` | Battery health | standout | M | Find |
| 15 | `smart-alerts` | Smart alerts | polish | S-M | Find |
| 16 | `triggers-plus` | More triggers, and conditions | polish | M | Automate |
| 17 | `actions-plus` | More actions | polish | M | Automate |
| 18 | `recipes` | Recipes | polish | S | Automate |
| 19 | `rule-explainer` | Why didn't it fire | polish | S | Automate |
| 20 | `automation-api` | Automation API | standout | M | Automate |
| 21 | `device-controls` | Device controls | polish | S-M | Automate |
| 22 | `shortcuts` | Long-press shortcuts | polish | S | Automate |
| 23 | `app-functions` | Ask your assistant | standout | S-M | Automate |
| 24 | `live-status` | Live status chip | polish | S | Automate |
| 25 | `headset-profiles` | Headset profiles | standout | M-L | Profile |
| 26 | `headset-garage` | Headset garage | standout | M | Profile |
| 27 | `switch-headset` | Switch headset | polish | S-M | Profile |
| 28 | `share-backup` | Share and back up | polish | S-M | Profile |
| 29 | `look-only` | Look, don't touch | standout | S-M | Trust |
| 30 | `test-pilot` | Test pilot | standout | M | Trust |
| 31 | `connection-doctor` | Connection doctor | polish | M | Trust |
| 32 | `confirmed-badges` | Confirmed by the headset | polish | S | Trust |
| 33 | `privacy-receipt` | Privacy receipt | polish | S | Trust |
| 34 | `headset-dictation` | Say it to your headset | standout | M | Delight |
| 35 | `earcons` | Earcons | polish | S | Delight |
| 36 | `read-aloud` | Read it to me | polish | M | Delight |
| 37 | `fold-adaptive` | Fold-ready layouts | polish | M | Delight |
| 38 | `head-tracker-lab` | Head tracker lab | standout | S (readout), M (gestures) | Delight |
| 39 | `auracast-status` | Auracast status | polish | S | Delight |

### 4.1 Sound and tuning

#### 1. Tuned on arrival (`autoeq-match`)

*polish* | effort **M** | area Tune

- **Pitch.** Earshot recognises your headphones by their Bluetooth name, offers the matching AutoEq correction curve in one tap, and applies it every time they connect.
- **Why it is special.** This is Wavelet's headline (5M+ installs, '5000+ AutoEq profiles', the profile loads when you switch headphones), and it is closed source, partly paid and breaks on some OEMs. XDA users keep asking for per-device profiles. Earshot would be the open, account-free, no-INTERNET version, tied to a driver (ANC mode) and to a headset profile. Phone-side EQ also keeps working when a firmware update breaks a maker's own EQ (Soundcore Life Q30 thread).
- **How it works offline.** Build-time script turns AutoEq ParametricEQ results into one compact asset (name aliases, up to 10 filters, source tag, target curve; about 1,000-1,500 popular models, a few hundred KB compressed). At runtime the Bluetooth name is normalised (strip LE_ / -LE / suffixes, case) and fuzzy-matched (token set plus model number); Earshot asks once ('Looks like Sony WH-1000XM5. Apply?') and stores the choice with the existing per-headset memory. Licence-safe fallback that ships first: 'Import an AutoEq folder or zip' through the Storage Access Framework (ACTION_OPEN_DOCUMENT_TREE), indexed on the phone and matched the same way. APIs: DynamicsProcessing (API 28), SAF. No permission.
- **Builds on.** dsp/AutoEqParser, audio/HeadsetEqMemory, DynamicsProcessingEngine, Headroom, the Sound screen import dialog; roadmap item B3 (the 'AutoEq suggestion by name' that was never built).
- **Risks and unknowns.** Data licence is NOT settled: AutoEq's code is MIT, but the repo says the measurements come from oratory1990, crinacle, Rtings, InnerFidelity and others without stating their terms, and a forum post claims oratory1990 objected to a commercial app bundling presets (anecdotal, unverified). Check each source (or ask) before bundling; ship the folder import first. Bluetooth names are ambiguous and a measured unit is not your unit. Repo discrepancy: README says AutoEq curves are 'bundled offline' but the repo has only a paste-in importer and one built-in curve (Tuned for QC Ultra); decide what 0.9 claims.
- **Sources.** [AutoEq on GitHub (MIT code; measurement sources oratory1990, crinacle, Rtings, InnerFidelity)](https://github.com/jaakkopasanen/AutoEq); [headphones.com forum: AutoEq presets and oratory1990 terms (anecdotal)](https://forum.headphones.com/t/eq-for-both-ios-mac/4874?page=4); [Wavelet on Google Play (5M+)](https://play.google.com/store/apps/details?id=com.pittvandewitt.wavelet); [APKMirror: Wavelet 26.05 notes (AutoEq 5000+ profiles, channel balance, BLE hearing aid detection)](https://www.apkmirror.com/?p=13969488); [XDA: Wavelet thread (per-device EQ requests, Tasker request)](https://xdaforums.com/t/app-9-0-wavelet-headphone-specific-equalization.4097957/page-6); [Soundcore community: Life Q30 equalizer stopped working after firmware 1.9](https://community.soundcore.com/t/life-q30-equalizer-not-working/1824833); Earshot research: competitors_and_demand.md (2026-10-20) (`docs/research/research_notes/Universal headset control accelerators/competitors_and_demand.md`)

#### 2. Match your ears (`match-your-ears`)

*standout* | effort **L** | area Tune

- **Pitch.** A ten-minute loudness-matching test builds a personal EQ for whatever headset is on your ears, per ear, entirely offline.
- **Why it is special.** In what I found, hearing-based personalisation lives inside makers' apps and only for their own models: JBL Personi-Fi (18 tones), Samsung (a five-minute test, FDA-cleared as a hearing aid feature for Galaxy Buds), Nothing X with Mimi, Sennheiser Sound Check. Earshot would do it for any headset. Because the user matches loudness against a 1 kHz reference instead of finding absolute thresholds, no calibrated SPL is needed and the result compensates headphone plus ear together. LibrePods says a hearing test is too imprecise to include and lets users enter an audiogram instead; Earshot positions this as a sound-preference tool and can also accept a typed audiogram.
- **How it works offline.** Java AudioTrack plays narrow-band warble tones at about 10 bands, alternating a 1 kHz reference and the test band; the user moves a slider until both sound equally loud (a few repeats, median, each ear). Fit a smooth correction (cap about +/-8 dB), optionally flatten with the AutoEq curve first, and save it as an ordinary preset ('My ears') with per-channel gains. DynamicsProcessing supports per-channel configuration; the current engine builds one plan for all channels, so a per-channel plan is needed. Typed-audiogram entry is the cheap second path. No permission. Min Android 9.
- **Builds on.** FindTone (tone generator), DynamicsProcessingPlan and Headroom, Sound-screen presets, HeadsetEqMemory.
- **Risks and unknowns.** Equal loudness changes with level, ANC mode and ear-tip fit: test at the normal volume in the mode you use. Not a medical test (Samsung registers its test as Software as a Medical Device; reviews agree app audiometry is screening at best and needs calibration), so never say 'hearing loss' in the UI. Tones start very quiet behind a hard output cap. Test fatigue and room noise add error; needs a handful of human testers to validate the fit.
- **Sources.** [JBL: Personi-Fi 2.0 (18-tone hearing test)](https://support.jbl.com/howto/personi-fi-2-0-us/000040242.html); [Samsung: hearing aid feature cleared by FDA (5-minute self test, Software as a Medical Device)](https://news.samsung.com/ca/samsungs-hearing-aid-feature-on-galaxy-buds-cleared-by-fda); [What Hi-Fi: Nothing Ear (2) (fit test, Mimi personal sound)](https://www.whathifi.com/news/nothing-announces-updated-ear-2-wireless-earbuds-promising-hi-res-audio-better-battery-life-and-adaptive-anc); [Sennheiser Smart Control (Sound Check, Sound Zones, accounts)](https://sennheiser-hearing.com/nl-BE/smartcontrol); [LibrePods on GitHub (head gestures, audiogram entry, no hearing test)](https://github.com/librepods-org/librepods); [Univ. of Michigan: phone audiometry review (screening only, room matters)](https://open.umich.edu/sites/default/files/downloads/mobilephoneandcellphoneaudiometry.pdf); [Frontiers in Psychology 2020: calibrated tablet audiometry](https://www.frontiersin.org/journals/psychology/articles/10.3389/fpsyg.2020.00744/pdf); [headphones.com forum: audiograms and EQ](https://forum.headphones.com/t/audiograms-and-eqing-for-both-the-headphone-and-individual-thoughts/16571); [Android: DynamicsProcessing reference (API 28)](https://developer.android.com/reference/android/media/audiofx/DynamicsProcessing)

#### 3. Blind A/B for your ears (`blind-ab`)

*standout* | effort **M** | area Tune

- **Pitch.** Find out whether your EQ really sounds better with a level-matched, randomised blind test on any local audio file.
- **Why it is special.** EQ apps (Wavelet, Poweramp) sell curves but give no way to know whether you are fooled by loudness; I found no headset app that offers ABX. It suits Earshot's honest identity: it scores your answers and tells you the probability you were guessing.
- **How it works offline.** Pick a local file with the Storage Access Framework; play it with MediaPlayer or AudioTrack inside Earshot's own audio session so DynamicsProcessing attaches reliably (this also proves the EQ attaches on that phone). A/B/X toggling with level matching from Headroom, 10-16 trials, binomial p-value. Preset vs flat, preset vs preset, limiter on vs off. Codec ABX is not possible (switching restarts the stream) and the screen says so. No permission.
- **Builds on.** dsp Headroom, DynamicsProcessingEngine, preset list, Sound screen.
- **Risks and unknowns.** Click or zipper noise when bands toggle; the Bluetooth codec colours the result and must be disclosed; small audience; MediaPlayer seeking and gapless quirks.
- **Sources.** [Android: DynamicsProcessing reference (API 28)](https://developer.android.com/reference/android/media/audiofx/DynamicsProcessing); [Android: AudioEffect reference (control-session intent, EXTRA_CONTENT_TYPE)](https://developer.android.com/reference/android/media/audiofx/AudioEffect)

#### 4. EQ that follows what you do (`eq-follows`)

*polish* | effort **S-M** | area Tune

- **Pitch.** Pick EQ layers by content type (music, movie, game, voice), app and listening mode, so podcasts, videos and ANC-off each get their own curve without touching anything.
- **Why it is special.** Wavelet and Poweramp load one curve per headphone. Earshot also knows the headset's mode (ANC changes the bass the ear receives) and what is playing, which keeps the switching honest and explainable.
- **How it works offline.** ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION carries EXTRA_CONTENT_TYPE (music, movie, game, voice) and EXTRA_PACKAGE_NAME, and AudioSessionReceiver already receives it. Add a small layer table: base preset per headset + delta per content type + delta per mode meaning (ModeResolver). Crossfade between layers. Min API 28, no permission.
- **Builds on.** audio/AudioSessionReceiver, per-app EQ, HeadsetEqMemory, core/ModeMeaning.
- **Risks and unknowns.** Many players do not broadcast their session, or omit the content type (it then defaults to MUSIC). Stacked deltas eat headroom (reuse Headroom). Flipping presets mid-song needs a ramp.
- **Sources.** [Android: AudioEffect reference (control-session intent, EXTRA_CONTENT_TYPE)](https://developer.android.com/reference/android/media/audiofx/AudioEffect); [Android: DynamicsProcessing reference (API 28)](https://developer.android.com/reference/android/media/audiofx/DynamicsProcessing); Earshot research: android_platform.md (2026-10-20) (`docs/research/research_notes/Universal headset control accelerators/android_platform.md`)

#### 5. Music dips when you listen out (`aware-dim`)

*polish* | effort **S-M** | area Tune

- **Pitch.** In Aware, Transparency or Ambient mode the music ducks by an amount you set, and returns when you go back to Quiet, on any headset whose driver reports the mode.
- **Why it is special.** A direct community request (a Sonos Ace owner asks for music to dim in ambient mode, with a slider). Makers hard-code it or leave it out. Earshot keys on mode meaning, so one setting works across brands.
- **How it works offline.** When the mode meaning flips, ramp the DynamicsProcessing input gain (or a negative LoudnessEnhancer gain) on attached sessions over about 300 ms. Does not touch the stream volume, so Android 17's volume hardening does not apply. Min API 28, no permission.
- **Builds on.** DynamicsProcessingEngine input-gain stage, core/ModeMeaning (ModeResolver), HeadphoneState modes.
- **Risks and unknowns.** Only sessions Earshot holds are dimmed (same limit as the EQ). Some headsets already duck by themselves. Ramps can zipper-click if the steps are coarse.
- **Sources.** [Sonos community: Ace ANC toggle sound and music dimming request](https://en.community.sonos.com/headphones-229146/ace-anc-fix-request-6897784); [Android: DynamicsProcessing reference (API 28)](https://developer.android.com/reference/android/media/audiofx/DynamicsProcessing)

#### 6. Volume that behaves (`volume-guard`)

*standout* | effort **M** | area Tune

- **Pitch.** A start volume per headset, a hard ceiling that holds even if you keep tapping volume up, and a quiet-floor fix for headsets whose lowest step is still too loud.
- **Why it is special.** XDA threads ask for per-Bluetooth-device volume memory and profile switching; Bluetooth Volume Manager has 1M+ installs; Android 15 absolute-volume regressions hurt (Fairphone thread). Earshot's ceiling is a limiter, not a polite setting, and the floor is an input-gain cut, so both survive however the volume is changed.
- **How it works offline.** On audio-link up (the same wait as the call boost), set the music stream volume once with AudioManager.setStreamVolume (MODIFY_AUDIO_SETTINGS is already declared). Ceiling: DynamicsProcessing limiter threshold and input-gain cut on attached sessions. Floor: an input-gain offset below a chosen volume index. Min API 28. Android 17 silently ignores volume calls from the background unless a visible activity or a non-SHORT_SERVICE foreground service exists (WIU at target 37), so test the connect path from the companion-presence service.
- **Builds on.** call/HeadsetVolumeControl, CallStartBoostScheduler (waits for the link), AbsoluteVolumeSetting, the DP limiter.
- **Risks and unknowns.** Android 17 hardening (above) may stop the start-volume part when Earshot is not in the foreground; the limiter only covers sessions Earshot holds; absolute volume couples phone and headset steps on some phones.
- **Sources.** [XDA: Bluetooth audio volume (per device setting)](https://xdaforums.com/t/bluetooth-audio-volume-per-device-setting.3867368/); [XDA: Precise Volume (Bluetooth automation, per-device presets)](https://xdaforums.com/t/app-9-0-precise-volume-fine-tune-volume-steps-auto-eq-automation-replace-volume-interface-more-no-root.3573562/page-9); [Bluetooth Volume Manager on Google Play (1M+)](https://play.google.com/store/apps/details?id=eu.darken.bluemusic); [Fairphone forum: Bluetooth volume steps broken after Android 15](https://forum.fairphone.com/t/bluetooth-volume-is-louder-after-android-15/122201); [Engadget: disabling absolute Bluetooth volume](https://engadget.com/2240704/android-disable-absolute-bluetooth-volume-how-to-guide/); [Android 17: background audio hardening](https://developer.android.com/about/versions/17/changes/bg-audio); [Android: DynamicsProcessing reference (API 28)](https://developer.android.com/reference/android/media/audiofx/DynamicsProcessing)

#### 7. Sound check pack (`sound-check`)

*polish* | effort **S** | area Tune

- **Pitch.** One screen of honest tests: left/right identification, balance, polarity, a bass sweep for seal and rattle, a hiss floor, a mono check, and a warning when Android's Mono audio or Balance setting would defeat them.
- **Why it is special.** Makers ship fit tests for their own buds (Nothing X); nothing is cross-brand, and the tests double as a proof that the EQ and routing work on this phone.
- **How it works offline.** Extend FindTone with sweeps and noise; route to the shown headset the way FindOutput does (setPreferredDevice). Read the accessibility audio values from Settings.System if they are readable by apps (unverified) to show the warning. Start only from a visible screen (Android 17). Min API 28, no permission.
- **Builds on.** find/FindTone, FindOutput, FindPlayer.
- **Risks and unknowns.** Results are subjective; a 'seal test' is a guide, not a measurement; a bud in a case will not play.
- **Sources.** [What Hi-Fi: Nothing Ear (2) (fit test, Mimi personal sound)](https://www.whathifi.com/news/nothing-announces-updated-ear-2-wireless-earbuds-promising-hi-res-audio-better-battery-life-and-adaptive-anc); [HeadphoneCheck: Nothing Ear (a) (low lag, in-ear detection, fit test)](https://www.headphonecheck.com/test/nothing-ear-a/); [Android 17: background audio hardening](https://developer.android.com/about/versions/17/changes/bg-audio)

#### 8. Latency lab (`latency-lab`)

*polish* | effort **M** | area Tune

- **Pitch.** Measure how late your headset is per codec and say what to change for video and games.
- **Why it is special.** Bluetooth Codec Changer (500K+) has a latency test but is closed, has ads and two trackers. Earshot's is open, sits beside the codec switch, so you can compare AAC, SBC and LC3 and save a 'game' preference, and it translates the number into a video-player audio-delay value.
- **How it works offline.** Play a click through AudioTrack to the headset while the phone's built-in mic records it (hold an earcup to the mic); cross-correlate; subtract the phone's own output latency (AudioTrack.getTimestamp, AudioManager output frames-per-buffer property). Store per codec and show the delay to enter in a player. RECORD_AUDIO is already requested. Min API 28.
- **Builds on.** Codec screen, codec/AssociationCodecPath, mic/HeadsetMicRouter and MicCheck.
- **Risks and unknowns.** Acoustic measurement needs a quiet room; uncertainty of 20-30 ms; HFP and LE Audio behave differently; players have their own sync offsets.
- **Sources.** [Bluetooth Codec Changer on Google Play (latency test, per-app codec profiles)](https://play.google.com/store/apps/details?id=com.amrg.bluetooth_codec_converter); Earshot research: competitors_and_demand.md (2026-10-20) (`docs/research/research_notes/Universal headset control accelerators/competitors_and_demand.md`)

### 4.2 Ears, hearing and sleep

#### 9. Listening log (`listening-log`)

*standout* | effort **M** | area Ears

- **Pitch.** Private weekly stats per headset (hours, time at high volume, codec, mode) with safe-listening nudges that you control.
- **Why it is special.** Sony's app shows a WHO comparison but only on compatible Sony devices; Android's own sound-dose system has no app-facing API (AOSP: 'no external-facing APIs') and on Pixel its warnings are on by default. I found no cross-brand, local, honest log. Honest means it says 'volume-index estimate', not dB, and lets the curious calibrate it ('my headphones reach 85 dB at step 9').
- **How it works offline.** AudioManager.registerAudioPlaybackCallback (API 26) tells when playback is active on the headset; sample the music stream volume index while playing (getStreamVolume plus the volume-changed broadcast); fold in Earshot's own gain from EQ and boost; take codec and mode from state. Daily buckets in the same TSV style as the Bluetooth history. Opt-in notification such as '2 h at high volume'. No new permission. Min API 28.
- **Builds on.** shortcut/AndroidControlFeeds (already listens to playback start/stop and music-volume changes for Detect a button), bt/BtTimeline and TimelineStore, ConnectionState.audioLinked, LowBatteryTracker (alert plumbing), the call-volume code.
- **Risks and unknowns.** Not real SPL (sensitivity unknown), so no health claims; whether getActivePlaybackConfigurations shows other apps' players routed to the headset on One UI 9 is unverified; Pixel already warns at system level.
- **Sources.** [AOSP: Sound dose (CSD, 'no external-facing APIs')](https://source.android.com/docs/core/audio/sound-dose); [Google: Pixel sound exposure notifications](https://support.google.com/pixelphone/answer/16785703); [APKMirror: Sony Sound Connect 12.0.0 notes (Scene tab, listening log, WHO comparison)](https://apkmirror.com/apk/sony-corporation/sony-headphones-connect); [Android: AudioManager.AudioPlaybackCallback (API 26)](https://developer.android.com/reference/android/media/AudioManager.AudioPlaybackCallback)

#### 10. Live listen (`live-listen`)

*standout* | effort **M** | area Ears

- **Pitch.** Turn the phone into a remote microphone for any headset: put it near the speaker and hear them in your ears with a speech boost and a safety limiter.
- **Why it is special.** iPhone has Live Listen for AirPods. On Android the closest is Google's Sound Amplifier (closed; reported not to work with some Bluetooth hearing aids) and Pixel's new Voice Broadcast (LE Audio / Auracast, a Pixel Quick Settings tile in the Android 17 QPR2 beta). Earshot's would work on any A2DP or LE headset, be open, and share the headset's EQ and limiter.
- **How it works offline.** Foreground service of type microphone (permission and type already declared), AudioRecord from the built-in mic (preferred device = built-in), light compressor and high-shelf through DynamicsProcessing on the output track, AudioTrack routed to the headset with setPreferredDevice, measured latency shown on screen, hard gain cap. Start only from a visible screen (mic foreground-service rule). Min API 28.
- **Builds on.** mic/MicCheckRepositoryImpl and HeadsetMicRouter, find/FindOutput routing, DynamicsProcessingEngine, the VoiceMemoService foreground-service pattern.
- **Risks and unknowns.** A2DP adds roughly 150-250 ms, which makes lip-sync odd and echoes if the phone is within earshot; some OEMs block mic capture during SCO; hearing safety needs a hard cap; Sound Amplifier already exists, so the pitch is openness plus integration, not novelty.
- **Sources.** [HearingTracker: iOS Live Listen vs Android Sound Amplifier](https://forum.hearingtracker.com/t/ios-live-listen-vs-android-sound-amplifier/94866); [SoundGuys: Android 17 QPR2 beta, Pixel 'Voice Broadcast'](https://www.soundguys.com/android-17-qpr2-beta-4-pixel-voice-broadcast-162201/); [Android: LE Audio overview](https://developer.android.com/develop/connectivity/bluetooth/ble-audio/overview); [Android 17: background audio hardening](https://developer.android.com/about/versions/17/changes/bg-audio)

#### 11. Sleep kit (`sleep-kit`)

*standout* | effort **M** | area Ears

- **Pitch.** A timer that fades your audio out, optionally plays generated noise, switches the headset to its quiet mode and finally disconnects it so the earbuds stop draining.
- **Why it is special.** The owner's tested hardware includes a sleep earbud (Soundcore Sleep A30 with local sounds and snore masking), and sleep features in maker apps work only for their own sleep buds. Earshot's works with any headset and finishes by saving the headset's battery (disconnect on Android 17).
- **How it works offline.** Timer inside the existing foreground service. Fade by ramping DynamicsProcessing input gain or stream volume. End: pause the player (dispatch KEYCODE_MEDIA_PAUSE or a transient audio-focus request), set the mode through the driver (Quiet / Off), then BluetoothDevice.disconnect() on Android 17 when the headset is linked. Generated white/pink/brown noise and a rain-like filtered noise synthesised in code (no assets) in an AudioTrack. Optional gentle wake chime via AlarmManager.setAlarmClock, always with a phone-speaker fallback. Needs the FOREGROUND_SERVICE_MEDIA_PLAYBACK permission for the noise (manifest change for the integrator). Min API 28 (disconnect step 37).
- **Builds on.** service/HeadphoneService, bt/CompanionRemote, SoundcoreHeadphoneRepository (A30 modes), schedule automations, FindTone synthesis.
- **Risks and unknowns.** Android 17 background-audio hardening (needs the foreground service, and WIU at target 37); pausing other apps varies by player; no medical claims; never rely on the headset alone to wake the user.
- **Sources.** [Notebookcheck: Soundcore Sleep A30 review](https://www.notebookcheck.net/Sleep-better-Soundcore-Sleep-A30-by-Anker-review.1174412.0.html); [TechRadar: Soundcore sleep earbuds, white noise](https://www.techradar.com/health-fitness/sleep/ive-been-testing-the-new-soundcore-sleep-earbuds-heres-why-their-white-noise-feature-is-a-dream); [Android 17: background audio hardening](https://developer.android.com/about/versions/17/changes/bg-audio); [Android: BluetoothDevice reference (connect/disconnect 37, setAlias 31, removeBond via CDM 36)](https://developer.android.com/reference/android/bluetooth/BluetoothDevice)

### 4.3 Find, battery and presence

#### 12. Sonar (`sonar-finder`)

*standout* | effort **M** | area Find

- **Pitch.** Find a lost earbud by walking toward the strongest Bluetooth signal, with a Geiger-style tick and a hot/cold gauge, even when it is not connected.
- **Why it is special.** Find my earbuds (0.8) rings only a connected headset. Google's Find Hub needs Fast Pair, a Google account and location history. CAPod users asked for a finder (5 thumbs-up). Sonar works offline for any earbud that advertises (out of its case) and, via the Apple adverts Earshot already parses, for AirPods.
- **How it works offline.** BluetoothLeScanner (SCAN_MODE_LOW_LATENCY) filtered by the bonded LE address or the '<name>-LE' twin (GattTargets / LeAdvertMatch), or by Apple continuity adverts (AppleAdvertScanner) matched against the last-known left / right / case battery to tell your pair from others. Exponentially smoothed RSSI drives tick rate and pitch. BLUETOOTH_SCAN with neverForLocation is already declared (Android 9-11 need location). Min API 28. Later: Android 16's RangingManager exposes BLE Channel Sounding and BLE RSSI ranging (API 36) when both ends support it.
- **Builds on.** ui/tools/FindScreen, bt/AppleAdvertScanner, GattTargets and LeAdvertMatch, the battery history.
- **Risks and unknowns.** Buds in the case or switched off do not advertise; Apple addresses rotate; RSSI is noisy through bodies and walls; some stacks hide adverts for bonded devices (unverified); keep sessions short for battery.
- **Sources.** [CAPod issues (find the AirPods and case, decibel level)](https://github.com/d4rken-org/capod/issues); [Google: find Pixel Buds / Find Hub (needs Google account and location history)](https://support.google.com/googlepixelbuds/answer/9642885); [Android: RangingManager reference (BLE channel sounding, BLE RSSI, UWB, Wi-Fi RTT)](https://developer.android.com/reference/android/ranging/RangingManager); Earshot research: competitors_and_demand.md (2026-10-20) (`docs/research/research_notes/Universal headset control accelerators/competitors_and_demand.md`)

#### 13. Where they last were (`last-seen`)

*polish* | effort **S** | area Find

- **Pitch.** Find shows when, and on which Wi-Fi or labelled place, each headset last disconnected, with its last battery level.
- **Why it is special.** Find Hub needs location history in a Google account. Earshot keeps it on the phone and uses Wi-Fi names, not GPS, which is also the privacy-first story. It complements Sonar.
- **How it works offline.** On disconnect (Bluetooth timeline), store timestamp, last battery, the connected SSID (ACCESS_FINE_LOCATION and location services, already declared for Wi-Fi automations) and an optional user label per SSID. Show it on the Find screen. No background location. Min API 28.
- **Builds on.** extras/automation/WifiSsidWatcher, bt/TimelineRecorder, FindScreen.
- **Risks and unknowns.** The SSID can be blank without the permission or with location services off; the phone may already have left Wi-Fi at disconnect; needs clear privacy copy.
- **Sources.** [Google: find Pixel Buds / Find Hub (needs Google account and location history)](https://support.google.com/googlepixelbuds/answer/9642885)

#### 14. Battery health (`battery-health`)

*standout* | effort **M** | area Find

- **Pitch.** Track how long each headset really lasts per charge, warn when it is getting worse or when one bud drains faster.
- **Why it is special.** None of the maker apps I checked shows long-term battery health; they show a percentage. It is cross-brand because Android's battery broadcast, the GATT Battery Service and driver values already feed Earshot, and Gadgetbridge only offers plain battery tracking for generic headphones.
- **How it works offline.** Append (time, percent per part, charging, playing, codec, mode) on every change to a TSV store; compute drain per hour while playing, runtime per 100% trend (median of discharge segments), per-bud skew and charge time; a Battery screen with a chart, and a tile or widget subtitle such as '~4 h left'. Min API 28, no permission.
- **Builds on.** call/HeadsetBatteryReader, GattBatteryReader, the TimelineStore pattern, LowBatteryTracker, the battery dial UI.
- **Risks and unknowns.** Generic headsets report coarse 10% steps, so the trend is noisy and needs three or more discharge segments before it is shown; playing detection; the case battery is rarely available; always labelled as an estimate.
- **Sources.** [Gadgetbridge 0.85.0: generic headphones (read-aloud, auto call pickup, battery tracking)](https://gadgetbridge.org/blog/release-0_85_0/); Earshot research: competitors_and_demand.md (2026-10-20) (`docs/research/research_notes/Universal headset control accelerators/competitors_and_demand.md`)

#### 15. Smart alerts (`smart-alerts`)

*polish* | effort **S-M** | area Find

- **Pitch.** Useful alerts: one bud isn't charging, the case is low, buds left out of the case for 20 minutes, battery full, and auto-off only when nothing is playing.
- **Why it is special.** Straight from user requests in the prior research (a Sennheiser reviewer asks for auto-off only if nothing is playing and an alert when one bud isn't charging; a Pixel Buds reviewer wants a ring when the buds are disconnected). Works across brands wherever per-part battery exists.
- **How it works offline.** Extend LowBatteryTracker with rule types over per-part battery, charger state and play state; one notification channel per alert type; actions 'Ring' and 'Snooze'. Defaults off except two. POST_NOTIFICATIONS is already requested. Min API 28.
- **Builds on.** extras/alerts/LowBatteryAlert and LowBatteryTracker, per-part battery model, find/FindPlayer.
- **Risks and unknowns.** Needs per-part data (drivers and Apple adverts only); alert fatigue; auto-off needs a driver write (for Bose, [1.4]).
- **Sources.** Earshot research: competitors_and_demand.md (2026-10-20) (`docs/research/research_notes/Universal headset control accelerators/competitors_and_demand.md`)

### 4.4 Automation and integration

#### 16. More triggers, and conditions (`triggers-plus`)

*polish* | effort **M** | area Automate

- **Pitch.** Rules can start on battery level, charging, a mode change, call start or end, another Bluetooth device (the car), walking, or time since connect, and can be limited to a time window or battery level.
- **Why it is special.** Sony's Adaptive Sound Control learns your places from location and activity, and Sennheiser's Sound Zones use location. Earshot reaches the same outcomes deterministically, offline, visibly and editably, on every brand that has modes.
- **How it works offline.** New Trigger subclasses (keep the pinned @SerialName rule documented in AutomationRepository). Sources: AudioManager.OnModeChangedListener (API 31) for calls, ACL connect events for other devices, battery values from state, Sensor.TYPE_STEP_DETECTOR with the ACTIVITY_RECOGNITION runtime permission for walking (asked only when the user adds that rule). A conditions list (AND). FireGuard already prevents loops. Min API 28 (call listener 31, broadcast fallback below).
- **Builds on.** extras/automation/AutomationEngine, RuleRunner, FireGuard, SchedulePlanner, WifiSsidWatcher, ForegroundAppWatcher, A7 ModeRefs, shortcut/AndroidControlFeeds (already has call-mode and playback feeds).
- **Risks and unknowns.** More permissions (ask lazily); sensor battery cost (use batching); saved-rule format must stay downgrade-safe; editor complexity.
- **Sources.** [APKMirror: Sony Sound Connect 7.4.0 notes (Adaptive Sound Control by location/activity)](https://www.apkmirror.com/?p=1859022); [Sennheiser Smart Control (Sound Check, Sound Zones, accounts)](https://sennheiser-hearing.com/nl-BE/smartcontrol); [Android: NotificationManager reference (automatic zen rules, policy access)](https://developer.android.com/reference/android/app/NotificationManager)

#### 17. More actions (`actions-plus`)

*polish* | effort **M** | area Automate

- **Pitch.** Rules can also set volume, start Do Not Disturb, pause or play, speak the battery, switch codec, open an app, start the sleep timer, switch headset or apply a profile.
- **Why it is special.** Today a rule can only change mode, EQ or noise. Do Not Disturb goes through an app-owned automatic rule that appears in Android's own DND settings, so it is transparent and revocable.
- **How it works offline.** NotificationManager.addAutomaticZenRule (API 28) after the user grants notification-policy access once; volume and pause through AudioManager from the foreground service; codec through the association path; speech through SpokenFeedback; headset switch through the universal remote (Android 17). Min API 28.
- **Builds on.** core/AutomationAction (sealed interface), shortcut/SpokenFeedback, codec/AssociationCodecPath, bt/CompanionRemote.
- **Risks and unknowns.** Android 17 volume and audio hardening for the volume action; the policy-access prompt is scary, so explain it first; each action needs a permission explainer.
- **Sources.** [Android: NotificationManager reference (automatic zen rules, policy access)](https://developer.android.com/reference/android/app/NotificationManager); [Android 17: background audio hardening](https://developer.android.com/about/versions/17/changes/bg-audio); [Android: CompanionDeviceManager reference](https://developer.android.com/reference/android/companion/CompanionDeviceManager)

#### 18. Recipes (`recipes`)

*polish* | effort **S** | area Automate

- **Pitch.** Ten ready-made automations (Commute, Focus, Gym, Sleep, Work call, Plane and more) added in one tap, each listing what it needs.
- **Why it is special.** Lowers the barrier of the rule editor; Sony moved scenes to a front-and-centre tab in Sound Connect 12. Because rules store mode meaning, one recipe works on any brand.
- **How it works offline.** Static JSON templates become AutomationRule objects with placeholders (choose the app or Wi-Fi), plus a permission preflight. No permission of its own.
- **Builds on.** Automation rules, A7 mode refs, ui/more/AutomationEditorScreen.
- **Risks and unknowns.** Wording per brand; every recipe must stay editable; keep the count small.
- **Sources.** [APKMirror: Sony Sound Connect 12.0.0 notes (Scene tab, listening log, WHO comparison)](https://apkmirror.com/apk/sony-corporation/sony-headphones-connect)

#### 19. Why didn't it fire (`rule-explainer`)

*polish* | effort **S** | area Automate

- **Pitch.** Test any rule now and see line by line which trigger, condition, permission or cooldown stopped it.
- **Why it is special.** Maker routines are black boxes (Bose Play reviews say the 'remember my settings' option never worked). Transparent automation is on-brand and cuts support questions.
- **How it works offline.** Dry-run mode in RuleRunner that returns a structured explanation (trigger state, condition state, FireGuard cooldown, headset not connected, mode missing: '... isn't a mode of these headphones'); shown on the rule detail screen and written to AutomationLog.
- **Builds on.** RuleRunner, FireGuard, AutomationLog.
- **Risks and unknowns.** Needs a stable enum of skip reasons; small otherwise.
- **Sources.** Earshot research: competitors_and_demand.md (2026-10-20) (`docs/research/research_notes/Universal headset control accelerators/competitors_and_demand.md`)

#### 20. Automation API (`automation-api`)

*standout* | effort **M** | area Automate

- **Pitch.** An opt-in, token-protected intent interface so Tasker, MacroDroid or Automate can read battery and mode and trigger Earshot actions, on any headset.
- **Why it is special.** Gadgetbridge ships an opt-in Intent API (off by default, per-category switches) aimed at Tasker, Automate and MacroDroid; I found no cross-brand headset app that does this, and the XDA Wavelet thread has a Tasker request.
- **How it works offline.** An exported broadcast receiver that stays disabled until Advanced > Automation API is switched on; every call carries a random token shown in the app; actions map to the same core commands as the tile and widget; outbound events (connected, battery, mode changed) are sent to a package the user enters (explicit intents). A read-only subset has its own switch. Min API 28.
- **Builds on.** HeadphoneRepository commands, tile and widget action handlers, the single write path (WriteGuard) which stays in front of everything.
- **Risks and unknowns.** Any installed app can send an intent, so token, per-category switches and an audit log (see look-only-mode) are mandatory; dangerous operations (power off, pairing, resets) are never exposed; support burden.
- **Sources.** [Gadgetbridge: Intent API (opt-in, per-category switches)](https://gadgetbridge.org/internals/automations/intents); [XDA: Wavelet thread (per-device EQ requests, Tasker request)](https://xdaforums.com/t/app-9-0-wavelet-headphone-specific-equalization.4097957/page-6)

#### 21. Device controls (`device-controls`)

*polish* | effort **S-M** | area Automate

- **Pitch.** The headset appears in Android's Device controls (the power-menu panel) with battery, mode, EQ preset and Find.
- **Why it is special.** Two taps from the lock screen without opening the app, beside smart-home controls; Bose's widget only works while its app runs and holds a companion permission.
- **How it works offline.** A ControlsProviderService (Android 11+) with templated controls (toggle, range, status) fed by StateFlow through Flow publishers; the docs use kotlinx-coroutines-jdk9 for that, which would be a new dependency to request. Service declared with BIND_CONTROLS. Hidden below API 30.
- **Builds on.** extras/tile/ModeTileService, extras/widget/EarshotWidget, TileCycle.
- **Risks and unknowns.** Placement differs by OEM; a new dependency; control updates need the app process alive (the foreground service already keeps it).
- **Sources.** [Android: Control external devices (Device Controls)](https://developer.android.com/develop/ui/views/device-control); [Bose support: Android widget (needs the app running and companion permission)](https://www.bose.co.nz/en_nz/support/article/setting-up-your-system-bma.html)

#### 22. Long-press shortcuts (`shortcuts`)

*polish* | effort **S** | area Automate

- **Pitch.** Long-press the app icon for Quiet, Aware, next EQ, Find my earbuds or Sleep 30 min, and pin any of them to the home screen.
- **Why it is special.** Tiny and fast, works on every launcher and in launcher search, and the user starts the action, which keeps it clear of Android 17's background-audio limits.
- **How it works offline.** ShortcutManager static, dynamic (updated with the connected headset's modes) and pinned shortcuts pointing at a trampoline activity. Min API 28 (pinned shortcuts API 26). No permission. (Platform knowledge, not re-verified in this pass.)
- **Builds on.** shortcut/ShortcutTriggerActivity pattern, mode list from HeadphoneState.
- **Risks and unknowns.** The trampoline must not flash UI; launchers show about four shortcuts.
- **Sources.** [Android 17: background audio hardening](https://developer.android.com/about/versions/17/changes/bg-audio)

#### 23. Ask your assistant (`app-functions`)

*standout* | effort **S-M** | area Automate

- **Pitch.** Expose a few Earshot actions as AppFunctions so Android assistants can honour 'switch my headphones to Aware'.
- **Why it is special.** Would make Earshot one of the first headset apps that is agent-ready on-device (I found none doing it); AppFunctions run locally ('on-device MCP'), which is consistent with no INTERNET, and it stays off by default.
- **How it works offline.** Jetpack androidx.appfunctions annotations (@AppFunction) on setMode, applyPreset, getBattery and findEarbuds; Android 16+; callers need EXECUTE_APP_FUNCTIONS. As of May 2026 the Gemini integration is a private preview. New dependency (integrator).
- **Builds on.** The shared action layer built for the automation API and shortcuts.
- **Risks and unknowns.** Preview API that may change; the calling assistant (Gemini) is Google's service even though Earshot stays offline, which is a privacy-copy question; only limited apps can use the pipeline today; not available on AOSP builds (community guidance).
- **Sources.** [Android: Overview of AppFunctions](https://developer.android.com/ai/appfunctions); [InfoQ: AppFunctions and agents](https://www.infoq.com/news/2026/03/android-appfunctions-agents)

#### 24. Live status chip (`live-status`)

*polish* | effort **S** | area Automate

- **Pitch.** While a Find ring or a Sleep timer runs, show it as an Android Live Update (status-bar chip and lock-screen card) with a Stop button.
- **Why it is special.** Matches Android 16's design language and shows state without opening the app.
- **How it works offline.** Notification.ProgressStyle with POST_PROMOTED_NOTIFICATIONS (normal permission) and setRequestPromotedOngoing(true); ongoing, with a title, no custom RemoteViews; Android 16 QPR1 and later, falling back to an ordinary ongoing notification. Chip text must be short (the platform shows it only if it fits).
- **Builds on.** The Find notification action, sleep timer, existing notification channels.
- **Risks and unknowns.** Live Updates are for ongoing, user-started, time-sensitive activities and explicitly not for 'accelerated access to app functionality': a Find ring qualifies, a sleep timer is borderline; OEMs may add criteria; stable Android 16 reportedly had the feature off by default (Android Authority, unverified now).
- **Sources.** [Android: Create Live Update notifications](https://developer.android.com/develop/ui/views/notifications/live-update); [Android Authority: Live Updates in Android 16 QPR1](https://www.androidauthority.com/android-16-qpr1-live-updates-3573399); [Android 16 features (ranging, hearing-aid mic routing, progress notifications)](https://developer.android.com/about/versions/16/features)

### 4.5 Profiles and switching

#### 25. Headset profiles (`headset-profiles`)

*standout* | effort **M-L** | area Profile

- **Pitch.** One profile per headset that follows it: EQ, start volume and ceiling, default mode, codec, call boost, name and the rules bound to it, with Home / Commute / Work variants.
- **Why it is special.** XDA threads ask for exactly this (per-device EQ plus volume plus automatic switching) and note no single app covers all three; Wavelet remembers only the EQ; makers' profiles are brand-locked. Because modes are stored by meaning (A7), a profile can also be copied from one brand to another.
- **How it works offline.** A HeadsetProfile record keyed by address (plus LE-twin alias) replaces scattered prefs (HeadsetEqMemory, CallAudioPrefsStore, DevicePrefs); applied when the audio link comes up; variants selected manually, from a tile long-press or by a rule. One Profile page per headset. Min API 28.
- **Builds on.** audio/HeadsetEqMemory, call/CallAudioPrefsStore, bt/DevicePrefs, core/ModeMeaning, AutomationRule, the universal-remote alias.
- **Risks and unknowns.** Migration of existing prefs without losing anyone's setup; precedence between profile, rule and app state; Android 17 hardening for the volume part; a big UI surface.
- **Sources.** [XDA: Wavelet thread (per-device EQ requests, Tasker request)](https://xdaforums.com/t/app-9-0-wavelet-headphone-specific-equalization.4097957/page-6); [XDA: Bluetooth audio volume (per device setting)](https://xdaforums.com/t/bluetooth-audio-volume-per-device-setting.3867368/); [XDA: Equalizer change based on Bluetooth device](https://xdaforums.com/t/equalizer-change-based-on-bluetooth-device.3967653/); [XDA: Precise Volume (Bluetooth automation, per-device presets)](https://xdaforums.com/t/app-9-0-precise-volume-fine-tune-volume-steps-auto-eq-automation-replace-volume-interface-more-no-root.3573562/page-9)

#### 26. Headset garage (`headset-garage`)

*standout* | effort **M** | area Profile

- **Pitch.** A home for all your headsets: last-known battery with its age, last seen, health, active profile and a Connect button, even when none is connected.
- **Why it is special.** Android Settings shows only what is connected; each maker app knows one brand and shows little when its headset is off; app fatigue is a documented gripe (iMore counted eight headphone apps on one iPhone).
- **How it works offline.** Persist a small last-known summary per device on disconnect; a Compose list on the empty/disconnected Home; Connect via the universal remote on Android 17, otherwise 'Open Bluetooth settings'. Min API 28.
- **Builds on.** Device switcher, bt/DevicePrefs, universal remote, battery history.
- **Risks and unknowns.** Staleness must be obvious (show the age); names are personal data kept locally; experimental drivers need an 'unverified' tag.
- **Sources.** [iMore (2023): Please stop making headphone apps](https://imore.com/music-movies-tv/please-stop-making-headphone-apps-my-phone-is-full); Earshot research: competitors_and_demand.md (2026-10-20) (`docs/research/research_notes/Universal headset control accelerators/competitors_and_demand.md`)

#### 27. Switch headset (`switch-headset`)

*polish* | effort **S-M** | area Profile

- **Pitch.** One tap on the tile, widget or a shortcut disconnects the current headset and connects the next, whatever the brand.
- **Why it is special.** Multipoint switching is a top complaint (Bose Connect reviews call it whack-a-mole; Sony reviews describe wrong-device priority). Earshot does it phone-side for any brand through the universal remote.
- **How it works offline.** BluetoothDevice.disconnect() then connect() (API 37, needs the Companion Device Manager association); on older Android, deep link to the Bluetooth / media output screen; Bose can also use its own device routing [4.12]. Min API 28, full on 37.
- **Builds on.** bt/CompanionRemote, device switcher, tile.
- **Risks and unknowns.** Samsung Auto Switch and multipoint races; behaviour of connect() / disconnect() on OEM stacks is untested; timeouts between the two steps.
- **Sources.** [Android: BluetoothDevice reference (connect/disconnect 37, setAlias 31, removeBond via CDM 36)](https://developer.android.com/reference/android/bluetooth/BluetoothDevice); [Android: CompanionDeviceManager reference](https://developer.android.com/reference/android/companion/CompanionDeviceManager); Earshot research: competitors_and_demand.md (2026-10-20) (`docs/research/research_notes/Universal headset control accelerators/competitors_and_demand.md`)

#### 28. Share and back up (`share-backup`)

*polish* | effort **S-M** | area Profile

- **Pitch.** Export every setting to one file (or share a single EQ preset or profile) and open it on another phone, no cloud.
- **Why it is special.** Makers' sync needs accounts (Sennheiser, Samsung); Earshot's is a file you control. EQ presets become shareable without a server.
- **How it works offline.** kotlinx.serialization (already used for rules) to a .earshot JSON through the Storage Access Framework (ACTION_CREATE_DOCUMENT); an intent filter to open .earshot files; an option to redact SSIDs and package names. Min API 28.
- **Builds on.** extras/automation/AutomationJson (pinned names), dsp/PresetCodec.
- **Risks and unknowns.** Format versioning; SSIDs are personal; imports must be validated.
- **Sources.** [Sennheiser Smart Control (Sound Check, Sound Zones, accounts)](https://sennheiser-hearing.com/nl-BE/smartcontrol); Earshot research: competitors_and_demand.md (2026-10-20) (`docs/research/research_notes/Universal headset control accelerators/competitors_and_demand.md`)

### 4.6 Trust, honesty and community

#### 29. Look, don't touch (`look-only`)

*standout* | effort **S-M** | area Trust

- **Pitch.** A switch that makes Earshot read-only for one headset (or all), plus a plain-language ledger of everything it sent.
- **Why it is special.** Lets owners of expensive headsets try an experimental driver with zero write risk, and I know of no other app that shows what it sent. It is the strongest possible expression of 'honest about what is tested'.
- **How it works offline.** A read-only flag checked in every vendor WriteGuard (the single write path already exists) and in the generic repository; the ledger is a ring buffer of (time, command category, outcome) with human text, fed by the guards and included in the connection report. Min API 28.
- **Builds on.** bt/WriteGuard and the per-vendor guards, ui/tools/DiagnosticsReport, the Lab console.
- **Risks and unknowns.** Some 'writes' are session setup (Bose [9.2] subscribe is a SET_GET), so commands need a category; features that need writes (EQ on the headset) must say 'turned off by Look-only'; the ledger must never record secrets (Bragi / Sennheiser pairing material).
- **Sources.** [Exodus Privacy: Bose app (8 trackers, 29 permissions)](https://reports.exodus-privacy.eu.org/en/reports/com.bose.bosemusic/latest/)

#### 30. Test pilot (`test-pilot`)

*standout* | effort **M** | area Trust

- **Pitch.** A guided checklist turns every owner of an experimental headset into a verifier: try each feature, answer yes or no, send a structured report.
- **Why it is special.** The project's bottleneck is hardware (Gadgetbridge closed a Jabra PR because the contributor no longer had the earbuds; OpenSCQ30 prioritises models by thumbs-up). This scales verification and lets tiers move from Experimental to Tested openly.
- **How it works offline.** Per-driver checklist of safe, reversible steps with automatic read-back comparison; results plus model, firmware and masked addresses packaged through the share sheet or an issue-template text; a tested.json asset maps model to tier for the next release. No network. Min API 28.
- **Builds on.** Lab, Copy connection report, the conformance suite C1-C8, demo mode and the virtual devices.
- **Risks and unknowns.** Writes on untested hardware (mitigate with Look-only, undo, read-back, allow-lists); the wording must not promise safety; triage workload for maintainers.
- **Sources.** Earshot research: competitors_and_demand.md (2026-10-20) (`docs/research/research_notes/Universal headset control accelerators/competitors_and_demand.md`); [OpenSCQ30 on GitHub (device requests dominate)](https://github.com/Oppzippy/OpenSCQ30)

#### 31. Connection doctor (`connection-doctor`)

*polish* | effort **M** | area Trust

- **Pitch.** When a headset won't connect or keeps dropping, Earshot says why in plain words and offers the fix.
- **Why it is special.** 'Can't see or connect to my headset' is the top complaint class in maker apps (Bose, JBL, Samsung reviews); those apps just spin. Earshot already records Android 17 disconnect reasons, lost pairing keys and encryption changes.
- **How it works offline.** A rule-based analyser over the Bluetooth history plus current state: key missing means 'forget and re-pair' (CompanionDeviceManager.removeBond, API 36, or Settings); repeated SDP / AVDTP failures (EXTRA_DISCONNECTED_REASON, API 37) mean 'toggle Bluetooth / LE Audio'; the maker's app holds the channel means 'close X'; also low battery, multipoint steal, forced codec. Shows evidence lines and a confidence. Min API 28 (richer on 36-37).
- **Builds on.** bt/BtTimeline and ui/tools/TimelineScreen, ConnectionProblem.ChannelHeld, DiagnosticsReport, the universal remote.
- **Risks and unknowns.** Heuristics can mis-diagnose, so show evidence; older Android exposes less.
- **Sources.** [Android: BluetoothDevice reference (connect/disconnect 37, setAlias 31, removeBond via CDM 36)](https://developer.android.com/reference/android/bluetooth/BluetoothDevice); Earshot research: android_platform.md (2026-10-20) (`docs/research/research_notes/Universal headset control accelerators/android_platform.md`); Earshot research: competitors_and_demand.md (2026-10-20) (`docs/research/research_notes/Universal headset control accelerators/competitors_and_demand.md`)

#### 32. Confirmed by the headset (`confirmed-badges`)

*polish* | effort **S** | area Trust

- **Pitch.** Every control shows a tick when the headset's read-back matched, a warning when it did not answer, and 'your firmware doesn't support this' when refused.
- **Why it is special.** 'Settings that don't stick' is a recurring maker-app complaint; Earshot already reads back, so it can show it, and a tier chip on each screen puts 'Tested / Experimental / Generic' where users look.
- **How it works offline.** Surface existing read-back results (the Soundcore 'confirms' step, TolerantReader, FuncNotSupp) as a tri-state on vendor controls and Bose controls; a tier chip per Tools screen. No permission.
- **Builds on.** HeadphoneState.vendorControls and VendorControlsCard, the drivers' read-back code, docs/supported-headsets.md tiers.
- **Risks and unknowns.** Needs one result type shared by the drivers; avoid UI noise.
- **Sources.** Earshot research: competitors_and_demand.md (2026-10-20) (`docs/research/research_notes/Universal headset control accelerators/competitors_and_demand.md`)

#### 33. Privacy receipt (`privacy-receipt`)

*polish* | effort **S** | area Trust

- **Pitch.** A screen that proves the promises: permissions in use and why, 'no internet permission' computed from the installed package, the signing certificate and build info to compare with GitHub.
- **Why it is special.** The Bose app shows 8 trackers and 29 permissions on Exodus, Nothing X 92 permissions; none lets you verify on the device. Fits the 1.0 Play checklist (licences screen) too.
- **How it works offline.** PackageManager.getPackageInfo with GET_PERMISSIONS and GET_SIGNING_CERTIFICATES; plain-English reason per permission; the open-source licences list the Play checklist needs. Min API 28.
- **Builds on.** ui/more/AboutScreen, the permission explainers in onboarding.
- **Risks and unknowns.** Claim only what can be verified on the device.
- **Sources.** [Exodus Privacy: Bose app (8 trackers, 29 permissions)](https://reports.exodus-privacy.eu.org/en/reports/com.bose.bosemusic/latest/); Earshot research: competitors_and_demand.md (2026-10-20) (`docs/research/research_notes/Universal headset control accelerators/competitors_and_demand.md`)

### 4.7 Delight and platform polish

#### 34. Say it to your headset (`headset-dictation`)

*standout* | effort **M** | area Delight

- **Pitch.** Press and hold your headset, dictate, and the text lands in a local note or on the clipboard, recognised on the phone.
- **Why it is special.** I found no headset app that offers this; it combines Earshot's assistant-gesture Controls, the headset mic route and Android's on-device recognizer, fully offline.
- **How it works offline.** SpeechRecognizer.createOnDeviceSpeechRecognizer (API 31) with checkRecognitionSupport and triggerModelDownload (the system manages language packs, so Earshot still has no INTERNET); also transcribe saved voice memos with RecognizerIntent.EXTRA_AUDIO_SOURCE (API 33; a recognizer may ignore it). RECORD_AUDIO exists. Min API 31.
- **Builds on.** shortcut/VoiceMemoService, the voice-memo shortcut action, mic/HeadsetMicRouter, SpokenFeedback.
- **Risks and unknowns.** On-device model availability varies by OEM and language; SCO mic quality is poor; Android 17 mic foreground services must start while visible (the press opens an activity, which counts); where the text is stored.
- **Sources.** [Android: SpeechRecognizer reference (on-device recognizer, model download)](https://developer.android.com/reference/android/speech/SpeechRecognizer); [Android: RecognizerIntent reference (EXTRA_AUDIO_SOURCE)](https://developer.android.com/reference/android/speech/RecognizerIntent); [Android 17: background audio hardening](https://developer.android.com/about/versions/17/changes/bg-audio)

#### 35. Earcons (`earcons`)

*polish* | effort **S** | area Delight

- **Pitch.** Short distinct sounds, or a spoken word, when the mode, EQ preset or battery state changes, for headsets that stay silent.
- **Why it is special.** A direct community request (Sonos Ace owners ask for a different sound when ANC is toggled). Cheap, noticeable and fully offline.
- **How it works offline.** Pre-synthesised tones plus SpokenFeedback, ducking the media stream; only on user-initiated changes (tile, widget, button) to respect Android 17; one setting per event; off by default. Min API 28.
- **Builds on.** shortcut/SpokenFeedback, find/FindTone, the 'announced in the headset' EQ-cycle feedback.
- **Risks and unknowns.** Background-audio hardening (keep to user-started changes); do not duplicate headset prompts (Bose voice prompts); can annoy, so off by default.
- **Sources.** [Sonos community: Ace ANC toggle sound and music dimming request](https://en.community.sonos.com/headphones-229146/ace-anc-fix-request-6897784); [Android 17: background audio hardening](https://developer.android.com/about/versions/17/changes/bg-audio)

#### 36. Read it to me (`read-aloud`)

*polish* | effort **M** | area Delight

- **Pitch.** Optional spoken notifications and auto-answer for any headset, with an app and contact allow-list.
- **Why it is special.** Gadgetbridge 0.85 added exactly this for generic headphones; an offline, allow-listed, off-by-default version is on-brand.
- **How it works offline.** NotificationListenerService (user grant) into TTS on the headset's output; auto-answer through the telecom API (ANSWER_PHONE_CALLS). Min API 28 (26 for answering).
- **Builds on.** shortcut/SpokenFeedback, the shortcut engine, call signals.
- **Risks and unknowns.** Notification access is a heavy permission for a privacy-first app; reading aloud in public leaks content; Android 17 audio hardening; lowest priority of the set.
- **Sources.** [Gadgetbridge 0.85.0: generic headphones (read-aloud, auto call pickup, battery tracking)](https://gadgetbridge.org/blog/release-0_85_0/)

#### 37. Fold-ready layouts (`fold-adaptive`)

*polish* | effort **M** | area Delight

- **Pitch.** Two-pane Home plus Sound on big screens, a larger EQ curve, keyboard and mouse support, and a cover-screen-friendly widget.
- **Why it is special.** The developer's phone is a Fold7, and since Android 16 apps targeting API 36 cannot opt out of resizing and orientation on screens of 600 dp or more, so this is required quality for the Play listing, not a luxury.
- **How it works offline.** WindowSizeClass and adaptive layouts (material3 window-size-class / adaptive libraries are a new dependency to request), WindowInfoTracker posture for tabletop; test on the resizable emulator and the Fold7.
- **Builds on.** The Compose UI, ui/sound/EqCurveCanvas, the widget.
- **Risks and unknowns.** New dependency; screenshots to redo; One UI cover-screen widget support is unverified.
- **Sources.** [Android Developers Blog: orientation and resizability changes in Android 16](https://android-developers.googleblog.com/2025/01/orientation-and-resizability-changes-in-android-16.html)

#### 38. Head tracker lab (`head-tracker-lab`)

*standout* | effort **S (readout), M (gestures)** | area Delight

- **Pitch.** Show whether Android is getting head tracking from your headset, with a live view, and where it exists, nod to answer and shake to decline.
- **Why it is special.** LibrePods charges for head gestures and only for AirPods; a cross-brand version would work with any headset that implements Android's head-tracker HID protocol.
- **How it works offline.** Spatializer (API 33) read-only calls (isAvailable, isEnabled, isHeadTrackerAvailable, immersive level) and SensorManager.getDynamicSensorList(Sensor.TYPE_HEAD_TRACKER). Protocol v1.0 is Android 13, v2.0 (LE Audio) Android 15, and the phone maker must enable it. Pose to gesture classifier is the second step. Answering calls needs ANSWER_PHONE_CALLS.
- **Builds on.** Lab, ui/tools/PhoneLeAudio.
- **Risks and unknowns.** Very few headsets expose the sensor; whether apps can read it without an extra permission is unverified here; treat as a Lab spike first.
- **Sources.** [AOSP: Head tracker HID protocol (v1.0 Android 13, v2.0 LE Audio Android 15)](https://source.android.com/docs/core/sensors/head-tracker-hid-protocol); [LibrePods on GitHub (head gestures, audiogram entry, no hearing test)](https://github.com/librepods-org/librepods); [LibrePods on Google Play](https://play.google.com/store/apps/details?id=me.kavishdevar.librepods)

#### 39. Auracast status (`auracast-status`)

*polish* | effort **S** | area Delight

- **Pitch.** Tell the user whether their phone and headset support LE Audio and Auracast sharing, open the system's sharing screen, and say honestly that apps cannot drive it.
- **Why it is special.** Answers a question many users ask and keeps the honest-limits identity (the broadcast classes are SystemApi; Google's Voice Broadcast is system-only).
- **How it works offline.** BluetoothAdapter.isLeAudioSupported / isLeAudioBroadcastSourceSupported / isLeAudioBroadcastAssistantSupported (API 33); AudioDeviceInfo BLE types including TYPE_BLE_HEARING_AID on Android 17; a deep link to Bluetooth settings. Extends PhoneLeAudio. Min API 33 for the checks.
- **Builds on.** ui/tools/PhoneLeAudio, the Bose LE Audio switch.
- **Risks and unknowns.** No public broadcast API; OEM settings paths differ.
- **Sources.** Earshot research: android_platform.md (2026-10-20) (`docs/research/research_notes/Universal headset control accelerators/android_platform.md`); [TechCrunch: Android 16 adds Auracast support](https://techcrunch.com/2025/03/13/android-is-adding-auracast-support-which-allows-hearing-aids-to-connect-to-public-audio-broadcasts/); [SoundGuys: Android 17 QPR2 beta, Pixel 'Voice Broadcast'](https://www.soundguys.com/android-17-qpr2-beta-4-pixel-voice-broadcast-162201/); [Android Developers Blog: Android 17 Beta 3 (TYPE_BLE_HEARING_AID)](https://android-developers.googleblog.com/2026/03/the-third-beta-of-android-17.html)

## 5. Considered and rejected

| Idea | Why not |
|---|---|
| Control or join Auracast broadcasts from Earshot | The broadcast classes are SystemApi; apps only get feature checks (API 33). Auracast status covers the honest part. |
| AirPods listening modes | Needs a hidden-API bypass to open classic L2CAP; already a no-go in the roadmap (C2). Point users to LibrePods or CAPod. |
| Accessibility-service button remapper | AVRCP keys never become input events, Play restricts non-tool accessibility services and Android 17's Advanced Protection Mode blocks them; the assistant-gesture remapper stays. |
| Real sound-dose (dB) tracking | No app-facing API ('no external-facing APIs'); only an honest estimate is possible (listening-log). |
| Crossfeed, virtualizer or microphone EQ | Mixing channels or processing call audio needs capture or privileged APIs; DynamicsProcessing is per channel only. |
| Cloud presets, community upload, online AutoEq search, firmware updates | Break the no-INTERNET rule; sharing is by file instead (share-backup). |
| 'Last seen on a map' with GPS | Needs background location and heavy review; Wi-Fi names (last-seen) give most of the value. |
| Wear OS companion | The Data Layer needs Google Play services; revisit only if the project accepts that. |
| One-tap mono audio tile | Writing the accessibility setting needs WRITE_SECURE_SETTINGS (Shizuku only); the sound-check pack warns about it instead. |
| Learned/AI places and routines | Location learning conflicts with privacy and with 'visible, editable rules'; triggers-plus is the deterministic answer. |
| Bluetooth Quality Report dashboards | SystemApi. |
| Wired-style 'bit-perfect' modes | Bit-perfect mixer attributes are USB-only. |

## 6. Spikes to run before committing

- **AutoEq data licence** (autoeq-match): read each measurement source's terms, or ask the maintainers; until then ship the folder import.
- **Volume and audio from a companion-started service on Android 17** (volume-guard, sleep-kit, earcons): does a service started by the companion-presence callback count as visible/while-in-use? The 0.8 spike notes leave this open.
- **What `getActivePlaybackConfigurations` shows on One UI 9** (listening-log, aware-dim): can Earshot tell that a player is routed to the headset?
- **BLE adverts of bonded earbuds** (sonar-finder): does the Fold7 report bonded earbuds' adverts, and what does the QC Ultra 2 advertise when out of range of its case?
- **Per-channel DynamicsProcessing plans on One UI 9** (match-your-ears): confirm L/R can differ and the limiter stage still holds.
- **Live-listen latency** on the QC Ultra 2 over AAC and over LE Audio.
- **On-device speech packs on the Fold7** (headset-dictation): which languages are available without a download?
- **Head-tracker sensor**: dump `SensorManager.getDynamicSensorList(TYPE_HEAD_TRACKER)` with the QC Ultra 2 and any other headset at hand (head-tracker-lab).
- **Device Controls dependency** (device-controls): can the Flow publishers be written without `kotlinx-coroutines-jdk9`?

## 7. Notes on the current repo

- **AutoEq claim.** README and the task brief say AutoEq curves are bundled offline. The code has `AutoEqParser`, a paste-in importer on the Sound screen and one built-in curve ('Tuned for QC Ultra'); the roadmap says the by-name suggestion 'was not built' (B3). autoeq-match fills that gap.
- **Automation engine today.** Four triggers (on connect, Wi-Fi, foreground app, schedule) and three actions (mode, EQ preset, noise). That is why triggers-plus and actions-plus are 'M' rather than 'S'.
- **Not in the app yet** (checked by search): sleep timer, listening log, battery history, hearing or tone tests beyond the Find tone, any intent API, dictation.
- **Existing building blocks** the ideas lean on: `HeadsetEqMemory`, `DynamicsProcessingEngine` (input gain, 32-band EQ, limiter), `AudioSessionReceiver`, `FindTone/FindOutput/FindPlayer`, `AppleAdvertScanner`, `BtTimeline/TimelineStore`, `CompanionRemote`, `HeadsetPresenceService`, `SpokenFeedback`, `VoiceMemoService`, `RuleRunner/FireGuard/AutomationLog`, `ModeResolver`, the per-vendor write guards and the Lab.

## 8. Sources

- [AutoEq on GitHub (MIT code; measurement sources oratory1990, crinacle, Rtings, InnerFidelity)](https://github.com/jaakkopasanen/AutoEq)
- [headphones.com forum: AutoEq presets and oratory1990 terms (anecdotal)](https://forum.headphones.com/t/eq-for-both-ios-mac/4874?page=4)
- [Wavelet on Google Play (5M+)](https://play.google.com/store/apps/details?id=com.pittvandewitt.wavelet)
- [APKMirror: Wavelet 26.05 notes (AutoEq 5000+ profiles, channel balance, BLE hearing aid detection)](https://www.apkmirror.com/?p=13969488)
- [XDA: Wavelet thread (per-device EQ requests, Tasker request)](https://xdaforums.com/t/app-9-0-wavelet-headphone-specific-equalization.4097957/page-6)
- [Soundcore community: Life Q30 equalizer stopped working after firmware 1.9](https://community.soundcore.com/t/life-q30-equalizer-not-working/1824833)
- Earshot research: competitors_and_demand.md (2026-10-20) (`docs/research/research_notes/Universal headset control accelerators/competitors_and_demand.md`)
- [JBL: Personi-Fi 2.0 (18-tone hearing test)](https://support.jbl.com/howto/personi-fi-2-0-us/000040242.html)
- [Samsung: hearing aid feature cleared by FDA (5-minute self test, Software as a Medical Device)](https://news.samsung.com/ca/samsungs-hearing-aid-feature-on-galaxy-buds-cleared-by-fda)
- [What Hi-Fi: Nothing Ear (2) (fit test, Mimi personal sound)](https://www.whathifi.com/news/nothing-announces-updated-ear-2-wireless-earbuds-promising-hi-res-audio-better-battery-life-and-adaptive-anc)
- [Sennheiser Smart Control (Sound Check, Sound Zones, accounts)](https://sennheiser-hearing.com/nl-BE/smartcontrol)
- [LibrePods on GitHub (head gestures, audiogram entry, no hearing test)](https://github.com/librepods-org/librepods)
- [Univ. of Michigan: phone audiometry review (screening only, room matters)](https://open.umich.edu/sites/default/files/downloads/mobilephoneandcellphoneaudiometry.pdf)
- [Frontiers in Psychology 2020: calibrated tablet audiometry](https://www.frontiersin.org/journals/psychology/articles/10.3389/fpsyg.2020.00744/pdf)
- [headphones.com forum: audiograms and EQ](https://forum.headphones.com/t/audiograms-and-eqing-for-both-the-headphone-and-individual-thoughts/16571)
- [Android: DynamicsProcessing reference (API 28)](https://developer.android.com/reference/android/media/audiofx/DynamicsProcessing)
- [Android: AudioEffect reference (control-session intent, EXTRA_CONTENT_TYPE)](https://developer.android.com/reference/android/media/audiofx/AudioEffect)
- Earshot research: android_platform.md (2026-10-20) (`docs/research/research_notes/Universal headset control accelerators/android_platform.md`)
- [Sonos community: Ace ANC toggle sound and music dimming request](https://en.community.sonos.com/headphones-229146/ace-anc-fix-request-6897784)
- [XDA: Bluetooth audio volume (per device setting)](https://xdaforums.com/t/bluetooth-audio-volume-per-device-setting.3867368/)
- [XDA: Precise Volume (Bluetooth automation, per-device presets)](https://xdaforums.com/t/app-9-0-precise-volume-fine-tune-volume-steps-auto-eq-automation-replace-volume-interface-more-no-root.3573562/page-9)
- [Bluetooth Volume Manager on Google Play (1M+)](https://play.google.com/store/apps/details?id=eu.darken.bluemusic)
- [Fairphone forum: Bluetooth volume steps broken after Android 15](https://forum.fairphone.com/t/bluetooth-volume-is-louder-after-android-15/122201)
- [Engadget: disabling absolute Bluetooth volume](https://engadget.com/2240704/android-disable-absolute-bluetooth-volume-how-to-guide/)
- [Android 17: background audio hardening](https://developer.android.com/about/versions/17/changes/bg-audio)
- [HeadphoneCheck: Nothing Ear (a) (low lag, in-ear detection, fit test)](https://www.headphonecheck.com/test/nothing-ear-a/)
- [Bluetooth Codec Changer on Google Play (latency test, per-app codec profiles)](https://play.google.com/store/apps/details?id=com.amrg.bluetooth_codec_converter)
- [AOSP: Sound dose (CSD, 'no external-facing APIs')](https://source.android.com/docs/core/audio/sound-dose)
- [Google: Pixel sound exposure notifications](https://support.google.com/pixelphone/answer/16785703)
- [APKMirror: Sony Sound Connect 12.0.0 notes (Scene tab, listening log, WHO comparison)](https://apkmirror.com/apk/sony-corporation/sony-headphones-connect)
- [Android: AudioManager.AudioPlaybackCallback (API 26)](https://developer.android.com/reference/android/media/AudioManager.AudioPlaybackCallback)
- [HearingTracker: iOS Live Listen vs Android Sound Amplifier](https://forum.hearingtracker.com/t/ios-live-listen-vs-android-sound-amplifier/94866)
- [SoundGuys: Android 17 QPR2 beta, Pixel 'Voice Broadcast'](https://www.soundguys.com/android-17-qpr2-beta-4-pixel-voice-broadcast-162201/)
- [Android: LE Audio overview](https://developer.android.com/develop/connectivity/bluetooth/ble-audio/overview)
- [Notebookcheck: Soundcore Sleep A30 review](https://www.notebookcheck.net/Sleep-better-Soundcore-Sleep-A30-by-Anker-review.1174412.0.html)
- [TechRadar: Soundcore sleep earbuds, white noise](https://www.techradar.com/health-fitness/sleep/ive-been-testing-the-new-soundcore-sleep-earbuds-heres-why-their-white-noise-feature-is-a-dream)
- [Android: BluetoothDevice reference (connect/disconnect 37, setAlias 31, removeBond via CDM 36)](https://developer.android.com/reference/android/bluetooth/BluetoothDevice)
- [CAPod issues (find the AirPods and case, decibel level)](https://github.com/d4rken-org/capod/issues)
- [Google: find Pixel Buds / Find Hub (needs Google account and location history)](https://support.google.com/googlepixelbuds/answer/9642885)
- [Android: RangingManager reference (BLE channel sounding, BLE RSSI, UWB, Wi-Fi RTT)](https://developer.android.com/reference/android/ranging/RangingManager)
- [Gadgetbridge 0.85.0: generic headphones (read-aloud, auto call pickup, battery tracking)](https://gadgetbridge.org/blog/release-0_85_0/)
- [APKMirror: Sony Sound Connect 7.4.0 notes (Adaptive Sound Control by location/activity)](https://www.apkmirror.com/?p=1859022)
- [Android: NotificationManager reference (automatic zen rules, policy access)](https://developer.android.com/reference/android/app/NotificationManager)
- [Android: CompanionDeviceManager reference](https://developer.android.com/reference/android/companion/CompanionDeviceManager)
- [Gadgetbridge: Intent API (opt-in, per-category switches)](https://gadgetbridge.org/internals/automations/intents)
- [Android: Control external devices (Device Controls)](https://developer.android.com/develop/ui/views/device-control)
- [Bose support: Android widget (needs the app running and companion permission)](https://www.bose.co.nz/en_nz/support/article/setting-up-your-system-bma.html)
- [Android: Overview of AppFunctions](https://developer.android.com/ai/appfunctions)
- [InfoQ: AppFunctions and agents](https://www.infoq.com/news/2026/03/android-appfunctions-agents)
- [Android: Create Live Update notifications](https://developer.android.com/develop/ui/views/notifications/live-update)
- [Android Authority: Live Updates in Android 16 QPR1](https://www.androidauthority.com/android-16-qpr1-live-updates-3573399)
- [Android 16 features (ranging, hearing-aid mic routing, progress notifications)](https://developer.android.com/about/versions/16/features)
- [XDA: Equalizer change based on Bluetooth device](https://xdaforums.com/t/equalizer-change-based-on-bluetooth-device.3967653/)
- [iMore (2023): Please stop making headphone apps](https://imore.com/music-movies-tv/please-stop-making-headphone-apps-my-phone-is-full)
- [Exodus Privacy: Bose app (8 trackers, 29 permissions)](https://reports.exodus-privacy.eu.org/en/reports/com.bose.bosemusic/latest/)
- [OpenSCQ30 on GitHub (device requests dominate)](https://github.com/Oppzippy/OpenSCQ30)
- [Android: SpeechRecognizer reference (on-device recognizer, model download)](https://developer.android.com/reference/android/speech/SpeechRecognizer)
- [Android: RecognizerIntent reference (EXTRA_AUDIO_SOURCE)](https://developer.android.com/reference/android/speech/RecognizerIntent)
- [Android Developers Blog: orientation and resizability changes in Android 16](https://android-developers.googleblog.com/2025/01/orientation-and-resizability-changes-in-android-16.html)
- [AOSP: Head tracker HID protocol (v1.0 Android 13, v2.0 LE Audio Android 15)](https://source.android.com/docs/core/sensors/head-tracker-hid-protocol)
- [LibrePods on Google Play](https://play.google.com/store/apps/details?id=me.kavishdevar.librepods)
- [TechCrunch: Android 16 adds Auracast support](https://techcrunch.com/2025/03/13/android-is-adding-auracast-support-which-allows-hearing-aids-to-connect-to-public-audio-broadcasts/)
- [Android Developers Blog: Android 17 Beta 3 (TYPE_BLE_HEARING_AID)](https://android-developers.googleblog.com/2026/03/the-third-beta-of-android-17.html)

