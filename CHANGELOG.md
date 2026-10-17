# Changelog

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
