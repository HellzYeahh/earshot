# Changelog

## Unreleased

**Skullcandy Crusher 1080 ANC (experimental).** The Crusher's "Sound by Bose" features run on a
different protocol from the Bose headphones (Bragi BOS-RPC), so Earshot gains a second stack behind
the same screens. It is complete in the virtual-headphones tests and in demo mode, but has not been
run on real hardware: see [docs/crusher-1080.md](docs/crusher-1080.md).

### Added
- Crusher control: Quiet / Aware / Off modes with a gain slider, Immersive Audio (Still / Motion),
  sidetone, battery, and a **Crusher bass** card (on/off, intensity, high / medium / low feel).
- One-time pairing dialog and encrypted session for firmware that requires one (the passcode you
  compare with the headphones); keys stay on the phone, the identity key is wrapped by the Android
  Keystore.
- **Headphone type** setting (Automatic / Bose / Skullcandy Crusher) for headphones you renamed.
- Lab: **Copy connection report** (model, link variants tried, services the phone has seen, recent
  log) for bug reports; the read-only scan also covers the Crusher.
- Demo mode can emulate a Crusher, or a Crusher that asks for pairing.

### Changed
- `ConnectionManager` is generic over the session type with a per-family link factory; the Bose
  path behaves as before. A routing layer picks the Bose or Crusher repository for the paired
  headphones, so Home, the widget, the tile and automations work unchanged on either.

## 0.2.0 — 2026-10-17

**More Bose headphones.** Earshot now reads which model is connected and adapts to it, instead of
assuming a QC Ultra Headphones (2nd Gen).

### Added
- Support for the **QuietComfort Ultra Headphones (1st Gen)**, **QuietComfort Headphones (1st and
  2nd Gen)**, **QuietComfort SC**, **QuietComfort 45**, **QuietComfort SE** and **Ultra Open
  Earbuds (1st and 2nd Gen)**. See the support table in the README for what is verified.
- The model name (e.g. "QuietComfort 45 / QC SE") is shown on the More screen.
- QC45 / QC SE / QC Headphones / QC SC: the noise-cancelling slider and Wind Block work by updating
  the active custom mode (these headphones have no live noise setting). On a built-in mode the
  controls explain that you need to switch to a custom mode first.

### Changed
- **Connection**: the control channel is chosen per model (channel 2 on the QC Ultra family,
  channel 8 on the QC45 / QC Headphones), with automatic fallback to the other one. The channel that
  works is remembered, so reconnects go straight to it.
- The connect check now sends the request the QC45 / QC SE need before they answer anything.
- Listening modes: built-in and custom slots, the number of slots and the mode record layout now
  follow the connected model.
- Controls a model doesn't have are hidden: the ANC on/off switch and Immersive Audio on the older
  models, the noise controls on open-ear earbuds, and mode editing where it isn't supported.
- Texts no longer assume a QC Ultra (widget, notification, onboarding, About).

### Faster
- A refresh skips reads that can't succeed on the connected headphones: functions the model is
  known not to have, function blocks the headphones don't list, and anything they already refused
  on this connection. The product id is read once per connection, not on every refresh. On the older
  models this saves about a dozen requests per refresh.

### Notes
- Only the QC Ultra Headphones (2nd Gen) were tested on our own hardware. The QC45 / QC SE and QC
  Headphones / QC SC layouts follow [bosectl](https://github.com/aaronsb/bosectl), which verified
  them on real units. The QC Ultra (1st Gen), QC Headphones (2nd Gen) and Ultra Open Earbuds (2nd
  Gen) are best guesses and are marked unverified. Reports and captures are welcome.

## 0.1.1 — 2026-10-17

- Safety: the app refuses any write to the firmware-update or authentication blocks and the
  factory-reset, ship-mode, reset and clear-device-list functions, on its single write path.

## 0.1.0 — 2026-10-17

- First release for the Bose QuietComfort Ultra Headphones (2nd Gen).
