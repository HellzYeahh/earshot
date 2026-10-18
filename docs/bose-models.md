# Bose QC / noise-cancelling models: per-model facts for Earshot's adaptive UI

Research date 2026-10-18 (for Earshot 0.4.0).

**Sources and confidence tags**
- **[APK]**: Bose app `com.bose.bosemusic` 13.3.4 (`reference/bose-app/base.apk`), decompiled with JADX (local scratch copies, not in the repo),
  plus `dexdump` of `classes4.dex` for the
  one method JADX could not decompile. The `com.bose.bmap.*` names are real. App classes are obfuscated, so they are given
  as `defpackage.Xyz` with the real name taken from the Kotlin metadata.
- **[APK-str]**: the app's `res/values/strings.xml` (product tour, help and setup copy, keyed by codename).
- **[bosectl-v]**: bosectl upstream 0.5.0 (`reference/bosectl-upstream`), verified on hardware by bosectl or a contributor.
- **[bosectl-u]**: bosectl catalogue entry or report that has not been verified.
- **[scan]**: Earshot's own wolverine captures (`docs/reference/captures-galaxy`, `docs/bmap-from-bose-app-13.3.4.md` §9).
- **[pub]**: public spec, press or Bose support page (web search, 2026-10-18).
- **[inf]**: inferred from the sources above.
- **[guess]**: not backed by any source.

---

## 0. Key takeaways

1. **The Bose app gates features almost entirely on what the device reports, not on a product table**
   (`BluetoothCapabilities`, see `docs/bmap-from-bose-app-13.3.4.md` §4). The wire tells you:
   - preset and custom mode counts, plus the feature flags cnc / autoCnc / spatial / wind / favourites / ancToggle, from `[31.2]`;
   - the ModeConfig layout, from the `[31.6]` STATUS length (44 / 45 / 47 / 48);
   - the CNC step count, from `[1.5]`;
   - per-bud battery, from `[2.2]` records with component ids;
   - which settings exist, from GET replies.

   Earshot should do the same. A product table is needed only for **form factor, names, artwork and first-guess
   channel**.
2. **The product table the app does have is `DeviceTypeCapabilities`** (`defpackage.Pq2`), keyed by the
   `DeviceTypeMap` (`defpackage.Rq2`) codename index. It lists:
   - banded headphones: goodyear, duran, prince, lonestarr, wolverine, mariah, plus the unreleased emerald, topaz, opal and hawkeye;
   - multi-component (earbuds): revel, lando, smalls, gwen, scotty, serena, edith, brussels, aubrey, spice, leo, quartz, sapphire;
   - open earbuds: serena, aubrey, spice, quartz;
   - fit kit (ear tips and bands): smalls, scotty, edith, leo, sapphire;
   - "On-Head" detection label (all others are called "In-Ear"): lonestarr, wolverine, emerald, topaz, opal, hawkeye;
   - handover capable: smalls, scotty, serena, edith, aubrey, spice, leo, quartz, sapphire.

   The set names come from the Kotlin metadata. Each set's meaning is inferred from where it is used, for example in
   `InPlaceDetectionSettingsViewModel` and `BatteryStateManager`.
3. **QC Headphones (2nd Gen) = codename `mariah`, product id 0x4083**, short name "Bose QC 2 HP". It is pre-release in
   13.3.4 (generic artwork) and launched in August 2026 [pub]. **Ultra Open Earbuds (2nd Gen) = `aubrey`, 0x4089.**
   Both product ids are in the APK. BoseModel.kt says the codename is "unconfirmed" and that the 0x4089 id appears in no reference.
4. **QC35 and QC35 II are not BMAP devices for the Bose app.** They are absent from the app's BMAP whitelist
   (`BmapDevicesModule.provideSupportedBmapDevices`, `defpackage.zK0`) and from `DeviceTypeMap`. That is a clean
   cut-off line for "very old".
5. **The 2024 "QuietComfort Earbuds" (`brussels`) are not BMAP in the Bose app.** It shows a "We have a different app for that"
   redirect to the "Bose QC Earbuds" app (`com.bose.app.android.brussels`) and recognises the earbuds by a **Bragi** BLE
   advert format. Earshot already has a Bragi stack, so this is a lead (unverified).
6. **Mode names come from the 2-byte prompt id, not from the 32-byte name field** (§C2). The `[1.5]` CNC step and the
   mode or `[31.10]` cnc are both "world volume" scales, where 0 = most cancelling (§C3).

---

## A. Per-product tables (in scope)

Values common to every BMAP model unless noted:
- `[1.7]` EQ is 3 bands (bass, mid, treble), ±10, SET_GET `[value, band]`. Verified on wolverine, prince, duran, lando
  and serena [bosectl-v]. The app also knows band ids 3 LowMid and 4 HighMid [APK].
- Battery `[2.2]` uses 4-byte records (§C1).
- The Bose app opens SPP by SDP UUID. LE CoC is used only when the advert says LE Audio and `[4.19]` says preferred [APK].

### A1. QuietComfort Ultra Headphones (2nd Gen): `wolverine`
| Field | Value | Src |
|---|---|---|
| IDs | pid **0x4082** (16514), BLE id 112, `[0.15]`/originalName "Bose QC Ultra 2 HP" | APK, scan |
| Names / gen | Marketing name "QuietComfort Ultra Headphones (2nd Gen)". App help name "QC Ultra 2 HP". Colour variants 1 Black … 9 Cherry Chocolate. | APK, pub |
| Form factor | Over-ear, banded, "On-Head Detection". Released 2025. Touch volume strip, USB-C audio, "lay flat to disconnect". | APK, APK-str, pub |
| Protocol | BMAP 1.2.0 on RFCOMM ch 2 (reflection) on the Galaxy. `[9.2]` subscription needed for pushes. | scan |
| Modes | `[31.2]` `04 07`: 4 presets (Quiet p1, Aware p2, Immersion p34, Cinema p36) and 7 custom slots (4–10). `[31.8]` favourites, at least 2. `[31.4]`, `[31.5]` and `[31.7]` are absent on fw 10.12. | scan, bosectl-v |
| Noise control | Live `[31.10]` `[cnc, autoCnc, spatial, wind, anc]`, cnc 0–10 on the world-volume scale. `[1.5]` has 11 steps and is read-only. Presets refuse SET_GET (Runtime 8). | scan, bosectl-v |
| Spatial | `[31.10]` byte 2: 0 off, 1 room (Still), 2 head (Motion). `[5.17]` calibration; `[5.15]` absent. | scan |
| Wind / ANC toggle | Wind in `[31.10]` byte 3 and ModeConfig byte 46. ANC on/off in `[31.10]` byte 4 and ModeConfig byte 47. | APK, scan |
| Battery | One record, component 0 (`5affff00`). Charger is `[2.5]`. | scan |
| Other | Shortcut `[1.9]` (Vpa, Disabled, Spotify, Immersive). Self Voice `[1.11]`, multipoint `[1.10]`, auto play/pause `[1.24]`, auto-answer `[1.27]`. Touch-strip disable `[1.34]`. LE Audio `[1.37]` (the headphones reboot). Latency `[5.7]`. | scan |

### A2. QuietComfort Ultra Headphones (1st Gen): `lonestarr`
| Field | Value | Src |
|---|---|---|
| IDs | pid **0x4066** (16486), BLE id 103, originalName "Bose QC Ultra Headphones" | APK |
| Names / gen | Marketing name "QuietComfort Ultra Headphones" with no generation suffix ("1st Gen" is informal). Colour variants Black, White Smoke, Sandstone, Diamond 60th, Lunar Blue, Deep Plum. | APK, pub |
| Form factor | Over-ear, banded, "On-Head Detection". Released 2023. Volume strip with touch-and-hold Shortcut; the Multi-Function button cycles modes. | APK, APK-str |
| Protocol | BMAP; the channel is unknown (no bosectl config or capture). Users report Earshot "not working right" since 0.2.0. | — |
| Modes | Presets Quiet, Aware (ActiveSense), Immersion. **No evidence of Cinema on the 1st Gen.** "Up to seven custom modes", which suggests `[31.2]` = `03 07` (10 slots) [guess]. Power-on mode can be "last used", likely `[31.5]` [inf]. | APK-str, pub |
| Noise control | CNC 0–10 in custom modes. A live `[31.10]` is assumed (same app generation) but unverified. | pub, guess |
| Spatial | Immersive Audio, Still and Motion. | pub |
| Wind / ANC toggle | **Wind Block exists, in custom modes** (Bose HC2710 for QCUH), so the ModeConfig STATUS is at least 47 bytes [inf]. ANC toggle unknown. | pub |
| Battery | One record [inf]. | — |
| Other | Sleeps and wakes on head; the auto-off time is settable. Multipoint. | APK-str |

### A3. QuietComfort Headphones (2nd Gen): `mariah`
| Field | Value | Src |
|---|---|---|
| IDs | pid **0x4083** (16515), BLE id 113, originalName **"Bose QC 2 HP"** | APK |
| Names / gen | Marketing name "QuietComfort Headphones (2nd Gen)". App help title "QuietComfort Headphones 2"; setup copy "Bose QC Headphones 2". 10 colours (Black, White Smoke, Blue Dusk, Dewdrop Mint, Eucalyptus Green, Hazelnut Taupe, Rosewood Mauve, Sky Pink, Vanilla Cream, Vivid Cobalt). | APK, APK-str, pub |
| Form factor | Over-ear, banded. Generic placeholder artwork in 13.3.4 (`DeviceTypeMap.d` = {mariah, leo}). Launched August 2026 for $359. Controls: Bluetooth/Power **slide switch**, **Action button on the left earcup** (Shortcut, cycles favourites), volume +/- and MFB on the right. No touch strip [inf]. USB-C audio. | APK, APK-str, pub |
| Protocol | RFCOMM ch 2, firmware 8.x, MAC prefix 68:F2:1F, 48/40-byte ModeConfig (catalogue comment only; no device file). | bosectl-u |
| Modes | Quiet, "Aware with ActiveSense", Immersion and Cinema, plus custom modes. | APK-str |
| Noise control | "Range of noise cancelling levels", Noise Control can be turned **off**, and "ActiveSense and Wind Block only work with Noise Control on". Probably `[31.10]` like wolverine [inf]. | APK-str |
| Spatial | TrueSpatial: Still, Motion and Cinema. | pub |
| Wind / ANC toggle | Wind Block: yes. ANC off: yes. | APK-str |
| Battery | One record [inf]. | — |

### A4. QuietComfort Headphones: `prince` (QC SC shares the id)
| Field | Value | Src |
|---|---|---|
| IDs | pid **0x4075** (16501), BLE id 71, originalName "Bose QuietComfort Headphones" | APK |
| Form factor | Over-ear, banded, physical buttons. Released 2023. | APK-str |
| Protocol | The SPP UUID resolved to ch **8** on one unit and **9** on another. Some channels accept the socket without BMAP. `[31.3]` START acks with PROCESSING. Firmware 1.0.6. | bosectl-v |
| Modes | Quiet (0, raw cnc 0) and Aware (1, cnc 10) are presets. **2 user slots** (2, 3): the tested unit had Commute (prompt 7) and Music (prompt 12). | bosectl-v |
| Noise control | **No `[31.10]`.** CNC is written by rewriting a slot with a 39-byte `[31.6]` SET_GET; raw 0 = max ANC. `[1.5]` is read-only. | bosectl-v |
| Spatial | None. STATUS byte 44 exists but carries no feature. | bosectl-v |
| Wind / ANC toggle | Wind at STATUS byte 46 and write byte 38. **No ANC toggle** (47-byte STATUS). | bosectl-v, APK |
| Battery | One record. | bosectl-v |
| Other | The two units disagreed on the sidetone and multipoint offsets (open question). | bosectl-v |

### A5. QuietComfort 45: `duran` (QC SE shares the id)
| Field | Value | Src |
|---|---|---|
| IDs | pid **0x4039** (16441), BLE id 55, originalName "Bose QC 45". `DeviceTypeMap.d()` falls back to "Bose QuietComfort Headphones" for duran (sic). | APK |
| Form factor | Over-ear, banded, physical buttons. CSR8670. Released 2021. | bosectl, pub |
| Protocol | ch 8; needs **GET `[0.1]` first**. Firmware 4.0.4. | bosectl-v |
| Modes | Quiet (cnc 0) and Aware (cnc 10), plus user slots 2–3 on fw 4.0.4. The app special-cases duran when `userModes == 0` and hides favourites (`ModesFavoriteManager`, `defpackage.xR4`), so **older firmware has no custom modes**. | bosectl-v, APK |
| Noise control | `[1.5]` is read-only (bosectl calls its SET_GET auth-gated). CNC is written with a 39-byte `[31.6]` SET_GET. When wind is set the firmware stores cnc 0. A 40-byte write gets ERROR Length. | bosectl-v |
| Spatial / ANC toggle | None / none. | bosectl-v |
| Wind | Yes, in custom modes. | bosectl-v |
| Absent | `[1.6]`, `[1.24]`, `[1.27]`, `[31.10]`. | bosectl-v |

### A6. Noise Cancelling Headphones 700: `goodyear` (legacy tier)
| Field | Value | Src |
|---|---|---|
| IDs | pid **0x4024** (16420), BLE id 15, originalName "Bose NC 700 Headphones". In the BMAP whitelist. | APK |
| Form factor | Over-ear, banded, touch right earcup. Released 2019. | pub |
| Protocol | BMAP; channel unknown. bosectl issue #44 is a unit whose BlueZ Modalias says **0x4024**, firmware 2.0.4. It refused **all of block 31** (FblockNotSupp), while Settings, EQ, battery, source and `[4.x]` worked. The reporter labelled it a QC Ultra 2nd Gen, but the pid is goodyear's. | bosectl-u |
| Modes | None (no AudioModes block) [inf]. | — |
| Noise control | 11 levels (0–10) with 3 favourites [pub]. Likely `[1.5]` SET_GET `[step, enabled]` plus `[1.15]` CncPresets `[selected, p0…pn]` (the app has both parsers) [guess for goodyear]. | pub, APK |
| Spatial / wind / ANC | No / no / off only through `[1.5]` enabled, when `userEnableDisable` is set [guess]. | — |

### A7. QuietComfort Ultra Earbuds (2nd Gen): `edith`
| Field | Value | Src |
|---|---|---|
| IDs | pid **0x4062** (16482), case 0x4063, BLE id 108, originalName **"Bose QC Ultra 2 Earbuds"** | APK |
| Names / gen | Marketing name "QuietComfort Ultra Earbuds (2nd Gen)". App help name "QC Ultra 2 Earbuds". Colours Black, White Smoke, Deep Plum, Desert Gold, Midnight Violet. | APK |
| Form factor | Earbuds (multi-component, fit kit, handover capable). **Wireless charging case.** Released 2025. | APK, APK-str |
| Protocol | ch 2, wolverine feature layout. | bosectl-v |
| Modes | Quiet, Aware, Immersion and **Cinema** (Cinema arrived as an update: "Try this new mode"), plus custom slots 4–10. | APK-str, bosectl-v |
| Noise control | `[31.10]` cnc 0–10, verified. | bosectl-v |
| Spatial / wind / ANC | Yes / layout says yes / layout says yes [inf]. | — |
| Battery | Records **1 Right, 2 Left, 3 Case, 4 combined** (`3cffff01 3cffff02 3cffff04 50ffff03`). `[2.5]` follows bud seating, not case charging. | bosectl-v |
| Other | Touch controls, in-ear detection, SimpleSync. | pub |

### A8. QuietComfort Ultra Earbuds: `scotty`
| Field | Value | Src |
|---|---|---|
| IDs | pid **0x4072** (16498), case 0x4073, BLE id 104, originalName "Bose QC Ultra Earbuds" | APK |
| Form factor | Earbuds, fit kit (tips and stability bands). Touch and hold an earbud for the Shortcut. Released 2023. | APK, APK-str |
| Protocol | Unknown; no config anywhere. ch 2 is a [guess]. | — |
| Modes | Default favourites Quiet, Aware and Immersion; "customize additional modes". Custom count unknown. | pub, APK-str |
| Noise / spatial / wind / ANC | CNC 10 levels in custom modes [pub]. Immersive Audio: yes. Wind and ANC toggle: unknown. | pub |
| Battery | Assume the edith record format [guess]. | — |

### A9. QuietComfort Earbuds II: `smalls`
| Field | Value | Src |
|---|---|---|
| IDs | pid **0x4064** (16484), case 0x4065, BLE id 101, originalName "Bose QC Earbuds II" | APK |
| Form factor | Earbuds, fit kit, handover capable. Released 2022. | APK, pub |
| Protocol | Unknown. | — |
| Modes | Quiet and "Aware with ActiveSense", plus **2 custom** ("customize two additional modes"), so 4 slots. | APK-str |
| Noise / spatial | CNC in custom modes / **no spatial**. | pub |
| Wind / ANC | Unknown. The STATUS length (44 or 45) decides [inf]. | — |
| Other | In-ear detection through `[1.16]`: auto play/pause, **auto-answer** ("when you insert an earbud") and **auto-transparency** ("lowest level when you're only wearing one earbud"). Touch Shortcut. | APK-str, APK |

### A10. QuietComfort Earbuds (2020): `lando` (legacy tier)
| Field | Value | Src |
|---|---|---|
| IDs | pid **0x402F** shared by L and R, case 0x4030, BLE ids R 28 / L 29 / case 30, originalName "Bose QuietComfort Earbuds". `ComponentId.getByBoseProductId` maps Right/Left/Case by BLE product. | APK |
| Form factor | Earbuds, wireless charging case. Released 2020. | APK-str |
| Protocol | ch 8, firmware 2.0.7, QCC-384. | bosectl-v |
| Modes | Slots 0–3 only. App copy: "customize two additional modes"; older copy has 3 favourite CNC levels. **44-byte** `[31.6]` STATUS; SET_GET failed in bosectl when it echoed 44 bytes. The app would send **37 bytes** (no spatial/wind/anc tail), which is untested. | bosectl-v, APK-str, APK |
| Noise control | **Direct `[1.5]` SET_GET `[step, enabled]`** (steps 0–10, world volume). | bosectl-v, APK |
| Spatial / wind / ANC | No / no / maybe through the `[1.5]` enabled bit [guess]. | bosectl-v |
| Other | No `[1.24]`/`[1.27]`. Removing one bud drops ANC to the lowest level. Double-tap the left bud to cycle modes. | bosectl-v, APK-str |

### A11. Ultra Open Earbuds: `serena`
| Field | Value | Src |
|---|---|---|
| IDs | pid **0x4068** (16488), case 0x4067, BLE id 102, originalName "Bose Ultra Open Earbuds" | APK |
| Form factor | **Open-ear** earbuds (open set, handover capable, own fit flow). Button on the barrel. Released 2024. | APK, APK-str |
| Protocol | ch 2, firmware 4.0.22. Battery, EQ, Settings SET_GET and `[31.3]` switching work. | bosectl-v (#25) |
| Modes | Presets **Stereo (p35) and Immersion (p34)**, plus custom ("customize additional modes"). The `[31.6]` length was never captured. | APK-str, bosectl-v |
| Noise | **None** (no ANC, CNC or wind). | bosectl-v, pub |
| Spatial | Immersion (Motion); Cinema/Still copy exists for "open earbuds". | APK-str |

### A12. Ultra Open Earbuds (2nd Gen): `aubrey`
| Field | Value | Src |
|---|---|---|
| IDs | pid **0x4089** (16521), case **0x4090**, BLE id 114, originalName "Bose Ultra Open 2 Earbuds"; help title "Ultra Open 2 Earbuds" | APK |
| Form factor | Open-ear, wireless charging case. Announced Sept 17 (2025), $299. | APK, pub |
| Protocol | Unknown; assume the serena behaviour [guess]. | — |
| Modes | Stereo, Immersion and **Cinema** ("plays immersive audio in the Still setting" for open earbuds). | APK-str, pub |
| Noise | None. Auto Volume [pub], possibly `[1.32]` AutoVolumeLevel [guess]. | pub |

### A13. Not supportable or not yet released (recognise them, don't drive them)
| pid | Codename | App name | Notes | Src |
|---|---|---|---|---|
| **0x0023** (value 35), BLE id 223 | **brussels** | "Bose QuietComfort Earbuds" (2024) | Redirected to the "QCE" app. **Bragi** advert format: manufacturer data type 0x41, BLE id = 200 + id, service UUID `F4C93A79-D04F-4565-B05E-79F7EAD9DF8E`. Not BMAP in the Bose app. | APK, pub |
| 0x4034 (case 0x4035) | leo | "Bose Leo Earbuds" | In-ear, fit kit, 2025 bucket, placeholder art. **Its pid collides with BudliteDev 0x4034**: `getByValue` returns BudliteDev. | APK |
| 0x404E / 0x404F | sapphire / quartz | "Bose Sapphire Earbuds" / "Bose Quartz Open Earbuds" | Earbuds (fit kit) / open earbuds. | APK |
| 0x4043, 0x4042, 0x404D, 0x4032 | emerald, topaz, opal, hawkeye | "Bose Emerald / Topaz / Opal Headphones", "Bose Hawkeye" | Banded headphones in the "On-Head" set. | APK |
| 0x4097, 0x403A, 0x402D | spice, gwen, revel | Sport Open Earbuds (2025 / 2021), Sport Earbuds | BMAP, but no ANC: out of the QC scope. | APK |

---

## B. Exclude as "very old"

| Product | pid | Why |
|---|---|---|
| QuietComfort 35 (`wolfcastle`) | 0x400C | 2016, Bose Connect era. **Not** in the app's BMAP whitelist or `DeviceTypeMap`. bosectl: ch 8 plus init GET `[0.1]`; noise is only `[1.6]` ANR off/high/wind/low; no block 31, no EQ, no multipoint. |
| QuietComfort 35 II (`baywolf`) | 0x4020 | 2017; same as QC35 (bosectl's qc35 config). |
| QuietControl 30 (`powder`) | 0x4014 | 2016 neckband, Bose Connect, not whitelisted. |
| SoundSport family, Hearphones (`stetson` 0x4015, `beanie` 0x402B), ProFlight/Aviation (`atlas` 0x4021) | — | Not QC; not whitelisted (Hearphones are not BMAP-app products). |
| Borderline, keep as a "basic tier": NC 700 (2019), QC Earbuds (2020) | 0x4024, 0x402F | Still whitelisted in 13.3.4 but pre-AudioModes or partial-AudioModes. Show battery, EQ, Settings and a CNC slider via `[1.5]`; no mode carousel, or 4-slot modes for lando once a 37-byte write is proven. |

---

## C. Derivations (with code references)

### C1. Battery `[2.2]`
- Parser: `com.bose.bmap.messages.responses.StatusBatteryLevelResponse.createFromPacket`. A payload of 1–4 bytes is one
  record; anything longer is split into 4-byte records `[percent][remaining minutes u16 BE, 0xFFFF → null][componentId]`.
- Model: `messages.models.status.BatteryLevel`. Enum `messages.enums.ComponentId`: -1 Unknown, 0 Single, 1 RightBud,
  2 LeftBud, 3 ChargingCase. **There is no id 4**: bosectl's "combined buds" record (edith) becomes `Unknown` in the app.
- Dispatch: `defpackage.JL6` (~l.768–777). The **headline relay takes record [0]** (percent and minutes, component dropped);
  `batteryInfoBehaviorRelay` gets the full list. For edith's fixture, record 0 is the right bud. Earshot's
  `Battery.aggregate` (id 4, else the lower bud) is a better headline than the app's.
- UI:
  - `BluetoothInfoExecutor.batteryInfoConverter` (`defpackage.yq0.a0`) turns the records into `SystemBatteryResponse(percent, minutes, componentId)`.
  - `BatteryStateManager.BatteryState` (`defpackage.oe0`) treats the battery as "single" when there is one record with Single/Unknown/null. It shows per-component battery only for **multi-component** products (`DeviceTypeCapabilities` set *e*) and sets left / right / case "missing" flags.
  - `LevelIndicatorViewModel.T0` (`defpackage.Jh4`) picks L / R / Case by id and shows "N/A" or "Unavailable".
  - The case is labelled "**Last known case battery %s**" (string `accessibility_battery_level_indicator_charging_case_label`). The widget has "Battery Left / Right".
- Second source: the case's **BLE advert** (`com.bose.bmap.model.parsers.ChargingCasePacketParser`, manufacturer data type 4)
  carries case %, L %, R %, lid open, each bud in case and case charging. Earshot has no BLE scan and would need
  BLUETOOTH_SCAN, so it should show the `[2.2]` id 3 value as "last known".
- Related earbud functions in the app: `[2.9]` InEar `[bit0 L, bit1 R]`, `[2.10]` ChargingCaseLid, `[7.9]`
  ChargingCaseLidStatus, `[7.15]` FitQuality (fit test). `[2.5]` on edith follows seating, not case charging [bosectl-v].

### C2. Mode names and preset detection
- `AudioModesModeConfigResponse.createFromPacket` field layout:

  | Bytes | Field |
  |---|---|
  | 0 | index |
  | 1–2 | prompt, decoded by `AudioModesPrompt.find(b1, b2)` (only b1 = 0 is defined; ids 0–36) |
  | 3 | userConfigurable |
  | 4 | userConfigured |
  | 5 | favourite |
  | 6–37 | name (UTF-8, NUL-terminated) |
  | 41 | mutability bits |
  | 42 | cnc |
  | 43 | autoCnc |
  | 44 | spatial, if length ≥ 45 |
  | 46 | wind, if length ≥ 47 |
  | 47 | anc, if length ≥ 48 |

  The constants are `spatialAudioModePayloadLength = 45`, `anrWindTogglePayloadLength = 47` and `ancTogglePayloadLength = 48`.
- **Display name = prompt, localised.** `ModesSelectionViewModel.K1` (`defpackage.rS4`) calls
  `ModesSelectionActivity.b.e(prompt)`, which calls `com.bose.madrid.audiomodes.ModeNameCollection.c(prompt)`. That returns a
  `R.string.audio_mode_*` for Quiet, Aware, Immersion, Stereo and Cinema, and for the custom-name list (Commute, Focus, Home,
  Music, Outdoor, Relax, Run, Walk, Work, Workout, …). Icons come from `ModeNameCollection.a`. **The name bytes are not used
  for the list.**
- When a mode is named, `ModeNameViewModel.F` (`defpackage.MQ4`) writes the chosen prompt plus `getString(nameRes)`, the
  localised text at naming time, into the 32-byte name. **New custom modes start at cnc 5.**
- Presets vs custom: a preset has STATUS byte 3 (`userConfigurable`) = 0. The counts are `[31.2]` byte 0 (Bose modes) and
  byte 1 (user modes); the app's maximum is their sum (`ModesSelectionViewModel` fields j0 and k0; "Your earbuds/headphones
  can have a maximum of %s modes"). Favourites come from `[31.8]`, with the minimum in `[31.2]` byte 6.
- Preset prompt ids in use: Quiet 1, Aware 2, Immersion 34, Stereo 35, Cinema 36 (wolverine: 01/02/22/24 hex).
- Recommendation for Earshot: name = prompt table (localisable), then fall back to the UTF-8 name, then "Mode N". Use
  "Aware with ActiveSense" copy when autoCnc is mutable or on.
- Write builder (`messages.packets.FBlockAudioModesKt.createAudioModesConfigSetGetPayload`):
  - base is `[idx, p1, p2, name32, cnc, autoCnc]` = **37 bytes**;
  - known spatial adds `spatial`, plus a 0 wind pad if wind is unknown → **39 bytes**;
  - known wind adds `(spatial or 0), wind` → **39 bytes**;
  - known anc adds `anc` → **40 bytes**.

  So the STATUS length maps to the write length as **44 → 37, 45 → 39, 47 → 39, 48 → 40**.

### C2a. `[31.2]` Capabilities (checked against the decompile for 0.4.0)
`AudioModesCapabilitiesResponse.createFromPacket` reads byte 0 boseModes, byte 1 userModes and byte 5 as flags: bit 0
cnc, bit 1 autoCnc, bit 2 spatial (head/room), bit 3 wind toggle, **bit 4 user favourites, bit 5 ANC toggle** (the
constructor order puts ancToggle before favourites, but the bits are 5 and 4). Byte 6, the minimum number of
favourites, is read only when the payload has at least 7 bytes (else 0). Bytes 2-4 are not read; bytes 0, 1 and 5
are read without a length check, so anything shorter than 6 bytes is unusable. The fw 10.12 frame `04 07 00 00 00 7f
02` also has bit 6 set, which the app ignores [APK, scan].

The prompt table `AudioModesPrompt` (ids 0-36, `find` only when byte 1 = 0) matches Earshot's `ModePrompt` one for one
[APK].

### C3. CNC direction
`com.bose.mobile.productcommunication.models.bmap.BmapCncLevelConfig` stores the `[1.5]` currentStep as **`worldVolumeStep`**.
Mode details map cnc ↔ slider with `abs(cnc − (totalSteps − 1))`, with totalSteps defaulting to 11 (`ModeDetailsViewModel`,
`defpackage.QP4`). The wolverine scan read `[1.5]` = `0b0a03` (step 10) while in Aware. So both `[1.5]` and the
ModeConfig/`[31.10]` cnc are **world-volume scales where 0 = most cancelling**, and the UI value is `(steps − 1) − wire`.
The `[1.5]` STATUS byte 2 holds the bits: bit 0 enabled; bit 1 = 0 means the user can toggle.
The write is `SettingsCncSetGetPacket`: SET_GET `[step, enabled ? 1 : 0]` [APK], the same payload bosectl verified on
lando.

### C4. Auto-off `[1.4]` StandbyTimer
- GET / STATUS (`SettingsStandbyTimerStatusResponse`): fewer than 3 bytes → minutes = byte 0; 3 bytes or more →
  minutes = `(byte2 << 8) | byte0` (a signed short) and "supports two-byte minutes". Byte 1 is not read [APK].
- SET_GET (`SettingsStandbyTimerSetGetPacket`): minutes ≤ 255 → `[m]`, else two bytes little-endian `[lo, hi]` [APK].
- The options the app lists (`defpackage.VW`, screen "Auto-Off"): **5, 20, 40, 60, 180, 1440 minutes and 0 = never**,
  in that order. A current value outside the list makes the app pick from the list [APK].
- wolverine fw 10.12 answers FuncNotSupp to `[1.4]` [scan], so the setting does not exist on the QC Ultra 2.

---

## D. Disagreements with `protocol/.../qcultra2/BoseModel.kt`

All of these are resolved in Earshot 0.4.0 (see §F); the text below describes the catalogue before it.

1. **`QC_HEADPHONES_2` codename "unconfirmed"**: the APK says **`mariah`** (0x4083, BLE id 113, `[0.15]` "Bose QC 2 HP"). The
   presets (Quiet/Aware/Immersion/Cinema), Wind Block and ANC off match the entry. It has physical buttons (Action button on
   the left), so `[1.34]` DisableCaptouch is probably absent [guess].
2. **Ultra Open Earbuds 2nd Gen "product id is in no reference"**: it is **0x4089** (`aubrey`, case 0x4090). Add it to
   `ULTRA_OPEN_EARBUDS.productIds` or give it its own entry with presets Stereo/Immersion/Cinema.
3. **Missing models.** These fall through `QcUltra2Protocol.detectModel` → `fromName([0.15])` → **QC Ultra 2 defaults**
   (Immersion and Cinema presets, 11 slots, STANDARD layout):
   - `smalls` 0x4064, `[0.15]` "Bose QC Earbuds II", which `fromName` maps to null;
   - `lando` 0x402F, "Bose QuietComfort Earbuds", null;
   - `goodyear` 0x4024, "Bose NC 700 Headphones", null; the test pins this as intended.

   lando has a verified bosectl config (ch 8, 44-byte STATUS, direct `[1.5]` SET_GET), so it should get its own entry.
4. **`ModeLayout.ofPayload`**: 44 and 45 both map to STANDARD (`size ≥ 40`). Per the APK, 44 = no spatial/wind/anc (write
   37 bytes), 45 = spatial only (write 39 bytes with a 0 wind pad), 47 = COMPACT (39), 48 = STANDARD (40). lando's 44-byte
   frames are misparsed today: cnc is at byte 42 per the APK, and there is no wind or anc.
5. **`fromName`** (applied to `[0.15]` and BT names; the originalName strings are likely the default BT names [inf]):
   - "Bose QC Ultra 2 Earbuds" → `QC_ULTRA_EARBUDS` (1st Gen). The earbuds regex `earbuds? (2|ii)` does not match
     "ultra 2 earbuds"; it needs an `ultra (2|ii)` check like the headphones branch.
   - "Bose QC 2 HP" → null.
   - "Bose QC Headphones 2" → `QC_HEADPHONES` (prince), because neither "2nd" nor "gen 2" is present.
   - "Bose QC Earbuds II", "Bose QuietComfort Earbuds" and "Bose NC 700 Headphones" → null.

   The pre-connect channel guess is the only real impact for the pid-known models, but the smalls, lando and goodyear
   fallbacks depend on it.
6. **`QC_ULTRA_1` / `QC_ULTRA_EARBUDS` fallback `slotCount = 11`, editable 3..10**: Bose says "up to seven custom modes".
   With 3 presets that is probably 10 slots (3..9) [guess]. Runtime `[31.2]`/`[31.8]` should override. lonestarr **has Wind
   Block** (custom modes only) [pub]; Cinema is not on the 1st Gen [pub].
7. **`QC45` editable 2..3 is unconditional**: the APK shows some duran firmware reports `userModes = 0`. Gate custom modes
   on `[31.2]` byte 1.
8. **`ULTRA_OPEN_EARBUDS.presetNames` is empty**: it should be Stereo, Immersion (+ Cinema on gen 2). `hasLiveAudioSettings
   = true` is unbacked: bosectl's serena config has no `[31.10]`.
9. **`[1.5]` direction "unverified" (CLAUDE.md)**: the APK names the field `worldVolumeStep` and the scan agrees, so step 0 =
   most cancelling (§C3).
10. **Wind byte**: BoseModel/CLAUDE.md use 46, which matches the APK and bosectl's prince parser. bosectl's
    `qc_ultra2.STATUS_OFFSETS` still says 45. That is bosectl's error, not Earshot's.
11. **Battery id 4**: Earshot's `COMBINED_BUDS` matches bosectl. The app has no enum value for it and shows only L/R/Case.
    The app's own headline is record 0.
12. **`brussels` 0x0023** should be recognised and reported as "not supported (separate Bose QC Earbuds app)" instead of
    being probed as BMAP. The Bragi stack is a possible future path.
13. **Display names**: the app's short names (originalName) are "Bose QC Ultra 2 HP", "Bose QC 2 HP", "Bose QC Ultra 2
    Earbuds", "Bose QC Ultra Headphones", "Bose QC Ultra Earbuds", "Bose QC Earbuds II", "Bose QuietComfort Headphones", "Bose QC
    45", "Bose NC 700 Headphones", "Bose QuietComfort Earbuds", "Bose Ultra Open Earbuds" and "Bose Ultra Open 2 Earbuds". For
    the header, use the marketing name plus a generation chip: "2nd Gen" for 0x4082, 0x4062, 0x4083 and 0x4089; none for
    the rest.

## E. Suggested detection order for the adaptive UI (from the app's behaviour)
1. `[0.3]` pid gives form factor, names and first-guess channel. An unknown pid falls back to `[0.15]`, and a Bragi advert
   to "unsupported".
2. `[0.2]` blocks. No block 31 means no modes UI and the `[1.5]`/`[1.15]` CNC path.
3. `[31.2]` gives preset and user counts and the flags cnc / autoCnc / spatial / wind / favourites / ancToggle. These drive the
   carousel, editor, Wind and ANC switches.
4. The `[31.6]` STATUS length (44/45/47/48) fixes the write size (37/39/39/40). Try `[31.10]` live; if it is refused, rewrite slots.
5. `[1.5]` steps set the slider range. The `[2.2]` record count and ids choose single vs L/R/Case. Each Settings GET
   (`[1.16]` in-ear vs `[1.24]`/`[1.27]` on-head, `[1.34]`, `[1.37]`, `[1.9]` mask) shows or hides its row.

---

## F. What Earshot 0.4.0 does with this

Implemented in `BoseModel.kt` (catalogue), `service/ConnectionFacts.kt` (per-connection facts) and
`service/QcUltra2Protocol.kt`. Everything below the catalogue is read from the device on each connection.

1. **Catalogue**: every model of §A plus QC35 (0x400C), QC35 II (0x4020) and brussels (0x0023) as `UNSUPPORTED`, and
   NC 700 / QC Earbuds 2020 as `BASIC`, each with family, generation ("2nd Gen" only for 0x4082, 0x4062, 0x4083,
   0x4089), form factor, presets, first-guess channel and layout. mariah, aubrey (Stereo / Immersion / Cinema), smalls,
   lando and goodyear have entries; the Ultra Open lost its live `[31.10]`; the 1st Gen Ultra headphones and earbuds
   guess 10 slots; lonestarr has Wind Block. `fromName` follows §D5.
2. **`[31.2]`** is read once per connection and overrides the preset / custom slot ranges (presets `0 until
   boseModes`, custom slots after them, none when userModes = 0) and the cnc / spatial / wind / favourites /
   ANC-toggle capabilities. `[31.8]` byte 0 still wins for the slot total.
3. **Mode names** come from the prompt (`ModeNames`: Quiet, Aware, Immersion, Stereo, Cinema and the custom-name list),
   then the UTF-8 name, then "Mode N".
4. **`[31.6]` lengths** 44 / 45 / 47 / 48 parse by length and write 37 / 39 (0 wind pad) / 39 / 40 bytes. The 37-byte
   write (lando) is allowed but **untested**; a refusal is reported as the firmware's error.
5. **Noise paths**: `[31.10]` live; else `[1.5]` SET_GET `[step, enabled]` where the catalogue says so (lando, goodyear)
   or block 31 is missing; else the active custom slot is rewritten. A refused `[1.5]` write drops to the slot path
   (with modes) or read-only (without). On `[1.5]` the UI gets the level (mapped onto the unit's step count, step 0 =
   most cancelling), ANC on/off only when flags bit 1 is clear, no wind or spatial.
6. **Battery**: `[2.2]` records map by id (0 overall, 1 right, 2 left, 3 case, 4 overall when there is no 0) with the
   minutes left; the headline stays `Battery.aggregate`. A 1- to 3-byte payload is one record, as in the app.
7. **Auto-off** `[1.4]` per §C4; the option list is `dev.earshot.protocol.qcultra2.AutoOffOptions.MINUTES`.
8. **EQ range**: the `[1.7]` min..max (within ±10) is reported and writes are clamped to it.
9. **Unknown products** keep the QC Ultra 2 defaults but take their family and form factor from `[0.15]`;
   **unsupported** ones read the battery and product info only, and every command fails with the support note.

Still guesses (no hardware): channels for smalls / goodyear (8), the `[31.6]` lengths of smalls and the Ultra Open,
whether smalls has `[31.10]` (catalogued without), goodyear's `[1.5]` write, the meaning of `[1.4]` byte 1, and
everything about scotty, lonestarr and mariah beyond the wolverine layout.
