# Reliability targets (1.0 plan item 3)

What "works reliably" means for 1.0, measured on real hardware with
`tools/reliability/measure.py` (adb; see its docstring). The numbers are the 1.0 targets; the
table records each measurement with its date, build and phone.

## Targets

| What | Target | How |
|---|---|---|
| Connect, learned channel: socket → first BMAP answer | ≤ 3 s median, ≤ 6 s worst | `measure.py connect` after a cold start |
| Connect success over repeated restarts | ≥ 19 of 20 | `measure.py cycles --runs 20` (drops the headset's control link each time) |
| Cold start to first frame | ≤ 1.5 s median | `measure.py startup` (phone unlocked) |
| Earshot's own battery use with the connection service on | ≤ 1 % of the battery a day | `measure.py battery` after a normal day |
| Background survival | the service is still there after a day with the screen off | `measure.py alive` the next morning |
| An overnight Sleep night | sounds on the headset only; the alarm rings within 60 s of its time (earbuds, then the phone after 60 s if nobody stops it); every night change put back in the morning | `docs/PHONE_TEST_CHECKLIST.md` §5c, one full night |

## Measurements

| Date | Build | Phone / headset | Result |
|---|---|---|---|
| 2026-10-29 | 0.9.0 + 1.0 prep (playDebug, `27b19ae`) | Galaxy Z Fold7, Android 17 / QC Ultra 2 fw 10.12.14 | Connect after a cold start: socket 1.45 s, first answer **2.03 s** (learned ch2, first of 6 candidates). Battery: **2.50 mAh in 2 h 28 min on battery** (foreground service 1.25 mAh over 1 h 54 min), about 0.6 % a day; 120 short wake locks, 1.2 s in total. Process and its foreground service running. |
| 2026-10-30 | 0.9.0 + 1.0 prep, **targetSdk 37** (playDebug) | Fold7, Android 17 / QC Ultra 2 fw 10.12.14 | **Cold start, phone unlocked:** **458 ms** and **425 ms**. **Connect** after a cold start: first answer in **3.50 s** on ch2. **Link drop** (headphones switched off): logged as `RFCOMM end of stream`; they reconnected by themselves when switched back on, first answer in **2.6 s**. With the headphones on but not connected for audio, every classic channel failed and Earshot reached them over **LE CoC (encrypted)**; that full walk took 26.9 s. **Sleep:** rain from the tab kept playing with the screen off, and the alarm started at exactly its minute; no Android 17 audio-hardening refusal. No crashes. |

Still to measure:
- 20 connect cycles (`measure.py cycles --runs 20`);
- a full day's battery at target 37;
- one complete overnight Sleep night.

The connect median is to be re-checked against the 3 s target with the cycles: 3.50 s once, 2.03 s and 2.6 s otherwise.
