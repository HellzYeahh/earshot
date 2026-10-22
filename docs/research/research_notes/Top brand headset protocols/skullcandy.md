# Skullcandy ANC headphones and earbuds (2020 onward): control stacks and Earshot coverage

How the notes were made: static reading of the official **Skullcandy app 5.0.6** (`app.skullcandy`,
pulled from the user's phone; Flutter UI with native Android plugins), plus public sources. Nothing was
installed or run and no Bluetooth device was contacted. The research was deliberately narrow. It
covers model names, product IDs, which stack each model uses and the feature list per model.
Authentication, pairing, secure-session and crypto mechanics, field-level message layouts and anything
security-related were **not** researched and are not described here.

Where the app facts come from:

- **Manifest**: `assets/flutter_assets/assets/products/product-manifest.json` (56 entries, Applanga
  date 2026-10-08).
- **Bragi config**: the Bragi SDK's device-configuration class `com.bragi.sdk.android.device.config.c`
  and its helper class `…config.b`.
- **Dart**: the Flutter source-file list and the string pool of `libapp.so`.
- **Vendor SDK packages** bundled in the APK: `com.bragi.*`, `com.airoha.*`, `com.jieli.*`,
  `com.qualcomm.qti.gaiaclient`, `com.audiodo.apsctrl`, and `app.skullcandy.bes|actions|jieli`.

Licences:

- The app and the vendor SDKs inside it are proprietary, so this file records facts only, in my own
  words. No code is quoted.
- The Bragi `.proto` files in the app carry a Bragi copyright notice. Only service names, and a few
  method names needed for a block list, appear here, at the same level as `docs/crusher-1080.md`.
- Press and review articles are copyrighted; facts only.
- Gadgetbridge is AGPL; only its device list was checked, and no code was used.

Citation convention: "APK" links go to the app's store page. The link text says which asset or class
inside the APK holds the fact.

Stack names used below:

- **BOS-RPC**: Bragi protobuf RPC in PPP frames. This is Earshot's existing Crusher 1080 stack.
- **BOS-AT**: the Bragi "AT command" feature service of the first Skull-iQ generation.
- **RACE**: Airoha's RACE protocol. **RACE-custom** is Airoha RACE with Skullcandy-specific
  commands.
- **TOTA**: BES (Bestechnic) TOTA protocol.
- **Jieli**, **Actions**, **GAIA** (Qualcomm), and **legacy BLE** (the 2019–2020 BLE models with
  Tile).

## 1. Which Skullcandy ANC models (2020 onward) exist, when, and in what form

### Takeaway
There are fourteen ANC products, released between late 2020 and 2026: six over-ear or on-ear
headphones and eight true-wireless earbuds. Thirteen appear in the app's manifest (Hesh ANC never had
app control), and two of those thirteen (Crusher 900 ANC, Dime 540 ANC) appear in no public
announcement I could find. The Sound by Bose line (Method 360 ANC 2025, Method 540 ANC 2026,
Crusher 1080 ANC 2026) is the newest and most premium.

### Cited Findings
| Model | Form | Public launch | Manifest `productId` / `modelId` | Sources |
|---|---|---|---|---|
| Hesh ANC | over-ear, folding | SoundGuys gives a 15 Dec 2020 release; Japan 25 Feb 2021 | **not in the manifest** | [SoundGuys](https://soundguys.com/skullcandy-hesh-anc-review-76453), [Pickr](https://www.pickr.com.au/news/2021/skullcandy-adds-noise-cancelling-to-trackable-indy-hesh-headphones/), [phileweb](https://www.phileweb.com/sp/news/d-av/202102/10/52082.html) |
| Indy ANC | TWS | already on sale in the US at the UK launch; UK 28 Jan 2021 | 0x07 / `S2IYW` | [What Hi-Fi](https://www.whathifi.com/news/skullcandy-indy-anc-pound99-noise-cancelling-buds-with-tile-tracking), [T3](https://t3.com/news/apple-airpods-face-cheap-rival-in-skullcandys-new-noise-canceling-earbuds), [APK: manifest](https://play.google.com/store/apps/details?id=app.skullcandy) |
| Sesh ANC | TWS | Aug or Sep 2022 (retailer dates differ) | 0x0C / `S2TEW` | [HMV](https://hmv.com/store/technology/headphones/skullcandy-sesh-anc-black-true-wireless-active-noi), [Galaxus](https://galaxus.de/en/s1/product/skullcandy-sesh-anc-10-h-wireless-headphones-11842977), [APK: manifest](https://play.google.com/store/apps/details?id=app.skullcandy) |
| Crusher ANC 2 | over-ear | announced 16 May 2023, $229.99 | 0x16 / `S6CAW` | [Shop-Eat-Surf (press release)](https://shop-eat-surf-outdoor.com/2023/05/skullcandy-unveils-the-crusher-anc-2-headphones/), [audioXpress](https://audioxpress.com/news/skullcandy-unveils-crusher-anc-2-headphones-combining-haptics-with-active-noise-canceling) |
| Rail ANC | TWS | Oct 2023 (UK 19 Oct, Japan 20 Oct) | 0x15 / `S2IPW` | [phileweb](https://www.phileweb.com/sp/news/d-av/202310/13/59092.html), [HMV](https://hmv.com/store/technology/headphones/skullcandy-rail-true-black-anc-true-wireless-bluet) |
| Push ANC Active | TWS, ear hooks | June or July 2024 ("Active Collection"), $99.99 | 0x26 / `S2PPW`, ODM code `HANC` | [Mimi](https://mimi.io/blog/skullcandy-launches-two-new-active-earbuds-featuring-mimi-sound-personalization), [SoundGuys](https://soundguys.com/skullcandy-active-collection-earbuds-117925) |
| Sesh ANC Active | TWS | June or July 2024, $89.99 | 0x25 / `S2SAW`, `TANC` | same as above |
| Icon ANC | on-ear | 1 Oct 2024, $99.99 | 0x1A / `S51OW`, `HI99` | [press release PDF](https://cdn.shopify.com/s/files/1/0635/5114/3993/files/ICON-ANC_PRES-RELEASE.pdf), [TechBuzz Ireland](https://techbuzzireland.com/2024/10/01/skullcandy-revives-icon-franchise-with-all-new-icon-anc-headphones/) |
| Method 360 ANC | TWS, Sound by Bose | 15 Apr 2025, $129.99 | 0x1D / `S2MTW` | [audioXpress](https://audioxpress.com/news/skullcandy-unveils-new-method-360-anc-earbuds-powered-by-bose), [Skullcandy blog](https://www.skullcandy.com/blogs/the-feed/skullcandy-just-dropped-the-method-360-anc-featuring-sound-by-bose) |
| Aviator 900 ANC | over-ear | June 2025, $299.99 | 0x06 / `S6AVW`, `AVANC` | [AV NIRVANA](https://www.avnirvana.com/threads/aviator-900-anc-takes-off-skullcandy%E2%80%99s-latest-marries-style-comfort-and-control.15176/) |
| Hesh 540 ANC | over-ear | July 2025, $149.99 | 0x1F / `S6HAW`, `HA2` | [Notebookcheck](https://www.notebookcheck.net/New-Skullcandy-Hesh-540-ANC-headphones-deliver-3D-sound-and-65-hours-of-battery-life.1052973.0.html) |
| Method 540 ANC | TWS, Sound by Bose | CES, 8 Jan 2026, $149.99 | 0x2D / `S2MFW` | [SoundGuys](https://www.soundguys.com/skullcandy-method-540-anc-gets-skull-ai-151116/), [product page](https://www.skullcandy.com/products/method-540-anc-noise-canceling-earbuds) |
| Crusher 1080 ANC | over-ear, Sound by Bose | 15 Jul 2026, $279.99 | 0x34 / `S6CNW` | [SoundGuys](https://www.soundguys.com/skullcandy-crusher-1080-anc-launch-160164/), [Digital Trends](https://www.digitaltrends.com/home-theater/skullcandy-serves-bose-tuning-and-some-peppy-colors-on-its-new-crusher-1080-anc-headphones/) |
| Crusher 900 ANC | over-ear (presumed) | no announcement found | 0x44 / empty `modelId`; firmware file code `CHP250` | [APK: manifest](https://play.google.com/store/apps/details?id=app.skullcandy) |
| Dime 540 ANC | TWS (presumed) | no announcement found | 0x45 / `modelId` "Dime 540 ANC" | [APK: manifest](https://play.google.com/store/apps/details?id=app.skullcandy) |

- Reviewers said the Hesh ANC had no companion app and no adjustable EQ, only Tile finding. That fits
  its absence from the manifest. One reviewer said the Skullcandy app "didn't want to work" with it.
  — [EFTM](https://eftm.com/2021/03/review-skullcandy-hesh-anc-affordable-noise-cancelling-for-everyone-207264), [T3 review](https://www.t3.com/reviews/skullcandy-hesh-anc-review), [Pickr review](https://www.pickr.com.au/reviews/2022/skullcandy-hesh-anc-headphones/)
- The 2019 Crusher ANC (`S6CPW`, productId 0x01) is still in the manifest but is out of scope.
  Its first firmware entry is dated 2019-08-02. — [APK: manifest](https://play.google.com/store/apps/details?id=app.skullcandy)
- Non-ANC models from 2020 onward that the app also controls, for context:
  - Crusher Evo, Indy Evo, Indy Fuel and Push Ultra (2020).
  - Grind, Grind Fuel and Push Active (the first Skull-iQ products).
  - Rail, Mod, Dime Evo, Dime 360, Hesh 360, Crusher 720, Crusher 540 Active.
  - Push 720/540/180 Open, Icon 180, Smokin' Buds 360/360H/360S.
  - Gaming: PLYR, SLYR Pro, Crusher PLYR 720.
  - Speakers: Session, Terrain.

  The manifest's `unsupportedProducts` list names Dime, Dime 2, Jib True / RC / XT 2, Riff Wireless,
  Sesh Evo and MOD 180. — [APK: manifest](https://play.google.com/store/apps/details?id=app.skullcandy)
- Skull-iQ launched in June 2021 with Grind Fuel and Push Active, built on Bragi's licensed software
  platform. — [audioXpress](https://audioxpress.com/news/skullcandy-confirms-partnership-with-bragi-for-software-upgradable-true-wireless-earbuds), [Wearable Technologies](https://wearable-technologies.com/news/skullcandy-partners-with-bragi-to-incorporate-voice-interface-and-artificial-intelligence-into-its-products)

### Inferences
- Crusher 900 ANC and Dime 540 ANC look like pre-announced entries. Crusher 900's only firmware
  entry has a placeholder-like version (`v26.37.32`), and Dime 540's has `v0.0.1` with no download.
  They should be treated as unreleased until a public launch appears.
- Crusher 900's manifest entry has no ANC feature flag despite the name, which also suggests the
  entry is incomplete.
- Hesh ANC can only ever be a GENERIC device in Earshot: audio link plus Android battery.

### Gaps
- No official Skullcandy press release was found for Sesh ANC (2022), so its date comes from
  retailers. Indy ANC's first US on-sale date (late 2020 or Jan 2021) is unconfirmed.
- No public information was found on Crusher 900 ANC or Dime 540 ANC.

## 2. Which chip and control stack each model uses, and the evidence

### Takeaway
Skullcandy uses at least five control stacks.

- **Bragi BOS-RPC** on Airoha AB158x/AB1595: the three Sound by Bose models. This is Earshot's stack.
- **Bragi BOS-AT plus Airoha RACE** on AB1562/1565/1568: the Skull-iQ generation (Crusher ANC 2,
  Rail ANC, plus Grind/Push Active).
- **Airoha RACE / RACE-custom without Bragi**: very likely the 2022–2025 non-Bragi ANC models.
- **BES TOTA**: probably Dime 540 ANC and the open-ear Push models.
- **Jieli, Actions and Qualcomm GAIA**: speakers and gaming headsets, not the ANC line.

Only the Bragi assignments are certain. The rest are inferences from the app's command families and
firmware file names.

### Cited Findings
- The Bragi config in app 5.0.6 defines **10 device models**:

  | Model | Bluetooth names / codes | Chipset |
  |---|---|---|
  | Grind (alias "Sesh Boost") | GRIND | AB1565 |
  | Grind Fuel (alias "Sesh Boost Costco") | GRIND_FUEL | (shared slot) |
  | Push Active ("Oppactive", "Opp active") | PUSH_ACTIVE | (shared slot) |
  | Crusher ANC 2 | CHP200 / S6CAW | AB1568, over-ear |
  | Rail ANC | S2IPW-P740 / "railanc" / T80+, code T80_PLUS | AB1562 |
  | Rail | S2RLW-Q740 / "rail" / T70 | (shared slot) |
  | Method 360 ANC | "Skullcandy Method 360 ANC", code T99_PLUS | AB1585 |
  | Method 540 ANC | "Skullcandy Method 540 ANC", code T120 | (shared slot) |
  | Crusher 1080 ANC | CHP300 / "Skullcandy CRUSHER 1080 ANC" | AB1595 |
  | P6 speaker | — | Jieli JL7012, reached through a Bragi "plugin" |

  "(shared slot)" means no chipset string is written for that model; it reuses the previous
  model's entry. — [APK: Bragi config](https://play.google.com/store/apps/details?id=app.skullcandy)
- The Bragi config uses these feature-service types: `BOS_AT`, `AIROHA_RACE`,
  `AIROHA_RACE_CUSTOM`, `BOS_RPC` and `PLUGIN`. — [APK: Bragi config](https://play.google.com/store/apps/details?id=app.skullcandy)
  - Three configurations reference `BOS_RPC` for device information, and they also use Airoha RACE.
    They are bound to Crusher 1080 ANC, Method 540 ANC and Method 360 ANC.
  - All three share the same link UUIDs: data service `54C5BF4C-C52F-4635-98DC-EEBF97C6726F`, the
    third-party service `A4602B24-F9F1-4AC3-988C-228D764B97A6`, and two further UUIDs
    (`8F0A6E23-9E7D-4F3B-9A61-0B9C73B6F6A2`, `EA4F0F8F-89AF-4914-9DFF-AA14E4185810`) whose role I
    did not establish.
- The Skull-iQ configurations are different. — [APK: Bragi config](https://play.google.com/store/apps/details?id=app.skullcandy)
  - The ones built in the class initialiser (Grind, Grind Fuel, Push Active, Crusher ANC 2,
    Rail ANC) take their audio features from `BOS_AT` and other features from `AIROHA_RACE`.
  - They reference UUIDs from a helper class: `0F0186B9-36C6-428A-BC64-9C9A3FF0D38C`,
    `BDA1C873-A897-4371-AA67-B67FE53A1D3E` and `FFEEDDCC-BBAA-9988-7766-554433221100`.
  - The **Rail** (T70) configuration uses only `AIROHA_RACE` and `AIROHA_RACE_CUSTOM`, with
    `FFEEDDCC-BBAA-9988-7766-554433221100`.
- The Flutter layer has one connection API per vendor: Bragi, Airoha, BES, Jieli, Actions, Android
  BLE, Audiodo. It has product getters `isBragiDevice`, `isAirohaDevice`, `isBesDevice`,
  `isActionsDevice` and `isQualcommDevice`, and an enum `SKDYProductChip` (values seen include
  airoha, jieli, actions, qualcomm). — [APK: Dart](https://play.google.com/store/apps/details?id=app.skullcandy)
- Command families in the Dart sources, by vendor folder. — [APK: Dart](https://play.google.com/store/apps/details?id=app.skullcandy)
  - `at_commands` (BOS-AT): ANC state, button settings, Crusher technology level and status, device
    info, language, Mimi user id, stay-aware, voice control.
  - `race_commands` (Airoha): ANC, Bragi, Crusher, gestures, touch, voice control, Mimi, multipoint,
    THX, LED, SG200 EQ, speaker bass boost, battery saver, charge-out protection, feedback sounds.
  - `tota_commands` (BES): hearing modes, EQ mode and custom EQ, low latency, multipoint, sidetone,
    touch control, button settings, TWS status, battery, firmware version, serial number.
  - `actions_commands`: battery, firmware version, FOTA.
  - `jieli`: battery, firmware version, TWS status.
- Native transport hints. — [APK: app.skullcandy.bes / actions](https://play.google.com/store/apps/details?id=app.skullcandy)
  - BES TOTA runs over SPP with service UUID `66666666-6666-6666-6666-666666666666`.
  - The Actions path uses standard SPP `00001101-…` and a BLE service `0000E003-…`.
  - The Airoha linker uses `00000000-0000-0000-0099-AABBCCDDEEFF`.
- Vendor native libraries and FOTA modules shipped in the APK. — [APK: lib/ and dex packages](https://play.google.com/store/apps/details?id=app.skullcandy)
  - Airoha: `libfota155x`, `libfota1562`, `libfota1568`, plus `libnative-anc-1568` and
    `libnative-anc-158x` (the ANC tuning library).
  - Jieli: `libjl_*`.
  - Qualcomm: a GAIA client with its upgrade library.
- Firmware file names in the manifest point to three package families. — [APK: manifest](https://play.google.com/store/apps/details?id=app.skullcandy)
  - **Bragi-style** names: `…_fota_full` plus `…_fota_langpack` for Grind and Crusher ANC 2
    (`skdychp200_…`), and `skdy_t99_…` / `skdy_t120_…` for Method 360/540.
  - **`…_FotaPackage.bin`** names: Indy ANC, Sesh ANC, Rail ANC, Mod, Dime XT2, Push ANC Active
    (`HANC80_…`), Sesh ANC Active, Icon ANC (`HI99_…`), Aviator 900 (`AVANC_…`), Hesh 540
    (`HA2_…`), Hesh 360 (`HEVO2_…`), Crusher 720, Crusher 1080 (`CHP300_…`), Crusher 900 (`CHP250_…`).
  - **Airoha `FotaCode_NVKEY` / `FileSystemImage`** names: the Session speakers.
  - **Odd ones out**: Push 540 Open (`OPEH80_FOTA_…`), Terrain speakers (`…_ota.bin`), gaming
    SG200 (`…_Master_Headset_DFU_…`).
- MediaTek's own blog was titled "Skullcandy Indy ANC True Wireless Earbuds powered by MediaTek
  Airoha" (vendor source; the URL now returns 404). — [MediaTek blog (search result)](https://marketing.mediatek.com/blog/skullcandy-indy-anc-true-wireless-earbuds-powered-by-mediatek-airoha)
- The Skull-iQ platform was described at launch as a "co-developed system-on-a-chip" with updatable
  firmware and Bragi software. — [audioXpress](https://audioxpress.com/news/skullcandy-confirms-partnership-with-bragi-for-software-upgradable-true-wireless-earbuds)
- No FCC record was found for `S6CNW`. Skullcandy files under FCC grantee code **Y22**. —
  [device.report S6MBW](https://device.report/skullcandy/s6mbw), [device.report S6PCW](https://device.report/skullcandy/s6pcw)

### Inferences
Per-model stack. "Certain" means the Bragi config names the model; everything else is inferred.

| Model | Stack | Confidence |
|---|---|---|
| Crusher 1080 ANC | BOS-RPC + RACE side channel, AB1595 | certain (Bragi config) |
| Method 360 ANC | BOS-RPC + RACE side channel, AB1585 | certain |
| Method 540 ANC | BOS-RPC + RACE side channel; chip probably AB1585 (shared slot) | stack certain, chip medium |
| Crusher ANC 2 | Bragi BOS-AT + RACE, AB1568 | certain |
| Rail ANC | Bragi BOS-AT + RACE, AB1562 | high (the T80+ config sits in the BOS-AT group; the register-level binding is not fully resolved) |
| Crusher 900 ANC | probably Bragi (the `CHP` codename series matches CHP200/CHP300, plus a `crusherTechnologyDial` like the 1080) | low; not in the 5.0.6 Bragi config |
| Indy ANC | Airoha (MediaTek blog, `FotaPackage` naming, Audiodo personal sound) | medium |
| Sesh ANC | Airoha RACE / RACE-custom (`FotaPackage` naming, features match RACE commands) | medium |
| Push ANC Active, Sesh ANC Active, Icon ANC | Airoha RACE / RACE-custom (`FotaPackage` naming; feature sets match `race_commands`: ANC, Mimi, multipoint, gestures) | medium |
| Aviator 900 ANC, Hesh 540 ANC | Airoha RACE / RACE-custom (THX spatial lives in `race_commands/thx`) | medium |
| Dime 540 ANC | BES TOTA (only it and Push 540 Open use `capTouchControl`, which only the BES command set implements) | low to medium |

- Jieli, Actions and Qualcomm GAIA seem to serve speakers (P6, Terrain), the Push open-ear models
  and the gaming headsets, not the ANC line.

### Gaps
- The definitive product-to-chip map is compiled Dart code (`SKDYProductChip` and the
  `is…Device` getters), which a string dump cannot recover. A Dart AOT decompiler such as blutter
  would settle the medium and low rows above. Not attempted.
- Whether the non-Bragi Airoha models use standard Airoha RACE command IDs or Skullcandy-specific
  ones was not established. The app's `AIROHA_RACE_CUSTOM` and `airoha_run_custom_command` names
  suggest at least some custom ones.
- I found no teardown or FCC internal photos naming the chips in the 2024–2026 models.

## 3. How the official app tells models apart

### Takeaway
The manifest keys every product by a hex `productId` and a `modelId` list. The `modelId` list holds
the Skullcandy SKU prefix, sometimes plus an ODM code, with alternate Bluetooth names in some
entries. The Bragi SDK matches its own 10 models by Bluetooth name lists, and also has a BLE
advertisement matcher.

### Cited Findings
- Fields in each manifest entry:
  - `productName`, `productId` (for example 0x34 Crusher 1080 ANC, 0x1D Method 360 ANC, 0x2D
    Method 540 ANC, 0x16 Crusher ANC 2, 0x15 Rail ANC).
  - `modelId`, for example `S6CNW`; `S2PPW` + `HANC`; `S6AVW` + `AVANC`.
  - `alternateProductNames` on 14 entries, for example "Crusher PLYR", "SG200-Slave", "OPEH80",
    "SKDY Terrain".
  - `airohaProductId` on four legacy BLE entries: Crusher Evo 0x30, Indy Fuel 0xB, Indy Evo 0xA,
    Crusher 540 Active 0x31.
  - Android connection types: `ble`, `bluetoothClassic`, `leAudio`.

  — [APK: manifest](https://play.google.com/store/apps/details?id=app.skullcandy)
- Two product IDs carry two entries each, an older BLE generation and a newer Classic one with its
  own ID: Crusher Evo (0x02 BLE / 0x30 Classic) and Crusher 540 Active (0x2C / 0x31). — [APK: manifest](https://play.google.com/store/apps/details?id=app.skullcandy)
- A generated native enum lists the same products by camel-case keys: crusher1080ANC,
  method360ANC, method540ANC, crusherANC2, railANC, hanc, tanc, iconANC, aviatorANC, hesh540ANC,
  crusher900ANC, dime540ANC and so on. — [APK: SKDYProductModel](https://play.google.com/store/apps/details?id=app.skullcandy)
- Each Bragi device model carries a list of Bluetooth names, a chipset family and a model code (see
  §2). A separate BLE strategy matches advertisements by model ID, model-ID range and advertised MAC
  address. — [APK: Bragi config](https://play.google.com/store/apps/details?id=app.skullcandy)
- Earshot's own matcher (`BragiModel`) knows only the Crusher 1080. It matches the name fragments
  "crusher 1080"/"crusher1080", model number `S6CNW` and codename CHP300.
  — `protocol/src/main/kotlin/dev/earshot/protocol/bragi/BragiModel.kt` (this repo)

### Inferences
- For Earshot, the cheapest reliable keys are the Bluetooth name and, after connecting, the SKU from
  `dev_info`. The name lists in the Bragi config and the `modelId` SKU prefixes in the manifest
  cover both. For example: "Skullcandy Method 360 ANC" / `S2MTW`; "Skullcandy Method 540 ANC" /
  `S2MFW`; "CHP200"/"S6CAW" for Crusher ANC 2; "railanc"/"T80+"/"S2IPW" for Rail ANC.
- Rail ANC's name list includes "T80+" and the Grind's includes "Sesh Boost". Name hints must not be
  too loose (the same lesson as "QC" vs "QCY" in Earshot's Bose routing).

### Gaps
- Where `productId` is read from on Classic and LE Audio devices (advert, device info or name) was
  not established.

## 4. Which user-facing features each ANC model offers in the app

### Takeaway
Almost every 2023+ ANC model offers these in the app:

- adjustable ANC and adjustable Stay-Aware
- preset and custom EQ, with a bass boost
- Mimi Personal Sound
- button remapping, including Spotify Tap
- multipoint toggle, low latency, and Clear Voice smart mic

Sound by Bose and premium over-ears add wear detection, sidetone and spatial audio (THX or Bose
TrueSpatial). The Crusher models add the haptic Crusher bass. Tile finding exists only on the older
models. The manifest has no auto-off or "find my" feature on any model from 2024 onward.

### Cited Findings
Feature flags, as the union over each product's `featureManifest` entries ("-" = absent).
Source: [APK: manifest](https://play.google.com/store/apps/details?id=app.skullcandy).

Column key:

- **Modes** = "advanced hearing modes" (ANC / Stay-Aware / Off selector).
- **Btns** = `customButtonActions`; the mappable actions include `oneTouchSpotify`, `takePhoto` and
  `voiceAssistant`.
- **Link**: Android connection types.

| pid | product | ANC | Modes | Aware | EQ | Pers. sound | Bass | Btns | Spotify | Low lat. | Multipoint | Wear | Spatial | Sidetone | Voice | Tile | Smart mic | Link |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 0x07 | Indy ANC | on/off | - | ambient | presets | Audiodo | - | - | - | - | - | - | - | - | - | yes | - | Classic |
| 0x0C | Sesh ANC | on/off | yes | adj | presets+custom | - | - | - | - | gaming | - | - | - | - | - | yes | yes | Classic |
| 0x16 | Crusher ANC 2 | adj | yes | adj | presets+custom | Mimi | Crusher | remap | yes | - | toggle | - | - | - | Skull-iQ | yes | yes | Classic |
| 0x15 | Rail ANC | adj | yes | adj | presets+custom | Mimi | boost | remap | yes | - | toggle | - | - | - | Skull-iQ | yes | yes | Classic |
| 0x26 | Push ANC Active | adj | yes | adj | presets+custom | Mimi | boost | remap | yes | yes | toggle | - | - | - | - | - | yes | Classic/LE Audio |
| 0x25 | Sesh ANC Active | adj | yes | adj | presets+custom | Mimi | boost | remap | yes | yes | toggle | - | - | - | - | - | yes | Classic/LE Audio |
| 0x1A | Icon ANC | adj | yes | adj | presets+custom | Mimi | boost | remap | yes | yes | toggle | - | - | - | - | - | yes | Classic/LE Audio |
| 0x1D | Method 360 ANC | adj | yes | adj | presets+custom | - | boost | remap | yes | yes | toggle | - | - | - | - | - | yes | Classic |
| 0x06 | Aviator 900 ANC | adj+adaptive | - | adj | presets+custom (+THX gaming) | Mimi | boost | remap | yes | yes | toggle | yes | THX + head tracking | yes | - | - | yes | Classic/LE Audio |
| 0x1F | Hesh 540 ANC | adj | yes | adj | presets+custom | Mimi | boost | remap | yes | yes | toggle | yes | THX | yes | - | - | yes | Classic/LE Audio |
| 0x2D | Method 540 ANC | adj | yes | adj | presets+custom | - | boost | remap | yes | yes | toggle | - | - | - | Skull AI | - | yes | Classic |
| 0x34 | Crusher 1080 ANC | adj + Bose QuietControl | - | adj | presets+custom+Bose EQ | Mimi | Crusher dial + profiles | remap | - | yes | toggle | yes | Bose TrueSpatial still/motion | yes | - | - | - | Classic/LE Audio |
| 0x44 | Crusher 900 ANC | - (flag missing) | - | adj | presets+custom+Bose EQ | Mimi | boost + Crusher dial | remap | - | yes | toggle | yes | THX | yes | - | - | yes | Classic/LE Audio |
| 0x45 | Dime 540 ANC | adj | yes | adj | presets+custom | Mimi | boost | remap + touch on/off | - | yes | toggle | - | - | - | - | - | yes | Classic |

More findings from the manifest and public sources:

- Sound by Bose flags. Method 360 ANC, Method 540 ANC and Crusher 1080 ANC carry `soundByBose`. The
  Crusher 1080 also has `boseQuietControlANC`, `boseTrueSpatialStill`, `boseTrueSpatialMotion` and
  `boseEqualizer`, and its button actions include "cycle Bose TrueSpatial". — [APK: manifest](https://play.google.com/store/apps/details?id=app.skullcandy)
- Battery and auto-off. Battery is not a manifest flag. The Bragi `device` service has a battery
  call. The Bragi `power_manager` service exposes only a read of the timeout, so auto-off has no
  write path in that stack. — [APK: Bragi service names](https://play.google.com/store/apps/details?id=app.skullcandy)
- Public specs back up the app flags:
  - Crusher 1080 ANC: Bluetooth 5.3, LE Audio with Auracast, multipoint, wear detection, low-latency
    mode. — [SoundGuys](https://www.soundguys.com/skullcandy-crusher-1080-anc-launch-160164/)
  - Icon ANC: Mimi, adjustable Stay-Aware, multipoint, Spotify Tap, Google Fast Pair. — [press release PDF](https://cdn.shopify.com/s/files/1/0635/5114/3993/files/ICON-ANC_PRES-RELEASE.pdf)
  - Crusher ANC 2: Crusher bass adjustable by wheel, voice (Skull-iQ) or app; 4-mic ANC tuned by a
    hearing test. — [Shop-Eat-Surf](https://shop-eat-surf-outdoor.com/2023/05/skullcandy-unveils-the-crusher-anc-2-headphones/)
  - Method 540 ANC: "Skull AI" voice assistant with a subscription. — [SoundGuys](https://www.soundguys.com/skullcandy-method-540-anc-gets-skull-ai-151116/)

### Inferences
- A per-model capability table in Earshot can be built straight from these flags.
- Spatial, wear detection and sidetone matter only for the over-ears.
- Skull AI and Skull-iQ voice control are cloud or voice-pack features. Earshot, with no INTERNET
  permission, should leave them alone.

### Gaps
- The manifest flags say what the UI shows, not which wire service carries each feature.
- On BOS-RPC models some features go over Airoha RACE. The Bragi config mixes both service types,
  and register reuse in the dump hides the per-feature split.

## 5. How much of Earshot's existing Skullcandy support would cover each model

### Takeaway
Earshot's BOS-RPC stack fits the whole Sound by Bose family:

- **Crusher 1080 ANC**: already supported, still unverified on hardware.
- **Method 360 ANC** and **Method 540 ANC**: same link UUIDs, framing and service model.
- **Crusher 900 ANC** possibly, if it ships on Bragi.

Skull-iQ models (Crusher ANC 2, Rail ANC) are Bragi too, but use the AT-style feature service on
different UUIDs. Only Earshot's routing and RFCOMM-by-UUID plumbing carries over to them. All other
ANC models need a new stack (Airoha RACE or BES TOTA). Earshot's rule "never speak RACE" currently
excludes them.

### Cited Findings
- What Earshot has (`docs/crusher-1080.md`, this repo):
  - `protocol/.../bragi/`: `BosRpcPacket`, `PppFraming`, protobuf reader/writer, Crusher messages,
    and `BragiServices` with allowed services DEVICE, HEARING, SPATIAL_AUDIO, CRUSHER and
    AUTHENTICATED_ACCESS.
  - `app/.../bt/BragiLinkFactory.kt`: the data service `54C5BF4C-…` first, then the third-party
    `A4602B24-…`.
  - `app/.../service/CrusherProtocol.kt`, `CrusherHeadphoneRepository.kt` and `VirtualCrusher*`.
- The Method 360/540 Bragi configurations reference the same two UUIDs and `BOS_RPC`, like the
  Crusher 1080 configuration. They do not reference the Crusher-bass or spatial features: those
  belong only to the 1080 configuration (bass levels LOW/MEDIUM/HIGH). The Method EQ preset-name map
  contains "Bass Boost"; the 1080's contains Music / Podcast / Movie. — [APK: Bragi config](https://play.google.com/store/apps/details?id=app.skullcandy)
- The BOS-RPC service names in the app are:
  - action_button, audio_out, authenticated_access, bluetooth, crusher, data_streamer, device,
    echo, feature, hearing, imu_calibration, insertion, language, low_latency, player, mimi,
    power_manager, sounds, spatial_audio, spatial_audio_dolby, test_diagnostics, voice_control.

  Earshot covers device, hearing, spatial_audio and crusher. — [APK: Bragi .proto set](https://play.google.com/store/apps/details?id=app.skullcandy)
- The Skull-iQ (BOS-AT) group references UUIDs `0F0186B9-36C6-428A-BC64-9C9A3FF0D38C` and
  `BDA1C873-A897-4371-AA67-B67FE53A1D3E`, not `54C5BF4C-…`. Its Dart commands are AT-style
  (`at_commands`), not protobuf. — [APK: Bragi config, Dart](https://play.google.com/store/apps/details?id=app.skullcandy)
- No open-source project or Gadgetbridge entry supports any Skullcandy device. Gadgetbridge's
  headphone list has no Skullcandy or Bragi entry. — [Gadgetbridge gadgets](https://gadgetbridge.org/gadgets/); a GitHub search turned up nothing Skullcandy-specific.

### Inferences
| Model | Coverage by existing Earshot code | Work needed |
|---|---|---|
| Crusher 1080 ANC | full driver exists | hardware confirmation by an owner |
| Method 360 ANC, Method 540 ANC | link, framing, `dev_info`, `hearing` (ANC / Aware / Off and gain), sidetone and battery should carry over | new `BragiModel` entries (names "Skullcandy Method 360/540 ANC", SKUs `S2MTW`/`S2MFW`, codes T99_PLUS/T120); hide the Crusher-bass and spatial cards; maybe add `low_latency` read/set; the EQ preset names differ (a "Bass Boost" preset; the full list was not resolved) |
| Crusher 900 ANC (if Bragi) | probably like the 1080 | confirm in a later app version's Bragi config |
| Crusher ANC 2, Rail ANC (and non-ANC Grind, Grind Fuel, Push Active) | only routing (`DeviceFamily.BRAGI`), RFCOMM-by-UUID and the link-profile memory | a new BOS-AT driver |
| Rail (non-ANC) | none | RACE-only |
| Indy ANC, Sesh ANC, Push ANC Active, Sesh ANC Active, Icon ANC, Aviator 900 ANC, Hesh 540 ANC | none | a RACE / RACE-custom stack, which needs the integrator to lift the "never RACE" rule for these models |
| Dime 540 ANC | none | BES TOTA, which involves a session set-up step before commands (not researched) |
| Hesh ANC | GENERIC only | none |

- LE Audio. Many 2024+ models are flagged `leAudio`. If the phone links over LE Audio only, an
  RFCOMM control channel may be absent until Classic is also connected. This should be checked per
  model.

### Gaps
- Whether Method 360/540 ask for the optional secure session as the Crusher 1080 might, and how
  their `hearing` gain ranges differ, are untested. Both are runtime facts Earshot already reads.
- The BOS-AT command set was not researched beyond its command names.

## 6. Services and command groups an app must never use (block-list names)

### Takeaway
Block firmware update on every stack. On BOS-RPC, also block the feature-toggle, language, test and
diagnostics, data-streamer, calibration and crash-reset surfaces, and button injection. On Airoha,
block FOTA, NV-key writes, reset or power-off and raw custom-command passthrough. On BES, Jieli,
Actions and GAIA, block their OTA/upgrade services. Earshot's existing allow-list approach already
does the BOS-RPC part.

### Cited Findings
- BOS-RPC service and method names (these names exist in the app's service set; only names were
  read). — [APK: Bragi .proto set](https://play.google.com/store/apps/details?id=app.skullcandy)
  - `feature.set_state`: toggles device features (Earshot already blocks it).
  - `language.lang_configure`: changes voice-prompt language packs.
  - `test_diagnostics`: memory, task and pool info, boot count.
  - `data_streamer`: start, stop, subscribe.
  - `imu_calibration`: start, stop, reset.
  - `voice_control`: enable, disable, streaming, crash-info reset.
  - `mimi.reset`.
  - `action_button.inject` / `invoke`: simulated button presses.
  - `bluetooth.devicename_set`.
  - `device.time_set`.
  - `spatial_audio_dolby` calibration calls.
  - `authenticated_access`: only through Earshot's existing session code, never ad hoc.
- Firmware update is not a BOS-RPC service in this set. The app ships Airoha FOTA modules
  (`libfota155x`, `libfota1562`, `libfota1568`), and the Bragi configs carry `FOTA` and
  `LAST_FOTA_STATUS_CHECK` features alongside Airoha RACE service types. Other Bragi feature types
  to avoid: `CALIBRATION`, `CUSTOM_COMMAND` (marked hidden), `SESSION`, `AUDIOLOOP`.
  — [APK: Bragi config, libraries](https://play.google.com/store/apps/details?id=app.skullcandy)
- Airoha path: the Flutter layer has an Airoha "run custom command" message and RACE OTA services.
  — [APK: Dart](https://play.google.com/store/apps/details?id=app.skullcandy)
- BES path: a TOTA custom-command passthrough (customer get/set commands) and a BES OTA service.
  Jieli: OTA libraries (`jl_bt_ota`, `libjl_ota_auth`). Actions: a FOTA command set (prepare,
  remote parameters, send data, confirm update). Qualcomm GAIA: an upgrade library.
  — [APK: native and Dart](https://play.google.com/store/apps/details?id=app.skullcandy)
- Skull-iQ AT: commands to reset button settings and to set the Mimi user ID (an account-link
  identifier). — [APK: Dart](https://play.google.com/store/apps/details?id=app.skullcandy)

### Inferences
- Keep `BragiServices.ALLOWED_SERVICES` as an allow-list. Extend it only with `low_latency`, and
  perhaps `mimi` read-only `info`, when Method support lands.
- Any future RACE or TOTA driver should get the same single-write-path guard as `bt/WriteGuard`
  (Bose) and `SoundcoreWriteGuard`: an allow-list of known-safe command IDs, refusing FOTA, NV-key,
  reset, power and custom passthrough.
- Never touch the `66666666-…` BES channel (the same rule as for Soundcore).

### Gaps
- Exact Airoha RACE or BES command IDs for these groups were not looked up. That is out of this
  pass's scope, and an allow-list does not need them.

## 7. Recommended order for Earshot

### Takeaway
1. Get the Crusher 1080 confirmed on real hardware.
2. Extend the same BOS-RPC driver to the Method 360 ANC and Method 540 ANC. This is the
   highest-volume, lowest-effort win.
3. Watch for Crusher 900 ANC in the Bragi config.
4. Consider a BOS-AT driver for Crusher ANC 2 / Rail ANC.
5. Decide on a guarded Airoha RACE stack, which could be shared with JBL's A-RACE models, for the
   2024–2025 ANC line.
6. Leave BES TOTA (Dime 540 ANC) and Hesh ANC as GENERIC.

### Cited Findings
- The Method 360 ANC has been on sale since April 2025 at $99.99–$129.99, and Method 540 ANC since
  January 2026. Both use the same Bragi BOS-RPC configuration pattern as the Crusher 1080. —
  [audioXpress](https://audioxpress.com/news/skullcandy-unveils-new-method-360-anc-earbuds-powered-by-bose), [SoundGuys](https://www.soundguys.com/skullcandy-method-540-anc-gets-skull-ai-151116/), [APK: Bragi config](https://play.google.com/store/apps/details?id=app.skullcandy)
- `docs/crusher-1080.md` lists five open hardware questions for the Crusher 1080 (this repo):
  1. which service answers
  2. whether the secure session is required
  3. the error encoding
  4. tag echo
  5. real ranges

  They apply equally to the Method models.
- The JBL notes in this folder (`jbl.md`) document an Airoha RACE stack ("A-RACE") for several JBL
  models (this repo).

### Inferences
1. **Crusher 1080 hardware confirmation.** Without it, every BOS-RPC extension inherits the same
   unknowns.
2. **Method 360 ANC + Method 540 ANC on BOS-RPC.** This means:
   - adding `BragiModel` entries
   - a per-model capability table (no Crusher bass, no spatial; low latency yes; sidetone per
     runtime)
   - a demo profile on `VirtualCrusher`
   - name hints that cannot catch "Method" non-ANC products
3. **Crusher 900 ANC.** Re-check the next Skullcandy app version. If its Bragi config adds a
   CHP250 entry with `BOS_RPC`, it slots in like the 1080.
4. **BOS-AT driver** (Crusher ANC 2, Rail ANC; Grind, Grind Fuel and Push Active for free). It needs
   its own protocol research and a separate link profile on the `0F0186B9-…` / `BDA1C873-…` UUIDs.
5. **Guarded Airoha RACE driver** (Push ANC Active, Sesh ANC Active, Icon ANC, Aviator 900 ANC,
   Hesh 540 ANC, Sesh ANC, Indy ANC). This is the largest installed base, but it needs an
   integrator decision to allow RACE writes behind an allow-list, and research into Skullcandy's
   custom RACE commands.
6. **Not worth it now:** Dime 540 ANC (BES TOTA with a session set-up, unreleased) and Hesh ANC (no
   control channel). Both stay GENERIC.

### Gaps
- Sales or installed-base figures per model were not found. The ordering uses launch recency,
  price class and engineering reuse instead.
