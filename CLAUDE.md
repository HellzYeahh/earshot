# Earshot — Bose QC Ultra Headphones (2nd Gen) controller for Android

A native Kotlin/Compose replacement for the Bose app, plus a sound-tuning engine, codec panel,
mic tools and automations. Talks to the headphones directly over Bluetooth RFCOMM (BMAP
protocol, channel 2). No account, no cloud, **no INTERNET permission**.

## Modules

| Module | What | Depends on |
|---|---|---|
| `:protocol` | Pure Kotlin/JVM. BMAP framing (`BmapPacket.kt`, the contract) + `qcultra2/` catalogue, typed parsers/builders, Lab scanner helpers. Unit tests against `docs/reference/bosectl` captures. | nothing |
| `:dsp` | Pure Kotlin/JVM. `EqModel.kt` (the contract), biquad maths, graphic↔parametric mapping, AutoEq import, limiter/preamp staging. Unit tests. | kotlinx-serialization |
| `:app` | Android. `core/` contracts, `bt/`, `service/`, `audio/`, `codec/`, `mic/`, `ui/`, `extras/`, `virtual/` (in-process virtual QC Ultra 2 + demo-mode flag; `ConnectionManager` takes a `demoTransport` factory). | `:protocol`, `:dsp` |

Real-hardware facts learned 2026-10-16 on the user's Galaxy (Samsung Galaxy, Android 17): firmware
10.12.12 connects via the reflection `createInsecureRfcommSocket(2)` strategy, greeting silent,
`[2.3]` charging → FuncNotSupp, `[1.9]` returns 15 bytes. The headphones only negotiate AAC 44.1 k
or SBC with this phone. Real captures: `docs/reference/captures-galaxy/`.

## Contracts (read these first; do not change them without telling the integrator)

- `protocol/src/main/kotlin/dev/earshot/protocol/BmapPacket.kt` — operators, errors, encode/decode.
- `dsp/src/main/kotlin/dev/earshot/dsp/EqModel.kt` — `ParametricBand`, `EqPreset`, `TuningSettings`.
- `app/src/main/kotlin/dev/earshot/core/*.kt` — `HeadphoneState` + `HeadphoneRepository`,
  `AudioTuningRepository`, `CodecRepository` + `MicCheckRepository`, `AutomationRepository`.
- `app/src/main/kotlin/dev/earshot/core/fake/` — working in-memory fakes. Build UI and extras against these.
- `app/src/main/kotlin/dev/earshot/AppContainer.kt` — manual DI. **Integrator-owned.** Report the
  constructor you need; don't edit it.
- `app/src/main/AndroidManifest.xml` — components are pre-declared with fixed class names
  (`service.HeadphoneService`, `service.BluetoothConnectionReceiver`, `audio.AudioSessionReceiver`,
  `extras.tile.ModeTileService`, `extras.widget.EarshotWidgetReceiver`,
  `extras.automation.ScheduleReceiver`). Implement exactly those names. If you need another
  permission or component, say so in your report rather than editing the manifest.

## Ownership (parallel agents work in separate checkouts)

Each agent owns one directory tree and touches nothing outside it except new test files under its
own module. Never edit another area's files, the Gradle files, the version catalog, the manifest or
`AppContainer.kt`. Put "needs from integrator" items in your final report.

## Conventions

- Kotlin, coroutines + `StateFlow`; no Hilt/Dagger, no RxJava. Dependencies only from
  `gradle/libs.versions.toml`; if you need something new, ask in your report — don't add it.
- Repository commands are `suspend`, update `state` before returning, and throw
  `HeadphoneCommandException` (user-readable message) on failure. UI uses `runCatching`.
- Human units in `core` models; wire conversions only in `:protocol`. Example: noise-cancelling
  level is 0..10 with **10 = most cancelling** in the UI; on the wire `[31.10]` it is inverted.
- Compose: Material 3, `collectAsStateWithLifecycle`, previews use the fakes, dark theme must look right.
- Log tag prefix `Earshot/…`. No PII in logs beyond the headphones' Bluetooth address.
- Keep files focused; prefer several small files over one huge one. Comment the *why*, especially
  anything learned from the protocol notes.

## Protocol facts that bite (full notes: `docs/bmap-from-bose-app-13.3.4.md`, `docs/reference/bosectl/NOTES.md`)

- RFCOMM **channel 2** on the QC Ultra 2; SPP UUID `00001101-0000-1000-8000-00805F9B34FB`; Bose's own BMAP UUID
  `00000000-deca-fade-deca-deafdecacaff` (SDP may resolve it to the wrong channel on some phones —
  prefer `createInsecureRfcommSocket(n)` via reflection, SDP last).
- **The channel is probed, not assumed** (bosectl #22/#26/#44: prince units on 8 *and* 9; 14/25/26
  accept the socket without BMAP, 14 = `ff55…` beacon, 22 = log stream). `BoseLinkFactory` walks
  `BmapCandidates.order` (learned → name guess → 2 → 8 → 9 → SDP SPP → SDP BMAP) within one
  connect via `BmapChannelSearch` (1.5 s probe each, 20 s budget), keeps the first that passes and
  stores it in `DevicePrefs` (SDP winners as negative codes). Opened-but-mute → `looksBusy`.
- **Strict `LivenessProbe`**: GET `[0.1]` (the QC45 init packet); alive only on a device-side
  STATUS/ERROR/RESULT for `[0.1]` or traffic that frames *entirely* as device-side BMAP on known
  blocks (`BmapConnection.traffic()`). Beacons, logs and echoes fail.
- **Silent-function tolerance**: after 3 silent requests both `BmapConnection` and the refresh's
  `TolerantReader` send GET `[0.1]` (1.5 s). It answers → the silent functions are absent for
  this connection (`silentFunctions`, skipped by the refresh); silent too → the link is deaf
  (busy channel), closed as before.
- **Runtime capabilities** (catalogue = first guess): `[31.10]` refused (4/3/5) or silent →
  `liveNoiseControl` off for the connection, noise edits rewrite the active custom slot; the
  `[31.6]` write uses the layout of the STATUS frames seen (47 → 39 B, 48 → 40 B); FblockNotSupp
  anywhere in block 31 → the block is skipped, `capabilities.listeningModes = false` (UI hides
  carousel/editor), mode commands throw and the level goes through `[1.5]` (below). FblockNotSupp
  on any block skips the whole block per connection. All of this lives in `service/ConnectionFacts.kt`
  (catalogue entry + `[31.2]` + seen `[31.6]` layout + `[31.8]` total + `[1.5]` steps → slot plan
  and capabilities) and `NoisePath` (LIVE `[31.10]` / DIRECT `[1.5]` / SLOTS / READ_ONLY / NONE).
- **`[31.2]` Capabilities** (read once per connection, beats the catalogue): byte 0 built-in modes
  (slots `0 until n`), byte 1 custom modes (the slots after them; 0 = none, some QC45 firmware),
  byte 5 flags bit0 cnc, bit1 autoCnc, bit2 spatial, bit3 wind, **bit4 favourites, bit5 ANC toggle**,
  byte 6 minimum favourites (only if 7 bytes). fw 10.12: `04 07 00 00 00 7f 02`.
- **`[1.5]` CNC** `[steps, step, flags]`: step 0 = **most** cancelling (Bose app `worldVolumeStep`,
  confirmed, docs/bose-models.md §C3); flags bit0 enabled, bit1 = 0 → user may switch ANC off.
  SET_GET `[step, enabled]` is the noise path of the QC Earbuds 2020 (bosectl-verified), the NC 700
  (guess) and any unit without block 31; wolverine and the QC45 answer error 5. A refusal → slot
  rewrites (with modes) or read-only for the connection.
- No handshake; the device may send a greeting burst on connect — drain it.
- Operators: GET works everywhere; every write the Bose app makes is SET_GET or START. Plain SET →
  error 5, which means "operator not implemented for this function" — **not** an auth gate (there
  is none on any BMAP write the app does). UI copy: "The headphones' firmware doesn't support changing this".
- **Bitsets are big-endian** (bit i in byte `len-1-i/8`): `[0.2]`, `[x.1]` GetAll (`[7.1] 0873` =
  {0,1,4,5,6,11}), `[1.9]` masks, `[9.2]`, `[31.8]`, `[18.1]`. Use `qcultra2.Bitset`.
- `[9.2]` pushes are **off** after connect (`00000000`); Earshot subscribes like the Bose app with
  SET_GET `01 80 04 22 b7` (blocks 0,1,2,4,5,7,9,13,18,31). Then STATUS frames arrive by themselves
  (battery, charger `[2.5]`, buttons `[2.12]`, mic `[2.21]`, volume `[5.5]`, play state `[5.4]`,
  modes `[31.3]`/`[31.10]`, LE Audio `[1.37]`); no polling except a 60 s battery poll if `[9.2]` fails.
- `[31.10]` SET_GET 5 bytes `[cnc, autoCnc=0, spatial, wind, anc]`, cnc wire-inverted; `autoCnc=1`
  is rejected (Runtime 8). CNC is audible only when anc=1 and wind=0.
- `[31.3]` START `[modeIndex, 0]` switches modes; GET returns the current index.
- `[31.6]` START with no payload dumps every slot as 48-byte STATUS frames (one per mode, may
  arrive across several reads); GET `[index]` reads one; SET_GET 40-byte payload writes custom slots
  4..10 only. In the 48-byte STATUS **wind is byte 46** (45 is a flag, 1 on user slots; 41 = changeable
  flags); in the 40-byte write wind is byte 38. `[31.9]` START `[index]` resets one slot.
- **`[31.6]` lengths by model** (Bose app: spatial if ≥ 45 bytes, wind if ≥ 47, ANC if 48): STATUS
  44 → write 37 (lando, level only; the 37-byte write is **untested**), 45 → 39 (spatial, 0 wind pad),
  47 → 39 (prince / duran), 48 → 40. `ModeLayout.ofStatus`; the write follows the frames seen.
- Mode names come from the prompt (`ModeNames`, as the Bose app does), then the UTF-8 name, then "Mode N".
  Rewriting a slot keeps its prompt and name bytes; a name the user types is written with prompt 0.
- `[31.8]` favourites `[totalModes][big-endian bitset]` (2 bytes for 11 slots: `0b 00 37` = {0,1,2,4,5}).
- `[1.7]` EQ: GET → 3×4 bytes `[min,max,current,band]`; SET_GET `[value, band]` per band. The
  min..max (within ±10) is `HeadphoneState.eqRange`; writes are clamped to it.
- `[1.4]` auto-off (StandbyTimer): GET < 3 bytes → `[m]`, ≥ 3 → `(b2 << 8) | b0`; SET_GET `[m]` if
  ≤ 255 else `[lo, hi]` little-endian; 0 = never. App options `AutoOffOptions.MINUTES` = 5, 20, 40, 60,
  180, 1440, 0. FuncNotSupp on wolverine fw 10.12 (the setting is then hidden).
- `[1.11]` sidetone `[persist, level]` with level 0=off 1=high 2=med 3=low (note the order).
- `[2.2]` battery: 4-byte records `[percent (0xFF unknown), minutes u16 BE (0xFFFF unknown), id]`; ids
  0 single, 1 right, 2 left, 3 case, 4 combined buds (bosectl only; overall when there is no 0).
  1-3 bytes = one record. `HeadphoneState.battery` per part; the headline `batteryPercent` is
  `Battery.aggregate`. Charging is `[2.5]` ChargerDetect; `[2.3]` is AuxCableDetection.
- `[1.37]` LE Audio SET_GET restarts the headphones (link drops; tolerate it). `[5.7]` latency
  `[min,max,cur]` s16 BE (0..520, 210); its SET_GET is unused by the Bose app (experimental).
- Error 24 (not in the Bose app) on blocks 8, 22–26: guessed "needs a secure/debug session".
- `[4.4]` paired list (byte 0 = connected bitmask), `[4.5]`/`[4.6]` device info (GET with 6-byte MAC
  payload), `[4.9]` AppAddress = this phone, `[5.1]` active source, `[4.12]` START `[0x82 + mac]` to
  route (retry: routing table fills only while the target streams; its GET is FuncNotSupp on 10.12).
- Only one app can hold the BMAP channel: the Bose app must be closed while Earshot is connected.
- Other models: `protocol/.../qcultra2/BoseModel.kt` is the catalogue, only for names, family /
  generation ("2nd Gen" only on 0x4082, 0x4062, 0x4083, 0x4089), form factor, first-guess channel,
  layout and presets (per-model facts: `docs/bose-models.md`). Entries: QC Ultra Headphones 2nd Gen
  (wolverine) / 1st (lonestarr), QC Headphones 2nd Gen (**mariah** 0x4083) / 1st (prince, + QC SC),
  QC45 (+ QC SE), NC 700 (goodyear 0x4024, BASIC: no block 31, `[1.5]` noise), QC Ultra Earbuds 2nd
  (edith 0x4062) / 1st (scotty 0x4072), QC Earbuds II (smalls 0x4064), QC Earbuds 2020 (lando 0x402F,
  BASIC: ch 8, 44-byte modes, `[1.5]` noise), Ultra Open Earbuds 1st (serena) / 2nd (aubrey 0x4089),
  and UNSUPPORTED QC35 0x400C / QC35 II 0x4020 (Bose Connect era; battery and product info only,
  every command throws the `supportNote`) and the 2024 QC Earbuds (brussels 0x0023, Bragi, never
  BMAP). Detected from `[0.3]`; `[0.15]` name for unknown ids (`fromName`, the app's originalNames),
  else QC Ultra 2 defaults with the family taken from the name. QC45/QC Headphones: channel 8 first
  guess (prince seen on 9 too), 47/39-byte `[31.6]`, no `[31.10]` (noise edits rewrite the active
  custom slot), QC45 needs GET `[0.1]` first (the liveness probe sends it), prince answers `[31.3]`
  START with PROCESSING and a late STATUS (a timeout is verified with a GET). Only the QC Ultra 2 is
  tested on our hardware; the others follow bosectl or are best guesses that the runtime checks
  correct. `VirtualQcUltra2(profile = VirtualProfile.X)` emulates each model (demo devices in
  `DemoDevice`); the quirk switches (`silentFunctions`, `refuseAudioModesBlock`,
  `withoutAudioSettings`, `productId`) still work on any profile.
- **Never write to block 3 (FirmwareUpdate) or 18 (Authentication).** bosectl's "[3.2] NC state"
  START is really FirmwareUpdate Init: it opens an update session; Run applies an image and
  reboots; Reset erases a staged image. Safe GETs: `[3.0] [3.1] [3.4] [3.6] [3.16]`. `[7.5]`
  FactoryDefault, `[7.6]` ShipMode, `[7.11]` Reset and `[4.7]` ClearDeviceList are destructive.
  `bt/WriteGuard` refuses all of these on the single write path; keep it that way.
- Error 0xFF carries a sub-code in blocks 0, 3 and 18 (FirmwareUpdate: 0 = non-sequential
  sequence number, 1 = image key mismatch). Error 24 is still unexplained. `[2.13]`/`[2.15]` are
  FUNC_NOT_SUPPORTED on firmware 10.12.

## Skullcandy Crusher 1080 ANC (Bragi BOS-RPC, not BMAP)

A second stack behind the same screens; full notes in `docs/crusher-1080.md`. Never run on real
headphones: its facts come from interoperability analysis of the Skullcandy app plus a virtual device.

- `:protocol` `dev.earshot.protocol.bragi`: `BosRpcPacket` (4-byte header + protobuf), `PppFraming`
  (`7E FF 80 <94 clear / 95 secure> <len> <CRC-8/ROHC> body <CRC-16/MCRF4XX>`, golden frames in
  `BragiWireTest`), minimal `ProtoWriter`/`ProtoReader`, feature messages (`CrusherMessages.kt`),
  `BragiServices` (numeric ids; also the allow-list: no FOTA, no feature.set_state), secure-session
  crypto (`BragiHandshake`, `ChannelCipher`, `AuthMessages`). `dev_info` body is `08 00`.
- `:app`: `BragiConnection` (tagged calls, streaming subscriptions, overlapping calls to different
  methods), `BragiLinkFactory` (data service `54C5BF4C-…` first, then `A4602B24-…`; raw PPP frames,
  never RACE; the answering profile is remembered in `DevicePrefs`), `BragiSession` (pairing + ECDHE), `CrusherProtocol`, `CrusherHeadphoneRepository`,
  `RoutingHeadphoneRepository` (picks Bose or Crusher by name / "Headphone type" / demo device).
- `ConnectionManager<C : ManagedLink>` takes a `LinkFactory`; `BoseLinkFactory` holds the BMAP channel probing.
- Crusher modes: slot 0 Quiet, 1 Aware, 2 Off (so slots 0/1 match Bose for rules, tile, widget).
- `VirtualCrusher` (+ `VirtualCrusherTransport`) is the device side incl. the secure session; the
  end-to-end tests run the real connection and protocol against it. In `runTest`, `advanceUntilIdle()`
  ignores `backgroundScope` work: use `testScheduler.runCurrent()`.
- Do not vendor the Bragi `.proto` files (copyright notice); only field numbers live in the source.

## Build

Always go through `gw.cmd` (it sets JAVA_HOME to Android Studio's bundled JDK; plain
`gradlew.bat` fails with "JAVA_HOME is not set" on this PC):

```
.\gw.cmd :protocol:test :dsp:test --console=plain -q     # fast, no Android
.\gw.cmd :app:compileDebugKotlin --console=plain -q       # type-check the app
.\gw.cmd assembleDebug --console=plain -q                 # APK at app\build\outputs\apk\debug\app-debug.apk
```

From a Bash shell: `cmd //c gw.cmd :protocol:test --console=plain -q`.
If `local.properties` is missing in your checkout, create it with
`sdk.dir=C\:\\Users\\Hellz\\AppData\\Local\\Android\\Sdk`.
Don't run two Gradle builds at once in the same checkout.

## Credits / licence

Protocol knowledge comes from **bosectl** (github.com/aaronsb/bosectl, MIT) and **Quiet Rebellion**
(github.com/MadEyez/quietrebellion, MIT); copies of the relevant files and licences are in
`docs/reference/`. Keep attribution in the About screen. Earshot itself is MIT.
