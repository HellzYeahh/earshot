# Changelog

## 0.75.0 — unreleased

**Any headset, and the first drivers beyond Bose.** Earshot is becoming the companion app for every
Bluetooth headset, not just Bose (plan: [docs/roadmap.md](docs/roadmap.md)). Every headset now gets
the features that work through Android itself, a universal remote, and a new look; Sennheiser, Sony,
more Soundcore models and the Skullcandy Method buds get their own experimental drivers, built from
protocol research ([docs/research/reports](docs/research/reports)). Bose keeps its full control.
0.6 was never released: its work ships here.

### New
- **Universal remote, for any headset** (Tools → Controls). Link the headset once (one Android
  dialog, no re-pairing) and Earshot can **connect** and **disconnect** it (Android 17), set the
  **name this phone shows** (Android 13+) and **forget** it (Android 16), whatever the brand and
  with or without an Earshot driver. Rows this Android version can't do say which version they need.
- **Battery straight away on more headsets, without Shizuku:** a headset that speaks Bluetooth LE
  is asked once for its standard Battery Service level when nothing else knows it yet.
- **A look of its own: Field.** Olive, sand and ink with a safety-orange indicator; Barlow
  Condensed titles, Barlow text, JetBrains Mono for values and legends (bundled, SIL OFL);
  hairline panels on a dot grid, a tick-marked battery dial, square switches and bar sliders.
  It replaces the wallpaper-derived colours as the default. **App preferences → Appearance**
  keeps **System colours** (Android 12+) as an option: Field's type and shapes with the
  wallpaper's palette.
- **Soundcore Sleep A30 (experimental, tested on one pair):** Earshot's first non-Bose driver.
  - Home has an **Earbud controls** card: a Noise cancelling switch and the Listening mode
    (Bluetooth or the earbuds' own Local sounds).
  - More has the rest:
    - the mode they start in;
    - switch once asleep, and what to do then (pause, or play local sounds);
    - incoming calls in Bluetooth mode;
    - low-battery, noise-cancelling, listening-mode and auto power-off prompts.
  - Tap controls are on Tools → Controls. Per-bud battery and firmware show too.
  - It talks only on the earbuds' Soundcore service: unpair, pairing mode, power off, the
    find-device tone and any unknown command are refused before they leave the phone. Close the
    soundcore app while Earshot is connected (only one app can hold the connection).
  - Protocol facts from OpenSCQ30 (GPL, facts only) and soundcorebridge (MIT).
  - Demo mode has a virtual Sleep A30.
- **Headsets without an Earshot driver are shown as themselves.** AirPods, Sony, Soundcore,
  no-name earbuds and the rest appear by their own Bluetooth name. Home shows "Connected" while the
  headset is linked for audio, its battery as it reports it to Android (straight away with Shizuku),
  and what works with it: Pro EQ, codec, call audio, Controls, automations. Earshot never sends
  anything to them, and screens hide the settings only a driver could change.
- **AirPods and Beats: left, right and case battery and charging**, read from the Bluetooth adverts
  they broadcast. No connection, nothing sent. Needs "Nearby devices", which Android grants together
  with the permission Earshot already has. Demo mode has a virtual AirPods Pro.
- **The connected headset is the one shown.** Opening Earshot with AirPods on shows the AirPods
  instead of waiting for the Bose you used last. A headset you turn on later takes over too. Picking
  a device in the switcher keeps it until another headset links up or the app restarts.
- **Pro EQ remembers a preset per headset** and applies it when that headset connects: the Bose
  correction doesn't follow you to the Soundcore.
- **Codec panel without Shizuku:** the current codec shows from the next codec change Android
  announces (switching still needs Shizuku).
- **Headphone type: Soundcore (Sleep A30) and Other headset**, for a device that Earshot recognises wrongly.

### Changed
- **Tools never hide a setting.** Touch controls, voice prompts, rename, power off, LE Audio,
  latency and the Lab stay on screen for any headset and can be tried while it is connected; a
  headset that doesn't support one says so. A Bose-only command can be tried on another headset by
  setting Headphone type to Bose. Earshot then only looks the Bose service up by its ID and never
  probes channels blind, since some earbuds keep a firmware-update channel on one of them.
- **Calls, automations and the background service follow the headset's audio link**, not only
  Earshot's control link: with the Bose app holding the control channel, or on a headset without a
  driver, the call boost and EQ automations keep working.
- Bose detection by name is stricter: "QCY" earbuds and "… Ultra" names no longer count as Bose. A
  Bose you renamed stays Bose once Earshot has talked to it, and existing setups carry over.
- The "Headphone type" setting now belongs to the device it was set for.
- The device switcher lists every paired headset, not only the Bose products. Car kits, speakers and
  watches are left out.
- "Close the … app" names the app that actually holds the channel (Bose or Skullcandy) and says
  music and calls keep working meanwhile; the tip only shows for Bose.
- Wording: "Headphones", "No paired headphones found", "Your headphones" instead of Bose-specific
  text where any headset can be meant. About: not affiliated with any headphone maker.

### Fixed
- **A headset forced to "Headphone type: Bose" no longer gets a frame on Apple's accessory
  channel.** The Bose lookup by the `deca-fade` service ID most likely finds the iPhone accessory
  (iAP2) channel on other brands, and a stray frame there has switched a Sennheiser Accentum off.
  Only a headset that looks like or answered as a Bose gets that lookup now.
- **Call volume boost didn't reach the headset.** Android keeps one call volume per output and
  applies a change to the output the call uses *at that moment*. The boost fired 1 s after the call
  started, often before the Bluetooth call link was up, so it raised the earpiece (seen on a Galaxy:
  earpiece 15/15, headset 10/15). It now waits until call audio really reaches the headset and checks
  again for 12 s (catching One UI putting its saved volume back), for any headset, not only Bose.
- **Unknown headsets got the Bose connection search.** Earshot used to treat any headset it didn't
  recognise as a Bose and opened Bose control-channel sockets on it.
- **Controls named the watch as your headset:** a smartwatch that takes calls (Galaxy Watch) is
  connected like a hands-free headset and was shown on the Controls screen. Watches and car kits are
  skipped now.

## 0.5.0 — 2026-10-20

**Controls: give your headset's press-and-hold a new job.** A new screen at the top of Tools
remaps the shortcut gesture of any Bluetooth headset, finds out what each of its controls does,
and gathers the Bose headphone controls in one place. Touch controls on Bose headphones were
inverted and are fixed.

### New
- **Controls** (top of Tools): give a headset's press-and-hold a new job. One dropdown,
  "Shortcut Button 1:", under the connected headset's name: Phone assistant (the default), Cycle
  EQ preset (announced in the headset), Voice memo (headset mic, saved to Recordings/Earshot) or
  Launch an app; on Bose headphones also their own Immersive Audio or Off. Works with any
  Bluetooth headset whose press-and-hold calls the phone's voice assistant. The choice is kept per
  headset without any list to manage. No setup screen: the first time you hold the button after
  picking an action, Android asks which app to open; pick Earshot, then Always. Earshot only
  offers itself for the gesture while an action or "Detect a button" needs it, so nothing changes
  for anyone who keeps Phone assistant.
- **Automatic Bose Shortcut button:** Earshot sets the headphones' Shortcut button itself:
  Voice assistant for an Earshot action, or Immersive Audio / Off when you pick those, and puts it
  back on connect when it was changed. Only modes the headphones report are written, never twice
  in a row. For someone who never picked anything on the Controls screen only a button on Spotify
  Tap is changed (to Voice assistant), because Spotify Tap does nothing while Earshot is connected.
- **Detect a button** (Controls): press each control on your headset and Earshot says what it
  does: the assistant gesture ("This is your shortcut: you can remap it"), play/pause, track and
  volume (they go straight to the music app; Android doesn't let apps remap them), Bose button
  events by their Bose names, and call controls while a call is on. It never assumes which
  control the shortcut is (on one QC Ultra 2 it was a short press of the power button): once
  found you name it, and the screen shows e.g. "Power button, single press ✓". Hints for touch
  controls being off, no button on the assistant, and other headsets. Works for any Bluetooth
  headset from Android's own signals; Bose pushes add detail. Demo mode can press virtual controls.
- **One row per Bose button:** earbuds that report a shortcut record per bud get "Shortcut
  Button 1 (right earbud)" and "Shortcut Button 2 (left earbud)", each with the modes it reports
  and written with its own button id. A second button can share Shortcut Button 1's action
  (the phone can't tell two assistant buttons apart, and the screen says so) or do the
  earbuds' own Immersive Audio / Off.

### Changed
- **"Shortcuts remapper" is now "Controls", and the headphone controls live there:** Touch
  controls, Voice prompts and Prompt language, Devices, Rename and Power off sit on the Controls
  screen below the shortcut buttons (same rules and dialogs as before). The Tools tab shows the
  Controls row first ("Shortcut Button 1: Cycle EQ preset · Touch controls on"), then the other
  tools. The Shortcut button row is gone from More (Controls sets it). More keeps sidetone, auto
  play/pause, auto-answer, multipoint, auto-off, About these headphones and the App section.
- **Call audio shows the call link:** the headset's call volume and mic gain (0–15, as the
  headset reports them) and whether the phone's own echo cancelling and noise reduction run on
  top of the headphones'. Shown when the phone reports them.

### Fixed
- **Touch controls were inverted:** turning the switch on turned the headphones' touch strip off
  (volume swipes and the touch-and-hold Shortcut stopped working). Bose's own app reads the
  setting the other way round, and the QC Ultra 2 confirmed it. If you used the switch in 0.4.0,
  it now shows the real state: turn it on once to get the touch strip back.
- **A Bose button left on Spotify Tap did nothing** while Earshot was connected; on connect it now
  gets what the default action needs. Immersive Audio and Off set in the Bose app are kept.
- The connection search no longer mistakes the headset's call channel for "the Bose app is holding
  the control channel"; it closes that channel and keeps looking.

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
