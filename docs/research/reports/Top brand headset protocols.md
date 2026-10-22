# Four brands fit sockets Earshot already opens

Sennheiser, Sony, Soundcore and Skullcandy's Bose-tuned Method earbuds all put their control protocol on a classic RFCOMM service that Android finds by SDP UUID. Earshot already has that transport in `RfcommTransport.connectService` ([RfcommTransport.kt][rfcomm]), so 0.7 can add all four as Experimental drivers without new platform work. JBL is the exception: **104 of the 123 products in its app connect over BLE, most of them through a Harman GATT service**, so JBL waits for a GATT link in 0.8 ([JBL notes §1][jbl-n]). The fastest safe order for 0.7 is:

1. a read-only Soundcore view for every model on the vendor UUID (S);
2. Method 360/540 on the existing Bragi stack (S);
3. a Sennheiser GAIA v3 driver, pulled forward from 0.8 (S–M);
4. Sony's v2 tables (M);
5. Soundcore write support, one model family at a time as captures arrive.

0.8 then adds the GATT transport, JBL's two Harman stacks and, only after an explicit decision, an allow-listed Airoha RACE codec that JBL's and Skullcandy's Airoha lines can share.

Safety comes from the design, not from guarding single commands. Every brand keeps firmware update on a separate service or command block. Three of the five punish channel probing:
- one community report says GAIA frames on an Accentum's iAP2 channel powered it off;
- Soundcore's Space 2 keeps firmware update on RFCOMM channel 12;
- Sony's Airoha units expose RACE channels.

So the whole safety design is two rules: connect by UUID only, and pass every write through a per-vendor allow-list keyed on command, sub-type and body length.

How much can ship without hardware depends on whether the protocol describes itself. Sony, Sennheiser and JBL's 2025+ stack announce their features. Soundcore and JBL's older stack need per-model layouts that only captures can confirm.

Every finding here comes from static analysis of the official Android apps plus open sources. Nothing was run against a headset. The only on-device evidence is other people's captures: Momentum 4/5, Wave Buds 2, Space 2 and OpenSCQ30's owner captures.

Compared with earlier research, these findings:
- confirm the Sony plan and the shared-GAIA-codec idea;
- move Sennheiser into 0.7;
- replace JBL's "gen 3 unknown" with a documented stack that needs GATT;
- correct two identification assumptions: the `0cf12d31-…` UUID base is not unique to Soundcore, and the forced-Bose path still opens the `deca-fade` record, which is probably Apple iAP2.

## RFCOMM by UUID reaches four brands; JBL alone needs GATT

The five apps analysed were:
- Sony Sound Connect 13.3.0;
- Soundcore 5.0.32 (its Java is encrypted, so it yielded only the manifest and resources);
- Sennheiser Smart Control 4.9.3;
- JBL Headphones 5.37.10;
- Skullcandy 5.0.6.

The table compares them on what decides driver cost:
- the transport, and whether Earshot has it;
- how a model is identified;
- whether the headset announces its own capabilities;
- whether a permissive reference exists that Earshot may port and test against.

The per-brand sections below give the evidence. Each brand's notes file has the full byte tables.

| Brand | 2020+ ANC scope | Control stack | Transport | Identify by | Self-describing | Best permissive reference | MVP size |
|---|---|---|---|---|---|---|---|
| **Sennheiser** | 9 GAIA v3 models 2021–24 plus Momentum 5; 2020 MTW2 on legacy GAIA | Qualcomm GAIA v3, vendor 0x0495 + core 0x001D | RFCOMM, secure, UUID `a2129ff3-…-ec`; BLE for CX Plus/CX 500 | name map, then `0x1206` model string | yes, feature list `0x0001` | momentum4-control (MIT, M4 + M5 captures) | S–M |
| **Sony** | ~15 models; v2 from WF-1000XM4 on, v1 on WH-1000XM4 | Tandem/MDR, tables v1 or v2 | RFCOMM, secure, SDP UUID (v2 `956C7B26-…`, v1 `96CC203E-…`); GATT only while LE Audio is up | UUID picks the table set; MODEL_NAME string | yes, support-function list + capabilities | SonyHeadphonesClient (MIT, replay fixtures) | M |
| **Soundcore** | ~40 documented layouts, 5+ families | `08 EE` frames, a full-state layout per model | RFCOMM by vendor UUID (gen 2) or SPP (gen 1) | UUID suffix, name, serial prefix | no | soundcorebridge (MIT, Space 2 only) | S read-only; M + S per model |
| **JBL** | H-AA 2021–24, H-P4 2025+, A-RACE on Airoha | Harman AA, Harman Protocol 4, Airoha RACE + Harman IDs | BLE GATT `65786365-…` (H-AA/H-P4); Airoha SPP or GATT | BLE advert 0x0ECB / 0xFDDF → PID | H-P4 yes; H-AA no | jbl-control (MIT, Wave Buds 2) | L overall |
| **Skullcandy** | 14 ANC products, 13 in the app | Bragi BOS-RPC, Bragi BOS-AT, Airoha RACE, BES TOTA | RFCOMM by Bragi UUID `54C5BF4C-…` for BOS-RPC | name + SKU from `dev_info` | n/a | none | S for Method; L for the rest |

Two patterns cut across the table.

**Transport decides when a driver can ship.** Every main control path except JBL's uses a public RFCOMM socket opened by UUID. JBL's main stacks need a GATT client, BLE scanning and handling of rotating LE addresses. This confirms the accelerators report's "transport risk" criterion ([accelerators][accel]): all five brands are reachable through public APIs, but at two different platform costs.

**Self-description decides how far an Experimental driver can be trusted without hardware.** A Sony or Sennheiser driver can ask the headset what it supports and take ranges from the replies. A Soundcore driver must guess the layout of a 90–165-byte state blob from someone else's capture. That is why Soundcore writes should follow owner captures, while Sony and Sennheiser can ship against virtual devices built from MIT fixtures.

## Brand by brand: what a minimum driver sends and refuses

### Sennheiser: one GAIA v3 dialect covers nine models and the Momentum 5

These models run Qualcomm GAIA v3, with Sennheiser commands under vendor **0x0495** and Qualcomm core commands under **0x001D**:
- Momentum 4;
- Momentum True Wireless 3 and 4;
- Accentum, Accentum Plus and Accentum TW;
- Momentum Sport;
- CX Plus TW and CX 500BT.

The command IDs are identical across every GAIA v3 model file in Smart Control 4.9.3; only the declared properties differ ([Sennheiser notes §1][sen-n]). The 2020 Momentum True Wireless 2 and the HD 4x0BT line use GAIA v1/v2 under 0x0494, and the HD line has no ANC control, so both are low value.

Sennheiser's own FAQ says Smart Control will not support products launched after December 2024 ([Sennheiser UK FAQ](https://uk.sennheiser-hearing.com/pages/smartcontrol)). Those products are in the new Smart Control Plus, which was not analysed. MIT-licensed captures show a **Momentum 5 on firmware 6.21.5 answering the same 0x0495 commands**, with an 8-band ±6 dB EQ and extra head-tracking features ([M4C M5 captures](https://github.com/Ariazonaa/momentum4-control/tree/HEAD/tests/CapturedPackets/M5)).

**Transport.** On Android the app opens a **secure RFCOMM socket by SDP UUID `a2129ff3-081b-4c45-8afe-469d9c4842ec`**. There is no handshake and no authentication ([Sennheiser notes §2][sen-n]). The frame is `FF 03 00 len vendor16 cmd16 payload`, big-endian, with no checksum. A battery read `FF 03 00 00 04 95 06 03` comes back as `… 07 03 2A`, which is 42 % ([sennheiser-desktop-client][sdc]).

Two groups use BLE instead, with unframed GAIA on service FDCE or FCFE (write `6333133B-…`, notify `6333133C-…`):
- the CX Plus TW and CX 500BT;
- Momentum 4 and MTW3 below firmware 2.11.26.

**Channels.** Channel numbers vary: the Accentum uses 15, and the Momentum 4 has two GAIA records, on channels 1 and 2. One community report says GAIA frames sent to the Accentum's iAP2 channel 14 powered the headset off ([sennheiser-desktop-client][sdc]; [M4C resolver](https://github.com/Ariazonaa/momentum4-control/blob/HEAD/src/Momentum4.Bluetooth/Rfcomm/GaiaServiceResolver.cs)).

**Identification.** The app's Bluetooth-name map ("MOMENTUM 4", "ACCENTUM Plus", codenames such as "Mars") gives a first guess. `0x1206` then returns a model-and-colour string such as "M4AEBT Black".

Command IDs follow one rule:
- SET is even and GET is odd;
- a response adds `0x0100`, a notification adds `0x0080`, and an error adds `0x0180`.

| Need | Commands (vendor 0x0495 unless marked) |
|---|---|
| Liveness, identity | `0x001D/0x0000` API version; `0x1206` model string; `0x1201` firmware (3 × u16) |
| Capabilities | `0x0495/0x0001` and `0x001D/0x0001` return feature/version pairs (one reply on M4) |
| Pushes | `0x0007 [feature]` for features 2, 3, 4, 8, 10, 11, 12, 13; each registration triggers a state dump |
| Battery | GET `0603` → `[p]`, `[L,R]` or `[L,R,case]`, 0xFF = unknown; charging `0602`; push `0683` |
| Noise | ANC on/off `1A04`/`1A05`; mode table `1A00`/`1A01` (1 anti-wind 0/1/2, 2 comfort, 3 adaptive); ANC↔transparency level `1A02`/`1A03`, 0–100 (0 = full ANC); transparency `1804`/`1805`; earbud transparency level `1802` |
| EQ (Gen-2 firmware only) | config `1000` → bands, min, max (dB×10); band set `1001 [band, gain]`; all gains `1003`; bass-boost bit `1008` |
| Auto-off | `0600 [timer, seconds u16]`; the app offers never / 900 / 1800 / 3600 s |

Full tables: [Sennheiser notes §3][sen-n].

**Hardware quirks.** On a Momentum 4 ([M4C captures][m4c-cap]):
- `1A04 [01]` also switches transparency off and resets the level to 0;
- `1804 [00]` restores the previous level;
- writes answer with an empty response, then a notification about 30 ms later;
- auto-off writes send no notification at all.

Elsewhere, the Accentum ignores the mode table ([sennheiser-desktop-client][sdc]), and slow reads need 9–10 s timeouts, as in the app.

**Driver shape.** The driver needs three noise strategies:
- a slider for Momentum 4, Accentum Plus and probably Momentum 5, where Earshot's 0..10 maps to level = (10 − n) × 10;
- two toggles for the Accentum;
- Off/ANC/Transparency plus anti-wind and a transparency level for the earbuds.

EQ should run only on Gen-2 firmware (feature 8 version 6). Gen-1 reuses `1002`/`1003` for different commands and needs the app's shelf-filter maths.

**Blocks:**
- factory reset `0x0040`;
- Sennheiser upgrade `0x0200`/`0x0201`;
- Qualcomm upgrade `0x001D/0x0C00–0x0C02`;
- reboot `0x060F` and role switch `0x1C00`;
- pairing-list disconnect/delete `0x1403`, `0x1405`, `0x1406`;
- the debug and data-transfer commands;
- button functions 8 (power off) and 19 (disconnect all BLE) inside `0x1600`;
- the iAP2 record itself.

**References.** The best one is **momentum4-control (MIT)**. It is hardware-verified on Momentum 4 firmware 3.37.3 and 3.38.3 and on Momentum 5 firmware 6.21.5. Its catalogue classes every command as Verified, NeedsHardwareTest or Blocked, a policy that maps straight onto Earshot's WriteGuard ([momentum4-control][m4c]; [CommandPolicy.cs](https://github.com/Ariazonaa/momentum4-control/blob/HEAD/src/Momentum4.Core/Queue/CommandPolicy.cs)). Its captures can become golden frames for a `VirtualSennheiser`. The others:
- momentumctl (MIT) cross-checks the IDs;
- sennheiser-desktop-client has no licence, so facts only;
- Gadgetbridge covers only a 2015 model.

**Effort.** S–M for the RFCOMM MVP: about 25 GET/SET pairs and three noise strategies. M for the BLE phase.

**Gaps:**
- no hardware evidence for MTW3, MTW4, Accentum Plus/TW, Momentum Sport or CX Plus;
- Smart Control Plus (MTW5, HDB 630, Accentum Clip) not analysed;
- unknown which of the Momentum 4's two records Android's SDP lookup returns;
- the iAP2 power-off rests on a single observation.

### Sony: a self-describing protocol behind a strict ACK layer

Sony uses one framing, "Tandem", with two generations of command tables ([Sony notes §1][sony-n]):
- **v2** covers every ANC model from the WF-1000XM4 (2021) on: the LinkBuds family, WH-1000XM5/XM6, WF-1000XM5/XM6, WH-CH720N, WF-C700N/C710N, ULT Wear and LinkBuds Fit/Open/Clip.
- **v1** covers the 2020 WH-1000XM4. That rests on Gadgetbridge practice, not on a capture ([Gadgetbridge protocol][gb-sony]).

**Choosing the table set.** Sound Connect picks it from the RFCOMM UUID in SDP: v2 `956C7B26-D49A-4BA8-B03F-B17D393CB6E2` first, then v1 `96CC203E-5068-46AD-B32D-E316F5E069BA`. It also accepts **byte-reversed copies of both**, because Android sometimes reports them that way. The length of the protocol-info reply (4 bytes v1, 8 bytes v2) confirms the choice.

**Transport.** The app opens a secure socket by SDP, never by channel number. Gadgetbridge waits 500 ms before its first send ([Gadgetbridge support](https://codeberg.org/Freeyourgadget/Gadgetbridge/raw/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/sony/headphones/SonyHeadphonesSupport.java)). LE Audio headsets carry the same frames over GATT service `5b833e20-…` only while the LE Audio link is up; otherwise the app falls back to RFCOMM. So RFCOMM is enough for an MVP ([Sony notes §2][sony-n]).

**Link layer.** It is the strictest of the five brands:
- frames are `3E type seq len32BE payload sum8 3C`, with 0x3C–0x3E escaped across the whole body;
- one frame in flight, with a 1-bit sequence; the ACK carries 1 − seq;
- resend after 750 ms, up to 10 times, then drop the link;
- duplicates are ACKed and ignored;
- table 1 rides data type 0x0C and table 2 rides 0x0E.

**Discovery.** After the link is up, the headset describes itself: support-function lists, then a capability reply per function, then values. The ambient-level range, EQ band and step counts, the preset list and the auto-off choices all come from the headset ([Sony notes §3][sony-n]).

The app refuses protocol versions outside a whitelist. Earshot should keep that whitelist and treat an unknown version as read-only (an inference, not app behaviour).

| Need | Messages (v2 table 1; first byte = command, second = inquired type) |
|---|---|
| Init | `00 00` protocol info → `02 00` capability info → `04 01/02/03` model name, firmware, series/colour → `06 00` support functions on both tables → `GET_CAPABILITY` per function |
| Battery | GET `22 type` → `23`, push `25`; types 00 single, 01 L/R, 02 case, 08/09/0A with threshold; v1 uses `10/11/13` |
| Noise | capability `60/61` gives each ambient mode's level range; GET param `66` → `67`, SET `68 type change effect …`, push `69`; the inquired type comes from the support list (e.g. FunctionType 0x6B → 0x17). NC is on/off or single/dual/auto; only ambient has a level |
| EQ | capability `50/51` (bands, steps, presets); param `56–59` = preset id + values; custom EQ type 0x31 |
| Auto-off | capability `21 04`; SET `28 04 current lastSelected` (type 05 adds "when removed") |
| Extras | GENERAL_SETTING `D1–D4` (multipoint, touch panel, sidetone…) → `VendorControl`; speak-to-chat `F8 0C`; touch keys `F8 03`; ALERT push `99` answered with `98` |

Full tables: [Sony notes §3][sony-n].

**Pitfalls the notes surface:**
- **Inverted enums.** `EnableDisable` uses 0 = ENABLE, while `NcAsmOnOffValue` uses 0 = OFF.
- **Ignored presets.** WH-1000XM6 firmware 3.0.0 silently ignores EQ presets it did not advertise ([mos XM6 doc](https://raw.githubusercontent.com/mos9527/SonyHeadphonesClient/HEAD/docs/device-support/WH-1000XM6.md)).
- **Reboots.** Switching the voice assistant reboots the headset ([Gadgetbridge][gb-sony]).
- **ALERT confirmations.** Some settings never apply unless the app answers the headset's ALERT confirmation. Earshot should answer "positive" only for actions the user started.

The pitfalls also correct one assumption in roadmap E3: **Sony has no ANC strength slider**. Earshot's noise slider maps to Sony's ambient level, while ANC itself is a mode.

**Blocks:**
- firmware update: UPDT `0x30–0x39`, `0x3E`, `0x3F` (v1 `0x34–0x37`) and the BLE FOTA service `5b833e30-…`;
- test commands: `0x0F` (v1 `0xFF`), NC test mode, tap and gesture training;
- logging `0xC4`/`0xC9`. mos switches it on during init; Earshot should not;
- SYSTEM `0x09` reset (factory-reset type);
- any power-off other than USER `24 03 01`;
- the PERI unpair action;
- connection-mode switches and data uploads.

Earshot must also never open the Airoha RACE channel present on many Sony models. ERNW confirmed the vulnerable RACE debug protocol (CVE-2025-20700/20701/20702) on WH-1000XM4/XM5/XM6, WF-1000XM3/XM4/XM5, LinkBuds S, ULT Wear, WH-CH720N and others, and no Sony patch list was found ([ERNW][ernw-jun]).

**References:**
- **mos9527/SonyHeadphonesClient (MIT)** is the one to port. Its V2 tables use the same names and values as the app's own enums. It supports the WH-1000XM6, WF-1000XM6 and LinkBuds Clip, and ships replay fixtures (WF-1000XM5 6.1.0, WF-LC900 2.0.3) that can seed a virtual device. Its v1 support is unfinished ([SonyHeadphonesClient][mos]).
- Plutoberth's client (MIT, archived 2025-07-19) covers v1 noise control for XM3/XM4.
- Gadgetbridge (AGPL, facts only) has the widest list of models and quirks.
- mdr-protocol has no licence, so facts only.

**Effort.** M: about the size of the Soundcore driver, plus a FunctionType table and an allow-list.

**Gaps:**
- no captures;
- the GATT session handshake is untraced;
- the WH-1000XM4 v1 assignment is unconfirmed;
- the second speak-to-chat byte is unknown;
- whether Sound Connect holding the socket blocks Earshot is inferred, not observed.

### Soundcore: one framing, forty state layouts

Soundcore is the widest brand and the least uniform. **About 40 models since 2020 have public layouts.** They fall into five families:
- gen-1 SPP headphones and earbuds;
- gen-2 earbuds on the vendor UUID;
- gen-2 headphones on the vendor UUID;
- sleep earbuds;
- a 2025–26 tag-length-value generation (Liberty 5 Pro and Pro Max, probably Liberty Buds 2).

All of them use the framing Earshot already implements: `08 EE 00 00 00`, then the command, a u16 LE total length, the body and a sum8 out; `09 FF 00 00 01` in ([Soundcore notes §1–2][sc-n]; [OpenSCQ30][oscq]).

**The app yielded no protocol facts.** Its Java is encrypted by a commercial packer. Everything comes from OpenSCQ30 (GPL-3.0), Gadgetbridge (AGPL-3.0) and soundcorebridge (MIT).

**Identification by UUID.** Gen-2 models publish `0cf12d31-fac3-4553-bd80-d6832e7` plus five hex digits. Gadgetbridge derives the suffix as the model code read as hex, OR 0x10000:
- A-codes end in `b` plus digits (`…b3947` for the Liberty 4 NC);
- D-codes end in `d` plus digits;
- there are real exceptions: `…b395B` on the R50i NC and `…1202c` on the R60i NC.

([Gadgetbridge SoundcoreDeviceSupport][gb-sc]). Earshot's `SoundcoreModel.fromServiceUuid` matches models by suffix and knows only `d1301` today ([D1301State.kt][d1301]).

**Identification by serial.** The most reliable model ID is the serial in the `01 01` state: its first four characters are the model digits, for example `3957…` on a Liberty 5 and `1402…` on a Space 2 ([OpenSCQ30 serial_number.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/common/structures/serial_number.rs)). Earshot should copy soundcorebridge's policy: **writes only when the model read from the device matches a verified profile; read-only battery and firmware otherwise** ([soundcorebridge DeviceProfile](https://github.com/mervin008/soundcorebridge/blob/HEAD/Sources/soundcorectl/DeviceProfile.swift)).

| Need | Commands |
|---|---|
| Link | RFCOMM by vendor UUID (gen 2) or SPP `00001101` (gen 1); one request per command id; 3 retries at 500/1000/1500 ms |
| State, identity | `01 01` full state (per-model layout; serial at offset 16 on gen-2 earbuds, 7 on headphones, 14 on sleep buds); `01 05` firmware + serial where the state lacks them |
| Pushes | `01 03` battery, `01 04` charging, `06 01` sound modes; re-read `01 01` after every write |
| Battery | header bytes; three scales: 0..5, 0..9 → (n+1)×10 %, or 0..100; 255 = bud absent |
| Noise | `06 81` in the model's codec: 4 bytes (v1), 6/7/8 bytes (v2 variants), a slider (Liberty 4 Pro), the Space 2 form, or `06 87 [0/1]` (Sleep A30) |
| Settings | one-byte flags: `0b 84` multipoint, `01 83` touch tone, `01 86 [on, index]` auto-off, `10 82` low-battery prompt |
| EQ | preset u16 + bands, byte = 120 + dB×10; plain `02 81` first; `02 83`/`03 87` also carry DRC-processed bands, so they come later |
| Init script | Space 2 discards writes until `01 01`, `05 01 [01]`, `05 81` ×2, `05 81 [01]`, `05 81`, `18 85 [01]` |

Codec and offset tables per model: [Soundcore notes §3][sc-n].

**Write-path quirks** ([OpenSCQ30 sound_modes_v2.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/common/modules/sound_modes_v2.rs); [soundcorebridge map][scb]; [OpenSCQ30 #377](https://github.com/Oppzippy/OpenSCQ30/issues/377)):
- v1 devices jump into noise cancelling if the sub-mode changes outside it, so write NC plus the sub-mode first;
- v2 devices misbehave on transitions the official app never makes, so writes need multi-step plans;
- layouts drift with firmware: a Liberty 5 on firmware 03.11 breaks OpenSCQ30's parser;
- only one client holds the link.

**Blocks.**
- *Services.* Never open BES `66666666-…` (RFCOMM or GATT), GATT `86868686-…` or iAP2. On the Space 2, firmware update sits on RFCOMM channel 12 and control on 30 ([soundcorebridge RFCOMM.swift](https://github.com/mervin008/soundcorebridge/blob/HEAD/Sources/soundcorectl/RFCOMM.swift)).
- *Writes refused:* `01 85`/`01 89` power off, `0b 81/82/83/85` host connect/disconnect/forget/pairing mode, `04 82` button reset, `14 82` delete alarm.
- *Explicit user action only, never automations:* `10 89` find device and `09 01` fit test, because both play loud tones.

No public source names a factory-reset or firmware-update opcode inside the `08 EE` protocol. Command ids are also reused across products: `01 81` is wear detection on earbuds and volume on the Motion+ speaker. So the guard must become **a per-model allow-list keyed on command id and body length**, with today's global deny-list kept as a second layer ([Soundcore notes §4][sc-n]).

**References and licences:**
- OpenSCQ30, GPL-3.0: facts only. Its device-faker captures are GPL test data, so unlike the MIT fixtures for Sony, Sennheiser and JBL they cannot seed Earshot's virtual devices.
- Gadgetbridge, AGPL: 10 models.
- soundcorebridge, MIT: Space 2, with hardware-verified writes and the handshake.
- SoundcoreManager: no licence; it talks over BLE.
- Anker's SoundcoreSDKDemo, Apache-2.0: covers only a voice recorder.

**Effort:**
- generic read-only view: S;
- core parser for gen-2 earbuds with the 7/8-byte codecs: M, then S per model;
- headphone family: M;
- EQ writes: M–L, because the DRC bands need Earshot's own derivation;
- TLV generation: L.

**Gaps:**
- `03 87` layouts are verified only in GPL code;
- TLV-generation write ids and the V40i opcodes are unknown;
- factory-reset and firmware-update opcodes are unknown;
- the BLE GATT UUIDs are unknown;
- which models need the handshake is unknown;
- every non-D1301 model still needs confirmation on real hardware.

### JBL: three stacks, two of them on BLE GATT

JBL's app ships its catalogue in a 123-entry product table keyed by a 16-bit product ID (PID). The entries split 87 BES, 19 Airoha, 11 Realtek and 6 Avnera, and 104 connect over BLE ([JBL notes §1][jbl-n]). Every 2020+ ANC model runs one of three stacks:

| Stack | Frames | Models | Transport |
|---|---|---|---|
| **H-AA** | `AA cmd len payload`, no checksum | the 2021–24 BES/Realtek base: Tour One M2, Tour Pro 2, Live 670/770NC, Live Pro 2, Live Beam/Flex 3, Tune/Wave/Vibe *2 | GATT service `65786365-6c70-6f69-6e74-2e636f6d0000` (notify `…0001`, write-with-response `…0002`, MTU 512) |
| **H-P4** | `00 DD`, little-endian feature-ID TLVs with a status word and an unsupported-feature list | every 2025+ BES or Realtek model: Tour One M3, Live 680/780NC, Tune 680/780/785NC, Live *4, the *3 lines | the same GATT service |
| **A-RACE** | Airoha RACE plus Harman-reserved IDs | Tour Pro 3, Live Buds 3, Live Free 2, Reflect Aero, Tune 670/770/775/777NC, Endurance Race 2, Wave/Vibe Beam 2 | Airoha SPP UUID `00000000-0000-0000-0099-AABBCCDDEEFF` on a secure socket, or Airoha GATT on LE Audio |

**Identification** comes from the BLE advert: manufacturer data under company 0x0ECB, or service data under 0xFDDF, with the PID little-endian in bytes 0–1. After connecting, the device reports its PID again. Match on PID or exact name, never on a substring: "Tune 770NC" is Airoha and "Live 770NC" is BES.

jbl-control adds two hardware findings ([jbl-control][jblc]):
- the TWS control service sits on a separate, rotating random LE address advertised as `<name>-LE`;
- only one control client may hold the link.

| Need | H-AA | H-P4 | A-RACE |
|---|---|---|---|
| Start | `AA 9B 02 01 01` (pushes on), `AA 11 00` device info, `AA 21 01 30` all status | GET `0000` unsupported list, `0004` PID, `000C` firmware; connect gate per security mode | MMI enum read `0x0901` |
| Modes | read `AA 91 01 11`; set `AA 91 07 10 01 a 02 b 03 c` (ANC/AA/TT, one set to 1); off `AA 91 01 13` | SET `0018` ANC, `0019` Ambient Aware, `001A` TalkThru (+ `0049` on the newest) | `0x0E06` filter 1 ANC, 5 Ambient Aware, 4 TalkThru; `00 0B` off |
| Levels | `AA 91 n 20 [04 lvl]` ANC, `[A1 lvl]` Ambient Aware (1..7 on Wave Buds 2) | bits 0–6 of the mode byte (bit 7 = on; 127 = unchanged) | `0x0E06 00 1E level` |
| Battery | `AA 25 01 00` (TWS); device-info byte 22 (headband) | `000D` left, `000E` right, `1F03` case | `0x0CD6` |
| EQ | `AA A2` with the app's 10-band preset blobs (32 Hz–16 kHz) | `0E02` EQInfo | `0x0900` module 0 = preset index |
| Keep-alive | echo `B1` heartbeats | echo heartbeat feature `0002` | not documented |

Full tables: [JBL notes §3][jbl-n].

**H-P4 connect gates** ([JBL notes §2][jbl-n]):
- *Security mode 2* waits for a device-side pairing-status push. That probably needs LE bonding, which is unverified.
- *Security mode 3* requires a host-identification write derived from the phone's Bluetooth name.

Neither gate encrypts anything. The app's "authentication" features are an app-side anti-counterfeit check, and nothing in the control path waits for it. Earshot skips the check and blocks those feature IDs.

**Blocks** (listed in the guard table below):
- the BES and excelpoint firmware-update services;
- H-AA factory reset `95`, power off `97`, DFU `F0`, case-OTA launch and the ANC-tuning write `74`;
- H-P4 command IDs `0x00FF` and `0x0101–0x0104`, plus features `000F`, `0011`, `1F00`, `0BA4`, `0BA5` and the auth IDs;
- everything outside an allow-list on RACE.

ERNW lists the Endurance Race 2 and Live Buds 3 among RACE-vulnerable devices ([ERNW, Dec 2025][ernw-dec]).

**References.**
- **jbl-control (MIT)** is the only open, hardware-verified implementation. On Wave Buds 2 firmware 2.7.0 it covers battery, four modes, EQ, VoiceAware, prompts, SafeSound, auto-off and find. It reads only battery on the H-P4 models ([jbl-control][jblc]).
- Gadgetbridge has no JBL protocol.
- race-toolkit has no licence.

**Effort.** L overall:
- GATT link factory: M;
- H-AA: M;
- H-P4: S–M on top;
- A-RACE: M, shared with Skullcandy;
- custom EQ curves: L, because they need native coefficient maths.

**Gaps:**
- no H-P4 or JBL-Airoha captures;
- whether mode 2 needs bonding;
- per-model level ranges;
- the role of the raw `…0003` characteristic;
- the Tour Pro 4 and Tour One M4 have no control class even in the app.

### Skullcandy: Method buds come almost free; the rest need RACE

These notes were deliberately limited to models, stacks, features, coverage and block names, and this section keeps to that scope.

**Models.** Skullcandy shipped **14 ANC products from late 2020 to 2026**:
- 13 are in the app's manifest; the Hesh ANC never had app control;
- two of the 13, the Crusher 900 ANC and Dime 540 ANC, have no public announcement.

([Skullcandy notes §1][sk-n]; [EFTM Hesh ANC review](https://eftm.com/2021/03/review-skullcandy-hesh-anc-affordable-noise-cancelling-for-everyone-207264)).

**Stacks.** At least four:
- **Bragi BOS-RPC** with an Airoha RACE side channel on the three Sound by Bose models: Crusher 1080 (AB1595), Method 360 (AB1585) and Method 540. Certain, from the Bragi config.
- **Bragi's older AT-style service** plus RACE on the Skull-iQ generation: Crusher ANC 2 (AB1568) and Rail ANC (AB1562). Certain or high confidence.
- **Airoha RACE or RACE-custom** on the 2022–25 non-Bragi line. Inferred with medium confidence from firmware naming and command families.
- **BES TOTA** on the Dime 540 ANC. Low to medium confidence.

([Skullcandy notes §2][sk-n]). The Method 360/540 configurations reference the same two link UUIDs as the Crusher 1080 (`54C5BF4C-…`, `A4602B24-…`) and BOS-RPC. They do not reference the Crusher-bass or spatial features.

**Identification** keys on Bluetooth name lists plus the SKU from `dev_info` (S2MTW, S2MFW, S6CAW, S2IPW, S6CNW). Loose hints are dangerous: "T80+" is the Rail ANC and "Sesh Boost" is the Grind.

**Features.** Almost every 2023+ ANC model offers adjustable ANC and Stay-Aware, preset and custom EQ, button remapping, multipoint and low latency. The over-ears add wear detection, sidetone and spatial audio. No 2024+ model has auto-off or find. Battery comes from the Bragi `device` service ([Skullcandy notes §4][sk-n]).

| Models | Stack (confidence) | What Earshot already covers | Work, size |
|---|---|---|---|
| Crusher 1080 ANC | BOS-RPC + RACE side channel (certain) | full driver, unverified | owner confirmation |
| Method 360 ANC, Method 540 ANC | BOS-RPC (certain) | link, framing, `dev_info`, `hearing` (ANC/Aware/Off, gain), battery | `BragiModel` entries, capability table without Crusher bass or spatial, `low_latency`; **S** |
| Crusher 900 ANC | probably Bragi (low) | none yet | re-check the next app version |
| Crusher ANC 2, Rail ANC | BOS-AT + RACE (certain/high) | routing and RFCOMM by UUID only | new BOS-AT driver after its own research; **M** |
| Indy, Sesh, Push ANC Active, Sesh ANC Active, Icon, Aviator 900, Hesh 540 ANC | Airoha RACE / RACE-custom (medium) | none | RACE allow-list codec plus custom-ID research, needs a decision; **L** |
| Dime 540 ANC | BES TOTA (low–medium) | none | skip: TOTA runs over the BES `66666666-…` UUID Earshot never opens |
| Hesh ANC | no app control | GENERIC | none |

**Block names to refuse** ([Skullcandy notes §6][sk-n]):
- *BOS-RPC methods:* `feature.set_state`, `language.lang_configure`, `test_diagnostics`, `data_streamer`, `imu_calibration`, `voice_control`, `mimi.reset`, `action_button.inject`/`invoke`, `bluetooth.devicename_set`, `device.time_set` and the `spatial_audio_dolby` calibration calls. `authenticated_access` is used only through Earshot's existing session code.
- *Bragi feature types:* FOTA, LAST_FOTA_STATUS_CHECK, CALIBRATION, CUSTOM_COMMAND, SESSION and AUDIOLOOP.
- *Other stacks:* Airoha FOTA, NV-key writes, reset, power and custom passthrough; BES TOTA passthrough and OTA; the Skull-iQ commands that reset buttons or set the Mimi user ID.

`BragiServices.ALLOWED_SERVICES` stays an allow-list. When the Method models land, it grows only by `low_latency`, and perhaps a read-only `mimi` info call.

**References.** No open project or Gadgetbridge entry supports any Skullcandy device, and the Bragi `.proto` files carry a copyright notice, so only names and field numbers can live in Earshot.

**Gaps:**
- the Crusher 1080 itself is unverified, with five open hardware questions;
- the product-to-chip map is compiled Dart;
- Skullcandy's custom RACE IDs and the BOS-AT protocol are unresearched;
- headsets linked over LE Audio only might expose no RFCOMM control channel.

## Shared blocks: one socket, one GATT link, one RACE guard, one GAIA codec

The cross-brand reuse is uneven, and the details change what to build.

**Airoha RACE shows up in three of the five brands, in three different roles:**
- *JBL:* the control protocol on its Airoha models. Standard Airoha IDs (0x0E06 ANC/passthrough, 0x0CD6 battery, 0x0900/0x0901 MMI enums, 0x2C82/0x2C83 settings, 0x2C00/0x2C01 find-me) plus Harman-reserved IDs 0x0006–0x0095 ([JBL notes §3][jbl-n]).
- *Skullcandy:* a side channel on its Bragi models, and probably the control protocol of its non-Bragi line, with custom IDs that were not researched.
- *Sony:* an unused but exposed debug path that Earshot must never touch.

So the shared asset is a **RACE codec whose allow-list is per vendor**: standard core IDs plus that vendor's own IDs. It must open only when that vendor's driver actually controls through RACE, never as a probe on Sony, Bose QC Earbuds or Bragi BOS-RPC models. The block ranges from the JBL analysis are Airoha-wide, so they belong in the shared guard:
- FOTA `0x1C00–0x1C1B`;
- storage `0x0400–0x04F0`;
- flash `0x0702–0x070F`;
- NV-key writes;
- dump/log `0x1E00–0x1E15`;
- power-mode switch, DSP suspend and role switch;
- relay `0x0D01` and the raw-frame tunnel `0x0093`, both inspected recursively.

**Qualcomm GAIA matters for control only at Sennheiser.** Soundcore and Skullcandy carry GAIA only as firmware-update clients ([Soundcore notes §1][sc-n]; [Skullcandy notes §2][sk-n]). Sennheiser also skips Qualcomm's standard Audio Curation and Music Processing features: a Momentum 4's Qualcomm feature list does not even include them ([M4C captures][m4c-cap]). Its controls are all vendor 0x0495, and only the framing and Core discovery (`0x001D/0x0000`, `0x0001`) are Qualcomm's. That is exactly the slice a shared `:protocol` GAIA codec should own, with a guard that never sends Qualcomm feature 6 or Sennheiser feature 1.

**BES is everywhere and is never to be opened.** Soundcore, JBL and Skullcandy all ship BES chips:
- BES's firmware-update UUID `66666666-…` is a chip fingerprint for identification, never a channel to open;
- JBL's app lists further BES and excelpoint OTA UUIDs;
- Skullcandy's BES control protocol (TOTA) runs over that same `66666666-…` UUID, which settles the Dime 540 as GENERIC.

**The `0cf12d31-fac3-4553-bd80-d6832e7…` base is a BES "customer SPP" pattern, not an Anker one.** JBL's RFCOMM UUID for its BT-only BES models is `0CF12D31-…-D6832E7B3850`, which OpenSCQ30's 108-bit Soundcore mask would accept ([JBL notes §2][jbl-n]; [Soundcore notes §1][sc-n]). Identification must pair the prefix with the name or serial.

| Building block | Brands that need it | Earshot today | Size |
|---|---|---|---|
| RFCOMM by SDP UUID, secure first | Sony, Sennheiser, Soundcore, Skullcandy (Bragi), Airoha SPP for JBL A-RACE | exists: `RfcommTransport.connectService` ([source][rfcomm]) | done |
| BLE GATT byte transport: MTU, write-with-response, notify reassembly, scan + address mapping | JBL H-AA/H-P4 (mandatory), Sennheiser CX Plus/CX 500, Airoha GATT, Sony LE Audio and Soundcore BLE (optional) | only one-shot GATT reads ([BmapPsmReader][psm]) and the C1 advert scanner | **M** |
| Airoha RACE codec + per-vendor allow-list | JBL A-RACE, Skullcandy RACE line | none; the Bragi stack "never speaks RACE" | **M**, plus a policy decision |
| GAIA v3 codec (framing, Core discovery, feature-6 guard) | Sennheiser; later EarFun, Moondrop and others ([accelerators][accel]) | none | **S** |
| Recognise-never-open table (BES, JBL OTA services, iAP2) | Soundcore, JBL, Skullcandy, Sennheiser | inside the Soundcore link factory only | **S** (move into A2) |
| Per-vendor allow-list WriteGuard | all | Bose, Soundcore (global) | **S** each |

The identification signals for roadmap item A2 (DeviceIdentifier), compactly:

| Signal | Means | Caveat |
|---|---|---|
| SDP `956C7B26-…` / `96CC203E-…`, or their byte-reversed forms | Sony v2 / v1 | prefer v2; confirm by reply length |
| SDP `a2129ff3-081b-4c45-8afe-469d9c4842ec` (`…eb` = legacy) | Sennheiser GAIA v3 (v1/v2) | route before any Bose logic |
| SDP `0cf12d31-fac3-4553-bd80-d6832e7` + 5 hex | BES customer SPP: a Soundcore model (suffix), or JBL `…b3850` | not unique; needs name or serial |
| SDP `54C5BF4C-…` / `A4602B24-…` | Bragi BOS-RPC | |
| SDP `00000000-0000-0000-0099-AABBCCDDEEFF` | Airoha chip with RACE | open only for a RACE driver |
| SDP `66666666-…`; GATT `86868686-…` | BES chip | never open |
| SDP `00000000-deca-fade-deca-deafdecacaff` | probably Apple iAP2 (two community sources) | never write |
| BLE company 0x0ECB or service data 0xFDDF | JBL; PID little-endian in bytes 0–1 | owner of 0x0ECB unconfirmed |
| BLE service FDCE / FCFE, company 0x0BA3 | Sennheiser / Sonova | the advertised product ID is encrypted; not needed |
| GATT `5b833e20-…` | Sony Tandem over LE Audio | |

The WriteGuard seeds for each brand, named only so they can be refused:

| Brand | Never open | Refuse on the control channel | User action only |
|---|---|---|---|
| Sennheiser | iAP2 record; any channel other than the SDP-resolved GAIA one | `0040` reset, `0200/0201` upgrade, `001D/0C00–0C02` upgrade, `060F` reboot, `1C00` role switch, `1403/1405/1406` pairings, `001D/0803–0804` debug, `001D/0009–000A` data transfer, button functions 8 and 19; legacy `000A/0204`, `0494/0101`, `000A/0712` | `1402` connect a paired device; `2C00` find |
| Sony | BLE FOTA `5b833e30`; the Airoha RACE channel | UPDT `30–39`, `3E`, `3F`; test `0F` (v1 `FF`); NC test mode; tap/gesture training; LOG `C4`/`C9`; SYSTEM `09` reset; power-off other than `24 03 01`; PERI unpair; data uploads; in the MVP also the Classic/LE Audio mode switch and voice-assistant changes, which restart the link or the headset | none in the MVP |
| Soundcore | BES `66666666`, GATT `86868686`, iAP2; channels by number | `01 85`, `01 89`, `0b 81/82/83/85`, `04 82`, `14 82`, any write not in that model's list | `10 89` find, `09 01` fit test |
| JBL | BES/excelpoint OTA services | H-AA `95`, `97`, `F0`, `A4 03`, `A3 03`, `15`, `74`, `61`, `B0`; H-P4 cmds `00FF`, `0101–0104`, `02FF`; features `000F`, `0011`, `1F00`, `0BA4`, `0BA5`, `0032`, `1F13`, `1E84`, auth IDs; RACE outside the allow-list | find-my-buds |
| Skullcandy | BES `66666666` | BOS-RPC methods and Bragi feature types listed above; Airoha FOTA/NV/reset/power/passthrough | none |

**Cross-cutting rules.** Four rules apply to every driver:
- **One control client.** The single-owner rule holds across brands: confirmed for JBL ([jbl-control][jblc]) and Soundcore ([soundcorebridge map][scb]), and inferred for Sony. Each driver should name its competing app in `ConnectionProblem.ChannelHeld`.
- **Read back after every write.** Sennheiser writes have side effects, Soundcore acks do not prove the write applied, and Sony pushes changes made by the headset's own buttons.
- **Respect the "never hide settings" decision.** Capability replies from Sony, Sennheiser and H-P4 should set ranges and produce refusals as messages, not hide controls.
- **Store modes by meaning.** The vendors now in view each have different mode sets:
  - JBL has four modes (Off, ANC, Ambient Aware, TalkThru);
  - Sony has NC, Ambient, Off and a voice focus;
  - Soundcore adds adaptive and transport sub-modes;
  - Sennheiser's earbuds and headbands differ.

  So roadmap item A7 (modes stored by meaning) is due with the first of these drivers rather than with AirPods.

## Ranked order: five RFCOMM drivers in 0.7, GATT and RACE in 0.8

The ranking weighs four things:
- whether the transport already exists;
- whether an MIT reference with hardware captures exists, so the virtual device can be trusted;
- whether the protocol describes itself, so a driver degrades gracefully on unknown firmware;
- how isolated the dangerous commands are.

Earshot owns only the Sleep A30 among these brands. Under roadmap rule 3 everything else ships as Experimental, and device reports (F3) are what promote it ([roadmap][roadmap]).

| Rank | Driver | Release | Size | Why here |
|---|---|---|---|---|
| 1 | **Soundcore generic read-only view**: header parse, model from the serial, battery L/R/case and firmware for every vendor-UUID model; fix the A-code suffix rule | 0.7 (first) | S | zero writes; lifts every vendor-UUID model above "any headset"; creates the capture path for F3 |
| 2 | **Skullcandy Method 360 / 540 ANC** on BOS-RPC | 0.7 | S | configuration-only reuse of the Crusher driver; inherits its five open questions |
| 3 | **Sennheiser GAIA v3 over RFCOMM**, with the shared GAIA codec | 0.7 (moved up from 0.8) | S–M | 9 models plus the Momentum 5; simplest framing; MIT captures for golden frames; feature discovery |
| 4 | **Sony v2 over RFCOMM** (E3) | 0.7 | M | the premium Bose peer; self-describing; MIT replay fixtures; the ACK layer is the main extra cost |
| 5 | **Soundcore gen-2 earbud writes**: Liberty 4 NC, Liberty 5, P40i, R50i NC/A30i, P31i (E2) | 0.7 → 0.8 | M + S per model | writes only after an owner's capture confirms the layout (clean-room from GPL facts) |
| 6 | **BLE GATT byte transport** | 0.8 | M | unlocks JBL, Sennheiser CX, Airoha GATT and Sony LE Audio |
| 7 | **JBL H-AA** | 0.8 | M | largest JBL base (2021–24); MIT jbl-control frames |
| 8 | **JBL H-P4** | 0.8 | S–M | every 2025–26 model; self-describing; the security gates need one capture |
| 9 | **Soundcore headphones**: Space One, One Pro, Q45, then Space 2 with its init script | 0.8 | M | direct Bose competitors; MIT map for Space 2 |
| 10 | **Airoha RACE allow-list codec → JBL A-RACE** | 0.8, after a decision | M | the same codec later serves Skullcandy's Airoha line |
| 11 | **Sony v1** (WH-1000XM4); **Sennheiser BLE** (CX Plus, CX 500) | 0.8 | S–M each | v1 after mos's support matures; BLE once rank 6 lands |

After 0.8, by demand:
- Skullcandy BOS-AT (M, plus research);
- Skullcandy's Airoha line (L);
- Soundcore EQ writes (M–L) and the TLV generation (L);
- JBL custom EQ curves (L).

Not worth building: the Dime 540 ANC (BES TOTA on the OTA UUID), the Hesh ANC (no control channel), and Sennheiser's legacy GAIA v1 models (MTW2, HD 4x0BT). All stay GENERIC.

Two ordering calls deserve a defence.

**Sennheiser before Sony** is a cost call, not a value call. Sennheiser needs no sequence/ACK layer, no byte escaping and no version whitelist, and its MIT reference was tested on two current models. Sony needs the full Tandem link layer but reaches more users. They can run in parallel. If only one fits in 0.7, Sony's user value argues for Sony.

**The read-only Soundcore view comes first** because it is pure parsing on an already-working link. It also turns every Soundcore owner into a capture source. That is the bottleneck for ranks 5 and 9, because OpenSCQ30's captures cannot be reused as test data.

## What this confirms, changes and adds to the roadmap

| Item | Verdict | Finding |
|---|---|---|
| Rules 6–7 (own WriteGuard; identify before opening) | **Confirms, sharpens** | Three brands punish probing: Sennheiser's iAP2 power-off, Soundcore Space 2's OTA on channel 12, Sony's exposed RACE. Allow-lists must key on command + sub-type + length, because Soundcore reuses ids across models and Sony multiplexes by inquired type |
| D6 (`deca-fade`) | **Strengthens, adds a fix** | A second community source (momentum4-control, MIT) labels it iAP2. The forced-Bose path still tries `SdpBmap` with `fixedChannels = false` ([BmapCandidate.kt][bmapcand]); drop it for non-Bose names now, not after D6 |
| A2 DeviceIdentifier | **Adds** | the identification table above; the `0cf12d31` prefix is shared with JBL; Sony's UUIDs need byte-reversed matching |
| A6 / A7 (shared session base; modes by meaning) | **Changes timing** | Both were deferred to AirPods modes (C2), which is now a go/no-go decision. Sennheiser and Sony are vendors 4 and 5 with modes, so attach A6 and A7 to the first of them |
| A5 capability flags | **Confirms** | Sony support functions, Sennheiser feature lists and H-P4's unsupported list map onto it directly |
| E2 Soundcore | **Confirms, refines** | model table kept; adds the serial-prefix model ID, the `b`-suffix rule for A-codes, per-model init scripts, a read-only tier first, per-model allow-lists; the app is packed, so no app-derived facts |
| E3 Sony | **Confirms; answers the open decision with "yes"** | The MIT reference matches the app's own enums, the protocol describes itself, and the fixtures can seed the virtual device. Adds reversed UUIDs, ACK timing, the version whitelist and the block list; drops "ANC level" (Sony has only an ambient level); WH-1000XM4 (v1) comes later |
| E4 Fast Pair | **Confirms the shrink** | JBL identifies through its own advert, not Fast Pair; jbl-control's Fast Pair battery path is Linux-only evidence |
| F1 VendorControl, F2 conformance, F3 device report | **Confirm, raise F3** | Sony GENERAL_SETTING items, Soundcore flags and Sennheiser toggles fit F1. MIT-licensed frames or fixtures exist for Sony, Sennheiser, JBL H-AA and Space 2; Soundcore otherwise depends on F3 captures |
| 0.7 headline | **Changes** | "Experimental: Sony, Crusher" becomes "Experimental: Sony, Sennheiser, Skullcandy Crusher 1080 + Method 360/540, Soundcore families" |
| 0.8 drivers | **Changes** | Sennheiser moves to 0.7 with the GAIA codec; add the GATT transport and the RACE codec as 0.8 platform items; JBL lands in 0.8 |
| Bragi "never RACE" vs rule 6 "RACE allow-list only" | **Decision needed** | An allow-listed RACE codec is the only route to JBL's Airoha models and Skullcandy's 2022–25 line; keep "never" for Sony, Bose and Bragi BOS-RPC |
| Landscape JBL row | **Changes** | "gen 3 unknown" becomes H-P4, documented; the main transport is BLE GATT, not RFCOMM; L effort stands |
| Landscape Sennheiser and Sony rows | **Refine** | Sennheiser's best reference is momentum4-control (hardware captures, safety classes); CX Plus needs BLE. Sony's "BLE on XM5+" is GATT only while LE Audio is active |
| Accelerators: Sennheiser 2.4★ app | **Weakens** | That rating belongs to the legacy Smart Control; Smart Control Plus is the current app and is recommended for M4, MTW4 and Accentum ([App Store](https://apps.apple.com/au/app/id6448098057)) |
| Accelerators: GAIA codec | **Refines** | Sennheiser shares only framing and Core discovery with other GAIA brands; per-brand tables carry everything else |
| No INTERNET permission | **Confirms** | Cloud-only features stay out: Sennheiser Sound Check, Skullcandy Skull AI and Skull-iQ voice. Soundcore product names are delivered from the server, so Earshot must bundle its own code-to-name table |

## Conclusion

The study changes the question from "which brand is easiest" to "which platform piece unlocks the most brands safely". RFCOMM by UUID, which Earshot already has, opens four brands at once. The only new transport worth building is GATT, and JBL alone makes it mandatory. The one real policy decision left is whether Earshot speaks Airoha RACE behind an allow-list. That one choice gates JBL's Airoha models and most of Skullcandy's line, and the honest answer depends on whether the integrator trusts a per-vendor allow-list on a protocol whose debug surface is publicly documented as dangerous.

The second shift is in what "Experimental" means. For self-describing protocols, an Experimental driver built on MIT fixtures is close to correct by construction, because the headset tells it what is safe to offer. For Soundcore it is a guess until an owner's capture arrives. That argues for treating the F3 device report as a release gate for Soundcore writes and as a confirmation step for Sony and Sennheiser. The single most urgent fix this research found is not a new driver: it is removing the `deca-fade` SDP lookup from Earshot's forced-Bose path, before more brands reach users.

[sony-n]: ../research_notes/Top%20brand%20headset%20protocols/sony.md
[sc-n]: ../research_notes/Top%20brand%20headset%20protocols/soundcore.md
[sen-n]: ../research_notes/Top%20brand%20headset%20protocols/sennheiser.md
[jbl-n]: ../research_notes/Top%20brand%20headset%20protocols/jbl.md
[sk-n]: ../research_notes/Top%20brand%20headset%20protocols/skullcandy.md
[roadmap]: ../../roadmap.md
[landscape]: ../headset-landscape-2026-10.md
[accel]: Universal%20headset%20control%20accelerators.md
[rfcomm]: ../../../app/src/main/kotlin/dev/earshot/bt/RfcommTransport.kt
[psm]: ../../../app/src/main/kotlin/dev/earshot/bt/BmapPsmReader.kt
[bmapcand]: ../../../app/src/main/kotlin/dev/earshot/bt/BmapCandidate.kt
[d1301]: ../../../protocol/src/main/kotlin/dev/earshot/protocol/soundcore/D1301State.kt
[mos]: https://github.com/mos9527/SonyHeadphonesClient
[gb-sony]: https://codeberg.org/Freeyourgadget/Gadgetbridge/raw/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/sony/headphones/SonyHeadphonesProtocol.java
[m4c]: https://github.com/Ariazonaa/momentum4-control
[m4c-cap]: https://github.com/Ariazonaa/momentum4-control/tree/HEAD/tests/CapturedPackets
[sdc]: https://github.com/Oein/sennheiser-desktop-client/blob/main/PROTOCOL.md
[oscq]: https://github.com/Oppzippy/OpenSCQ30
[gb-sc]: https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/soundcore/SoundcoreDeviceSupport.kt
[scb]: https://github.com/mervin008/soundcorebridge/blob/HEAD/docs/protocol-map.md
[jblc]: https://github.com/AhmadRun/jbl-control
[ernw-jun]: https://insinuator.net/2025/06/airoha-bluetooth-security-vulnerabilities/
[ernw-dec]: https://insinuator.net/2025/12/bluetooth-headphone-jacking-full-disclosure-of-airoha-race-vulnerabilities
