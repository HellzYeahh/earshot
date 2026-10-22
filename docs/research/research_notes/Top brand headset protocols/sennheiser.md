# Sennheiser (Sonova Consumer Hearing) ANC headphones and earbuds, 2020 onward: control protocol for an Earshot driver

Collected 2026-10-21. Static analysis only: no APK was installed or run, and no Bluetooth device was contacted.

**Sources and licences** (facts only from anything not MIT; no code copied):

| Key | Source | Licence | How used |
|---|---|---|---|
| **[APK]** | Sennheiser Smart Control **4.9.3** (`com.sennheiser.control`, versionCode 147651, APKMirror upload 2025-06-11), statically unpacked | Proprietary (Sonova Consumer Hearing GmbH) | Facts only |
| **[M4C]** | [Ariazonaa/momentum4-control](https://github.com/Ariazonaa/momentum4-control) | MIT | Clean-room project; its hardware captures on a Momentum 4 (fw 3.37.3 and 3.38.3) and a Momentum 5 (fw 6.21.5) are the only on-device evidence used here |
| **[MC]** | [gjabell/momentumctl](https://github.com/gjabell/momentumctl), mirror of [codeberg galen/momentumctl](https://codeberg.org/galen/momentumctl) | MIT | Cross-check |
| **[SDC]** | [sennheiser-desktop-client PROTOCOL.md](https://github.com/Oein/sennheiser-desktop-client/blob/main/PROTOCOL.md), also [zaval/sennheiser-desktop-client](https://github.com/zaval/sennheiser-desktop-client) | No licence stated | Facts only |
| **[GB]** | Gadgetbridge | AGPL-3.0 | Covers only the 2015-era Momentum In-Ear Wireless, so nothing from it is used |

**How [APK] is laid out.** It is a **NativeScript/Angular** app: the logic is minified JavaScript in `assets/app/bundle.js`, and the Bluetooth plumbing is Kotlin in `com.sennheiser.bluetooth.*`. The protocol is declared in **per-model JSON files** in `assets/app/`:
- headphones: `m4.json`, `ac1.json`, `acp1.json`
- true wireless: `tw2.json`, `tw3.json`, `tw4.json`, `atw1.json`, `msport1.json`
- CX line: `cx200.json`, `cx200sports.json`, `cx400II.json`, `cx500.json`
- older units: `hd450.json` and others
- optional feature files in `assets/app/features/`: `user-eq-1/2`, `audio-mode-1/2`, `sound-personalization-1/2`, `find-device-1`, `auracast-1`

Each file gives the vendor ID, command, response, notification and error IDs, plus the payload layout of every property.

**Citation for [APK] facts.** In-text citations of [APK] link to the [APKMirror listing](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/), which is where the analysed build came from. The path given next to each one is the file inside the APK.

---

## 1. Model scope, chips and protocols, how the app identifies a model, and Smart Control Plus

### Takeaway
Every Sennheiser ANC model from 2021 onward runs the same Qualcomm GAIA v3 command set under Sennheiser vendor ID `0x0495`, with identical command IDs across models. That covers MTW3, MTW4, Momentum 4, Accentum, Accentum Plus, Accentum TW, Momentum Sport, CX Plus TW and CX 500BT.

The 2020 Momentum True Wireless 2 and the HD 4x0BT generation use the older GAIA v1/v2 under vendor `0x0494`.

The app identifies a model from three things:
- the Bluetooth name;
- a GAIA "model ID" string (`0x0495/0x1206`);
- on BLE, an encrypted product ID in Sonova advertising data.

Sonova now ships **Smart Control Plus**, which is the only app for products launched after December 2024: Momentum 5, MTW5, Accentum Clip and HDB 630. Community captures show the Momentum 5 still speaks the same `0x0495` GAIA v3 set.

### Cited Findings
- **Per-model protocol.** [APK] declares a protocol for each model JSON. Product IDs are the advertising product ID, decoded below.

  | Model | Product ID | Protocol |
  |---|---|---|
  | Momentum 4 | 5 | `GAIAV3` |
  | Momentum True Wireless 3 | 4 | `GAIAV3` |
  | Momentum True Wireless 4 | 9 | `GAIAV3` |
  | Accentum | 7 | `GAIAV3` |
  | Accentum Plus | 8 | `GAIAV3` |
  | Accentum TW | 10 | `GAIAV3` |
  | Momentum Sport | 11 | `GAIAV3` |
  | CX Plus TW (`CX400II`) | 2 | `GAIAV3` |
  | CX 500 | 6 | `GAIAV3` |
  | CX True Wireless (`CX200`) | 1 | `GAIAV3` |
  | Sport True Wireless (`CX200_SPORTS`) | 3 | `GAIAV3` |
  | MTW2, CX 400BT, CX 350BT, HD 250BT, HD 350BT, HD 450BT, HD 450SE, HD 458BT, IE 80S BT | – | `GAIA` (v1/v2) |
  | Momentum 3, PXC 550 | – | `Nautilus` |
  | Soundbar | – | `ELMAR` |

  — [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/) `assets/app/*.json` `protocol` field
- **Two vendor IDs.** GAIA v3 models use vendor **`0x0495`** for Sennheiser commands and **`0x001D`** for Qualcomm core, upgrade, debug, voice-UI and statistics commands. GAIA v1/v2 models (e.g. MTW2) use vendor **`0x0494`** for Sennheiser commands and **`0x000A`** for Qualcomm-standard ones such as user-EQ control, power state and PS keys.
  — [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/) `m4.json`, `tw2.json`
- **Article numbers** (sent to Sonova's cloud for firmware checks):

  | Model | Article no. |
  |---|---|
  | M4 | 509266 |
  | MTW3 | 509180 |
  | MTW4 | 700365 |
  | AC1 | 700174 |
  | ACP1 | 700176 |
  | ATW1 | 700262 |
  | MSPORT1 | 700304 |
  | CX Plus | 509188 |
  | CX200 | 508973 |
  | CX200 Sports | 509299 |
  | CX500 | 700271 |
  | MTW2 | 300001 |

  — [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/) `parameters[].articleNumber`
- **Identification by Bluetooth name.** [APK] holds a name-to-type map:

  | Bluetooth name | Type |
  |---|---|
  | "MOMENTUM 4", "Mars" | M4 |
  | "MOMENTUM TW 3" | MTW3 |
  | "MOMENTUM TW 4", "MOMENTUM TW4" | MTW4 |
  | "ACCENTUM", "VENUS" | AC1 |
  | "ACCENTUM Plus", "APHRODITE" | ACP1 |
  | "ACCENTUM TW" | ATW1 |
  | "MOMENTUM SPORT", "MOMENTUM Sport", "MOMENTUM SPORT1", "Magnum", "MSPORT1-HR", "MSPORT1-Sensor" | MSPORT1 |
  | "CX Plus True Wireless", "CX Plus SE True Wireless", "CX 400BT TW2" | CX400II |
  | "CX True Wireless" | CX200 |
  | "Sport True Wireless" | CX200_SPORTS |
  | "CX 500BT" | CX500 |
  | "MOMENTUM TW 2" | MTW2 |
  | "HD 450BT", "HD 458BT", "HD 450SE" | HD line |

  Mars, Venus, Aphrodite and Magnum are internal codenames.
  — [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/) `bundle.js` `DeviceType.deviceTypeByBluetoothNameMap`
- **Identification over classic.** After connecting over classic Bluetooth, the app reads **`0x0495/0x1206` Versions_GetModelId** (a NUL-terminated string) and maps it to the exact model and colour variant. Example strings:
  - "M4AEBT Black", "M4AEBT White", "M4AEBT Graphite"
  - "MTW3 Black"
  - "MTW4 Black Graphite", "MTW4 White Silver", "MTW4 Black Copper"
  - "ACAEBT Black", "ACPAEBT White"
  - "ATW1 Black"
  - "MSPORT1 Olive"
  - "CXPLUSTW1 Black", "CX200TW1 Black", "CX200TW1 S", "CX500BT1 Grey"

  — [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/) `GaiaProduct.readModelNameViaGaia`, `DeviceType.getDeviceTypeByManufacturerName`

  On hardware, a Momentum 5 returned "M5AEBT Black" and a Momentum 4 returned a "…BT Black" string.
  — [M4C m5-6.21.5-parity.json](https://github.com/Ariazonaa/momentum4-control/tree/HEAD/tests/CapturedPackets/M5); [SDC](https://github.com/Oein/sennheiser-desktop-client/blob/main/PROTOCOL.md)
- **Identification over BLE (advertising).** The app reads manufacturer data under company keys **2979 (0x0BA3, "SONOVA")** and **1172 (0x0494, "SENNHEISER")**. The Sonova data is a TLV list:
  - type 0: 2-byte randomised address;
  - type 1: 16 bytes, AES-decrypted with a fixed key/IV embedded in the app (key not reproduced here). The plaintext holds the 6-byte public MAC, then a colour byte, then a product-ID byte.
  - Product IDs: 1 CX200, 2 CX400II, 3 CX200SPORTS, 4 MTW3, 5 M4, 6 CX500, 7 AC1, 8 ACP1, 9 MTW4, 10 ATW1, 11 MSPORT1.
  - Colours: 0 black, 1 white, 2 SE, 3 graphite, 4 grey, 5 olive, 6 silver, 7 copper, 8 JD, 9 denim, 10 pride, 11 blue, 12 R.

  BLE advertising also carries the 16-bit service UUIDs **FDCE** (Sennheiser) and **FCFE** (Sonova).
  — [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/) `BTPayloadDecoder`, `SmartUUID`
- **Internal platform name.** The app's internal name for the GAIA v3 platform is **"Herakles"**: the model-name tile is enabled when `protocol === GAIA3`, and the bonding type is `Herakles`.
  — [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/) `bundle.js` `ModelNameTileComponent`, `mW` enum
- **Smart Control Plus support list.** The App Store lists Smart Control Plus (Sonova Consumer Hearing GmbH) as compatible with:
  - MOMENTUM True Wireless 5, MOMENTUM 5, HDB 630, ACCENTUM Clip
  - MOMENTUM 4, MOMENTUM True Wireless 4, MOMENTUM True Wireless 3, MOMENTUM Sport
  - ACCENTUM, ACCENTUM Plus, ACCENTUM True Wireless
  - CX TW, CX Sport TW, CX TW Plus

  Version history:
  - 1.1.0 (2025-10-09): multipoint switch for MOMENTUM 4, MOMENTUM TW4, HDB 630, ACCENTUM and ACCENTUM Plus; HDB 630 parametric EQ.
  - 1.3.0 (Feb 2026): BTA1 and RS 275.
  - 1.5.0 (Jun 2026): ACCENTUM Clip.

  — [App Store AU listing](https://apps.apple.com/au/app/id6448098057)
- **Old app frozen.** Sennheiser says Smart Control "will not support products launched after December 2024". It recommends migrating MOMENTUM 4, MTW4, ACCENTUM, ACCENTUM Plus, MOMENTUM Sport and ACCENTUM TW to Smart Control Plus.
  — [Sennheiser UK Smart Control FAQ](https://uk.sennheiser-hearing.com/pages/smartcontrol)
- **Momentum 5 uses the same commands.** It answers the same `0x0495` GAIA v3 commands and framing (examples below), plus extra features: head tracking `0x3602/03`, immersive audio `0x3604/05`, battery protection `0x060A/0B`.
  - Its `0x1A01` mode table came back as `01 00 03 00 02 00`.
  - Its `0x1000` EQ config came back as `08 C4 3C 00 00`: 8 bands, −6.0 to +6.0 dB.

  — [M4C m5-6.21.5-hardware.json](https://github.com/Ariazonaa/momentum4-control/tree/HEAD/tests/CapturedPackets/M5); [M4C CommandCatalog.cs](https://github.com/Ariazonaa/momentum4-control/blob/HEAD/src/Momentum4.Protocol/Catalog/CommandCatalog.cs)
- **Chips.**
  - MTW4 is built on Qualcomm S5 Sound Gen 2 (Snapdragon Sound, aptX Lossless/Adaptive, with LE Audio/Auracast promised by firmware). — [Sennheiser newsroom](https://newsroom.sennheiser.com/sennheisers-nye-flaggskip-hodetelefoner-har-landet-momentum-true-wireless-4); [audioXpress](https://audioxpress.com/news/sennheiser-momentum-true-wireless-4-are-first-tws-earbuds-to-support-dual-mode-bluetooth-classic-and-le-audio)
  - A review attributes QCC5141 (S4) to the predecessor. — [Cybershack](https://cybershack.com.au/reviews/sennheiser-momentum-4-true-wireless-bt-anc-earphones-cant-get-better-than-this-av-review)
- **Launch dates.**
  - MTW3: April 2022.
  - Sport True Wireless: 2022.
  - Accentum: September 2023.
  - MTW4, Accentum Plus and Momentum Sport: CES, January 2024. MTW4 pre-orders opened 15 February; Accentum Plus followed on 20 February.

  — [What Hi-Fi](https://www.whathifi.com/news/sennheisers-triple-drop-includes-a-much-anticipated-sequel-to-the-momentum-true-wireless-3-earbuds); [Pickr](https://www.pickr.com.au/news/2024/sennheisers-anc-efforts-triple-with-momentum-accentum/); [digitec](https://www.digitec.ch/en/page/sennheiser-shows-two-new-headphones-23524)
- **HD 450BT, HD 458BT and HD 450SE: no ANC control.** Their JSON files expose battery, user-EQ, audio mode, sidetone and factory reset, but **no ANC property**, so their ANC is a hardware switch.
  - The MTW2 JSON does expose ANC: `0x0494/0x0708` set, `0x0788` get, u16 OFF 0 / ON 1 / ADAPTIVE 2.
  - It also exposes transparent hearing: `0x0494/0x0705` and `0x0785`.

  — [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/) `hd450.json`, `hd458.json`, `hd450se.json`, `tw2.json`

### Inferences
- **Treat the GAIA v3 set as one protocol.** Command IDs are identical across all eleven GAIA v3 JSONs. The only differences are which properties each model declares, so an Earshot driver can treat "Sennheiser GAIA v3" as one protocol with per-model capability tables.
- **One driver for most of the line.** A single `0x0495` driver covers MTW3, MTW4, M4, Accentum, Accentum Plus, Accentum TW, Momentum Sport and, going by the Momentum 5 captures, likely the Smart Control Plus generation.
- **v1/v2 is low value.** The GAIA v1/v2 models from 2020 are MTW2 (BLE-only in the app; see §2) and the HD 4x0BT line (no ANC control). They are low priority.
- **Name map for the first guess.** Earshot already lists bonded devices, so the Bluetooth name map plus the `0x1206` model ID is enough for identification. There is no need to decode Sonova's encrypted advertising.

### Gaps
- **Smart Control Plus was not analysed.** No Smart Control Plus APK was available in this session, so the protocol of MTW5, HDB 630, ACCENTUM Clip, BTA1 and RS 275 is unconfirmed. For Momentum 5 there is only [M4C]'s community evidence.
- **CX 500BT is unidentified.** I found no public product page for it (model string "CX500BT1 Grey"); it may be a regional model.
- **Chips** are unconfirmed for M4, Accentum, Accentum Plus, Momentum Sport and CX Plus. That they are Qualcomm is inferred only from the GAIA protocol.
- **Company IDs.** I did not check the Bluetooth SIG registry names for company IDs 0x0494 and 0x0495.

---

## 2. Transport, connection and initialisation, version negotiation, feature discovery, authentication

### Takeaway
On Android, the app controls all GAIA v3 headphones, MTW3/MTW4, Accentum TW and Momentum Sport over **classic RFCOMM**:
- **Service UUID:** Sennheiser's own **`a2129ff3-081b-4c45-8afe-469d9c4842ec`**, resolved by SDP. The app never uses a fixed channel.
- **Framing:** `FF ver flags len vendor16 cmd16 payload`.
- **No authentication.**

**BLE GATT** (no framing) is used for:
- CX Plus TW, CX 500BT, CX TW and Sport TW;
- MTW2 (GAIA v1/v2 characteristics);
- M4 and MTW3 on firmware older than 2.11.26.

The app performs no handshake for normal control: it sends commands and registers for notifications. GAIA version and supported-feature discovery exist but are used only by the firmware updater.

RFCOMM channel numbers differ by model (Accentum 15, M4 1 and 2). One community source reports that sending GAIA frames to the Accentum's iAP2 channel (14) **powers the headset off**. Earshot must therefore never probe channels on Sennheiser devices.

### Cited Findings
- **Bluetooth UUIDs.** [APK] `SmartUUID` constants:

  | Constant | UUID | Role |
  |---|---|---|
  | `GAIA3_SPP_UUID` | `a2129ff3-081b-4c45-8afe-469d9c4842ec` | RFCOMM, GAIA v3 |
  | `GAIA_SPP_UUID` | `a2129ff3-081b-4c45-8afe-469d9c4842eb` | RFCOMM, GAIA v1/v2 |
  | `NAUTILUS_SPP_UUID` | `1ddce62a-ecb1-4455-8153-0743c87aec9f` | Momentum 3 / PXC 550 |
  | `SENNHEISER_SERVICE` | `FDCE` | BLE service |
  | `SONOVA_SERVICE` | `FCFE` | BLE service |
  | `GAIA_BLE_SERVICE` | `63331337-23c1-11e5-b696-feff819cdc9f` | GAIA v1/v2 GATT service |
  | `GAIA_BLE_WRITE` / `READ` / `DATA` | `63331338-…`, `63331339-…`, `6333133a-…` | GAIA v1/v2 characteristics |
  | `GAIA3_BLE_WRITE` / `READ` / `DATA` | `6333133b-…`, `6333133c-…`, `6333133d-…` | GAIA v3 characteristics |
  | `BLE_PAIRING` | `63331379-…` | BLE pairing |

  All the `6333…` UUIDs share the base `-23c1-11e5-b696-feff819cdc9f`.
  — [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/) `bundle.js` `SmartUUID`
- **Per-model connection config.** `DeviceConfigurationService.getDeviceConfig` sets fields per model:
  - `preferredTransportAndroid`, `preferredTransportIOS`
  - `communicationService`, `writeCharacteristic`, `readCharacteristic`
  - `rfcommService`
  - `iapMinVersion`, `rfcommMinVersion`

  | Model | Android | iOS | BLE service | Characteristics | RFCOMM | Min firmware |
  |---|---|---|---|---|---|---|
  | M4 | CLASSIC | CLASSIC | FDCE | GAIA3 | `GAIA3_SPP_UUID` | iAP ≥ 2.13.16; RFCOMM ≥ 2.11.26 |
  | MTW3 | CLASSIC | BLE | FDCE | GAIA3 | `GAIA3_SPP_UUID` | iAP ≥ 3.1.1; RFCOMM ≥ 2.11.26 |
  | MTW4 | CLASSIC | CLASSIC | FCFE | GAIA3 | yes | iAP ≥ 4.7.0 |
  | ATW1 | CLASSIC | CLASSIC | FCFE | GAIA3 | yes | iAP ≥ 3.4.2 |
  | AC1, ACP1 | CLASSIC | CLASSIC | FCFE | GAIA3 | yes | – |
  | MSPORT1 | CLASSIC | BLE | FCFE | GAIA3 | yes | – |
  | CX400II (CX Plus), CX500 | BLE | BLE | FDCE | GAIA3 | **none** | – |
  | CX200 | BLE | BLE | FDCE | GAIA v1/v2 | none | – |
  | CX200_SPORTS | BLE | BLE | FDCE | GAIA v1/v2 | `GAIA_SPP_UUID` listed | – |
  | MTW2 | BLE | BLE | FDCE | GAIA v1/v2 | none | – |
  | HD 250/350/450/450SE/458BT | CLASSIC | BLE | – | – | `GAIA_SPP_UUID` | – |

  The RFCOMM column is `rfcommService` (`p`); the BLE service is the `communicationService`.
  — [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/) `bundle.js` `getDeviceConfig`
- **BLE fallback below `rfcommMinVersion`.** On Android, if the stored firmware is below `rfcommMinVersion`, the app switches the device to BLE.
  — [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/) `bundle.js`, log text "preferred transport … is RFCOMM, but the minimum FW is not installed … Switching to BLE transport"
- **RFCOMM connect.** `com.sennheiser.bluetooth.BluetoothSPP.connect`:
  1. opens a **secure** `createRfcommSocketToServiceRecord(UUID)`, so SDP resolves the channel;
  2. cancels discovery;
  3. tries `connect()` once.

  If the device's UUIDs are unknown, a JS state machine first runs `service_discovery` (an SDP UUID fetch). There is no reflection or channel-number path.
  — [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/) `BluetoothSPP.connect`; `bundle.js` `RFCommSMA`
- **Framing (from the app's own framer and deframer).** One GAIA v3 frame:

  | Field | Size | Meaning |
  |---|---|---|
  | SOF | 1 | `0xFF` |
  | version | 1 | 3 for GAIA v3 sessions, 1 for GAIA v1/v2 |
  | flags | 1 | bit0 = a trailing XOR checksum follows; bit1 = 16-bit length |
  | length | 1 (2 if bit1) | payload bytes **excluding** the 4-byte vendor+command header |
  | vendor | 2 | big-endian |
  | command | 2 | big-endian |
  | payload | n | |
  | checksum | 1, optional | XOR of all preceding bytes |

  - The framer sets bit1 and forces version 4 when the payload exceeds 254 bytes.
  - The deframer accepts versions 1, 3 and 4.
  - The app never enables the checksum.

  — [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/) `bundle.js` `SPPGaiaFramer`, `SPPGaiaDeframer`
- **Hardware frames that match.**
  - GET battery: `FF 03 00 00 04 95 06 03` → `FF 03 00 01 04 95 07 03 2A` (42 %). — [SDC](https://github.com/Oein/sennheiser-desktop-client/blob/main/PROTOCOL.md)
  - SET ANC on: `FF 03 00 01 04 95 1A 04 01` → `FF 03 00 00 04 95 1B 04`. — [M4C capture anc-off-on-adaptive](https://github.com/Ariazonaa/momentum4-control/tree/HEAD/tests/CapturedPackets)
  - [SDC] reads bytes 2–3 as a big-endian length. That gives the same bytes as the app's "flags + 1-byte length" whenever flags = 0 and length < 256.
- **BLE has no framing.** In BLE mode `DeviceControlTransport` creates no framer, so the ATT value is just vendor + command + payload. The app:
  - subscribes to notifications on `GAIA3_BLE_READ` (6333133C) under FDCE or FCFE;
  - writes to `GAIA3_BLE_WRITE` (6333133B);
  - requests an ATT MTU from the model config (default 92).

  — [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/) `DeviceControlTransport`, connect state machine in `bundle.js`
- **Command word.** The 16-bit command is `(feature << 9) | (type << 7) | pdu`:
  - response = command | `0x0100`
  - notification = command | `0x0080`
  - error = command | `0x0180`; the app treats `(id & 0x0180) == 0x0180` as an error and reads the error code from the first payload byte.

  GAIA v1/v2 commands differ: the response is command | `0x8000`, followed by a status byte (0 = success) before the payload.
  — [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/) `Gaia3Command.handleIncomingData`, `GaiaCommand.handleIncomingData`
- **Sennheiser `0x0495` feature numbers.** Derived from `id >> 9` and the RegisterNotification values:

  | Feature | Name | Contents |
  |---|---|---|
  | 0 | core | factory reset, primary side, register notification |
  | 1 | upgrade/DFU | |
  | 2 | device | on-head, physical state, BT compatibility, aptX Lossless |
  | 3 | battery/power | timers, charging, levels, eco, battery protection, reboot |
  | 4 | genericAudio | codec, prompts, sound mode, sidetone, smart pause, auto-answer, comfort call, low latency, sample rate |
  | 8 | userEQ | |
  | 9 | versions | |
  | 10 | deviceManagement | multipoint |
  | 11 | mmi | touch controls |
  | 12 | transparentHearing | |
  | 13 | ANC | |
  | 14 | role switch | |
  | 15 | fit test | |
  | 16 | personalisedSound | |
  | 17 | sensors | Momentum Sport |
  | 18 | MCU | |
  | 19 | tap detection | |
  | 20 | localName | |
  | 21 | Auracast | |
  | 22 | findDevice | |
  | 27 | head tracking / immersive | M5 |

  — [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/) model JSONs; feature 20 and 27 from [M4C CommandCatalog](https://github.com/Ariazonaa/momentum4-control/blob/HEAD/src/Momentum4.Protocol/Catalog/CommandCatalog.cs)
- **Qualcomm `0x001D` feature numbers in the app:** 0 Core, 1 Earbud, 2 AudioCurationOld, 3 VoiceUi, 4 Debug, 5 MusicProcessing, 6 DFU, 7 HandsetService, 8 AudioCuration, 9 FitTest, 10 VoiceEnhancement, 11 UserConfig, 12 Statistics.

  Core commands:
  - `0x0000` API version → `[major, minor]`
  - `0x0001` supported features → `[moreData, (id, ver)…]`
  - `0x0002` next page
  - `0x0003` serial
  - `0x0007` register notification
  - `0x0009` / `0x000A` data transfer
  - `0x000C` transport info `[key]` → `[key, u32]`
  - `0x000D` set transport parameter

  Transport keys: 1 max TX, 2 optimum TX, 3 max RX, 4 optimum RX, 5 TX flow, 6 RX flow, 7 protocol version, 8 payload size. A Core feature version > 3 means version-4 (extended-length) transport is supported.
  — [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/) `Gaia3FrameworkService`, `TransportInfo`
- **Negotiation happens only in the updater.** The Core framework init does API version, then supported features (looping on `moreData`), then transport info, then switches to transport v4. It runs only inside the firmware updater. Normal control gates features by **firmware-version thresholds** in the JSON (e.g. `ancMinFwVersion`, `Eq5Band_Gen1_MinFwVersion`, feature `minVersion`).
  — [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/) `Gaia3Updater.read_firmware_infoOnRun`, `MultiBandEqFactory`
- **Supported features on a Momentum 4 (fw 3.37.3).** Both lists fit in one reply (`moreData = 0`):
  - **Sennheiser:** `00 | 09 01 08 06 0C 01 03 02 10 02 04 02 02 01 0A 02 14 01 0B 01 0D 01 00 04`, i.e. features 9, 8 (v6), 12, 3, 16, 4, 2, 10, 20, 11, 13 and 0 (v4).
  - **Qualcomm:** `00 | 0D 01 0C 01 06 02 04 02 07 01 00 04`.

  — [M4C capture supported-features-mmi](https://github.com/Ariazonaa/momentum4-control/tree/HEAD/tests/CapturedPackets)
- **Notification registration.** RegisterNotification is `0x0495/0x0007 [featureId]`, acknowledged by an empty `0x0107`. Right after it, the headset sends the current state of that feature as notifications. Feature 10 sends no such dump.

  The app's MTW4 class registers features 4, 10, 12, 13, 3, 0, 2 and 11. Gen-2 EQ adds 8, personalisation adds 16, find-device 22 and Auracast 21.
  — [M4C capture stage5-notifications](https://github.com/Ariazonaa/momentum4-control/tree/HEAD/tests/CapturedPackets); [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/) `Tw4Product`, `features/*.json`
- **RFCOMM channels vary.**
  - Accentum: "GAIA" SDP record on RFCOMM **15**, HFP on 10, and Apple iAP2 (`00000000-deca-fade-deca-deafdecacaff`) on **14**. "Sending GAIA frames there powers the headset off". — [SDC](https://github.com/Oein/sennheiser-desktop-client/blob/main/PROTOCOL.md)
  - Momentum 4: **two** GAIA SDP records, on channels 1 and 2. — [M4C GaiaServiceResolver.cs](https://github.com/Ariazonaa/momentum4-control/blob/HEAD/src/Momentum4.Bluetooth/Rfcomm/GaiaServiceResolver.cs)
  - [M4C]'s comment labels the deca-fade UUID "Apple iAP2" and says the GAIA UUID must not be confused with it. — [M4C ProtocolConstants.cs](https://github.com/Ariazonaa/momentum4-control/blob/HEAD/src/Momentum4.Protocol/ProtocolConstants.cs)
- **[MC] connects by UUID too.** It opens RFCOMM by the same UUID through BlueZ Profile1. — [momentumctl ble.rs](https://github.com/gjabell/momentumctl)
- **iOS uses iAP2.** The app's iOS build reaches classic models over iAP2: `iapMinVersion` exists for M4, MTW3, MTW4 and ATW1. The deca-fade UUID does not appear anywhere in the Android build.
  — [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/) (grep of `bundle.js` and `classes*.dex`)
- **No authentication on RFCOMM.** No GAIA v3 property in any JSON is an authentication or challenge step, and the RFCOMM socket is a plain secure (bonded) socket. BLE models use an app-driven bonding flow (`BLE_PAIRING` characteristic, "Herakles" bonding) that the classic path does not need.
  — [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/) model JSONs, `BondEffects`

### Inferences
- **Classic path for Earshot.** Use `BluetoothDevice.createRfcommSocketToServiceRecord(a2129ff3-…-ec)` on the bonded device, as the app does. Use `fetchUuidsWithSdp` only if the cached UUIDs lack it. **Never** walk channel numbers the way the Bose driver does.
- **Route Sennheiser before any Bose probing.** If `RoutingHeadphoneRepository` ever sent a Sennheiser device through `BoseLinkFactory`, Bose's channel walk and its "SDP BMAP" step would target the deca-fade (iAP2) record. On an Accentum that may power the headset off. Sennheiser routing (name / model ID / GAIA UUID present) must happen before any BMAP probe.
- **Bose UUID note.** The same iAP2 label may explain Bose's "BMAP UUID" (see the landscape note). It is now reported by two independent community projects, [SDC] (unlicensed) and [M4C] (MIT), but Apple documentation does not confirm it.
- **Minimum transport.** Classic RFCOMM plus the 4-byte-header framer covers all 2022+ GAIA v3 headphones and earbuds on current firmware.
- **CX Plus needs BLE.** CX Plus TW and CX 500BT (and old-firmware M4/MTW3) would need a BLE GATT transport: unframed GAIA on FDCE/FCFE with 6333133B/6333133C. Earshot has no such transport today.
- **Use feature discovery anyway.** Earshot should call `0x0495/0x0001` and `0x001D/0x0001` itself and gate controls on features present and their versions; feature 8 v6 means "Gen-2 EQ". That is more robust than copying the app's firmware thresholds, and [M4C] showed it works on hardware.

### Gaps
- **Which M4 record answers.** When the M4 advertises two GAIA records, it is unknown which one (channel 1 or 2) the Android SDP lookup returns, and whether both answer. [M4C] used record 0, which is channel 1.
- **CX Plus RFCOMM.** No evidence on whether CX Plus TW exposes the GAIA v3 SPP UUID at all. The app never tries it.
- **The iAP2 power-off** is a single community observation on one Accentum, with no firmware version given.
- **GAIA v3 error-code names** (Qualcomm's manual is login-gated) are unconfirmed. Observed so far:
  - error reason `0x05` for invalid MMI arguments on M4 — [M4C capture](https://github.com/Ariazonaa/momentum4-control/tree/HEAD/tests/CapturedPackets);
  - the app treats error code 6 on an EQ write as success — [APK] `Eq3Band.send`.

---

## 3. Commands per feature (vendor `0x0495` unless marked; GAIA v3)

### Takeaway
The useful Sennheiser commands come in GET/SET pairs: SET = `…0`/even, GET = odd, response = +`0x0100`, notification = +`0x0080`.

Noise control works in one of three ways depending on the family:
- **Headband slider models** (Momentum 4, Accentum Plus): a mode table (anti-wind, comfort, adaptive) plus a 0–100 ANC↔transparency level.
- **Accentum:** two independent on/off toggles.
- **Earbuds** (MTW3/4, Accentum TW, Momentum Sport, CX Plus): ANC on/off plus anti-wind, and a transparency on/off toggle with a separate level.

EQ comes in two incompatible generations that reuse `0x1002`/`0x1003` for different things. The generation is chosen by firmware or by the feature 8 version.

### Cited Findings

**Payload notation.** u8, u16, u32 and i8 are byte sizes; multi-byte values are **big-endian**. Response and notification IDs follow the arithmetic in §2.

| Feature | SET (payload) | GET → response payload | Notification | Values / notes | Models (from the JSON) |
|---|---|---|---|---|---|
| **ANC on/off** | `1A04 [u8]` | `1A05` → `[u8]` | `1A85` | 0 off, 1 on | all GAIA v3 ANC models (not CX200 / CX200 Sports) |
| **ANC mode table** | `1A00 [mode,state]` | `1A01` → `[mode,state]×3` (6 bytes) | `1A81` | mode 1 = anti-wind (0 off, 1 on/max, 2 auto); 2 = comfort (0/1); 3 = adaptive (0/1) | declared for all GAIA v3 ANC models; on Accentum it is ignored (see below) |
| **ANC↔transparency level** (`ANC_Transparency`) | `1A02 [u8]` | `1A03` → `[u8]` | `1A83` | 0..100; 0 = full ANC, 100 = full transparency; UI slider 0..100 (M4, ACP1) | M4, AC1, ACP1, MSPORT1 |
| **Transparent hearing on/off** | `1804 [u8]` | `1805` → `[u8]` | `1885` | 0 off, 1 on | all GAIA v3 ANC models |
| **Transparent hearing level (TW)** | `1802 [u8]` | `1803` → `[u8]` | `1883` | earbuds UI uses presets 1 / 50 / 100 (low / mid / high) | all GAIA v3 ANC models (used by earbud classes) |
| **Transparency behaviour** | `1800 [u8]` | `1801` → `[u8]` | – | 0 keep music playing, 1 pause/stop music; UI "pause on full transparency" | all GAIA v3 ANC models |
| **Battery** | – | `0603` → `[p]`, `[L,R]` or `[L,R,case]` | `0683` | percent, `0xFF` = unknown (bud in case or charging) | all |
| **Charging state** | – | `0602` → `[L(,R)]` | `0682` | 0 disconnected, 1 charging, 2 complete | all |
| **Wear state** | – | `0402` → `[headset/L, R]` | `0482` | 0 unknown, 1 in case, 2 not on head, 3 on head | all (fw-gated) |
| **On-head detection** | `0400 [u8]` | `0401` | – | 0/1 | M4, AC1, ACP1 |
| **Smart pause** (auto-pause) | `080C [u8]` | `080D` | – | 0/1; on headbands only effective with on-head detection on | most; not AC1 |
| **Auto-answer calls** | `080A [u8]` | `080B` | – | 0/1 | ANC models |
| **Comfort call** | `0814 [u8]` | `0815` | – | 0/1 | M4, AC1, ACP1 |
| **Auto power-off** | `0600 [timer u8, seconds u16]` | `0601 [timer]` → `[timer, seconds u16]` | – | timer 0 = power-off, 1 = "limbo"; app offers 0 (never) / 900 / 1800 / 3600 s | all |
| **Sidetone** | `0805 [u8]` | `0806` | – | JSON says 0..5; app UI uses 0 off / 1 low / 2 high | most (MTW4 ≥ fw 5.3.0) |
| **Prompt mode** | `0801 [u8]` | `0802` | – | 0 off, 1 tones, 2 voice + tones | all |
| **Prompt language** | (no SET wired) | `0807` → `[code]`; `0808` available; `0809 [side]` versions | – | 0 EN, 1 DE, 2 FR, 3 ES, 4 ZH, 5 JA, 6 RU, 7 KO | all |
| **Sound mode** (audio mode) | `0803 [u16]` | `0804` → `[u16]` | `0884` | audio-mode-1: 1 user EQ, 2 speech/podcast, 3 flat; audio-mode-2: 0 off, 1 user EQ, 2 speech, 3 personalised | all |
| **Codec in use** | – | `0800` → `[u8]` | `0880` | 0 SBC, 1 AAC, 2 aptX, 3 aptX-LL, 5 aptX-HD, 7 LHDC, 8 aptX Adaptive, 9 aptX Lossless, 10 LC3 | all |
| **Sample rate** | – | `081A` → `[u32]` | `089A` | Hz | MTW4, AC1, ACP1, ATW1, MSPORT1 (fw ≥ 3.12.0) |
| **Low-latency mode** | `0817 [u8]` | `0818` | `0898` | 0/1 (the app keeps it hidden on M4/ACP1/MSPORT1 via min fw 99.99.99) | declared for M4, MTW3/4, AC1, ACP1, ATW1, MSPORT1 |
| **BT compatibility mode** | `0405 [u8]` | `0406` | – | 0 better audio, 1 better compatibility | M4, MTW3/4, ATW1, MSPORT1 |
| **aptX Lossless** | `0407 [u8]` | `0408` | – | 0/1 | MTW4 (fw ≥ 5.0.16) |
| **Battery eco mode** | `0608 [u8]` | `0609` | – | 0/1; disables aptX (and sport sensors on MSPORT1); apply with buds in case | MTW4, ATW1, MSPORT1 |
| **Battery protection** | `060A [u8]` | `060B` | – | 0/1; slower charging, stops below 100 % | MTW4, ATW1, MSPORT1; Momentum 5 ([M4C]) |
| **Touch controls off** (AutoLock) | `1606 [u8]` | `1607` | `1687` | 1 = touch disabled | all |
| **Touch area lock** | `1608 [u8]` | `1609` | – | 0/1 | MSPORT1 |
| **Tap sensitivity** | `2600 [u8]` | `2601` | – | 0 high, 1 mid, 2 low | MSPORT1 (fw ≥ 3.16.0) |
| **Button mapping** | `1600 [button, pattern, function]` | `1601 [button, pattern]` → `[b, p, f]` | `168A` button activity | button 0 right / 1 left; pattern 1 single, 2 double, 3 triple, 4 long… (see below); `1605` is-default; `1604` reset to default; `1602 [0/1]` discard/apply (CX200) | all; fw-gated |
| **Multipoint / paired list** | `1402 [idx]` connect | `1400` → `[count u16]`; `1401 [idx]` → `[idx, priority, status, name\0]`; `1404 [idx]` → `[idx, status]`; `1407` own index; `1409` max connections | `1484` connection change, `148C` refresh | status ≠ 0 = connected | all |
| **Versions** | – | `1201` → `[maj u16, min u16, patch u16]`; `1202` → per-side bytes; `1200` HW rev; `1206` model ID string; `1203/1204/1205` case HW / SW / serial | – | | case versions: TW models |
| **Fit test** | `1E00` start, `1E01` precondition | – | `1E80`/`1E81` `[L,R]` | 0–29 bad, 30–59 acceptable, 60–100 good, 254 invalid, 255 none | MTW3/4, ATW1, MSPORT1 |
| **Find device** | `2C00` ring, `2C01` stop, `2C02 [side]` ring one | `2C03 [side]` | `2C83` `[ringing, side]` | side 0 right, 1 left | MTW4/ATW1 (fw ≥ 5.8.17), MSPORT1 (≥ 3.18.30) |
| **Personalised sound** | `2000 [mode]`, `2002`/`2004`/`2006` thresholds / ratios / gains (REAL32 arrays) | `2001`/`2003`/`2005`/`2007` | `2081`, `2088` timeout | mode-2 values: 0 not parameterised, 1 calibrating, 2 calibrated, 3 inhibited | M4, ACP1, MTW3/4 |
| **Sport sensors** | `2201 [type, enable]` | `2200 [type]` → `[type, data…]` | `2280` | type 0 heart rate, 1 body temp, 2 battery temp, 3 sport scene, 4 temp raw | MSPORT1 |
| **Open-eartip / "adaptive acoustics"** | `0810 [u8]` | `0811` | `0891` | 0/1 | CX200 Sports |
| **Auracast assistant** | `2A02`…`2A05` add / modify / code / remove source; `2A23`/`2A24` scan | `2A21`, `2A22`, `2A25` | `2AA0`, `2AA1`, `2AA2`, `2AA5` | | MTW4, ATW1 (fw ≥ 5.13.1) |
| **Primary side** | – | `0041` → `[u8]` | – | 0 both, 1 left, 2 right | all GAIA v3 models except CX200 / CX200 Sports |

Sources for the table:
- [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/): `m4.json`, `tw3.json`, `tw4.json`, `ac1.json`, `acp1.json`, `atw1.json`, `msport1.json`, `cx*.json`, `features/*.json`; UI ranges from `bundle.js` (`defaultAutoPowerOff`, `PhoneCallsTileComponent`, `TransparentHearingTileComponent`, `FitQualityThresholds`, `mapBatteryValue`)
- [M4C CommandCatalog.cs](https://github.com/Ariazonaa/momentum4-control/blob/HEAD/src/Momentum4.Protocol/Catalog/CommandCatalog.cs) (MIT), which independently lists the same IDs and marks them verified on hardware
- [MC codec.rs](https://github.com/gjabell/momentumctl) (MIT): ANC, mode, level, on-head, smart pause, auto-answer, comfort call, battery
- [SDC PROTOCOL.md](https://github.com/Oein/sennheiser-desktop-client/blob/main/PROTOCOL.md)

**ANC behaviour on hardware (Momentum 4, fw 3.37.3).**
- `1A04 [01]` turns ANC on, switches transparent hearing **off** and sets the level to **0**.
- `1A04 [00]` leaves transparent hearing and the level unchanged.
- `1804 [01]` sets the level to 100.
- `1804 [00]` restores the level that was set before.
- `1A00` accepts **both** the single pair (`01 02`) and the full 6-byte table. Either one also turns transparent hearing off and sets the level to 0.
- Notifications `1A81`/`1A83` arrive about 30 ms after the response.

— [M4C captures anc-off-on-adaptive, stage6-both-formats, th-restores-level](https://github.com/Ariazonaa/momentum4-control/tree/HEAD/tests/CapturedPackets)

**What the app sends for noise control, per family:**
- **M4 and ACP1:**
  - adaptive: `ANC = [3, 0/1]`
  - wind: `ANC = [1, 0/1/2]`
  - transparency slider: `1A02 [0..100]`
  - pause-on-full-transparency: `1800`
- **AC1 (Accentum):**
  - wind: `ANC = [1, x]`
  - transparency: `1804` on when the UI value is 100, otherwise off
  - Reading `1A03 == 100` means "transparency mode".
- **MTW3, MTW4, ATW1:**
  - ANC: `ANC = [1,0]` + `1A04 01`
  - ANC + anti-wind: `ANC = [1,1]` + `1A04 01`
  - off: `1A04 00`
  - transparency: `1804` on/off, with the level via `1802`
- **MSPORT1:** as above, but "OFF" sets `1804 = on`, meaning transparency.
- **CX Plus and CX500:** `1A04` and `1804` are kept mutually exclusive: turning transparency on writes `1A04 = 0`, and turning ANC on writes `1804 = 0`.

— [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/) `Momentum4Product`, `Acp1Product`, `Ac1Product`, `Tw3Product`, `Tw4Product`, `ATW1Product`, `MomentumSport1Product`, `Cx400IIProduct`, `Cx500Product` (`sendFeatureToDevice`)

**Accentum.** On the Accentum the mode table `1A00`/`1A01` reads as zeros and ignores writes, so it is controlled with `1A04` and `1804` instead.
— [SDC](https://github.com/Oein/sennheiser-desktop-client/blob/main/PROTOCOL.md)

**EQ, Gen-2** (`user-eq-2`; the app uses it from M4 3.18.0, AC1/ACP1/MSPORT1 3.16.4, MTW4 5.0.0, ATW1 5.0.26):

| Command | Direction | Payload |
|---|---|---|
| `1000` | GET config | → `[bands, min i8, max i8, presets, userPresets]`, gains in dB×10 |
| `1001` | SET slider | `[band, gain i8 (dB×10)]` |
| `1002` | GET slider | `[band]` → `[gain i8]` |
| `1008` / `1009` | SET / GET sub-mode bitmask | bit0 = bass boost |
| `1082` | notification | all gains |
| `1089` | notification | sub-mode |

Ranges and band count by model:
- App range: ±6 dB, 5 bands; band centres 90 / 325 / 1500 / 6500 Hz (UI labels 63 / 250 / 1k / 4k / 8k).
- The Momentum 5 reports 8 bands.

On hardware ([M4C]):
- `1003 [00]` reads all gains: `3C 3C 16 16 0A` = 6.0 / 6.0 / 2.2 / 2.2 / 1.0 dB.
- `100B`, `100D` and `100F` read band frequency, Q (×4096) and filter type. Notifications `108B`/`108D`/`1091` carry them; e.g. frequencies 90 / 325 / 1500 / 6500 / 6500 Hz and Q 2048 / 2908 (0.5 / 0.71).

— [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/) `features/user-eq-2.json`, `Eq5BandGen2`, `getMultiBandGen2ParametersForDevice`; [M4C captures eq-band-bass-boost, stage5-notifications](https://github.com/Ariazonaa/momentum4-control/tree/HEAD/tests/CapturedPackets)

**EQ, Gen-1** (`user-eq-1`, older firmware):

| Command | Direction | Payload |
|---|---|---|
| `1002` | SET parameter group | list of 16-bit words |
| `1003` | GET parameter group | list of 16-bit words |
| `1004` / `1005` | SET / GET "knob" value | `[index u16, value u16]`; index 0 = X, 1 = Y |
| `1006` / `1007` | SET / GET level shift | i8, centibels |

Gen-1 parameter-group encoding:
- The word list is `[count, (paramId, value)×count, 0]`.
- Parameter IDs follow Qualcomm's user-EQ numbering: `0x0101` master gain; band *n* (*n* = 1..4) at `0x01n0` filter type (the app sends 11), `0x01n1` frequency (Hz×3), `0x01n2` gain (dB×60) and `0x01n3` Q (×4096).
- The app turns its 5 UI bands into a master gain plus 4 filter gains, with loudness compensation and headroom limits that differ per model.
- The knob values only store UI state (Y bit `0x1000` = bass boost, bit `0x4000` = new-storage marker).

Which engine the app picks:
- Gen-2 when the model has the `user-eq-2` feature and the firmware is new enough;
- otherwise Gen-1 when firmware ≥ `Eq5Band_Gen1_MinFwVersion` (M4/ACP1 2.12.0, MTW3 2.10.5);
- otherwise a 3-band legacy EQ.

— [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/) `features/user-eq-1.json`, `Eq5BandGen1`, `EqKnobValuesCoder`, `MultiBandEqFactory`, `get5BandParametersForDevice`

**Presets, bass boost and podcast.** EQ presets (Rock, Pop and so on) and "Sound Check" exist only in the app: they are sets of band gains written with the commands above. Sound Check requires a Sennheiser account and internet. "Bass boost" is the Gen-2 sub-mode bit (or extra gains in Gen-1), and "Podcast" is sound mode 2.
— [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/) `default3BandEqPresets`, i18n `soundcheck_intro_desc_3`, `Ac1Product.createProductFeatures`

**"Sound Zones"** (CX Plus `soundZonesMinFwVersion`) are the app's geofence "triggers". When the user enters a zone, the app sends a stored set of ANC/TH/EQ commands. There is no device-side feature.
— [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/) `TriggerEffects.getCmdByteArrayForSettings`, `GeofenceHandler`

**Button-mapping enums** (wire values from the app):
- **Functions:**
  - 0 none, 1 play/pause, 2 next, 3 previous, 4 vol+, 5 vol−
  - 6 transparent hearing, 7 voice assistant, 8 **power off**, 9 ANC
  - 10 launch app, 11 ANC adaptive toggle, 12 ANC comfort toggle, 13 lock toggle
  - 14 focus next device, 15 sound-mode toggle, 16 open-eartip toggle, 17 low-latency toggle
  - 18 ANC/TH toggle, 19 **disconnect all BLE devices**, 20 anti-wind toggle, 21 bass-boost toggle
  - 128 answer, 129 reject, 130 redial
- **Patterns:**
  - 1 single, 2 double, 3 triple, 4 long, 5 very long, 6 very-very long
  - 7 tap-hold, 8 two-finger tap-hold, 9 two-finger single, 10 two-finger double
  - 11 / 12 circle clockwise / counter-clockwise

— [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/) `bundle.js` MMI enums

On the M4 (fw 3.37.3), `1601` accepted only `[00 09]` (→ `00 09 00`). Every other button/pattern combination returned error reason `0x05`, so the `m4.json` enum does not fit the M4.
— [M4C capture supported-features-mmi](https://github.com/Ariazonaa/momentum4-control/tree/HEAD/tests/CapturedPackets)

**Device name.** Feature 20 has SET `2801` and GET `2802` (device name). [M4C] verified both on M4 3.37.3/3.38.3; Smart Control 4.9.3 keeps renaming disabled (`renameDeviceMinFwVersion` 99.99.99).
— [M4C CommandCatalog.cs](https://github.com/Ariazonaa/momentum4-control/blob/HEAD/src/Momentum4.Protocol/Catalog/CommandCatalog.cs); [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/) `m4.json`

**Qualcomm `0x001D` extras the app declares:**
- voice assistant: `0600` get selected (0 none, 2 Google, 3 Amazon), `0601` set, `0602` supported list. The app disables it for all models with min fw 99.99.99.
- statistics: `1800`–`1802`.
- debug panic log: `0803` info, `0804` erase.
- upgrade: `0C00`–`0C02`.

— [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/) `m4.json`

**Legacy GAIA v1/v2 (vendor `0x0494`), MTW2 2020, for reference:**
- battery `0182`, charging `0185`, earbud status `0183`
- ANC: `0708` set / `0788` get (u16)
- transparent hearing: `0705` / `0785`; mode `0704` / `0784`
- sidetone `0707` / `0787`, sound mode `0706` / `0786`, smart pause `0602` / `0682`, auto-answer `0604` / `0684`, touch lock `0601` / `0681`
- user-EQ control: vendor `0x000A`, `0220` / `02A0`; EQ group `0x000A/021B`

— [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/) `tw2.json`

### Inferences
- **Noise-control model in Earshot terms.**
  - **Headband slider family (M4, ACP1, likely M5):** ANC is "on" by design. Expose the level `1A02` as the ANC↔transparency slider (100 − level = cancelling strength), plus adaptive, anti-wind (off/max/auto) and comfort toggles through `1A00` single pairs, and a "transparency" quick toggle through `1804`.
  - **Earbuds:** expose a 3-state mode (Off / ANC / Transparency) via `1A04` + `1804`, an anti-wind toggle via `1A00 [01, x]`, and the transparency level via `1802`.
  - **Accentum:** two toggles only.
- **Capabilities.** Use read-back plus notifications as the source of truth. Several writes have side effects (§3 hardware notes), so after any noise-control write, re-read `1A05`, `1A03` and `1805`, or wait for notifications.
- **EQ engine.** Decide by feature 8's version from `0x0495/0x0001`: v6 on M4 3.37.3 means Gen-2. Treat `1002`/`1003` as Gen-1 only when Gen-2 is absent. Gen-2 has a clean, readable API; Gen-1 needs Earshot to reproduce the app's shelf-filter maths and is not worth it for an MVP.
- **Map onto Earshot's existing models:**
  - EQ: `HeadphoneState.eqRange` gets the min/max from `1000`; the band count is dynamic.
  - Battery: the 1/2/3-byte layout maps onto `Battery.aggregate`, with `0xFF` meaning unknown.
  - Auto-off: the presets map onto auto-off options (0/900/1800/3600 s).

### Gaps
- **"Comfort" mode** (ANC mode 2): its audible effect is unconfirmed. [M4C] calls it unknown. The app has an `ANTI_PRESSURE` behaviour and a "pressure" ANC animation for the M4 (`anc_mars`), which suggests pressure relief.
- **Earbud transparency level** (`1802`) range: the full range and step on earbuds is unconfirmed beyond the app's 1/50/100 presets.
- **Sidetone:** the real per-model maximum is unknown; the JSON says 0..5 but the UI shows 3 states.
- **Momentum Sport sensors:** payload formats per sensor type were not decoded.
- **Gen-1 filter type 11:** its meaning and the exact UI-to-shelf algorithm were not documented in detail.
- **Smart Control Plus features** (multipoint switch, parametric EQ for HDB 630, crossfeed) are unknown.

---

## 4. What must be blocked (never sent by Earshot)

### Takeaway
Block these:
- the whole firmware-update path, under both vendors (`0x0495` feature 1 and `0x001D` feature 6);
- factory reset `0x0495/0x0040`;
- the reboot and role-switch actions;
- deleting or disconnecting pairings (`0x1403`, `0x1405`, `0x1406`);
- debug and data-transfer commands;
- persistent-store writes and power-off on the legacy GAIA v1 units;
- writing to any RFCOMM channel other than the SDP-resolved GAIA v3 record (iAP2 power-off report).

Allow-list known GET/SET pairs instead of deny-listing.

### Cited Findings
- **Factory reset.** `0x0495/0x0040` Service_FactoryReset (response `0x0140`). The app follows it by deleting the bond and resetting its overlay (`factoryResetDetails`: `needsDeleteBonding`, `needsResetOverlay`). — [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/) all GAIA v3 JSONs
- **Firmware upgrade, Sennheiser feature 1.**
  - `0x0200` Upgrade_EnterDfuMode
  - `0x0201` Upgrade_SetUpgradeFilesize
  - progress notification `0x0282` (response `0x0302`)

  — [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/) `m4.json`
- **Firmware upgrade, Qualcomm feature 6.**
  - `0x001D/0x0C00` UpgradeConnect
  - `0x0C01` UpgradeDisconnect
  - `0x0C02` UpgradeControl, the tunnel for upgrade sync / start / data / validate / commit / abort
  - data indication `0x0C80`

  Also Momentum Sport's MCU upgrade feature `0x0495/0x2400`–`0x2403`; the reads are harmless, the feature is not needed.
  — [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/) `m4.json`, `msport1.json`
- **Reboot and role switch.**
  - `0x0495/0x060F` Reboot_Gaia (MTW4, ATW1, MSPORT1)
  - `0x0495/0x1C00` EnforceRoleSwitch (TW models; forces a primary/secondary bud swap)

  — [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/) `tw4.json`, `msport1.json`
- **Pairing list.**
  - `0x1403` DeviceList_Disconnect `[idx]`
  - `0x1405` DeleteEntry `[idx]`
  - `0x1406` DeletePDL (wipes the whole paired-device list)

  The app's `pairedDevicesDisconnectOwnDevice = true` means it can also disconnect the phone itself.
  — [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/) all GAIA v3 JSONs

  [M4C] blocks disconnect because "on some firmware that command wiped the pairing". Its own throwaway test on M4 fw 3.38.3 found that `1403 [03]` disconnected but did **not** delete the entry (4 devices before and after). — [M4C README](https://github.com/Ariazonaa/momentum4-control); [M4C capture disconnect-1403](https://github.com/Ariazonaa/momentum4-control/tree/HEAD/tests/CapturedPackets)
- **Debug and data transfer.**
  - `0x001D/0x0803` Debug_GetPanicLogInfo, `0x0804` Debug_ErasePanicLog
  - `0x001D/0x0009` / `0x000A` Core DataTransfer (the app uses them to pull crash dumps for upload)
  - `0x0495/0x080F` Debug_SmartPauseSensorCalibrationStart, `0x080E` raw sensor data

  — [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/) `m4.json`, `tw4.json`, `CrashdumpsService`
- **Other state changers to avoid by default:**
  - `0x2009` PersonalizedSound_Reset (erases the hearing profile)
  - `0x1604` MMI_SetDefaultConfig (resets the button layout)
  - `0x001D/0x000D` SetTransportParameter (changes framing to v4)
  - `0x001D/0x0601` voice-assistant select
  - `0x2801` SetLocalName (rename)

  — [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/); [M4C CommandCatalog](https://github.com/Ariazonaa/momentum4-control/blob/HEAD/src/Momentum4.Protocol/Catalog/CommandCatalog.cs), which marks `000D`, `0601`, `0817`, `1403`, `1405`, `1406`, `1604`, `0040`, `0200`, `0201`, `0C00`–`0C02`, `0803`, `0804`, `0009` and `000A` as Blocked
- **Button-function values to refuse** when writing `0x1600`: function 8 (power off) and 19 (disconnect all BLE devices). — [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/) MMI enum
- **Legacy GAIA v1/v2** (MTW2, CX 400BT):
  - `0x000A/0x0204` Control_SetPowerState: power off. This confirms the "0x0204 power-off" note in the earlier research.
  - `0x0494/0x0101` Service_FactoryReset
  - `0x000A/0x0712` Debug_Store_PsKey: writes the chip's persistent store
  - `0x000A/0x0710` / `0x0711` PS-key reads
  - DeviceList `0x0494/0x0803`, `0805`, `0806`

  — [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/) `tw2.json`, `cx400.json`
- **Transport level.** Do not open or write to the iAP2 record (`00000000-deca-fade-deca-deafdecacaff`, Accentum channel 14). GAIA frames there powered an Accentum off. — [SDC](https://github.com/Oein/sennheiser-desktop-client/blob/main/PROTOCOL.md)

### Inferences
- **Allow-list per vendor.** Extend Earshot's single write path (`bt/WriteGuard`) with a Sennheiser allow-list keyed on (vendor, command). Permit only the SET IDs in the §3 table, and refuse any command whose feature is 1 (`0x0495`), or 4 or 6 (`0x001D`), regardless of PDU.
- **GETs are safe.** GETs (odd PDUs, and the reads in features 9 and 10) are safe to send, as are RegisterNotification `0x0007` with feature IDs from the known list.
- **Treat connect as an action.** `0x1402` (connect a paired device) is safe but takes effect immediately, so it needs a user action. Never expose `0x1403`, `0x1405` or `0x1406`.

### Gaps
- **No full v1 upgrade list.** The legacy GAIA v1 upgrade command IDs (vendor `0x000A`) were not enumerated: MTW2 and HD 4x0BT are low priority, and a blanket block on `0x000A` except EQ control covers them.
- **Which firmware wiped pairings.** The specific firmware where `0x1403` deleted a pairing ([M4C README]) is not documented.

---

## 5. Community knowledge (projects, issues, write-ups)

### Takeaway
Three independent open projects agree with the APK on the `0x0495` command IDs and framing for the Momentum 4 and Accentum:
- [M4C] (MIT): the most thorough; hardware captures on M4 and M5 plus a safety-classed command catalogue;
- [MC] (MIT);
- [SDC] (unlicensed; facts only).

Gadgetbridge has no 2020+ Sennheiser support. I found no security write-up beyond the iAP2 power-off note.

### Cited Findings
- **[M4C] momentum4-control (MIT, 2026):**
  - Windows WinUI app plus a Linux Qt app and CLI.
  - Momentum 4 verified on fw 3.37.3 and 3.38.3; Momentum 5 verified on fw 6.21.5 (model M5AEBT), with other M5 firmware read-only; BTD 700 dongle not supported for control.
  - Talks RFCOMM + GAIA.
  - Blocks firmware update, factory reset and disconnect/delete of pairings in every policy.
  - Has no power-off control.
  - States it contains no Sennheiser, Sonova or Qualcomm software.

  — [README](https://github.com/Ariazonaa/momentum4-control); [THIRD_PARTY_NOTICES.md](https://github.com/Ariazonaa/momentum4-control/blob/HEAD/THIRD_PARTY_NOTICES.md)
- **[M4C] command policy.** Only "Verified" commands are sent in production. "NeedsHardwareTest" commands need an explicit test policy. "Blocked" and "Unknown" are never sent. — [CommandPolicy.cs](https://github.com/Ariazonaa/momentum4-control/blob/HEAD/src/Momentum4.Core/Queue/CommandPolicy.cs)
- **[M4C] long timeouts:** battery and paired-count reads 9 s, firmware version 10 s. That matches [APK]'s `send_command_timeout` of 9000/10000 ms. — [CommandCatalog.cs](https://github.com/Ariazonaa/momentum4-control/blob/HEAD/src/Momentum4.Protocol/Catalog/CommandCatalog.cs); [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/) `m4.json`
- **[MC] momentumctl (MIT, Rust).**
  - Features: Momentum 4 adaptive, ANC, anti-wind, auto-answer, battery, comfort call, on-head detection, smart pause and transparency level.
  - IDs it uses: `1A00`/`1A01`/`1A02`/`1A03`/`1A04`/`1A05`, `0400`/`0401`, `080A`/`080B`, `080C`/`080D`, `0814`/`0815`, `0603`.
  - Opens RFCOMM by UUID `a2129ff3-081b-4c45-8afe-469d9c4842ec`.
  - The README calls it "an alternative to the SmartControlPlus app", says ACCENTUM "may be supported", and credits [SDC].

  — [momentumctl](https://github.com/gjabell/momentumctl); [codeberg](https://codeberg.org/galen/momentumctl)
- **[SDC] sennheiser-desktop-client PROTOCOL.md (no licence).**
  - Based on Smart Control APK v1.5.0.
  - Accentum on channel 15.
  - Accentum's `1A00` table is ignored.
  - iAP2 channel 14 powers the headset off.
  - EQ Gen-2 IDs, with band centres 50 / 250 / 800 / 3000 / 8000 Hz; these are the app's AC1/ACP1 Gen-1 labels.

  — [PROTOCOL.md](https://github.com/Oein/sennheiser-desktop-client/blob/main/PROTOCOL.md)
- **[GB] Gadgetbridge (AGPL-3.0)** supports only the Momentum In-Ear Wireless (M2 IEBT). Its source has just `SennheiserMomentumProtocol.java`, `SennheiserMomentumSupport.java` and `SennheiserMomentumInEarWirelessCoordinator.kt`. — [gadgetbridge.org Sennheiser page](https://gadgetbridge.org/gadgets/headphones/sennheiser/); [Codeberg API listing of `service/devices/sennheiser`](https://codeberg.org/Freeyourgadget/Gadgetbridge)
- **iAP2 UUID.** A web search for the deca-fade UUID string found no Apple or Bluetooth SIG source. Apple's forums say iAP2 details are MFi-confidential. — [Apple Developer Forums thread 749182](https://developer.apple.com/forums/thread/749182)

### Inferences
- **Port from [M4C].** It is the best MIT base to port from, with attribution. Its safety-class catalogue, verified/unverified split and captured-packet tests fit Earshot's `WriteGuard` and virtual-device testing style. Its captures can become golden frames for a `VirtualSennheiser`.
- **Facts only from [SDC].** Copy no text; its unique facts (Accentum channel 15, the ignored `1A00`, iAP2 power-off) are recorded here as facts.

### Gaps
- **No other community sources found.** Searches turned up no XDA, Reddit or GitHub-issue reports of bricking or power-off beyond [SDC] and [M4C]'s README, and no security write-up on Sennheiser/Sonova GAIA. Searches were limited (standard web search only).

---

## 6. Effort, quirks, and what a minimum viable Sennheiser driver needs

### Takeaway
An MVP "Sennheiser GAIA v3" driver is **medium effort**, smaller than the Bose driver. It needs:
- one transport (RFCOMM by UUID, 4-byte-header framing, one request in flight);
- feature discovery;
- notification registration;
- about 25 GET/SET pairs: battery, charging, wear, ANC, transparency, level, mode table, EQ Gen-2, sound mode, on-head, smart pause, auto-answer, comfort call, auto-off, sidetone, prompts, codec, touch lock, multipoint read/connect;
- three per-family noise-control mappings.

CX Plus TW / CX 500BT (BLE-only in the app) and the 2020 GAIA v1/v2 units are second-phase. The newer Smart Control Plus generation does not appear to break the approach (Momentum 5 matches).

### Cited Findings
- **Firmware thresholds the app uses to unlock features:**

  | Model | Thresholds |
  |---|---|
  | M4 | voice prompts 2.6.12; customisable controls 2.12.0; auto-answer 2.13.28; on-head detection 2.6.3; BT compatibility 2.12.0; physical state 2.12.0; Gen-1 EQ 2.12.0; Gen-2 EQ 3.18.0; personalisation 2.12.0 |
  | MTW3 | ANC 2.3.0; TH slider 2.3.0; BT compatibility 2.9.0; fit test 2.11.23; Gen-1 EQ 2.10.5 |
  | MTW4 | sample rate 3.12.0; sidetone 5.3.0; lossless 5.0.16; reboot 5.3.0; button activity 5.8.32; Gen-2 EQ, audio-mode-2 and personalisation-2 5.0.0; find device 5.8.17; Auracast 5.13.1 |
  | MSPORT1 | fit test 3.17.14; tap sensitivity 3.16.0; bass-boost and podcast toggles 3.17.3; high-resolution 3.17.14 |
  | CX Plus | ANC, TH and sound zones 1.7.28 |

  — [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/) model JSON `parameters`
- **Version numbers.** Firmware version comes from `0x1201` (3 × u16 major/minor/patch). The M5 replied `00 06 00 15 00 05` = 6.21.5. — [M4C capture](https://github.com/Ariazonaa/momentum4-control/tree/HEAD/tests/CapturedPackets/M5)
- **Quirks observed on hardware (M4):**
  - writes return an empty response, then a notification about 30 ms later;
  - registration triggers a full-state dump;
  - ANC-enable side effects (TH off, level 0);
  - `1804` off restores the previous level;
  - MMI enum mismatch;
  - auto-off writes send no notification even with feature 3 registered.

  — [M4C captures](https://github.com/Ariazonaa/momentum4-control/tree/HEAD/tests/CapturedPackets)
- **Serialised requests.** The app talks to the device through one property layer with per-command timeouts (6–15 s for slow ones). [M4C] runs a GAIA command queue that keeps one request in flight. — [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/) `send_command_timeout`; [M4C GaiaCommandQueue.cs](https://github.com/Ariazonaa/momentum4-control/blob/HEAD/src/Momentum4.Core/Queue/GaiaCommandQueue.cs)
- **Gen-1/Gen-2 ID collision.** `0x1003` is a different command in Gen-1 (EQ_UserParameterGroup_Get) and in Gen-2 firmware (all band gains on M4 3.37.3). `0x1002` is Gen-1 "set parameter group" but Gen-2 "get slider gain". — [APK](https://www.apkmirror.com/apk/sonova-consumer-hearing-gmbh/sennheiser-smart-control/) `features/user-eq-1.json` vs `user-eq-2.json`; [M4C capture eq-band-bass-boost](https://github.com/Ariazonaa/momentum4-control/tree/HEAD/tests/CapturedPackets)
- **Sonova recommends Smart Control Plus** for current firmware updates on the 2022–2024 models. — [Sennheiser UK FAQ](https://uk.sennheiser-hearing.com/pages/smartcontrol)

### Inferences
**MVP plan for Earshot**, mapped onto its current architecture:
1. **`SennheiserLinkFactory`.** Implements Earshot's `LinkFactory`:
   - RFCOMM with `createRfcommSocketToServiceRecord(a2129ff3-…-ec)`; no channel walk; no Bose probe on Sennheiser names;
   - liveness probe = `0x001D/0x0000` API version (expect `03 xx`) or `0x0495/0x1206`.
2. **Framer.** A `:protocol` `dev.earshot.protocol.sennheiser` package:
   - a GAIA v3 framer and deframer (version 3, flags 0, 1-byte length, BE vendor and command), accepting version 4 / extended length on input;
   - command word helpers (type bits `0x0080`, `0x0100`, `0x0180`) and an error-code read.
3. **Identification.** Bonded name → first guess; then `1206` → exact model; then `1201` → firmware; then `0x0495/0x0001` and `0x001D/0x0001` → capabilities.
4. **Subscribe.** `0x0007` for 3, 4, 2, 8, 10, 11, 12, 13, then consume the state dump. After that, no polling is needed beyond an occasional battery read.
5. **Repository.** A `SennheiserHeadphoneRepository` with three noise-control strategies:
   - `SliderFamily` for M4/ACP1/M5;
   - `ToggleFamily` for AC1;
   - `EarbudFamily` for MTW3/MTW4/ATW1/MSPORT1/CX Plus.
   - Earshot's 0..10 "10 = most cancelling" maps on the slider family as level = (10 − n) × 10.
6. **EQ.** Gen-2 only (dynamic band count from `1000`). Hide EQ on Gen-1 firmware; tell the user to update the firmware with Sonova's app.
7. **Guard.** An allow-list `WriteGuard` (§4).
8. **Tests.** A `VirtualSennheiser` built from [M4C]'s MIT captures for golden-frame tests.

**Phase 2:**
- a BLE GATT transport (FDCE/FCFE + 6333133B/6333133C, unframed) for CX Plus TW and CX 500BT;
- MTW2 over GAIA v1 BLE (`63331338`/`63331339`, vendor `0x0494`);
- Momentum Sport extras (sensors, tap sensitivity);
- Auracast assistant;
- find-my-earbuds.

**Effort:** small to medium for the MVP: one transport, about 25 commands, three noise strategies. Medium for BLE.

**Risk:** low, provided routing never sends Bose BMAP probes to Sennheiser devices and the allow-list is enforced.

**Do the newer models break this?** Not on current evidence. The Momentum 5 under Smart Control Plus answers the same IDs, adding feature 27 and an 8-band EQ. The 2025–2026 products (HDB 630, MTW5, Accentum Clip) are unverified.

### Gaps
- **Untested models.** No hardware evidence for MTW3, MTW4, Accentum Plus, Accentum TW, Momentum Sport or CX Plus. Their behaviour comes only from the app's JSON and code.
- **Smart Control Plus changes.** No Smart Control Plus APK was analysed, so new commands or firmware-side changes in that generation (e.g. multipoint switch, parametric EQ) are unknown.
- **Pre-2.11.26 firmware.** Whether M4/MTW3 units below 2.11.26 can answer GAIA v3 over RFCOMM at all is unknown; the app switches them to BLE.
