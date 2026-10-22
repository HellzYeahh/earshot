# Anker Soundcore ANC headphones and earbuds (2020 onward): Bluetooth control protocol

Research notes for Earshot roadmap item E2 ("Soundcore"), written 2026-10-21. Static analysis only: no APK was
installed or run, and no Bluetooth device was contacted. Facts are restated in my own words. Licences of the
sources: Soundcore app = proprietary (facts only); OpenSCQ30 = GPL-3.0 (facts only); Gadgetbridge = AGPL-3.0
(facts only); SoundcoreManager = no licence (facts only); soundcorebridge = MIT; Anker SoundcoreSDKDemo =
Apache-2.0 (covers the D3200 recorder only). Earshot's existing Sleep A30 (D1301) driver was read, not edited.

Short names used below:
- **OSCQ** = OpenSCQ30 at commit `200d9e1` (2026-10-19), `lib/src/devices/soundcore/...`
- **Faker** = OpenSCQ30's `tools/soundcore-device-faker/devices/<model>.toml`: real state captures with
  per-byte comments, contributed by owners.
- **GB** = Gadgetbridge `service/devices/soundcore/...` and `devices/soundcore/...` (Codeberg, master).
- **SCB** = soundcorebridge (MIT): a verified Space 2 (D1402) protocol map.
- **APK** = the user's Soundcore 5.0.32 (`com.oceanwing.soundcore`, versionCode 431) base.apk and splits.
- Command ids are written as two bytes `[category code]`, as they appear on the wire (`06 81` = category 6,
  code 0x81). Codes 0x00..0x7F read or report, 0x80..0xFF write.

---

## 1. Model scope and identification

### Takeaway
About 40 Soundcore models since 2020 have publicly documented layouts. Each has a product code: `A3xxx`/`A6xxx`
for older lines, `D1xxx` for 2024+ lines. There are five protocol "families" by state layout (gen-1 SPP, gen-2 TWS,
gen-2 headphones, sleep, and a new 2025-26 TLV generation). Earshot can identify a model from three things: the
vendor RFCOMM UUID suffix in SDP, the Bluetooth name, and, most reliably, the first four characters of the serial
number in the `01 01` state, which are the model digits. The official app's own mapping logic could not be
recovered, because its Java code is encrypted by a commercial packer.

### Cited Findings

#### What the APK yielded (and what it did not)
- The base.apk holds only a 303 KB shell `classes.dex` with 301 classes: the packer shell (`s.h.e.l.l.A/C/N/S`),
  the ARouter route tables and the AndServer library. The real bytecode sits encrypted in `assets/ijiami.dat`
  (30.9 MB) and `assets/ijiami.ajm` (9.0 MB), both at about 7.98-7.99 bits/byte of entropy, with the Ijiami
  loader libs in `assets/ijm_lib/*/libexec.so` and `libexecmain.so`. No headphone protocol class could be
  decompiled. Unpacking would mean running the app or defeating the packer, both outside this task's rules —
  [APK base.apk, local static analysis](file:///C:/Users/Hellz/AppData/Local/Temp/claude/C--Users-Hellz-Desktop-earshot/8ff49ff3-c9c6-4f41-b13e-33c2f63035a9/scratchpad/apks/com.oceanwing.soundcore/base.apk)
- The splits hold no dex. `split_soundcoreso.apk` carries native libs: `libjl_ota_auth.so` (JieLi OTA,
  JNI `com.jieli.jl_bt_ota`), `libecc-encryption.so` (JNI `com.anker.esiotkit...EccEncryption`, micro-ECC plus
  AES-128-CBC), `libsecuritytool.so`, opus, snore detection, deep AEC and others. `split_lib_asset.apk` carries
  media (HearID tones, sleep sounds, PAG animations named after models such as `d1302_welcome`,
  `a6611_sleep_homepage`, `a3953_gif_fittest_guide2`) —
  [APK splits](file:///C:/Users/Hellz/AppData/Local/Temp/claude/C--Users-Hellz-Desktop-earshot/8ff49ff3-c9c6-4f41-b13e-33c2f63035a9/scratchpad/apks/com.oceanwing.soundcore/)
- The Flutter module (`libapp.so`, 21 MB, package `module_flutter`) covers the D3200 recorder, AI chat, the
  HearID test UI and "Anker Day". Its strings contain no `0cf12d31` UUID and no `08 EE` framing, so the device
  protocol is in the encrypted Java layer, not Dart —
  [APK lib/arm64-v8a/libapp.so](file:///C:/Users/Hellz/AppData/Local/Temp/claude/C--Users-Hellz-Desktop-earshot/8ff49ff3-c9c6-4f41-b13e-33c2f63035a9/scratchpad/apks/com.oceanwing.soundcore/base.apk)
- `libscsecurity.so` holds the app's cloud API secrets (JNI `com.anker.scsecurity.SCSecurityLib`). They are
  irrelevant to Bluetooth and deliberately not recorded here. `libscnativelib.so` is DSP and health code (deep AEC,
  SBC decoder, LZF, "dailycare" heart-rate maths), not protocol code —
  [APK base.apk native libs](file:///C:/Users/Hellz/AppData/Local/Temp/claude/C--Users-Hellz-Desktop-earshot/8ff49ff3-c9c6-4f41-b13e-33c2f63035a9/scratchpad/apks/com.oceanwing.soundcore/base.apk)
- The decoded manifest (readable, not encrypted) shows about 100 per-model activity packages named by product
  code: `com.oceanwing.soundcore.activity.a3951`, `.a3947`, `.a3953`, `.a3954`, `.a3955`, `.a3957`, `.a3959`
  (none), `.a3936`, `.a3933`, `.a3040`, `.a3035`, `.a3062`, `.a6611`, `.d1301`, `.d1302`, `.d1303`, `.d1402`,
  `.d1404`, `.d1406`, `.d3200` and so on. It also shows chip-specific OTA screens:
  `com.oceanwing.ota.ui.CommonABOtaActivity`, `CommonActionsOtaActivity`, `CommonBesOtaActivity`,
  `CommonBes2OtaActivity`, `CommonCombainOtaActivity`, `CommonJLOtaActivity`, `CommonWqActivity`,
  `CommonWqV2Activity` and `CommonWqV2BleActivity`, plus a Qualcomm `com.qualcomm.qti.gaiacontrol.services`
  service and the Kable BLE library (`com.juul.kable.KableInitializer`) —
  [APK AndroidManifest.xml](file:///C:/Users/Hellz/AppData/Local/Temp/claude/C--Users-Hellz-Desktop-earshot/8ff49ff3-c9c6-4f41-b13e-33c2f63035a9/scratchpad/apks/com.oceanwing.soundcore/base.apk)
- `resources.arsc` key names are prefixed by model code. Approximate counts: a3951 335, a3952 241, a3953 203,
  a3954 249, a3956 249, a3957 61, a3947 44, a3936 37, a3040 85, a3035 56, a3062 44, a6611 678, d1301 556,
  d1302 282, d1204 146, d1203 24, d1205 24, d1206 19, d1402 52. Earbud/headphone codes present include A3004,
  A3005, A3027, A3028, A3029, A3033, A3035, A3040, A3062, A3330, A3388, A3850, A3871-A3879, A3909, A3910,
  A3926-A3968, A3982/A3983, A6610, A6611, D1101, D1105, D1200-D1206, D1301-D1303, D1402-D1406 —
  [APK resources.arsc](file:///C:/Users/Hellz/AppData/Local/Temp/claude/C--Users-Hellz-Desktop-earshot/8ff49ff3-c9c6-4f41-b13e-33c2f63035a9/scratchpad/apks/com.oceanwing.soundcore/base.apk)
- Product names are mostly server-delivered. Only a few names are local strings (for example "Liberty 5 Pro Max",
  "Sleep A10", "Sleep A20", "AeroFit 2", "Space A40", "Liberty 4 NC"), so the APK alone cannot give a complete
  code-to-name table — [APK resources.arsc values](file:///C:/Users/Hellz/AppData/Local/Temp/claude/C--Users-Hellz-Desktop-earshot/8ff49ff3-c9c6-4f41-b13e-33c2f63035a9/scratchpad/apks/com.oceanwing.soundcore/base.apk)
- Older, unpacked builds of the app that the community decompiled show the architecture. For each model there is
  an `AxxxxDeviceModel` that creates an `AxxxxCmdService` (writes) and an `AxxxxAnalysisService` (parses reads).
  Both are handed to `Cmm2BtDeviceManager`, with a shared command catalogue in `Cmm2CmdData`/`CmmBtCmdService`
  and dispatch through `BaseBtDispatch`. Per-model command services inherit from each other, for example
  `A3040CmdService` from `A3952CmdService` from `CmmBtCmdService` —
  [SoundcoreManager A3040.md (no licence)](https://github.com/gmallios/SoundcoreManager/blob/HEAD/soundcore-lib/A3040.md);
  OSCQ comments cite the same names (`A3953AnalysisService`, `A3953CmdService`, `Cmm2CmdData`,
  `HearId2Utils`, `BaseControllerSelectVM`) for a 6.4.0 app build —
  [OSCQ a3953/packets/state_update.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/a3953/packets/state_update.rs),
  [OSCQ a3953/structures.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/a3953/structures.rs)

#### Code → model → family table
Family keys: **G1s** gen-1 single-battery headphones on SPP; **G1t** gen-1 TWS on SPP; **G2t** gen-2 TWS on the
vendor UUID; **G2h** gen-2 headphones on the vendor UUID; **SL** sleep-earbud header; **TLV** 2025-26 tag-length-value
state; **FE** fixed layout with float or parametric EQ. "Modes" names the sound-mode codec described in section 3.

| Code | Product (source) | RFCOMM service seen | Family | Modes codec | ANC |
|---|---|---|---|---|---|
| A3027 | Life Q35 (OSCQ README) | vendor or SPP (dynamic) | G1s | v1-4 | yes |
| A3028 | Life Q30 / Q30 (OSCQ, Faker, GB) | SPP `00001101` (Faker; GB) | G1s | v1-4 | yes |
| A3029 | Life Tune (OSCQ, GB) | parsed as A3028 | G1s | v1-4 | yes |
| A3030 | Life Tune Pro (OSCQ) | parsed as A3027 | G1s | v1-4 | yes |
| A3031 | Vortex (OSCQ) | dynamic | own (TWS-like) | v1-4 | per OSCQ |
| A3033 | Life 2 Neo (OSCQ) | dynamic | G1s | none | no |
| A3004 | Q20i (OSCQ, Faker) | SPP (Faker) | G1s variant | v1-4 | yes |
| A3005 | Q11i (OSCQ, Faker) | `…d6832e7b3005` | G1s variant | none | no |
| A3035 | Space One (OSCQ, Faker) | `…b3035` | G2h | v2-A3035 (6 B) | yes |
| A3040 | Space Q45 (OSCQ, Faker) | `…b3040` | G2h | v2-A3040 (6 B) | yes |
| A3062 | Space One Pro (OSCQ, Faker) | `…b3062` | G2h | v2-A3062 (6 B) | yes |
| D1402 | Space 2 (SCB; retailer listing) | `…d1402`, RFCOMM ch 30 on the tested unit | G2h-like | Space2 (6 B) | yes |
| A3951 | Liberty Air 2 Pro (OSCQ, Faker) | SPP (Faker) | G1t | v1-4 | yes |
| A3931 | Life Dot 2 NC (OSCQ, Faker) | SPP (Faker) | G1t | v1-4 | yes |
| A3935 | Life A2 NC (OSCQ) | parsed as A3931 | G1t | v1-4 | yes |
| A3930 | Liberty 2 Pro+ (OSCQ) | SPP, constant (OSCQ) | G1t | v1-4 | yes |
| A3933 | Life Note 3 (OSCQ, Faker) | `…b3933` | G2t | v1-4 | yes |
| A3939 | Life P3 (OSCQ, Faker) | `…b3939`, parsed as A3933 | G2t | v1-4 | yes |
| A3945 | Life Note 3S (OSCQ, Faker) | `…b3945` | G2t | none | no |
| A3936 | Space A40 (OSCQ, Faker) | `…b3936` | G2t | v2-A3936 (6 B) | yes |
| A3952 | Liberty 3 Pro (OSCQ, Faker, GB) | `…b3952` | G2t | v2-A3952 (6 B) | yes |
| A3953 | Liberty 4 (OSCQ, Faker) | `…b3953` | G2t | v2-A3953 (6 B) | yes |
| A3947 | Liberty 4 NC (OSCQ, Faker, GB) | `…b3947` | G2t | v2-A3947 (7 B) | yes |
| A3954 | Liberty 4 Pro (OSCQ, Faker) | `…b3954` | G2t + case block | slider (4 B) | yes |
| A3955 | P40i (OSCQ, Faker) | `…b3955` | G2t | v2-A3955 (7 B) | yes |
| A3957 | Liberty 5 (OSCQ, Faker) | `…b3957` | G2t | v2-A3957 (7 B) | yes |
| A3959 | P30i / R50i NC (OSCQ); R50i NC capture | `…b395B` (sic) on the R50i NC capture | G2t | v2-A3959 (7 B) | yes |
| A3958 | A30i (GB, Faker) | `…b3958` | G2t | same layout as A3959 (7 B) | yes |
| A3968 | Sport X20 (OSCQ, Faker, GB) | `…b3968` | G2t | v2-A3968 (6 B) | yes |
| D1202 | P31i (OSCQ, Faker) | `…d1202` | G2t | v2-D1202 (8 B) | yes |
| D1202C | R60i NC (OSCQ, Faker) | `…d6832e71202c` (sic) | as D1202 | v2-D1202 | yes |
| A3948 | A20i (OSCQ, Faker) | `…b3948` | G2t | none | no |
| A3949 | P20i / P25i / R50i (OSCQ, Faker) | `…b3949` | G2t | none | no |
| A3944 | Life P2 Mini (OSCQ, Faker) | `…b3944` | G2t | none | no |
| A3330 | C30i (OSCQ, Faker) | `…b3330` | G2t variant | none | no |
| A3388 | AeroClip (OSCQ, Faker) | `…b3388` | G2t variant | none | no |
| A3876 | V20i (OSCQ, GB, Faker) | `…b3876` | G2t variant | none | no |
| A3874 | AeroFit 2 (GB) | GB derives `…b3874` | unknown (GB writes only) | none | no |
| D1101 | C50i (OSCQ, Faker) | `…d1101` | G2t variant | none | no |
| A6611 | Sleep A20 (Faker; APK strings) | `…b6611` | SL | none | no |
| D1301 | Sleep A30 (Earshot driver, OSCQ) | `…d1301` | SL | `06 87` on/off | on/off |
| D1203 | Liberty 5 Pro (OSCQ issue #342; retailer listing) | not captured | TLV | read-only map | yes |
| D1204 | Liberty 5 Pro Max (OSCQ issue #304; retailer listing) | not captured | TLV | read-only map | yes |
| D1206 | Liberty Buds 2 (serial `1206…` in OSCQ issue #411) | not captured | TLV | unknown | ? |
| D1205 | P42i (OSCQ issue #336) | not captured | FE | unknown | ? |
| A3875 | AeroFit 2 Pro (serial `3875…` in OSCQ issue #285) | not captured | FE | unknown | no |
| A3878 | V40i (serial `3878…` in OSCQ issue #357) | not captured | new layout | unknown | no |

Sources for the table: [OSCQ README / device_model.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/device_model.rs);
[Faker captures (names, rfcomm_uuid)](https://github.com/Oppzippy/OpenSCQ30/tree/200d9e1657dff67d516ed1b6347f312d7a1b01a7/tools/soundcore-device-faker/devices);
[GB coordinators (A3958 = A30i, A3874 = AeroFit 2, A3947, A3952, A3968, A3876, A3028/A3029)](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/soundcore);
[SCB protocol map (D1402)](https://github.com/mervin008/soundcorebridge/blob/HEAD/docs/protocol-map.md);
[OSCQ issue #304 (D1204 TLV map)](https://github.com/Oppzippy/OpenSCQ30/issues/304); [#342](https://github.com/Oppzippy/OpenSCQ30/issues/342);
[#411](https://github.com/Oppzippy/OpenSCQ30/issues/411); [#336](https://github.com/Oppzippy/OpenSCQ30/issues/336);
[#285](https://github.com/Oppzippy/OpenSCQ30/issues/285); [#357](https://github.com/Oppzippy/OpenSCQ30/issues/357).
The Space 2 / Liberty 5 Pro / Pro Max codes are also on retail listings found by search
([Taiwan Yahoo listing, D1402](https://tw.buy.yahoo.com/brands/41170689?pg=2); [nttxstore JP listings, D1203/D1204](https://nttxstore.jp/shop/c/c0632_p16/)).

#### How a device maps to a model
- **Vendor UUID.** Modern models publish an RFCOMM service `0cf12d31-fac3-4553-bd80-d6832e7` plus five hex
  digits. OSCQ matches only the first 108 bits (mask `ffffffff-ffff-ffff-ffff-fffffff00000`) and falls back to
  SPP `00001101-…` when the vendor service is absent —
  [OSCQ soundcore.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore.rs),
  [OSCQ common/device.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/common/device.rs)
- **Suffix rule.** GB derives the suffix as the model code read as hex, OR `0x10000`. That turns `A3947` into
  `b3947` and leaves `D1202` as `d1202`. The captures confirm `…b3958`, `…b3947`, `…d1301` and `…d1402`, and
  show two exceptions: R50i NC = `…b395B` and R60i NC = `…1202c` —
  [GB SoundcoreDeviceSupport.kt](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/soundcore/SoundcoreDeviceSupport.kt);
  [Faker a3959.toml](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/tools/soundcore-device-faker/devices/a3959.toml);
  [Faker d1202c.toml](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/tools/soundcore-device-faker/devices/d1202c.toml)
- **Serial number.** The 16-character serial is the model digits followed by the headset's MAC in reversed byte
  order. Example: model 1234 with MAC 55:66:77:88:99:AA gives `1234AA9988776655` —
  [OSCQ structures/serial_number.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/common/structures/serial_number.rs).
  Real captures follow this: `3957F49D8AC20334` on a Liberty 5, `1203…`/`1204…` on Liberty 5 Pro/Pro Max, and
  `1402…` on Space 2 —
  [Faker a3957.toml](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/tools/soundcore-device-faker/devices/a3957.toml),
  [OSCQ #304](https://github.com/Oppzippy/OpenSCQ30/issues/304), [SCB protocol map](https://github.com/mervin008/soundcorebridge/blob/HEAD/docs/protocol-map.md)
- **Bluetooth names.** Names are literal ("soundcore Liberty 4 NC", "soundcore A30i", "soundcore AeroFit 2",
  "Soundcore Liberty 3 Pro", "Soundcore Q30"). Upper/lower case of "soundcore" varies by model, so GB matches
  each name with its own regex —
  [GB coordinators](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/soundcore)
- **SCB policy.** SCB unlocks writes only when the model code read from the device itself matches a verified
  profile. A name match is used for discovery only, and unknown Soundcore devices get a read-only battery and
  firmware view —
  [SCB DeviceProfile.swift](https://github.com/mervin008/soundcorebridge/blob/HEAD/Sources/soundcorectl/DeviceProfile.swift)

### Inferences
- The suffix rule explains Earshot's current `SoundcoreModel.fromServiceUuid`, which works for `d1301` only.
  For A-codes the UUID ends in `b` + four digits, never `a3947`. The model table needs an explicit list of UUID
  suffixes per model, because exceptions exist.
- Best identification order for Earshot: (1) the SDP vendor UUID prefix means "Soundcore, modern"; (2) a name
  regex gives a first guess; (3) after `01 01`, the serial's first four characters (offset 16 on G2t, 7 on
  G2h/Space 2, 14 on SL, 44 on G1s, from `01 05` on G1t, tag 7 on TLV) confirm the model before any write.
- Liberty Buds 2 = D1206, AeroFit 2 Pro = A3875 and V40i = A3878 are inferred from the serial prefix rule on
  single captures (medium confidence).

### Gaps
- No product names for app codes A3956, A3961, A3937 (P41i per one issue title), A3850 (app has a dongle/VR
  section, VR P10 is a guess), A3871/A3872/A3873, D1105, D1200, D1302, D1303, D1404 or D1406. Search found no
  reliable listing.
- Release years are not sourced here. Scope filtering to 2020+ relies on general knowledge, so verify before
  marketing claims. Liberty 2 Pro A3909 (and possibly A3930) predate 2020 and are excluded from the order below.
- The app's real device-to-model logic (BLE advert parsing, server product catalogue) is encrypted and was not
  recovered.

---

## 2. Transport per model family

### Takeaway
All documented models use one framing over a classic RFCOMM socket: `08 EE 00 00 00` out, `09 FF 00 00 01` in,
a two-byte command, a little-endian u16 total length and a sum-mod-256 checksum. Gen-1 models expose only SPP
`00001101`. Gen-2 models expose the vendor service `0cf12d31-…-d6832e7xxxxx`. A custom BLE GATT service with a
write and a notify characteristic carries the same frames on at least some models. There is no encryption and
no authentication on any documented headset. The newest Space 2 does require a plain handshake before it applies
writes. OTA runs on separate vendor services (BES `66666666-…` and others) that must never be opened.

### Cited Findings
- **Frame layout:** prefix 5 bytes + command 2 bytes + total length u16 LE (prefix, command, length, body and
  checksum all counted) + body + checksum. The checksum is the wrapping 8-bit sum of every preceding byte. A
  reply echoes the command id —
  [OSCQ common/packet.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/common/packet.rs),
  [OSCQ packet/checksum.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/common/packet/checksum.rs).
  This matches Earshot's existing implementation and its example `08 EE 00 00 00 01 01 0A 00 02` —
  [Earshot SoundcorePacket.kt](file:///C:/Users/Hellz/Desktop/earshot/protocol/src/main/kotlin/dev/earshot/protocol/soundcore/SoundcorePacket.kt)
- **Checksum outlier:** OSCQ flags a no-checksum variant used only by the Motion+ speaker (A3116), which also
  pins its own vendor UUID `…b3116`. Liberty 2 Pro+ (A3930) is pinned to SPP —
  [OSCQ a3116.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/a3116.rs),
  [OSCQ a3930.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/a3930.rs)
- **Request/response discipline:** OSCQ sends one request per command id at a time and retries up to three times
  with waits of 500, 1000 and 1500 ms. Frames that no request is waiting for go to a push handler. A parse
  failure clears the receive buffer —
  [OSCQ packet_io_controller.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/common/packet/packet_io_controller.rs).
  Space One (A3035) gets a longer 1500 ms timeout for `0b 01` —
  [OSCQ a3035.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/a3035.rs)
- **Connect sequence (GB):** optional per-model delay, `01 01` (state); on models that need it, after 300 ms,
  `05 01` (GB calls it "extended info"); after another 300 ms, `05 81` ("session init", acked empty). Liberty 4
  NC, AeroFit 2 and Q30 skip both extra steps —
  [GB SoundcoreDeviceSupport.kt](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/soundcore/SoundcoreDeviceSupport.kt),
  [GB SoundcoreProtocolImplV1.java](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/soundcore/protocol/impl/v1/SoundcoreProtocolImplV1.java)
- **Space 2 (D1402) write gate:** until the official app's connect sequence has run, the headphones silently
  discard writes. SCB copied that sequence from an Android HCI capture:
  1. `01 01`
  2. `05 01 [01]`, which returns a capability table of about 600 bytes over 4 frames
  3. `05 81` twice with no body
  4. `05 81 [01]`
  5. `05 81` with no body
  6. `18 85 [01]`

  After it, writes are acked and applied. SCB also reports that `01 01` sometimes returns a bare ack (ask again),
  and that only one control client can hold the link (an iPhone keeps it even with the app closed) —
  [SCB protocol map](https://github.com/mervin008/soundcorebridge/blob/HEAD/docs/protocol-map.md)
- **Capability tables:** Faker captures answer `05 01` with a button-capability table (47 bytes on a Liberty 5,
  92 on a P31i, 42 on a Sleep A20) and `05 81` with one byte. Liberty 5 and P40i also answer `20 03` (current dB
  level) and `03 94` —
  [Faker a3957.toml](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/tools/soundcore-device-faker/devices/a3957.toml),
  [Faker d1202.toml](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/tools/soundcore-device-faker/devices/d1202.toml),
  [Faker a6611.toml](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/tools/soundcore-device-faker/devices/a6611.toml)
- **SPP-only models in captures:** Q20i (A3004), Life Q30 (A3028), Liberty 2 Pro (A3909), Liberty 2 Pro+ (A3930),
  Life Dot 2 NC (A3931) and Liberty Air 2 Pro (A3951). Every other captured model shows the vendor UUID —
  [Faker devices](https://github.com/Oppzippy/OpenSCQ30/tree/200d9e1657dff67d516ed1b6347f312d7a1b01a7/tools/soundcore-device-faker/devices)
- **Channel numbers:** on Space 2 the control service sits on RFCOMM channel 30 and BES firmware OTA on channel 12.
  SCB therefore resolves channels from SDP records and refuses by service UUID, never by number. Its refuse-list:
  BES OTA `66666666-6666-6666-6666-666666666666` and Apple iAP2 `00000000-deca-fade-deca-deafdecacaff` —
  [SCB RFCOMM.swift](https://github.com/mervin008/soundcorebridge/blob/HEAD/Sources/soundcorectl/RFCOMM.swift).
  Earshot already saw `…d1301` plus BES `66666666-…` on the user's Sleep A30 —
  [Earshot roadmap](file:///C:/Users/Hellz/Desktop/earshot/docs/roadmap.md)
- **BLE GATT:** SoundcoreManager (no licence) talks to Soundcore devices over BLE. Its resolver skips GATT
  services `1800`, `1801`, `86868686-8686-8686-8686-868686868686` and `66666666-6666-6666-6666-666666666666`, and
  uses the remaining custom service's write and read/notify characteristics —
  [SoundcoreManager resolver.rs](https://github.com/gmallios/SoundcoreManager/blob/HEAD/soundcore-lib/src/ble/characteristic_resolver/resolver.rs).
  SCB's BLE probe states the control channel is a custom service with a write+notify pair carrying the same
  08EE frames — [SCB GATT.swift](https://github.com/mervin008/soundcorebridge/blob/HEAD/Sources/soundcorectl/GATT.swift)
- **Encryption:** no source describes an encrypted or authenticated headset session. Search for Soundcore-specific
  encryption found nothing reliable (see section 5). The only crypto in the APK is (a) `libecc-encryption.so` in
  Anker's `esiotkit` (ECC key generation and encrypt/decrypt, AES-128-CBC), (b) JieLi OTA auth and (c) cloud API
  signing. Anker's official SDK demo shows a "secure binding" key store for the D3200 recorder —
  [APK split_soundcoreso.apk](file:///C:/Users/Hellz/AppData/Local/Temp/claude/C--Users-Hellz-Desktop-earshot/8ff49ff3-c9c6-4f41-b13e-33c2f63035a9/scratchpad/apks/com.oceanwing.soundcore/split_soundcoreso.apk),
  [AnkerInnovations/SoundcoreSDKDemo (Apache-2.0)](https://github.com/AnkerInnovations/SoundcoreSDKDemo)

### Inferences
- The reported "encrypted session" on newer models most likely refers to one of two things: the Space 2
  `05 xx`/`18 85` handshake gate, which is plain and not encrypted, or the D3200 recorder's secure binding. Neither
  makes headset control encrypted. Earshot's connection should support an optional per-model "init script" of
  frames, sent after `01 01` and before any write.
- The vendor stacks named in the APK manifest (BES/BES2, Qualcomm GAIA, JieLi, Actions, WuQi "Wq", an "AB" chip,
  "Combain") show Soundcore ships several chipsets. The 08EE control protocol is the common layer, and each OTA
  path is chip-specific.
- Earshot's current rule (connect by UUID only, never by channel) is the right one, and the BES UUID is the main
  thing to avoid. Earshot should also refuse `86868686-…` on BLE and iAP2 on RFCOMM.

### Gaps
- Exact GATT service and characteristic UUIDs of the Soundcore control service were not found in the sources read.
  SoundcoreManager discovers them dynamically.
- Which models refuse writes without the `05 81` handshake is only verified for Space 2. GB sends it for some
  models, and the Faker includes it for Liberty 5, P40i, P31i and Sleep A20.
- The meaning of `18 85 [01]` is unknown.

---

## 3. Commands and state

### Takeaway
Every model answers `01 01` with one full-state body whose layout depends on the model. Writes are small,
per-feature commands (`06 81` sound modes, `02 81`/`02 83`/`03 87` EQ, `04 81` buttons, `01 86` auto-off, one-byte
flag commands). Three common headers and one length byte make a shared parser practical. Sound modes come in a few
codecs: a 4-byte v1, 6-byte and 7-byte v2 variants, an 8-byte D1202 form, and the Liberty 4 Pro slider. The same
command id can mean different things on different models, so an Earshot allow-list must be per model.

### Cited Findings

#### Reads, reports and pushes (shared ids)
| Cmd | Direction | Body | Notes |
|---|---|---|---|
| `01 01` | req → full state; also pushed | per model | the probe and refresh command |
| `01 02` | push/read | `[host bud 0 L/1 R, tws connected]` | TWS status |
| `01 03` | push/read | `[L, R(, case)]` levels, or `[level]` on headphones | battery |
| `01 04` | push/read | `[L charging, R charging]` | charging |
| `01 05` | read | fw L (5 ASCII) + fw R (5) + serial (16) = 26 B | used where the state lacks fw/serial (G1t, sleep) |
| `01 0F` | push | 1 B | Chinese voice prompt state |
| `01 10` | read | 1 B | voice prompt state (set `01 90`) |
| `01 11` | push | 1 B | gaming mode update |
| `01 7F` | read/push | `[0/1]` | LDAC (set `01 FF`) |
| `06 01` | read/push | the model's sound-mode block | sent when modes change on the buds |
| `0b 01` | read/push | list of `[len 0x28, connected, MAC 6 B LE, name 32 B zero-padded]` (GB: 2-byte header first) | paired hosts (holds phone names: do not log) |
| `0b 02` | push/read | `[flag, MAC 6 B LE]` | active source |
| `10 09` | push | model-specific | device state (GB hook) |
| `20 03` | read | `[dB]` | current exposure level (Liberty 5, P40i) |
| `09 01` | write `[0a]`, reply `[L, R]` | 1 = good seal, 2 = poor | fit test (plays tones) |

Sources: [OSCQ packet/inbound/*.rs](https://github.com/Oppzippy/OpenSCQ30/tree/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/common/packet/inbound),
[OSCQ structures/packet_type.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/common/structures/packet_type.rs),
[OSCQ structures/dual_connections.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/common/structures/dual_connections.rs),
[GB SoundcoreProtocolImplV1.java](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/soundcore/protocol/impl/v1/SoundcoreProtocolImplV1.java),
[Faker a3957.toml](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/tools/soundcore-device-faker/devices/a3957.toml).

#### State headers (offsets into the `01 01` body)
**G2t (gen-2 TWS):** 0 host bud (0 L, 1 R); 1 TWS connected; 2 battery L; 3 battery R; 4 charging L; 5 charging R;
6-10 firmware L (ASCII "NN.NN"); 11-15 firmware R; 16-31 serial. Many models follow with 32-36 case firmware and
37 case battery (Liberty 4 Pro, P40i, Liberty 5, P31i). R50i NC and A30i put `FF FF` at 4-5 —
[OSCQ state_update parsers](https://github.com/Oppzippy/OpenSCQ30/tree/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore);
[Faker a3957/a3955/a3954/d1202/a3959](https://github.com/Oppzippy/OpenSCQ30/tree/200d9e1657dff67d516ed1b6347f312d7a1b01a7/tools/soundcore-device-faker/devices).
GB's generic decoder accepts firmware at offset 6, or at 4 when the charging bytes are absent, and reads battery
from bytes 2-3 — [GB SoundcoreProtocolImplV1.java](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/soundcore/protocol/impl/v1/SoundcoreProtocolImplV1.java)

**SL (sleep earbuds, A6611/D1301):** 0 host; 1 linked; 2-3 battery L/R; 4-8 fw L; 9-13 fw R; 14-29 serial
(no charging bytes) — [Faker a6611.toml](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/tools/soundcore-device-faker/devices/a6611.toml),
[Earshot D1301State.kt](file:///C:/Users/Hellz/Desktop/earshot/protocol/src/main/kotlin/dev/earshot/protocol/soundcore/D1301State.kt)

**G2h (Space One/Q45/One Pro, Space 2):** 0 battery; 1 charging (`FF` seen); 2-6 firmware; 7-22 serial; 23-24 EQ
preset id u16 LE; 25 onward EQ bands. On Space 2, 7-10 are the ASCII model "1402" and 11-22 the ASCII MAC —
[Faker a3035/a3040/a3062](https://github.com/Oppzippy/OpenSCQ30/tree/200d9e1657dff67d516ed1b6347f312d7a1b01a7/tools/soundcore-device-faker/devices),
[SCB protocol map](https://github.com/mervin008/soundcorebridge/blob/HEAD/docs/protocol-map.md)

**G1s (Life Q30/Q35/Tune), computed from the parser and checked against the 67-byte Q30 capture:**
| Offset | Field |
|---|---|
| 0, 1 | battery level, charging |
| 2-3 | EQ preset id |
| 4-11 | 8 EQ bands |
| 12 | gender |
| 13 | age range |
| 14 | HearID on |
| 15-30 | HearID L/R, 8 bands each |
| 31-34 | HearID time, u32 BE |
| 35-38 | sound modes (v1-4) |
| 39-43 | firmware |
| 44-59 | serial |

Q30 extras follow: 60 unknown, 61 touch, 62 multipoint, 63-64 auto-off, 65 ambient prompt, 66 battery prompt.
Q35 has wear detection instead —
[OSCQ a3028 state_update_packet.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/a3028/packets/state_update_packet.rs),
[OSCQ a3027 state_update_packet.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/a3027/packets/state_update_packet.rs),
[Faker a3028.toml](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/tools/soundcore-device-faker/devices/a3028.toml)

**G1t (Liberty Air 2 Pro):**
| Offset | Field |
|---|---|
| 0-1 | TWS status |
| 2-5 | battery L/R and charging L/R |
| 6-7 | EQ preset |
| 8-23 | 2×8 EQ bands |
| 24 | gender |
| 25 | age |
| 26-64 | custom HearID |
| 65-76 | 6 buttons × 2 bytes |
| 77-80 | sound modes (v1-4) |
| 81 | flag |
| 82 | wear detection |
| 83 | touch tone |

The state has no firmware or serial; read `01 05`. Offsets are computed from the parser, and the 84-byte capture
matches — [OSCQ a3951 state_update_packet.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/a3951/packets/state_update_packet.rs),
[Faker a3951.toml](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/tools/soundcore-device-faker/devices/a3951.toml)

**Battery scales:**
- 0..5 (`dual_battery(5)`): most 2021-23 models.
- 0..9, percent = (n+1)×10: Space One Pro, Liberty 5, P31i, Sleep A20/A30, V20i, AeroClip, C50i, Space 2. The
  R50i NC is configured with max 10.
- 0..100 percent: Liberty 4 Pro, and the TLV family as `[charging, %]`.
- 255 = bud absent or not reporting.

Sources: [OSCQ builder calls per model](https://github.com/Oppzippy/OpenSCQ30/tree/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore),
[GB A30i/AeroFit 2/V20i BatteryProperties(multiplier 10, offset 1)](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/soundcore),
[OSCQ #304](https://github.com/Oppzippy/OpenSCQ30/issues/304).

#### Locating the sound-mode block without a per-model table
On ten gen-2 captures, one length byte sits right after the HearID block. Read it at offset L: the sound-mode
block starts at L + byte[L], and the ambient-mode-cycle byte sits just before it. Measured: Space A40 L=103→117,
Liberty 3 Pro 103→121, Liberty 4 103→121, Liberty 4 NC 108→126, Liberty 4 Pro 115→125, P40i 109→119, Liberty 5
109→119, P31i 109→119, R50i NC 54→64, A30i 54→64. OSCQ parses the same byte as `custom_length` (A3953) or skips it.
Two Faker comment sets (A3953, A3955) place the ambient byte one position early, but the parsers and this rule agree
on 121 and 119 —
my own byte check of the [Faker captures](https://github.com/Oppzippy/OpenSCQ30/tree/200d9e1657dff67d516ed1b6347f312d7a1b01a7/tools/soundcore-device-faker/devices)
against [OSCQ a3953 state_update.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/a3953/packets/state_update.rs)
and [OSCQ a3955 state_update.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/a3955/packets/inbound/state_update.rs).

#### Sound modes (ANC / transparency): set with `06 81`, pushed as `06 01`
Common enums:
- ambient sound mode: 0 noise cancelling, 1 transparency, 2 normal (Liberty 4 Pro adds 3 airplane).
- v1 noise-cancelling sub-mode: 0 transport, 1 outdoor, 2 indoor, 3 custom.
- transparency: 0 full, 1 vocal.
- wind byte: bit0 suppression on, bit1 wind detected (read-only).
- "level byte": manual level in the high nibble, adaptive level in the low nibble.

Sources: [OSCQ structures/sound_modes.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/common/structures/sound_modes.rs),
[OSCQ manual_adaptive_noise_canceling.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/common/structures/manual_adaptive_noise_canceling.rs),
[OSCQ structures/wind_noise.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/common/structures/wind_noise.rs).

| Codec | Models | `06 81` body (byte: meaning, range) | State offset (capture) |
|---|---|---|---|
| v1-4 | Q30, Q35, Life Tune/Pro, Q20i, Liberty Air 2 Pro, Dot 2 NC, A2 NC, Note 3, P3, Liberty 2 Pro+ | 0 ambient; 1 NC sub-mode 0-3; 2 transparency 0/1; 3 custom NC level 0-10 (255 allowed) | Q30 35; Air 2 Pro 77; Note 3/P3 ≈116 |
| v2-A3936 | Space A40 | 0 ambient; 1 manual(1-3)<<4 \| adaptive(0-2 low/med/high noise); 2 transparency; 3 NC mode 0 manual/1 adaptive; 4 wind; 5 adaptive sensitivity | 117 |
| v2-A3952 | Liberty 3 Pro | as A3936 (manual 1-3 weak/moderate/strong; adaptive 0-2); byte 5 unknown, written back | 121 |
| v2-A3953 | Liberty 4 | 0 ambient; 1 manual(1-6)<<4 \| auto; 2 transparency 0-2; 3 automation mode; 4 wind bit0; 5 sensitivity. The app exposes only the 3 ambient modes on this model | 121 |
| v2-A3035 / A3062 | Space One / One Pro | 0 ambient; 1 custom(1-5)<<4 \| adaptive(1-5); 2 repeat of ambient (One) or fixed 1 (One Pro); 3 NC mode 0 custom/1 adaptive; 4 wind 0/1; 5 transparency level 1-5 | 69 |
| v2-A3040 | Space Q45 | 0 ambient; 1 manual(1-5)<<4 \| adaptive(1-5); 2 transparency mode 0 talk/1 manual; 3 NC mode; 4 wind; 5 manual transparency 0-5 | 49 |
| v2-A3968 | Sport X20 | 0 ambient; 1 manual(1-3)<<4 \| adaptive(0-2); 2 transparency; 3 NC mode; 4 wind; 5 `FF` | n/a |
| v2-A3947 (7 B) | Liberty 4 NC | 0 ambient; 1 manual(1-5)<<4 \| adaptive(1-5); 2 transparency; 3 NC mode 0 manual/1 adaptive/2 transportation; 4 wind; 5 environment detection; 6 transport 0 plane/1 train/2 bus/3 car | 126 |
| v2-A3957 (7 B) | Liberty 5 | 0 ambient; 1 manual(1-5)<<4 \| adaptive(0-2); 2 transparency; 3 NC mode 0/1/2 transportation; 4 wind; 5 sensitivity; 6 transport 0 plane/3 car | 119 |
| v2-A3955 (7 B) | P40i | 0 ambient; 1 manual(1-5)<<4 \| adaptive(1-3); 2 transparency; 3 NC mode 0/1/2 multi-scene; 4 wind; 5 sensitivity; 6 scene 0 transport/1 outdoor/2 indoor | 119 |
| v2-A3959 (7 B) | P30i/R50i NC, A30i | 0 ambient; 1 manual<<4 \| adaptive; 2 repeat of ambient; 3 NC mode 0/1/2 multi-scene; 4 wind; 5 sensitivity; 6 scene | 64 |
| v2-D1202 (8 B) | P31i, R60i NC | 0 ambient; 1 manual(1-5)<<4 \| adaptive(0-2); 2 transparency; 3 NC mode 0/1/2; 4 wind; 5 0; 6 scene 0-2; 7 real-time adaptive ANC 0/1 | 119 |
| slider (4 B) | Liberty 4 Pro | 0 ambient 0-3; 1 slider 1-11 (6 = normal); 2 airplane 0 manual update/1 automatic; 3 wind | 125 |
| Space 2 | D1402 | `[mode 0 NC/1 transparency/2 normal, level<<4 \| 0xF (1F..5F), 02, 00, 00, 01]`. Byte 2 must be `02` on writes, while reads show `FF` | 71-76 |
| on/off | Sleep A30 | `06 87 [0/1]` | 140 |

Sources: [OSCQ a3936/structures/sound_modes.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/a3936/structures/sound_modes.rs),
[a3952/structures.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/a3952/structures.rs),
[a3953/structures.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/a3953/structures.rs),
[a3035/structures.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/a3035/structures.rs),
[a3040/structures.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/a3040/structures.rs),
[a3062/structures.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/a3062/structures.rs),
[a3947/structures.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/a3947/structures.rs),
[a3954/structures.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/a3954/structures.rs),
[a3955/structures/sound_modes.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/a3955/structures/sound_modes.rs),
[a3957/structures/sound_modes.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/a3957/structures/sound_modes.rs),
[a3959/structures/sound_modes.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/a3959/structures/sound_modes.rs),
[a3968/structures/sound_modes.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/a3968/structures/sound_modes.rs),
[d1202/structures.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/d1202/structures.rs),
[GB A30i protocol](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/soundcore/devices/SoundcoreA30iProtocol.java),
[SCB protocol map](https://github.com/mervin008/soundcorebridge/blob/HEAD/docs/protocol-map.md),
[Earshot SoundcorePacket.kt](file:///C:/Users/Hellz/Desktop/earshot/protocol/src/main/kotlin/dev/earshot/protocol/soundcore/SoundcorePacket.kt).

Mode-writing quirks:
- **v1 sub-mode bug.** If the NC sub-mode changes while the ambient mode is normal or transparency, v1 devices
  jump into noise cancelling without updating the ambient id. OSCQ therefore writes NC + new sub-mode first, then
  the target ambient mode — [OSCQ sound_modes/state_modifier.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/common/modules/sound_modes/state_modifier.rs)
- **v2 transition planning.** Some v2 devices misbehave on transitions the official app never makes. OSCQ plans a
  multi-step path. Examples: the transparency type only after ambient = transparency, the NC mode only after
  ambient = NC, the adaptive or manual level only in that NC mode, wind only in NC or transparency. Every step is a
  full `06 81` write — [OSCQ sound_modes_v2.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/common/modules/sound_modes_v2.rs),
  [OSCQ a3955 sound_modes migration requirements](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/a3955/structures/sound_modes.rs)
- **Liberty 4 Pro slider conflict.** OSCQ's setting handler treats slider 1-5 as NC strength (6 − slider) and
  7-11 as transparency (slider − 6), and sets 5/6/7 for NC/normal/transparency. The Faker comment states the
  opposite polarity (below 6 = transparency). Unresolved —
  [OSCQ a3954 sound_modes setting_handler.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/a3954/modules/sound_modes/setting_handler.rs);
  contradicted by [Faker a3954.toml](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/tools/soundcore-device-faker/devices/a3954.toml)
- **Q30 in GB.** GB writes Q30 modes as 3 bytes `[ambient, sub-mode, 01]` and reports it "mostly supported" —
  [GB SoundcoreQ30Protocol.java](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/soundcore/devices/SoundcoreQ30Protocol.java)
- **Cached ANC level in the app.** The official app shows its own cached ANC level rather than the device's
  `06 01` value, so a level set by another client looks wrong in the app —
  [SCB protocol map](https://github.com/mervin008/soundcorebridge/blob/HEAD/docs/protocol-map.md)
- **Mode cycle.** The button's ambient-mode cycle is set with `06 82 [mask]` (bit0 NC, bit1 transparency,
  bit2 normal). The TWS variant (Sport X20) uses the low nibble for two buds and the high nibble for a single bud —
  [OSCQ set_ambient_sound_mode_cycle.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/common/packet/outbound/set_ambient_sound_mode_cycle.rs),
  [OSCQ structures/ambient_sound_mode_cycle.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/common/structures/ambient_sound_mode_cycle.rs)

#### Equalizer and HearID
- **Band encoding:** byte = 120 + dB×10, so 0.1 dB steps. The common range is −12.0..+13.4 dB, and the app's
  presets stay within ±6 dB. Visible bands are 100, 200, 400, 800, 1600, 3200, 6400 and 12800 Hz; 10-band models
  carry two hidden bands. Preset id u16 LE: 0 Soundcore Signature, 1 Acoustic, 2 Bass Booster, 3 Bass Reducer,
  4 Classical, 5 Podcast, 6 Dance, 7 Deep, 8 Electronic, 9 Flat, 10 Hip-Hop, 11 Jazz, 12 Latin, 13 Lounge,
  14 Piano, 15 Pop, 16 R&B, 17 Rock, 18 Small Speakers, 19 Spoken Word, 20 Treble Booster, 21 Treble Reducer;
  `0xFEFE` = custom. A channel whose first byte is `0xFF` is absent (the right channel on mono-EQ earbuds) —
  [OSCQ structures/volume_adjustments.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/common/structures/volume_adjustments.rs),
  [OSCQ modules/equalizer.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/common/modules/equalizer.rs),
  [SCB protocol map](https://github.com/mervin008/soundcorebridge/blob/HEAD/docs/protocol-map.md)
- **Three write forms:**
  - `02 81 [preset u16][bands per channel]` (plain): Q30/Q35/Life 2 Neo, C50i. GB's Q30 custom write is
    `02 81 [FE FE b1..b8]`.
  - `02 83`: the same body followed by DRC-processed bands. Used by Q20i, Q11i, Dot 2 NC, A2 NC, A20i, P20i/R50i,
    P30i/R50i NC, C30i.
  - `03 87` (`03 86` when HearID is not set up, age byte = FF): preset u16; [genre u16]; bands; gender; age; 0;
    HearID bands; HearID time u32 BE; HearID type (0 initial, 1 custom, 2 favourite genre); custom HearID bands;
    DRC-processed bands. Per-model variants add an "initialized" pair (`00 00` vs `FF FF`), zero the last band
    byte, or append a trailing 0 (Liberty 4, P40i, Liberty 4 NC, Space Q45). Used by Liberty Air 2 Pro, Space A40,
    Liberty 3 Pro, Liberty 4/4 NC/4 Pro, P40i, Liberty 5, P31i, Space One/One Pro/Q45, Sport X20.

  Sources: [OSCQ set_equalizer.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/common/packet/outbound/set_equalizer.rs),
  [OSCQ set_equalizer_with_drc.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/common/packet/outbound/set_equalizer_with_drc.rs),
  [OSCQ set_equalizer_and_custom_hear_id.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/common/packet/outbound/set_equalizer_and_custom_hear_id.rs),
  [OSCQ a3953 set_equalizer_configuration.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/a3953/packets/set_equalizer_configuration.rs),
  [OSCQ a3040 set_equalizer.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/a3040/packets/set_equalizer.rs),
  [GB SoundcoreQ30Protocol.java](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/soundcore/devices/SoundcoreQ30Protocol.java)
- **DRC and capture advice:** OSCQ computes the DRC bands with an 8×8 coefficient matrix over the dB values (GPL
  code; Earshot needs its own derivation). SCB documents Space 2's 53-byte `03 87`: bytes 13-41 are an
  app-managed block that should be captured, not assumed —
  [OSCQ volume_adjustments.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/common/structures/volume_adjustments.rs),
  [SCB protocol map](https://github.com/mervin008/soundcorebridge/blob/HEAD/docs/protocol-map.md)
- **Spatial audio via EQ:** Liberty 4 Pro and P31i: the app turns spatial audio off by sending an EQ packet, not
  the spatial command — [OSCQ a3954 equalizer state_modifier.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/a3954/modules/equalizer/state_modifier.rs)
- **New float EQ:** Liberty 5 Pro Max tag 12 holds 8 bands as f32 LE in dB. P42i, AeroFit 2 Pro and Liberty Buds 2
  captures contain repeated float triples that look like parametric bands (gain, frequency, Q; for example 100.0 Hz
  with Q 0.7). That reading is mine and unverified —
  [OSCQ #304](https://github.com/Oppzippy/OpenSCQ30/issues/304), [#336](https://github.com/Oppzippy/OpenSCQ30/issues/336),
  [#285](https://github.com/Oppzippy/OpenSCQ30/issues/285), [#411](https://github.com/Oppzippy/OpenSCQ30/issues/411)

#### Touch / buttons
- `04 81 [side 0 L/1 R, button id, action]`. Button ids: 0 double, 1 long, 2 single, 5 triple, 6 swipe up,
  7 swipe down. Actions: 0 volume+, 1 volume−, 2 previous, 3 next, 4 ambient-sound mode, 5 voice assistant,
  6 play/pause, 9 gaming mode, 0xF disabled.
- Stored "TWS low bits" form: low nibble = action while both buds are connected, high nibble = action on a single
  bud. The same packing applies to the enabled flag on models that have one.
- `04 83 [side, id, enabled]` sets enabled; `04 84` sets all; `04 82` resets to defaults. C30i uses `04 87` instead
  of `04 81`.
- Liberty 4 hides long press in the official app, and writing it has no effect on real hardware.

Sources: [OSCQ set_button_configuration.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/common/packet/outbound/set_button_configuration.rs),
[OSCQ structures/button_configuration.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/common/structures/button_configuration.rs),
[OSCQ modules/button_configuration.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/common/modules/button_configuration.rs),
[OSCQ a3953.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/a3953.rs),
[OSCQ a3330.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/a3330.rs),
[GB SoundcoreProtocolImplV1.java](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/soundcore/protocol/impl/v1/SoundcoreProtocolImplV1.java).

#### Other settings (writes take one byte, `[0/1]`, unless noted)
| Cmd | Meaning | Models (examples) |
|---|---|---|
| `01 81` | wear detection, or auto play/pause on Liberty 4 NC | Q35, Air 2 Pro, Liberty 4, Liberty 5 |
| `01 83` | touch tone | most earbuds |
| `01 87` | gaming mode (push `01 11`) | Note 3, A40, P3, R50i NC |
| `10 85` | gaming mode | Liberty 4 NC, Liberty 5 |
| `01 8C` | wearing tone | Liberty 3 Pro, 4, 4 NC, 5 |
| `01 90` | voice prompt (read `01 10`) | V20i |
| `01 AE` | voice prompt | Space One, Q45, One Pro |
| `01 AF` | low-battery prompt | Space One, Q45 |
| `10 82` | low-battery prompt | Liberty 4/4 NC/4 Pro, P40i, Liberty 5, P31i |
| `01 84` | side tone | Space One Pro |
| `02 86` | 3D surround, or Dolby Audio on Space One Pro | Liberty 4 NC, R50i NC, Sport X20 / One Pro |
| `01 FF` | LDAC (read `01 7F`) | Q45, Space One, A40, Liberty 3 Pro/4/4 NC/5, P31i |
| `0b 84` | multipoint on/off | most 2022+ models |
| `01 86` | auto power-off `[enabled, index]` | index = 30/60/90/120 min (Q30, Space One/Q45/One Pro, A40, Liberty 3 Pro) or 10/20/30/60 min (Liberty 4 family, P40i, Liberty 5, R50i NC, Sport X20, P31i, A30i); GB writes disabled as `[00, 03]` |
| `20 82` | volume limit `[enabled, dB 75..100 step 5]` | Space One/Q45/One Pro, Liberty 4 NC/4 Pro/5, P40i, P31i |
| `20 81` | dB-reading refresh `[0 real-time/1 10 s/2 1 min]` | same |
| `04 86` | touch lock | Liberty 4 NC |
| `10 86` | sound-leak compensation | Liberty 4 NC/4 Pro/5 |
| `10 94` | all buttons disabled | Sleep A30, C30i, AeroClip, C50i |
| `10 81` | spatial audio | Liberty 4 `[on, 1 fixed/2 head tracking, 0 music/1 movie]`; Liberty 4 Pro `[on, music mode, mode]`; P31i `[on, mode]` |
| `10 83` | ambient-sound prompt | Liberty 4 |
| `04 85` | press sensitivity 0-4 | Liberty 4 |
| `10 9D` | easy chat | Liberty 4 Pro |
| `07 87` | case features | Liberty 4 Pro |
| `07 8A` | case language | Liberty 4 Pro |
| `12 81` | immersive mode 0/1 gaming/2 movie | P40i |
| `03 90` | ANC personalised to ear canal | P40i |
| `10 89` | find device `[left, right, 0]` | GB: A30i, AeroFit 2, Sport X20 |
| `10 8C`, `0E 82..87` | volume balance; lights | V20i |

Sources: [OSCQ packet/outbound.rs constants](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/common/packet/outbound.rs),
[OSCQ modules/flag.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/common/modules/flag.rs),
[OSCQ a3035/a3040/a3062 flag.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/a3062/modules/flag.rs),
[OSCQ set_auto_power_off.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/common/packet/outbound/set_auto_power_off.rs),
[OSCQ set_limit_high_volume.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/common/packet/outbound/set_limit_high_volume.rs),
[OSCQ a3953/a3954/a3955/d1202 outbound + structures](https://github.com/Oppzippy/OpenSCQ30/tree/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore),
[OSCQ a3876 outbound.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/a3876/packets/outbound.rs),
[GB SoundcoreProtocolImplV1.java](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/soundcore/protocol/impl/v1/SoundcoreProtocolImplV1.java).

**Id reuse:** on the Motion+ speaker (A3116), `01 81` is volume, `01 83` auto power-off and `01 85` power off.
On earbuds, `01 81` and `01 83` are wear detection and touch tone —
[OSCQ a3116/packets/outbound.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/a3116/packets/outbound.rs).

#### Per-model settings offsets from annotated captures (selection; firmware-specific)
| Model | ANC block | EQ preset | Case batt. | LDAC | Multipoint | Auto-off (en, dur) | Other |
|---|---|---|---|---|---|---|---|
| Liberty 4 NC A3947 (159 B) | 126-132 | 37 | 139 | n/a | 147 | 154, 155 | touch tone 144, gaming 143, leak comp 141, vol limit 149-151, touch lock 156, low batt 157 |
| Liberty 5 A3957 (154 B) | 119-125 | 38 | 37 | 129 | 130 | 131, 132 | wearing tone 127, vol limit 133-135, wear det 144, touch tone 145, gaming 146 |
| P40i A3955 (152 B) | 119-125 | 38 | 37 | n/a | 134 | 138, 139 | touch tone 133, vol limit 135-137, low batt 141 |
| R50i NC A3959 (91 B) | 64-70 | 32 | n/a | n/a | 73 | 75, 76 | touch tone 72, low batt 77, gaming 78 |
| A30i A3958 (91 B) | 64-70 | 32 | n/a | n/a | n/a | 75, 76 | touch tone 72, surround 74, low batt 77 (GB) |
| Space A40 A3936 (143 B) | 117-122 | 32 | 124 | 126 | 127 | 128, 129 | touch tone 123, gaming 130 |
| Liberty 3 Pro A3952 (153 B) | 121-126 | 32 | 130 | 133 | n/a | 138, 139 | touch tone 127, wear det 128, wearing tone 136 |
| Liberty 4 Pro A3954 (165 B) | 125-128 | 44 | 37 | 141 | 142 | 143, 144 | vol limit 145-147, spatial 148-150, case lang 157, wear det 159 |
| P31i D1202 (152 B) | 119-126 | 38 | 37 | 130 | 131 | 132, 133 | touch tone 128, low batt 129, vol limit 134-136, spatial 137-138 |
| Space One A3035 (95 B) | 69-74 | 23 | n/a | 81 | 82 | 85, 86 | auto play 76, vol limit 87-89, prompts 90-91 |
| Space Q45 A3040 (142 B) | 49-54 | 23 | n/a | 61 | 62 | 65, 66 | vol limit 67-69, prompts 70-71 |
| Space One Pro A3062 (95 B) | 69-74 | 23 | n/a | 78 | 79 | 80, 81 | low batt 76, Dolby 77, vol limit 82-84, side tone 85 |
| Space 2 D1402 (103 B) | 71-76 | 23 | n/a | `01 7F` | n/a | n/a | host count 91, ANC level count 70 |

Sources: [Faker a3947](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/tools/soundcore-device-faker/devices/a3947.toml),
[a3957](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/tools/soundcore-device-faker/devices/a3957.toml),
[a3955](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/tools/soundcore-device-faker/devices/a3955.toml),
[a3959](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/tools/soundcore-device-faker/devices/a3959.toml),
[a3936](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/tools/soundcore-device-faker/devices/a3936.toml),
[a3952](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/tools/soundcore-device-faker/devices/a3952.toml),
[a3954](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/tools/soundcore-device-faker/devices/a3954.toml),
[d1202](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/tools/soundcore-device-faker/devices/d1202.toml),
[a3035](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/tools/soundcore-device-faker/devices/a3035.toml),
[a3040](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/tools/soundcore-device-faker/devices/a3040.toml),
[a3062](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/tools/soundcore-device-faker/devices/a3062.toml),
[GB A30i](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/soundcore/devices/SoundcoreA30iProtocol.java),
[SCB protocol map](https://github.com/mervin008/soundcorebridge/blob/HEAD/docs/protocol-map.md).
Layouts change with firmware: a Liberty 5 on firmware 03.11 fails OSCQ's parser —
[OSCQ #377](https://github.com/Oppzippy/OpenSCQ30/issues/377).

#### TLV generation (Liberty 5 Pro D1203, Liberty 5 Pro Max D1204, likely Liberty Buds 2 D1206)
The `01 01` body is a series of `[tag, length, value]` entries:

| Tag | Length | Content |
|---|---|---|
| 1 | 1 | host |
| 2 | 1 | TWS connected |
| 3, 4 | 2 each | battery L/R `[charging, %]` |
| 5, 6 | 5 each | firmware L/R |
| 7 | 17 | serial + NUL |
| 8 | 2 | case `[0x80 = charging, %]` |
| 9 | 5 | case firmware |
| 11 | 2 | EQ preset u16 LE (`FEFE` = custom) |
| 12 | 32 | 8×f32 dB |
| 13-24 | 2 each | buttons `[action_tws, action_single]` |
| 27 | 3 | smart features (`0x40` = EasyChat) |
| 36 | 3 | sound mode, byte 0: 0 manual ANC, 1 transparency, 2 normal, 3 adaptive ANC |
| 37 | 2 | manual ANC level `[1..5, 1]` |
| 39 | 3 | volume limit `[on, dB, ?]` |
| 44 | 3 | Dolby `[on, 0 off/1 fixed/2 head tracking, 1]` |

The write commands for this family are not documented —
[OSCQ #304 tag map by brigante91](https://github.com/Oppzippy/OpenSCQ30/issues/304), [OSCQ #342](https://github.com/Oppzippy/OpenSCQ30/issues/342),
[OSCQ #411](https://github.com/Oppzippy/OpenSCQ30/issues/411). A V40i owner reports that the command opcodes
differ entirely from the older ones on that model — [OSCQ #357](https://github.com/Oppzippy/OpenSCQ30/issues/357).

### Inferences
- One Earshot parser can cover the G2t family with three per-model numbers: the header variant (charging bytes
  present?), the position of the length byte, and the sound-mode codec. Settings offsets beyond the sound-mode
  block stay per model and per firmware. Length-check them and fall back to "unknown".
- Use the `06 01` push and the `01 03`/`01 04` pushes for live updates, and re-read `01 01` after each write
  (Earshot's D1301 driver already does this). Do not trust the write ack alone.
- For EQ, start with preset selection plus custom bands only on models that accept `02 81`. Defer `02 83`/`03 87`
  until Earshot has its own DRC derivation, or until captures show the app's bytes for a given model.

### Gaps
- Exact `03 87` layouts are verified only per model in GPL code. A clean-room capture per model is needed.
- The meanings of `03 94`, `10 97`, `05 01` content and many "unknown" bytes are open.
- Whether `01 FF` (LDAC) reconnects or reboots the headset is not documented.
- TLV-family write ids and the newer V40i opcodes are unknown.

---

## 4. What must be blocked

### Takeaway
The dangerous paths are mostly *outside* the 08EE control protocol. Firmware update runs on separate chip-vendor
services (BES `66666666-…`, Qualcomm GAIA, JieLi, Actions, WuQi, others), and Earshot must never open them. Inside
the control protocol, the known disruptive writes are power off, pairing mode, forget/connect/disconnect host,
reset buttons, delete alarm, and the loud find-device and fit-test tones. No public source names a factory-reset,
clear-all-pairings or OTA opcode in this protocol, so the only safe design is a per-model allow-list.

### Cited Findings
- **Never open these services:**
  - BES OTA `66666666-6666-6666-6666-666666666666` (RFCOMM, and as a GATT service).
  - `86868686-8686-8686-8686-868686868686` (GATT; skipped by SoundcoreManager, purpose unknown).
  - Apple iAP2 `00000000-deca-fade-deca-deafdecacaff` (not this protocol).

  Do not choose by channel number: on Space 2, channel 12 is OTA —
  [SCB RFCOMM.swift](https://github.com/mervin008/soundcorebridge/blob/HEAD/Sources/soundcorectl/RFCOMM.swift),
  [SoundcoreManager resolver.rs](https://github.com/gmallios/SoundcoreManager/blob/HEAD/soundcore-lib/src/ble/characteristic_resolver/resolver.rs),
  [SCB protocol map](https://github.com/mervin008/soundcorebridge/blob/HEAD/docs/protocol-map.md)
- **OTA paths in the official app:** BES, BES2, JieLi, Actions, "AB", "Combain", WuQi (classic and BLE), Qualcomm
  GAIA, plus per-model OTA screens (A3145, A3165, A3301, A3395, A3398, A3600, A3850, A3909, A3933) and a JieLi OTA
  auth native lib — [APK AndroidManifest.xml and split_soundcoreso.apk](file:///C:/Users/Hellz/AppData/Local/Temp/claude/C--Users-Hellz-Desktop-earshot/8ff49ff3-c9c6-4f41-b13e-33c2f63035a9/scratchpad/apks/com.oceanwing.soundcore/)
- **Hidden debug/test screens** exist in the app: `A3062DebugActivity`, `activity.devtools.*`, A3200/A3600/A3947
  `BetaTestActivity`, `A3874TestActivity`, and resource names `a3956box_cmd_test_activity` and
  `d3200_cmd_test_activity`. Their opcodes are inside the encrypted code —
  [APK AndroidManifest.xml / resources.arsc](file:///C:/Users/Hellz/AppData/Local/Temp/claude/C--Users-Hellz-Desktop-earshot/8ff49ff3-c9c6-4f41-b13e-33c2f63035a9/scratchpad/apks/com.oceanwing.soundcore/base.apk)
- **Disruptive control writes with known ids:**

  | Cmd | Effect | Source |
  |---|---|---|
  | `01 89` | power off (GB `CMD_POWER_OFF`) | GB |
  | `01 85` | power off on the Motion+ speaker | OSCQ a3116 |
  | `0b 85 [00 90]` | pairing mode | GB |
  | `0b 83 [MAC LE]` | forget host | GB, OSCQ |
  | `0b 81` / `0b 82 [MAC LE]` | disconnect / connect host | GB, OSCQ |
  | `04 82` | reset all button mappings | OSCQ, GB |
  | `14 82 [id]` | delete alarm (Sleep A30) | OSCQ d1301 |
  | `10 89 [L, R, 0]` | find device (loud tone) | GB |
  | `09 01 [0a]` | fit test (tones) | GB |

  Sources: [GB SoundcoreProtocolImplV1.java](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/soundcore/protocol/impl/v1/SoundcoreProtocolImplV1.java),
  [OSCQ dual_connections outbound](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/common/packet/outbound/dual_connections.rs),
  [OSCQ a3116 outbound](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/a3116/packets/outbound.rs),
  [OSCQ d1301 outbound](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/d1301/packets/outbound.rs)
- **Earshot's guard today:** `SoundcoreWriteGuard` refuses unpair, pairing mode, host connections, power off
  (`01 85`/`01 89`), find device, reset buttons, delete alarm and any unknown write —
  [Earshot SoundcorePacket.kt](file:///C:/Users/Hellz/Desktop/earshot/protocol/src/main/kotlin/dev/earshot/protocol/soundcore/SoundcorePacket.kt)
- **Why it matters:** SCB keeps unverified models read-only, because guessed offsets written to headphones can
  leave them in odd states —
  [SCB README](https://github.com/mervin008/soundcorebridge/blob/HEAD/README.md)

### Inferences
- Earshot's `SoundcoreWriteGuard` is global today. Because ids are reused across models (`01 81`/`01 83`/`01 85`),
  the allow-list should be the model table's own list: command id plus expected body length. The global list stays
  as an extra deny-list for `01 85`, `01 89`, `0b 81`/`0b 82`/`0b 83`/`0b 85`, `04 82`, `14 82` and every
  unknown write.
- Allow find device and fit test only behind an explicit user action with a warning (loud), never from
  automations.
- Send handshake frames (`05 81`, `18 85`) only as part of a model's verified init script.
- Never send anything on the OTA, GAIA or iAP2 services, and never probe RFCOMM channels by number.

### Gaps
- Factory-reset, clear-all-pairings, ship-mode and OTA-start opcodes inside the 08EE protocol are unknown (no
  public source). The encrypted app likely contains them.
- The purpose of the `86868686-…` GATT service is unknown.

---

## 5. Community and security knowledge

### Takeaway
The working knowledge comes almost entirely from OpenSCQ30 (GPL-3.0, about 40 models, annotated captures and a
device faker), Gadgetbridge (AGPL-3.0, 10 models, multipoint, find device, the connect sequence) and the newer
MIT-licensed soundcorebridge (Space 2, with verified writes and the handshake). None of the security research
found names Soundcore. The Airoha RACE disclosures list other brands, and no source documents an encrypted
Soundcore headset session.

### Cited Findings
- **OpenSCQ30 (GPL-3.0):** about 40 models across Windows, Linux and Android, distributed via Flathub and
  IzzyOnDroid, with a device-faker tool and a request template that collects `01 01` captures —
  [OpenSCQ30 README](https://github.com/Oppzippy/OpenSCQ30);
  [Faker devices](https://github.com/Oppzippy/OpenSCQ30/tree/200d9e1657dff67d516ed1b6347f312d7a1b01a7/tools/soundcore-device-faker/devices).
  Open requests include Liberty 5 Pro and Pro Max, Space 2, AeroFit 2 Pro, Liberty Buds 2, P42i, Q21i NC, V40i,
  Sleep A20 and VR P10 — [OSCQ issues](https://github.com/Oppzippy/OpenSCQ30/issues)
- **Gadgetbridge (AGPL-3.0)** support levels:
  - "Highly supported": A30i, Liberty 3 Pro, Liberty 4 NC, Sport X20.
  - "Mostly": Q30, Life Tune, Life Tune XR.
  - "Partially": AeroFit 2.
  - Known gaps: no EQ or HearID on Liberty 3 Pro/4 NC, and no active battery polling.

  [Gadgetbridge Anker page](https://gadgetbridge.org/gadgets/headphones/anker/)
- **soundcorebridge (MIT, macOS):** full control of Space 2, read-only battery and firmware for other models.
  Its protocol map was confirmed live on hardware —
  [SCB README](https://github.com/mervin008/soundcorebridge/blob/HEAD/README.md), [SCB protocol map](https://github.com/mervin008/soundcorebridge/blob/HEAD/docs/protocol-map.md)
- **SoundcoreManager (Rust/Tauri, no licence, last push 2025-02):** models A3027, A3028, A3029, A3040, A3930,
  A3935, A3947, A3951; uses BLE —
  [SoundcoreManager repo](https://github.com/gmallios/SoundcoreManager)
- **Other projects** (licence noted, contents not reviewed): `CallMeTak/SoundCoreReversing` (Liberty Air 2 Pro,
  none), `nnonickreal/openqore` (BES-based headphone firmware tooling, GPL-3.0), `d7omdev/soundcore-control`
  (GPL-3.0), `CriticalRange/CoreSound` (GPL-3.0), `JordanViknar/Noiseclapper-GNOME` (MPL-2.0),
  `maniacx/BudsLink` (has a Soundcore config) — found via GitHub search
  ([gh search: soundcore](https://github.com/search?q=soundcore&type=repositories))
- **Anker SoundcoreSDKDemo (Apache-2.0):** an official repo for the D3200 "soundcore Work" recorder. It needs a
  server-signed licence and does not cover headphones —
  [AnkerInnovations/SoundcoreSDKDemo README](https://github.com/AnkerInnovations/SoundcoreSDKDemo)
- **Airoha disclosures (CVE-2025-20700/20701/20702, ERNW):** the confirmed list covers Sony, Marshall, JBL,
  Jabra, Bose QC Earbuds and others; Anker/Soundcore are not named. The vulnerable vendor protocol is reachable
  over BLE GATT and RFCOMM without pairing —
  [ERNW / insinuator.net](https://insinuator.net/2025/06/airoha-bluetooth-security-vulnerabilities/),
  [CVE-2025-20700](https://cvefeed.io/vuln/detail/CVE-2025-20700)
- **No Soundcore encryption source:** searches for Soundcore-specific encryption or an authenticated handshake
  found nothing reliable — web search results 2026-10-21 (no qualifying source).

### Inferences
- The MIT soundcorebridge is the only permissive source with verified writes. It is the cleanest base for a
  clean-room Space 2 implementation. Everything else must be re-derived from captures, using GPL/AGPL material as
  facts only.
- A model's chipset, and so its OTA service, may come from a vendor whose debug protocol is risky (Airoha-style
  RACE). Earshot's "never open anything but the control UUID" stance covers this.

### Gaps
- Reddit, XDA and blog sources were not searched in depth (time budget).
- Whether any Soundcore model runs an Airoha SoC with RACE exposed is unconfirmed.

---

## 6. Effort, quirks and a recommended order

### Takeaway
Shared code covers most of the effort: the framing (done), one G2t header parser, the length-byte rule, a handful
of sound-mode codecs and per-model flag maps. After that, each additional fixed-layout model costs about S
(capture, model-table entry, virtual device, tests). Outliers to plan separately: Liberty 4 Pro (case block and
slider), Space 2 (handshake), the TLV generation (Liberty 5 Pro/Pro Max/Buds 2) and the float/parametric-EQ models.

### Cited Findings
- **Shared parsers in OSCQ:** Life Tune with Life Q30, Life Tune Pro with Life Q35, Life A2 NC with Life Dot 2 NC,
  Life P3 with Life Note 3, R60i NC with P31i —
  [OSCQ device_model.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/device_model.rs).
  GB treats Life Tune and Tune XR as rebranded Q30s —
  [Gadgetbridge Anker page](https://gadgetbridge.org/gadgets/headphones/anker/)
- **Same layout across models:** A30i (A3958) and R50i NC (A3959) share the 64-70 ANC block layout and 91-byte
  states — [Faker a3958/a3959](https://github.com/Oppzippy/OpenSCQ30/tree/200d9e1657dff67d516ed1b6347f312d7a1b01a7/tools/soundcore-device-faker/devices)
- **Outliers:**
  - Liberty 4 Pro: percent battery, case firmware/serial/battery inserted after the header, 12 buttons including
    swipes, slider modes.
  - Motion+ speaker: no checksum.
  - Space 2: handshake gate, byte 2 = `02` on writes.
  - TLV generation.
  - Unusual UUID suffixes `395B` and `1202c`.

  Sources: [OSCQ a3954.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/a3954.rs),
  [OSCQ a3116.rs](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/lib/src/devices/soundcore/a3116.rs),
  [SCB protocol map](https://github.com/mervin008/soundcorebridge/blob/HEAD/docs/protocol-map.md),
  [OSCQ #304](https://github.com/Oppzippy/OpenSCQ30/issues/304)
- **Firmware drift and parse failures:** layouts drift with firmware (Liberty 5 03.11 — [OSCQ #377](https://github.com/Oppzippy/OpenSCQ30/issues/377);
  Life P2 Mini fails at the dual firmware field — [OSCQ #386](https://github.com/Oppzippy/OpenSCQ30/issues/386))

### Inferences

Recommended order for Earshot. Sizes use the roadmap's S/M/L scale; "verify" means a capture from a real owner via
the Lab device report (F3) before writes are enabled.

1. **Generic read-only Soundcore view** (S). Covers every vendor-UUID model:
   - parse the G2t/SL/G2h header by length and sanity checks (firmware matches `NN.NN`);
   - take the model from the serial's first four characters;
   - show battery L/R/case and firmware; no writes.

   This immediately lifts Soundcore above "any headset" and gives owners a capture path.
2. **G2t core + v2 7/8-byte codecs** (M), then add models (S each):
   - Liberty 4 NC (A3947), the most documented model (OSCQ + GB + capture);
   - Liberty 5 (A3957);
   - P40i (A3955);
   - P30i/R50i NC (A3959) and A30i (A3958), which share a layout;
   - P31i/R60i NC (D1202).

   Features: modes, ANC level, wind, multipoint flag, touch tone, auto-off, low-battery prompt, button actions.
   EQ comes later.
3. **Space headphones family** (M for the G2h header and three 6-byte codecs): Space One (A3035), Space One Pro
   (A3062), Space Q45 (A3040), then Space 2 (D1402) with its handshake, from the MIT map. These are the Bose
   competitors, so they are high value for Earshot users.
4. **Older 6-byte TWS:** Space A40 (A3936), Liberty 3 Pro (A3952), Liberty 4 (A3953), Sport X20 (A3968) (S each).
5. **Gen-1 SPP, v1 4-byte modes:** Life Q30/Q35/Tune/Tune Pro, Liberty Air 2 Pro, Life Note 3/P3, Dot 2 NC/A2 NC,
   Q20i (M for the v1 codec and the SPP fallback, S per model). Popular but older; needs the v1 sub-mode
   workaround and `01 05` for firmware.
6. **Sleep A20 (A6611)** next to the existing Sleep A30 driver (S; same SL header).
7. **EQ writes** (M-L): `02 81` models first; `02 83`/`03 87` after an own DRC derivation or per-model captures.
   HearID read-only.
8. **Liberty 4 Pro** (M; outlier) and the **TLV generation** (L: new parser; writes unknown, need captures).
   Float/parametric-EQ models (P42i, AeroFit 2 Pro) last.

Model-table fields Earshot needs (mirrors `BoseModel`): code, display name, name regex, UUID suffixes, transport
(vendor or SPP), header variant, battery scale, minimum state length, length-byte position or fixed sound-mode
offset, mode codec, EQ write form, settings map (feature → offset + set command + body size), init script,
allow-list.

### Gaps
- Real-device confirmation for every non-D1301 model is still missing on Earshot's side. All offsets above come
  from other people's captures and firmware versions.
- Market share or popularity data per model was not researched. The ordering uses documentation depth and the
  Bose-competitor angle.
