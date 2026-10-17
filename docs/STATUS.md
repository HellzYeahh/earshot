# Earshot — status (morning of 2026-10-17, written 07:45)

The build on your phone right now is the QA-passed one from 05:04 (commit 873095d); it connected,
subscribed to live push and bound the codec service on install.

## What changed overnight (17 Oct, 04:00–08:00)
- **Codec switching works on your phone.** The crash you hit after enabling Shizuku came from a
  hidden-API library that breaks on Android 17; it is gone. Codec read/switch now runs inside a
  Shizuku *user service* (shell privileges, no bypass tricks). Verified live: "SBC active after
  265 ms". Your pair only negotiates **AAC or SBC** (no LDAC decoder in the headphones; Samsung
  has no aptX Adaptive).
- **The Bose app's full protocol table is now ours.** `docs/bmap-from-bose-app-13.3.4.md` (+ JSON
  tables) was derived from the Bose app and cross-checked with a full scan of your headphones
  (`docs/reference/captures-galaxy/deep-scan-fw10.12-2026-10-17.txt`). Key facts: nothing is
  locked behind Bose's login; the headphones can **push** changes (`[9.2]`), which is now on, so
  button presses, mode/ANC/battery/volume changes show instantly.
- **New controls** (all in the app, verified readable on your firmware; writes marked below):
  - Tools → **Connection & latency**: LE Audio on/off (`[1.37]`, headphones restart), LE/Classic
    per device, reported latency (220 ms, range 0–520) with an *experimental* latency setting
    (`[5.7]`, untested write), low-latency tips.
  - Tools → **Call audio**: automatic in-call volume boost (call stream + headset volume), call
    codec readout (CVSD / mSBC / LC3), optional "absolute volume off" via Shizuku. Honest note:
    apps cannot process the call microphone and this firmware has no mic EQ; a wideband/LC3 call
    codec (LE Audio) is what improves how you sound.
  - Home: now-playing row (play/pause/next/previous, headset volume slider), status chips
    (mic muted, charging, live updates), shortcut-button press toasts.
  - More: Touch controls on/off; Edit modes: Reset slot; Devices: connected / this phone / Bose
    badge / LE vs Classic profiles.
  - Lab: Deep scan (all 64 blocks, auto-saved file) and an experimental Print console.
- Wording fixed: "Bose locks this behind its login" was wrong; it now says the firmware doesn't
  support changing that setting (true for renaming on your firmware).

## Verified on the real headphones so far
Connect (channel 2, liveness probe), full read incl. your custom modes, live
push subscription accepted, LE Audio check answered, codec read + **switch to SBC**, noise slider
and Wind Block writes (echoed correctly), Shizuku user service start on One UI 9.

## Not yet tried on the real headphones (first things to do today)
1. **Mode taps** on Home (Quiet → Aware → a custom mode): never exercised; just tap and listen.
2. LE Audio toggle (Connection screen) — expect a restart and ~15 s reconnect.
3. Volume slider / transport buttons on Home while music plays.
4. Touch controls switch, Reset slot, Self Voice levels, EQ.
5. A real phone call with the headphones: watch Tools → Call audio (volume meter, codec chip) and
   `adb logcat -s Earshot/CallAudio`.
6. Experimental latency write (`[5.7]`): try a small change, listen for lip-sync change, reset.

## Open polish items (from QA pass 2, not yet done — small)
1. Demo mode doesn't yet emit button/charger/volume pushes by itself.
2. An "On connect" automation re-fires after the LE Audio restart or a power-off reconnect.
3. Lab Print console field hides behind the keyboard; placeholder duplicates the caption.
4. Latency slider: the promised grey "original" marker isn't drawn (caption only).
5. Codec screen: empty "What both support" card has no empty-state text; duplicate arrow glyph.
6. Home shows an empty mode row for ~4 s on first load instead of "Reading settings…".
7. Wording: "Reset slot N" snackbar; LE Audio off-dialog reuses the on-text; Devices chip wraps;
   automation log entries show time without date.

## Known caveats
- The **Bose app must stay uninstalled or disabled** while testing: it grabs the control channel in
  the background and the headphones then answer nothing (Earshot now detects this and shows
  "Close the Bose app, then try again").
- Shizuku must be restarted after a phone reboot (wireless debugging start).
- Another Claude session ("Bose updater installer analysis") shares this folder; its work is on
  branch `overnight/bose-updater-findings`.

See `PHONE_TEST_CHECKLIST.md` for the full list; `install.ps1` builds and installs.
