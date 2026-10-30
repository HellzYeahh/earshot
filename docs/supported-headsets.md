# Supported headsets

What Earshot does with each brand and model, and what it needs. The short version is in the
[README](../README.md); everything that changed per release is in the [CHANGELOG](../CHANGELOG.md).

## Tiers

Earshot never opens a control channel blind. It identifies the headset first (by name, by what it
announces, and by the service ID it publishes) and then talks only that brand's protocol, on that
brand's own service. Anything it doesn't recognise is an [any headset](#any-headset).

| Tier | What it means | Who |
|---|---|---|
| **Tested** | Run on our own hardware | Bose QC Ultra Headphones (2nd Gen); Soundcore Sleep A30 (one pair) |
| **Experimental** | Built from published protocol research and a virtual headset, **not yet tried on real hardware**. Earshot reads the state before it writes and checks the result where it can | The other Bose models, Soundcore, Sennheiser, Sony, Skullcandy, JBL |
| **Battery only** | Left, right and case battery from the Bluetooth adverts | AirPods, Beats |
| **Any headset** | The Android-level features, nothing sent to the headset | Everything else |

> **Experimental means untested on real hardware. Reports are very welcome:** open an issue with
> what works and what doesn't. Tools → Lab → **Copy connection report** helps most. Demo mode
> (App preferences → Demo mode) shows how each model looks without the headset.

## Bose

Earshot reads the model from the headphones when it connects and, like the Bose app, takes most of
what it shows from what the headphones report: how many built-in and custom modes they have, which
of Wind Block, Immersive Audio and the ANC switch exist, the noise-level steps, the batteries and
which settings answer. A product table only supplies names, form factor and first guesses.
Per-model research: [bose-models.md](bose-models.md).

Everything the Bose app does for daily use is there: your model's listening modes (for example
Quiet / Aware / Immersion / Cinema + 7 custom slots on the QC Ultra 2, favourites), noise-cancelling
level, Wind Block, Immersive Audio, Self Voice (sidetone), auto play/pause, auto-answer, auto-off
timer, voice prompts, multipoint and device switching, pairing mode, shortcut-button action, touch
controls, battery, power off. On top come LE Audio on/off with a result check, latency controls
and the headphones' own 3-band EQ next to the Pro EQ.

| Model | Status | Noise control | Notes |
|---|---|---|---|
| QC Ultra Headphones (2nd Gen) | **Tested** on our hardware | Live | Everything above |
| QC Ultra Headphones (1st Gen) | Reported working by a user (0.3.1) | Live | Built-in Quiet / Aware / Immersion, Wind Block, up to 7 custom modes |
| QC Ultra Earbuds (2nd Gen) | Layout verified by bosectl | Live | Left / right / case battery; Quiet / Aware / Immersion / Cinema |
| QC Ultra Earbuds (1st Gen) | Best guess, checked while connecting | Live, or via the active custom mode | LE Audio connection fixed in 0.4.0 (user report) |
| QC Headphones (2nd Gen) | Best guess, checked while connecting | Live | Quiet / Aware / Immersion / Cinema, Wind Block, ANC off |
| QC Headphones (1st Gen), QC SC | Layout verified by [bosectl](https://github.com/aaronsb/bosectl) | Via the active custom mode | No ANC on/off, no Immersive Audio; built-in Quiet / Aware. Control channel 8 or 9: both are tried |
| QC45, QC SE | Layout verified by bosectl | Via the active custom mode | No ANC on/off, no Immersive Audio; Wind Block sets the level to 0 |
| QC Earbuds II | Best guess, checked while connecting | Via the active custom mode | Quiet / Aware + 2 custom modes; left / right / case battery |
| Ultra Open Earbuds (1st and 2nd Gen) | Partial, unverified | None (open-ear) | Stereo / Immersion (+ Cinema on the 2nd Gen); battery, EQ, settings |
| QC Earbuds (2020) | **Basic**, layout verified by bosectl | Direct level (0–10) | Battery, EQ, settings, noise level, mode switching; saving custom modes untested |
| Noise Cancelling Headphones 700 | **Basic**, best guess | Direct level (0–10) | Battery, EQ, settings and noise level; no listening modes |

**Noise control, "via the active custom mode":** some headphones have no live noise setting, so
Earshot saves the slider and Wind Block into the custom mode you are using (as the Bose app does).
On the built-in Quiet / Aware modes, switch to a custom mode first.

Only the QC Ultra Headphones (2nd Gen) is tested on our hardware, so the other Bose rows are
"experimental" in the sense above, even where a reference project verified the layout.

## Soundcore (Anker)

Experimental, apart from the Sleep A30. Earshot recognises 35 models on Soundcore's own Bluetooth
service (Liberty 3 Pro / 4 / 4 NC / 4 Pro / 5, P31i, P40i, R50i NC, A30i, R60i NC, Space A40 / One /
One Pro / Q45 / 2, Sport X20, Sleep A20 and more). It writes only after the state it read matches
the layout it knows, and reads each change back.

| Models | Status | What works |
|---|---|---|
| Sleep A30 | **Tested** on one pair | Noise cancelling switch, listening mode (Bluetooth or the earbuds' own Local sounds), the mode they start in, switch once asleep, call and prompt settings, tap controls, per-bud battery, firmware |
| Liberty 3 Pro, Liberty 4 NC, Liberty 5, P31i, P40i, R50i NC, R60i NC, A30i, Space A40, Space One, Space One Pro, Space Q45 | Experimental | Battery (per bud and case), mode (noise cancelling, transparency, normal), noise-cancelling type and level, wind reduction, and the settings their layout documents |
| Space 2, Liberty 4, Liberty 4 Pro | Experimental, **read-only** until their layouts are confirmed | Battery, charging, firmware, sound mode |
| The other recognised models | Experimental, read-only | Battery (per bud and case), charging, firmware and, where the layout is known, the sound mode |

## Sennheiser

Experimental, untested on real hardware, over Sennheiser's own Qualcomm GAIA service.

| Models | What works |
|---|---|
| MOMENTUM 4, MOMENTUM 5, ACCENTUM, ACCENTUM Plus, ACCENTUM TW, MOMENTUM True Wireless 3 and 4, MOMENTUM Sport | Noise cancelling / transparency / off, the noise ↔ transparency slider (MOMENTUM 4 / 5, ACCENTUM Plus), adaptive noise cancelling, anti-wind and comfort, the earbuds' transparency level. Battery, EQ on current firmware with bass boost, auto-pause, on-head detection, auto-answer, comfort call, sidetone, auto power-off |

The CX Plus TW and CX 500BT (Bluetooth LE only) stay generic.

## Sony

Experimental, untested on real hardware, over Sony's own protocol (MDR v2).

| Models | What works |
|---|---|
| WH-1000XM5 / XM6, WF-1000XM4 / XM5 / XM6, LinkBuds, ULT WEAR, WH-CH720N, WF-C510 / C700N / C710N | Noise cancelling / Ambient sound / Off, the ambient level and focus on voice. Battery (headband, each earbud, case), the headphones' own EQ presets and bands, auto power off, speak-to-chat, pause when taken off, DSEE, connection priority, voice guidance, and the settings the headset describes itself (multipoint, touch controls...) |

Features come from what the headset announces, not from its name. The WH-1000XM4 (an older
protocol) stays generic.

## JBL

Experimental, untested on real hardware, over JBL's Harman protocol ("AA") on Bluetooth LE. Built
from protocol notes and jbl-control's captures of a Wave Buds 2 (MIT).

| Models | What works |
|---|---|
| The 2021–24 models: Wave / Vibe / Tune Buds 2, Flex 2, Beam 2, Tune Flex / Beam / Buds, Tune 130NC / 230NC / 235NC TWS, Live Pro 2, Live Flex / Beam 3 / Flex 3, Live 460NC / 660NC / 670NC / 675NC / 770NC / 775NC, Tour One / One M2, Tour Pro+ / Pro 2, Live Pro+, Reflect Flow Pro, Tune 245NC / 255NC, Junior 470NC, UA Project Rock | Noise cancelling / Ambient Aware / TalkThru / Off (the modes the headset lists), the noise-cancelling level on models that report it, JBL's EQ presets, voice prompts, battery (each earbud and the case, or the headband) |

Earshot reaches the control service on the headset's LE side (on earbuds a separate `-LE`
address, found by a short nearby scan), checks the product ID the headset reports and refuses
JBL's 2025+ models (a newer protocol) and its Airoha-based models (Tune 670 / 770NC, Live Buds 3,
Tour Pro 3 and others): those stay generic. Factory reset, power off and firmware transfer are
never sent.

## Skullcandy

Experimental, **never run on real headphones.** The Skullcandy support comes from an
interoperability analysis of the official app plus a virtual device; no open implementation exists.

| Models | What works |
|---|---|
| Crusher 1080 ANC | A different protocol (Bragi BOS-RPC, not Bose BMAP): Quiet / Aware / Off with a gain slider, Immersive Audio (Still / Motion), sidetone, battery and the **Crusher bass** card. See [crusher-1080.md](crusher-1080.md) |
| Method 360 ANC, Method 540 ANC | The same connection: Quiet / Aware / Off, the noise level, sidetone and battery, without the Crusher-only cards |

## AirPods and Beats (battery only)

Left, right and case battery and charging, read from the Bluetooth adverts they broadcast (needs
"Nearby devices"). No connection, nothing sent. Listening modes are not supported: they need a
Bluetooth-stack workaround that Google can close, and apps built for that, such as LibrePods,
already do it.

## Any headset

Every other Bluetooth headset (Samsung, JBL's 2025+ and Airoha models, Soundcore models Earshot doesn't know, no-name
earbuds...) is shown by its own name. Earshot never changes its settings: it only reads its battery, and the universal remote can connect, disconnect, rename or forget it on this phone.
What works through Android itself:

- **Battery**, as the headset reports it to Android; straight away on LE-capable headsets
  (standard Battery Service) and, with Shizuku, from the first moment.
- **Pro EQ** with a preset remembered per headset, applied when that headset connects.
- **Codec panel**: the current codec shows from the next codec change Android announces; switching
  needs Shizuku.
- **Louder calls**, the call-codec readout and the **mic check**.
- **Controls**: remap the press-and-hold, and **Detect a button**.
- **Automations**, the Quick Settings tile and the widget.
- **Universal remote**: connect, disconnect, rename and forget (see
  [Requirements](#requirements) for the Android versions).
- The headset's own settings (noise modes, its EQ, touch controls) need a driver for its protocol.
  Screens hide the settings only a driver could change; if you know better, set
  **Headphone type** for that device.

**Not supported:** Bose QuietComfort 35 and 35 II (Bose Connect era; Earshot recognises them and
shows the battery only), the 2024 Bose QuietComfort Earbuds (a different protocol and a separate
Bose app), and JBL's 2025+ models (Protocol 4) and Airoha-based models: they stay generic.

## Requirements

| | |
|---|---|
| Android | **9.0 (API 28) or newer**. Developed and tested on Android 17 / One UI 9 (a Samsung Galaxy); also exercised on Android 16–17 emulators. Some universal-remote rows need a newer Android: **connect and disconnect need Android 17, forget needs Android 16, rename needs Android 13**. The row says so when your Android can't do it. |
| CPU | Any: arm64-v8a, armeabi-v7a, x86_64. The APK contains no native code, so one APK fits every phone. |
| Bluetooth | Classic Bluetooth (RFCOMM) for control, or an LE channel when the phone is on LE Audio (Android 10+); JBL over Bluetooth LE (GATT). LE Audio features need a phone with LE Audio support (Android 13+). |
| Headphones | Any Bluetooth headset, paired in Android's Bluetooth settings. For their own controls, one of the [supported models](#tiers). |
| The maker's app | **Closed or disabled** while Earshot controls that headset: only one app can hold a headset's control channel, and the maker's app grabs it in the background. Music and calls keep working meanwhile. Keep the app for firmware updates. Not needed for an [any headset](#any-headset). |
| Optional | [Shizuku](https://shizuku.rikka.app/) (free, no root; start it via Wireless debugging on Android 11+) unlocks codec switching, the absolute-volume option and a battery value from the first moment. Everything else works without it. |
| Permissions | Nearby devices (Bluetooth), notifications; microphone only for the mic check and headset voice memos; location only if you create a Wi-Fi automation; usage access only for app-based automations. The universal remote asks for one Android dialog per headset (Companion Device Manager). **No internet permission.** |

## Limitations

- **Experimental drivers can be wrong.** Sennheiser, Sony, JBL, most Soundcore models, the Skullcandy
  headsets and every Bose model except the QC Ultra 2 are built from references and virtual
  devices. They read the state first and check it before they write, but a firmware revision
  can differ. Please report what you see.
- **Firmware updates stay with the maker's app.** So does anything that needs the maker's servers.
- **One app at a time** can hold a headset's control channel: close the maker's app while Earshot
  controls that headset (and the other way round).
- **Universal remote depends on your Android:** connect and disconnect need Android 17, forget needs
  Android 16, rename needs Android 13. Results on other makers' Bluetooth stacks can differ.
- **Not every setting of every headset** is covered, and AirPods listening modes and JBL's newest
  and Airoha-based models are not supported (see above).
- **LDAC / aptX Adaptive can't be added**: a codec needs a decoder inside the headphones. The QC
  Ultra 2 ships SBC, AAC and aptX Adaptive; Samsung phones don't offer aptX Adaptive, so the pair
  negotiates AAC or SBC.
- **No microphone EQ**: Android doesn't let apps process call audio and the firmware has no mic
  processing setting. What improves how you sound is a wideband call codec (mSBC / LC3 via LE Audio).
- Pro EQ attaches to apps that announce their audio session (Spotify, YouTube Music, most players);
  an experimental global option covers the rest on phones that allow it. Another system-wide
  equaliser app can compete for the same Android effect: use one at a time.
- LE Audio reliability is the firmware's; Earshot makes the switch deterministic and shows the state.

