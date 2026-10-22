# JBL (Harman) ANC headphones and earbuds: control protocols for an Earshot driver

How the notes were made: static analysis of the official **JBL Headphones 5.37.10** app (`jbl.stc.com`,
version code 260825178, APKMirror build dated 2026-09-01). The app was decompiled with jadx and its JSON
assets and native-library exports were read. Nothing was installed or run, and no Bluetooth device was
contacted. The app is plain Kotlin/Java with no Flutter or React Native. Its protocol code lives in:

- `com.harman.*`: Harman's own SDK, `cmdctrl`, `protocol4`, `bluetooth`.
- `com.airoha.*`: the bundled Airoha SDK plus a Harman adapter layer.
- `assets/*.json`: per-model configuration.

Licences: the app and the Airoha SDK bundled in it are proprietary, so this file records facts only, in
my own words, and quotes no decompiled source. Each community source's licence is noted where it is cited.

Citation convention: "APK" links to the app's store page. The class or asset name in the link text says
where a fact was found inside the APK. Byte values are hex. "LE" means little-endian.

Stack names used throughout:

- **H-AA**: Harman "AA" protocol. Frames start with `AA`. It covers the app's `u3.bes.*` control classes
  and its "Protocol 3" classes (`u3.protocol3.*`).
- **H-P4**: Harman Protocol 4. Frames start with `00 DD`. The product config sets `appCmdProtocol: 4`.
- **A-RACE**: Airoha RACE with Harman-specific command IDs (`u3.airoha.*`, `u3.lea.*`).
- **Legacy Avnera**: Everest-era "ModuleId 43" table. It covers only pre-2020 models and is out of scope.

## 1. Model scope: which JBL ANC models use which stack, and how the app identifies them

### Takeaway
Every 2020-or-later JBL ANC model the app supports runs one of three stacks:

- **H-AA** over Harman's BLE GATT "excelpoint" service. This is the large BES-chip installed base of
  2021 to 2024 (Tour One M2, Tour Pro 2, Live 670/770NC, Live Pro 2, Live Beam/Flex 3, Tune/Wave/Vibe
  *2 and similar).
- **H-P4** on the same GATT service. This covers every 2025 and later BES or Realtek model: Tour One M3/M4,
  Tour Pro 4, Live 680/780NC, Tune 680/780/785NC, Live *4, Tune/Wave/Vibe *3.
- **A-RACE** over Airoha SPP or GATT: Tour Pro 3, Live Buds 3, Live Free 2, Reflect Aero, Tune
  670/770/775/777NC, Endurance Race 2, Wave/Vibe Beam 2.

The app picks the stack from its product table (`assets/product_list.json`). The table is keyed by a
16-bit product ID (PID) and the device name.

### Cited Findings
- The app ships its whole catalogue in `assets/product_list.json` (123 entries). Each entry carries:
  `name`, `nickName`, `pidS` (global and CN PIDs), `casePid`, `platform` (Avnera / Bes / Airoha / Rtk),
  `connectType` (BT / BLE / BR_EDR), `cmdCtrlList` (control class), `leaCtrl` (LE Audio class),
  `appCmdProtocol` (4 or absent), `bleSecurityMode` (1/2/3 or absent), `hasAuthentication`,
  `eqGainRange`, `smartAudioVideo` and `otaConfig`. Counts: platform Bes 87, Airoha 19, Rtk 11,
  Avnera 6; connectType BLE 104, BT 18, BR_EDR 1 — [APK: assets/product_list.json](https://play.google.com/store/apps/details?id=jbl.stc.com)
- The asset's own readme defines `earphoneType`: 0 headband, 1 half-TWS (Live 300 only), 2 TWS. It also
  defines the `uiVersion` generations: 1 = Everest series, 3 = Live Pro 2 / Live Free 2, 2 = everything
  else. 3.1 and 4.0 appear in the data but the readme does not explain them — [APK: assets/product_list_readme.txt](https://play.google.com/store/apps/details?id=jbl.stc.com)
- Stack selection in code:
  - `AppUtils.isAirohaDevice(name)` is true when the product's `platform` is "Airoha".
  - `AppUtils.isHMProtocolBTDevice(name)` is true for `platform` "Bes" with `connectType` "BT". Those
    models then run the Harman protocol over RFCOMM with the vendor UUID `0CF12D31-FAC3-4553-BD80-D6832E7B3850`
    (`TLINK_SPP_UUID`).
  - BLE models go through `BleDevice` on the Harman GATT service.
  - `BR_EDR` (Tour Pro 4) means GATT over the classic BR/EDR transport (`DeviceProtocol.PROTOCOL_GATT_BR_EDR`,
    `connectGatt` transport 1). Plain BLE uses transport 2 (LE).
  - [APK: jbl.stc.com.utils.AppUtils; com.harman.bluetooth.imp.mgr.BleDevice/SppDevice; obfuscated gn.v](https://play.google.com/store/apps/details?id=jbl.stc.com)
- The control class named in `cmdCtrlList` fixes the command set:
  - `com.harman.cmdctrl.imp.control.u3.bes.*` → H-AA (BES generation 2)
  - `…u3.protocol3.*` → H-AA plus TLV commands
  - `…u3.protocol4.*` → H-P4
  - `…u3.airoha.*` and `…u3.lea.*` (the latter `LeaAhBaseControl`) → Airoha SDK
  - `…u3.bes`'s `lea` siblings (`TourOne2LeControl`, `TourPro2LeControl`, `LiveBeam3LeControl`…) are
    the BES models' alternative class when they are linked over LE Audio (`PROTOCOL_LEA`)
  - [APK: assets/product_list.json, cmdctrl package](https://play.google.com/store/apps/details?id=jbl.stc.com)
- Identification from BLE adverts (scanner class `qi.n`, obfuscated):
  - The scanner filters on manufacturer data with company ID **3787 (0x0ECB)**, or on service data under
    the 16-bit UUID **0xFDDF**.
  - Bytes 0–1 of that data are the **PID, little-endian**. The app skips any PID missing from its table.
  - "Classic" adverts: byte 2 = colour ID, bytes 3–4 and 5–6 = CRC-16 of the names of the connected
    phone(s), bytes 7–8 = a further CRC/ID, byte 9 = a flag.
  - Newer "P5" adverts: bytes 2–3 are a bitmap, then optional fields follow in this order — colour, src
    name CRC 1, src name CRC 2, BT MAC CRC, BT MAC (6), battery, misc info (bit 6 = "another phone's JBL
    app is connected"), party info, stereo group, firmware (3), device ID, light info, misc info 2.
  - The app only proceeds when a name CRC matches the CRC of this phone's own Bluetooth adapter name. That
    CRC is a CRC-16 with the 0xA001 reflected table and initial value 0, over the first 31 bytes of the
    name (or 50 bytes if longer).
  - [APK: obfuscated qi.n, qi.b "BroadcastParse", com.harman.utils.c](https://play.google.com/store/apps/details?id=jbl.stc.com)
- AhmadRun/jbl-control (MIT) independently names 0x0ECB the "JBL vendor ID" and 0xFDDF the 16-bit
  service-data UUID in the buds' `<name>-LE` advertisement — [jbl-control protocol.py](https://github.com/AhmadRun/jbl-control/blob/main/bin/jbl/protocol.py)
- Harman International's registered classic Bluetooth company ID is 0x0057 in the systemd vendor
  database. No source tied 0x0ECB to a registered owner — [systemd hwdb](https://third-party-mirror.googlesource.com/systemd/+/refs/heads/master/hwdb.d/20-bluetooth-vendor-product.hwdb)
- Once connected, the device reports its PID itself:
  - H-AA: device info (`AA 12`) bytes 19–20, LE.
  - Protocol-3 TLV: feature 0x02.
  - H-P4: feature 0x0004.
  - Airoha: the SDK's device-PID field.
  - [APK: HandleParse.parseDeviceInfo, GenDeviceInfoCmd, DeviceInfoFeature5](https://play.google.com/store/apps/details?id=jbl.stc.com)

**Model → stack table (2020-on, from product_list.json).** PIDs are hex, global first, then CN.
Ellipses in the Class column abbreviate the model's control-class name.

| Model | PID(s) | Case/TX PID | Platform | Transport | Stack | Class | BLE sec. mode |
|---|---|---|---|---|---|---|---|
| Club Pro+ TWS | 1F65, 202B | | Airoha | BT (SPP) | A-RACE (old adapter) | airoha.ClubProControl | – |
| Reflect Mini NC | 201C, 2027 | | Airoha | BT | A-RACE (old adapter) | airoha.ReflectMiniNCControl | – |
| Live Free NC+ TWS | 201F, 2024 | | Airoha | BT | A-RACE (old adapter) | airoha.LiveFreeNCPlusControl | – |
| Live 460NC / 660NC | 2031,2032 / 2033,2034 | | Bes | BLE | H-AA (old ANC variant) | u3.bes.Live460ncControl | – |
| Live Pro+ TWS | 2043, 203B, 2060 | | Bes | BLE | H-AA | bes.LiveProPlusSdkControl | – |
| Tour Pro+ TWS | 2020, 202C | | Bes | BLE | H-AA | bes.BesSdkControl | – |
| Tour One | 2021, 2028 | | Bes | BLE | H-AA | u3.bes.TourOneControl | – |
| UA Project Rock | 1F60, 2044 | | Bes | BLE | H-AA | bes.UAProjectSdkControl | – |
| Reflect Flow Pro | 202D, 202E | | Bes | BLE | H-AA | u3.bes.ReflectProSdkControl | – |
| Tune 130NC / 230NC / 235NC TWS | 2049,204A / 204B,204C / 20E5 | | Bes | BLE | H-AA | u3.bes.Tune130/Tune230Control | – |
| Live Pro 2 TWS | 2058, 2059 | | Bes | BLE | H-AA | u3.bes.LivePro2Control | – |
| Live Free 2 TWS | 2067, 2068 | | Airoha | BT | A-RACE | u3.airoha.LiveFree2Control | – |
| Reflect Aero | 207B | | Airoha | BT | A-RACE | u3.airoha.ReflectAeroControl | – |
| Quantum TWS / TWS Air | 207D,207E / 20BD | | Airoha | BT | A-RACE | u3.airoha.Quantum…Control | – |
| Tune Flex | 207F, 2080 | | Bes | BLE | H-AA | u3.bes.TuneFlexControl | – |
| Tour One M2 | 2085, 2086 | | Bes | BLE (+LEA) | H-AA | u3.bes.TourOne2Control / lea.TourOne2LeControl | – |
| Live Flex | 2099, 209A | | Bes | BLE (+LEA) | H-AA | u3.bes.LiveFlexControl | – |
| Tour Pro 2 | 2093, 2094, 20E8 | 20C8 | Bes | BLE (+LEA) | H-AA (+ case cmds) | u3.bes.TourPro2Control | – |
| Tune Beam / Tune Buds / Tune 245NC | 20BB,20BC / 20B9,20BA / 211E | | Bes | BLE | H-AA | u3.bes.TuneBeam/TuneBudsControl | – |
| Tune 670NC / 770NC / 775NC / 777NC | 20B5,20B6 / 20B7,20B8 / 2123 / 215D | | Airoha | BLE (LEA) or SPP | A-RACE | u3.lea / u3.airoha Tune670nc/Tune770ncControl | – |
| Live 670NC / 675NC / 770NC / 775NC | 20CC / 20CD / 20CE,20CF / 2124 | | Bes | BLE | H-AA | u3.bes.Live670/675/770NCControl | – |
| Live Beam 3 / Live Flex 3 | 20EF / 20F0 | 20FA / 20FB | Bes | BLE (+LEA) | H-AA (+ case) | u3.bes.LiveBeam3/LiveFlex3Control | – |
| Live Buds 3 | 20F1 | 20FC | Airoha | BT (+LEA) | A-RACE | u3.airoha.LiveBuds3Control | – |
| Tour Pro 3 | 20F2 | 20FD | Airoha | BT (+LEA) | A-RACE (+ case) | u3.lea.TourPro3LeControl | – |
| Endurance Race 2 | 2110 | | Airoha | BLE | A-RACE | u3.airoha.EnduranceRace2Control | – |
| Wave Beam 2 / Vibe Beam 2 | 20FE / 2102 | | Airoha | BLE | A-RACE | u3.airoha.WaveBeam2/VibeBeam2Control | – |
| Wave Buds 2 / Wave Flex 2 / Vibe Buds 2 / Vibe Flex 2 | 2100 / 20FF / 2104 / 2103 | | Bes | BLE | H-AA ("Protocol 3") | u3.protocol3.* | – |
| Tune Buds 2 / Tune Flex 2 / Tune Beam 2 / Tune 255NC | 210C / 210E / 210D / 2164 | | Bes | BLE | H-AA ("Protocol 3") | u3.protocol3.* | – |
| Junior 470NC | 2105 | | Bes | BLE | H-AA | u3.bes.Junior470Control | – |
| Tour One M3 | 2101 | 214B (Smart Tx) | Bes | BLE | H-P4 | u3.protocol4.TourOneM3Control | 1 |
| Live 680NC / 780NC | 2160 / 2161 | 214B | Bes | BLE | H-P4 | protocol4.Live680NCControl | 1 |
| Tune 680NC / 780NC / 785NC | 215E / 215F / 21A4 | | Bes | BLE | H-P4 | protocol4.Tune680Control | 2 |
| Sense Pro / Sense Lite | 2153 / 2133 | | Bes | BLE | H-P4 | protocol4.SensePro / TourOneM3Control | 2 / none |
| Endurance Zone / Pace / Run 3 / Peak 4 | 2134 / 2150 / 214F / 2151 (Rtk) | | Bes/Rtk | BLE | H-P4 | protocol4.* | none / 2 / 2 / 2 |
| Soundgear Clips | 2152 | | Bes | BLE | H-P4 | protocol4.SoundgearClipControl | 2 |
| Live Beam 4 / Buds 4 / Flex 4 | 2179 / 217B / 217D | 217A / 217C / 217E | Bes | BLE | H-P4 | protocol4.LiveTws4Control | 3 |
| Tune Buds 3 / Beam 3 / Flex 3 | 21B7 / 21B8 (21B9 unsupported) / 21B6 | (21BF) | Bes | BLE | H-P4 (+auth flag) | protocol4.TuneFlex3/TuneBeam3Control | none |
| Wave/Vibe Buds 3, Beam 3, Flex 3 | 21AB-21AD / 21B2-21B4 | | Bes | BLE | H-P4 (+auth flag) | protocol4.WaveFlex3Control | none |
| Tour Pro 4 | 21AE | 20AF | Bes | **BR_EDR** | H-P4 (+auth flag) | (none yet, `support:false`) | 1 |
| Tour One M4 | 2193 | 21BE | Bes | BLE | H-P4 (+auth flag) | (none yet, `support:false`) | 1 |

[APK: assets/product_list.json](https://play.google.com/store/apps/details?id=jbl.stc.com).

Other notes on the table:

- Realtek ("Rtk") non-ANC Tune 520/525/527BT run H-AA.
- Tune 530/730/535/735BT and 520BT2 run H-P4.
- Out-of-scope entries also present: Everest Elite / Reflect Aware (Avnera), Live 220/400/500/650BTNC,
  Club 700BT/950NC/One.
- Quantum 910 is marked `support:false`.

### Inferences
- Harman's own protocol is chip-independent. BES and Realtek firmware both implement the same excelpoint
  GATT service and the H-AA / H-P4 command sets. Only Airoha products use Airoha's RACE (plus Harman
  custom IDs), so an Earshot JBL driver needs two code paths, not one per chip.
- A naming caution: "Tune 770NC" (Airoha) and "Live 770NC" (BES) look alike but use different stacks.
  Match on PID or exact name, never on a substring.
- Model ↔ year mapping is from general product knowledge and was **not verified** in this pass. Roughly:
  2020 (Club Pro+, Live Free NC+, Reflect Mini NC, Live 460/660NC, Live Pro+, Tour Pro+); 2021 (Tour One,
  Tune 130/230NC, Reflect Flow Pro); 2022 (Live Pro 2, Live Free 2, Reflect Aero, Tune Flex, Live Flex);
  2023 (Tour One M2, Tour Pro 2, Tune Beam/Buds, Tune 670/770NC, Live 670/770NC); 2024 (Live *3, Tour
  Pro 3, Endurance Race 2, *2 Wave/Vibe/Tune); 2025–26 (Tour One M3, x80NC, Live *4, *3 lines, Tour Pro 4 /
  One M4).

### Gaps
- Tune 760NC (2020) is **not** in the app's product list. It probably has no app control protocol, but
  this was not confirmed.
- The app's per-model `analytic` feature flags are inconsistent. For example, Live 460/660NC show
  "ANC not supported", so the flags cannot replace a real ANC capability list. A per-model ANC/AA/TT
  matrix needs the device's own replies, either H-P4 `UnsupportedFeatureIDList` or H-AA status.
- The owner of company ID 0x0ECB is unconfirmed.

## 2. Transport per stack: UUIDs, connection set-up, framing, how the stack is chosen

### Takeaway
- **H-AA and H-P4** use one Harman GATT service, `65786365-6c70-6f69-6e74-2e636f6d0000`. The UUID spells
  "excelpoint.com".
  - Notifications arrive on characteristic `…0001`.
  - The app writes (with response) to `…0002`.
  - `…0003` is a "raw" characteristic used by Protocol-4 security modes 1 and 3.
  - The app requests an MTU of 512.
  - Neither stack has a checksum. H-AA frames are `AA cmd len payload`. H-P4 frames are
    `id16 cmd16 count idx len16 payload`, all little-endian.
- **A-RACE** uses one of two transports:
  - RFCOMM with Airoha's SPP UUID `00000000-0000-0000-0099-AABBCCDDEEFF` (secure socket).
  - Airoha's GATT service `5052494D-2DAB-0341-6972-6F6861424C45`, used for LE Audio links.
  - Frames are `05 type len16 id16 payload`.

### Cited Findings
- **Harman GATT constants**:
  - Service `65786365-6c70-6f69-6e74-2e636f6d0000`.
  - RX/notify `…0001`, TX/write `…0002`, raw RX+TX `…0003`.
  - CCC descriptor `00002902-…`. Default MTU 512.
  - Long-command unit length 79 bytes (`UNIT_DATA_LENGTH`).
  - [APK: com.harman.bluetooth.constantsimp.Constants](https://play.google.com/store/apps/details?id=jbl.stc.com)
- **BLE connect sequence** (`BleDevice`, `GenServiceDiscovery`, `RawServiceDiscovery`):
  1. Open GATT on the LE transport (on BR/EDR for Tour Pro 4).
  2. Discover services.
  3. Request high connection priority.
  4. In security modes 1 and 3, find the raw characteristic `…0003`.
  5. Find the write characteristic `…0002`, with `WRITE_TYPE_DEFAULT` (with response).
  6. Request MTU 512. Over BR/EDR the app skips this and assumes 512.
  7. Enable notifications: on `…0003` first (modes 1/3), then on `…0001`.
  8. Wait for the mode-specific gate, then report "connected".
  - [APK: BleDevice, GenServiceDiscovery, RawServiceDiscovery](https://play.google.com/store/apps/details?id=jbl.stc.com)
- **`bleSecurityMode` gates** (H-P4 models only; absent on H-AA models):
  - **Mode 2**: after notifications are on, the app waits until the device pushes the H-P4 notification
    "PairingStatus (0x002E) = 1". It then declares the link up.
  - **Mode 3**: the app writes the H-P4 feature **HostNameCRC (0x0007)** on the raw characteristic. The
    value is the 2-byte CRC-16 of the phone's Bluetooth name described in section 1. The app waits up to
    1 s for the device to echo it, then opens the general characteristic.
  - **Mode 1**: the raw characteristic is opened as well, but the app performs no extra handshake.
  - [APK: BleDevice (isPaired / isHostCRCReceived / onDescriptorWrite)](https://play.google.com/store/apps/details?id=jbl.stc.com)
- **RFCOMM for BT-only BES models**: `SppDevice` opens an insecure RFCOMM socket by service record. The UUID
  is the `TLINK_SPP_UUID` extra when set, otherwise `00001101-…` (standard SPP, `BES_SPP_CONNECT`). The
  `TLINK_SPP_UUID` value is `0CF12D31-FAC3-4553-BD80-D6832E7B3850` (the Harman AA protocol over SPP).
  - Earshot's own Soundcore notes record a near-identical UUID ending `…d6832e7d1301`. So this looks like a
    common BES "customer SPP" base UUID with a vendor suffix. That link is an inference, not confirmed.
  - [APK: SppDevice; obfuscated gn.v constant G0](https://play.google.com/store/apps/details?id=jbl.stc.com)
- **H-AA framing** (`CmdBase`, `CommandHeader`, `Command3`):
  - Short form: `AA cmd len8 payload`.
  - Long form: `AA cmd len16LE payload` (EQ `A2`, case notification `A9`, device-info `06`).
  - Multi-packet form for old custom EQ: `AA cmd len packetCount payload`, split at 79 bytes.
  - The device answers with its own command IDs (for example `AA 12` for device info, `AA 22` for
    status) or with a generic ACK `AA 00 len cmd status`.
  - No CRC.
  - "Protocol 3" TLV commands (cmd `06`) use `AA 06 len16LE type …`. Type is 0 set, 1 get, 2 return.
    The body is entries of `[featureId 16-bit big-endian][len8][value]`; a GET lists just the IDs.
  - [APK: com.harman.commands.CmdBase/Command3, com.harman.bluetooth.reqimp.CommandHeader](https://play.google.com/store/apps/details?id=jbl.stc.com)
- jbl-control (MIT) confirms the same frame on real hardware (Wave Buds 2, firmware 2.7.0): "AA <cmd>
  <len>, no sequence byte, no checksum", with a 2-byte LE length on cmd 0x06 and 0xA2 — [jbl-control protocol.py](https://github.com/AhmadRun/jbl-control/blob/main/bin/jbl/protocol.py)
- **H-P4 framing** (`AssembleCmd4`, `ConstCmd4`, `HeaderCommandID`, `DeviceInfoList4Cmd`). Every
  field is LE:

  | Field | Size | Values |
  |---|---|---|
  | identifier | u16 | 0xDD00 (bytes `00 DD`), or 0xDD01 (`01 DD`) "forward" |
  | commandID | u16 | see list below |
  | packetCount | u8 | |
  | packetIndex | u8 | from 0 |
  | payloadLength | u16 | |
  | payload | n | |

  - CommandIDs: 0x0001 GET_DEVICE_INFO, 0x0002 SET_DEVICE_INFO, 0x0003 NOTIFICATION_TO_APP,
    0x0004 NOTIFICATION_TO_DEVICE, 0x0201 GET_ANALYTICS, 0x02FF CLEAN_ANALYTICS, 0x00FF
    RESET_TO_FACTORY, 0x0101 START_OTA, 0x0102 SEND_OTA_DATA, 0x0103 STOP_OTA, 0x0104
    OTA_NOTIFICATION.
  - Packet size: a payload is split into chunks of (MTU − 15) bytes.
  - GET payload: a list of feature IDs (u16).
  - SET payload: `[featureId u16][len u16][value]`, repeated.
  - Reply or notification: the first packet's payload starts with a status u16 (0 = OK), followed by
    `[featureId u16][len u16][value]` entries.
  - A SET reply carries only the status.
  - [APK: com.harman.protocol4.*](https://play.google.com/store/apps/details?id=jbl.stc.com)
- **Airoha transports**:
  - SPP UUID `00000000-0000-0000-0099-AABBCCDDEEFF`, opened with `createRfcommSocketToServiceRecord`
    (a secure socket).
  - GATT service `5052494D-2DAB-0341-6972-6F6861424C45`, TX `43484152-2DAB-3241-…`,
    RX `43484152-2DAB-3141-…`.
  - The app also knows Airoha's dongle GATT service `F93A34AD-…` and the Microchip transparent-UART
    service `49535343-FE7D-…`.
  - [APK: com.airoha.liblinker.constant.UuidTable, SppController](https://play.google.com/store/apps/details?id=jbl.stc.com)
- **Airoha transport choice**:
  - The JBL adapter connects with `PROTOCOL_LEA` (Airoha GATT over LE) when the model has an LE Audio
    control class and the phone is on LE Audio.
  - Otherwise it uses `PROTOCOL_SPP`.
  - In both cases it initialises the SDK as chip type `AB155x`.
  - [APK: obfuscated gi.a.w(), com.airoha.adapter.harman.AirohaDeviceConnector](https://play.google.com/store/apps/details?id=jbl.stc.com)
- **RACE frame**:
  - `05 type lenLE16 idLE16 payload`; the length counts the ID plus the payload.
  - Types: 0x5A command, 0x5B response, 0x5D indication.
  - [APK: com.airoha.libbase.RaceCommand.packet.RacePacket](https://play.google.com/store/apps/details?id=jbl.stc.com)
- **Session behaviour on H-AA**:
  - The app reads device info with `AA 11 00`.
  - It sends `AA 9B 02 01 01` ("device multi status") to switch on pushes. jbl-control sends the same
    frame as "notify_on".
  - The device sends heartbeats via cmd `B1`. The app echoes `AA B1 04 01 00 01 00`, and only after
    device info has been received.
  - [APK: CmdGen.generateReqDeviceMultiStatusCmd, HandleParse, GetSetFeatureCmd.doHeartBeat](https://play.google.com/store/apps/details?id=jbl.stc.com); [jbl-control](https://github.com/AhmadRun/jbl-control/blob/main/bin/jbl/protocol.py)
- **H-P4 heartbeat**: when a notification contains feature 0x0002 Heartbeat, the app answers with SET
  Heartbeat (no payload) — [APK: General4Control.notifyOnReceived](https://play.google.com/store/apps/details?id=jbl.stc.com)
- **Separate rotating LE address**: jbl-control reports two facts on TWS buds.
  - The control service sits on a separate, rotating random LE address, advertised as `<name>-LE`.
  - Battery and that address can also come over the Google Fast Pair Message Stream on RFCOMM
    (`df21fe2c-2515-4fdb-8886-f12c4d67927c`).
  - [jbl-control README](https://github.com/AhmadRun/jbl-control); [protocol.py](https://github.com/AhmadRun/jbl-control/blob/main/bin/jbl/protocol.py)

### Inferences
- Earshot is RFCOMM-only today. JBL support therefore needs a **BLE GATT link factory**. That means BLE
  scanning for 0x0ECB / 0xFDDF adverts, mapping each advert to the bonded classic device by PID plus
  name/CRC, and following address rotation. None of this needs INTERNET, but it does need
  `BLUETOOTH_SCAN`. Ask the integrator; it is probably already declared for the AirPods advert scanner.
- Security mode 3 only proves which phone is talking: a name CRC the phone can compute itself.
- Mode 2 most likely needs the LE link to be bonded/encrypted before the device sends PairingStatus = 1.
  This is inferred from the name only; the app has no explicit `createBond` on this path.

### Gaps
- What characteristic `…0003` carries in mode 1 is unknown. So is whether mode-2 devices demand LE bonding
  or encryption; that needs a capture.
- It is unconfirmed whether H-AA devices need encryption on the GATT link. jbl-control's plain-ATT access
  suggests not.

## 3. Commands per stack: ANC / Ambient Aware / TalkThru, EQ, battery and other settings

### Takeaway
All three stacks expose the same feature set:

- ANC on/off/level, adaptive ANC, Ambient Aware (AA) and TalkThru (TT) with levels
- 10-band EQ presets and custom EQ (32 Hz – 16 kHz)
- battery per part
- Smart Audio & Video (codec bitrate/latency)
- spatial sound, gestures, auto-off, auto play/pause, voice prompts, VoiceAware (sidetone)
- find-my-buds, multipoint, and the smart-case features

The byte encodings differ completely between stacks. The cleanest is H-P4: one generic feature-ID TLV,
with a 1-byte "status bit7 + level bits0-6" value for every mode. H-AA is a set of per-command
mini-formats. A-RACE uses Airoha standard IDs for ANC, battery, EQ and find-me, plus Harman-reserved RACE
IDs 0x0006–0x0095 for everything else.

### Cited Findings

**H-AA (cmd byte after `AA`; the payload follows the length byte)**
Source: [APK: CommandHeader, CmdGen, CmdBase, RetHeader, HandleParse, AdvancedAncCmd, BatteryInfoCmd, BeepingCmd, EQCmd, *Cmd classes](https://play.google.com/store/apps/details?id=jbl.stc.com).
Hardware-checked subset: [jbl-control protocol.py (MIT)](https://github.com/AhmadRun/jbl-control/blob/main/bin/jbl/protocol.py).

| Function | Request (app → device) | Reply / push | Notes |
|---|---|---|---|
| ACK / bye | `AA 01 02 cmd status` app ACK; `AA 03 00` bye | `AA 00 len cmd status` device ACK (status 0 = OK); `AA 02` bye; `AA 04` fin-ack | The device ACK names the command it answers (31 ANC, 32 AA, 40 preset, 41 custom EQ, 33 auto-off) |
| Device info | `AA 11 00` | `AA 12`. Layout: name 16 B at offset 3, PID LE at 19–20, model ID 21, battery 22 (bit7 charging, bits0-6 %, >100 unknown), MAC reversed 23–28, FW 29–31, voltages LE 34–37, TWS/channel bits 38, country 39 | |
| Device info TLV ("Protocol 3") | `AA 06 len16 01 [fid16BE]…` get; `AA 06 len16 00 [fid16BE len value]…` set | `AA 06 len16 02 …` | Feature IDs: 01 name, 02 PID, 03 colour, 04 MAC, 05 FW, 06 TWS channel, 07 country, 08 smart talk [on, latency], 09 case FW, 0A dongle FW, 0B auto-off [on, minutes u16 LE], 0C spatial [on, scene 1 movie / 2 music / 3 game], 0D VoiceAware/sidetone, 0E multi-AI, 0F BT link, 10 OTA status, 11 auto play/pause, 12 in-ear, 13 seal test, 14 sound effect, 17 ear tips, 18 low-EQ comp, 19 headband battery, 1A left battery, 1B right battery, 1C case battery (each `[charging, level]`), 1F call status, 20 SafeSound, 21 noise suppression, 22 manual ANC compensation, 23 audio limiter, 25 manual power-off, 26 auto pickup, 27 private-call mode, 28 game/chat balance, 29/2A play/power-on time, 2B dynamic EQ, 2D liquid detection, 2E spatial + head tracking, 3A enhanced volume, 3B Hi-Res, 3C custom-EQ count, 3D time format, 3E/3F Auracast password/HQ, 40 heartbeat |
| Status | `AA 21 01 type`. Types: 30 all, 31 ANC, 32 AA, 33 auto-off, 34 EQ preset, 35 multi-AI, 36 BT link, 37 OTA, 38 auto play/pause, 39 TWS, 3A PersoniFi test, 41 in-ear, 42 seal | `AA 22 len type …` (also pushed) | Type 30 body: ANC on, AA mode, auto-off, EQ… |
| ANC on/off (old firmware, `VERSION_OLD`) | `AA 31 01 00/01` | ACK | |
| AA / TalkThru (old) | `AA 32 01 v`: 0 off, 1 TalkThru, 2 Ambient Aware | ACK | |
| **Listening mode (current firmware, `VERSION_NEW`)** | `AA 91 07 10 01 a 02 b 03 c`: pairs of (mode 1 ANC / 2 AA / 3 TT, 0 or 1), exactly one set to 1. `AA 91 01 13` all off. `AA 91 01 11` query | `AA 91 …` | Every u3.bes / protocol3 class uses `VERSION_NEW`. jbl-control sends the same mode_set/mode_get frames on Wave Buds 2 |
| ANC sub-modes (Tour One era) | `AA 91 02 00 idx` set (0 off, 1 on, 2 "everyday", 3 "travel"); `AA 91 01 01` query | | |
| Advanced ANC (adaptive / levels) | `AA 91 n 20 [type value]…` set; `AA 91 01 21` get; reply sub 22 | `AA 91 … 22 …` | Types: 01 adaptive ANC on/off, 02 true-adaptive level, **04 manual ANC level**, 05 leakage compensation, 06 ear-canal compensation, 07 ANC always-on, 08 auto compensation, **A1 Ambient Aware level**. jbl-control clamps level 1..7 on Wave Buds 2 |
| ANC tuning (calibration) | `AA 74 05 sel float32LE` set; `AA 75 01 sel` get | `AA 76` | See section 4: do not write |
| Battery (TWS) | `AA 25 01 00` | `AA 25 len 01` then slave-charging, master-charging, slave %, master %, case-charging, case %, slave mV u16LE, master mV u16LE | Headband battery comes from device-info byte 22 |
| Case FW | `AA 26 01 00` | `AA 26` | |
| EQ preset (old) | `AA 40 01 idx` | ACK | Index: 0 off/default, 1 jazz, 2 vocal, 3 bass, 17 rock, 18 piano, 19 club, 20 studio (`EqCatIndex`) |
| EQ (current, cmd A2) | Set: `AA A2 len16LE`, then sub (00, or 03 = Q×10), category, calibration f32LE, sample-rate u8, left gain f32, right gain f32, band count u8, and per band type u8, gain f32, freq f32, Q u8. Get: `AA A2 02 01 cat` (FF = current) | `AA A2 len16 …` same layout | Categories in the app's JSON: 0 off, 1 Jazz, 2 Vocal, 3 Bass, 6 Club, 7 Studio, 9 Extreme Bass, 230 custom. Bands 32/64/125/250/500/1k/2k/4k/8k/16k Hz, type 1 (peak), Q 1. Gain range per model `eqGainRange` (mostly ±6; ±7 Tour; ±10–12 others). jbl-control sends calibration −1.0 and sample rate 48 |
| Custom EQ (old) | `AA 41 len count …` (19 + 16×bands bytes, 79-byte chunks); `AA 42 01 idx` read | `AA 43` | |
| Segment EQ | cmd A6, sub 03 set / 01 get / 04 return | | Multi-segment EQ blobs |
| Auto power-off | `AA 33 01 (en<<7 | minutes&7F)` or `AA 33 03 en minutesLE16` | status 33 | Protocol-3 models: TLV 0B |
| Auto play/pause | `AA 35 01 0/1` | status 38 | |
| Multi-AI / voice assistant | `AA 34 01 v` (0 off, 1, 2, 3, 4, DF); hotword `AA 92 03 00 va status` / `AA 92 01 01` | | |
| Find my buds (new) | cmd 23: sub 01 query; set `00 L R` (jbl-control: FF = leave that side alone) | `AA 23 … 02 L R` | |
| Find my buds (old) | `AA 36 01 x`: 00 stop R, 01 stop L, 10 start R, 11 start L | `AA 24` | |
| Gestures | Batch: `AA 77 n 00 (gesture action)…`; read `AA 77 02 01 g`; old per-gesture `AA 71` / `AA 72`; touch-panel on/off `AA 79 03 00 01 v`; function control `AA 78` | `AA 73`, `AA 77` | Action codes 0..13, then A0+ for assistants (jbl-control: 01 vol+, 02 vol−, 03 AA, 04 TT, 05 next, 06 prev, 08 play/pause, 09 noise control, 0C ANC off, 0D AA off, A1 voice assistant). Gesture IDs vary per model (`assets/gesture/product_gesture_config.json`) |
| Smart Audio & Video (bitrate/latency) | `AA 81 08 AAC u16 SBC u16 lat u16 lat2 u16` set; `AA 82 00` get; Protocol-3: cmd 80 | `AA 83` | Values per model in product_list `smartAudioVideo` (Tour One M2: normal 256/53/230, audio 256/53/150, video 197/46/80). jbl-control only replays frames byte-identical to the app's |
| Voice prompts | `AA 93 02 03 00/01` set; `AA 93 01 04` get; `AA 93 01 01` language list | `AA 93` | |
| VoiceAware (sidetone in calls) | `AA 98 02/03 00 level [extra]`; `AA 98 01 01` get | `AA 98` | jbl-control echoes back the extra byte the device reported |
| Standby / keep-ANC | `AA 96 n 00 (mode 1 normal / 2 keepANC, on)…`; `AA 96 01 01` get | | |
| Silent Now / sleep timer | cmd 90 (11-byte set; durations u32LE) | | |
| Spatial 3D | cmd 9D | | |
| Low-volume EQ | cmd 9E | | |
| Smart Talk / Speak-to-chat | cmd 9F | | |
| PSAP (ambient amplifier) | cmd A0, features 1 status, 2 balance, 3 gain | | |
| SafeSound | cmd A5 | | |
| L/R balance | cmd A8 | | |
| PersoniFi | 44/45 enable + DJ preset u16; 47/49 mode + hearing test; 4A volume (float); A1 hearing-test text | | Personalisation needs the native `libpersonifiAlgorithm.so` |
| Smart charging case (Tour Pro 2 / Live *3) | A3 display settings (01 get, 03 delete screensaver); A4 case features (01 get, 03 launch case OTA); A7 sync time; A9 notifications (len16) | | |
| LE Audio / Auracast | cmd B0 (scan/receive/switch subgroup) | | |
| Serial number | `AA 94 01 01` | | |
| Ear-canal / environment checks | 9A / 99 | | |

**H-P4 (feature IDs; SET with `00 DD 02 00`, GET with `00 DD 01 00`, notifications arrive as `00 DD 03 00`)**
Source: [APK: com.harman.sdk.message.DeviceInfoFeature5; com.harman.protocol4.features.*; General4Control, Live680NCControl, LiveTws4Control, TourOneM4Control](https://play.google.com/store/apps/details?id=jbl.stc.com).
The asset [APK: assets/protocol4_commands.json](https://play.google.com/store/apps/details?id=jbl.stc.com)
lists the same IDs with bit layouts; where the two differ, the code enum is newer (it adds
AmbientSoundControl 0x49, auth features and others).

- **Mode value ("StatusLevelSize1")**: one byte. Bit 7 is status (on/off) and bits 0–6 are the level;
  level 127 means "leave unchanged". Every SET entry carries a u16 LE value length.
  - Example, ANC on: `00 DD 02 00 01 00 05 00 18 00 01 00 FF`.
  - Example, ANC off: the same frame with value `7F`.
  - Example, read ANC/AA/TT: `00 DD 01 00 01 00 06 00 18 00 19 00 1A 00`.
  - These frames are composed from the documented layout; none was captured.
- **Mode switching**:
  - General4Control (Tour One M3 family): filter 1 → ANC (0x0018) on, 3 → AA (0x0019) on, 2 → TT (0x001A)
    on, 0 → ANC off. Each is a single SET with level 127.
  - Live 680/780NC, Live *4, Tour One M4 and Tour Pro 4 also send **AmbientSoundControl 0x0049** (on/off)
    together with ANC/AA/TT carrying the chosen level.
  - Adaptive ANC is 0x001B (status). Manual adaptive ANC level goes into ANC's level bits. Auto
    compensation is 0x0020. Leakage compensation is 0x001D.

| Area | Feature IDs |
|---|---|
| Identity | 0000 unsupported-feature list (2 B each — use for capability discovery), 0001 MTU, 0002 heartbeat, 0004 PID, 0005 colour, 0006 name (UTF-8), 0007 host-name CRC, 0008/0009 L/R serial, 000A MAC, 000B peer MAC, 000C FW (3 B), 002E pairing status, 002F host type, 0033 host BLE address |
| Battery | 000D left, 000E right (bit7 charging, bits0-6 %), 1F03 case, 0017 battery presence |
| Listening | 0018 ANC, 0019 AA, 001A TT, 001B adaptive ANC, 001C true-adaptive, 001D leakage comp, 001E ear-canal comp, 001F ANC always-on, 0020 auto compensation, 0049 AmbientSoundControl, 0026 noise suppression |
| Sound | 0021 spatial mode, 0022 spatial scene, 002D spatial recenter, 0050 active room, 1D00–1D03 head-tracking calibration/data, 0024 low-EQ compensation, 0025 max volume, 0027 audio limiter, 0028 Hi-Res, 0029 sound effect, 0015 L/R balance, 0023 sidetone, 2D46 codec setting |
| EQ | 0E01 EQ info query, 0E02 EQInfo (category u8, state u8, band count u8, calibration f32, sample rate u32, L gain f32, R gain f32, then per band type u8, freq f32, gain f32, Q f32, optional UTF-8 name), 0E03 EQ sequence, 0E04 curve list, 0E7E design-EQ raw, 0E7F EQ curve (coefficients from the native Airoha PEQ library per `eqLibInfo`), 0E80 dynamic EQ, 0E81 custom-EQ count |
| Controls | 0F00–0F0D per-gesture action (L/R swipe fwd/back, tap, double, triple, tap-hold, double-tap-hold), 0F80–0F89 dials/buttons, 0FC1/0FC2 touch sensitivity |
| Behaviour | 0012 auto power-off (u16: bit15 on, bits0-14 minutes), 0013 auto standby, 002A auto play/pause, 002B smart talk, 0C40 voice prompts, 0C41 VP language, 0C43–0C45 VP/tone volume and type, 0B60 multi-AI, 100F Smart Audio & Video (AAC u16, SBC u16, latency u16, latency2 u16), 1002 in-ear, 1000 TWS info, 1003 seal test, 1005 ear tips, 1007 sports mode, 100A find-my-buds (bits case/right/left), 100B call status, 100C auto pickup, 100D private call, 1015 call beamforming, 0C80–0C84 PSAP, 0CB0–0CB4 sleep/alarm |
| Multipoint / sources | 0BA0 LE unicast, 0BA1 active source, 0BA2 source info, 0BA3 paired-device list (6 B MAC, 32 B name, info bits), 0BA4 operate paired device, 0BA5 pairing mode, 0B80–0B8A Auracast |
| PersoniFi | 1E00 mode, 1E01 hearing-test mode, 1E02 setup flow, 1E03/1E04 test file side/frequency |
| Smart case / Smart Tx | 1F01 case FW, 1F02 case MAC, 1F04 brightness, 1F05 notifications, 1F06–1F18 screensaver, 1F0A language, 1F0B unlock indicator, 1F0C tap-to-wake, 1F0F find-my-case, 1F10 screen size, 1F11 24 h, 1F80–1FA9 per-page case configuration, 0030–0032 text notifications, 0031 time sync, 0034–0036 case/dongle/Smart Tx link status |
| Safe listening (kids) | 1E80–1E8B (current volume, max limit, daily limit, PIN code, history, reminder voice) |
| Auth (app-side) | 003E AuthenticateDevice, 004A algorithm, 004B advanced auth, 0053/0054 accessory auth info/result |

**A-RACE (JBL Airoha models)**
Sources: [APK: com.airoha.libbase…RaceId; com.airoha.adapter.harman.stage.MmiStage_*; com.airoha.libmmi.stage.*; com.airoha.libpeq.stage.*; AirohaDeviceStatusControl](https://play.google.com/store/apps/details?id=jbl.stc.com).
All commands use type 5A, and the reply is 5B unless noted.

- **ANC/passthrough (Airoha standard, RACE 0x0E06)**:
  - On: payload `00 0A filter [mode]`. Off: `00 0B`.
  - Pass-through gain: `00 0F gain-s16LE`. The JBL adapter sets the ANC/AA *level* with `00 1E level-s16LE`.
  - Airoha UI filter values: 0 off, 1 ANC1, 2 ANC2, 3 ANC3, 4 PassThrough1, 5 PassThrough2,
    6 PassThrough3, 7 ANC4.
  - JBL mapping: ANC = filter 1 (filter 2 = ANC with "sports mode"), **TalkThru = 4**,
    **Ambient Aware = 5**.
- **Reads via RACE 0x0901 (HOSTAUDIO_MMI_GET_ENUM)** with a u16 LE module number:
  - module 5 = ANC status: reply byte 9 = filter, bytes 10–11 = gain.
  - module 7 = AA level, module 1 = voice prompt on/off, module 6 = Smart Audio & Video index,
    module 0 = EQ preset group.
- **Writes via RACE 0x0900 (HOSTAUDIO_MMI_SET_ENUM)**:
  - Voice prompts: `01 00 on`.
  - **EQ preset: `00 00 index`** (PeqStageSetPeqGrpIdx).
- **Custom EQ**: PEQ coefficients are computed by `libnative-peq.so` and sent with RACE 0x0E03
  (real-time PEQ), then saved with 0x09FD.
- **Battery**: RACE 0x0CD6 TWS battery (payload agent 00 / partner 01; reply type 5D). Case battery 0x000B
  and charging info 0x0009 (role byte) are Harman-reserved IDs.
- **Find me**: 0x2C01 with blink, ring, target; 0x2C00 query.
- **Auto power-off / hotword / VP language / voice EQ**: get 0x2C83 and set 0x2C82 with modules 1, 11,
  9 and 19 respectively.
- **Harman-reserved RACE IDs (get / set pairs)**:
  - Sound: 0x0013/0x0012 3D spatial [on, type]; 0x0046/0x0047 spatial sound; 0x0048/0x0049 head tracker;
    0x0075/0x0076 Hi-Res; 0x0058/0x0059 low-volume EQ; 0x0037/0x0036 L/R balance [on, level];
    0x0051/0x0050 audio limiter; 0x0014/0x0015 SafeSound.
  - Call and talk: 0x0060/0x0061 Smart Talk [on, latency]; 0x0053/0x0052 chat optimizer;
    0x0087/0x0088 auto pickup; 0x0064/0x0065 mic gain; 0x0032–0x0038 stand-alone mic.
  - Listening extras: 0x0077/0x0078 PSAP [on, level, index]; 0x0068/0x0069 Silent Now; 0x000D ANC sports
    mode; 0x0E13 auto-compensation check; 0x0E26 full-adaptive ANC mode; 0x0095 auto-compensation off.
  - Sources and links: 0x0055/0x0054 audio source; 0x0066/0x0067 paired list; 0x0056 connect BT device;
    0x0057 delete BT device; 0x000A/0x000C in-ear sensor on/off and status.
  - Device info and case: 0x0092 device info; 0x0094 set device info; 0x000E colour; 0x000F case FW;
    0x0079/0x0080 case display; 0x0081/0x0082 case features; 0x0083 smart-case action; 0x0089 delete
    screensaver; 0x0090 set time (u32 LE seconds); 0x0091 case notification.
  - PersoniFi: 0x0062/0x0063 status, 0x0070/0x0071 EQ, 0x0072/0x0073 setup flow.
  - Other: 0x0040–0x0045 lighting (Quantum); 0x0006 device push on/off; 0x0007 power-control set;
    0x0010/0x0016 analytics; 0x0011 clear analytics; 0x0093 tunnel for a raw Harman `AA` frame.
- **Gestures on Airoha** are stored in NV keys:
  - Read 0x0A00 / 0x0A0C. Write 0x0A01 / 0x0A0D. Reload to RAM 0x0A09.
  - The local bud is written directly; the partner bud is reached through relay 0x0D01.
  - Smart Audio & Video uses 0x1101 (MMI key) with a parameter block.

### Inferences
- **Recommended Earshot mapping**:
  - Listening modes, all stacks: Off / ANC / Ambient Aware / TalkThru map onto Earshot's mode slots
    (slot 0 quiet, slot 1 aware).
  - H-AA: `AA 91 10` pairs.
  - H-P4: SET 0018/0019/001A.
  - A-RACE: 0x0E06 with filter 1/5/4, plus `00 0B` for off.
- **EQ** for an MVP:
  - Expose the JBL preset categories (Jazz, Vocal, Bass, Club, Studio, Extreme Bass).
  - H-AA: write the app's own 10-band preset payloads (gains are in the asset JSON and jbl-control).
  - H-P4: write EQInfo 0x0E02.
  - A-RACE: select the PEQ group index (0x0900 module 0).
  - Leave custom EQ on Airoha and the H-P4 EQ curve (0x0E7F) for later. Both need native coefficient
    maths and an EQ calibration value from `libcalibrationLib.so`.

### Gaps
- Per-model ANC/AA/TT level ranges are not in the assets. jbl-control saw 1..7 manual ANC level on Wave
  Buds 2. H-P4 levels are 7-bit; the real range per model is unknown.
- The H-AA status-30 "all status" body was traced as far as frame byte 13. Byte offsets count from the
  `AA`:

  | Byte | Field |
  |---|---|
  | 4 | ANC on |
  | 5 | AA mode (0 off, 1 TT, 2 AA) |
  | 6 | auto-off: bit7 on, bits0-6 minutes; FF = not reported |
  | 7 | EQ preset |
  | 8 | multi-AI |
  | 10 | OTA status |
  | 11 | auto play/pause |
  | 12 | TWS link |
  | 13 onward | auto-off v2: on flag + minutes; FFFF = absent |

  Byte 9 is unknown. Source: [APK: HandleParse.parseDeviceAllStatus](https://play.google.com/store/apps/details?id=jbl.stc.com).
- Airoha 0x0CD6 reply: after the 6-byte RACE header come a status byte, then the role (agent/partner),
  then the percentage — [APK: MmiStageTwsGetBattery](https://play.google.com/store/apps/details?id=jbl.stc.com).
  The Harman case-battery (0x000B) and charging-info (0x0009) replies were not traced field by field.

## 4. What must never be sent: block-list per stack

### Takeaway
Use an **allow-list** per stack, as Earshot's Bose `WriteGuard` and Soundcore guard already do. The IDs
below are named so a guard can refuse them. Never open the BES firmware-update channels at all.

### Cited Findings
- **Firmware-update channels: never open.**
  - BES OTA GATT service `66666666-6666-6666-6666-666666666666`, characteristic `77777777-…`, RX
    `97979797-…`.
  - BES TOTA service `86868686-…`.
  - "Normal" OTA service `01000100-0000-1000-8000-009078563412` / `03000300-…`.
  - BES SDK OTA `05000500-0000-1000-8000-009478563412`, `06000600-0000-1000-8000-009578563412` and
    `07000700-0000-1000-8000-009678563412`.
  - The "excelpoint.co\x82" OTA service variants `65786365-6c70-6f69-6e74-2e636f82000x`.
  - The SPP OTA UUID `66666666-…`.
  - [APK: Constants, BesSdkConstants](https://play.google.com/store/apps/details?id=jbl.stc.com)
- **H-AA**:
  - **`95` factory reset** (jbl-control also blocks it).
  - `97` shut down (power off).
  - **`F0` DFU** (firmware transfer: CRC, file size, break-point keys).
  - `A4` sub `03` "launch charging-case OTA".
  - `A3` sub `03` delete case screensaver.
  - `15` clear analytics.
  - `74` ANC tuning write (factory-style ANC gain floats).
  - `37` status type "OTA upgrade".
  - `61` "sync app status" (purpose unclear).
  - Frames to the OTA services above.
  - [APK: CommandHeader, DfuCmd, ChargingCaseFeatureCmd, ChargingCaseDisplayStatusCmd](https://play.google.com/store/apps/details?id=jbl.stc.com); [jbl-control BLOCKED_COMMANDS](https://github.com/AhmadRun/jbl-control/blob/main/bin/jbl/protocol.py)
- **H-P4 command IDs**: 0x00FF reset to factory, 0x0101 / 0x0102 / 0x0103 / 0x0104 OTA, 0x02FF clean
  analytics — [APK: HeaderCommandID](https://play.google.com/store/apps/details?id=jbl.stc.com)
- **H-P4 feature SETs**:
  - 000F factory reset.
  - 0011 manual power-off.
  - 1F00 launch OTA mode (case/dongle).
  - 0BA4 operate paired device (can delete a pairing).
  - 0BA5 pairing mode.
  - 0032 remove all text notifications.
  - 1F13 remove custom screensaver.
  - 1E84 Safe-listening PIN code: could lock a child's limits.
  - The authentication features 003E / 004A / 004B / 0053 / 0054.
  - 0007 host-name CRC: send only as the mode-3 connect step.
  - [APK: DeviceInfoFeature5](https://play.google.com/store/apps/details?id=jbl.stc.com)
- **A-RACE**: refuse everything outside an allow-list. Named for the guard:

  | RACE ID range | What it is |
  |---|---|
  | **0x1C00–0x1C1B** | FOTA, incl. 0x1C05 software reset |
  | **0x0400–0x04F0** | storage / flash program-erase-lock |
  | **0x0702–0x070F** | flash |
  | **0x0A01, 0x0A03, 0x0A09, 0x0A0A, 0x0A0D** | NV-key write / reclaim / reload |
  | 0x1E00–0x1E15 | dump / log / assert (0x1E07 forces an assert) |
  | 0x020D | power-mode switch |
  | 0x0E01 / 0x0E02 | DSP suspend / resume |
  | 0x0E0A / 0x0E0B | ANC / air dump |
  | 0x1101 | MMI key codes; only specific known keys |
  | 0x0CD7 | role switch |
  | 0x2C86 | device operation |
  | 0x3101 | dongle action |
  | **0x0D01** | relay to the partner bud: inspect the wrapped packet with the same rules |
  | **0x0093** | raw Harman-frame tunnel: apply the H-AA block-list to the inner frame |

  - Also refuse these Harman-reserved IDs:
    - 0x0057 delete BT device, 0x0067 rewrite paired list
    - 0x0007 power control, 0x0011 clear analytics
    - 0x0083 smart-case action, 0x0089 delete screensaver
    - 0x0094 set device info, 0x0030 ULL pairing
  - [APK: RaceId, MmiStage_* classes](https://play.google.com/store/apps/details?id=jbl.stc.com)
- ERNW's Airoha disclosure lists three CVEs, named here for awareness only:
  - CVE-2025-20700: missing authentication for GATT.
  - CVE-2025-20701: missing authentication for BR/EDR.
  - CVE-2025-20702: debug-level RACE functions.
  - ERNW confirmed vulnerable JBL models: **Endurance Race 2** and **Live Buds 3**.
  - [ERNW / insinuator.net](https://insinuator.net/2025/12/bluetooth-headphone-jacking-full-disclosure-of-airoha-race-vulnerabilities)

### Inferences
- For A-RACE, a safe allow-list (only these IDs) covers every user-facing feature in section 3:
  - Airoha standard: 0x0E06, 0x0900/0x0901 (modules 0, 1, 5, 6, 7), 0x2C82/0x2C83 (modules 1, 9, 11,
    19), 0x0CD6, 0x2C00/0x2C01, 0x0CD4/0x0CC4 (role queries).
  - The Harman-reserved get/set pairs listed in section 3.
  - Nothing else is needed for an MVP.
- Gestures on Airoha require NV-key writes. Leave them out of Earshot until a capture shows exactly which
  NV IDs the app writes.

### Gaps
- The meaning of H-AA `61` (sync app status) and `B0` sub-commands was not traced; keep both blocked
  until it is.
- The MMI key codes (0x1101) that are safe were not enumerated.

## 5. Community and security knowledge

### Takeaway
- The only open, hardware-verified JBL control implementation found is **AhmadRun/jbl-control (MIT)**.
  It covers H-AA on Wave Buds 2 and reports that the newer "gen-3" models (Tune 680/780NC, Live x80NC,
  Tour Pro 4) answer battery only. That matches this analysis: those models use H-P4 with BLE security
  modes 1/2.
- No JBL support was found in Gadgetbridge (AGPL) beyond its generic audio-device support.
- On the security side, ERNW names JBL Airoha models among RACE-vulnerable devices.

### Cited Findings
- **jbl-control (MIT)** by AhmadRun, an Omarchy/Hyprland plugin:
  - Verified on Wave Buds 2 firmware 2.7.0: battery, four listening modes, EQ, VoiceAware, voice prompts,
    SafeSound, auto power-off, find-my-buds.
  - "Likely compatible": Vibe Buds 2, Tune Flex 2 / Buds 2 / Beam 2, Wave Flex 2, Tune 230NC/255NC,
    Live 660/770NC, Tour Pro 2.
  - Gen-3 (Tune 680/780NC, Live x80NC, Tour Pro 4): battery only; "newer protocol not decoded".
  - Blocks `AA 95`. Uses a raw ATT socket plus the Fast Pair message stream.
  - Credits (no code copied): ncr/omarchy-headphones (MIT), Asklios/jbl-vibe-buds-2-gnome (BSD-3),
    emperorbug46/JblTray (MIT), FajkPes/JBL-Headphones-App (no licence shown).
  - [jbl-control](https://github.com/AhmadRun/jbl-control)
- Its protocol module documents the frame types and IDs listed in sections 2 and 3, including Smart
  Audio & Video blobs taken byte-for-byte from the JBL app on a Tune Flex 2 — [jbl-control protocol.py](https://github.com/AhmadRun/jbl-control/blob/main/bin/jbl/protocol.py)
- Gadgetbridge 0.85.0 added generic support for any Bluetooth audio device (battery, as reported by the
  OS). No JBL-specific protocol was found — [Gadgetbridge 0.85.0](https://gadgetbridge.org/blog/release-0_85_0/)
- ERNW's December 2025 full disclosure of the Airoha RACE issues lists JBL Endurance Race 2 and JBL Live
  Buds 3 as verified vulnerable. It says nothing about JBL's patch status. RACE was reachable without
  pairing over BLE GATT and/or classic RFCOMM on the devices they tested — [insinuator.net](https://insinuator.net/2025/12/bluetooth-headphone-jacking-full-disclosure-of-airoha-race-vulnerabilities); press coverage: [Tom's Guide](https://www.tomsguide.com/computing/online-security/major-security-flaw-exposes-sony-jbl-and-bose-headphones-to-hijacking-threat-how-to-stay-safe)
- The app's "authentication" is an anti-counterfeit check of the accessory:
  - Algorithms: RSA-2048 with a prebuilt or dynamic key pair, or AES-256-CBC.
  - Implemented in `libcounterfeit.so`, `com.jbl.counterfeit` and `AuthAccessoryMgr`.
  - A failed check makes the app show a "counterfeit" pop-up.
  - [APK: AuthenticationAlgorithmIndex, AuthenticateDeviceFeature, obfuscated bm.a/bm.g, lib/arm64-v8a/libcounterfeit.so](https://play.google.com/store/apps/details?id=jbl.stc.com)
- Native libraries in the arm64 split, and what each does (from their JNI export names):
  - `libnative-peq(-serialize).so`, `libnative-anc(-1568).so`: Airoha EQ and ANC coefficient maths.
  - `libcalibrationLib.so`: EQ calibration value.
  - `libDspConfig.so`: Realtek (realsil) EQ.
  - `libpersonifiAlgorithm.so`: PersoniFi hearing test.
  - `libcounterfeit.so`: the authenticity check above.
  - `libamazonjniutil.so`: cloud analytics.
  - `libsbcconverter.so`: voice memo to SBC.
  - None carries the control protocol itself.
  - [APK: split_config.arm64_v8a.apk](https://play.google.com/store/apps/details?id=jbl.stc.com)

### Inferences
- The counterfeit check is something the *app* does to the *device*. Nothing in the control path waits
  for it, so Earshot can skip it; it is only flagged on 2025–26 models (`hasAuthentication`). Whether a
  device refuses commands when no check has happened is unconfirmed.
- The earlier landscape note's "gen 3 unknown" is H-P4. Its framing, feature IDs and connect gates are
  now documented above.

### Gaps
- No public captures of H-P4 or of the JBL Airoha custom IDs were found.
- Nothing was found on XDA or Reddit beyond the repos credited by jbl-control. I did not search
  exhaustively.

## 6. Effort, quirks and a recommended order

### Takeaway
1. **First: H-AA on the Harman GATT service.**
   - Largest installed base of 2021–2024 ANC models.
   - Simple framing, no security gate.
   - Hardware-verified open reference: jbl-control.
2. **Second: H-P4 on the same GATT link.**
   - Every 2025–26 model.
   - Clean TLV with a built-in unsupported-feature list.
   - Needs the mode-2/3 connect gates and heartbeat echoes.
3. **Third: A-RACE for the Airoha models.**
   - Reuse any Earshot Airoha RACE layer; add the JBL-reserved IDs.
   - Needs a strict allow-list.

### Cited Findings
- The H-AA and H-P4 controls share one transport class (`BleDevice`) and one GATT service. They differ
  only in command builders and parsers.
  - H-AA: `CmdGen` / `HandleParse`.
  - H-P4: `DeviceInfoList4Cmd`.
  - [APK: BleDevice, HmProtocol4DeviceStatusControl, BesDeviceStatusControl](https://play.google.com/store/apps/details?id=jbl.stc.com)
- Platform counts: 87 BES, 19 Airoha, 11 Realtek entries. Every model added in 2025–26 is
  `appCmdProtocol: 4` — [APK: product_list.json](https://play.google.com/store/apps/details?id=jbl.stc.com)
- jbl-control found that the TWS control service rotates its LE address. It also found that only one
  control link is allowed at a time: the first client owns it — [jbl-control](https://github.com/AhmadRun/jbl-control)

### Inferences
- **Minimum viable JBL driver (H-AA)**:
  1. Scan for 0x0ECB / 0xFDDF and map the PID through a small table (section 1).
  2. Connect GATT, request MTU 512, enable notifications on `…0001`.
  3. Send `AA 9B 02 01 01`, then `AA 11 00` (device info) and `AA 21 01 30` (all status).
  4. Read battery: `AA 25 01 00`, or TLV 1A/1B/1C on Protocol-3 models.
  5. Modes: `AA 91 01 11` to read, `AA 91 07 10 …` to set.
  6. ANC/AA level: `AA 91 … 20 04/A1`.
  7. EQ preset: `AA A2`.
  8. Echo `B1` heartbeats.
  9. Reassemble AA frames across notifications and expect pushes.
- **H-P4 add-on**:
  - Assembly and splitting at MTU−15.
  - Status u16 check.
  - GET 0000 for capability discovery.
  - Mode 3: write the host-name CRC first. Mode 2: wait for PairingStatus.
  - Echo heartbeats.
  - Modes via 0018/0019/001A (and 0049 on the newer models). Battery via 000D/000E/1F03.
- **Quirks to plan for**:
  - Two control classes per BES model (classic vs LE Audio link).
  - Global and CN PIDs.
  - Name collisions (Tune vs Live 770NC).
  - Per-model gesture tables.
  - EQ gain range per model (±6 typical).
  - The JBL app must be closed: one control owner at a time.
  - BLE scan permission and address rotation.
  - Airoha SPP uses a secure RFCOMM socket, unlike Earshot's insecure Bose path.
- Effort estimate (L overall):
  - H-AA: M — about the size of the Soundcore driver, plus a new GATT link factory.
  - H-P4: S–M on top.
  - A-RACE: M, shared with other Airoha vendors.
  - Custom EQ on Airoha and H-P4 curves: L, because of the native coefficient maths. Defer it.

### Gaps
- None of this was tested on hardware.
- Priorities beyond the H-AA basics should follow captures from a real 2025 H-P4 device (security modes)
  and an Airoha JBL model.
