# Sony ANC headphones and earbuds (2020 onward): Bluetooth control protocol ("Tandem" / MDR)

Research notes for Earshot roadmap item E3 ("Sony (Experimental)"), written 2026-10-21. Static analysis only:
the APK was never installed or run, and no Bluetooth device was contacted. Facts are restated in my own words.
This extends `docs/research/headset-landscape-2026-10.md`, which already has the two UUIDs, the 3E…3C framing and
the MIT reference; those basics are only repeated here where the app adds detail.

**Status: complete for E3 planning (2026-10-21). Not verified on hardware.**

Short names used below (all links are reference-style, defined at the end of the file):
- **APK** = Sony | Sound Connect 13.3.0 (`com.sony.songpal.mdr`) base.apk, decompiled with jadx. Proprietary:
  facts only. Class names are the app's own where readable (`com.sony.songpal.tandemfamily.*`,
  `com.sony.songpal.mdr.kmp.*`), otherwise the R8 names (`lm0.c`, `qm0.b`, …), which are stable only for 13.3.0.
- **mos** = mos9527/SonyHeadphonesClient `libmdr` (MIT): `ProtocolV1T1.hpp`, `ProtocolV2T1.hpp`, `ProtocolV2T2.hpp`,
  `Headphones*.cpp`, `docs/device-support/*.md`.
- **pluto** = Plutoberth/SonyHeadphonesClient (MIT, archived 2025-07-19).
- **GB** = Gadgetbridge Sony headphones driver (AGPL-3.0, facts only), Codeberg master.
- **mdrp** = AndreasOlofsson/mdr-protocol (no licence file, facts only).
- Command ids are written as the first payload byte (the "command"), followed by the second byte (the
  "inquired type", Sony's sub-function selector): `68 17` = NCASM_SET_PARAM, inquired type 0x17.

---

## 1. Model scope, protocol generation per model, and identification

### Takeaway
Every Sony model the app supports speaks the same "Tandem" framing; what differs is the **command-table set**:
`TABLE_SET_1` (protocol "v1", MDR table `mdr.v1`) or `TABLE_SET_2` ("v2", `mdr.v2`). The app picks the set **from
the RFCOMM service UUID the headset advertises in SDP** (v2 UUID checked first), not from a model list; the
`CONNECT_RET_PROTOCOL_INFO` reply length (4 bytes v1, 8 bytes v2) is the on-wire confirmation. All 2022+ models
(LinkBuds family, WH-1000XM5/XM6, WF-1000XM5/XM6, WH-CH720N, WF-C700N/C710N, ULT Wear) and the WF-1000XM4 are v2;
the WH-1000XM4 (2020) is the one notable 2020+ ANC model on v1. The app identifies the model from the device
itself: `CONNECT_GET_DEVICE_INFO` returns the model name string, the firmware version string, and a
series byte + colour byte; there is no numeric model id on the wire comparable to Bose's product id.

### Cited Findings

#### How the app chooses v1 or v2
- Two RFCOMM service records are known to the app, each with a "reversed" twin: v1 `96CC203E-5068-46AD-B32D-E316F5E069BA`
  (twin `BA69E0F5-16E3-2DB3-AD46-68503E20CC96`) and v2 `956C7B26-D49A-4BA8-B03F-B17D393CB6E2` (twin
  `E2B63C39-7DB1-3FB0-A84B-9AD4267B6C95`). The twin is the same 16 bytes in reverse order; the app accepts it
  because "Android was return a reversed UUID" (its own log text) — [APK `lm0.i`, `ep0.a`][apk]
- The candidate list is ordered **[v2, v1]**; `ep0.a.d()` walks the list and returns the first one found in
  `BluetoothDevice.getUuids()`. v1 → `CommandTableSet.TABLE_SET_1`, v2 → `TABLE_SET_2`
  (`platform.connection.connection.l1`) — [APK `lm0.i`, `ep0.a`, `l1`][apk]
- The same v1 UUID is used as the `CompanionDeviceManager` association filter (with a name pattern) —
  [APK `application.e0`, `application.q0`, `vim.p`][apk]
- v1 `CONNECT_RET_PROTOCOL_INFO` = 4 bytes `[01, 00, version u16 BE]` (`tm0.m2`); v2 = exactly 8 bytes
  `[01, 00, version u32 BE, table1 EnableDisable, table2 EnableDisable]` (`in0.k`; note `EnableDisable` ENABLE = 0x00,
  DISABLE = 0x01) — [APK `tm0.m2`, `in0.k`][apk]; matches [mos `ProtocolV2T1.hpp`][mos-v2t1] and GB's detection by
  reply length (4 → V1, 8 → V2) — [GB `SonyHeadphonesProtocol.java`][gb-proto]
- GB's source comments list replies seen on real units: V1 (4 bytes) WH-1000XM3, WF-SP800N, WI-SP600N; V2 (8 bytes)
  WF-1000XM4 1.1.5, LinkBuds 1.0.3, LinkBuds S 2.0.2, WH-1000XM5 1.1.3, WF-1000XM5 2.0.1 — [GB][gb-proto]
- GB uses the V2 UUID if the device advertises it, else V1 — [GB `SonyHeadphonesSupport.java`][gb-support]

#### Identification messages (v2)
- `CONNECT_GET_DEVICE_INFO` `[04, type]`, reply `CONNECT_RET_DEVICE_INFO` `[05, type, …]` with `DeviceInfoType`
  MODEL_NAME 0x01, FW_VERSION 0x02, SERIES_AND_COLOR_INFO 0x03, INSTRUCTION_GUIDE 0x04 — [APK `in0.b`, `in0.f`][apk]
- MODEL_NAME and FW_VERSION replies are `[05, type, len, UTF-8 string]` (length byte must equal the remaining bytes)
  — [APK `in0.i`, `in0.g`][apk]
- SERIES_AND_COLOR_INFO reply is exactly 4 bytes `[05, 03, series, colour]` — [APK `in0.j`][apk]
- `ModelSeries` (v2): NO_SERIES 0x00, EXTRA_BASS 0x10, ULT_POWER_SOUND 0x11, HEAR 0x20, PREMIUM 0x30 (1000X line),
  SPORTS 0x40, CASUAL 0x50, LINK_BUDS 0x60, NECKBAND 0x70, LINKPOD 0x80, GAMING 0x90 (INZONE). v1 has only
  NO_SERIES/EXTRA_BASS/HEAR/PREMIUM/SPORTS/CASUAL — [APK enums `…v2.table1.connect.param.ModelSeries`,
  `…v1.table1.param.ModelSeries`][apk]
- The app's cloud model catalogue (images, colours, tutorial pages) is keyed by `model_id`, `model_number`,
  `model_name` and `model_color_id`; the bundled `assets/model_info_list_for_debug.json` only holds dummy entries
  (WF-1000XM4, WF-C500), so no real model→id table ships in the APK — [APK assets][apk]
- mos's init reads FW_VERSION, MODEL_NAME and SERIES_AND_COLOR_INFO right after protocol and capability info —
  [mos `HeadphonesV2.cpp`][mos-hv2]

#### Model scope (2020+ ANC models and close relatives)

| Model (marketing name) | Model no. | Release | Table set | Evidence |
|---|---|---|---|---|
| WH-1000XM4 | WH-1000XM4 | Aug 2020 | v1 (in GB practice; not in GB's sample list) | [GB support page][gb-page], mos v1-compat README |
| WF-1000XM4 | WF-1000XM4 | Jun 2021 | v2 | [GB][gb-proto] |
| WH-XB910N | WH-XB910N | Oct 2021 | not confirmed | in Sony's compatibility list [Sony][sony-compat] |
| LinkBuds (no ANC) | WF-L900 | Feb 2022 | v2 | [GB][gb-proto] |
| LinkBuds S | WF-LS900N | May 2022 | v2 | [GB][gb-proto], [mos docs][mos-tree] |
| WH-1000XM5 | WH-1000XM5 | May 2022 | v2 | [GB][gb-proto], [mos docs][mos-tree] |
| WH-CH720N | WH-CH720N | 2023 | v2 (mos device doc exists) | [mos docs][mos-tree] |
| WF-C700N | WF-C700N | 2023 | v2 (mos device doc) | [mos docs][mos-tree] |
| WF-1000XM5 | WF-1000XM5 | Jul 2023 | v2 | [GB][gb-proto], [mos tests `WF-1000XM5-6.1.0`][mos-tree] |
| INZONE Buds | WF-G700N | 2023 | not confirmed (series GAMING exists) | [Sony][sony-compat] |
| ULT Wear | WH-ULT900N | 2024 | GB "highly supported" (V2 assumed) | [GB support page][gb-page] |
| LinkBuds Fit / Open | WF-LS910N / WF-L910 | late 2024 | v2 (mos doc for WF-L910) | [mos docs][mos-tree] |
| WF-C510 (no ANC) / WF-C710N | — | 2024 / 2025 | GB supported | [GB support page][gb-page] |
| WH-1000XM6 | WH-1000XM6 | 2025 | v2, mos mainline support | [mos WH-1000XM6 doc][mos-xm6] |
| LinkBuds Clip | WF-LC900 | announced 2026-01-21 | v2 (mos tests `WF-LC900-2.0.3`) | [mos docs][mos-tree], [GSMArena][gsma] |
| WF-1000XM6 | WF-1000XM6 | announced 2026-02-12 | v2 (mos device doc) | [mos WF-1000XM6 doc][mos-xm6wf], [audioXpress][axp] |

Release months without a link are from the web researcher's memory and are not verified.

### Inferences
- Earshot should copy the app: read `getUuids()`, prefer the v2 UUID, accept the byte-reversed twins, and then
  confirm with the protocol-info length. A mismatch (v2 UUID but 4-byte reply) should fall back to the v1 tables.
- Model routing should key on the MODEL_NAME string from the device (e.g. "WH-1000XM5"), with the Bluetooth name
  as a pre-connect guess only; the series byte is useful for UI (earbuds vs headband) but not for features.
  Features come from the support-function list (section 3), never from the model name.

### Gaps
- No ground-truth table of which UUID each model advertises (SDP records need a device). The WH-1000XM4 = v1
  assignment rests on GB practice and the mos README, not on a capture.
- Sound Connect's current official compatibility page (May 2026) returned HTTP 403 to the web researcher.

---

## 2. Transport: RFCOMM, BLE GATT, connection init, ACK and retries

### Takeaway
Classic headsets: **secure** RFCOMM `createRfcommSocketToServiceRecord(uuid)` (SDP-resolved channel, never a fixed
number). LE Audio headsets connected over LE: the same Tandem frames over a GATT service
(`5b833e20-…` "TANDEM_V2_HPC_SERVICE", write `5b833c60`, notify `5b833c61`). Frames are `3E | type | seq | len u32
BE | payload | sum8 | 3C` with 0x3D escaping; stop-and-wait with a 1-bit sequence, **750 ms ACK timeout and up
to 10 resends** (LARGE_DATA: 5 s, 2 resends), after which the app drops the link.

### Cited Findings

#### RFCOMM
- The connector cancels discovery and opens `createRfcommSocketToServiceRecord(uuid)` (secure, SDP lookup) with the
  UUID of the chosen table set; on Android 12+ it bails out if BLUETOOTH_SCAN is not granted —
  [APK `ep0.c`, `om0.c`, `om0.d`][apk]
- GB waits 500 ms after the socket connects before sending ("connecting too quickly fails") and reads with a
  2048-byte buffer — [GB `SonyHeadphonesSupport.java`][gb-support]

#### Frame codec (app)
- Start byte 0x3E, end byte 0x3C. Body before escaping: `dataType(1) seq(1) payloadLength(u32 BE) payload checksum(1)`;
  checksum = low 8 bits of the plain byte sum of everything before it — [APK `qm0.b`, `cp0.f`][apk]
- Escaping: 0x3C → `3D 2C`, 0x3D → `3D 2D`, 0x3E → `3D 2E`; unescape = byte after 0x3D OR 0x10 —
  [APK `cp0.b`, `qm0.e`][apk]; same in [pluto `CommandSerializer.cpp`][pluto-ser]
- The receiver scans for 0x3E, buffers to 0x3C, unescapes, checks the sum (bad sum → frame dropped silently, no
  NAK), and reports but tolerates a length field that disagrees with the actual size — [APK `qm0.a`][apk]
- Outgoing frames longer than the transport's max packet size are split into chunks by the writer (the frame is
  not changed, only the writes) — [APK `qm0.b`, `cp0.a`][apk]
- `DataType` byte (ack required?): DATA 0x00 (yes), ACK 0x01 (no), DATA_MC_NO1 0x02, DATA_SSH 0x07, DATA_ICD 0x09,
  DATA_EV 0x0A, **DATA_MDR 0x0C** (yes, table 1), DATA_COMMON 0x0D, **DATA_MDR_NO2 0x0E** (yes, table 2),
  SHOT 0x10 (no), SHOT_MDR 0x1C (no), SHOT_COMMON 0x1D, SHOT_MDR_NO2 0x1E (no), LARGE_DATA_SSH 0x27,
  LARGE_DATA_MDR 0x2C (yes), LARGE_DATA_COMMON 0x2D — [APK `tandemfamily.message.DataType`][apk]
- The v2 receiver routes DATA_MDR / SHOT_MDR / LARGE_DATA_MDR to the table-1 parser and DATA_MDR_NO2 / SHOT_MDR_NO2
  to the table-2 parser; unknown commands and unknown data types are still ACKed and then ignored —
  [APK `om0.f`][apk]. (This confirms the web researcher's inference that table 2 rides on type 0x0E.)

#### Sequence numbers, ACK, retries (app)
- The ACK frame is fixed: `3E 01 <seq> 00 00 00 00 <sum> 3C`, i.e. `3E 01 00 00000000 01 3C` or
  `3E 01 01 00000000 02 3C` — [APK `qm0.b`][apk]
- For every received frame whose type needs an ACK, the app answers with ACK seq = **1 − received seq**
  (`cp0.d.a`). It then drops the frame if its seq equals the previous received seq (a resend), unless the
  message is a "notify" class — [APK `lm0.c$b`, `cp0.d`][apk]
- Sending: the app keeps one outgoing seq (starts 0). It sends with the current seq and blocks; an ACK whose seq
  differs from the current seq is accepted and **its seq becomes the next outgoing seq**; an ACK with the same seq
  is logged as invalid and ignored. Only one frame is in flight — [APK `lm0.c`][apk]
- Timeout: 750 ms per try, up to 10 resends with the same seq for normal frames; LARGE_DATA_MDR 5000 ms and 2
  resends. After the last try the app reports "Remote endpoint does not respond" and closes the connection —
  [APK `om0.e.l`, `lm0.c.n`][apk]
- On (re)connect of a BLE target the sequence state is reset (send seq 0, last received seq −1) —
  [APK `lm0.c$C0800c`][apk]
- mos and mdrp describe the same 1-bit scheme; GB sends one queued request per ACK and has no timeout/retry in
  its protocol class — [mos][mos-hv2], [mdrp][mdrp], [GB][gb-proto]

#### BLE GATT (Sony base `5b83xxxx-6bc7-4802-8e9a-723ceca4bd8f`)

| Service | UUID | Use |
|---|---|---|
| TANDEM_V2_HPC_SERVICE | 5b833e20-… | Tandem v2 for headphones ("HPC"); characteristics TANDEM_HPC_TO_ACC `5b833c60` (phone → headset) and TANDEM_HPC_FROM_ACC `5b833c61` (headset → phone) |
| TANDEM_V2_MC_SERVICE / TANDEM_V1_MC_SERVICE | 5b833e21 / 5b833e23 | speakers ("MC"); `5b833c62` / `5b833c63` |
| TANDEM_V1_FIESTABLE_SERVICE, TANDEM_SSH_SERVICE | 5b833e24, 5b833e25 | party speakers, TV/SSH; not headphones |
| shared tandem characteristics | `5b833c90` READY_TO_START_INITIAL_COMMUNICATION, `5b833c91` WRITABLE_VALUE_LENGTH, `5b833c92` BEACON_INFO, `5b833c93` DETERMINE_MTU | session setup |
| LE_AUDIO_CAPABILITY_FOR_HPC | 5b833e27 | `5b833c68` LE Audio switch supported compatibility |
| BLE_PAIRING_HBS_HPC / _TWS_HPC, BLUETOOTH_CTKD_PAIRING, SOUND_CONNECT_TRIGGER_LE_PAIRING | 5b833e0c, 5b833e0d, 5b833e28, 5b833e29 | pairing helpers |
| MODEL_INFORMATION_SERVICE | 5b833e07 | `5b833c50` MODEL_FEATURES |
| **BLANC_FOTA_SERVICE** | **5b833e30** | firmware update: `5b833c80`–`5b833c85` (data sink, ready, size, SSS FOTA request/notify) — never touch |

Source: [APK `com.sony.songpal.ble.client.ServiceUuid`, `CharacteristicUuid`][apk]

- Route choice: a device paired as CLASSIC → SPP (RFCOMM). A device paired as LEA (LE Audio) whose LE Audio link is
  up → GATT; LEA device whose LE Audio is **not** connected → "Falling back to SPP via Classic BD address"
  (`ConnectionMode.SPP`) — [APK `platform.connection.connection.q0.e0`][apk]
- A Qualcomm LE Audio check also looks for SDP UUID `443cce33-e85d-4b85-8d53-6e319ede53ae` on the device —
  [APK `lm0.i.a()`, `connection.y0.n`][apk]
- v2 table 1 has an LEA command group (0x40–0x4D) with `ProtocolLEAConnectionType` SPP 0x00 / BLE_GATT 0x01 and
  `ProtocolLEAStreamingStatus` POWER_OFF/NONE/VIA_A2DP/VIA_LE_AUDIO — [APK `v2t1 Command`, kmp `…lea.*` enums][apk]

### Inferences
- Earshot's Sony link factory should be SDP-by-UUID only (like Soundcore), never a channel number. A fixed-channel
  probe like the Bose search must not run against Sony: Sony units with Airoha SoCs expose other channels (RACE,
  see section 4).
- For an MVP, RFCOMM is enough: even LE Audio models keep a Classic link for the app when LE Audio is not active.
  GATT Tandem can come later (it needs MTU handling and the "ready to start" handshake).

### Gaps
- The GATT session handshake (what is written to READY_TO_START_INITIAL_COMMUNICATION / DETERMINE_MTU) is not yet
  traced. (Planned next if budget allows.)

---

## 3. Commands (v1 and v2 tables)

### Takeaway
A v2 payload is `[command, inquiredType, …]`. Commands come in groups of ten per function block (GET/RET
CAPABILITY, GET/RET/SET/NTFY STATUS, GET/RET/SET/NTFY PARAM); "status" is mostly on/off or read-only state, "param"
is the user setting. Table 1 (data type 0x0C) holds the audio controls; table 2 (data type 0x0E) holds
multipoint/peripheral, voice guidance, safe listening, LE Audio, party and newer system items. **What a headset
supports is announced, not inferred**: `CONNECT_RET_SUPPORT_FUNCTION` lists FunctionType ids, and each FunctionType
selects one inquired type (e.g. FunctionType 0x6B → NCASM inquired type 0x17). The app then asks the matching
`GET_CAPABILITY` for ranges and preset lists, then `GET_STATUS`/`GET_PARAM` for values; the headset pushes `NTFY_*`
on every change (also from its own buttons). An MVP needs about a dozen message shapes.

### Cited Findings

#### Message conventions (v2)
- The app accepts only listed v2 protocol versions (u32 from `CONNECT_RET_PROTOCOL_INFO`): 0x01000000, 0x02000000,
  0x02000001, 0x02001000, 0x03000000–0x03000020, 0x03001001–0x03001004, 0x03002000–0x03002019 (with gaps),
  0x03003000–0x03003037 (with gaps); any other value ends init with `UNAVAILABLE_PROTOCOL_VERSION`. Version 0x03999999
  or any 0x04xxxxxx takes a newer "KMP" capability path — [APK `vv.e` (`f84924b`, `M()`, `Q()`)][apk]
- Init order in the app (v2): GET_PROTOCOL_INFO `00 00` → capability info (`02 00`, or a per-device cache) →
  DEVICE_INFO MODEL_NAME, FW_VERSION, SERIES_AND_COLOR_INFO (and INSTRUCTION_GUIDE on some protocol versions) →
  SUPPORT_FUNCTION on table 1 (`06 00`, if table 1 is enabled) and on table 2 (if enabled) → one GET_CAPABILITY per
  supported function — [APK `vv.e$CallableC1079e.call`][apk]; mos's init does the same order plus the values —
  [mos `HeadphonesV2.cpp`][mos-hv2]
- `CONNECT_RET_CAPABILITY_INFO` `[03, 00, version, len, UTF-8 string]` — [APK `in0.e`][apk]
- `CONNECT_RET_SUPPORT_FUNCTION` `[07, 00, n, (functionType, version) × n]`; the same shape on table 2 —
  [APK `in0.l`, `jo0.b`][apk]
- Value enums are not uniform: `EnableDisable` and KMP `ProtocolOnOff` use **0x00 = ENABLE/ON, 0x01 = DISABLE/OFF**,
  while `NcAsmOnOffValue` uses 0x00 = OFF, 0x01 = ON — [APK enums `v2.EnableDisable`, kmp `ProtocolOnOff`,
  `ncasm.param.NcAsmOnOffValue`][apk]

#### v2 table 1 command bytes (data type 0x0C)

| Block | Bytes | Notes |
|---|---|---|
| CONNECT | 00–07 | protocol info, capability info, device info, support function |
| GET_TEST | 0F | test, never send |
| COMMON | 10–13, 15, 18, 19 | codec, connection status, BLE setup, device special mode |
| POWER | 20–29 | battery 22/23/25; power off 24; auto-off 26–29 |
| UPDT | 30–39, 3E, 3F | **firmware update — block** |
| LEA | 40–4D | LE Audio switching |
| EQEBB | 50–5B | EQ, Clear Bass, sound effects |
| NCASM | 60–69 | noise cancelling / ambient sound |
| SENSE | 70–7B | adaptive control, heart-rate sensor |
| OPT | 80–89 | NC optimizer, sound field optimisation |
| ALERT | 90–99 | headset-to-app confirmations |
| PLAY | A0–A9 | playback, volume |
| SAR_AUTO_PLAY | B0–B9 | spatial audio / auto play |
| LOG | C4, C9 | **usage logging — never enable** |
| GENERAL_SETTING | D0–D9 | generic toggles/lists described by the headset |
| AUDIO | E0–E9 | DSEE, connection-quality mode, BGM, upmix |
| SYSTEM | F0–FD | buttons, speak-to-chat, wearing detection, Quick Access, voice assistant |

Source: [APK `tandemfamily.message.mdr.v2.table1.Command`][apk]; matches [mos `ProtocolV2T1.hpp`][mos-v2t1]

#### v2 table 2 command bytes (data type 0x0E)

| Block | Bytes |
|---|---|
| CONNECT | 06, 07 (support function only) |
| POWER | 20–29 (auto standby, caring charge with threshold, USB submersion) |
| PERI (peripheral: multipoint, pairing list, source switch) | 30–39, 3C, 3D |
| VOICE_GUIDANCE | 40–4B |
| SAFE_LISTENING | 50–5B |
| LEA | 60–69 |
| PARTY (incl. Auracast receiver) | 70–7D |
| SYSTEM | F0–FD |

Source: [APK `tandemfamily.message.mdr.v2.table2.Command`][apk]. There is no firmware-update block in table 2.

#### Support-function ids (v2 `FunctionType`, the byte inside `CONNECT_RET_SUPPORT_FUNCTION`)
Table 1 (selected): 0x20 BATTERY_LEVEL_INDICATOR, 0x21 LEFT_RIGHT_BATTERY, 0x22 CRADLE_BATTERY, 0x23 POWER_OFF,
0x24 AUTO_POWER_OFF, 0x25 AUTO_POWER_OFF_WITH_WEARING_DETECTION, 0x28/0x29/0x2A battery *_WITH_THRESHOLD,
0x2D BT_STANDBY, 0x2E STAMINA, 0x30–0x38 FW_UPDATE_* (several MTK/Tandem variants), 0x50 PRESET_EQ, 0x51 EBB,
0x52 PRESET_EQ_NON_CUSTOMIZABLE, 0x53 PRESET_EQ_AND_ULT_MODE, 0x54 SOUND_EFFECT, 0x55 CUSTOM_EQ, 0x57
PRESET_EQ_AND_ERRORCODE, 0x5A LR_BALANCE_CORRECTION, 0x61–0x6D noise/ambient variants (below), 0x70 AUTO_NCASM,
0x71 ADAPTIVE_CONTROL_WITH_PARAMETER_NOTIFICATION, 0x72–0x76 heart-rate sensor, 0x80–0x82 NC optimizer, 0x83 SOUND_FIELD_OPTIMIZATION,
0xA1–0xA4 playback controller variants, 0xB0 SAR, 0xB1 AUTO_PLAY, 0xC1–0xC3 log notifiers, 0xD1–0xD4 GENERAL_SETTING_1..4,
0xE1 CONNECTION_MODE_SOUND_QUALITY_CONNECTION_QUALITY, 0xE2 UPSCALING_AUTO_OFF, 0xE3 connection mode with LDAC status,
0xE7 CONNECTION_MODE_CLASSIC_AUDIO_LE_AUDIO, 0xE9 SOUND_LEAKAGE_REDUCTION, 0xED UPSCALING_AUTO_OFF_WITH_STATUS_DISABLE_REASON,
0xF0 VIBRATOR, 0xF1 PLAYBACK_CONTROL_BY_WEARING_REMOVING_HEADPHONE_ON_OFF, 0xF2 SMART_TALKING_MODE_TYPE1, 0xF3
ASSIGNABLE_SETTING, 0xF4 VOICE_ASSISTANT_SETTINGS, 0xF5 VOICE_ASSISTANT_WAKE_WORD_ON_OFF, 0xF6 WEARING_STATUS_DETECTOR,
0xF7 EARPIECE_SELECTION, 0xF8 CALL_SETTINGS, 0xF9 RESET_SETTINGS, 0xFA AUTO_VOLUME, 0xFB FACE_TAP_TEST_MODE, 0xFC
SMART_TALKING_MODE_TYPE2, 0xFD QUICK_ACCESS, 0xFE ASSIGNABLE_SETTING_WITH_LIMITATION, 0xFF HEAD_GESTURE_ON_OFF_TRAINING.
Table 2: 0x20 AUTO_STANDBY, 0x30 PAIRING_DEVICE_MANAGEMENT_CLASSIC_BT, 0x31 SOURCE_SWITCH_CONTROL, 0x32/0x33 pairing
management with class of device, 0x34 MUSIC_HAND_OVER_SETTING, 0x40–0x48 voice guidance variants, 0x50–0x55 safe
listening / volume limit, 0x60–0x64 LE Audio, 0x70–0x77 party/Auracast, 0xF0 WEARING_STATUS_CHECKER, 0xF1
REPEAT_TAP_TRAINING_MODE, 0xF2 QUICK_ACCESS_EASY_SETTING, 0xF3/0xF4 auto volume, 0xF5 SONY_VOICE_ASSISTANT, 0xF6
WEARING_POSITION, 0xF8 LINK_AUTO_SWITCH_FOR_HEADSETS, 0xF9 MIC_ON_OFF_BY_HEADPHONE_OPERATION, 0xFA FUNCTION_CHANGE.
Source: [APK `tandemfamily.message.mdr.v2.FunctionType`][apk] (full 200-entry list decoded locally).

#### Noise cancelling / ambient sound (NCASM, table 1)
- FunctionType → NCASM inquired type the app uses: 0x62 → 0x11, 0x64 → 0x13, 0x63 → 0x12, 0x65 → 0x14,
  0x68 → 0x15 (auto NC), 0x6A → 0x16 (dual/single), 0x6B → 0x17, 0x6C → 0x18 (with NC test mode), 0x6D → 0x19
  (noise adaptation) — [APK `vv.e$CallableC1079e.call`][apk]
- Every NCASM SET/RET/NTFY PARAM starts `[68|67|69, type, valueChangeStatus, totalEffect]`; valueChangeStatus
  UNDER_CHANGING 0x00 (slider still moving) / CHANGED 0x01, totalEffect = NcAsmOnOffValue OFF 0x00 / ON 0x01 (ON = the
  NC/ambient block is active; OFF = both off) — [APK `sn0.z0`][apk]
- Bytes after the header, per inquired type (byte offsets from the command byte) — [APK `un0.*`, `sn0.*1`][apk]:

| Type | Name | Length | Bytes 4.. |
|---|---|---|---|
| 0x01 | NC_ON_OFF | 5 | [4] NC on/off |
| 0x11 | NC_ON_OFF_AND_ASM_ON_OFF | 7 | [4] NC on/off, [5] ambient mode, [6] ASM on/off |
| 0x12 | NC_MODE_SWITCH_AND_ASM_ON_OFF | 7 | [4] NcValue, [5] ambient mode, [6] ASM on/off |
| 0x13 | NC_ON_OFF_AND_ASM_SEAMLESS | 7 | [4] NC on/off, [5] ambient mode, [6] ambient level |
| 0x14 | NC_MODE_SWITCH_AND_ASM_SEAMLESS | 7 | [4] NcValue, [5] ambient mode, [6] level |
| 0x15 | MODE_NC_ASM_AUTO_NC_MODE_SWITCH_AND_ASM_SEAMLESS | 8 | [4] NcAsmMode, [5] NcValue, [6] ambient mode, [7] level |
| 0x16 | MODE_NC_ASM_DUAL_SINGLE_NC_MODE_SWITCH_AND_ASM_SEAMLESS | 8 | same as 0x15 |
| 0x17 | MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS | 7 | [4] NcAsmMode, [5] ambient mode, [6] level |
| 0x18 | MODE_NC_NCSS_ASM_DUAL_… | 7 | [4] NcNcssAsmMode, [5] ambient mode, [6] level |
| 0x19 | MODE_NC_ASM_DUAL_…_NA (noise adaptive) | 9 | as 0x17 + [7] noise-adaptive on/off, [8] sensitivity |
| 0x21 | ASM_ON_OFF | 6 | [4] ambient mode, [5] ASM on/off |
| 0x22 | ASM_SEAMLESS | 6 | [4] ambient mode, [5] level |
| 0x30 | NC_AMB_TOGGLE | 3 | [2] Function cycled by the NC/AMB button (0x01 NC_ASM_OFF, 0x02 NC_ASM, 0x03 NC_OFF, 0x04 ASM_OFF) |

- Enums: NcAsmMode NC 0x00 / ASM 0x01; AmbientSoundMode NORMAL 0x00 / VOICE 0x01 ("focus on voice"); NcValue OFF 0,
  ON_SINGLE 1, ON_DUAL 2, AUTO 3, AUTO_SINGLE 4, AUTO_DUAL 5; NoiseAdaptiveSensitivity STANDARD 0, HIGH 1, LOW 2 —
  [APK `ncasm.param.*`][apk]; the same names and values in [mos][mos-v2t1]
- `NCASM_RET_CAPABILITY` for the "seamless" types: `[61, type, n, (ambientMode, min, max, step) × n]` — the ambient
  level range comes from the headset, not a constant; for NC_AMB_TOGGLE it is `[61, 30, key]` — [APK `un0.b`,
  `un0.c`, `cn0.b` (`Range{mMin, mMax, mStep}`)][apk]. mos caps the level at 20 and pluto (v1) at 19 —
  [mos][mos-h], [pluto][pluto-const]
- Wind noise reduction and "auto NC" do not have their own NCASM fields here: wind/auto modes are NcValue AUTO* in
  0x15/0x16, and NcNcssAsmMode (0x18) adds the "NCSS" mode — [APK `ncasm.param.NcValue`, `NcNcssAsmMode`][apk];
  GB lists "wind reduction" as one of its ASM options for older models — [GB support page][gb-page]

#### Battery (POWER, table 1)
- GET `[22, type]`, reply `[23, type, …]`, push `[25, type, …]` — [APK `yn0.c`, `yn0.k0`, `yn0.r0`, `yn0.m0`][apk]

| Type | Layout after `[cmd, type]` |
|---|---|
| 0x00 BATTERY | level 0–100, charging |
| 0x01 LEFT_RIGHT_BATTERY | L level, L charging, R level, R charging |
| 0x02 CRADLE_BATTERY | level, charging |
| 0x08 BATTERY_WITH_THRESHOLD | level, charging, threshold 0–100 |
| 0x09 LR_BATTERY_WITH_THRESHOLD | L, L chg, R, R chg, L threshold, R threshold |
| 0x0A CRADLE_BATTERY_WITH_THRESHOLD | level, charging, threshold |

  Charging: NOT_CHARGING 0x00, CHARGING 0x01, UNKNOWN 0x02, CHARGED 0x03 — [APK `power.param.BatteryChargingStatus`][apk]
- v1 battery uses COMMON 0x10/0x11/0x13 instead; its charging enum is NOT_CHARGING 0x00, CHARGING 0x01, UNKNOWN 0xF0
  — [APK `v1.table1.Command`, kmp `v1.table1.power.ProtocolBatteryChargingStatus`][apk]; [mos `ProtocolV1T1.hpp`][mos-v1t1]

#### Power off and auto power off (POWER, table 1)
- Power off: `POWER_SET_STATUS [24, 03, 01]` (USER_POWER_OFF). The enum also has FACTORY_POWER_OFF 0xFF; the app's
  validator accepts only 0x01 — [APK `yn0.b1`, `power.param.PowerOffSettingValue`][apk]
- Auto power off: capability `[21, 04, n, element × n]`; set `[28, 04, current, lastSelected]`; type 0x05 is the
  variant with wearing detection. Elements: 5 min 0x00, 30 min 0x01, 60 min 0x02, 180 min 0x03, 15 min 0x04,
  "when removed from ears" 0x10 (type 0x05 only), disabled 0x11 — [APK `yn0.y`, `yn0.v0`,
  `AutoPowerOffElements`, `AutoPowerOffWearingDetectionElements`][apk]; same values in [mos][mos-v2t1]

#### Equalizer (EQEBB, table 1)
- PRESET_EQ (0x00), PRESET_EQ_NONCUSTOMIZABLE (0x02), PRESET_EQ_AND_ERRORCODE (0x04): capability
  `[51, type, bandCount, levelSteps, presetCount, (presetId, nameLen, name) × presetCount]` — [APK `jn0.z`, `kn0.e`][apk]
- Param (get 56 / ret 57 / set 58 / ntfy 59): `[cmd, type, presetId, n, value × n]`, each value an unsigned step
  0–255 — [APK `kn0.c`, `jn0.x0`, `jn0.j0`][apk]. mos: the 5-band layout is 6 values with Clear Bass first, each sent as
  dB + 10 (±10); the 10-band layout sends dB + 6 (±6) — [mos `Headphones.cpp`][mos-h]
- CUSTOM_EQ (0x31): capability `[51, 31, bandCount, levelSteps]` (4 bytes); param `[cmd, 31, n, value × n]` —
  [APK `jn0.w`, `jn0.s0`, `kn0.a`][apk]
- EQEBB_GET_EXTENDED_INFO (5A/5B) returns per-band info: type NO_INFORMATION 0x00 / HZ 0x01 / KHZ 0x02 /
  SPECIFIC_INFORMATION 0x10 + a u16 BE value (SPECIFIC 0x0001 = CLEAR_BASS) — [APK `kn0.b`, `EqBandInformationType`,
  `SpecificInformationType`][apk]
- EqPresetId: OFF 0x00, ROCK 01, POP 02, JAZZ 03, DANCE 04, EDM 05, R&B/HIP-HOP 06, ACOUSTIC 07, BRIGHT 10, EXCITED 11,
  MELLOW 12, RELAXED 13, VOCAL 14, TREBLE 15, BASS 16, SPEECH 17, GAMING_EQ 20, FPS_1–3 21–23, HEAVY 30, CLEAR 31,
  HARD 32, SOFT 33, CUSTOM A0, USER_SETTING1–5 A1–A5 — [APK `eqebb.param.EqPresetId`][apk]; mos adds ARTIST_COLLAB
  0xB0–0xBB — [mos][mos-v2t1]
- WH-1000XM6 firmware 3.0.0 accepts only HEAVY/CLEAR/HARD/SOFT, CUSTOM and USER presets ("devices silently ignore
  presets they did not advertise") — [mos WH-1000XM6 doc][mos-xm6], [mos][mos-h]

#### Other v2 settings (beyond the MVP, layouts verified in the app)
- **DSEE / upscaling (AUDIO, type 0x01; 0x0B variant has a disable reason)**: capability `[E1, 01, UpscalingType]`
  (DSEE_HX 0, DSEE 1, DSEE_HX_AI 2, DSEE_ULTIMATE 3); set `[E8, 01, OFF 0x00 | AUTO 0x01]` — [APK `fn0.e0`, `fn0.i1`][apk]
- **Sound quality vs connection priority (AUDIO 0x00; 0x02 = with LDAC status)**: set `[E8, 00|02, PriorMode]`,
  SOUND_QUALITY_PRIOR 0, CONNECTION_QUALITY_PRIOR 1, LOW_LATENCY_PRIOR_BETA 2 — [APK `fn0.b1`, `fn0.d1`, `PriorMode`][apk]
- **Codec in use**: COMMON GET_STATUS `[12, 02]` → AudioCodec SBC 0x01, AAC 0x02, LDAC 0x10, aptX 0x20, aptX HD 0x21,
  LC3 0x30 — [APK `common.param.AudioCodec`][apk]
- **Speak-to-chat (SYSTEM 0x0C = SMART_TALKING_MODE_TYPE2; 0x02 = TYPE1 older)**: set `[F8, 0C, on/off, on/off]`
  using `OnOffSettingValue` **ON 0x00 / OFF 0x01**; mos names the second byte "preview mode" (not confirmed in the
  app); ext param `[FC, 0C, DetectSensitivity AUTO 0/HIGH 1/LOW 2, ModeOutTime FAST 0/MID 1/SLOW 2/NONE 3]`; status push
  `[F5, 0C, EnableDisable, active 0/1]` — [APK `do0.d2`, `do0.e1`, `do0.n1`, `eo0.e`][apk]
- **Pause when taken off ("control by wearing")**: newer KMP code path, SYSTEM type 0x01: `[F2, 01]` status,
  `[F6, 01]` param, set on `F8 01` with a `ProtocolOnOff` byte — [APK kmp `V2ControlByWearingRepository`,
  `nj0.n0`][apk]. Wearing state itself: SYSTEM 0x06 WEARING_STATUS_DETECTOR, table 2 SYSTEM 0xF0 WEARING_STATUS_CHECKER
  — [APK `SystemInquiredType`, FunctionType][apk]
- **Touch/button assignment (SYSTEM 0x03)**: set `[F8, 03, n, preset × n]`, one Preset per key in capability order;
  Preset values include AMBIENT_SOUND_CONTROL 0x00, VOLUME_CONTROL 0x10, PLAYBACK_CONTROL 0x20, TRACK_CONTROL 0x21,
  VOICE_RECOGNITION 0x30, GOOGLE_ASSIST 0x31, AMAZON_ALEXA 0x32, AMBIENT_SOUND_CONTROL_QUICK_ACCESS 0x35,
  QUICK_ACCESS 0x36 — [APK `do0.w1`, `fo0.g`, `system.param.Preset`][apk]. Quick Access has its own list
  `[F8, 0D, n, …]` — [APK `do0.b2`][apk]. GB: switching the voice assistant reboots the headset — [GB][gb-proto]
- **Headset-described toggles (GENERAL_SETTING D1–D4)**: the capability carries a title the app matches by name, e.g.
  `MULTIPOINT_SETTING` ("connect to 2 devices"), `TOUCH_PANEL_SETTING`, `SIDETONE_SETTING`, `TAP_SENSITIVITY_SETTING`,
  `VOICE_GUIDANCE_SETTING`; boolean set `[D8, Dx, 00, ON 0x00 | OFF 0x01]` — [APK `ln0.h`, `ln0.p`,
  `j2objc.cap.GsTitleTitle`][apk]. This is how multipoint on/off is exposed, so it maps directly onto Earshot's
  `VendorControl` list.
- **Multipoint / source management (table 2 PERI)**: paired-device list `[37, 00, …]` (17-byte ASCII address +
  name per device); connect/disconnect a paired source `[3C, 00|02, action, "AA:BB:CC:DD:EE:FF"]` with action
  DISCONNECT 0x00 / CONNECT 0x01 / **UNPAIR 0x02**; pairing mode `[34, 00|02, INQUIRY_SCAN 0x01, ENABLE]`;
  SOURCE_SWITCH_CONTROL `[38, 01, ON 0x00 | OFF 0x01]` — [APK `oo0.y`, `oo0.e0`, `oo0.c0`, `oo0.s`][apk]
- **Voice guidance (table 2)**: on/off `[48, 00|01|03, ON 0x00 | OFF 0x01]`; volume `[48, 20|21, −2…+2 signed,
  on/off]` — [APK `ap0.i0`, `ap0.l0`][apk]
- **Playback / volume (PLAY)**: music volume `[A8, 20, volume]` (mos range 0–30); control `[A4, 01..03, ENABLE,
  PlaybackControl]` (PAUSE 1, TRACK_UP 2, TRACK_DOWN 3, PLAY 7) — [APK `wn0.d0`, `wn0.f0`][apk], [mos][mos-h]
- **Alerts**: the headset can ask the app to confirm an action (e.g. "disconnect caused by changing multipoint",
  "battery consumption increases") with ALERT NTFY `99`; the app answers ALERT_SET_PARAM `[98, type, action]`
  (NEGATIVE 0 / POSITIVE 1) — [APK `AlertInquiredType`, `AlertAction`, kmp `AlertSetFixedMessage 98 00`][apk]. GB
  notes that "connect two devices" needs such a pre-request — [GB][gb-proto]

#### v1 table (WH-1000XM4 and older), differences that matter
- Battery: COMMON `[10, type]` / `[11, type, …]` / push `[13, type, …]`, same per-type layouts as v2 —
  [APK `tm0.s0`, `vm0.h`, `vm0.x`][apk]
- NC/ASM: type 0x02 NOISE_CANCELLING_AND_AMBIENT_SOUND_MODE `[68, 02, NcAsmEffect, NcAsmSettingType, ncValue,
  AsmSettingType, AsmId, level]`; NcAsmEffect OFF 0, ON 1, ADJUSTMENT_IN_PROGRESS 0x10, ADJUSTMENT_COMPLETION 0x11;
  NcAsmSettingType ON_OFF 0 / LEVEL_ADJUSTMENT 1 / DUAL_SINGLE_OFF 2; NcDualSingleValue OFF 0 / SINGLE 1 / DUAL 2;
  AsmId NORMAL 0 / VOICE 1 — [APK `tm0.i3`, `vm0.a0`, v1 enums][apk]; same in [pluto][pluto-ser] (level capped at 19)
- Inquired types are shifted by one versus v2: EqEbb NO_USE 0, PRESET_EQ 1, EBB 2; Audio CONNECTION_MODE 1,
  UPSCALING 2; System VIBRATOR 1, POWER_SAVING 2, AUTO_POWER_OFF 4, SMART_TALKING_MODE 5, ASSIGNABLE 6 —
  [APK v1 enums][apk], [mos `ProtocolV1T1.hpp`][mos-v1t1]
- v1 table 2 has only PERIPHERAL 0x30–0x3D and VOICE_GUIDANCE 0x40–0x49 — [APK `v1.table2.Command`][apk]

### Inferences
- The minimum viable Sony driver needs: protocol info, device info (model name, FW), support functions (T1),
  battery GET + push (0x00/0x01/0x02 types), NCASM capability + param GET/SET + push for whichever inquired type the
  support list selects, preset/custom EQ capability + param, auto power off, and the ACK/seq layer. Everything else
  can come through the GENERAL_SETTING items as generic toggles.
- Build commands from the capability, never from a model table: the ambient level range, EQ band count and step
  count, auto-off choices and preset list all come from the headset.

### Gaps
- Exact meaning of the second speak-to-chat byte and of the NCASM capability for non-seamless types was not traced.
- The KMP-path serializers for "set" messages (pause on removal) were only partly decoded (the leading bytes, not
  the value byte order).
- No real captures were available, so every layout here is from the app's validators and builders, not from wire
  traffic.

---

## 4. What must be blocked

### Takeaway
On the MDR channel itself, block every firmware-update, test, log and reset command and allow-list writes by
`[command, inquiredType]`, like Earshot's Bose `WriteGuard`. Outside MDR, never open the BLE FOTA service and never
open the Airoha RACE channel that many Sony models also expose.

### Cited Findings
- **Firmware update, table 1 UPDT 0x30–0x39, 0x3E UPDT_TRANSFER_DATA, 0x3F UPDT_NTFY_MESSAGE** (v2), and v1 UPDT
  0x34–0x37. The Tandem FOTA commands include ENTER_FW_UPDATE_MODE 0x01, START_TRANSFER 0x03, EXECUTE_FW_UPDATE 0x06;
  update methods include MTK "transfer without disconnection" and a repair mode — [APK `v2.table1.Command`,
  kmp `ProtocolTandemFotaCommand`, `UpdtInquiredType`][apk]
- **BLE FOTA**: service `5b833e30-…` (BLANC_FOTA) with characteristics `5b833c80`–`5b833c85` — [APK `ServiceUuid`,
  `CharacteristicUuid`][apk]
- **Test commands**: v2 `GET_TEST 0x0F`, v1 `TEST_COMMAND 0xFF`; NCASM inquired type 0x40 NC_TEST_MODE (start/finish);
  SYSTEM 0x0B FACE_TAP_TEST_MODE; SYSTEM 0x10 HEAD_GESTURE_TRAINING; table 2 SYSTEM 0x01 REPEAT_TAP_TRAINING_MODE;
  SENSE heart-rate sensor test — [APK `Command`, `NcAsmInquiredType`, `SystemInquiredType`, kmp
  `ProtocolSenseHeartRateSensorTestOperation`][apk]
- **Logging**: LOG_SET_STATUS 0xC4 (ACTION_LOG 0x00, TIME_SERIES_OPERATIONLOG 0x01, SOUND_DROPOUT 0x02) switches on
  usage logs pushed as LOG_NTFY_PARAM 0xC9 — [APK `LogInquiredType`][apk]. mos's init enables the time-series log
  like the app does — [mos][mos-hv2]; Earshot should not.
- **Reset**: SYSTEM 0x09 RESET_SETTINGS with ResetType SETTINGS_ONLY 0x00 / **FACTORY_RESET 0x01** — [APK
  `system.param.ResetType`][apk]
- **Power off**: only `[24, 03, 01]` (USER_POWER_OFF) is legitimate; FACTORY_POWER_OFF 0xFF exists in the enum —
  [APK `PowerOffSettingValue`, `yn0.b1`][apk]
- **Unpair**: table 2 PERI `[3C, …, 02, address]` removes a paired device — [APK `oo0.y`, `ConnectivityActionType`][apk]
- **Disconnecting/reconnecting changes**: LE Audio / Classic mode switch (AUDIO 0x05, LEA 0x48/0x4C) and voice-assistant
  changes restart the link or the headset — [APK `AlertMessageType` "disconnect caused by connection mode change"][apk],
  [GB][gb-proto]
- **Data uploads**: EQEBB 0x36 CUSTOMIZABLE_SOUND_EFFECT_EXTERNAL_UPDATE, voice-guidance and Sony voice assistant
  "MTK transfer" language-pack variants — [APK `EqEbbInquiredType`, `VoiceGuidanceInquiredType`,
  table 2 `SystemInquiredType`][apk]
- **Airoha RACE**: ERNW confirmed the unauthenticated RACE debug protocol (CVE-2025-20700/20701/20702) on WH-1000XM4,
  WH-1000XM5, WH-1000XM6, WF-1000XM3/XM4/XM5, LinkBuds S, ULT Wear, WH-CH720N, WH-CH520, WH-XB910N, WF-C500, WF-C510 and
  WI-C100 (list said to be incomplete); the RACE Toolkit shows firmware-update access on WH-CH720N and WH-1000XM6. I
  found no Sony advisory or list of fixed firmware — [ERNW][ernw], [ERNW white paper][ernw-wp], [RACE Toolkit][racetk]

### Inferences
- Allow-list for an MVP: GETs on blocks 0x00–0x29, 0x50–0x5B, 0x60–0x69, 0xD0–0xD9, 0xE0–0xE9, 0xF0–0xFD (both tables);
  SETs only for NCASM param (excluding type 0x40), EQEBB param 0x00/0x02/0x04/0x31, POWER auto-off 0x04/0x05,
  POWER_SET_STATUS `03 01`, GENERAL_SETTING boolean, AUDIO 0x00/0x01/0x02, SYSTEM 0x01/0x03/0x0C and ALERT replies.
  Everything else is refused before it reaches the socket.

### Gaps
- Whether Sony patched RACE on any model, and on which firmware, is unknown.

---

## 5. Open-source and community knowledge

### Takeaway
mos9527/SonyHeadphonesClient (MIT) is the best match for the app: its V2 tables use the same names and values as
the app's own enums and it supports the newest models (WH-1000XM6, WF-1000XM6, LinkBuds Clip). Gadgetbridge (AGPL)
has the widest model list and real-device quirks. The app adds what they lack: the reversed-UUID workaround, the ACK
timing, the support-function → inquired-type map, the protocol-version whitelist, GATT UUIDs, and the FOTA/test ids.

### Cited Findings
- mos `libmdr` has V1/V2 × table 1/table 2 headers, BLE and RFCOMM transports, per-device notes (WF-1000XM5,
  WF-1000XM6, WF-C510, WF-C700N, WF-L910, WF-LC900, WF-LS900N, WH-1000XM3, WH-1000XM5, WH-1000XM6, WH-CH720N) and replay
  fixtures (WF-1000XM5 6.1.0, WF-LC900 2.0.3); its own README says V1 ("XM4 and earlier") support is still in progress
  — [mos tree][mos-tree], [mos][mos]
- Plutoberth (MIT, archived 2025-07-19): WH-1000XM3 full, WH-1000XM4 partial; V1 NC/ASM, VPT, frame codec —
  [pluto][pluto]
- GB (AGPL, facts only): 19 Sony coordinators (LinkBuds, LinkBuds S, WF-1000XM3/XM4/XM5, WF-C500/C510/C700N/C710N,
  WF-SP800N, WH-1000XM2/XM3/XM4/XM5/XM6, WH-CH720N, WH-ULT900N, WI-C100, WI-SP600N); only V1 and V2 implementations;
  XM6 marked experimental with EQ (a comment says 9 bands), DSEE and touch still TODO — [GB coordinators][gb-coord],
  [GB XM6][gb-xm6], [GB support page][gb-page]
- mdrp (no licence): same framing and the "NTFY only after the matching GET" rule — [mdrp][mdrp], [mdrp packets][mdrp-packets]
- Where the app differs from or extends the community sources:
  - Community code picks v1/v2 by reply length (GB) or by caller choice (mos); the app picks by SDP UUID first and
    accepts byte-reversed UUIDs — [APK `ep0.a`][apk]
  - The app retries every 750 ms up to 10 times and then closes the link; GB has no retry — [APK `om0.e`][apk], [GB][gb-proto]
  - The app refuses unknown v2 protocol versions; community code does not — [APK `vv.e`][apk]
  - mos sends the time-series log ENABLE in init (copied from the app); Earshot should skip it — [mos][mos-hv2]

### Inferences
- Port from mos (MIT, with attribution) for layouts and test fixtures; use GB only as a list of real-world quirks.

### Gaps
- mos's EQ, DSEE and speak-to-chat struct definitions and its table-2 enums were not read in full by the web pass.

---

## 6. Effort and quirks for Earshot

### Takeaway
A Sony driver is medium effort and lower risk than Bose: one framing, a self-describing capability system, and a
well-documented MIT reference. The MVP (connect, battery, NC/ambient, EQ, auto-off) is about the size of the
Soundcore driver, plus a FunctionType table and an allow-list WriteGuard. Ship it as Experimental with a virtual
device built from mos's replay fixtures.

### Cited Findings
- Sequence/ACK: 1-bit seq, ACK carries 1 − seq, resend after 750 ms (10×), duplicates ACKed but ignored —
  [APK `lm0.c`, `om0.e`][apk]
- Escaping must cover 0x3C/0x3D/0x3E inside the length field and checksum too (the whole body is escaped) —
  [APK `qm0.b`][apk]
- Talking too soon after the socket opens fails on some units; GB waits 500 ms — [GB][gb-support]
- Firmware differences are real: XM6 FW 3.0.0 silently ignores unadvertised EQ presets — [mos XM6][mos-xm6]
- Android may report the service UUIDs byte-reversed — [APK `ep0.a`][apk]
- LE Audio: when the headset streams over LE Audio the app uses GATT; with Classic it uses RFCOMM — [APK `q0.e0`][apk]

### Inferences
- **Channel ownership**: like Bose, only one client can hold the RFCOMM link; Sound Connect connecting first will
  likely make Earshot's connect fail (inference from the single-socket design; no source tested this).
- Route by the SDP UUIDs only; do not run the Bose channel probe against Sony, because Airoha-based units expose a
  RACE channel too.
- Map Sony to Earshot units: NC/ambient as modes (NC, Ambient, Off) plus ambient level 1…max and "focus on voice";
  EQ as preset list + bands with the headset's step count; GENERAL_SETTING items and speak-to-chat as `VendorControl`
  toggles; battery L/R/case into `HeadphoneState.battery` parts.
- Handle ALERT pushes (answer POSITIVE only for actions the user started), or some settings never apply.

### Gaps
- Real captures from a Sony headset (for the virtual device and to confirm layouts) are still needed; mos's fixtures
  are the best stand-in.

---

[apk]: file:///C:/Users/Hellz/AppData/Local/Temp/claude/C--Users-Hellz-Desktop-earshot/8ff49ff3-c9c6-4f41-b13e-33c2f63035a9/scratchpad/apks/com.sony.songpal.mdr/extracted/base.apk
[mos]: https://github.com/mos9527/SonyHeadphonesClient
[mos-tree]: https://api.github.com/repos/mos9527/SonyHeadphonesClient/git/trees/HEAD?recursive=1
[mos-v2t1]: https://raw.githubusercontent.com/mos9527/SonyHeadphonesClient/HEAD/libmdr/include/mdr/ProtocolV2T1.hpp
[mos-v1t1]: https://raw.githubusercontent.com/mos9527/SonyHeadphonesClient/HEAD/libmdr/include/mdr/ProtocolV1T1.hpp
[mos-proto]: https://raw.githubusercontent.com/mos9527/SonyHeadphonesClient/HEAD/libmdr/include/mdr/Protocol.hpp
[mos-hv2]: https://raw.githubusercontent.com/mos9527/SonyHeadphonesClient/HEAD/libmdr/src/HeadphonesV2.cpp
[mos-h]: https://raw.githubusercontent.com/mos9527/SonyHeadphonesClient/HEAD/libmdr/src/Headphones.cpp
[mos-xm6]: https://raw.githubusercontent.com/mos9527/SonyHeadphonesClient/HEAD/docs/device-support/WH-1000XM6.md
[mos-xm6wf]: https://raw.githubusercontent.com/mos9527/SonyHeadphonesClient/HEAD/docs/device-support/WF-1000XM6.md
[pluto]: https://github.com/Plutoberth/SonyHeadphonesClient
[pluto-ser]: https://raw.githubusercontent.com/Plutoberth/SonyHeadphonesClient/master/Client/CommandSerializer.cpp
[pluto-const]: https://raw.githubusercontent.com/Plutoberth/SonyHeadphonesClient/master/Client/Constants.h
[gb-proto]: https://codeberg.org/Freeyourgadget/Gadgetbridge/raw/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/sony/headphones/SonyHeadphonesProtocol.java
[gb-support]: https://codeberg.org/Freeyourgadget/Gadgetbridge/raw/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/sony/headphones/SonyHeadphonesSupport.java
[gb-coord]: https://codeberg.org/api/v1/repos/Freeyourgadget/Gadgetbridge/contents/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/sony/headphones/coordinators?ref=master
[gb-xm6]: https://codeberg.org/Freeyourgadget/Gadgetbridge/raw/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/sony/headphones/coordinators/SonyWH1000XM6Coordinator.java
[gb-page]: https://gadgetbridge.org/gadgets/headphones/sony/
[mdrp]: https://github.com/AndreasOlofsson/mdr-protocol
[mdrp-packets]: https://raw.githubusercontent.com/AndreasOlofsson/mdr-protocol/master/MDR_packets.md
[ernw]: https://insinuator.net/2025/06/airoha-bluetooth-security-vulnerabilities/
[ernw-wp]: https://ernw.de/en/whitepapers/issue-74.html
[racetk]: https://github.com/auracast-research/race-toolkit
[sony-compat]: https://helpguide.sony.net/mdr/hpc/v1/en/contents/TP0001548861.html
[sony-lea]: https://helpguide.sony.net/mdr/2984/v1/en/contents/TP1001872731.html
[gsma]: https://www.gsmarena.com/sony_teases_audiorelated_launch_event_for_next_week-news-71141.php
[axp]: https://audioxpress.com/news/sony-electronics-launches-wf-1000xm6-truly-wireless-earbuds
[techinsights]: https://www.eetasia.com/express/techinsights-2024030215/
