# Competitors and demand: multi-brand / third-party headset companion apps on Android (as of 2026-10-20)

Method note: Play Store metrics (installs, stars, review counts, last update, ads/IAP flags) were scraped from each app's public Play listing (en_US/US) on **2026-10-20**. GitHub/Codeberg stars and release dates come from the public GitHub/Codeberg APIs on **2026-10-20**. Exodus Privacy figures are from each app's latest Exodus report; the report date is given with each. "Review samples" are the few "most relevant" reviews shown on the Play listing that day. They are anecdotes, not statistics.

## 1. Who Earshot competes with: multi-brand, brand-agnostic and single-brand third-party apps

### Takeaway
No Android app combines deep vendor drivers for several brands with system-wide audio features (EQ, codec, calls, remapping, automations). The field splits three ways:
- **Gadgetbridge** has the widest device list (85 headphone models across 20 brands). It has no audio features and is not on Play.
- **Audio-only apps** (Wavelet, Poweramp EQ, RootlessJamesDSP, Bluetooth Codec Changer) work with every headset but can't control any of them.
- **Single-brand clones** (LibrePods/CAPod for AirPods, BudsClient, OpenSCQ30, OpenFreebuds, Sony clients) go deep on one brand only.

The two threats to watch:
- **Gadgetbridge** is moving fast on headphones. It added Bose NC 700 and quick-settings tiles for several brands between Aug and Oct 2026.
- **LibrePods** has 30.4k GitHub stars and is now on Play. It dominates mindshare for AirPods, the brand Earshot plans to add next.

### Cited Findings

**Brand-agnostic / multi-brand**
- **Gadgetbridge**
  - Licence and stars: AGPL-3.0. Development moved to Codeberg, where it has 1,981 stars. The GitHub mirror is archived (4,602 stars, last push 2024-12-23). Checked 2026-10-20. — [Codeberg repo](https://codeberg.org/Freeyourgadget/Gadgetbridge); [GitHub mirror](https://github.com/Freeyourgadget/Gadgetbridge)
  - Headphone coverage: 85 models across 20 brands. Sony 19; Anker 8, Nothing 8, Xiaomi 8; Huawei 6, Realme 6, Samsung 6; EarFun 3, Jabra 3, Moondrop 3, Oppo 3; 1MORE 2, Bose 2, Shokz 2; B&W, Cardo, Google, Haylou, Sennheiser and UGREEN 1 each. — [gadgetbridge.org headphones](https://gadgetbridge.org/gadgets/headphones/)
  - Bose: only two models, NC 700 and QC35, both "highly supported". Features: battery/firmware readout, NC level, voice prompts, auto-off, shortcut button, media controls and multipoint management (list, forget, connect). No EQ is listed. No QC Ultra / QC45 / QC Earbuds. — [Gadgetbridge Bose page](https://gadgetbridge.org/gadgets/headphones/bose/)
  - Sony: 19 models, e.g. WH-1000XM2–XM6, WF-1000XM3–XM5, LinkBuds. Features: ANC/ambient, EQ, DSEE, touch modes, speak-to-chat, LDAC, multipoint. Gaps per model, e.g. XM5 partial (no multipoint or adaptive NC); XM6 experimental, no EQ. "Firmware updates" is listed as missing on several models. — [Gadgetbridge Sony page](https://gadgetbridge.org/gadgets/headphones/sony/)
  - "Generic headphones" (since 0.85.0): any connected Bluetooth audio device can be added for spoken notifications, automatic call pickup and battery tracking, with no device-specific support needed. — [Gadgetbridge 0.85.0 release](https://gadgetbridge.org/blog/release-0_85_0/)
  - 2026 release cadence: roughly monthly. 0.90.0 (2026-03-19), 0.91.0 (05-10), 0.91.1 (05-14), 0.92.0 (06-26), 0.92.1 (07-06), 0.92.2 (07-11), 0.93.0 (08-11), 0.94.0 (09-15), 0.95.0 (10-08), 0.95.1 (10-09). — [Codeberg releases](https://codeberg.org/Freeyourgadget/Gadgetbridge/releases)
  - Recent headphone work:
    - 0.93.0: quick-settings tiles for Sony headphones and Shokz.
    - 0.94.0: "Initial support for Bose NC 700", "Bose: Add quick settings", Nothing Ear QS tiles.
    - 0.95.0: Soundcore A30i, Redmi Buds 6 Lite, Sennheiser Momentum In-Ear; QS tiles for Anker, Oppo/Realme and Redmi; Oppo/Realme multipoint; Huawei "find individual earbuds"; "Generic headphones: Reflect the actual headphones connection".
    - [Codeberg releases](https://codeberg.org/Freeyourgadget/Gadgetbridge/releases)
  - A Jabra Elite 2 PR was closed on 2026-05-23 because the developer no longer had the earbuds. That is the "no hardware, no driver" limit at work. — [PR #3672](https://zff.dev/Freeyourgadget/Gadgetbridge/pulls/3672)
- **Why Gadgetbridge is not on Play**
  - The official build is F-Droid only (issue #3304 asks to reconsider). In 2017 (#480) the developer said he was "ambivalent". — [Codeberg #3304](https://codeberg.org/Freeyourgadget/Gadgetbridge/issues/3304); [Codeberg #480](https://codeberg.org/Freeyourgadget/Gadgetbridge/issues/480)
  - Espruino forum posts say the maintainers don't want to need the internet for anything, "also don't like the Play store", and have no interest in a listing. These are community statements, not an official FAQ. — [Espruino forum](https://forum.espruino.com/conversations/370708)
  - A separate "Bangle.js Gadgetbridge" build is on Play. It has the internet permission and was renamed to avoid confusion. — [Reddit r/Gadgetbridge via mirror](https://red.127.pp.ua/r/Gadgetbridge/comments/1ioxzx7/why_not_on_google_play)

**Audio / EQ apps (work on every headset, control none)**
- **Wavelet**
  - Play (2026-10-20): 5M+ installs, 3.7★ (19.3K reviews), updated 2026-05-24, in-app purchases, no ads. — [Play listing](https://play.google.com/store/apps/details?id=com.pittvandewitt.wavelet)
  - Closed source: its GitHub repo is documentation only, with no licence (893 stars). — [GitHub Pittvandewitt/Wavelet](https://github.com/Pittvandewitt/Wavelet)
  - Exodus: 0 trackers, 8 permissions (v26.05, report 2026-05-26). — [Exodus](https://reports.exodus-privacy.eu.org/en/reports/com.pittvandewitt.wavelet/latest/)
  - Features (Play listing): "5000+ AutoEq profiles"; import from autoeq.app or squig.link; the saved profile "automatically loads when switching between different headphones"; ISO 226 loudness compensation, limiter with auto-preamp, bass tuner, channel balance, virtualizer, reverb; no root. — [Play listing](https://play.google.com/store/apps/details?id=com.pittvandewitt.wavelet)
  - Limits:
    - Built on Android's `DynamicsProcessing` effect (needs Android 9+). The normal mode depends on players broadcasting their audio session. "Legacy mode" is a fallback that "might work" depending on the OEM audio framework (developer post). — [XDA Wavelet thread](https://forum.xda-developers.com/t/app-9-0-wavelet-headphone-specific-equalization.4097957/post-87124721)
    - It cannot run alongside RootlessJamesDSP or other `DynamicsProcessing` apps. — [RootlessJamesDSP on F-Droid](https://f-droid.org/en/packages/me.timschneeberger.rootlessjamesdsp/)
    - A GrapheneOS user says that since Android 12 it processes only music, not most apps or games. — [GrapheneOS forum](https://discuss.grapheneos.org/d/27431-questions-regarding-autoeq-support)
    - Honor/Android 16 report: Wavelet stopped detecting Spotify's session. — [Spotify Community](https://community.spotify.com/t5/Android/Equalizer-and-system-audio-effects-not-working/td-p/7299118)
    - Play review sample: it "no longer works for a single Bluetooth device, unless I disable HD audio". — [Play listing](https://play.google.com/store/apps/details?id=com.pittvandewitt.wavelet)
    - The developer of the open-source Equalizer314 says Wavelet gates some dynamics features behind premium. — [Equalizer314 on F-Droid](https://f-droid.org/packages/com.bearinmind.equalizer314/)
- **Poweramp Equalizer**
  - Play (2026-10-20): 5M+, 4.4★ (23.5K), updated 2026-10-16, IAP. — [Play listing](https://play.google.com/store/apps/details?id=com.maxmpz.equalizer)
  - Exodus: 0 trackers, 16 permissions (report 2026-06-04). — [Exodus](https://reports.exodus-privacy.eu.org/en/reports/com.maxmpz.equalizer/latest/)
  - Features: AutoEQ presets; 5–32 configurable bands or a parametric mode; limiter, compressor, balance; auto-resume on Bluetooth connect. "Advanced Player Tracking … requires additional permissions". — [Play listing](https://play.google.com/store/apps/details?id=com.maxmpz.equalizer)
- **RootlessJamesDSP**
  - GPL-3.0, 1,710 GitHub stars, last push 2026-08-31. — [GitHub](https://github.com/timschneeb/RootlessJamesDSP)
  - Play (2026-10-20): 100K+, last Play update 2024-12-14. F-Droid 1.6.14 (2025-05-06). — [Play](https://play.google.com/store/apps/details?id=me.timschneeberger.rootlessjamesdsp); [F-Droid](https://f-droid.org/en/packages/me.timschneeberger.rootlessjamesdsp/)
  - Setup needs Shizuku or ADB. Apps that block audio capture stay unprocessed (Spotify and Chrome are the examples given). It conflicts with Wavelet. AutoEQ integration. — [F-Droid](https://f-droid.org/en/packages/me.timschneeberger.rootlessjamesdsp/)
- **Bluetooth Codec Changer (AmrG DEV)**
  - Play (2026-10-20): 500K+, 4.6★ (5.96K), updated 2026-10-11, IAP. — [Play listing](https://play.google.com/store/apps/details?id=com.amrg.bluetooth_codec_converter)
  - Exodus: 2 trackers (Crashlytics, Firebase Analytics), 23 permissions (report 2026-07-22). — [Exodus](https://reports.exodus-privacy.eu.org/en/reports/com.amrg.bluetooth_codec_converter/latest/)
  - Features (listing):
    - Switch and lock LDAC, aptX family, AAC, LHDC, LC3 or SBC.
    - Auto-apply a codec on connect, per-app codec profiles, a codec database "community-powered".
    - Latency test, live bitrate in the notification, an "HCI packet analyzer" built on the system snoop log.
    - Battery and life estimates, EQ, widgets, automation hooks.
    - The listing doesn't say how switching works (Shizuku, root or otherwise).
    - [Play listing](https://play.google.com/store/apps/details?id=com.amrg.bluetooth_codec_converter)
- **Other utilities on Play (2026-10-20)**
  - Bluetooth Volume Manager (darken, per-device volume): 1M+, 3.9★. — [Play](https://play.google.com/store/apps/details?id=eu.darken.bluemusic)
  - Bluetooth Audio Connect Widget: 5M+, 4.3★, ads. — [Play](https://play.google.com/store/apps/details?id=com.tom.bluetoothDevicesWidget)
  - AutoEq-branded newcomers are tiny: "AutoEQ: Smart Music Equalizer" 1K+, "Auto Equalizer" 1K+. — [Play](https://play.google.com/store/apps/details?id=com.cloudpresser.autoeq); [Play](https://play.google.com/store/apps/details?id=com.antigravity.autoeq)
- **AutoEq**, the data source behind all of these, is MIT with 16,360 stars and was pushed 2026-10-20. — [GitHub jaakkopasanen/AutoEq](https://github.com/jaakkopasanen/AutoEq)

**AirPods on Android (Earshot's planned next vendor)**
- **LibrePods**
  - GPL-3.0, **30,388 stars**, 1,804 forks. v1.0.4 released 2026-10-16; v1.0.2–1.0.4 shipped within two days. — [GitHub](https://github.com/librepods-org/librepods)
  - Play (2026-10-20): 50K+, 4.8★ (1.42K), updated 2026-10-16, IAP. — [Play](https://play.google.com/store/apps/details?id=me.kavishdevar.librepods)
  - Reached Play in late April 2026 without root. Requires Android 16 QPR3; OnePlus/Oppo adapted OxygenOS/ColorOS 16, others need Android 17. Basic features free; head gestures and assistant activation cost a one-time €5. Full support for AirPods Pro 2/3 and Max, basics (battery, ear detection) for the rest. — [heise, 2026-04-28](https://heise.de/-11275308); [Android Authority](https://www.androidauthority.com/librepods-using-airpods-with-android-unlock-3661340/)
  - Features: listening modes from the app, Quick Settings or widget; ear detection; exact battery ("unlike most other apps that report to the nearest 10%"); head gestures; conversational awareness; rename; long-press customisation. — [Play](https://play.google.com/store/apps/details?id=me.kavishdevar.librepods)
  - Linux version in Rust. Some features need Xposed "VendorID spoofing" (hearing-aid mode, multi-device). The README says parts were AI-generated. — [GitHub README](https://github.com/librepods-org/librepods)
- **CAPod**
  - GPL-3.0, 1,192 stars, v5.5.0-rc0 on 2026-10-07. — [GitHub](https://github.com/d4rken-org/capod)
  - Play (2026-10-20): 500K+, 3.9★ (1.61K), updated 2026-10-12, IAP "Pro", ad-free. — [Play](https://play.google.com/store/apps/details?id=eu.darken.capod)
  - Exodus: 0 trackers, 17 permissions (2026-10-21 report). — [Exodus](https://reports.exodus-privacy.eu.org/en/reports/eu.darken.capod/latest/)
  - Features: battery/charging for pods and case, ear-detection play/pause, auto-connect, case-open popup, widgets. Distributed on Play, GitHub, F-Droid and IzzyOnDroid. — [GitHub README](https://github.com/d4rken-org/capod)
- **Closed-source AirPods battery apps (Play, 2026-10-20)**
  - AndroPods: 10M+, 4.1★ (71.9K); ear detection and 4-tap assistant are Pro; 1 tracker (Firebase Analytics, Exodus 2025-08-28). — [Play](https://play.google.com/store/apps/details?id=pro.vitalii.andropods); [Exodus](https://reports.exodus-privacy.eu.org/en/reports/pro.vitalii.andropods/latest/)
  - MaterialPods: 1M+, 4.2★ (22.3K), ads + IAP, AdMob tracker (Exodus 2025-06-12). — [Play](https://play.google.com/store/apps/details?id=com.pryshedko.materialpods); [Exodus](https://reports.exodus-privacy.eu.org/en/reports/com.pryshedko.materialpods/latest/)
  - PodsLink: 1M+, 4.5★. — [Play](https://play.google.com/store/apps/details?id=net.podslink)
  - PodsBattery: 1M+, 4.5★, ads. — [Play](https://play.google.com/store/apps/details?id=com.yugongkeji.podstool)
  - BatteryPods: 1M+, 4.4★, ads. — [Play](https://play.google.com/store/apps/details?id=com.sumyapplications.bluetooth.earphone)
- **OpenPods**: GPL-3.0, 1,263 stars, last push 2025-09-26, on F-Droid. — [GitHub](https://github.com/adolfintel/OpenPods); [F-Droid](https://f-droid.org/packages/com.dosse.airpods/)

**Single-brand open-source clients**
- **GalaxyBudsClient / "BudsClient"**
  - GPL-3.0, 5,309 stars. Last release 5.2.1 on 2026-06-07 (21.7K asset downloads for that release). — [GitHub](https://github.com/timschneeb/GalaxyBudsClient)
  - Play listing title "BudsClient: Manage your Buds": **paid**, 1K+ installs, updated 2026-06-07. Supports Buds (2019) through Buds3 Pro. Firmware downgrade or custom firmware sideload, factory self-tests, hidden debug info, erasing SmartThings Find data. It "cannot connect … simultaneously" with Samsung's manager. Free on Windows/macOS/Linux. — [Play](https://play.google.com/store/apps/details?id=me.timschneeberger.galaxybudsclient)
- **BudBuddy** (newer Galaxy Buds manager): GPL-3.0, 22 stars, created 2026-08-02. Buds4 Pro full, Buds3 Pro partial. — [GitHub](https://github.com/BenEgeDeniz/BudBuddy); [XDA thread](https://xdaforums.com/t/app-bud-buddy-open-source-feature-rich-galaxy-buds-manager-for-all-android-devices.4797256/)
- **OpenSCQ30** (Soundcore)
  - GPL-3.0, 524 stars, v2.13.0 on 2026-10-17. — [GitHub](https://github.com/Oppzippy/OpenSCQ30)
  - Runs on Windows, Linux and Android. Distributed on GitHub, Flathub and IzzyOnDroid, not Play or F-Droid. 40 models. — [GitHub README](https://github.com/Oppzippy/OpenSCQ30)
- **OpenFreebuds** (Huawei): GPL-3.0, 460 stars, last push 2026-09-14. — [GitHub](https://github.com/melianmiko/OpenFreebuds)
- **FreeBuddy** (Huawei FreeBuds 4i only): Apache-2.0, 145 stars, last push 2025-11-03. Created because official apps are "bloated as hell". — [GitHub](https://github.com/TheLastGimbus/FreeBuddy)
- **QuickBuds** (OnePlus/OPPO/realme): a GPL-3.0 "HeyMelody alternative" offering battery, ANC, EQ, gestures, wear detection, find, dual connection, low latency and widgets. The repo URL returned 404 when re-checked via the API on 2026-10-20, so it may have moved. — [GitHub (search result)](https://github.com/Abhishek-banal/QuickBuds)
- **Sony**
  - mos9527/SonyHeadphonesClient: MIT, 340 stars, pushed 2026-10-20. — [GitHub](https://github.com/mos9527/SonyHeadphonesClient)
  - The original Plutoberth/SonyHeadphonesClient is archived (1,363 stars, MIT). — [GitHub](https://github.com/Plutoberth/SonyHeadphonesClient)
  - Android Authority covered open-source PC apps for Sony, including "Sony Device Center" for Windows/Linux. Only the search snippet was seen; the page returned 403. — [Android Authority](https://www.androidauthority.com/sony-open-source-headphone-app-3710109/)
- **Pixel Buds Pro 2**: tedsluis/opencontrolpixelbudspro2 is AGPL-3.0, 3 stars, created 2026-08-09, aimed at GrapheneOS without Play services. — [GitHub](https://github.com/tedsluis/opencontrolpixelbudspro2)
- **Bose quick-toggle**: the "Noise Cancelling" F-Droid app offers a QS tile that only turns ANC off on the NC 700 / QC35. — [F-Droid](https://f-droid.org/en/packages/dev.danjackson.noisecancel/)

### Inferences
- Only Earshot pairs a vendor driver with system-wide audio and phone-side features (call boost, mic check, assistant-gesture remapping, automations). Gadgetbridge has drivers but no EQ, codec or call features. Wavelet, Poweramp and Codec Changer have audio but no drivers. That combination is the moat, and it only holds if the "Any headset" tier (roadmap B1–B5) is genuinely good.
- **Gadgetbridge is the closest strategic rival:**
  - It is adding exactly Earshot's "quick-access" features (QS tiles, multipoint, find earbuds) across many brands, and ships monthly.
  - It already covers Bose NC 700 and QC35, the models Earshot lists as basic or UNSUPPORTED (roadmap E5). It does not cover QC Ultra, QC45 or QC Earbuds, so Earshot's Bose depth stays unique.
  - Gadgetbridge's absence from Play is Earshot's opening for mainstream users.
- **AirPods is crowded:**
  - LibrePods (30k stars, Play, €5 unlock, Android 16 QPR3+/17) and CAPod (500K+, F-Droid) already cover modes and battery. The closed-source battery apps together have 15M+ installs.
  - Earshot's AirPods value has to come from integration rather than parity: the same tile, widget, automations, Pro EQ and remapper as every other headset. It must also run alongside LibrePods/CAPod without fighting over the L2CAP channel, because only one app can hold it (inferred from the BMAP single-holder rule and BudsClient's "cannot connect simultaneously" note).
- Wavelet (5M+) shows real demand for per-headset AutoEq that loads on connect. Its weak spots are a closed source, premium gating, `DynamicsProcessing` session detection that varies by OEM, and conflicts with other EQ apps. Earshot's roadmap B3 targets the same thing, so Earshot should state clearly whether its Pro EQ conflicts with Wavelet/RootlessJamesDSP and how it attaches to audio sessions.
- Bluetooth Codec Changer (500K+, 4.6★) shows the codec niche is valuable and Play-viable. It already has per-app and per-device codec profiles, a latency test and an HCI inspector, so Earshot's codec panel is not unique on features. Its edge is no trackers, open source, and a panel next to the headset's own controls.

### Gaps
- Wavelet's Pro price and which features it gates could not be confirmed from the listing.
- AirBattery: no current Play listing was found with the search used, so no metrics.
- No 2024–2026 commercial "universal headphone app" with meaningful installs turned up. The Play search for "universal headphones app" returned only OEM apps and spam clones. This is a sign of absence, not proof.
- RootlessJamesDSP's Play rating showed "5.0" with no review count, likely too few ratings. Treat it as unreliable.
- QuickBuds' current repo location and star count are unknown.

## 2. What users dislike about OEM companion apps

### Takeaway
The recurring complaints:
- Apps that fail to see or connect to the headset.
- Online or account dependence: Bose needs internet to see devices offline according to a reviewer, Samsung needs an account, Bose activation needs internet or an account.
- Ads, AI bloat and slow startup (Soundcore).
- Broken multipoint switching and settings that won't stick (Sony, Bose).
- Excessive permissions and trackers: Bose has 8 trackers including Facebook and Segment; Nothing X requests 92 permissions; the Samsung Buds plugin requests 102.
- Neglected apps: Sennheiser 2.4★, last update May 2025.

Old-model abandonment is real for Bose SoundTouch (cloud ended May 2026), but Bose Connect is still being updated.

### Cited Findings

**Play ratings and update recency (scraped 2026-10-20)**

| App | Installs | Rating (reviews) | Last update |
|---|---|---|---|
| Bose | 10M+ | 3.5★ (187K) | 2026-10-06 |
| Bose Connect (legacy) | 10M+ | 4.2★ (266K) | 2026-10-12 |
| Sony Sound Connect | 10M+ | 4.4★ (425K) | 2026-09-29 |
| Anker Soundcore | 10M+ | 3.7★ (131K) | 2026-10-12 |
| JBL Headphones | 50M+ | 4.6★ (801K) | 2026-08-25 |
| Samsung "Galaxy Buds" plugin | 1B+ | **1.6★** (2.45K) | 2026-10-07 |
| Galaxy Wearable | 1B+ | 3.7★ (1.8M) | not recorded |
| Galaxy Buds Live Manager | 1M+ | 3.4★ (6.38K) | not recorded |
| Jabra Sound+ | 10M+ | 4.5★ (236K) | 2025-10-31 |
| Sennheiser Smart Control | 1M+ | **2.4★** (19.6K) | 2025-05-28 |
| Nothing X | 10M+ | 4.6★ (76.8K) | 2026-10-15 |
| Xiaomi Earbuds | 10M+ | 4.3★ (35.8K) | not recorded |
| Google Pixel Buds | 100M+ | **2.6★** (26.2K) | not recorded |
| HeyMelody | 10M+ | 3.9★ | not recorded |
| Marshall Bluetooth | 5M+ | 4.7★ | not recorded |

Play listings: [Bose](https://play.google.com/store/apps/details?id=com.bose.bosemusic), [Bose Connect](https://play.google.com/store/apps/details?id=com.bose.monet), [Sony](https://play.google.com/store/apps/details?id=com.sony.songpal.mdr), [Soundcore](https://play.google.com/store/apps/details?id=com.oceanwing.soundcore), [JBL](https://play.google.com/store/apps/details?id=jbl.stc.com), [Galaxy Buds](https://play.google.com/store/apps/details?id=com.samsung.accessory.budsunitemgr), [Galaxy Wearable](https://play.google.com/store/apps/details?id=com.samsung.android.app.watchmanager), [Galaxy Buds Live Manager](https://play.google.com/store/apps/details?id=com.samsung.accessory.neobeanmgr), [Jabra](https://play.google.com/store/apps/details?id=com.jabra.moments), [Sennheiser](https://play.google.com/store/apps/details?id=com.sennheiser.control), [Nothing X](https://play.google.com/store/apps/details?id=com.nothing.smartcenter), [Xiaomi Earbuds](https://play.google.com/store/apps/details?id=com.mi.earphone), [Pixel Buds](https://play.google.com/store/apps/details?id=com.google.android.apps.wearables.maestro.companion), [HeyMelody](https://play.google.com/store/apps/details?id=com.heytap.headset), [Marshall](https://play.google.com/store/apps/details?id=com.zoundindustries.marshallbt)

**Trackers and permissions (Exodus Privacy, latest report per app)**

| App | Version | Report date | Trackers | Permissions |
|---|---|---|---|---|
| Bose | 13.2.7 | 2026-09-26 | **8**: Facebook Analytics, Facebook Login, Facebook Share, Crashlytics, Firebase Analytics, SAP CDC (Gigya), Segment, Urban Airship | 29 |
| JBL Headphones | 5.37.10 | 2026-09-28 | 5: Amazon Analytics, Google Analytics, Crashlytics, Firebase Analytics, Google Tag Manager | 25 |
| Nothing X | 3.9.0 | 2026-10-11 | 4: Baidu Map, Crashlytics, Firebase Analytics, MixPanel | **92** |
| Sennheiser Smart Control | 4.9.3 | 2025-06-13 | 4: Crashlytics, Firebase Analytics, Mapbox, Qualtrics | 22 |
| Xiaomi Earbuds | 1.38.0i | 2026-09-21 | 3: Facebook Login, Crashlytics, Firebase Analytics | 35 |
| Sony Sound Connect | 13.2.2 | 2026-09-07 | 2: Crashlytics, Firebase Analytics | 27 |
| Jabra Sound+ | not recorded | 2025-11-21 | 2 | 31 |
| Soundcore | 5.0.32 | 2026-10-20 | 0 | 43 |
| Samsung Galaxy Buds plugin | 9.0.10 | 2026-10-09 | 0 | **102** |

Exodus reports: [Bose](https://reports.exodus-privacy.eu.org/en/reports/com.bose.bosemusic/latest/), [JBL](https://reports.exodus-privacy.eu.org/en/reports/jbl.stc.com/latest/), [Nothing X](https://reports.exodus-privacy.eu.org/en/reports/com.nothing.smartcenter/latest/), [Sennheiser](https://reports.exodus-privacy.eu.org/en/reports/com.sennheiser.control/latest/), [Xiaomi](https://reports.exodus-privacy.eu.org/en/reports/com.mi.earphone/latest/), [Sony](https://reports.exodus-privacy.eu.org/en/reports/com.sony.songpal.mdr/latest/), [Jabra](https://reports.exodus-privacy.eu.org/en/reports/com.jabra.moments/latest/), [Soundcore](https://reports.exodus-privacy.eu.org/en/reports/com.oceanwing.soundcore/latest/), [Samsung](https://reports.exodus-privacy.eu.org/en/reports/com.samsung.accessory.budsunitemgr/latest/)

**Mozilla Privacy Not Included** (2023-11-01): Bose 700 and QC Earbuds rated "somewhat creepy". The main concern is that Bose may use or share de-identified data "for any purpose", and such data can be re-identified. — [Mozilla: Bose 700](https://www.mozillafoundation.org/en/privacynotincluded/bose-700-wireless-headphones/); [Mozilla: QC Earbuds](https://www.mozillafoundation.org/en/privacynotincluded/bose-quietcomfort-earbuds/)

**Online and account dependence**
- Bose's own support page: "Product Activation Failed" appears when the product can't reach the internet or the Bose account. — [Bose support HC2602](https://www.bose.co.uk/en_gb/support/articles/HC2602/productCodes/bose_frames_tenor/article.html)
- Bose Play review sample: "when not connected to the Internet it can't see your devices, adjust settings", a problem for headphones used "on a plane". — [Bose Play](https://play.google.com/store/apps/details?id=com.bose.bosemusic)
- Samsung Galaxy Buds plugin review sample: needing "3 more apps to make this app run" and having to "create Samsung account to use my ear buds". Other reviewers say it is unusable on a Pixel 8 Pro. — [Galaxy Buds Play](https://play.google.com/store/apps/details?id=com.samsung.accessory.budsunitemgr)
- Sennheiser: forum replies say the app needs an account, and location only for an opt-in location-based preset feature. — [Lemmy thread](https://lemmy.4rs.nl/post/3175/10540)
- Soundcore explains its location prompt as an Android BLE-scanning requirement that users may decline. — [Soundcore support](https://service.soundcore.com/article-description/Why-the-Soundcore-app-asks-for-location-access)

**Ads, AI and bloat (Soundcore)**
- A "5-second unskippable advert" appears after relaunch and delays ANC control (eufy community, 2025-10-23, no official reply). — [eufy community](https://community.eufy.com/t/stop-forcing-un/5758781)
- Play review samples: "AI slop imagery" and an AI assistant "front and center"; the app "takes up too much space because of its AI"; "takes forever to load … will randomly flash you with ads". — [Soundcore Play](https://play.google.com/store/apps/details?id=com.oceanwing.soundcore)
- The profile login is unclear in value and doesn't support Google/Facebook login in the app. — [Soundcore community](https://community.soundcore.com/t/soundcore-app-profile-log-in-vs-web-log-in-and-is-there-a-benefit-to-logging-into-it/150606)

**Reliability, multipoint and settings that don't stick**
- Bose review samples:
  - The "remember my settings" sound-mode option "has never worked".
  - "Majority of time, my Bose app wont even connect … locking me out" of the headphones.
  - [Bose Play](https://play.google.com/store/apps/details?id=com.bose.bosemusic)
- Bose Connect review samples:
  - "Find My Buds" often says "can't play sound".
  - Multipoint switching is "whack-a-mole".
  - [Bose Connect Play](https://play.google.com/store/apps/details?id=com.bose.monet)
- Sony review samples:
  - The 2026-07-16 update caused a "continuous initialization loop".
  - A firmware update stopped standby in the case.
  - Multipoint "will randomly prioritize one playing no sound".
  - [Sony Play](https://play.google.com/store/apps/details?id=com.sony.songpal.mdr)
- Sony forum: a WH-1000XM6 owner calls the app quite buggy when switching devices. — [Sony community](https://community.sony.dk/t5/portable-audio/sound-connect-changing-the-connected-device-a-lot/td-p/3981428)
- An aggregator says speak-to-chat re-enables itself. This is a machine-generated summary, so low reliability. — [marlvel.ai](https://marlvel.ai/apps/sony-sound-connect/reviews)
- JBL review samples: the app "sometimes … doesn't recognize them at all"; Find My Buds doesn't work in the case. — [JBL Play](https://play.google.com/store/apps/details?id=jbl.stc.com)
- Sennheiser review samples:
  - With the HD 450BT, "no settings available, no equalizer, nothing".
  - Requests auto-off "only if there's no music playing".
  - Requests an alert when one of two earbuds isn't charging.
  - [Sennheiser Play](https://play.google.com/store/apps/details?id=com.sennheiser.control)
- Pixel Buds review sample: find-my-device should ring the buds when they are not connected. — [Pixel Buds Play](https://play.google.com/store/apps/details?id=com.google.android.apps.wearables.maestro.companion)

**App rename, migration and end of life**
- Sony renamed Headphones Connect to Sound Connect and added Find My Device network support for some models. — [hifi.de](https://hifi.de/news/sony-headphones-connect-wird-zu-sound-connect-und-erhaelt-neue-features-207366)
- Sony ended Sound Connect support for Android 9 in April 2025 (NW-A100/ZX500 players). Existing features keep working but there are no new versions. — [Sony UK support 00348311](https://www.sony.co.uk/electronics/support/articles/00348311)
- No official list of headphones dropped by Sound Connect was found. One forum post says the MDR-1000X doesn't work with it. — [Sony community](https://community.sony.cz/t5/portable-audio/mdr-1000x/td-p/3665568)
- Bose SoundTouch: cloud support ended on 2026-05-06, extended from 2026-02-18. Presets no longer work. Bose published the SoundTouch API so independent developers can keep the speakers usable. — [Bose SoundTouch EOL](https://bose.com/soundtouch-end-of-life); [Engadget](https://www.engadget.com/audio/speakers/bose-made-the-consumer-friendly-move-to-open-source-its-soundtouch-speakers-163459024.html)
- Bose Connect (QC35-era app) is still listed and was updated on 2026-10-12, so it has not been shut down. — [Bose Connect Play](https://play.google.com/store/apps/details?id=com.bose.monet)

### Inferences
- Earshot's existing pitch already answers the three most-cited OEM pains: no account, no internet permission, and connected in about a second. It should be made concrete with comparisons people can check:
  - "0 trackers, no INTERNET permission" against Bose's 8 trackers (Exodus) and Nothing X's 92 permissions.
  - "Works on a plane" in the store listing.
- Earshot's own review-friendly fixes map directly onto the complaints:
  - Settings that stick: modes stored by meaning (roadmap A7) and automations.
  - Multipoint and device switching (Bose has it today).
  - Push-based live state, rather than "can't see your device".
- The Sennheiser and Pixel Buds requests can be met cheaply with Earshot's automation engine:
  - Auto-off only when idle.
  - An alert when one bud isn't charging, which needs per-bud battery data.
  - Ring the buds.
- The SoundTouch shutdown and Sony's Android 9 cutoff support an "Earshot keeps your headphones working when the maker's app moves on" message. However, Bose Connect is still alive, so "abandoned app" claims about Bose headphones would be inaccurate today.

### Gaps
- No quantified review-theme analysis (share of 1★ reviews per theme) was available. All review evidence here is the handful of top reviews shown on the listing.
- No Mozilla Privacy Not Included entry for Sony, Soundcore or JBL headphones was found in this pass.
- Whether the current Bose app works fully offline without signing in is not confirmed officially. One reviewer reports it does if you skip sign-in.
- Reddit threads (r/headphones, r/bose, r/sony, r/androidapps) could not be retrieved directly. The search tools returned no Reddit pages for these queries, so Reddit sentiment is missing.

## 3. Most-requested features, and Earshot's coverage

### Takeaway
The clearest demand signals are:
1. Support for *my* model (device requests dominate the issue trackers).
2. Quick access to modes and battery (QS tiles, widgets, notification).
3. Find or ring my buds.
4. Per-headset EQ/AutoEq that loads on connect.
5. Reliable multipoint switching.
6. Ear-detection auto-pause (AirPods).
7. Codec control.
8. Store and install choices (F-Droid, non-Play).

Earshot already has or plans most of these. The clear gaps are **find/ring buds** (only via Fast Pair in 0.7 E4), **a device-request intake** (F3 device report helps), **firmware updates** (deliberately blocked), and **per-bud charging alerts**.

### Cited Findings
- Issue-tracker demand (open issues sorted by 👍, 2026-10-20):
  - CAPod's top three: "Request for official connection method" (7), "Add the ability to find the Airpods and its case" (5), "Show real-time environment decibel level" (5). Also requested: Loud Sound Reduction, spatial audio, head tracking, "Don't connect to AirPods when not in ear". — [CAPod issues](https://github.com/d4rken-org/capod/issues)
  - LibrePods: "android: todo list" (14), "[Request] Add the project to a f-droid repo" (6, open since 2025-05-17), "Use a different application ID for FOSS builds" (2026-08-29), "display firmware version". — [LibrePods issues](https://github.com/librepods-org/librepods/issues)
  - OpenSCQ30: the top issues are device requests (Space 2, Sleep A20, Motion X600, Liberty 5 Pro, AeroFit 2 Pro, A3i), plus "feature: find my device". — [OpenSCQ30 issues](https://github.com/Oppzippy/OpenSCQ30/issues)
  - GalaxyBudsClient: "[REQ] Better noise control / ANC / ambient switching", Buds3 support, "[REQ] Alternative installation methods for the android app" (14 comments), "High power consumption while in background". — [GalaxyBudsClient issues](https://github.com/timschneeb/GalaxyBudsClient/issues)
- What competitors ship tells you what users value:
  - Gadgetbridge added QS tiles for Sony, Shokz, Bose, Nothing, Anker, Oppo/Realme and Redmi between Aug and Oct 2026, plus multipoint and "find individual earbuds". — [Gadgetbridge releases](https://codeberg.org/Freeyourgadget/Gadgetbridge/releases)
  - LibrePods advertises modes "from the app, Quick Settings or widget". — [LibrePods Play](https://play.google.com/store/apps/details?id=me.kavishdevar.librepods)
  - Wavelet advertises the auto-loaded per-headphone profile. — [Wavelet Play](https://play.google.com/store/apps/details?id=com.pittvandewitt.wavelet)
  - Codec Changer advertises per-device and per-app codec profiles and auto-apply on connect. — [Codec Changer Play](https://play.google.com/store/apps/details?id=com.amrg.bluetooth_codec_converter)
- Paid tiers point to features people pay for:
  - AndroPods puts ear detection and assistant tap behind Pro. — [AndroPods Play](https://play.google.com/store/apps/details?id=pro.vitalii.andropods)
  - LibrePods charges €5 for head gestures and assistant activation. — [heise](https://heise.de/-11275308)
  - GalaxyBudsClient charges on Play and highlights firmware downgrade or sideload. — [BudsClient Play](https://play.google.com/store/apps/details?id=me.timschneeberger.galaxybudsclient)
- OEM review requests: find buds in the case (JBL); ring when not connected (Pixel Buds); auto-off only when idle and a one-bud-not-charging alert (Sennheiser); offline use (Bose); settings that stick (Bose). — [JBL](https://play.google.com/store/apps/details?id=jbl.stc.com); [Pixel Buds](https://play.google.com/store/apps/details?id=com.google.android.apps.wearables.maestro.companion); [Sennheiser](https://play.google.com/store/apps/details?id=com.sennheiser.control); [Bose](https://play.google.com/store/apps/details?id=com.bose.bosemusic)
- Firmware: Gadgetbridge lists "firmware updates" as missing on several Sony models, and AirPods firmware updates still need an Apple device. These are the main reasons users keep the OEM app installed. — [Gadgetbridge Sony](https://gadgetbridge.org/gadgets/headphones/sony/); [borncity](https://borncity.com/news/librepods-app-oeffnet-airpods-4-fuer-android-nutzer/)
- Earshot today (README): no account, no INTERNET permission, Pro EQ with AutoEq import, codec panel (AAC/SBC switching via Shizuku), call boost, assistant-gesture remapper with "Detect a button", QS tile, widget, persistent notification with mode buttons, automations (Wi-Fi, foreground app, schedule, on connect), low-battery alert, per-app EQ, multipoint/device switching for Bose. The roadmap adds battery for any headset (B1), codec readout without Shizuku (B2), per-headset EQ with an offline AutoEq index (B3), AirPods (C1–C3), Fast Pair ring/battery (E4) and device reports (F3). — [Earshot README](../../../../README.md); [Earshot roadmap](../../../roadmap.md)

**Feature-by-feature comparison**

| Feature (evidence of demand) | Earshot status |
|---|---|
| Battery for any headset in widget/notification (8+ AirPods battery apps with 15M+ installs between them; QS/widget pushes) | Bose today; any headset in roadmap B1, AirPods C1 |
| ANC/mode toggle from tile, notification or widget (Gadgetbridge, LibrePods) | **Has** (Bose); needs drivers for other brands |
| Per-headset EQ + AutoEq auto-loaded on connect (Wavelet 5M+, Poweramp EQ 5M+) | Pro EQ + AutoEq import **has**; per-headset auto-apply + offline index = B3 |
| Codec forcing, per-device profiles (Codec Changer 500K+) | AAC/SBC via Shizuku **has**; readout without Shizuku = B2. No per-app codec profile or latency test |
| Button remapping (AndroPods Pro, LibrePods paid) | **Has** (Controls, any headset via assistant gesture) |
| Multipoint management (Sony/Bose complaints; Gadgetbridge adding it) | **Has** for Bose; per driver later |
| Find / ring buds (CAPod, OpenSCQ30, JBL, Pixel reviews) | **Missing** except the Fast Pair ring (E4, conditional on D5) |
| Ear-detection auto-pause (AirPods apps' core feature) | Bose auto-pause exists; AirPods in C2 (Android 17 gate) |
| Firmware update without account (main reason the OEM app stays installed) | **Out of scope by design** (WriteGuard blocks it; no internet) |
| Offline / no account (Bose, Samsung complaints) | **Has**; core differentiator |
| Device requests / support for *my* model (dominant issue type) | Device report = F3; no intake flow in the app yet |
| F-Droid / non-Play install (LibrePods, BudsClient issues) | Planned at 1.0 |

### Inferences
- **Unique, defensible combination:**
  - Call audio boost, mic check, and assistant-gesture remapping that work on *any* headset. No competitor found offers these across brands.
  - Automations keyed on Wi-Fi, foreground app or schedule. Only Codec Changer has comparable "automation hooks", and only for codecs.
  - Lead with these in the store copy.
- **Highest-value additions not yet firmly on the roadmap:**
  1. **Find/ring.** Play a loud tone through the connected headset; Android can route audio to it with no vendor protocol. Also show "last seen" from Earshot's own connect/disconnect log, offline and without location history beyond what the user opts into. That gives Earshot a cross-brand "find" without Fast Pair.
  2. **One-bud-not-charging and idle auto-off automations** wherever per-bud battery or play state is known.
  3. **In-app "request my headset" flow** that produces the F3 report. Device requests dominate every open-source tracker.
- Ear detection and head gestures are where AirPods users pay. If C2 slips because of the Android 17 L2CAP gate, Earshot's AirPods tier should be positioned as "battery + Earshot's any-headset features" and coexist with LibrePods, not compete with it.

### Gaps
- No survey or quantified ranking of feature requests across brands exists in public sources. The ranking above is synthesised from issue upvotes (small numbers, under 15 👍), product feature lists and sample reviews.
- No usage data on how many users actually use QS tiles or widgets.

## 4. Distribution lessons from comparable open-source apps

### Takeaway
Comparable open-source apps follow three patterns:
- **Play + F-Droid/IzzyOnDroid + GitHub, with a paid unlock on Play** (CAPod, LibrePods).
- **F-Droid only, by principle** (Gadgetbridge).
- **Play-only paid with free desktop** (BudsClient, which users push back on).

The lessons for Earshot:
- Keep brand names out of the app name (BudsClient, not "Galaxy Buds Client").
- Exclude logos and store assets from the GPL/MIT grant (CAPod).
- Use a separate application ID for FOSS builds (LibrePods issue).
- Get onto F-Droid/IzzyOnDroid early (an open LibrePods request).
- Plan for Google's developer verification: enforcement in 4 countries since 2026-09-30, global in 2027.

### Cited Findings
- **Naming and trademark**
  - Tim Schneeberger's app lists on Play as "BudsClient: Manage your Buds" while the repo stays "GalaxyBudsClient". The README says it "is not affiliated with Samsung". — [Play](https://play.google.com/store/apps/details?id=me.timschneeberger.galaxybudsclient); [GitHub](https://github.com/timschneeb/GalaxyBudsClient)
  - LibrePods states the GPL grants no rights to the LibrePods name or logo, disclaims affiliation with Apple, plans to replace Apple's SF Pro font, and warns that "librepods.org" is an impersonating site. — [LibrePods README](https://github.com/librepods-org/librepods)
  - Gadgetbridge renamed its Play variant "Bangle.js Gadgetbridge". — [Reddit mirror](https://red.127.pp.ua/r/Gadgetbridge/comments/1ioxzx7/why_not_on_google_play)
- **Licence carve-outs**: CAPod's GPL-3.0 excludes icons, logos, mascots, marketing material, Play screenshots and descriptions, and translations. — [CAPod README](https://github.com/d4rken-org/capod)
- **Channels**
  - CAPod: Play (stable and beta), GitHub, F-Droid and IzzyOnDroid. — [CAPod README](https://github.com/d4rken-org/capod)
  - OpenSCQ30: GitHub (GPG-signed), Flathub and IzzyOnDroid, no Play. — [OpenSCQ30 README](https://github.com/Oppzippy/OpenSCQ30)
  - RootlessJamesDSP: on Play and F-Droid, but the Play build was last updated 2024-12-14 while F-Droid got 1.6.14 in 2025-05. — [Play](https://play.google.com/store/apps/details?id=me.timschneeberger.rootlessjamesdsp); [F-Droid](https://f-droid.org/en/packages/me.timschneeberger.rootlessjamesdsp/)
  - Gadgetbridge: F-Droid only. — [Codeberg #3304](https://codeberg.org/Freeyourgadget/Gadgetbridge/issues/3304)
- **Same application ID for Play and FOSS builds causes problems**: a LibrePods user installed the GitHub FOSS APK and Play then offered to "update" it to the Play version. The suggested fix is a `.foss` application ID suffix. — [LibrePods issues](https://github.com/librepods-org/librepods/issues)
- **Paid-on-Play-only creates friction**: GalaxyBudsClient users opened "[REQ] Alternative installation methods for the android app" (14 comments). — [GalaxyBudsClient issues](https://github.com/timschneeb/GalaxyBudsClient/issues)
- **Monetisation models found**
  - LibrePods: €5 one-time unlock plus GitHub Sponsors. — [heise](https://heise.de/-11275308); [GitHub](https://github.com/librepods-org/librepods)
  - CAPod: Pro IAP plus GitHub Sponsors, ad-free. — [CAPod README](https://github.com/d4rken-org/capod)
  - BudsClient: paid on Play, Ko-fi and GitHub Sponsors. — [GitHub](https://github.com/timschneeb/GalaxyBudsClient)
  - Wavelet and Poweramp EQ: closed source with IAP, no ads. — [Wavelet Play](https://play.google.com/store/apps/details?id=com.pittvandewitt.wavelet); [Poweramp EQ Play](https://play.google.com/store/apps/details?id=com.maxmpz.equalizer)
  - MaterialPods and other AirPods battery apps: ads (AdMob) plus IAP. — [MaterialPods Play](https://play.google.com/store/apps/details?id=com.pryshedko.materialpods)
- **Privacy as a selling point**: Wavelet, CAPod, Poweramp EQ and Soundcore all show 0 trackers on Exodus. Bose shows 8. — Exodus links in section 2 and [Wavelet](https://reports.exodus-privacy.eu.org/en/reports/com.pittvandewitt.wavelet/latest/), [CAPod](https://reports.exodus-privacy.eu.org/en/reports/eu.darken.capod/latest/)
- **Shizuku as the no-root path**: RootlessJamesDSP requires Shizuku (Android 11+) or ADB. — [F-Droid](https://f-droid.org/en/packages/me.timschneeberger.rootlessjamesdsp/)
  - LibrePods Play review sample: "Fold 8 w/ Shizuku". — [LibrePods Play](https://play.google.com/store/apps/details?id=me.kavishdevar.librepods)
  - Shizuku itself: Apache-2.0, 31,878 stars, last push 2025-06-18. — [GitHub RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku)
- **Developer verification**
  - Enforcement began 2026-09-30 in Brazil, Indonesia, Singapore and Thailand; the global rollout is planned for 2027. It covers apps distributed outside Play. Secondary sources only, no Google post seen. — [pasqualepillitteri.it](https://pasqualepillitteri.it/en/news/12067/keep-android-open-google-developer-verification-sideloading); [AlternativeTo](https://alternativeto.net/news/2025/11/google-rolls-out-developer-verification-with-an-option-for-experienced-users-to-bypass-it)
  - F-Droid shipped client 2.0 on 2026-09-24 and calls verification an existential threat. — [Pinggy blog](https://pinggy.io/blog/f_droid_2_0_android_developer_verification/)
- **Accessibility-service policy**: the only enforcement found in this pass is Google's 2017 crackdown on non-accessibility uses (LastPass, Tasker and others named), which was then paused. — [Android Police](https://www.androidpolice.com/2017/12/07/google-pauses-accessibility-app-ban-considers-responsible-innovative-uses-accessibility-services/?amp)

### Inferences
- **Name and identity**
  - Keep "Earshot" as the only name.
  - Use brand names only descriptively ("for Bose QuietComfort…"), with a not-affiliated line, as the README already does.
  - Avoid brand logos or imagery in store screenshots (roadmap 0.8 already says so).
  - Exclude Earshot's own icon and store assets from the MIT grant, as CAPod does, to protect the identity.
- **Build flavours**: ship two application IDs, `dev.earshot` (Play) and `dev.earshot.foss` or similar (F-Droid/GitHub), so channels don't cross-update. This also fits the roadmap's possible "full" flavour with Shizuku features.
- **Monetisation**: an optional supporter unlock or donation fits the category norm (CAPod, LibrePods). Gating core control behind payment would undercut the "anti-OEM-app" pitch. Ads would contradict the no-INTERNET promise.
- **Channel timing**: getting onto IzzyOnDroid early (it accepts GitHub-built APKs) would capture the privacy audience that Gadgetbridge serves and that LibrePods users are asking for.
- **Remapper stays clean**: Earshot's remapper uses the assistant gesture rather than an AccessibilityService, so it avoids the accessibility-policy risk entirely. Keep it that way.

### Gaps
- Current (2025–2026) Play enforcement around Shizuku-dependent features, `connectedDevice` foreground-service declarations, and `neverForLocation` BLE scanning for headphone apps was not verified. No primary source was found in this pass. Check Play Console policy pages directly before 0.8.
- The reason for the "BudsClient" Play name (trademark complaint or pre-emptive) is not documented publicly. The finding is the name itself.
- No IzzyOnDroid inclusion-policy details were collected.
- Install counts for the F-Droid/IzzyOnDroid channels are not published, so channel mix can't be quantified.
