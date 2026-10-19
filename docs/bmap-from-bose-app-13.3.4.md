# BMAP as seen by the Bose app 13.3.4 (derived knowledge)

Source: `reference/bose-app/base.apk`, package `com.bose.bosemusic` 13.3.4 (versionCode 35696,
SHA-256 `2b473d52…5a29f`), analysed statically with REA (`inspect_android_package` evidence
`ev_35e7c549…91eb`, `inspect_android_class` evidence `ev_8809057f…94f6` for `BmapFunctionBlock`)
and the JADX core bundled with REA (decompiled into a scratch directory, never into this repo).
The `com.bose.bmap.*` library is **not obfuscated** in 13.3.4, so every class name below is real;
app/UI code is obfuscated (`defpackage.XYZ` names are given only so findings can be re-checked).
No Bose code is copied here: only names, numbers, byte layouts and behaviour.

Cross-checked against `docs/reference/bosectl/NOTES.md` and the live sweep
`docs/reference/captures-galaxy/deep-scan-fw10.12-2026-10-17.txt` (fw 10.12.12, section 9).

Legend: **[obs]** read directly from app code; **[scan]** seen on the user's headphones;
**[inf]** inference; **[guess]** unverified hypothesis.

---

## 0. Headline findings

1. **Bitsets are big-endian** (`com.bose.bmap.messages.utils.BitSetUtil.fromByteArray`: bit *i* is
   in byte `len-1-i/8`). Every "supported X" field (`[0.2]`, `[7.1]`, `[13.1]`, `[18.1]`, `[1.9]`
   button masks, `[1.11]`, `[9.2]`) uses it. NOTES.md decodes `[18.1]` and the `[1.9]` action
   mask little-endian and is **wrong** for both. The big-endian reading matches the scan exactly
   (e.g. `[7.1] 0873` → functions 0,1,4,5,6,11, and the scan shows 7.4 STATUS, 7.5/7.6/7.11
   OP_NOT_SUPPORTED, everything else FUNC_NOT_SUPPORTED).
2. **The app never sends plain SET** to settings. Every write it makes is **SET_GET** (op 2) or
   **START** (op 5). "Error 5 on SET" in NOTES.md means "this operator is not implemented for this
   function", not "auth required". The app's only SET packets are `DataCollectionEnableSetPacket`
   and HearingAssistance MappedSettings (block 12, absent on wolverine).
3. **No BMAP write in the app is gated by the ECDH/cloud flow.** In 13.3.4 the app only uses
   block 18 for cloud ownership/activation (`[18.8]` condensed challenge → "SimpleDeviceChallenge"
   for the Bose account, `[18.17]` activation status, `[18.24]` IRK). The functions NOTES.md
   hypothesised as the unlock path (`[18.19]`, `[18.27–29]`) are in the enum but have **zero**
   call sites (section 5).
4. **UI gating is device-driven, not product-table driven.** `BluetoothCapabilities`
   (`defpackage.C3810Lk0`) turns each GET that answered into a capability id; the settings UI only
   shows what the firmware answered. There is no wolverine-specific feature list (the only
   wolverine tables are colour variants, help strings and the supported-product whitelist
   `BmapDevicesModule`). So **the scan is the authoritative wolverine feature list**.
5. **`[9.2]` notifications are off on Earshot's link** (scan `00000000`). The Bose app turns them
   on at connect with SET_GET `[01, 80 04 22 B7]` (Enable + blocks 0,1,2,4,5,7,9,13,18,31), after
   which the headset pushes STATUS frames by itself. This is the cheapest big win for Earshot.
6. **LE Audio is a BMAP toggle**: `[1.37]` LeAudioControl SET_GET `[0|1]` (scan: `01` = on),
   `[0.23]` = LE Audio supported (scan `01`; was `00` on fw 8.2.20), `[4.19]` START = LE Audio
   check (supported/preferred/prohibited). The app uses Android's `BluetoothLeAudio` only to
   *read* connection state. Toggling makes the headset **reboot** (app copy).
7. **`[5.7]` is AudioManagement Latency** `[min u16][max u16][current u16]` big-endian; scan
   `0000 0208 00d2` = 0…520, current 210 (ms [inf]). The app has `setLatency(short)` →
   SET_GET 2-byte BE but **never calls it** for any product. Prime "low-latency" candidate.

---

## 1. Operators, errors, framing

Framing (`com.bose.bmap.messages.packets.BmapPacket`) agrees with `BmapPacket.kt`: header
`[fblock, function, (deviceId<<6)|(port<<4)|op, len]`, payload ≤ 255. Port is only non-zero after
`[4.12]` routing (error 19 says so). The app opens SPP with `createRfcommSocketToServiceRecord`
(UUID `00001101-…`), **not** the deca-fade UUID and not channel 2 by reflection.

Operators (`BmapOperator`): 0 Set, 1 Get, 2 SetGet, 3 Status, 4 Error, 5 Start, 6 Result,
7 Processing, 127 Unknown. (Matches notes.)

Errors (`BmapPacketError`), additions to NOTES.md in bold:

| code | name | app description (paraphrased) |
|---|---|---|
| 0–10 | Unknown, Length, Chksum, FblockNotSupp, FuncNotSupp, OpNotSupp, InvalidData, DataUnavailable, Runtime, Timeout, InvalidState | as notes; 5 = operator not supported **for that function** |
| **11** | DeviceNotFound | not in paired device list |
| **12** | Busy | device busy |
| **13** | NoconnTimeout | could not connect to a PDL device |
| **14** | NoconnKey | pairing key deleted on the source |
| **15** | OtaUpdate | OTA already in progress |
| **16** | OtaLowBatt | battery too low for OTA |
| **17** | OtaNoCharger | charger needed |
| **18** | OtaUpdateNotAllowed | |
| **19** | UnknownPortNumber | non-zero port without a `[4.12]` START |
| 20 | InsecureTransport | |
| **21** | InvalidOtpKey | |
| **0xFF** | FblockSpecific | 2nd payload byte is a block-specific code (block 18 → `AuthenticationError`) |
| **24** | *not in the app* | returned by the scan for many fn ids in blocks 8, 22–26 — see §9 |

`AuthenticationError` (block 18, via 0xFF): 0 InvalidSignature, 1 RotatingKeyNotSet,
2 MissingEcdheKey, 3 MissingSharedKey, 4 PermissionError, 5 CryptoProcessingError,
6 PublicKeyNotFound, 7 FeatureLockFailed, 8 FeatureUnlockFailed, 9 InvalidOtpKey,
10 AuthNotInitiatedByThisConnection, 11 AlreadyActivated, 12 ComponentMissing.
`AuthOtpKeyType`: 0 Invalid, 1 None, 2 Dev, **3 Prod** (scan `[18.11]=03`).

Other transports in the app: BLE GATT service `0000febe-…`, characteristics
`D417C028-…` (unsecure), `C65B8F2F-…` (secure), and **BMAP over LE CoC**: PSM (128–255) read from
characteristic `089CA084-6721-4419-9003-5E14D5AB587D`, socket = `createInsecureL2capChannel(psm)`
(API 36+: `BluetoothSocketSettings` L2CAP, encryption required) — `LecocConnectionManager`.

---

## 2. Complete function table (`BmapFunctionBlock` / `BmapFunction`)

Full machine-readable copy: `docs/bmap-tables/functions-bose-app-13.3.4.json` (283 functions).
Blocks with **no** functions in the enum: 6 CallManagement, 8 Debug, 10/11 ReservedBosebuild1/2,
15 PeerBud, 19 Experimental. **Blocks 23–30 do not exist in the app at all** (they map to
`Unknown`/127), yet the headset answers in 23–26 (§9).

| Block | Functions (id = name) |
|---|---|
| 0 ProductInfo | 0 FblockInfo, 1 BmapVersion, 2 AllFblocks, 3 ProductIdVariants, 4 GetAllFunctions, 5 FirmwareVersion, 6 MacAddress, 7 SerialNumber, 8 DateOfManufacture, 9 BoardSerialNumber, 10 HardwareRevision, 11 ComponentDevices, 12 Guid, 13 ProductSalesRegion, 14 ProductMarking, 15 OriginalProductName, 16 UnifiedCommunicationsConfig, 17 ComponentId, 18 EarbudMode, 19 PeerId, 20 MacAddressForRole, 21 MacAddressForComponent, 22 BondedGroupSupport, 23 LeAudioSupported |
| 1 Settings | 0 FblockInfo, 1 GetAll, 2 ProductName, 3 VoicePrompts, 4 StandbyTimer, 5 Cnc, 6 Anr, 7 RangeControl, 8 Alerts, 9 Buttons, 10 Multipoint, 11 Sidetone, 12 SetupComplete, 13 ConversationMode, 14 CncPersistence, 15 CncPresets, 16 OnHeadDetection, 19 LedBrightness, 20 MotionInactivityAutoOff, 22 FlipToOff, 24 AutoPlayPause, 26 TeamsButtonMode, 27 AutoAnswer, 28 VolumeControl, 29 AutoAwareMode, 30 SourceBargeIn, 32 AutoVolumeLevel, 33 Grouping, 34 DisableCaptouch, 37 LeAudioControl, 38 LeAudioBroadcastConfig, 39 HeadGesture, 41 LeaGrouping, 47 LeaGroupMember |
| 2 Status | 0 FblockInfo, 1 GetAll, 2 BatteryLevel, 3 AuxCableDetection, 4 MicrophoneLevel, 5 ChargerDetect, 6 Preset, 7 NetworkUp, 8 CrashData, 9 InEar, 10 ChargingCaseLid, 11 UnifiedCommunicationsLink, 12 Button, 13 BatteryLog, 14 BatteryLogRaw, 15 BatteryCondition, 16 OperationMode, 17 BatteryCapacity, 18 ProcessorTemperature, 19 MoistureDetection, 20 Monotime, 21 MicMute |
| 3 FirmwareUpdate | 0 FunctionBlockInfo, 1 State, 2 Init, 3 DataTransfer, 4 Synchronize, 5 Validate, 6 Run, 7 Reset, 8 DataTransferWithSequenceNumber, 9 DataTransferCrc, 10 SubComponentProgress, 11 Commit, 12 Abort, 13 Count, 14 Log, 15 UpdateMode, 16 DataTransferProgress |
| 4 DeviceManagement | 0 FblockInfo, 1 Connect, 2 Disconnect, 3 RemoveDevice, 4 ListDevices, 5 Info, 6 ExtendedInfo, 7 ClearDeviceList, 8 PairingMode, 9 AppAddress, 10 PrepareP2p, 11 P2pMode, 12 Routing, 13 P2pFeatures, 14 Features, 15 BoseProduct, 16 ConnectionPriority, 17 UserCarouselSelect, 18 AvailableToConnect, 19 LeAudioCheck |
| 5 AudioManagement | 0 FblockInfo, 1 Source, 2 GetAll, 3 Control, 4 Status, 5 Volume, 6 NowPlaying, 7 Latency, 8 ChannelParameter, 9 SignalClip, 10 ChannelCount, 11 ToneMatchPreset, 14 ChannelParameterCapabilities, 15 SpatialAudioMode, 16 SpatialAudioStatus, 17 SpatialAudioCalibration *(12, 13 undefined)* |
| 7 Control | 0 FblockInfo, 1 GetAll, 2 Chirp, 3 BmapRole, 4 Power, 5 FactoryDefault, 6 ShipMode, 7 ANROnly, 8 MaxSourceCurrent, 9 ChargingCaseLidStatus, 10 ClientInteraction, 11 Reset, 12 BatteryRecoveryPending, 13 PrepareToPowerOff, 14 SecondaryProcessorTest, 15 FitQuality |
| 9 Notification | 0 FblockInfo, 1 Reset, 2 ByFblock, 3 ByFunction, 4 Periodic |
| 12 HearingAssistance | 0 FblockInfo, 1 WdrcCompleteUpdate, 2 WdrcIndividualUpdates, 3 Directionality, 4 DirectionalitySettings, 5 NoiseReductionControl, 6 NoiseReductionSettings, 7 MappedSettings, 8 WdrcBandDefinitions, 9 EqBandDefinitions, 10 SubprocessorVersion, 11 Muting, 12 BoostEq, 13 Limits, 14 SubprocessorStatus, 15 MappedSettingsMode, 16 MappedSettingsOffsetMode, 17 GlobalMute, 18 AlgorithmControl, 19 LiveMetrics, 20 DoffAutoOffTime, 21 PresetManagement |
| 13 DataCollection | 0 FblockInfo, 1 GetAll, 2 Records, 5 Uid, 6 Enable, 7 OneRecord, 8 Confirmation, 9 CrashLog, 10 DeleteCrashLog, 11 DeleteAllRecords, 12 CrashLogWithDelay |
| 14 HeartRate | 0 FblockInfo, 1 GetAll, 2 HeartRate, 3 HeartRateStats, 4 SpeedAndDistance, 5 StepRateStats, 6 Calories, 7 Vo2, 8 UserInfo, 9 WorkoutInfo, 10 ReadingReliability, 11 OpticalSensorStatus, 12 PostStatus, 13 CalibrationInfo, 14 FirmwareVersion, 15 HardwareInfo |
| 16 Vpa | 0 FblockInfo, 1 GetAll, 2 SupportedVpas, 3 Wuw (wake-up word), 4 Language, 5 Trigger |
| 17 Wifi | 0 FblockInfo, 1 GetAll, 2 AvailableNetworks, 3 ConfigureNetwork, 4 GetSecurityType, 5 RemoveNetwork, 6 EncryptedConfigureNetwork, 7 SupportedSecurityTypes, 8 SoftAPConnection, 9 SupportedFrequencies |
| 18 Authentication | 0 FblockInfo, 1 GetAll, 2 RotatingPublicKey, 3 Challenge, 4 EcdhePublicKey, 5 GenerateChallenge, 6 VerifyChallengeResponse, 7 ProductIrk, 8 CondensedChallenge, 9 DeviceIdentityPublicKey, 10 FeatureLock, 11 OtpKeyType, 12 ProductName, 13 PlatformName, 14 AppIrk, 15 GenerateActivationChallenge, 16 VerifyActivationChallenge, 17 Activation, 18 CondensedCloudChallenge, 19 ValidatedDeviceIdentityKeypair, 20 Reset, 21 ExtendedChallenge, 22 GenerateFastActivationChallenge, 23 VerifyFastActivationChallengeResponse, 24 UnencryptedProductIrk, 25 PropagateProductIrk, 26 NoTokenChallenge, 27 ProductToCloudChallenge, 28 ProductToCloudChallengeVerifyResponse, 29 CloudToProductChallenge, 30 Ciphers, 31 PublicKeyWithCipher, 32 GoogleFeatureKeys, 33 GoogleFeatureKeyData |
| 20 Cloud | 0 FblockInfo, 1 GetAll, 2 Activate, 3 Synchronize, 4 UpdateState, 5 InstallUpdate, 6 UpdateProgress |
| 21 AugmentedReality | 0 FblockInfo, 1 GetAll, 2 Status |
| 22 Print | 0 FBlockInfo, 1 GetAll, 2 Output, 3 Bind, 4 Unbind, 5 Verbosity, 6 Transports, 7 Clients |
| 31 AudioModes | 0 FblockInfo, 1 GetAll, 2 Capabilities, 3 CurrentMode, 4 DefaultMode, 5 Persistence, 6 ModeConfig, 7 UserIndices, 8 Favorites, 9 Reset, 10 SettingsConfig, 11 NamesSupported |

Disagreements with NOTES.md naming: `[4.9]` is **AppAddress** (the phone's MAC as the headset sees
it, not "LocalMacAddress"); `[18.24]` is **UnencryptedProductIrk** (25 is PropagateProductIrk);
`[2.5]` is **ChargerDetect**; `[0.17]` is **ComponentId**.

---

## 3. Payload layouts (builders `…messages.packets.*`, parsers `…messages.responses.*`)

All multi-byte integers are **big-endian** (`ByteUtils.convertBytesToInt` / `convertIntToBytes`)
unless stated. "GET" below means the STATUS reply to GET; SET_GET replies with the same STATUS.

### 3.1 Settings `[1.x]`

| fn | op(s) the app uses | write payload | STATUS layout / enums |
|---|---|---|---|
| 1.2 ProductName | SET_GET | UTF-8 name, no prefix | `[flags bit0=isDefault][UTF-8…]` (scan `01`+"Bose QC Ultra 2 HP" = default name) |
| 1.3 VoicePrompts | SET_GET | `[(en<<5)|(lang&0x1F)]` + optional `[batteryLevelEnabled]` | `[b0: lang=bits0-4, en=bit5, isDefaultLang=bit6, userCanToggle=bit7][u32 supported-language bitset][b5 bit0 batteryLevelSupported][b6 bit0 batteryLevelEnabled]` (7 bytes) |
| 1.4 StandbyTimer | SET_GET | minutes ≤255 → `[m]`; else 2 bytes **little-endian** `[lo,hi]` | len<3: `[m]`; len≥3: minutes=(b2<<8)|b0, "two-byte supported" |
| 1.5 Cnc | SET_GET | `[step, enabled]` | `[numSteps][currentStep][flags bit0 enabled, bit1=0 ⇒ user can enable/disable]` |
| 1.6 Anr | SET_GET | `[mode]` | `[mode][supported-mode bitset]`; AnrMode 0 Off, 1 High, 2 Wind, 3 Low |
| 1.7 RangeControl (EQ) | SET_GET | `[value s8, id]` | n×`[min,max,cur,id]`; id 0 Bass, 1 Mid, 2 Treble, **3 LowMid, 4 HighMid** |
| 1.8 Alerts | SET_GET | `[ringtone | haptics<<1]` | same bits |
| 1.9 Buttons | GET, SET_GET | `[buttonId, eventType, mode]` | `[id][event][mode][3..6 supported-mode bitset BE][7..10 unavailable bitset BE][11..74 pairs (mode, reason)]`; reason 1 = UnavailableLEAudioActive |
| 1.10 Multipoint | SET_GET | `[0/1]` | `[bit0 enabled, bit1 supported, bit2 disableSupported]` |
| 1.11 Sidetone (UI "Self Voice") | SET_GET | `[persist, mode]` | `[bit0 persisted][mode][supported-mode bitset]`; SidetoneMode 0 Off, 1 High, 2 Medium, 3 Low |
| 1.12 SetupComplete | SET_GET | `[0/1]` | `[0/1]` |
| 1.13 ConversationMode | SET_GET (API only) | `[0/1]` | `[bit0]` |
| 1.14 CncPersistence | SET_GET | `[0/1]` | `[bit0]` |
| 1.15 CncPresets | SET_GET | `[selectedIndex, preset0..n]` | same |
| 1.16 OnHeadDetection | SET_GET | `[enabled, (autoPlay|autoAnswer<<1|autoAware<<2)]` | `[b0: bit0 enabled, bit1/2/3 = autoPlay/autoAnswer/autoTransparency *supported*][b1: bit0/1/2 = those *enabled*]` |
| 1.20 MotionInactivityAutoOff | SET_GET | `[0/1]` | `[bit0]` |
| 1.22 FlipToOff | SET_GET | `[0/1]` | `[bit0]` |
| 1.24 AutoPlayPause | SET_GET | `[0/1]` | `[bit0]` |
| 1.26 TeamsButtonMode | SET_GET | `[0 Combination / 1 Dedicated]` | same |
| 1.27 AutoAnswer | SET_GET | `[0/1]` | `[bit0]` |
| 1.28 VolumeControl | SET_GET | `[0 Disabled,1 Button,2 CapTouch,3 IMU]` | `[current][support bits]` |
| 1.29 AutoAwareMode | SET_GET | `[0/1]` | `[bit0]` |
| 1.32 AutoVolumeLevel | SET_GET | `[0/1]` | `[bit0]` |
| 1.33 Grouping | SET_GET | `[enabled, LinkingStatus 0/1/2]` | `[b0 bits][status][modes bits][variant][guid…]` |
| 1.34 DisableCaptouch | SET_GET | `[0/1]`, **1 = touch controls ON** despite the name (the app's `isEnabled = (b0 & 1) == 1`; verified on hardware 2026-10-18) | `[bit0]` |
| 1.37 LeAudioControl | SET_GET | `[0/1]` | `[bit0]` |
| 1.38 LeAudioBroadcastConfig | SET_GET | `[type]` | 0 CompatHQ 48 kHz, 1 CompatLQ 16 kHz, 2 BoseLQ 16 kHz, 3 BoseHQ 48 kHz, 4 CompatNormal 24 kHz |
| 1.39 HeadGesture | SET_GET (API only) | `[0/1]` | `[bit0]` |
| 1.41 LeaGrouping | SET_GET | `[enabled, mode 0 Ungrouped/1 Stereo/2 Party]` | `[bits][mode][modes bits][b3: bits0-6 count, bit7 flag]` |
| 1.47 LeaGroupMember | START | — | PROCESSING/STATUS/RESULT |
| 1.1 GetAll | START | — | PROCESSING, one STATUS per supported function, RESULT |

`ActionButtonMode` (complete, 0–27): 0 NotConfigured, 1 Vpa, 2 Anr, 3 BatteryLevel,
4 PlayPause, 5 IncreaseCnc, 6 DecreaseCnc, 7 ToggleWuw, 8 SwitchSourceDevice, 9 ConversationMode,
10 TrackForward, 11 TrackBack, 12 FetchNotifications, 13 WindMode, 14 Disabled,
15 ClientInteraction, 16 SpotifyGoMode, 17 ModesCarousel, **18 VolumePresetCarousel**,
19 SpatialAudioMode, 20 LineInSwitch, 21 Linking, **22 PanicLocal, 23 PanicRemote, 24 PanicBoth,
25 ThirdPartyService, 26 SmartAction, 27 Alexa**. `ConfigurableButtonId`: 0 DistalCnc,
1 Reserved, 2 Vpa, 3 RightShortcut, 4 LeftShortcut, 128 Shortcut.
`VoicePromptLanguage` per `assets/voice_prompt_languages.json`; matches notes.

### 3.2 Status `[2.x]`

| fn | layout |
|---|---|
| 2.2 BatteryLevel | 1–4 bytes: `[percent][remaining-minutes u16 BE, 0xFFFF=unknown][componentId]`; >4 bytes = repeated 4-byte records. ComponentId 0 Single, 1 RightBud, 2 LeftBud, 3 Case |
| 2.3 AuxCableDetection | `[0/1]` |
| 2.5 **ChargerDetect** | `[1 = charger connected]` |
| 2.9 InEar | `[bit0 left, bit1 right]` |
| 2.11 UnifiedCommunicationsLink | `[bit0 inPdl, bit1 linked]` (Teams dongle) |
| 2.12 Button | same layout as `[1.9]` |
| 2.16 **OperationMode** | `[0 Unknown, 1 Normal, 2 OtaOnly]` |
| 2.19 MoistureDetection | `[0/1]` |
| 2.21 **MicMute** | `[1 = true]` (class `StatusMicMuteEnabledResponse`; whether it means "muted now" or "mute feature on" is ambiguous [guess]) |
| 2.1 GetAll | START → PROCESSING, STATUS stream, RESULT |

`[2.4]` MicrophoneLevel, `[2.13–15,17,18,20]` have enum names only (no packet/parser) — not used.

### 3.3 AudioManagement `[5.x]`

| fn | op | layout |
|---|---|---|
| 5.1 Source | GET | `[supported u16 BE bitset][active][source data]`; types 0 None, 1 Bluetooth (6-byte MAC), 2 Aux, **3 Usb, 4 LeaUnicast, 5 LeaBroadcast** |
| 5.3 Control | GET → `[u8/u16 supported bitset]`; START `[value]` | 0 Stop, 1 Play, 2 Pause, 3 TrackFwd, 4 TrackBack, 5/6 FF press/release, 7/8 Rew press/release |
| 5.4 Status | GET | `[AudioStatus 0 Stop,1 Play,2 Pause,3 FF,4 Rew][trackPosition s16 BE]` |
| 5.5 Volume | GET, SET_GET `[level]` | `[max][current]` |
| 5.6 NowPlaying | START | streamed STATUS with `NowPlayingAttribute`s |
| 5.7 **Latency** | GET, SET_GET `[s16 BE]` | `[min s16][max s16][current s16]` |
| 5.8 ChannelParameter | GET `[ch, type]`, SET_GET `[ch, type, value]` | types 0 Volume … 13 SubEq (PA products) |
| 5.9 SignalClip | GET `[ch]` | 0 NoSignal, 1 Signal, 2 Clipping |
| 5.11 ToneMatchPreset | GET `[a,b]`, SET_GET 104 bytes | PA products |
| 5.15 SpatialAudioMode | GET, SET_GET `[0 off,1 room,2 head]` | |
| 5.16 SpatialAudioStatus | GET | 0 AsDesired, 1 NotAsDesired |
| 5.17 SpatialAudioCalibration | GET; START (no payload) | `[type 0 Factory/1 Manual/2 Automatic][n][n bytes]` (rest ignored by app) |

### 3.4 CallManagement `[6.x]`
No functions defined in 13.3.4 at all. The app has nothing call-specific in BMAP besides `[1.11]`,
`[1.27]`, `[1.26]`, `[2.11]`, `[0.16]`. HFP itself is only observed through Android
(`BluetoothHfpConnectionReceiver`).

### 3.5 Control `[7.x]`

| fn | op | payload |
|---|---|---|
| 7.1 GetAll | GET | supported-function bitset BE |
| 7.2 Chirp ("find my") | GET → `[bit0 active, bits1+ stop reason]`; START `[0 End,1 Begin,2 Continue]` → RESULT `[u8]` | reasons 0 NoEnd, 1 DeviceEnd, 2 TimerEnd, 3 AppEnd, 4 CradleEnd |
| 7.3 BmapRole | GET; START `[0 Master,1 Puppet]` | |
| 7.4 Power | GET; START `[0 Off,1 On]` | |
| 7.5 FactoryDefault | START, no payload | |
| 7.10 ClientInteraction | START `[event][u32 BE]` | events 0 Unavailable…4 End |
| 7.15 FitQuality | START | earbud fit test |

### 3.6 AudioModes `[31.x]`

| fn | op | layout |
|---|---|---|
| 31.1 GetAll | START | stream |
| 31.2 Capabilities | GET | `[boseModes][userModes][2][3][4][b5 flags: bit0 cnc, bit1 autoCnc, bit2 spatial head+room, bit3 anrWindToggle, bit4 userFavorites, bit5 ancToggle][b6 minFavorites]` |
| 31.3 CurrentMode | GET; START `[index, prompt 0/1]` | `[index]` |
| 31.4 DefaultMode | GET; SET_GET `[index]` | `[index]` (absent on fw 10.12, §9) |
| 31.5 Persistence | GET; SET_GET `[0/1]` | `[0/1]` |
| 31.6 ModeConfig | GET `[index]`; SET_GET 40 bytes; START (dump all) | see below |
| 31.7 UserIndices | GET; SET_GET `[i…]` | one byte per index |
| 31.8 Favorites | GET; SET_GET | `[totalModes][ceil(total/8) bytes, BE bitset]` |
| 31.9 Reset | START `[modeIndex]` | RESULT, no payload — **resets one mode slot** |
| 31.10 SettingsConfig | GET; SET_GET `[cnc, autoCnc, spatial, wind, anc]` | same 5 bytes |
| 31.11 NamesSupported | GET | bitset (byte *i*, bit *j*, LSB-first, ≤ 37) of `AudioModesPrompt` ids |

ModeConfig STATUS (48 bytes, `AudioModesModeConfigResponse`): 0 index; 1–2 prompt; **3 userConfigurable;
4 userConfigured; 5 favorite**; 6–37 name (NUL-terminated); 38–40 *not read by the app*;
**41 mutability flags** (bit0 cncMutable, bit1 autoCncMutable, bit2 spatialMutable,
bit3 windToggleMutable, bit4 ancToggleMutable; `0x1d` = all but autoCnc); 42 cnc; 43 autoCnc;
44 spatial (if len≥45); **45 not read by the app; 46 windBlock (if len≥47); 47 ancToggle (if len≥48)**.
→ **Disagreement**: NOTES.md and `protocol/.../qcultra2/AudioModes.kt` read wind at 45 and call 46
unknown; the app reads wind at **46**. Verify with a GET `[31.6, idx]` before/after toggling wind
on a custom slot.
40-byte SET_GET (`FBlockAudioModesKt.createAudioModesConfigSetGetPayload`): as notes, but the tail
is variable: spatial is appended only if known, wind only if known (spatial forced to 0 when only
wind is given), anc only if known.
`AudioModesPrompt` ids: 0 None, 1 Quiet, 2 Aware, 3 Transparent, 4 Transparency, 5 Masking,
6 Comfort, 7 Commute, 8 Outdoor, 9 Workout, 10 Home, 11 Work, 12 Music, 13 Focus, 14 Relax,
15 Flight, 16 Airport, 17 Driving, 18 Training, 19 Gym, 20 Run, 21 Walk, 22 Hike, 23 Talk,
24 Call, 25 Whisper, 26 Hearing, 27 Learn, 28 Podcast, 29 Audiobook, 30 Calm, 31 Sleep,
32 Meditate, 33 Yoga, 34 Immersion, 35 Stereo, 36 Cinema.

### 3.7 ProductInfo `[0.x]`

| fn | layout |
|---|---|
| 0.1 BmapVersion | ASCII "x.y.z" — the app branches on `< 1.2.0` (fw 10.12 reports **1.2.0**) |
| 0.2 AllFblocks | BE bitset of block ids (drives which parsers load and which blocks get notifications) |
| 0.3 ProductIdVariants | `[pid u16 BE][variant]`; wolverine pid 0x4082 (`BoseProductId.Wolverine`, BLE id 112), variants 1 Black, 2 WhiteSmoke, 3 DriftwoodSand, 4 MidnightViolet, 5 DesertGold, 6 ThomBrowneCollab, 7 MeltedChocolate, 8 OliveGreen, 9 CherryChocolate |
| 0.4 GetAllFunctions | START → PROCESSING, STATUS stream |
| 0.11 ComponentDevices | n×6-byte MACs |
| 0.12 Guid | 16 bytes |
| 0.16 UnifiedCommunicationsConfig | `[1 = configured]` |
| 0.17 **ComponentId** | `[componentId]` or `[componentId][MAC 6]` |
| 0.18 EarbudMode | role 0 Unknown, 1 Master, 2 Puppet |
| 0.22 BondedGroupSupport | `[type 0 Unsupported…3 Required][?][host bits][?][accessory bits]` |
| 0.23 **LeAudioSupported** | `[1 = yes]` |

### 3.8 DeviceManagement `[4.x]`

| fn | op | layout |
|---|---|---|
| 4.1 Connect | GET → `[b0 bit5 MusicSamePid, bit6 MusicSameType][b1>0 ⇒ DWS supported][b2 unparsed]`; START `[0x00 + MAC]`, `[0x01 + name]`, or `[(productType<<7)|0x10, MAC, localMAC]` (13 bytes) | |
| 4.2 Disconnect / 4.3 RemoveDevice | START `[MAC]` | |
| 4.4 ListDevices | GET | `[connected bitmask][n×MAC]` (bit *i* = *i*-th MAC connected) |
| 4.5 **Info** | GET `[MAC]` | `[MAC 6][flags: bit0 connected, bit1 isLocalDevice (this phone), bit2 isBoseProduct, bit3 isComponent, bit7 productType][b7,b8: Bose ⇒ pid BE, else major/minor CoD][Bose ⇒ b9 variant, name from 10; else name from 9]` |
| 4.6 ExtendedInfo | GET `[MAC]` | `[MAC][paired A2DP,HFP,AVRCP,SPP,iAP bits][connected same bits][8][9][10 paired LE unicast bit0/broadcast bit1][11 connected LE unicast/broadcast]` |
| 4.7 ClearDeviceList | START | |
| 4.8 PairingMode | GET; START `[0/1]` | |
| 4.9 **AppAddress** | GET | 6-byte MAC of the phone as seen by the headset |
| 4.11 P2pMode | GET; SET_GET `[0 None…5 VoiceDifferentType]` | |
| 4.12 Routing | GET; START `[0x82, MAC]` | as notes |
| 4.14 **Features** | GET | `[bit0 cTKD (cross-transport key derivation), bit1 deviceCarousel, bit2 sourceBargeIn]` |
| 4.18 **AvailableToConnect** | GET | `[0 NotAvailable, 1 Available, 2 Unknown]` |
| 4.19 **LeAudioCheck** | START → STATUS/RESULT `[bit0 supported, bit1 preferred, bit2 prohibited]` | |

### 3.9 Notification `[9.x]`
`[9.2]` ByFblock GET → BE fblock bitset; SET_GET `[bitmask 0 Overwrite/1 Enable/2 Disable]` +
BE fblock bitset (`ByteUtils.convertIntToBytes(bits, byteLen)`; 2 zero bytes if empty).
`[9.3]` ByFunction GET `[fblock]`; SET_GET `[bitmask][fblock][BE function bitset]`.
`[9.4]` Periodic SET_GET `[period u16 BE][fblock][function bitset]`. `[9.1]` Reset START.
On connect the app sends `[9.2]` SET_GET Enable for the intersection of `[0.2]` with
{0,1,2,4,5,7,9,13,16,17,18,31} (`BluetoothCapableConnectionService` `defpackage.C6981Xl0`,
set `defpackage.C15166lV8`). For wolverine: **`09 02 02 05 01 80 04 22 B7`**.

### 3.10 Other blocks ≥ 8
* 8 Debug, 19 Experimental: no functions in the app.
* 12 HearingAssistance, 14 HeartRate, 16 Vpa, 17 Wifi, 20 Cloud, 21 AR: implemented for other
  products; not reported by wolverine (`[0.2]`).
* 13 DataCollection: START Records/OneRecord `[a,b]`/CrashLog `[a,b]`/CrashLogWithDelay
  `[a,b,delay]`/Confirmation `[blob]`/DeleteCrashLog `[blob]`; Enable GET/SET/SET_GET `[0/1]`;
  GetAll GET → BE function bitset.
* 22 Print (debug console): Bind/Unbind/Verbosity **SET_GET UTF-8 string**; Output START → RESULT
  `[u8]`; Transports/Clients GET → string. Interface methods exist (`setBind`, `setVerbosity`,
  `startOutput`, `getTransports`, `getClients`) but have **no callers** in the app.

---

## 4. What the app considers supported for wolverine

There is no static wolverine capability table. The flow is: `[0.2]` → load parsers for those
blocks → `[x.1]` GetAll / individual GETs → each answered GET fills a relay on
`ConnectedBoseDevice` → `BluetoothCapabilities` (`C3810Lk0`) maps relays to capability ids
(e.g. sidetone → 30518, cnc → 30512, LE Audio control → 30541, audio latency → 31305) →
UI shows only those. Version gate: for `BmapVersion < 1.2.0` the app assumes all AudioManagement
capabilities; fw 10.12 reports 1.2.0, so each one now needs a reply.
Product-specific bits that do exist: colour variant → artwork; product-tour strings; the
whitelist `BmapDevicesModule.provideSupportedBmapDevices` (contains Wolverine);
`LeAudioDebugViewModel` shared-pref `KEY_SHARED_PREFS_LE_AUDIO_DEBUG` (debug switch to reveal LE
features); cloud "fw feature flags" (`FwFeatureFlagsActivity`, `/featureFlag/` REST on Wi-Fi
speakers only — not BMAP, not relevant here); a hidden app menu revealed by repeated taps
(`UserAndAppSettingsItems.featureFlagReveal`, debug/test sections).
Wolverine's actual list = §9.

---

## 5. Authentication (block 18): what is really locked

Call sites in 13.3.4 (`BmapInterfaceImplementation` → app):
* Used: `[18.8]` CondensedChallenge START (setup, `BluetoothSetupExecutor` `DC0`) → challenge,
  GUID, signature forwarded to the Bose account backend as "SimpleDeviceChallenge"
  (product ownership); `[18.17]` Activation GET (activation state 0 Reserved, 1 NotActivated,
  2 Activating, 3 Activated, 4 Failed); `[18.24]` UnencryptedProductIrk GET; `[18.11]`, `[18.13]`
  GETs; `[18.30/31]` ciphers (Wi-Fi credential encryption).
* Defined but **never called**: `[18.2]` RotatingPublicKey, `[18.3]`, `[18.4]` EcdhePublicKey,
  `[18.5/6]`, `[18.15/16]`, `[18.20]` Reset, `[18.21]`, `[18.22/23]`, and **no packet class at
  all** for `[18.9]`, `[18.10]` FeatureLock, `[18.19]`, `[18.25–29]`, `[18.32/33]`.
* `sendCommandForResponse` wraps nothing: no signing, encryption or session token is added to
  ordinary packets.

Conclusion [inf]: nothing the app does over BMAP requires a prior auth handshake. What returns
error 5 does so because that operator is not implemented for that function. What the device
may lock is unknown and invisible from the app; the candidates are functions answering the
non-app **error 24** (§9) and `[18.10]` FeatureLock (`FeatureLockFailed/UnlockFailed` errors exist,
Google "feature keys" `[18.32/33]` too — likely Google Fast Pair / Find Hub keys [guess]).

---

## 6. Microphone, call and voice controls

| Control | Address | Payload / values | App UI | wolverine fw 10.12 [scan] |
|---|---|---|---|---|
| Self Voice (sidetone) | `[1.11]` SET_GET `[persist, mode]` | Off/High/Medium/Low (0/1/2/3); UI says it only works when Noise Control is on | Yes ("Self Voice") | **present**, `01 02 0f` = persisted, Medium, all 4 supported |
| Conversation mode | `[1.13]` SET_GET `[0/1]`; button action 9 | | No (API only; capability 30520 added blindly, no screen) | **absent** |
| Auto-aware (aware on talk) | `[1.29]` SET_GET `[0/1]` | | Yes if present | absent |
| On-head detection incl. auto-transparency | `[1.16]` SET_GET | see §3.1 | Yes if present | absent |
| Wind block | `[31.10]` byte 3; ModeConfig byte 46 [obs]/45 [notes]; `[1.6]` ANR mode 2 Wind on older products; button action 13 | | Yes ("Wind Block") | via `[31.10]` only (`[1.6]` absent) |
| ANC on/off | `[31.10]` byte 4 | | Yes | present |
| Auto-answer calls | `[1.27]` SET_GET | | Yes | present `01` |
| Teams button mode | `[1.26]` SET_GET | 0 Combination, 1 Dedicated | Yes if present | absent |
| UC (Teams) dongle link | `[0.16]`, `[2.11]` GET | | Info | absent |
| Mic mute state | `[2.21]` GET | `[1 = true]` | Not shown | **present `01`** |
| Microphone level | `[2.4]` | enum name only | No | absent |
| Voice assistant / wake word | `[16.x]`, `[1.9]` button Vpa | | Yes | block 16 absent; shortcut Vpa supported |
| Hearing assistance directionality / noise reduction | `[12.3–6]` | beamforming, NR | Hear-aid products | absent |
| Self-voice in LE Audio | — | LE Audio disables VA and Spotify shortcuts (`ButtonUnavailableReason 1`) | | — |

Nothing in the app exposes mic gain, beamforming, call-uplink EQ or "voice pickup" for wolverine.

---

## 7. Latency, codec, LE Audio (coordinator addendum)

**Latency / game mode.** No "game/low-latency mode" exists in the enums or strings. The only
latency control is `[5.7]` AudioManagement Latency (§3.3). The app parses it
(`AudioManagementLatencyResponse` → `LatencyLevel(min,max,current)`), raises capability 31305
"audio_latency", and has `setLatency(short)`, but **no UI or code path ever calls get/setLatency**.
Scan: `0000 0208 00d2` (0..520, current 210); bosectl captures show the current value drifting
0xD7–0xDD across unrelated setting changes, so it may be a live measurement rather than a setpoint
[inf]. `[5.13]` (not in the app) `00d6 0000 0208 0000 0208` looks like a sibling latency record
`[cur][min][max][min][max]` [guess]. No codec preference, A/V sync or lip-sync BMAP function exists;
"AV sync" is only a Wi-Fi soundbar capability. Immersive Audio = spatial byte in `[31.10]`
(0 off / 1 room / 2 head) and `[5.15]` (absent on fw 10.12); head-tracking calibration `[5.17]`.

**LE Audio.**
* Detection: BLE advertisement flag "BLE audio supported" (`ScannedBoseDevice`), `[0.23]`
  LeAudioSupported, `[4.19]` LeAudioCheck START (supported / preferred / prohibited),
  `[4.6]` ExtendedInfo LE unicast/broadcast paired/connected bits, `[5.1]` sources 4/5.
* Toggle: `[1.37]` LeAudioControl SET_GET `[0/1]` (`LeAudioToggleViewModel` `C18540qg4`, shown
  only when capability 30541 is present). UI strings: beta feature; the product **restarts and
  powers back on** after toggling; VA and Spotify shortcuts and multipoint may not work over LE.
* Broadcast quality: `[1.38]` (§3.1); LE grouping/"party": `[1.41]`, `[1.47]`.
* Android APIs: only `BluetoothLeAudio.getConnectedDevices()` via `getProfileProxy(…, 22)`
  (`LeaConnectionStatusHelper` `C21785vg4`) to read state. No `BluetoothLeBroadcast`/`BASS`/CSIP
  calls.
* Transport choice (`BluetoothDeviceConnection` `C3347Jp0`): if the advert says BLE audio, the app
  connects BLE first (BMAP over LE CoC), runs `[4.19]`; if supported AND preferred it stays on
  LE CoC (no SPP); otherwise switches to SPP. Also OTA keeps LE when preferred. There is a fixed
  1 s delay after BLE connect; no special retry loop beyond the general reconnect logic [obs].
* wolverine fw 10.12 [scan]: `[0.23]=01`, `[1.37]=01` (LE Audio ON), `[1.38]` absent,
  `[1.41]` absent, `[4.19]` present (OP_NOT_SUPPORTED for GET, i.e. START-only). The CLAUDE.md
  note that the Galaxy only negotiates AAC/SBC is consistent with LE Audio on but not in use.

---

## 8. Hidden / unexposed functions

Defined and fully implemented in the library, but with **no caller in the app**:
`setConversationMode/getConversationMode` [1.13], `setHeadGestureEnabled` [1.39], `setAnr` [1.6],
`setAlerts` [1.8], `setDefaultAudioMode/getDefaultAudioMode` [31.4], `setModesUserIndices` [31.7],
`get/setLatency` [5.7], `startChirp` [7.2], `setPowerState` [7.4], `setBmapRole` [7.3],
`getInEarStatus` [2.9], `sendGetMicMute` [2.21], `sendGetOperationModeStatus` [2.16],
`getRouting/startRouting` [4.12], `setGetP2PMode` [4.11], `getSupportedMusicShareModes` [4.1],
all Print `[22.x]` (debug log console), `startAuthReset` [18.20], all HearingAssistance setters.
Developer surfaces: `DebugLeAudioActivity` + `KEY_SHARED_PREFS_LE_AUDIO_DEBUG`, `FwFeatureFlagsActivity`
(Wi-Fi products), `UserAndAppSettingsItems` debug/test/feature-flag sections, an asset
`mock_firmware_update.dfu`.

---

## 9. Live sweep fw 10.12.12 decoded with the app's parsers

Source: `captures-galaxy/deep-scan-fw10.12-2026-10-17.txt` (GET 0–63 × 0–63). Status meanings:
FUNC_NOT_SUPPORTED = absent; **OP_NOT_SUPPORTED = function exists, GET not allowed** (use START or
SET_GET); INVALID_DATA = exists, GET needs a payload; error 24 = unknown to the app.

`[0.2] 87cc23ff` → blocks **0–7, 8 Debug, 9 Notification, 13 DataCollection, 18 Authentication,
19 Experimental, 22 Print, 23, 24, 25, 26, 31 AudioModes** — exactly the blocks that answered.

| addr | raw | decoded |
|---|---|---|
| 0.0 / 0.1 | "1.1.0" / "1.2.0" | fblock 1.1.0, **BMAP 1.2.0** |
| 0.3 | 408201 | pid 0x4082 Wolverine, variant 1 Black |
| 0.5 | "10.12.12+ga927c9a" | firmware |
| 0.12 | 16 B | product GUID |
| 0.15 | "Bose QC Ultra 2 HP" | OriginalProductName |
| 0.17 | 00 + MAC | ComponentId Single + MAC |
| 0.23 | 01 | LE Audio supported (fw 8.2.20 capture: 00) |
| 1.2 | 01 + name | isDefault=1, "Bose QC Ultra 2 HP" |
| 1.3 | 41 00008102 00 00 | US English, prompts **off**, default lang, user can't toggle; battery prompt unsupported |
| 1.5 | 0b0a03 | 11 steps, current 10, enabled |
| 1.7 | …0300 …0001 …0202 | bass +3, mid 0, treble +2 |
| 1.9 | 80 09 13 00094002 00000000 | Shortcut, long-press → **19 SpatialAudioMode**; offered {1 Vpa, 14 Disabled, 16 SpotifyGo, 19 SpatialAudioMode}; none unavailable |
| 1.10 | 07 | multipoint enabled, supported, disable supported |
| 1.11 | 01020f | Self Voice Medium, persisted, Off/High/Med/Low |
| 1.12 | 00 | setup not complete (Bose app never finished setup) |
| 1.24 / 1.27 | 01 / 01 | auto play-pause on, auto-answer on |
| 1.34 | 01 | **Touch controls ON** (verified 2026-10-18: `00` left the capacitive strip dead, no volume swipes and no touch-and-hold Shortcut; the Bose app showed touch off, and turning it on restored `01`) |
| 1.37 | 01 | **LE Audio enabled** |
| absent | 1.4, 1.6, 1.8, 1.13–1.16, 1.20, 1.22, 1.26, 1.28, 1.29, 1.32, 1.33, 1.38, 1.39, 1.41 | standby timer, ANR, alerts, conversation mode, CNC presets/persistence, on-head, auto-off, flip, Teams, volume-control, auto-aware, auto-volume, grouping, broadcast cfg, head gesture, LEA grouping |
| 2.2 | 5affff00 | 90 %, remaining unknown, Single |
| 2.5 | 00 | **ChargerDetect**: not on charger |
| 2.16 | 01 | **OperationMode Normal** |
| 2.21 | 01 | **MicMute = true** (see §3.2) |
| 2.18 | OPNS | ProcessorTemperature exists (no GET) |
| 3.1 | 01 | FW update state Idle |
| 3.4 | 00 00000000 "0.0.0" | Synchronize: offset 0, pending version 0.0.0 |
| 3.6 / 3.7 / 3.15 / 3.16 | 0001 / 00 / 01 / 00 | Run, Reset NoReset, UpdateMode, transfer progress 0 |
| 4.1 | 000003 | no music share, no DWS, b2=3 unparsed |
| 4.4 | 03 + 2 MACs | 2 paired, both connected |
| 4.8 | 00 | not pairing |
| 4.9 | MAC | **AppAddress** = phone MAC |
| 4.14 | 01 | **Features: cTKD only** |
| 4.18 | 01 | **AvailableToConnect: Available** |
| 4.19 | OPNS | LeAudioCheck exists (START) |
| 4.12 | absent for GET | routing GET gone on 10.12 (START untested here) |
| 5.1 | 000f 01 MAC | sources {None,BT,Aux,USB}; active Bluetooth |
| 5.3 | 1f | controls Stop/Play/Pause/Next/Prev |
| 5.4 | 01ffff | **Playing**, position unknown |
| 5.5 | 1f16 | **volume 22 of 31** |
| 5.6 | OPNS | NowPlaying exists (START) |
| 5.7 | 0000 0208 00d2 | **Latency** min 0, max 520, current 210 |
| 5.13 | 00d6 0000 0208 0000 0208 | unknown to app; latency-like [guess] |
| 5.17 | 00 06 … | SpatialAudioCalibration: Factory, 6 data bytes (+ unparsed tail) |
| 6.0 / 6.4 | "1.1.0" / INVALID_DATA | CallManagement has one function 6.4 that needs a payload; unnamed in the app |
| 7.1 | 0873 | Control functions {0,1,4,5,6,11} = FblockInfo, GetAll, Power, FactoryDefault, ShipMode, Reset |
| 7.4 | 01 | power on |
| 8.7 / 8.8 | 04 / 000000000100000000 | Debug, unnamed in the app (8.8 was 0401… on fw 8.2.20); 8.0, 8.2, 8.9, 8.21, 8.27, 8.34, 8.35, 8.46 → error 24 |
| 9.2 | 00000000 | **no notification blocks enabled** |
| 9.3 | INVALID_DATA | ByFunction needs `[fblock]` |
| 13.1 | ff83 | DataCollection functions {0,1,7–15}; 13.13=01, 13.14=00 (ids 13/14 unknown to app) |
| 18.1 | 0339083e07 | **{0,1,2,9,10,11,12,13,19,24,27,28,29,32,33}** (big-endian; NOTES.md list is wrong) |
| 18.9 / 18.11 / 18.12 / 18.13 / 18.24 | PEM / 03 / "wolverine" / "OTG-QCC-384" / 16 B | DeviceIdentityPublicKey, OTP Prod, product, platform, UnencryptedProductIrk |
| 18.10 | INVALID_DATA | FeatureLock exists, needs payload |
| 19.43 | INVALID_DATA | Experimental 43, unnamed |
| 22.x | 0,6,7 → err 24; 3,4,5 OPNS | **Print debug console present**: Bind/Unbind/Verbosity exist (SET_GET); FblockInfo/Transports/Clients locked (24) |
| 23.x | 24 fn ids → err 24; 12,17,28,31,37,40,43 OPNS | unknown block (not in app) |
| 24.x | 0,5,10,12 → 24; 4,9 OPNS; **24.13 STATUS 000000000100000000** | unknown; 24.13 equals `[8.8]` byte-for-byte |
| 25.x | 0,2,5,6,9,17–19,62 → 24 | unknown |
| 26.x | 29 fn ids → 24; 12–18,24,29,36 OPNS | unknown |
| 31.2 | 04 07 000000 7f 02 | 4 Bose + 7 user modes; cnc, autoCnc, spatial, wind, favourites, ancToggle; min 2 favourites |
| 31.3 | 01 | current mode 1 (Aware) |
| 31.8 | 0b 00 37 | 11 modes, favourites {0,1,2,4,5} |
| 31.10 | 0a 01 00 00 01 | cnc 10, **autoCnc 1**, spatial off, wind off, anc on |
| 31.11 | 1fffffffff | names supported (per app parse) {0–4, 8–36} [inf] |
| absent | 31.4, 31.5, 31.7 | DefaultMode, Persistence, UserIndices not on 10.12 |
| 31.1, 31.9 | OPNS | GetAll / Reset exist (START) |

Error 24 hypothesis [guess]: a post-13.3.4 code for "requires secure/authenticated session or
debug unlock" — it hits FblockInfo of the debug-ish blocks (8, 22–26) and never ordinary
settings.

---

## 10. Top feature candidates for Earshot (user value × feasibility, no auth)

| # | Feature | BMAP | Present on fw 10.12 [scan] |
|---|---|---|---|
| 1 | **Live state push**: enable notifications at connect so button presses, mode/ANC changes, battery, volume and source changes arrive without polling | `[9.2]` SET_GET `01 80 04 22 B7` | **yes** (`[9.2]` answers; currently `00000000`) |
| 2 | **Self Voice** level (Off/Low/Med/High) for calls | `[1.11]` SET_GET `[1, mode]` (mode 1 High, 2 Med, 3 Low) | **yes** |
| 3 | **LE Audio on/off** (warn: headset reboots; VA/Spotify shortcuts off under LE) + show LE support/preferred | `[1.37]` SET_GET; `[0.23]` GET; `[4.19]` START | **yes** (`[1.37]=01`) |
| 4 | **Shortcut button** editor with the device-reported options (Vpa, Disabled, Spotify Tap, Immersive Audio) using the big-endian mask | `[1.9]` SET_GET `[0x80, 9, mode]` | **yes** |
| 5 | **Touch controls on/off** (capacitive volume strip) | `[1.34]` SET_GET `[0/1]` | **yes** (`01` now) |
| 6 | **Latency readout / experimental low-latency setpoint** | `[5.7]` GET; SET_GET `[s16 BE]` (untested; app never writes it) | **yes** (GET); write unverified |
| 7 | **Now-playing / transport / volume** panel (play, pause, next, prev; absolute volume 0–31) | `[5.4]`, `[5.5]` GET + SET_GET `[level]`, `[5.3]` START, `[5.6]` START | **yes** |
| 8 | **Per-slot mode reset** and favourites editor | `[31.9]` START `[idx]`; `[31.8]` SET_GET | **yes** (31.9 OPNS = exists) |
| 9 | **Connected-devices view** with phone/Bose flags, LE/Classic profile bits and "available to connect" | `[4.4]`, `[4.5]`/`[4.6]` GET `[MAC]`, `[4.9]`, `[4.18]` | **yes** |
| 10 | **Mic-mute / charger / operation-mode indicators**; Print debug-log console in the Lab (Bind/Verbosity strings, then watch output) | `[2.21]`, `[2.5]`, `[2.16]` GET; `[22.3]`/`[22.5]` SET_GET | **yes** (Print partially err 24) |

Not available on this firmware (do not build): conversation mode `[1.13]`, auto-aware `[1.29]`,
on-head detection `[1.16]`, standby timer `[1.4]`, default mode `[31.4]`, head gesture `[1.39]`,
chirp/find-my `[7.2]`, fit test `[7.15]`, spatial mode `[5.15]`, broadcast quality `[1.38]`.

Open verification items: ModeConfig wind byte (45 vs 46); meaning of `[2.21]=01`. (`[1.34]=01` = touch on, settled 2026-10-18.)
on real hardware; whether `[5.7]` SET_GET is accepted and audible; error 24 semantics; `[4.12]`
routing on 10.12 (GET now FUNC_NOT_SUPPORTED).
