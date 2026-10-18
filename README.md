# Earshot

**A faster, more capable, account-free Android app for Bose QuietComfort and Ultra headphones and
earbuds.** Built around the QC Ultra Headphones (2nd Gen), it adapts to whichever model you connect:
QC Ultra Headphones and Earbuds (1st and 2nd Gen), QC Headphones (1st and 2nd Gen), QC SC, QC45,
QC SE, QC Earbuds II, Ultra Open Earbuds (1st and 2nd Gen), basic support for the QC Earbuds (2020)
and Noise Cancelling Headphones 700, and, experimentally, the Skullcandy Crusher 1080 ANC
([see what works on each](#supported-headphones)).

Earshot talks to the headphones directly over Bluetooth using Bose's own control protocol (BMAP),
the same channel the official app uses. No Bose account, no cloud, no analytics, **no internet
permission at all**. On top of everything the Bose app does for day-to-day control, it adds a
pro equaliser with volume boost, a Bluetooth codec panel, LE Audio and latency controls, louder
calls, a Quick Settings tile, a widget and automations.

> Not affiliated with or endorsed by Bose. Bose and QuietComfort are trademarks of Bose Corporation.
> Built and tested on the QC Ultra Headphones (2nd Gen, firmware 10.x). What's new: [CHANGELOG.md](CHANGELOG.md).

<p align="center">
  <img src="docs/screenshots/01-home.png" width="23%" alt="Home: battery, modes, noise control, now playing">
  <img src="docs/screenshots/03-sound-proeq.png" width="23%" alt="Sound: Pro EQ with the QC Ultra correction preset">
  <img src="docs/screenshots/05-connection.png" width="23%" alt="Connection: LE Audio, codec, latency">
  <img src="docs/screenshots/06-call-audio.png" width="23%" alt="Call audio: automatic call volume boost">
</p>
<p align="center">
  <img src="docs/screenshots/02-sound.png" width="23%" alt="Sound: the headphones' own 3-band EQ">
  <img src="docs/screenshots/04-tools.png" width="23%" alt="Tools">
  <img src="docs/screenshots/07-more.png" width="23%" alt="More: sidetone, auto-pause, multipoint, touch controls">
  <img src="docs/screenshots/08-more-dark.png" width="23%" alt="Dark theme">
</p>

### New in 0.4.0: it adapts to your Bose model

<p align="center">
  <img src="docs/screenshots/09-earbuds.png" width="23%" alt="QC Ultra Earbuds: model and generation, left, right and case battery, the earbuds' own modes">
  <img src="docs/screenshots/10-basic-model.png" width="23%" alt="Noise Cancelling Headphones 700: basic support, no modes, noise level slider">
  <img src="docs/screenshots/11-whats-new.png" width="23%" alt="What's new in 0.4.0">
</p>

The header shows your headphones' name, model and generation; earbuds get left, right and case
battery with listening time left; Home, Modes and Settings show only what your model has; tap the
name to switch between your Bose products. LE Audio earbuds now connect too.

## Why it's better than the stock Bose app

| | Bose app | Earshot |
|---|---|---|
| Account / cloud | Required; settings sync through Bose servers | None. Nothing leaves the phone (no `INTERNET` permission) |
| Startup | Splash, sign-in checks, slow | Connected and showing live state in about a second |
| Live state | Polls | Headphones **push** button presses, mode, noise, battery and volume changes instantly |
| Equaliser | 3 bands on the headphones | Those 3 bands **plus** a 10-band graphic / parametric Pro EQ, presets, AutoEq import and a measured **"Tuned for QC Ultra"** correction curve |
| Volume | Phone limits | **Volume boost** up to +12 dB with a brick-wall limiter and hearing-safety guard |
| Calls | Often too quiet on Android | **Automatic in-call volume boost**, call-codec readout (CVSD / mSBC / LC3) |
| Codec | Hidden in Developer options | Live codec panel; **switch AAC ↔ SBC** from the app (via Shizuku, no root) |
| LE Audio | Buried per-device toggle | LE Audio on/off with status, phone support check, a "try anyway" option and a result check; per-device LE vs Classic view. Earbuds on LE Audio are controlled over LE |
| Several Bose products | Pick from a list each time | **Quick switcher**: tap the name on Home |
| Earbuds battery | Per bud in a sub-screen | Left, right and case on Home, plus **listening time left** |
| Latency | Not shown | Reported latency, low-latency tips, experimental latency control |
| Per-app sound | No | Different EQ per app (e.g. music vs. video) |
| Quick access | Open the app | **Quick Settings tile**, **home-screen widget**, persistent notification with mode buttons |
| Automation | No | Mode or EQ by **Wi-Fi network, app in front, schedule, or on connect**; low-battery alert |
| Transparency | Hidden | Read-only **Lab** explorer and full protocol documentation in this repo |

Everything the Bose app does for daily use is here too: your model's listening modes (for example
Quiet / Aware / Immersion / Cinema + 7 custom slots on the QC Ultra 2, favourites), noise-cancelling
level, Wind Block, Immersive Audio, Self Voice (sidetone), auto play/pause, auto-answer, auto-off
timer, voice prompts, multipoint and device switching, pairing mode, shortcut-button action, touch
controls, battery, power off.

**What stays with the Bose app, on purpose:** firmware updates (not worth the risk of a bad flash
from a third-party app). Things a model's firmware doesn't allow third parties to write (renaming,
editing the built-in modes, the auto-off timer on some firmware) are shown as such instead of
silently failing.

## Supported headphones

Earshot reads the model from the headphones when it connects and, like the Bose app, takes most of
what it shows from what the headphones report: how many built-in and custom modes they have, which
of Wind Block, Immersive Audio and the ANC switch exist, the noise-level steps, the batteries and
which settings answer. A product table only supplies names, form factor and first guesses. Per-model
research: [docs/bose-models.md](docs/bose-models.md).

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
| **Skullcandy Crusher 1080 ANC** | **Experimental, never run on real headphones** | Quiet / Aware / Off, with a gain slider | A different protocol (Bragi BOS-RPC, not Bose BMAP): Immersive Audio (Still / Motion), sidetone and the **Crusher bass** card. See [docs/crusher-1080.md](docs/crusher-1080.md) |

**Not supported:** QuietComfort 35 and 35 II (Bose Connect era; Earshot recognises them and shows
the battery only) and the 2024 QuietComfort Earbuds (a different protocol and a separate Bose app).

**Noise control, "via the active custom mode":** some headphones have no live noise setting, so
Earshot saves the slider and Wind Block into the custom mode you are using (as the Bose app does).
On the built-in Quiet / Aware modes, switch to a custom mode first.

Reports from owners of the unverified models are very welcome: open an issue with what works and
what doesn't (Tools, Lab, **Copy connection report** helps most). Demo mode can show how each model
looks without the headphones (App preferences, Demo mode).

## Requirements

| | |
|---|---|
| Android | **9.0 (API 28) or newer**. Developed and tested on Android 17 / One UI 9 (a Samsung Galaxy); also exercised on Android 16–17 emulators. |
| CPU | Any: arm64-v8a, armeabi-v7a, x86_64. The APK contains no native code, so one ~5 MB file fits every phone. |
| Bluetooth | Classic Bluetooth (RFCOMM) for control, or an LE channel when the phone is on LE Audio (Android 10+). LE Audio features need a phone with LE Audio support (Android 13+). |
| Headphones | One of the [supported models](#supported-headphones), paired in Android's Bluetooth settings. QC Ultra (2nd Gen) firmware 10.x tested. |
| Bose app | **Closed or disabled** while Earshot is in use: only one app can hold the headphones' control channel and the Bose app grabs it in the background. Keep it for firmware updates. |
| Optional | [Shizuku](https://shizuku.rikka.app/) (free, no root; start it via Wireless debugging on Android 11+) unlocks codec switching and the absolute-volume option. Everything else works without it. |
| Permissions | Nearby devices (Bluetooth), notifications; microphone only for the mic check; location only if you create a Wi-Fi automation; usage access only for app-based automations. **No internet permission.** |

## Install

Download `earshot-<version>.apk` from the [Releases](../../releases) page and open it on the phone
(allow "install unknown apps" for your browser or file manager once). The release APK is signed with
a development key: Android will warn that the developer is unknown, which is expected for a
sideloaded open-source app.

Or build it yourself:

```bash
git clone https://github.com/HellzYeahh/earshot.git
cd earshot
./gradlew assembleDebug        # Windows: .\gw.cmd assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

No headphones at hand? Tap **Try demo mode** on the welcome screen: virtual Bose headphones and
earbuds built into the app answer the real protocol, so every screen works. Pick the model in App
preferences (QC Ultra, QC Ultra Earbuds, QC Earbuds II, QC Earbuds, QC45, NC 700, Ultra Open,
QC35 II, Crusher).

## How it works

- `protocol/` — pure-Kotlin implementation of BMAP (framing, the QC Ultra 2 function catalogue,
  parsers/builders), unit-tested against captured frames from real headphones. `BoseModel.kt` holds
  the product table (names, form factor, first-guess channel and layout); the rest is read from the
  headphones at connect time.
- `app/` — Kotlin + Jetpack Compose. Bluetooth RFCOMM and LE L2CAP transports with auto-reconnect, a foreground
  service, the sound engine (`DynamicsProcessing`), codec control through a Shizuku user service,
  call audio, widgets, tile, automations.
- `docs/bmap-from-bose-app-13.3.4.md` — the full BMAP function table and payload layouts, derived
  from the official app and verified against a scan of real hardware. Useful for anyone building
  Bose tooling.

## Honest limitations

- **LDAC / aptX Adaptive can't be added**: a codec needs a decoder inside the headphones. The QC
  Ultra 2 ships SBC, AAC and aptX Adaptive; Samsung phones don't offer aptX Adaptive, so the pair
  negotiates AAC or SBC.
- **No microphone EQ**: Android doesn't let apps process call audio and the firmware has no mic
  processing setting. What improves how you sound is a wideband call codec (mSBC / LC3 via LE Audio).
- Pro EQ attaches to apps that announce their audio session (Spotify, YouTube Music, most players);
  an experimental global option covers the rest on phones that allow it.
- LE Audio reliability is the firmware's; Earshot makes the switch deterministic and shows the state.

## Privacy & safety

No network access, no accounts, no analytics. The Lab explorer only reads. Volume boost is capped
and limited; the hearing-safety warning is deliberate. Codec switching and the absolute-volume
option run through Shizuku with the shell user's permissions and change only Bluetooth audio
settings; both are reversible from the app or Developer options.

## Credits

Protocol knowledge builds on [bosectl](https://github.com/aaronsb/bosectl) and
[Quiet Rebellion](https://github.com/MadEyez/quietrebellion) (both MIT) and on the headphone
correction curve from [AutoEq](https://github.com/jaakkopasanen/AutoEq) (MIT). Thank you.

## Licence

MIT. See `LICENSE`.
