# Changelog

## 0.4.0 — 2026-10-18

**Earshot adapts to your Bose model.** Like the Bose app, it now takes most of what it shows from
what the headphones or earbuds report, so each model gets its own modes, controls and settings, and
the header says which product and generation is connected.

### New
- **Model-aware screens.** The header shows your name for the headphones, the model and generation
  (for example "QuietComfort Ultra Earbuds · 2nd Gen") and a headphones or earbuds icon. Home,
  Modes and Settings show only what the model has; settings a model lacks are hidden instead of
  greyed out. Built-in mode names (Quiet, Aware, Immersion, Cinema, Stereo) come from the headphones.
- **More models:** QC Headphones (2nd Gen), QC Earbuds II, Ultra Open Earbuds (2nd Gen); basic
  support (battery, EQ, settings, noise level) for the QC Earbuds (2020) and Noise Cancelling
  Headphones 700. The QuietComfort 35 / 35 II and the 2024 QuietComfort Earbuds are recognised and
  explained as unsupported instead of failing.
- **Earbuds battery:** left, right and case (last known) on Home, in the widget and the
  notification; the low-battery alert watches the lower bud. **Listening time left** when the
  firmware estimates it.
- **Quick switcher:** tap the name on Home to switch between your paired Bose products.
- **Auto-off timer** in More (5, 20, 40 min, 1 h, 3 h, 24 h or never), on firmware that offers it.
- **LE Audio switch for every model:** it can be tried on headphones that report no support, after a
  warning that lists why it may not work (including whether this phone supports LE Audio). After the
  restart Earshot checks and tells you whether the change took.
- **Cleaner disconnected screen:** no stale controls; Connect, Choose headphones, tips and a
  one-tap connection report.
- **What's new** sheet after updating; **demo mode** can emulate QC Ultra Earbuds, QC Earbuds II,
  QC Earbuds, QC45, NC 700, Ultra Open and QC35 II.
- Noise-cancelling level on models without live noise control is written directly where the
  headphones allow it (QC Earbuds 2020, NC 700).

### Fixed
- **Earbuds on LE Audio (QC Ultra Earbuds, user report): "waits for the headphones to be
  connected".** With LE Audio on, Android lists the earbuds a second time as "LE-Bose …" with no
  classic Bluetooth link. Earshot now counts an LE Audio link as connected, treats the two entries as
  one product (one entry in the picker) and controls the earbuds over an LE channel, as the Bose app
  does. Switching LE Audio on no longer loses the headphones when the phone moves to LE Audio.
- The persistent notification no longer shows another model's mode names, or an "ANC off" button on
  headphones that can't switch it off.
- Custom modes are read and written in the exact record length each model uses (44, 45, 47 or 48
  bytes); mode names follow the headphones' own prompt ids.
- The "Noise cancelling" switch no longer wraps letter by letter beside Wind Block on narrow phones.

## 0.3.1 — 2026-10-17

Earshot is only tested on the QC Ultra Headphones (2nd Gen). Everything it assumed about the other
models is now checked while connecting, so a wrong guess costs a feature instead of the connection.

### Fixed
- **QC Ultra Headphones (1st Gen)**, reported "not working right" since 0.2.0: Earshot no longer
  relies on guesses about this model. If the headphones lack live noise control, noise changes are
  saved into the custom mode you are on; custom modes are written in the format the headphones
  actually send; settings the firmware quietly ignores are skipped instead of ending the connection.
- **QC Headphones / QC SC whose control channel is 9 instead of 8** (bosectl reports both): Earshot
  now tries channels 2, 8 and 9 and the phone's own service lookup on every connect, keeps the one
  that answers and remembers it. If a remembered channel stops working, it finds the new one.
- A channel that sends data but isn't the control channel (a status beacon or a log stream on some
  headphones and phones) is no longer mistaken for it.
- Headphones that ignore requests for features they don't have no longer drop and reconnect over
  and over; the connection stays up and those features are simply not shown.
- Headphones whose firmware doesn't offer listening modes to other apps now connect normally: the
  modes carousel and editor are hidden, noise cancelling is shown read-only when the headphones
  report it, and mode buttons explain why they can't work instead of failing.
- **QC Ultra Earbuds (1st and 2nd Gen)** are shown by their own name instead of as "QC Ultra
  Headphones (2nd Gen)"; the 2nd Gen follows bosectl's tested layout, the 1st Gen is a best guess.
- "Close the Bose app" still appears when another app holds the connection.

## 0.3.0 — 2026-10-17

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

### Fixed (found in review before release)
- A saved "Headphone type" now wins over the device name in every place that picks a stack, so a
  renamed Crusher or Bose no longer connects with the wrong one.
- A lost link during the Crusher's optional reads is no longer swallowed; the session closes and
  reconnects instead of showing "Connected" on a dead socket.
- Sidetone levels read back as the level that was written, whatever volume range the firmware reports.
- A Keystore failure while setting up the secure session no longer aborts the connect; a regenerated
  identity forgets the headphones it can no longer authenticate to; the pairing dialog shows on any
  tab and waits as long as the headphones do; the battery poll no longer greys the controls out.
- The link-up receiver waits until the right stack has started before finishing.

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
