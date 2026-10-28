# Sleep tab (0.9): design and build plan

Planned 2026-10-26 and built the same day (phases 1–5 except the launcher quick start; phase 0's
phone checks were skipped at the user's request and moved to the end, see
`docs/PHONE_TEST_CHECKLIST.md` §5c). Nothing has run on a phone yet. The user asked for a Sleep
tab like the Soundcore Sleep app (Sleep A20/A30) and BetterSleep, working with **any** connected
headset. This file is the handoff: §11 says where the code is and what is left.

## 1. What the user asked for

- A new **Sleep** tab (a fifth bottom-bar tab).
- **Sleep timer, 1 to 6 hours.** When it ends the headset stops playing and goes to sleep: turned
  off, disconnected or left idle, whichever the headset allows.
- **Noise cancelling off during the timer** (option): switched off automatically if it is on.
- **Sleep sounds:** rain, white noise, brown noise, fire crackling (Soundcore's audio library,
  BetterSleep).
- **Alarm:** a wake-up time that rings **in the earbuds, on the phone, or both**.
- Layout reference: the user's screenshots of BetterSleep's alarm screen (time and switch, wake-up
  window, sound, fade in, snooze) and the Soundcore Sleep A20 device page (battery L/R/case, audio
  library, reminder and alarm, sleep timer, sleep mode, a mini player at the bottom).

## 2. The shape in one paragraph

Everything runs on the phone; headset commands are extras the driver may or may not offer.
Pressing **Start** (from the visible Sleep tab) starts a new `mediaPlayback` foreground service,
`sleep.SleepService`, that plays sounds synthesised in `:dsp` (no audio files, no licences, no APK
growth) through an `AudioTrack` routed to the headset. The night's moments (noise cancelling off,
fade out, end action) are `AlarmManager` alarms, because coroutine `delay` stops counting while the
CPU sleeps. The alarm is `AlarmManager.setAlarmClock`, which wakes a receiver that restarts the
service in alarm mode and opens a lock-screen alarm screen. The alarm sound goes to the earbuds,
the phone speaker or both with `setPreferredDevice`, and **always falls back to the phone** when the
earbuds are gone. Headset actions go through `HeadphoneRepository` only (no vendor code in
`sleep/`, the `VendorBoundaryTest` rule), and modes are chosen by meaning (`ModeResolver`).

## 3. What works on which headset

| Feature | Any headset (GENERIC) | Headsets with a driver | Sleep A30 (later phase) |
|---|---|---|---|
| Timer, sounds, fade in / out | Yes | Yes | Yes |
| Noise cancelling off | No: the row stays visible and says why | Yes: the mode that means Off, else `setAncEnabled(false)` | Yes (its "Off" mode) |
| End: stop sounds | Yes | Yes | Yes |
| End: disconnect | Android 17 + linked once (CDM `disconnect`) | Same | Same |
| End: power off | No | Bose (`powerOff`, on the phone checklist), Sony (virtual only). Soundcore and JBL guards refuse power off: keep that | Refused by the guard; the earbuds' own timer instead |
| Alarm in the earbuds | Yes, while Android has an audio output for them | Yes | Later: the earbuds' own alarm |
| Lock touch controls / mute voice prompts for the night | No | Bose (touch, prompts), Soundcore A30 (touch, prompt toggles) | Yes |

"Tools never hide settings" applies: every row is visible on every headset; a row that can't work
explains why and what would make it work ("Link this headset once to let Earshot disconnect it").

## 4. How each part works

### 4.1 Sounds (`:dsp`, pure Kotlin)

**Changed 2026-10-26 after the user heard them:** the synthesised nature sounds were "awful", so
rain, heavy rain, ocean, stream, wind, fire and the birdsong alarm now play **real recordings**:
public-domain field recordings from radio aporee ::: maps (archive.org), cut into seamless 60–75 s
48 kHz loops by `tools/sleep-sounds/make_loops.py` (sources, licences and cut points in
`tools/sleep-sounds/sources.json`; credited on the About screen) and bundled as
`assets/sleep/<id>.ogg` (5.8 MB). `dsp/sleep/Recordings.kt` plays a decoded loop
(`LoopSource`, random start per night); `sleep/BundledRecordings` decodes the files with
`MediaCodec` off the audio thread. A sound without a decoded recording falls back to the
synthesis below, which also still covers white, pink and brown noise and the fan (no clean,
freely licensed fan recording exists; recording the user's own fan would fix that).

**Your own sounds (added 2026-10-26):** the Sounds card has a "Yours" group and a "Your own
sound" tile. Android's file picker (any audio, no storage permission) → a name → Earshot copies the
file to `files/sleep_custom/<uuid>` and `dsp/sleep/LoopMaker` turns it into a seamless 48 kHz
stereo loop (Catmull-Rom resampling, at most 90 s, the last 3 s crossfaded into the start, DC
removed, levelled to the bundled sounds, at most +30 dB). Layer ids are `custom:<uuid>`
(`CustomSound`); the mixer plays layers by id. Long-press deletes one (also from tonight's mix
and saved mixes).

The synthesis (the original design):

| Sound | Recipe |
|---|---|
| White | Uniform random samples (seeded `SplittableRandom`), never a looped buffer. |
| Pink | White through Paul Kellet's pink filter (-3 dB/octave). |
| Brown | Leaky integrator of white (-6 dB/octave), DC removed. |
| Rain | Pink noise band-passed around 1–6 kHz with a slow random level drift, plus droplets: short decaying noise bursts at random times (Poisson), random pitch and stereo position. "Heavy rain" = more drops, lower band. |
| Fire | Brown-noise rumble with slow flicker, plus crackles: very short high-passed clicks in random clusters, and occasional louder pops. |
| Later | Ocean (noise with a 6–12 s swell envelope), wind (band-passed noise with a wandering centre), fan (brown noise plus a faint hum). |

- `SleepSound` (id, name, group Noise / Water / Nature / Home), `SoundLayer(sound, gain)`,
  `SoundMixer` (up to 3 layers, per-layer gain, soft limiter so a mix never clips), `FadeCurve`
  (equal-power fade in, long fade out).
- Renders 48 kHz stereo float blocks on demand; the player pulls blocks into the `AudioTrack`.
- Tests on the JVM: spectral slope of pink/brown, no sample above full scale for any mix, the same
  seed gives the same output, no audible loop (autocorrelation), CPU per second of audio.
- If generated rain or fire don't sound good enough on the phone, the fallback is a few CC0
  recordings (bigger APK, attribution in About). Decide after hearing them (§9).

### 4.2 The session and its timeline

A pure `SleepPlanner` turns a `SleepPlan` into a timeline, so the rules are unit-tested:

```
T0          Start (visible tab): service starts, sounds fade in over 30 s,
            the night extras are applied (touch lock, prompts off, DND, volume cap)
T0 + n      Noise cancelling off (if chosen: after 15 / 30 / 60 min, or at the end)
Tend - f    Fade out the sounds (and, optionally, the other app's music) over f minutes
Tend        End action: stop sounds -> disconnect or power off (if chosen and allowed)
Twake - w   Wake-up window: a quiet sound starts and rises (if chosen)
Twake       Alarm rings: earbuds / phone / both, with snooze
After stop  Morning restore: the headset's daytime mode, touch controls and prompts come back
```

Sketch of the contract (the real one goes in `core/SleepRepository.kt` with a fake in `core/fake/`):

```kotlin
data class SleepPlan(
    val duration: Duration,                 // 15 min .. 6 h
    val layers: List<SoundLayer>,           // empty = timer only (for another app's audio)
    val noiseOffAfter: Duration?,           // null = leave noise cancelling as it is
    val fadeOut: Duration,                  // default 10 min
    val endAction: SleepEndAction,          // STOP_SOUNDS, DISCONNECT, POWER_OFF
    val nightExtras: Set<NightExtra>,       // TOUCH_LOCK, PROMPTS_OFF, DO_NOT_DISTURB, VOLUME_CAP
    val pauseOtherAudio: Boolean,           // fade and pause Spotify / podcasts at the end
)

data class SleepAlarm(
    val time: LocalTime, val days: Set<DayOfWeek>, val enabled: Boolean,
    val output: AlarmOutput,                // EARBUDS, PHONE, BOTH
    val sound: AlarmSound,                  // generated chime, the phone's alarm tone, a sleep sound
    val wakeWindow: Duration?, val fadeIn: Duration, val snooze: Duration?, val vibrate: Boolean,
)

interface SleepRepository {
    val session: StateFlow<SleepSessionState>   // idle / running(timeline, now) / ringing / snoozed
    val alarms: StateFlow<List<SleepAlarm>>
    val plan: StateFlow<SleepPlan>              // last used, so Start is one tap
    suspend fun start(plan: SleepPlan)
    suspend fun extend(by: Duration)            // "+15 min" from the screen or notification
    suspend fun stop()
    suspend fun saveAlarm(alarm: SleepAlarm)
    suspend fun snooze()
    suspend fun dismissAlarm()
    fun preview(layers: List<SoundLayer>)       // audition a sound from the library
}
```

### 4.3 Timers that survive the night

- Stage moments are `setExactAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP)` alarms to a
  `SleepAlarmReceiver`; the receiver hands the step to the running service. While sounds play the
  CPU stays awake anyway, but a timer-only session (another app's podcast) would drift with
  `delay` alone.
- Exact alarms need the "Alarms & reminders" special access (`SCHEDULE_EXACT_ALARM`). Without it,
  stage moments fall back to `setAndAllowWhileIdle` (can be minutes late; the tab says so) and the
  wake alarm offers "Set it in the Clock app" instead (`AlarmClock.ACTION_SET_ALARM`, needs no
  special access).
- The active session and the alarms are stored (DataStore). After process death the receiver
  rebuilds the session from the store; after a reboot the existing `BOOT_COMPLETED` path re-arms
  the wake alarms only (Android 15 forbids starting a `mediaPlayback` service from boot).

### 4.4 Android 17 background audio

From the [Android 17 page](https://developer.android.com/about/versions/17/changes/bg-audio):
playback, focus and volume calls need a visible activity or a non-`SHORT_SERVICE` foreground
service; apps **targeting** 37 also need the service to have while-in-use (WIU), which a service
started from a visible screen has. The WIU rule is waived for `USAGE_ALARM` streams when the app
holds the exact alarm permission. So:

- Start is only from the visible tab, a notification tap, the tile or a widget (all WIU sources).
- The alarm plays `USAGE_ALARM` from the service the alarm restarted, with the exact alarm
  permission held, and shows the lock-screen alarm screen (a visible activity) when it can.
- Earshot targets 36 today; test with `adb shell cmd audio set-enable-hardening enable`, which
  enforces the target-37 rules early.

### 4.5 Noise cancelling off, and the morning restore

`SleepHeadsetActions` (in `sleep/`, talks to `HeadphoneRepository` only):

1. Remember the current mode as a `ModeRef` (never a bare slot) and the touch / prompt settings.
2. Noise off: if `ModeResolver.slotFor(ModeRef(OFF))` finds a mode, `setMode(slot)`; else, if
   `capabilities.ancToggle`, `setAncEnabled(false)` (the Bose QC Ultra 2 has no "Off" mode but
   has the `[31.10]` ANC byte); else the session notes "this headset can't switch noise
   cancelling from an app" and carries on.
3. Every command is `runCatching`: a refusal becomes a line in the session, never a stopped night.
4. Morning restore (after the alarm is stopped, or on the next connect if the headset was off):
   put back the remembered mode, touch controls and prompts.

### 4.6 The end of the timer

- **Stop sounds:** fade out, stop the track. With "pause other audio": ramp `STREAM_MUSIC` down over
  the fade, take audio focus (players pause on a permanent loss), send `KEYCODE_MEDIA_PAUSE` as a
  backup, then put the volume back so tomorrow's music isn't silent.
- **Disconnect:** `CompanionRemote.disconnect(address)` (Android 17, linked headset). Many headsets
  then switch themselves off after their own idle time.
- **Power off:** `HeadphoneRepository.powerOff()` where the driver has it; otherwise disconnect;
  otherwise stop sounds and say so.
- **Conflict rule:** if the alarm should ring in the earbuds, the planner refuses "power off" and
  keeps "disconnect" only when it can reconnect before the alarm (`CompanionRemote.connect`
  2 minutes before; a headset that switched itself off can't be woken). Otherwise the tab says the
  alarm will ring on the phone, before Start.

### 4.7 The alarm

- `AlarmManager.setAlarmClock(info, operation)`: exact, shown as the next alarm in the status bar,
  fires in Doze. `info.showIntent` opens the Sleep tab.
- On fire: `SleepAlarmReceiver` starts `SleepService` in alarm mode (exact alarms may start a
  foreground service from the background) and posts a full-screen-intent notification that opens
  `sleep.AlarmActivity` (`showWhenLocked`, `turnScreenOn`) with big **Stop** and **Snooze**.
- Output (`AlarmRouting`, pure and unit-tested, built like `FindOutput`):
  - **Phone:** `setPreferredDevice(TYPE_BUILTIN_SPEAKER)`.
  - **Earbuds:** the headset's own output from `FindOutput.pick`; checked after it starts
    (`routedDevice`), like the Find tone.
  - **Both:** two tracks, or one alarm track left to Android's alarm routing (it normally plays on
    the speaker and the headset together); the spike in §8 decides.
  - **Safety net (not optional):** "Earbuds" moves to the phone when there is no headset output at
    fire time, when routing lands elsewhere, or when nobody stops it within 60 s. Never rely on the
    headset alone to wake someone.
- Fade in 15 s to 3 min, wake-up window (a quiet sound up to 30 min early), snooze 5 / 10 min,
  repeat days, phone vibration. Sounds: a generated chime, the phone's own alarm tone
  (`RingtoneManager.TYPE_ALARM`), or a sleep sound.

## 5. The tab

Order top to bottom (the user's layout, set 2026-10-26 after trying the first version on the
Fold7), in the Field theme (night palette in dark mode):

1. **Headset header:** name, L / R / case battery (Home's battery component) and a bedtime battery
   check ("about 4 h left: the 6 h timer will outlast it").
2. **Alarm card:** time and switch, "Rings in: Earbuds · Phone · Both", wake-up window, sound,
   fade in, snooze, repeat days; the permission banners (alarms, full screen, notifications)
   right under it.
3. **Tonight card:** the sleep timer as a snapping carousel (30 min, 1 h … 6 h, Custom) with a
   line above it saying what happens and when ("Your earbuds disconnect at 3:57 AM", then the
   fade and what the end action does on this headset), **Start**; the noise-cancelling timer as a
   carousel with its own line ("Noise cancelling goes off at 11:42 PM"); "When the timer ends"
   (Stop sounds / Disconnect / Switch off).
4. **Sound library:** group chips Noise · Water · Nature · Home, a grid of tiles, tap to preview,
   up to 3 layers with a volume each, saved mixes, where sounds play, sound volume.
5. **For the night** (the switches, last): lock touch controls, mute voice prompts, Do Not
   Disturb, volume cap, pause other apps.
6. Not built: a mini player bar above the bottom bar while a session runs.

While a session runs the tab shows a dim night view: big time left, the sound, +15 min, Stop. The
service notification carries the same buttons (later a Live Update, idea `live-status`).

## 6. Extra ideas, scored

Jev scored 20 ideas for value to someone who sleeps with earbuds (0 = niche … 3 = solves a
real nightly problem), whether they need headset control (what a plain sleep-sounds app can't do)
and a risk of being unreliable or making health claims. Claude decided where Jev was unsure
(marked *Claude*). Cost $0.0006, 1.5 s.

| Idea | Value | Needs headset | Risk | Decision |
|---|---|---|---|---|
| Bedtime battery check | 2.84 | 0.95 | 0.16 | **Core** (phase 5, first) |
| Alarm safety net (phone takes over) | 2.84 | 0.71 | 0.47 | **Core**, built into the alarm |
| Do Not Disturb for the night | 2.78 | 0.11 | 0.57 | Phase 5 (needs notification-policy access) |
| Night volume cap | 2.74 | 0.23 | 0.22 | Phase 5 |
| Lock touch controls for the night | 2.55 | 0.94 | 0.36 | **Phase 5**, a standout: maker apps don't do it across brands |
| Mute voice prompts for the night | 2.55 | 0.92 | 0.45 | Phase 5, with touch lock |
| Gentle wake (window + fade in) | 2.54 | 0.14 | 0.41 | **Core**, part of the alarm |
| Tap the earbud to snooze | 2.49 | 0.87 | 0.67 | Phase 5 (a media session while ringing; player conflicts) |
| Side-sleeper: sound in one ear | 2.43 (unsure) | 0.55 | 0.33 | *Claude:* phase 5, cheap (`FindTone` already zeroes a side) |
| Timer for any app (pause Spotify / podcasts) | 2.29 (unsure) | 0.14 | 0.71 | *Claude:* **core**: the classic sleep-timer use; the risk is players that ignore pause, so it says so |
| Quick start (tile, shortcut, widget) | 2.19 | 0.38 | 0.42 | Phase 5 |
| Morning restore | 2.07 | 0.96 | 0.67 | **Core**: whatever the night changed must come back |
| Lock-screen card (+15 min, Stop) | 2.05 (unsure) | 0.14 | 0.32 | *Claude:* core as notification buttons; Live Update later |
| Bedtime reminder | 2.03 | 0.66 | 0.41 | Later |
| Sleep A30's own timer, sounds, alarm | 1.98 (split) | 0.95 | 0.28 | *Claude:* phase 6: the user owns the pair, but it needs protocol reads first |
| Sound mixer with saved mixes | 1.77 | 0.10 | 0.15 | Core mixing (3 layers); saved mixes phase 5 |
| Own audio file as sound or alarm | 1.40 | 0.20 | 0.25 | Later (file picker, no permission) |
| Private night log | 1.31 (unsure) | 0.19 | 0.14 | *Claude:* later, no sleep-stage claims |
| Smart alarm from phone movement | 1.77 (unsure) | 0.19 | 0.90 | *Claude:* **dropped**: unreliable and implies sleep-stage claims |
| Binaural beats | 1.03 | 0.08 | 0.85 | **Dropped**: health claims |

Raw answers: Jev batch of 2026-10-26 (scratchpad, not kept in the repo).

## 7. Files and who owns them

New, owned by the Sleep work:
- `dsp/src/main/kotlin/dev/earshot/dsp/sleep/`: generators, `RainSynth`, `FireSynth`,
  `SoundMixer`, `FadeCurve`, `SleepSound` catalogue; tests under `dsp/src/test/`.
- `app/.../core/SleepRepository.kt`, `core/SleepModels.kt`, `core/fake/FakeSleepRepository.kt`.
- `app/.../sleep/`: `SleepService`, `SleepSoundPlayer`, `SleepPlanner`, `SleepScheduler`,
  `SleepAlarmReceiver`, `AlarmActivity`, `AlarmRouting`, `AlarmPlayer`, `SleepHeadsetActions`,
  `SleepStore`, `SleepRepositoryImpl`.
- `app/.../ui/sleep/`: `SleepScreen`, `SleepViewModel`, `TonightCard`, `SoundLibrarySection`,
  `AlarmCard`, `AlarmEditorScreen`, `SleepMiniPlayer`, `NightView`; `Tab.SLEEP` and the routes in
  `ui/nav/` (icon `Bedtime` from material-icons-extended, already a dependency).
- Tests: `SleepPlannerTest`, `AlarmRoutingTest`, `SleepHeadsetActionsTest` (on `DemoDevice`s:
  QC Ultra 2, Sleep A30, a generic headset), and `sleep` added to `VendorBoundaryTest.neutral`.

Integrator items (manifest, `AppContainer`, Gradle):
- Permissions: `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `SCHEDULE_EXACT_ALARM`,
  `USE_FULL_SCREEN_INTENT`, `VIBRATE`; later `ACCESS_NOTIFICATION_POLICY` (Do Not Disturb).
  `USE_EXACT_ALARM` (auto-granted, Play allows it only for alarm apps) is a 1.0 Play decision.
- Components: `sleep.SleepService` (`foregroundServiceType="mediaPlayback"`),
  `sleep.SleepAlarmReceiver` (not exported), `sleep.AlarmActivity` (`showWhenLocked`,
  `turnScreenOn`, excluded from recents).
- `AppContainer`: `val sleep: SleepRepository by lazy { SleepRepositoryImpl(appContext,
  headphones, deviceFinder, CompanionRemote(appContext), applicationScope) }`.
- No new library: DataStore, coroutines and Compose are already there.

## 8. Build order (start at phase 0)

| Phase | What | Needs hardware | Done when |
|---|---|---|---|
| **0. Spikes** (1–2 days, Fold7) | (a) `USAGE_ALARM` track with preferred device = headset / speaker / none: where does it play, with hardening on? (b) a `mediaPlayback` service started from the tab keeps playing 1 h+ with the screen off (One UI "sleeping apps"). (c) `setAlarmClock` -> receiver -> service + full-screen screen on a locked phone. (d) CDM disconnect / connect on the QC Ultra 2 and the A30: does the headset stay on, and for how long? (e) Bose power off (phone checklist item). | Yes | Answers written into §4 |
| **1. Sound engine** | `:dsp` generators, rain, fire, mixer, fades, tests | No | `:dsp:test` green; a debug button plays each sound |
| **2. Contract + tab** | `SleepRepository` + fake, `Tab.SLEEP`, the screen against the fake, previews (light and dark) | No | The tab works end to end on the fake |
| **3. Night session** | service, player, planner, scheduler, noise off, end actions, notification buttons, morning restore | Yes | A 15-min session on the QC Ultra 2 and on a generic headset runs every stage |
| **4. Alarm** | `setAlarmClock`, receiver, alarm screen, routing, safety net, snooze, fade in, wake window | Yes | Alarm rings in earbuds / phone / both, and on the phone with the buds in their case |
| **5. Night extras** | battery check, touch lock + prompts off, DND, volume cap, timer for other apps, side sleeper, quick start, saved mixes | Partly | Each extra restores itself in the morning |
| **6. Sleep A30 native** | read `15 03` (sleep settings) and `14 01` (alarms) on the real pair in the Lab, then the earbuds' own timer (`15 85` full form), local sounds and alarm (`14 81`, added to the guard with its source) | Yes (A30) | The A30's own timer and alarm work with the phone in another room |

Phases 1 and 2 can run in parallel (separate trees: `:dsp` vs `ui/` + `core/`). Phase 0 can run
alongside them on the phone.

## 9. Decisions for the user

The user didn't answer these before building ("start building"), so the recommended defaults were
used; each is one constant or one line to change.

1. **Generated sounds first, recordings only if they disappoint.** Built that way.
2. **Exact alarm access:** `SCHEDULE_EXACT_ALARM`, asked for from the tab's permissions banner;
   `USE_EXACT_ALARM` stays a 1.0 Play decision.
3. **Default end action:** Stop sounds (`SleepPlan.endAction`).
4. **Alarm default:** earbuds, phone joins after 60 s (`SleepAlarm.PHONE_FALLBACK_SECONDS`).

## 10. Guard rails

- No medical or sleep-quality claims anywhere in the copy.
- Never write to the headset outside the existing guards; the A30 alarm write is added only with
  its source and a hardware test, like every guard change.
- Nothing about the night leaves the phone; no INTERNET permission, as always.
- The alarm never depends on the headset alone.

## 11. What was built (2026-10-26) and what is left

Built in one session: the contract and fake (main session), the sound engine (a helper agent,
`:dsp` `dsp/sleep/`, 34 tests), the screens (a helper agent, `ui/sleep/`, 23 tests), the runtime
(main session, `sleep/`, 28 tests). A review agent found 17 problems; Jev ranked them by harm and
all were fixed (cancellation, speaker fallback after a restart, the silent full-screen alarm, the
phone fallback when the earbud stream dies, exact-alarm re-arming, alarms after an overnight
restart before unlock, lost or early restores, the wake-up-window double ring, and smaller ones).
A second review of those fixes found 13 more, all fixed too: the service could stop itself right
after Start (the session is now published before the service starts), a snooze taken on a locked
phone was lost at unlock, a one-off alarm's switch-off wasn't saved, a pending restore dropped its
phone part, the morning volume now waits for its own output and for a foreground, a store that
can't load no longer blocks everything, a preview can't end the night's sounds, and smaller ones.
Full suite green: app 1,051 (5 old skips), dsp 76, protocol 320; lint 0 errors; debug APK builds.

Phases done: 1 (sounds), 2 (contract and tab), 3 (night session), 4 (alarm), 5 (night extras,
including the launcher "Start sleep" shortcut). Not done:
- **Phase 0 / phone checks:** skipped on purpose; run `PHONE_TEST_CHECKLIST.md` §5c on the Fold7.
  Listen to `dsp/build/sleep-sounds/*.wav` on the PC first (rain and fire were tuned by
  measurement, not by ear).
- **Phase 6 (Sleep A30's own timer, local sounds, alarm).**
- Not built: the mini player across tabs (needs the root scaffold), a Live Update card, a
  bedtime reminder, own audio files, a night history beyond tonight's log.
- Unknowns only a phone answers: whether `USAGE_ALARM` pinned to the A2DP output plays on the
  earbuds only (some phones duplicate alarms to the speaker), whether One UI lets the
  `mediaPlayback` service run all night, and how each headset behaves after a CDM disconnect.
- No test drives `SleepRepositoryImpl` itself (it needs Android: AlarmManager, DataStore,
  AudioTrack); its pure parts (`SleepPlanner`, `AlarmRouting`, `SleepHeadsetActions`, the JSON)
  are tested.
