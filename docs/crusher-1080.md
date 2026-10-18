# Skullcandy Crusher 1080 ANC ("Sound by Bose")

Earshot can control the Crusher 1080 ANC, but **it has not been tried on real headphones yet**.
Everything below was derived from analysing the official Skullcandy app (5.0.6) for
interoperability, and is exercised by a virtual Crusher in the tests and in demo mode. Reports from
owners are what turns "should work" into "works".

## Why it is a separate stack

The Crusher is **not** a Bose BMAP device. It is an Airoha chip (model `S6CNW`, Bragi codename
`CHP300`) controlled with Bragi's **BOS-RPC**, a protobuf protocol. That is why Earshot's Bose
connection opens a socket to it but never gets an answer. The Bose features in the headphones
(QuietControl noise cancelling, TrueSpatial) are exposed through BOS-RPC services, together with the
Crusher bass and a parametric equaliser.

| Layer | Bose headphones | Crusher 1080 ANC |
|---|---|---|
| Link | RFCOMM channel 2 (or 8), `createInsecureRfcommSocket` | RFCOMM data service `54C5BF4C-C52F-4635-98DC-EEBF97C6726F`, secure socket, channel from SDP (the third-party service `A4602B24-…` carries Airoha's own RACE protocol, which Earshot never speaks) |
| Framing | BMAP packets | `bos_ppp_framing`: `7E FF 80 <id> <len> <CRC-8> body <CRC-16>` |
| Calls | `[block.function]` GET / SET_GET / START | `(service, method)` + protobuf body |
| Security | none | optional one-time pairing + per-session ECDHE (when the headphones ask) |

Code: `protocol/.../bragi/` (wire format and crypto, pure Kotlin), `app/.../bt/Bragi*.kt`
(connection, link profiles, secure session), `app/.../service/Crusher*.kt` (protocol mapping and
repository), `app/.../virtual/VirtualCrusher*.kt` (the simulated headphones).

## What the app offers on the Crusher

| Earshot | Crusher call |
|---|---|
| Modes **Quiet / Aware / Off** | `hearing.mode_configure {mode: ANC / AT / OFF, activate}` |
| Noise slider (0..10) | the active mode's gain (`hearing.mode_configure {mode, gain}`) |
| Immersive Audio **Off / Still / Motion** | `spatial_audio.audio_source_mode_set` (OFF / FIXED_TO_HEAD / FIXED_TO_ROOM) |
| Sidetone | `hearing.sidetone_configure {enabled, volume}`, folded into Off / Low / Medium / High |
| **Crusher bass** card | `crusher.state_set {is_active, intensity, mode}` (feel: 0 high, 1 medium, 2 low) |
| Battery | `device.batt_info` (polled each minute; no push exists) |

Slots 0 and 1 are Quiet and Aware, the same as on the Bose models, so automations, the tile and the
widget keep their meaning. Custom modes, favourites, the headphones' own equaliser and the
Bose-only switches are not offered. (The parametric equaliser calls are implemented in the protocol
layer but not wired into the UI yet; Earshot's own Pro EQ works with any headphones.)

## What is verified, and how

| Fact | Evidence |
|---|---|
| Message layouts, enums, service and method numbers | protobuf definitions shipped in the app, cross-checked with its service registry |
| PPP framing and both CRCs | reproduced byte for byte from frames recovered from the app (CRC-8/ROHC `2D` and CRC-16/MCRF4XX `5480` for `dev_info` tag 0) |
| `dev_info` is the first call, with body `08 00`; nothing is sent on connect | the app's own connect sequence |
| BOS-RPC frames go raw on the data service socket; the third-party socket is Airoha RACE only | the app's transport wiring (two separate RFCOMM sockets), decompiled; the data and third-party UUIDs come from the Crusher's device configuration |
| Secure-session crypto (P-256 ECDHE, ECDSA-SHA512, HKDF-SHA256, AES-GCM / CCM / ChaCha20) | the protocol description in the app plus an independent implementation tested end to end against the virtual Crusher |

## Still to be confirmed on real hardware

1. **Which service answers.** The data service is tried first and the third-party one second; the
   one that answers is remembered (`BragiLinkProfile`). The connection report lists what was tried.
   Earshot only ever writes PPP-framed BOS-RPC: no Airoha RACE command is sent.
   For comparison, the official app opens **both** sockets: its bundled Airoha SDK talks RACE on
   the third-party one (it reads the chip name and device type there, and treats that as part of
   "connected"), while every BOS-RPC call, `dev_info` included, goes over the data one. Whether the
   headphones answer BOS-RPC without that RACE conversation is untested; if the connection report
   shows the data service connecting but never answering, that is the first thing to look at.
2. **Whether the headphones require the secure session** (`dev_info.authenticated_access`). If they
   do, Earshot shows a one-time pairing dialog with a passcode to compare with the headphones.
   If not, nothing changes. The official app carries on without it if the pairing fails.
3. **The `STATUS` body encoding** of errors (Earshot treats an empty body or a leading zero as success).
4. Whether inbound frames echo the service, method and tag (Earshot matches on service and method).
5. Real ranges (gain, sidetone volume, Crusher intensity). Earshot reads them from the headphones.

## Trying it

1. Pair the headphones in Android's Bluetooth settings and **close the Skullcandy app** (only one
   app can hold the control connection).
2. Open Earshot and pick the headphones. Earshot recognises "Skullcandy CRUSHER 1080 ANC"; if you
   renamed them, set **App preferences → Headphone type → Skullcandy Crusher**.
3. If it does not connect, or a control does nothing: **Tools → Lab → Copy connection report** and
   include it in your issue. It contains the headphones' Bluetooth name and address, the services
   your phone has seen, which link variants were tried and the recent connection log.
4. Without the headphones: **Preferences → Demo mode → Crusher 1080** (or **Crusher (pairing)** to see the pairing dialog).

## Safety

* Only the services in `BragiServices.ALLOWED_SERVICES` can be called: device info, hearing,
  spatial audio, Crusher and the secure-session handshake. The firmware-update surface and the
  feature-toggling service are not addressable, mirroring the Bose write guard.
* The Lab only issues read-only calls.
* The pairing keys are generated on the phone and stay there: the identity private key is wrapped
  with an Android Keystore key, and nothing is sent to any server. Earshot verifies the headphones'
  signature in the secure session.

## Provenance

This was written from interoperability analysis of the publicly released Skullcandy app, so that
owners of the headphones can use a third-party controller; no code or assets were copied. The protobuf definition files themselves are not
redistributed (they carry a Bragi copyright notice): only the field numbers and constants needed to
talk to the device appear in the source.
