# Headset protocol landscape (research, 2026-10-20)

Background for [the roadmap](../roadmap.md). Collected from public sources on 2026-10-20 and not
re-checked since. Anything marked *unverified* rests on one source or on reading code, not on a
device. Nothing here comes from a real capture of ours, except where it says so.

## Licence rule for Earshot (MIT)

- **Permissive (MIT / Apache-2.0 / BSD / CC-BY):** credit them like bosectl. Code may be ported with
  attribution in `docs/reference/` and the About screen.
- **GPL / AGPL (Gadgetbridge, GalaxyBudsClient, OpenSCQ30, LibrePods, CAPod, OpenFreebuds,
  based-connect, …):** protocol *facts* only. Re-implement from the documented facts and our own
  captures. Never copy code, tables, or test data from them.
- **No licence** (all rights reserved): same as GPL, facts only.

## Brands

| Brand | Transport | Best open reference (licence) | Depth | Effort |
|---|---|---|---|---|
| Sony WH/WF-1000XM, LinkBuds | RFCOMM via SDP: v1 UUID `96CC203E-5068-46AD-B32D-E316F5E069BA` (≤ XM4), v2 `956C7B26-D49A-4BA8-B03F-B17D393CB6E2` (XM5+); BLE on XM5+ | [mos9527/SonyHeadphonesClient](https://github.com/mos9527/SonyHeadphonesClient) **MIT**, very active | High | M |
| Apple AirPods / Beats | AAP over L2CAP PSM `0x1001`; BLE Continuity adverts (company `0x004C`) for battery and lid | [LibrePods](https://github.com/librepods-org/librepods), [CAPod](https://github.com/d4rken-org/capod) GPL-3.0 | High | M |
| Google Fast Pair (cross-brand) | RFCOMM Message Stream `df21fe2c-2515-4fdb-8886-f12c4d67927c`; battery in BLE adverts (`0xFE2C`) | [Google spec](https://developers.google.com/nearby/fast-pair/specifications/extensions/messagestream) CC-BY / Apache | Low (battery, ring, ANC read) | S |
| Samsung Galaxy Buds | RFCOMM; Buds2+ UUID `2e73a4ad-332d-41fc-90e2-16bef06523f2`; `FD … CRC16 … DD` | [GalaxyBudsClient](https://github.com/timschneeb/GalaxyBudsClient) GPL-3.0 | High | M |
| Anker Soundcore | RFCOMM (SPP or vendor `0cf12d31-fac3-4553-bd80-d6832e7…`); `08 EE 00 00 00` out, `09 FF 00 00 01` in, sum checksum | [OpenSCQ30](https://github.com/Oppzippy/OpenSCQ30) GPL-3.0, ~40 models | Medium–High | M→L (per-model layouts) |
| Google Pixel Buds Pro / Pro 2 | RFCOMM "Maestro" `25e97ff7-24ce-4c4c-8951-f764a708f7b5`, pw_rpc + protobuf in HDLC | [qzed/pbpctrl](https://github.com/qzed/pbpctrl) **MIT/Apache-2.0** | Medium | M |
| JBL / Harman | Three stacks: BLE GATT (gen 2), Airoha RACE (Tune 770NC), gen 3 unknown | [jbl-control](https://github.com/AhmadRun/jbl-control) MIT, thin | Medium | L |
| Huawei FreeBuds | RFCOMM ch 16, `5A len 00 svc cmd TLV CRC16` | [OpenFreebuds](https://github.com/melianmiko/OpenFreebuds) GPL-3.0 | Medium | M |
| Xiaomi / Redmi Buds | RFCOMM `0000fd2d-…`, a JieLi RCSP dialect with auth | [MiBudsController](https://github.com/FallenLeeee/MiBudsController) MIT | High | M |
| Nothing / CMF | SPP `aeac4a03-dff5-498f-843a-34487cf133eb`, `55 …` frame, optional CRC16 | [nothing-ear-controller](https://github.com/LuanAdemi/nothing-ear-controller) MIT | Medium | S–M |
| Oppo / OnePlus / Realme | RFCOMM `0000079a-d102-11e1-9b23-00025b00a5a5`, `AA …` LE frames | Gadgetbridge (AGPL) | Medium | M |
| Sennheiser Momentum 4, Accentum | Qualcomm GAIA v3, vendor `0x0495` | [momentumctl](https://codeberg.org/galen/momentumctl) MIT | Medium | M |
| Edifier | SPP/BLE, `AA`/`BB` frames, sum + 0x2019 | [mEDIFIER](https://github.com/wh201906/mEDIFIER) MIT | Per model | S–M |
| Shokz, Jabra | RFCOMM proprietary | Gadgetbridge (AGPL) | Medium | M |
| Marshall, Technics, B&O | — | none | ? | L |
| **Bose QC35 / QC35 II** | **Plain BMAP**: noise `[1.6]` SET_GET `[level]`, auto-off `[1.4]`, prompts `[1.3]`, name `[1.2]`, pairing `[4.8]` | [based-connect](https://github.com/Denton-L/based-connect) GPL-3.0 (facts only) | Basic | **S** |

Market notes: Apple #1 TWS (23 % Canalys Q1 2025) with no first-party Android app, so this is the
biggest unmet need. Samsung #3, but Samsung's own app runs on every Android phone. Sony is the
premium ANC peer of Bose. JBL/Xiaomi are volume leaders (Futuresource).

## Chipsets (what no-name earbuds run on)

Secondary market figures (unverified): JieLi about 26 %, Bluetrum about 20 % of TWS main chips in
2022, Realtek mostly gone from the low end.

| Chipset | How to recognise it | Protocol | Open reference (licence) | Notes |
|---|---|---|---|---|
| **JieLi** AC69/70xx, JL701N | BLE service `0xAE00` (AE01 write, AE02 notify), company `0x05D6`; QCY renames it `0xA002`; RFCOMM on some | RCSP: `FE DC BA flag op len16BE [status] sn payload EF`; challenge-response auth at connect | **JieLi's own SDKs, Apache-2.0**: [Android-JL_Bluetooth](https://github.com/Jieli-Tech/Android-JL_Bluetooth), [Android-JL_OTA](https://github.com/Jieli-Tech/Android-JL_OTA), auth in [jl_auth_2.0.0.js](https://github.com/Jieli-Tech/WeChat-Mini-Program-OTA), device side in [fw-AC63_BT_SDK](https://github.com/Jieli-Tech/fw-AC63_BT_SDK) | The widest reach, and the only chipset with a permissive *official* reference. Redmi, QCY, Oraimo speak dialects. Many no-name buds ship with RCSP off (unverified). Block OTA, reboot, reset. |
| **Bluetrum** AB53/56xx | BLE `0xFDB3` (FF17 write, FF18 notify), company `0x0642` | "ABMate": EQ 0x20, keys 0x22, auto-off 0x23, in-ear 0x26, ANC 0x35 … | [ABMate demo](https://github.com/GitHubyangjunyi/ABMate) (no licence) | Framing unverified. **Block 0x24 factory reset, 0x2F clear pairing**, OTA. |
| Airoha AB155x–157x | RFCOMM UUID `00000000-0000-0000-0099-AABBCCDDEEFF` (often ch 21), BLE `5052494D-2DAB-0341-6972-6F6861424C45`, company `0x0094` | RACE `05 type len16LE id16LE payload` | [race-toolkit](https://github.com/auracast-research/race-toolkit) (no licence); [ERNW write-up](https://insinuator.net/2025/12/bluetooth-headphone-jacking-full-disclosure-of-airoha-race-vulnerabilities) | Sony, Marshall, JBL, Jabra Elite 8 Active, **Bose QC Earbuds (lando)**. Unauthenticated flash/RAM access on unpatched firmware (CVE-2025-20700/1/2): **allow-list only, never write.** Feature IDs are vendor-specific. |
| Qualcomm QCC (GAIA) | RFCOMM `00001107-D102-11E1-9B23-00025B00A5A5`; UUIDs on the `…-d102-11e1-9b23-00025b00a5a5` base | `FF ver flags len vendor16 cmd16 payload` | [SpaceTravel-Protocol](https://github.com/pubglite55/SpaceTravel-Protocol) MIT (Moondrop) | Framing S, features per vendor (Sennheiser, EarFun, Moondrop). |
| BES 23xx–27xx | RFCOMM `66666666-6666-6666-6666-666666666666` = firmware update | brand-specific | — | **Recognise, never open** the 6666 channel. |
| Realtek, Actions | company `0x005D`, `0x03E0` | closed | — | Skip. |

An Android app can identify a headset only from the SDP service UUIDs (`fetchUuidsWithSdp`), BLE
adverts (company ID, service UUIDs, the Fast Pair model ID), the name, and GATT services after
connecting. Brands often replace the company ID with their own, so service UUIDs are the stronger
signal.

## Findings that affect Earshot today

1. **Unknown headsets are treated as Bose.** `DeviceFamily.forName` returns `BOSE` for any name
   that isn't Bragi, so pointing Earshot at an AirPods or a Sony runs the BMAP channel search
   against it. That means sockets on channels 2/8/9 and the SDP UUIDs, each sent a `[0.1]` probe.
   That is harmless for BMAP, but on other stacks a stray frame has powered a headset off (next
   item). Fix this before any multi-brand work.
2. **`00000000-deca-fade-deca-deafdecacaff` may be Apple's iAP2 accessory UUID, not a Bose BMAP UUID.**
   One source says so (*unverified*): a Sennheiser write-up, where GAIA frames on that channel
   powered an Accentum off. That would explain "SDP resolves it to the wrong channel". Check it
   against the QC Ultra 2's SDP record before relying on the `SDP BMAP` candidate.
3. **Bose QC Earbuds (lando) are Airoha.** The ERNW toolkit lists a Bose RACE UUID
   `2D064AA9-32B5-4970-865C-643742BD2862`. BMAP probing is harmless there, but never write RACE.
4. **The QC35 speaks BMAP** (based-connect), so its UNSUPPORTED entry could get basic noise and
   auto-off control (S effort, experimental: nobody here owns one).

## What Android gives every headset (no vendor protocol)

- **Battery:**
  - `android.bluetooth.device.action.BATTERY_LEVEL_CHANGED` reaches a normal app holding
    `BLUETOOTH_CONNECT`. Its sources are the HFP battery indicator, Apple `+IPHONEACCEV`,
    Plantronics `+XEVENT` and BLE BAS.
  - `getBatteryLevel()` is a hidden SystemApi since Android 13, so the *initial* value needs the
    Shizuku dumpsys or the next broadcast.
  - Fast Pair adverts carry L/R/case battery. Matching an advert to *your* headset is only a
    heuristic.
- **Codec:**
  - `ACTION_CODEC_CONFIG_CHANGED` with `EXTRA_CODEC_STATUS` reaches normal apps, so the current
    codec can be read without Shizuku.
  - `getCodecStatus` and `setCodecConfigPreference` accept a **Companion Device Manager
    association** instead of `BLUETOOTH_PRIVILEGED`. They are SystemApi, so whether reflection may
    call them is *unverified*: check `hiddenapi-flags.csv`.
- **Companion Device Manager:**
  - An association gives background foreground-service starts.
  - It also gives `onDevicePresenceEvent` connect and disconnect callbacks.
  - It is the Play-sanctioned replacement for the boot and ACL receivers.
- **Not available to apps** (system or privileged only): LE Audio Volume Control, Microphone
  Control and hearing-aid presets, Spatializer on/off, AVRCP absolute-volume writes.
- **HFP:**
  - `startVoiceRecognition`, the vendor headset events (`+XEVENT`, `+IPHONEACCEV`) and
    `isNoiseReductionSupported` are public.

## Google Play and other channels

- **Shizuku apps are on Play** (RootlessJamesDSP, Key Mapper), so it is acceptable if the core app
  works without it.
- **Hidden APIs:**
  - `createInsecureRfcommSocket(int)` is "unsupported" (allowed, no maxTargetSdk).
  - SystemApi reflection is probably blocked.
  - Target SDK 36 is required from 2026-08-31.
- **Must fix before Play:**
  - `QUERY_ALL_PACKAGES`: replace it with a `<queries>` launcher intent.
  - The debug-key release signing.
  - The missing privacy policy and licences screen.
  - The `connectedDevice` foreground-service declaration, which needs a video of in-app
    connection.
- **Trademarks:**
  - Keep "Earshot" as the name and use brands descriptively only ("works with …").
  - No logos or product photos.
  - Add a not-affiliated line for every brand mentioned.
  - Galaxy Buds Client lists as "BudsClient" on Play.
- **F-Droid / IzzyOnDroid:**
  - Earshot fits (no network, no Google libraries).
  - Reproducible builds let F-Droid ship our signature.
  - Android developer verification goes global in 2027; register the signing key.
