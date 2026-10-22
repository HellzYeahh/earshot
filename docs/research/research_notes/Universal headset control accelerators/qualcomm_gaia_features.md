# Qualcomm GAIA v3 standard features: can one generic driver control many brands?

Collected 2026-10-21. Framing basics are in `docs/research/headset-landscape-2026-10.md` and are not repeated here.
Licence of each source is given in brackets. **GPL/AGPL sources: facts only, never copy code or tables.**
Qualcomm's own GAIA v3 reference is login-gated, so it was not read. The feature numbers below come from
open community code that agrees across two brands. None of it was checked against hardware we own.

## 1. Standard GAIA v3 features, what ANC/Audio Curation and Music Processing expose, and how a host discovers support

### Takeaway
GAIA v3 groups commands into numbered *features* under Qualcomm's vendor ID `0x001D`. A host asks Basic
for the list of supported features and their versions. Open code shows two brands (EarFun, Moondrop)
answering the same IDs: Basic 0, Earbud 1, ANC 2, Voice UI 3, Music Processing 5, Upgrade 6,
Audio Curation 8 and Battery 13.
- **Audio Curation** gives a mode count, get and set mode, gain, toggle and scenario configuration, and
  change notifications.
- **Music Processing** gives EQ presets and a user EQ: band count, read and write.

### Cited Findings
- **Header layout.** Qualcomm's vendor ID is `0x001D`. The 16-bit command is `(feature << 9) | (type << 7) | pduId`,
  where type 0 = command, 1 = notification, 2 = response, 3 = error. The frame version byte is `0x04`.
  — [Gadgetbridge moondrop/GaiaPacket.java (AGPL-3.0)](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/moondrop/GaiaPacket.java)
  - The same layout appears in [moondrop-control docs/PROTOCOL.md §2 (GPL-3.0)](https://github.com/FEAKEuser/moondrop-control/blob/HEAD/docs/PROTOCOL.md).
  - It also appears in [Gadgetbridge earfun/EarFunPacket.java (AGPL-3.0)](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/earfun/EarFunPacket.java), which accepts versions 3 and 4 and sends no checksum.
- **Basic feature (0) commands:**
  - `0x0001` GET_SUPPORTED_FEATURES
  - `0x0007` REGISTER_NOTIFICATION
  - `0x000D` SET_TRANSPORT_PARAMETER

  — [EarFunPacket.java (AGPL-3.0)](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/earfun/EarFunPacket.java)

  Commands 0, 3, 4 and 5 read the GAIA version, serial, variant (model name) and application version.
  The Moondrop EDGE answered `03 01` to the version request.
  — [moondrop-control PROTOCOL.md §5 (GPL-3.0)](https://github.com/FEAKEuser/moondrop-control/blob/HEAD/docs/PROTOCOL.md)
- **Feature list an EarFun headset reports:**
  - battery v1
  - music processing v1
  - audio curation v7
  - earbud v3
  - upgrade v2
  - handset v1
  - basic v5

  — [EarFunPacket.java comment (AGPL-3.0)](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/earfun/EarFunPacket.java)
- **Discovery.** GET_SUPPORTED_FEATURES (`FF 04 00 00 00 1D 00 01`) returns (feature, version) pairs,
  possibly after one leading byte. moondrop-control decodes the EDGE answer as these features:
  - BASIC 0, EARBUD 1, ANC(V1) 2, VOICE_UI 3, EQ 5, UPGRADE 6, AUDIO_CURATION 8
  - BATTERY 13, DAC_GAIN 15, CODEC 16, LIGHT_SENSOR 17, MULTIPOINT 20, BT_ADDRESS 21

  It reads the answer as pairs, not as a 32-bit bitmap, because the pairs matched the commands that
  actually responded. It falls back to "try everything" if the probe fails.
  — [moondrop-control PROTOCOL.md §4 (GPL-3.0)](https://github.com/FEAKEuser/moondrop-control/blob/HEAD/docs/PROTOCOL.md)
- **Older handshake.** Some devices are found by first sending the GAIA v1/v2 "API version" command `0x0300` under vendor `0x000A`.
  - EarFun answers `03 03 01` ("GAIA v3, API v3.1"). — [EarFunPacket.java (AGPL-3.0)](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/earfun/EarFunPacket.java)
  - Moondrop Robin and Pudding use the same handshake. — [moondrop-control PROTOCOL.md §9 (GPL-3.0)](https://github.com/FEAKEuser/moondrop-control/blob/HEAD/docs/PROTOCOL.md)
- **Audio Curation (feature 8) commands seen on a Moondrop EDGE (fw 1.4.0):**
  - cmd 2: number of modes (`03 49 01 00` = 3 modes)
  - cmd 3: current mode
  - cmd 4: set mode, which gets no ACK
  - cmd 41: ANC toggle configuration
  - cmds 0/5/7/8/12/15/17/18/20/22–40 got no answer. Their labels are wind, gain step, auto-transparency,
    howling and scenario.

  — [moondrop-control PROTOCOL.md §5 (GPL-3.0)](https://github.com/FEAKEuser/moondrop-control/blob/HEAD/docs/PROTOCOL.md)

  FxxkMoondrop names cmd `0x29`/`0x2A` "get/set switch configuration". — [FxxkMoondrop GaiaConstants.kt (GPL-3.0)](https://github.com/bqj6666/FxxkMoondrop/blob/HEAD/src/com/fxxkmoondrop/secret/GaiaConstants.kt)
- **Audio Curation notifications EarFun sends:**
  - `0x1080` state change
  - `0x1081` mode change (e.g. `0A010000` = transparency)
  - `0x1082` gain change (`01014646`)
  - `0x1083` toggle config
  - `0x1084` scenario config
  - `0x1085` demo state

  — [EarFunPacket.java (AGPL-3.0)](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/earfun/EarFunPacket.java)
- **Audio Curation quirks:**
  - The mode is read as a 0-based index (0 off, 1 ANC, 2 transparency) but written as 1/2/4.
  - The read-back is 4 bytes, `[mode] 01 00 00`.
  - After a mode change the firmware ignores all commands for about 2 s.
  - SET commands are not ACKed, so the host must verify by reading back.
  - The firmware drops commands that arrive while one is in flight, so requests must be serialised.

  — [moondrop-control PROTOCOL.md §3, §6 (GPL-3.0)](https://github.com/FEAKEuser/moondrop-control/blob/HEAD/docs/PROTOCOL.md)

  Gadgetbridge's Space Travel 2 driver uses the same 0-based read and 1/2/4 write.
  — [MoondropSpaceTravel2Protocol.java (AGPL-3.0)](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/moondrop/MoondropSpaceTravel2Protocol.java)
- **Three ANC command families coexist under `0x001D`:**
  - AudioCuration, feature 8
  - "ANC V1", feature 2: cmd 1 get state, cmd 2 set state, values 0/1
  - "ANC V2", feature `0x20` = 32: values 0..5, read and written with the same value (Moondrop Pudding)

  — [moondrop-control README and PROTOCOL.md (GPL-3.0)](https://github.com/FEAKEuser/moondrop-control); [FxxkMoondrop GaiaConstants.kt (GPL-3.0)](https://github.com/bqj6666/FxxkMoondrop/blob/HEAD/src/com/fxxkmoondrop/secret/GaiaConstants.kt)
- **Music Processing (feature 5) on the Moondrop EDGE:**
  - cmd 0: EQ state
  - cmd 1: preset list (`06 00 01 02 03 04 3f` = count, then ids)
  - cmd 2: current preset
  - cmd 3: select preset
  - cmd 4: user EQ band count (`05`)
  - cmd 5: read the user EQ (payload = start band, end band)
  - cmd 6: write the user EQ (39 bytes)

  Preset `0x3f` is the user EQ. Each band record holds frequency (u16 BE, Hz), Q (u16 BE ÷ 4096),
  filter type (always 0 here) and gain (s16 BE ÷ 60, in dB).
  — [moondrop-control PROTOCOL.md §5, §8 (GPL-3.0)](https://github.com/FEAKEuser/moondrop-control/blob/HEAD/docs/PROTOCOL.md)

  Gadgetbridge's Space Travel driver uses feature `0x05` with PDU `0x02`/`0x03` to get and set the EQ preset.
  — [MoondropSpaceTravelProtocol.java (AGPL-3.0)](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/moondrop/MoondropSpaceTravelProtocol.java)
- **Battery (feature 13 / `0x0D`).** cmd 0 returns the supported battery ids. cmd 1 returns (id, level) pairs:
  - EDGE reports id 0 for the whole headset.
  - Space Travel reports 1 = left, 2 = right.
  - Some models report 3 = case.

  — [moondrop-control PROTOCOL.md §7 (GPL-3.0)](https://github.com/FEAKEuser/moondrop-control/blob/HEAD/docs/PROTOCOL.md); [FxxkMoondrop GaiaConstants.kt (GPL-3.0)](https://github.com/bqj6666/FxxkMoondrop/blob/HEAD/src/com/fxxkmoondrop/secret/GaiaConstants.kt)
- **"Audio curation" is Qualcomm's own term.** Product briefs use it, without naming GAIA:
  - QCC5100 series: chips built for "audio curation". — [Qualcomm QCC5100 product brief](https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/Qualcomm-QCC5100-Series-Bluetooth-Audio-SoCs.pdf)
  - S5 Gen 1: dedicated audio-curation cores for ANC, transparency and hearing compensation. — [Snapdragon S5 Gen 1 brief](https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/Snapdragon-S5-Gen-1-Sound-Platform-product-brief.pdf)

  Both claims come from a search-index summary of these Qualcomm PDFs. All Qualcomm material is proprietary.
- **Qualcomm says "GAIA protocol v3"** in its QCC300x product brief. — [Qualcomm QCC300x brief (proprietary, public PDF)](https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/qcc300x-family-product-brief_87-cf481-1-c.pdf)
- **Conflicting feature table.** SpaceTravel-Protocol lists feature 0 = device management, 1 = basic, 3 = ANC V2, 7 = EQ, `0x0A` = codec, `0x0B` = status.
  — [SpaceTravel-Protocol README (MIT)](https://github.com/pubglite55/SpaceTravel-Protocol)

  This is contradicted by Gadgetbridge (5 = EQ preset, 8 = audio curation) and by moondrop-control, which
  tested its table on hardware. Treat the SpaceTravel-Protocol table as unreliable.

### Inferences
- **Probe order for a generic driver.**
  1. Open the GAIA RFCOMM/SPP channel and run the `0x000A`/`0x0300` handshake. Some vendors may skip it.
  2. Send Basic `0x0001` (GET_SUPPORTED_FEATURES). Expose controls only for features that are present.
  3. Audio Curation (8): mode count, get, set, notifications.
  4. Music Processing (5): presets, user EQ.
  5. Fall back to ANC V1 (2) or ANC V2 (0x20) when Audio Curation is absent.
  6. Battery (13) for battery.
- **Mode meaning is not standard.** Mode numbering and payload encoding vary between vendors (index vs
  1/2/4 vs identity). The driver needs per-model "mode meaning" tables, or read-back verification after
  each write.
- **Version is a hint, not a promise.** The feature version (e.g. EarFun audio curation v7) probably
  decides which Audio Curation commands exist, e.g. gain, scenarios, wind and adaptive. A device can
  still advertise a feature and leave its commands unanswered (EDGE AC cmds; Nekocake Upgrade and Gesture).
- **Non-standard IDs are vendor additions.** Features 15–21 and `0x20`, and parts of Earbud (1), look like
  vendor additions inside Qualcomm's ID space, not ADK standards. Gadgetbridge uses feature 1 PDU 2/3 for
  touch actions on the Space Travel, but on the EDGE feature 1 returns serial numbers.

### Gaps
- Qualcomm's own feature list (IDs and names for Debug, Handset, Voice Processing, Gesture, Statistics and so on),
  the exact Audio Curation and Music Processing PDU semantics (gain range, adaptive ANC, leakthrough,
  scenarios, wind), and the GET_SUPPORTED_FEATURES paging rules are all in the *Gaia V3 Command Reference
  Manual* 80-CH482-1. Not read: it is login-gated (see §2).
- No open source confirms the numeric ID of the Debug or Handset features, or Audio Curation gain or
  adaptive-ANC write commands.

## 2. Qualcomm's public GAIA host references and their licences

### Takeaway
Qualcomm distributes its GAIA host code (the "GAIA Client" for Android and iOS) and the GAIA v3 command
manual only through its login-gated portal, labelled "QTIL Standard OEM". There is no open-source licence.
GitHub copies of Qualcomm's older "GAIA Control" app carry Qualcomm copyright headers with no grant, so
they **cannot be ported into MIT Earshot**. Re-implement from interoperability facts instead.

### Cited Findings
- **The manual is login-gated.** Qualcomm's documentation index lists the "Gaia V3 Command Reference Manual 80-CH482-1, HTML Rev AJ, 2026/08/26"
  for QCC3083/3086/3095/3056.
  — [Qualcomm docs listing QCC3086](https://docs.qualcomm.com/products/resources?query=&product=1601111739785009); [QCC3083](https://docs.qualcomm.com/list?product=1601111739785008) (from a search-index snippet; the pages are JS-rendered)

  A direct request for its content redirects to the Qualcomm login:
  - `/bundle/publicresource/80-CH482-1/topics/*.md` → 403 `{"location":"https://myid.qualcomm.com/api/v2/login…"}`
  - `/bundle/resource/topics/80-CH482-1/` → account.qualcomm.com OAuth

  — [docs.qualcomm.com 80-CH482-1](https://docs.qualcomm.com/bundle/publicresource/topics/80-CH482-1/) (probed 2026-10-21)
- **The GAIA Client is under an OEM licence.** Qualcomm's chipset software pages list "GAIA_Client.SRC.1.0 QTIL Standard OEM Android" and an iOS
  equivalent, plus a "GAIA Control Demo Application". "QTIL Standard OEM" points to an OEM licence, not open source.
  — [Qualcomm QCC5124 software](https://www.qualcomm.com/audio/products/qcc51xx-series/qcc5124/software); [QCC5125 software](https://www.qualcomm.com/audio/products/qcc51xx-series/qcc5125/software); [QCC3095 software](https://www.qualcomm.com/audio/products/snapdragon-s3-series/qcc3095/software)

  These come from search snippets. The pages render client-side and could not be read directly, and the
  licence text itself requires a download behind login.
- **The GitHub copy is not relicensable.** `qiu-yongheng/GAIAControl` is a 2017 copy of Qualcomm's Android GAIA Control app, with gaialibrary,
  vmupgradelibrary and blelibrary. GitHub shows it as Apache-2.0, but each source file is headed
  "Copyright 2017 Qualcomm Technologies International, Ltd." with no licence grant.
  — [qiu-yongheng/GAIAControl](https://github.com/qiu-yongheng/GAIAControl); [EqualizerGaiaManager.java header](https://github.com/qiu-yongheng/GAIAControl/blob/master/app/src/main/java/com/qualcomm/gaiacontrol/gaia/EqualizerGaiaManager.java)
- **More unlicensed copies.** Other GitHub copies of Qualcomm GAIA apps (droid_gaja_ctrl, GAIAcontrol, FastBleTool, JBand) are linked
  from a blog post, with no licences stated. — [cweiske.de "Bluetooth GAIA protocol" (CC BY-NC-SA 3.0)](https://cweiske.de/tagebuch/bluetooth-gaia.htm)
- **Open projects avoid Qualcomm code.** momentum4-control states it contains "no Sennheiser, Sonova or Qualcomm software", and moondrop-control
  states it copied no upstream source and re-implemented from functional facts.
  — [momentum4-control README (MIT)](https://github.com/Ariazonaa/momentum4-control); [moondrop-control README §9 (GPL-3.0)](https://github.com/FEAKEuser/moondrop-control)

### Inferences
- An uploader cannot relicense Qualcomm-copyrighted code. Earshot should not copy from GAIAControl copies
  or from the GAIA Client, even though one repo is tagged Apache-2.0.
- **MIT sources Earshot may port with attribution:**
  - framing: budsctl, momentumctl, momentum4-control, SpaceTravel-Protocol, moonmode
  - command IDs: re-implemented from the GPL facts above
- Using the gated manual would mean accepting Qualcomm account terms. Check those terms before relying
  on it, or keep to community facts.

### Gaps
- The exact licence text of GAIA_Client.SRC and of the free "GAIA Control" store apps was not read (login required).
- No Qualcomm-authorised open release of any GAIA host library was found (searched GitHub and qualcomm.com).

## 3. Which brands answer the standard Qualcomm GAIA v3 features, and which use their own vendor IDs or protocols

### Takeaway
Only a few retail brands are publicly shown answering standard `0x001D` features.
- **EarFun:** advertises Audio Curation and Music Processing, but open drivers set modes through its own
  `0x000A` commands.
- **Moondrop:** EDGE, Space Travel 2 and Robin use Audio Curation and Music Processing directly.
- **Others:** Sennheiser (`0x0495`) and vivo (`0x001B`) use their own vendor IDs. SoundPEATS uses GAIA v2
  vendor commands. 1More, Edifier, Nothing, Jabra and B&W do not use GAIA at all in open drivers.

So "one driver" would reach a minority of brands directly, plus many through per-brand adapters on the
same framing.

### Cited Findings
- **EarFun** Air S (QCC3046), Air Pro 4 (QCC3091) and Free Pro 3 (QCC3072) are "highly supported" in Gadgetbridge:
  - ANC off/NC/transparency, NC sub-modes, transparency modes
  - 6- or 10-band EQ with presets
  - codecs, game mode and more

  — [Gadgetbridge EarFun page (AGPL-3.0 project)](https://gadgetbridge.org/gadgets/headphones/earfun/)

  The driver accepts vendors `0x000A` and `0x001D`.
  - It reads the GAIA v3 feature list and Audio Curation notifications.
  - Ambient/ANC/transparency writes go through EarFun's own `0x000A` commands: `0x0314` ambient sound,
    `0x033A` ANC mode, `0x033C` transparency mode.
  - The EQ uses commands it labels "GAIA v3 non-standard": `0x0E01` set band, `0x0E02` config,
    `0x0F81` band response, under `0x001D`.

  — [EarFunPacket.java (AGPL-3.0)](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/earfun/EarFunPacket.java)
- **Moondrop**
  - EDGE (Qualcomm, fw 1.4.0): standard AC (8) and Music Processing (5) with a 5-band PEQ, tested on hardware.
  - Robin: AC (8), from public docs, not tested.
  - Pudding: ANC V2 (`0x20`).
  - Space Travel: auto-probed.
  - Nekocake (Bluetrum BT8922E): speaks GAIA 3.1 framing with only Basic, Earbud, Voice UI, Music
    Processing and Upgrade. No ANC command answers.

  — [moondrop-control README / PROTOCOL.md §9, §11 (GPL-3.0)](https://github.com/FEAKEuser/moondrop-control)

  Space Travel (EQ preset via feature 5) and Space Travel 2 (AC feature 8) are in Gadgetbridge.
  — [MoondropSpaceTravelProtocol.java / MoondropSpaceTravel2Protocol.java (AGPL-3.0)](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/moondrop/MoondropSpaceTravel2Protocol.java)

  An MIT Android app controls Space Travel 2 ANC "over GAIA V3 BLE". — [moonmode (MIT)](https://github.com/camilopaezz/moonmode)
- **SoundPEATS** Air4 Pro (verified, fw `AIR4PRO-BS588R2E_20241112_v0.2.1`) speaks "GAIA V2" over BLE, service `00001100-D102-11E1-…`.
  - vendor `0x000A`, vendor-group commands: `0x0306`/`0x0307` battery L/R, `0x0309` firmware,
    `0x0310`/`0x0311` get/set ANC mode
  - The status byte is advisory: unsupported commands return `0x01`.

  — [budsctl README and GaiaFrame.swift (MIT)](https://github.com/hammad-iftikhar/budsctl)
- **Sennheiser**
  - Momentum 4: vendor `0x0495`, BLE GAIA UUID `a2129ff3-081b-4c45-8afe-469d9c4842ec`,
    frame start `0xFF`, flags `0x03`. — [momentumctl codec.rs / ble.rs (MIT)](https://codeberg.org/galen/momentumctl)
  - Momentum 4 and 5 over RFCOMM GAIA: ANC strength, adaptive ANC, anti-wind, 5-band EQ (8-band on M5).
    — [momentum4-control (MIT)](https://github.com/Ariazonaa/momentum4-control)
  - Older MOMENTUM In-Ear Wireless: GAIA **v1**. "Every command uses the CSR vendor id; the headset implements none under Sennheiser's own."
    — [Gadgetbridge SennheiserMomentumProtocol.java (AGPL-3.0)](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/sennheiser/SennheiserMomentumProtocol.java)
- **vivo** TWS Air3 Pro and 3e: GAIA v3 frames. The handshake goes over `0x000A`; vivo commands use vendor `0x001B`
  (e.g. noise mode `…1B 01 30 00 04 00`). — [HyperEars vivo protocol doc (GPL-3.0)](https://github.com/silverpoetry/HyperEars/blob/HEAD/docs/vivo-tws-air3-pro-protocol.md)
- **Not GAIA in open drivers:**
  - 1More SonoFlow: own frames, preamble `11 01 00`, noise control `0x5e`/`0x5f`.
    — [Gadgetbridge OneMorePacket.java (AGPL-3.0)](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/onemore_sonoflow/OneMorePacket.java)
  - Jabra Evolve2 55: "proprietary SPP/RFCOMM protocol". — [Gadgetbridge JabraEvolve255Support.kt (AGPL-3.0)](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/jabra/JabraEvolve255Support.kt)
  - Bowers & Wilkins P-series: own BLE RPC, characteristic `ada50ce9-67b8-4a97-9d8e-37e1d083156c`.
    — [Gadgetbridge BandWBLEProfile.java (AGPL-3.0)](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/bandwpseries/BandWBLEProfile.java)
  - Edifier: private BES RFCOMM transport. — [HyperEars compatibility.md (GPL-3.0)](https://github.com/silverpoetry/HyperEars/blob/HEAD/docs/compatibility.md)
  - Nothing and Edifier also appear as non-GAIA in the existing landscape doc.
- **Tozo:** only an unnamed GitHub-topics listing of a Linux BLE app (battery, EQ, game mode). No protocol or licence found.
  — [GitHub topic "earbuds"](https://github.com/topics/earbuds)
- **Airoha RACE: not covered.** No openly licensed source documenting a standard RACE ANC/EQ command set was found in this pass.

### Inferences
- **Brand tiers for a generic `0x001D` driver:**
  1. **Direct:** devices whose firmware answers AC/MP as Moondrop EDGE and ST2 do. Possibly EarFun too: it
     advertises AC v7 and MP v1, but whether standard AC writes work there is unverified.
  2. **Same framing, own command set:** EarFun (`0x000A` vendor commands), SoundPEATS (GAIA v2 `0x03xx`),
     vivo (`0x001B`), Sennheiser (`0x0495`).
  3. **Different protocol entirely.**
- **The codec layer is the real accelerator.** Shared framing, the feature probe, a serial request queue and
  read-back verification serve tiers 1 and 2. A thin per-brand table then handles mode meaning and EQ layout.

### Gaps
- Soundpeats GAIA v3 models, EarFun response to standard AC SET, and Edifier or Tozo models on Qualcomm
  chips: no open source found.
- No public data on how many retail models keep standard AC/MP enabled. Brands often reuse `0x001D`
  with their own feature IDs (Moondrop 15–21, 0x20).

## 4. GAIA v2 (older CSR/QCC) differences, briefly

### Takeaway
GAIA v1/v2 has no feature negotiation. It uses vendor `0x000A` (CSR) and a 16-bit command split into
fixed groups, with bit 15 marking a reply and a status byte. Brands put their ANC/EQ commands in the
"status" group under the shared CSR vendor ID, so v2 command meanings are vendor-specific.

### Cited Findings
- **v1/v2 command groups** (type byte):
  - `0x01` configuration
  - `0x02` control
  - `0x03` status
  - `0x05` feature
  - `0x06` data transfer
  - `0x07` debug
  - `0x40` notification
  - `0x80` acknowledge (ACK mask `0x8000`)

  Vendor `0x000A` is labelled "V1/V2". — [EarFunPacket.java (AGPL-3.0)](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/earfun/EarFunPacket.java)
- **v1 frame:** `FF 01 flags length vendor(2) command(2) payload`. A reply has bit 15 set and its first byte is the status.
  — [Gadgetbridge SennheiserMomentumProtocol.java (AGPL-3.0)](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/sennheiser/SennheiserMomentumProtocol.java)

  The same reply shape (command | 0x8000, then status) holds over BLE GAIA for SoundPEATS.
  — [budsctl GaiaFrame.swift (MIT)](https://github.com/hammad-iftikhar/budsctl/blob/HEAD/Sources/BudsKit/GaiaFrame.swift)
- **The same `0x03xx` numbers mean different things per brand:**
  - EarFun: `0x0314` ambient, `0x033A` ANC mode.
  - SoundPEATS: `0x0310`/`0x0311` ANC mode.

  — [EarFunPacket.java (AGPL-3.0)](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/earfun/EarFunPacket.java); [budsctl (MIT)](https://github.com/hammad-iftikhar/budsctl)

### Inferences
- A v2 device can be detected and identified generically (API version `0x0300`, application version
  `0x0304`), but ANC/EQ needs a per-brand map.
- The Qualcomm-standard v2 EQ commands that the old GAIA Control app used are only documented in the
  Qualcomm-copyright copies, so they are not usable here.

### Gaps
- No permissively licensed or GPL source lists Qualcomm's standard v1/v2 EQ or "feature" group commands.

## 5. Commands an app must never send (for the allow-list)

### Takeaway
Use an allow-list.
- **GAIA v3:** only Basic reads, Battery reads, Audio Curation, Music Processing and verified vendor IDs.
  Never Upgrade (feature 6), and never an unknown feature.
- **GAIA v1/v2:** block the configuration/control groups (`0x01xx`/`0x02xx`) and the data-transfer and debug
  groups (`0x06xx`/`0x07xx`), plus known vendor reset, reboot, power-off and pairing-wipe commands.

### Cited Findings
- **v3 Upgrade feature.** Feature 6 is UPGRADE (EDGE, Nekocake), and EarFun reports "upgrade v2".
  — [moondrop-control PROTOCOL.md §4, §11 (GPL-3.0)](https://github.com/FEAKEuser/moondrop-control/blob/HEAD/docs/PROTOCOL.md); [EarFunPacket.java (AGPL-3.0)](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/earfun/EarFunPacket.java)
- **v1/v2:** "`0x01xx` and `0x02xx` contain device reset and power off". budsctl deliberately names only the `0x03xx` group.
  — [budsctl README / GaiaFrame.swift (MIT)](https://github.com/hammad-iftikhar/budsctl)

  Sennheiser v1 `0x0204` = POWER_OFF. — [SennheiserMomentumProtocol.java (AGPL-3.0)](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/sennheiser/SennheiserMomentumProtocol.java)
- **EarFun vendor commands to block** (vendor `0x000A`):
  - `0x0305` FACTORY_RESET
  - `0x0308` COMMAND_REBOOT
  - `0x0351` SET_CONTROL_PLAY (`01` = turn off device)
  - `0x032A` DISCONNECT_DEVICE
  - `0x032B` TRIGGER_PAIRING

  — [EarFunPacket.java (AGPL-3.0)](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/earfun/EarFunPacket.java)
- **Sennheiser M4:** "disconnect device" wiped the pairing on some firmware, and firmware update and factory reset are deliberately blocked.
  — [momentum4-control README (MIT)](https://github.com/Ariazonaa/momentum4-control)
- **v1/v2 groups `0x06` data transfer and `0x07` debug exist** (type names only).
  — [EarFunPacket.java (AGPL-3.0)](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/earfun/EarFunPacket.java)

### Inferences
- **Earshot WriteGuard rules for GAIA:**
  - Refuse every write outside the allow-list.
  - Refuse every `0x001D` feature not returned by GET_SUPPORTED_FEATURES.
  - Never send feature 6.
  - Refuse any feature Earshot does not explicitly know (this covers the Debug feature, whose ID is
    unconfirmed) and any vendor ID not on the list.
  - For v1/v2, allow only named `0x03xx` reads and writes per brand.
- Probing on the wrong stack is risky: the landscape doc records GAIA frames powering an Accentum off. So
  GAIA probes should run only on channels with GAIA service UUIDs.

### Gaps
- Qualcomm's own list of destructive v3 commands (Debug feature contents, Basic reset or reboot PDUs, if
  any) is in the gated manual and was not read. The v1/v2 VM-upgrade command numbers likewise have no
  usable source.
