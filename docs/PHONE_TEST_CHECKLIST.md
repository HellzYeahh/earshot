# Earshot — phone test checklist

How to install: plug the Galaxy in (USB debugging on) or keep Wireless debugging paired, then run
`.\install.ps1` from the project folder. Before connecting, **force-stop the Bose app**
(Settings → Apps → Bose → Force stop): only one app can hold the headphones' control channel.

Overnight (2026-10-16) the app was installed on the Galaxy and connected to the real headphones on
the first try, reading battery, modes, EQ, sidetone and more. Nothing was written to the headphones.
Everything below that *changes* a setting is still unverified on real hardware.

Tick what works; note anything odd (wrong value, delay, voice prompt, disconnect).

## 1. Connection
- [ ] App opens to Home with battery %, device name, "Connected" (within ~5 s of opening).
- [ ] Persistent notification shows battery and mode with mode buttons.
- [ ] Turn headphones off → notification/Home show disconnected; on again → reconnects by itself.
- [ ] Open the Bose app while connected, then force-stop it → Earshot shows "Close the Bose app…" and later reconnects.
- [ ] Quick Settings tile added (Edit tiles → Headphone mode); tap cycles modes; long-press opens the app.
- [ ] Home-screen widget shows battery and mode; its mode buttons work; Connect button when disconnected.

## 2. Listening modes & noise (Home)
- [ ] Tap Quiet / Aware / Immersion / Cinema: headphones switch (listen), chip highlights, no voice prompt.
- [ ] Noise-cancelling slider 0→10 in Quiet mode: audible difference (10 = most cancelling).
- [ ] Wind Block on/off; ANC switch off → slider greys out with the hint.
- [ ] Immersive Audio Off / Still / Motion.
- [ ] Change the level on a preset → the mode highlight clears (unsaved settings), as in the Bose app.
- [ ] Long-press a mode → favourite star toggles; notification/tile use favourites.
- [ ] Edit modes → set up slot 5 with a name + settings → it appears in the carousel and switches.

## 3. Sound
- [ ] Headphone EQ sliders (bass/mid/treble) change the sound; values survive reopening the app.
- [ ] Pro EQ: enable, play music from Spotify/YouTube Music → "Active on 1 audio session(s)"; move a band.
- [ ] Switch phone output to speaker → Pro EQ detaches (no EQ on the speaker); back to headphones → reattaches.
- [ ] "Tuned for QC Ultra" preset sounds flatter/less bassy than Flat.
- [ ] Volume boost: +6 dB audibly louder; limiter on → no crackle on a loud track; limiter off → slider caps at +3 dB.
- [ ] Save a preset, delete it, import an AutoEq paste.
- [ ] Per-app profile: Spotify → Bass boost, YouTube → Vocal; switching apps switches presets.

## 4. Tools
- [ ] Codec: without Shizuku → shows the privilege banner and a Developer-options button.
- [ ] Install Shizuku (Play Store) → start via Wireless debugging → tap Grant in Earshot → current codec shows AAC 44.1 kHz.
- [ ] "Most stable" → SBC; "Best quality" → AAC (this Galaxy + QC Ultra 2 only negotiate AAC/SBC).
- [ ] Mic check: Record (grant mic) → level meter moves → Play: you hear yourself; banner says headset mic.
- [ ] Lab: scan blocks 0, 1, 2, 31 → results with names; copy to clipboard works. (Read-only.)

## 5. More
- [ ] Sidetone Off/Low/Medium/High audible on a call or voice memo.
- [ ] Auto play/pause, auto-answer.
- [ ] Tools → Headphone controls: voice prompts on/off + language.
- [ ] Tools → Devices: paired list, active source badge; "Switch to this device" with playback started on the other device.
- [ ] Pairing mode button → headphones announce pairing.
- [ ] Tools → Shortcuts remapper: pick Cycle EQ → the Bose Shortcut button goes to Voice assistant by itself; pick Immersive Audio / Off → the button does that (may be refused by firmware — note the message).
- [ ] Tools → Rename → expected message that the firmware doesn't support changing it.
- [ ] Tools → Power off → headphones turn off, app shows disconnected.
- [ ] Automations: On connect → Quiet; Wi-Fi "home" → Aware (grant location); Schedule 2 min ahead; Foreground app (grant Usage access).
- [ ] Low-battery alert threshold 90% (for testing) → alert appears once.
- [ ] About: credits present, version shown.

## 5b. Added 17 Oct (Connection, Call audio, playback)
- [ ] Tools → Connection: LE Audio status correct; toggle it → warning → headphones restart → app reconnects by itself; toggle back.
- [ ] Connection: reported latency shows (~220 ms); experimental slider: small change → any audible lip-sync change? → Reset to original.
- [ ] Home now-playing row: play/pause/next/previous work while Spotify plays; volume slider moves the headset volume (and follows the headphone buttons).
- [ ] Home chips: "Live updates" shown; "Charging" appears on USB; press the shortcut button on the headphones → a toast names the press.
- [ ] Mode/ANC/battery changes made with the headphone buttons appear in the app within a second (live push).
- [ ] Tools → Touch controls off → the touch strip stops responding; on again.
- [ ] Edit modes → Reset slot on a custom slot → it becomes empty.
- [ ] Call audio: during a real call, the live card shows the route, volume meter and codec; call volume gets louder automatically; "Boost now" works; last-call codec stays visible afterwards.
- [ ] Call audio → Advanced: absolute volume off (Shizuku) → reconnect → louder call volume? Revert if not.
- [ ] Codec: switch SBC ↔ AAC; "Best quality"/"Most stable".
- [ ] Lab → Print console: `output 3` returns something or a clean error.

## 6. Robustness
- [ ] Rotate / fold-unfold the phone on each tab: no crash, state kept, snackbars still appear.
- [ ] Revoke Nearby devices permission while connected → app asks again, no crash.
- [ ] Reboot the phone → notification/tile/widget come back after opening the app once; schedules still fire.

## Collecting a log for a bug
```
adb logcat -v time Earshot/Bt:V Earshot/Protocol:V Earshot/Codec:V Earshot/Tuning:V Earshot/A2dpBinder:V AndroidRuntime:E *:S > earshot.log
```
Reproduce, stop with Ctrl+C, and share `earshot.log`.
