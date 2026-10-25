# 0.8 spikes: answers so far (roadmap item 6)

Tested on the user's Galaxy Z Fold7 (SM-F966U1, One UI 9, Android 17) with the Bose QC Ultra
Headphones (2nd Gen), firmware 10.12.12, on 2026-10-25. Addresses masked as Android masks them.

## D4: codec switching through a Companion Device Manager association: **yes**

With the headset linked in Tools → Controls → Universal remote (CDM association id 9) and Shizuku
**not running**, Earshot's own process called the `@SystemApi` methods on its `BluetoothA2dp` proxy
by reflection:

- `getCodecStatus(device)` was accepted: "codec read through the companion link: AAC".
- `setCodecConfigPreference(device, config)` to SBC: Android broadcast SBC about 60 ms later, and
  the next read through the link said SBC. "Best quality" switched back to AAC the same way.
- `getSupportedCodecTypes()` still throws `SecurityException` (BLUETOOTH_PRIVILEGED); the config
  falls back to `setCodecType(int)`, which is enough for AAC and SBC. Vendor codecs that exist only
  as advertised `BluetoothCodecType` objects (Samsung Scalable, aptX Adaptive) may still need
  Shizuku; untested, since this headset negotiates only AAC and SBC with this phone.

So AOSP's `enforceCdmAssociationIfNotBluetoothPrivileged` on the A2DP codec calls holds on One UI 9.
Shipped as `codec/AssociationCodecPath` (experimental; a refusal turns it off and Shizuku stays the
path).

## D6: the QC Ultra 2's SDP record

From `dumpsys bluetooth_manager` (the phone's cache), device class `0x2C4404`, dual mode:

| Transport | UUIDs |
|---|---|
| BR/EDR | `00000000-deca-fade-deca-deafdecacaff`, SPP, A2DP sink, AVRCP, HFP, Battery, VCS, LE Audio, BASS, `109b862f-50e3-45cc-8ea1-ac62de4846d1`, `346d5c4b-00c2-4e4c-a679-fd02f02aa148`, `77265d41-a3ee-4391-8cb3-9c63521da246`, `88b0ee3c-3c66-492c-bd06-835676f316fe`, `9b26d8c0-a8ed-440b-95b0-c4714a518bcc`, `df21fe2c-2515-4fdb-8886-f12c4d67927c` (Fast Pair message stream) |
| LE | Battery, VCS, LE Audio, BASS, `109b862f-50e3-45cc-8ea1-ac62de4846d1` |

What it settles:
- `deca-fade` is in the record, next to SPP. Earshot reaches BMAP on channel 2 by reflection, so
  the `deca-fade` lookup stays what 0.75 made it: only for a headset that looks like, or answered
  as, a Bose.
- `346d5c4b…`, `77265d41…`, `88b0ee3c…` and `9b26d8c0…` also appear in the record of the QC Ultra
  Earbuds (2nd Gen) in GitHub issue #3. Two Bose products share them; they are a candidate Bose
  fingerprint that needs no connection, for recognising a renamed Bose (issue #3, postponed).
  `109b862f…` was not in the earbuds' record.
- What the four UUIDs carry is unknown; nothing is ever opened on them.

## D7: microphone mute over LE (MICS `0x184D`): **not on this headset**

The LE record lists Battery, VCS (volume), LE Audio and BASS, but no Microphone Control Service.
The QC Ultra 2's mic state keeps coming from BMAP `[2.21]`. MICS stays a possible generic path for
other LE Audio headsets; it needs one that lists it.

## Still open

- **D1** (which chip the user's generic earbuds use, and whether JieLi RCSP is on): needs those
  earbuds paired to the phone, then the same `dumpsys` read plus an LE advert capture.
- **D3** (can a normal app open L2CAP PSM `0x1001` to AirPods on One UI 9): needs the AirPods.
- **D5** (can Earshot open the Fast Pair message stream `df21fe2c…` while Google Play services
  holds it): the QC Ultra 2 offers it, but opening it could disturb Play services' own session, so
  it waits for an explicit yes.
