# Bluetooth SIG standard control surfaces usable by a normal Android app (Oct 2026)

Scope: standard (non-vendor) Bluetooth services and profiles that a third-party app (only `BLUETOOTH_CONNECT`/`BLUETOOTH_SCAN`, no `BLUETOOTH_PRIVILEGED`, no system signature) can use through its own GATT client or public APIs to control any compliant headset. Android-side facts were checked in AOSP source on 2026-10-20 (gitiles `?format=TEXT` downloads of the `android12-release` … `android17-release` and `main` branches). Line numbers are as fetched that day and will drift on `main`.

Licences: AOSP (`platform/packages/modules/Bluetooth`, `frameworks/base`, `frameworks/native`) is **Apache-2.0** (the module root has `MODULE_LICENSE_APACHE2`). Zephyr headers used for UUID and enum values are **Apache-2.0**. No GPL/AGPL or unlicensed code was used. The short code quotes below are Apache-2.0 with attribution, so they are fine to quote in an MIT project's docs.

---

## Q1. LE Audio and other GATT services on the headset: can an app's own GATT client read and write them?

### Takeaway
No for volume, AICS, hearing-aid presets, CSIS, PACS, ASCS and BASS. Since **Android 13**, the GATT service tags every handle of VCS, VOCS, AICS, PACS, ASCS, BASS, HAS and CSIS as "restricted". An app can still *see* these services in discovery, but read, write, notify and descriptor access require `BLUETOOTH_PRIVILEGED`. Apps targeting Android 13+ get a `SecurityException`; older targets fail silently. The list is unchanged through **Android 17** (`android17-release`), except that Android 15 QPR1 added the Android head-tracker service and Android 17 conditionally adds Apple ANCS.

Not restricted, so an app *can* use them over its own GATT connection: **Microphone Control Service (MICS, 0x184D)** and its Mute characteristic, **Battery Service (0x180F)**, **Device Information (0x180A)**, TMAS, GMAS and CAS. HID (0x1812) is visible, but its four report/map/control characteristics are restricted.

### Cited Findings
**Restricted list, Android 13 → 17**
- Android 13 `GattService.java` declares the restricted LE Audio list. The verbatim (Apache-2.0) definition is:
  ```java
  private static final UUID[] LE_AUDIO_SERVICE_UUIDS = {
      UUID.fromString("00001844-0000-1000-8000-00805F9B34FB"), // VCS
      UUID.fromString("00001845-0000-1000-8000-00805F9B34FB"), // VOCS
      UUID.fromString("00001843-0000-1000-8000-00805F9B34FB"), // AICS
      UUID.fromString("00001850-0000-1000-8000-00805F9B34FB"), // PACS
      UUID.fromString("0000184E-0000-1000-8000-00805F9B34FB"), // ASCS
      UUID.fromString("0000184F-0000-1000-8000-00805F9B34FB"), // BASS
      UUID.fromString("00001854-0000-1000-8000-00805F9B34FB"), // HAP
      UUID.fromString("00001846-0000-1000-8000-00805F9B34FB"), // CSIS
  };
  ```
  The `isRestrictedSrvcUuid()` check combines `isFidoSrvcUuid || isAndroidTvRemoteSrvcUuid || isLeAudioSrvcUuid` (lines 168–177 and 4441–4445). — [AOSP android13-release GattService.java](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/android13-release/android/app/src/com/android/bluetooth/gatt/GattService.java#168)
- **Android 12** restricted only FIDO (`0xFFFD`) and the Android TV remote service (`AB5E0001-…`), with `isRestrictedSrvc = isFidoSrvcUuid(el.uuid) || isAndroidTvRemoteSrvcUuid(el.uuid)`. So the LE Audio restriction arrived with Android 13. — [AOSP android12-release GattService.java (packages/apps/Bluetooth)](https://android.googlesource.com/platform/packages/apps/Bluetooth/+/refs/heads/android12-release/src/com/android/bluetooth/gatt/GattService.java#1559)
- The same eight-UUID list appears in android14-release, android15-release, android16-release and `main`. — [android14](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/android14-release/android/app/src/com/android/bluetooth/gatt/GattService.java#179), [android15](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/android15-release/android/app/src/com/android/bluetooth/gatt/GattService.java#119), [android16](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/android16-release/android/app/src/com/android/bluetooth/gatt/GattService.java#111), [main](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/gatt/GattService.java#113)
- **Android 15 QPR1 and later** add the Android head-tracker LE service (`109b862f-50e3-45cc-8ea1-ac62de4846d1`, `HidHostService.ANDROID_HEADTRACKER_UUID`) to the restricted set: `isFidoSrvcUuid || isAndroidTvRemoteSrvcUuid || isLeAudioSrvcUuid || isAndroidHeadtrackerSrvcUuid`. It is absent in `android15-release` and present in `android15-qpr1-release`, `android15-qpr2-release`, `android16-release`, `android16-qpr1/qpr2-release` and `main`. — [main GattService.java#2928](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/gatt/GattService.java#2928); [android15-release (no head tracker)](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/android15-release/android/app/src/com/android/bluetooth/gatt/GattService.java#3395); [HidHostService.java#68](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/hid/HidHostService.java#68)
- **Android 17** (`android17-release`) moves the lists into `GattUtil.kt` and keeps the same LE Audio UUIDs. It adds Apple Notification Center Service `7905F431-…` as restricted only when the user has not allowed messaging access: `isFidoSrvcUuid || isAndroidTvRemoteSrvcUuid || isLeAudioSrvcUuid || isAndroidHeadtrackerSrvcUuid || (isAppleNotificationCenterSrvcUuid(uuid) && shouldBlockMessaging(device))`. — [android17 GattUtil.kt#47](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/android17-release/android/app/src/com/android/bluetooth/gatt/GattUtil.kt#47); [android17 GattService.java#1654](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/android17-release/android/app/src/com/android/bluetooth/gatt/GattService.java#1654)

**How the restriction is enforced**
- Restricted services are **not hidden**. In the GATT DB callback, every primary *and secondary* service is added to the list returned to the app (`dbOut.add(currSrvc)`). A service whose UUID is restricted has its id and all its characteristic and descriptor ids put in `mRestrictedHandles` for that connection. For HID, only the four characteristic UUIDs `2A4A/2A4B/2A4C/2A4D` are restricted. — [main GattService.java#1132–1205](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/gatt/GattService.java#1132)
- `permissionCheck(connId, handle)` calls `enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, …)` when the handle is restricted. — [main GattService.java#285](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/gatt/GattService.java#285)
- `readCharacteristic`, `readDescriptor`, `registerForNotification` and `onNotify` catch the `SecurityException`. They rethrow it only when `checkCallerTargetSdk(…, TIRAMISU)` holds, with the comment "Only throws on apps with target SDK T+ as this old API did not throw prior to T"; otherwise they log "permission check failed!" and drop the operation. `writeCharacteristic` calls `permissionCheck` without a catch. — [main GattService.java#1826](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/gatt/GattService.java#1826), [#1917](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/gatt/GattService.java#1917), [#2059](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/gatt/GattService.java#2059), [#1238](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/gatt/GattService.java#1238)
- Android 17 keeps this behaviour in `GattServiceBinder.kt`. `onGattThreadAndEnforcePrivilegedOnBinderIfNeeded()` checks `restrictedHandles[connId]?.contains(handle)` against `checkCallerHasPrivilegedPermission()`, and the same targetSdk-T rethrow rule applies. — [android17 GattServiceBinder.kt#665](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/android17-release/android/app/src/com/android/bluetooth/gatt/GattServiceBinder.kt#665), [#228](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/android17-release/android/app/src/com/android/bluetooth/gatt/GattServiceBinder.kt#228)
- The hidden `BluetoothGatt.readUsingCharacteristicUuid` path checks only the HID characteristic UUIDs (`permissionCheck(UUID)`), not restricted handles. The method is `@hide`. — [main GattService.java#276](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/gatt/GattService.java#276); [BluetoothGatt.java#1570](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/framework/java/android/bluetooth/BluetoothGatt.java#1570); [android17 GattServiceBinder.kt#240](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/android17-release/android/app/src/com/android/bluetooth/gatt/GattServiceBinder.kt#240)

**What the system stack implements**
- AOSP's Bluetooth app ships these profile packages: `bas`, `bass_client`, `csip`, `hap`, `hearingaid`, `hfp`, `le_audio`, `mcp`, `tbs`, `vc` and others. There is **no MICP/MICS client**. The native `system/bta` has `aics`, `csis`, `gmap`, `has`, `le_audio`, `vc`, `ras`, but no MICS. — [main android/app/src/com/android/bluetooth/](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/), [main system/bta/](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/system/bta/)
- The native VC client defines these UUIDs. VCS 0x1844: Volume State 0x2B7D, Control Point 0x2B7E, Flags 0x2B7F. VOCS 0x1845: 0x2B80–0x2B83. AICS 0x1843: State 0x2B77, Gain Setting Properties 0x2B78, Input Type 0x2B79, Status 0x2B7A, Control Point 0x2B7B, Description 0x2B7C. — [main system/bta/vc/types.h#57](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/system/bta/vc/types.h#57)

**Standard UUIDs and values (Zephyr, Apache-2.0)**
- MICS = 0x184D, MICS Mute characteristic = 0x2BC3, BAS = 0x180F, Battery Level = 0x2A19, DIS = 0x180A, HIDS = 0x1812, CAS = 0x1853, HAS = 0x1854, TMAS = 0x1855, GMAS = 0x1858. HAS characteristics: Hearing Aid Features 0x2BDA, Preset Control Point 0x2BDB, Active Preset Index 0x2BDC. — [Zephyr include/zephyr/bluetooth/uuid.h](https://raw.githubusercontent.com/zephyrproject-rtos/zephyr/main/include/zephyr/bluetooth/uuid.h)
- MICP mute values are `0x00` unmuted, `0x01` muted and `0x02` disabled. Error `0x80` means mute disabled. — [Zephyr include/zephyr/bluetooth/audio/micp.h](https://raw.githubusercontent.com/zephyrproject-rtos/zephyr/main/include/zephyr/bluetooth/audio/micp.h)

**Framework APIs for these services**
- The framework proxies are system-only:
  - `BluetoothVolumeControl` is `@SystemApi`. `setDeviceVolume`, `setVolumeOffset` and `getAudioInputControlServices` require `allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED}`.
  - `AudioInputControl` is `@SystemApi` behind `FLAG_AICS_API`.
  
  — [BluetoothVolumeControl.java#66](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/framework/java/android/bluetooth/BluetoothVolumeControl.java#66), [#692](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/framework/java/android/bluetooth/BluetoothVolumeControl.java#692), [#726](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/framework/java/android/bluetooth/BluetoothVolumeControl.java#726); [AudioInputControl.java#56](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/framework/java/android/bluetooth/AudioInputControl.java#56)

**App GATT connections to an LE Audio headset (Android 17)**
- A direct, non-opportunistic LE `connectGatt` from an app can call `notifyDirectLeGattClientConnect` when the `DONOT_STEAL_AUDIO_ON_GATT_CONN` compat change is enabled for the caller's UID and `SdkLevel.isAtLeastC()`. The code comment reads "This logic prevents app-initiated GATT connections from hijacking an active LE Audio stream". `AdapterService.notifyDirectLeGattClientConnect` then calls `addGattClientToControlAutoActiveMode` on `LeAudioService`. — [android17 GattService.java#1134](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/android17-release/android/app/src/com/android/bluetooth/gatt/GattService.java#1134); [android17 AdapterService.java#3418](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/android17-release/android/app/src/com/android/bluetooth/btservice/AdapterService.java#3418)

### Inferences
- **Usable by a normal app on a compliant LE headset (Android 13–17), over its own `connectGatt(…, TRANSPORT_LE)`:**
  - MICS Mute (read, notify, write 0/1, to mute the headset mic).
  - BAS Battery Level (read and notify).
  - DIS (model, manufacturer, firmware strings: good for identification).
  - Discovery of *which* restricted services exist. The service list is returned, so an app can tell "this is an LE Audio / HAP device with N AICS instances" without reading them.
- **Not usable without privilege:** VCS volume, VOCS balance, every AICS instance (including AICS included under MICS, because AICS is a secondary service with UUID 0x1843 and secondary services are checked too), HAS presets, CSIS, PACS, ASCS, BASS.
- **Volume:** the practical public route is `AudioManager.setStreamVolume(STREAM_MUSIC, …)`. The framework then drives VCS on LE Audio or AVRCP absolute volume on Classic. This is an inference; the AOSP routing code was not traced here.
- **Concurrency:** nothing in `GattService` refuses an app's GATT connection because the system LE Audio stack is already connected. Android's own BAS client (Q6) opens its GATT client the same way, with public `connectGatt`. So concurrent app GATT on an LE Audio headset is expected to work. Android 17 even adds logic for exactly that scenario, so that the app's connection does not make the headset the active audio device.
- `readUsingCharacteristicUuid` is a hidden API (blocked or greylisted for apps). Using it to reach restricted characteristics would circumvent an intended platform restriction. Earshot should not rely on it.
- MICS is the only LE Audio *control* service left open, probably because AOSP has no MICP client to protect. This is an inference from the absence of the client.

### Gaps
- How many shipping LE Audio earbuds or headphones actually expose MICS, and whether their Mute is writable rather than "disabled" (2), is unknown. No source was found, and nothing was tested on hardware.
- The trigger that enables `DONOT_STEAL_AUDIO_ON_GATT_CONN` (target SDK level, or always on) was not found. `ChangeIds` was not located in `android17-release`.
- Behaviour on OEM forks (Samsung One UI, which has its own LE Audio stack additions) was not checked. OEMs could add restrictions beyond AOSP.
- Classic-only headsets (most of today's ANC headphones, including the QC Ultra 2 on the user's Galaxy, AAC/SBC) often expose no BLE GATT at all, so these services apply only to dual-mode or LE Audio products. Coverage numbers were not found.

---

## Q2. AICS as an ambient or transparency control; Android 16 "ambient volume" for hearing devices

### Takeaway
Yes, the standard has a hook for this. AICS defines an Audio Input Type **"Ambient" (0x07)**, and Android 16 Settings uses exactly that. SettingsLib's `AmbientVolumeController` finds every AICS instance whose type is `AUDIO_INPUT_TYPE_AMBIENT` on a hearing device and sets gain and mute on all of them. The only Android API for it (`BluetoothVolumeControl.getAudioInputControlServices` / `AudioInputControl`) is `@SystemApi` + `BLUETOOTH_PRIVILEGED`, and direct GATT access to AICS is blocked (Q1). **A normal app cannot drive AICS ambient gain on Android 13–17.**

### Cited Findings
- AICS input types in Zephyr's `aics.h` (Apache-2.0): `BT_AICS_INPUT_TYPE_UNSPECIFIED 0x00`, `BLUETOOTH 0x01`, `MICROPHONE 0x02`, `ANALOG 0x03`, `DIGITAL 0x04`, `RADIO 0x05`, `STREAMING 0x06`, `AMBIENT 0x07`. Mute states are 0 unmuted, 1 muted, 2 mute-disabled; gain modes are manual-only 0, auto-only 1, manual 2, auto 3. — [Zephyr include/zephyr/bluetooth/audio/aics.h](https://raw.githubusercontent.com/zephyrproject-rtos/zephyr/main/include/zephyr/bluetooth/audio/aics.h)
- AOSP's `AudioInputControl` (one object per AICS instance on the remote device, citing AICS 1.0) defines `AUDIO_INPUT_TYPE_AMBIENT`. It is annotated `@FlaggedApi(Flags.FLAG_AICS_API) @SystemApi`, and its getters and setters require `allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED}`. The class already exists in `android15-qpr2-release` and is present in android16 and android16-qpr2. — [main AudioInputControl.java#56–89](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/framework/java/android/bluetooth/AudioInputControl.java#56); [android15-qpr2-release AudioInputControl.java](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/android15-qpr2-release/framework/java/android/bluetooth/AudioInputControl.java)
- Android 16 SettingsLib `AmbientVolumeController` (2024, Apache-2.0) is documented as managing "the AudioInputControls of AUDIO_INPUT_TYPE_AMBIENT on the remote device". Its setter applies the value to every ambient control point (`ambientControls.forEach(control -> control.setGainSetting(value))`). The same directory holds `AmbientVolumeUi.java` and `AmbientVolumeUiController.java`. — [android16-release SettingsLib AmbientVolumeController.java#40](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/packages/SettingsLib/src/com/android/settingslib/bluetooth/AmbientVolumeController.java#40)
- SettingsLib `VolumeControlProfile.getAudioInputControlServices(device)` is `@hide` and calls the system service. The change that added it ("Integrate getAudioInputControlPoints() into SettingsLib") is gated by the flag `hearing_devices_ambient_volume_control`, bug 357878944. — [main SettingsLib VolumeControlProfile.java#224](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SettingsLib/src/com/android/settingslib/bluetooth/VolumeControlProfile.java#224); [OmniROM mirror of the AOSP commit](https://gerrit.omnirom.org/plugins/gitiles/android_frameworks_base/+/3b74adc96aa256207f961dcb3843764e2741c67b%5E%21)
- Press coverage of the Android 16 beta:
  - LE Audio hearing-aid users can adjust "the volume of ambient sound that is picked up by the hearing aid's microphones" (quoted as Google documentation), per ear or both together, with a quick mute of the surroundings.
  - Android 16 also adds switching between the hearing-aid mics and the phone mic during calls.
  
  — [Android Authority](https://www.androidauthority.com/android-16-hearing-aids-3520978/); [Android Headlines](https://www.androidheadlines.com/2025/01/android-16-could-bring-two-major-features-for-hearing-aid-users.html)
- The SIG lists Audio Input Control Service 1.0.1 as adopted. It "exposes the gain of an audio input" and belongs to the volume-control family (VCP, VCS, VOCS, AICS). — [bluetooth.com AICS](https://www.bluetooth.com/specifications/specs/audio-input-control-service/); [bluetooth.com LE Audio specifications](https://www.bluetooth.com/learn-about-bluetooth/feature-enhancements/le-audio/le-audio-specifications/)

### Inferences
- AICS "Ambient" is the closest thing to a standardized transparency-level knob. It is a gain plus mute on an "ambient" input: it fits "how much of the outside world do I hear" (transparency level), but not ANC on/off or ANC strength.
- On Android it is reachable only by Settings or system apps. Earshot could at most *detect* it: discover a 0x1843 service, even though its Input Type characteristic is unreadable. It could then deep-link the user to the system hearing-device settings.
- If an OEM build is rooted, or a privileged path appears via Shizuku, the framework `AudioInputControl` API is the right abstraction. This is speculative; SystemApi reflection is likely blocked, as the prior research notes.

### Gaps
- No source names a consumer earbud or headphone (as opposed to hearing aids) that exposes an AICS instance of type Ambient for transparency. Searches turned up only patents and generic AICS pages.
- No source lists which hearing aids support Android 16 ambient control. The Google support page tried returned 404.
- Whether the ambient UI shipped enabled in Android 16 stable, or stayed behind the flag on some builds, was not confirmed.

---

## Q3. Does the Bluetooth SIG standardize noise cancellation, transparency or ambient modes?

### Takeaway
**No.** As of 2026-10-20, no adopted Bluetooth SIG specification, no listed draft and no announced in-development project covers ANC, transparency or "ambient mode" control on headsets or earbuds. The nearest standard pieces are AICS's "Ambient" input type (a gain knob, Q2) and HAS presets (hearing-aid programs). Every ANC/transparency control is still vendor protocol, or Google Fast Pair's Message Stream (covered elsewhere).

### Cited Findings
- **Adopted specifications page.** The audio-related adopted specs are A2DP, AVCTP, AVDTP, AVRCP, AICS, ASCS, BAP, BASS, BAU (Broadcast Audio URI), CCP, CAP, CAS, CSIP, CSIS, GMAP, GAVDP, HFP, HSP, HAP, HAS, LC3, MCP, MCS, MICP, MICS, PBP, PACS, TBS, TMAP, VAP and VAS (Voice Assistant Profile and Service), VCP, VCS and VOCS. Core Specification versions 6.0–6.3 are listed. No entry mentions noise, earbuds or head tracking; "Ambient" appears only in the "Ambient Light Sensor NLC Profile" (lighting). — [bluetooth.com Specifications list](https://www.bluetooth.com/specifications/specs/)
- **Drafts on the same page:** Channel Sounding Inline PCT Transfer, Common Audio Profile v1.1, Core "Running Out of Bits", Dynamic Stream Adaptation (ASCS), **Exchange of Mute Status Information Between AG and HF**, High Data Throughput, High-Resolution/Lossless Codec, HDT PHY for LE Audio, IP Link Protocol, Randomized RPA Updates, and Voice Assistant Profile – Hearing Access Profile. None concerns ANC or transparency. — [bluetooth.com Specifications list](https://www.bluetooth.com/specifications/specs/)
- **"Specifications in development" page** (newest resource on it dated 2026-10-19). The SIG "typically has more than 50 active specification development projects". The highlighted LE Audio projects are:
  - high-resolution/lossless audio;
  - "a standardized approach for supporting spatial audio on Bluetooth headphones";
  - multi-channel surround;
  - voice-assistant integration with headphones;
  - standardized sound-pressure (exposure) reporting from headphones;
  - extended Auracast coverage, capacity and discovery.
  
  The page does not mention ANC, transparency, ambient modes or head tracking. Outside LE Audio: High Data Throughput (up to 8 Mbps), 5/6 GHz bands, and IP Link (targeting adoption in fall 2026). — [bluetooth.com Specifications in development](https://www.bluetooth.com/specifications/in-development/)
- A general web search for an SIG ANC/transparency specification or working group returned only consumer reviews and vendor explainers. — [SoundGuys transparency roundup (example result)](https://www.soundguys.com/best-transparency-mode-earbuds-109883/)

### Inferences
- The "spatial audio on Bluetooth headphones" project is the item most likely to standardize something Earshot cares about, such as spatial on/off or possibly head-tracking transport. It has no public date or content.
- "Exchange of Mute Status Information Between AG and HF" suggests a future HFP-level standard for mic mute sync. The draft title is the only evidence.
- The "sound exposure" project could eventually give a standard hearing-health readout from headphones.
- Because the SIG lists only "a partial list" and members-only drafts are not public, the absence of ANC work cannot be proven. It is only absent from everything public.

### Gaps
- Members-only SIG working-group material (Audio WG, Hearing Aid WG) is not public. No press release, blog post or draft title about ANC was found for 2023–2026.
- The Core 6.0–6.3 feature summaries were not read in detail. They are listed as adopted, and none of their titles relates to audio control.

---

## Q4. Head tracking: the Android head-tracker HID standard, public `Spatializer` APIs, and which headsets support it

### Takeaway
Head tracking reaches apps only *indirectly*:
- **Public `Spatializer` API (read-only):** a normal app can read whether spatial audio is available and enabled, whether a head tracker is available, and the immersive level, and listen for changes. Everything that *changes* state (enable/disable, head-tracking mode, recenter, compatible devices, pose listener) is `@SystemApi(PRIVILEGED_APPS)` + `MODIFY_DEFAULT_AUDIO_EFFECTS`.
- **Head-tracker sensor:** the standard "Android head tracker" HID sensor (`#AndroidHeadTracker#1.0` over Classic HID, `2.0` over LE from Android 15) is visible only to audioserver/system_server. `SensorService` hides `TYPE_HEAD_TRACKER` from apps.
- **Direct GATT:** since Android 15 QPR1, its LE GATT service is also on the restricted-UUID list.

### Cited Findings
**Spatializer API**
- Public `Spatializer` surface by release (from AOSP `core/api/current.txt`):
  - **12L:** `isAvailable`, `isEnabled`, `getImmersiveAudioLevel`, `canBeSpatialized`, `add/removeOnSpatializerStateChangedListener`.
  - **Android 13 adds:** `isHeadTrackerAvailable`, `add/removeOnHeadTrackerAvailableListener`.
  - **Android 15:** unchanged.
  - **Android 16 adds:** `getSpatializedChannelMasks()` (`@FlaggedApi("android.media.audio.spatializer_capabilities")`).
  
  — [android12L current.txt](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12L-release/core/api/current.txt), [android13](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/core/api/current.txt), [android16](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/api/current.txt)
- `Spatializer.java` (main) marks these `@hide @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS) @RequiresPermission(MODIFY_DEFAULT_AUDIO_EFFECTS)`: `isAvailableForDevice`, `hasHeadTracker`, `setHeadTrackerEnabled`, `isHeadTrackerEnabled`, `setEnabled`, `getCompatibleAudioDevices`, `add/removeCompatibleAudioDevice`, `getHeadTrackingMode`, `getDesiredHeadTrackingMode`, `getSupportedHeadTrackingModes`, `setDesiredHeadTrackingMode`, `recenterHeadTracker`, `add/removeOnHeadTrackingModeChangedListener`, `setOnHeadToSoundstagePoseUpdatedListener`, `setGlobalTransform`, `setEffectParameter`/`getEffectParameter`, `setOnSpatializerOutputChangedListener`. — [main Spatializer.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/Spatializer.java#129)

**Head-tracker sensor**
- `Sensor.TYPE_HEAD_TRACKER = 37` is public API, but its javadoc says "Note that this sensor type is typically not available for apps to use." — [main Sensor.java#716](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/hardware/Sensor.java#716)
- `SensorService::canAccessSensor`: "Special case for Head Tracker sensor type: currently restricted to system usage only, unless the restriction is specially lifted for testing". `SENSOR_TYPE_HEAD_TRACKER` is denied to any caller that is not audioserver or system_server while `mHtRestricted` is set. The dynamic sensor list silently skips it. A shell command `unrestrict-ht` / `restrict-ht` toggles the restriction (a test hook). The same check exists in `android13-release`. — [main SensorService.cpp#2294](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/main/services/sensorservice/SensorService.cpp#2294), [#1544](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/main/services/sensorservice/SensorService.cpp#1544), [#926](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/main/services/sensorservice/SensorService.cpp#926)

**Head-tracker HID protocol (source.android.com)**
- Covers USB or Bluetooth, Android 13+, and is used to control the audio virtualizer. The HID application collection uses the Sensors page (0x20) with custom usage 0xE1.
- Sensor Description (0x0308) must be `#AndroidHeadTracker#1.0` (Bluetooth Classic). v2.0, `#AndroidHeadTracker#2.0#x` (Android 15+, LE, x = 1 ACL / 2 ISO / 3 both), adds LE Transport property 0xF410.
- Persistent Unique ID (0x0302) links the tracker to the audio device: `"BT"` plus the audio device's MAC, or a UUID.
- Reporting State, Power State and Report Interval are written by the host; the interval must support at least 50 Hz.
- The input report carries a rotation vector, angular velocity and a discontinuity counter.

— [Head tracker HID protocol](https://source.android.com/docs/core/sensors/head-tracker-hid-protocol)
- AOSP HID host accepts LE devices that advertise the head-tracker UUID `109b862f-50e3-45cc-8ea1-ac62de4846d1` (`headtrackerSupported`) as well as HOGP. — [main HidHostService.java#1039](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/hid/HidHostService.java#1039)
- The spatial audio implementation page says OEMs "MUST implement the Spatializer class". Head-tracking latency must not exceed 150 ms; pose updates come about every 20 ms with a 40 ms maximum gap; head-tracking data may be sent only while the user enables it in Bluetooth settings. — [Implementation of spatial audio and head tracking](https://source.android.com/docs/core/audio/implement-spatial-audio)

**Headsets with head tracking on Android**
- **Pixel Buds Pro:** Google enabled spatial audio with head tracking in firmware 4.30, on the Pixel 6/6 Pro/7/7 Pro at launch (early 2023). — [9to5Google 2023-03-07](https://9to5google.com/2023/03/07/pixel-buds-pro-spatial-audio-update/); [9to5Google 2023-02-09](https://9to5google.com/2023/02/09/spatial-audio-pixel-buds-pro/)
- **OnePlus Buds Pro 2:** among the first to support Android 13 spatial audio with head tracking. The report claims "head-tracking data will also be made available to other Android applications", which contradicts AOSP `SensorService` (system-only). — [EFTM](https://eftm.com/2023/01/google-brings-spatial-audio-to-some-pixel-smartphones-supported-by-the-pixel-buds-pro-and-the-oneplus-buds-pro-2-231376)

### Inferences
- For Earshot, head tracking is a **read-only status surface**. It can show "Spatial audio: available/on, head tracker: available" via `AudioManager.getSpatializer()` and listeners, and deep-link to the system Bluetooth device page for toggles. It cannot toggle spatial audio, read pose, or recenter.
- A headset that advertises `109b862f-…` in its LE advertising or SDP can be identified as "supports the Android standard head tracker", which is useful for a capability badge. Reading its GATT is blocked from Android 15 QPR1 onwards; on Android 13–15.0 the LE service was not on the restricted list.
- Vendor apps (Sony, Samsung, Bose) still use proprietary toggles for their own spatial or head-tracking modes. The standard path only covers the Android virtualizer.

### Gaps
- No primary source confirms which mechanism Pixel Buds Pro / Pro 2 or OnePlus Buds Pro 2 use, whether the standard HID head tracker or a vendor path. Galaxy Buds (Samsung "360 Audio") and Sony support for the Android standard head tracker were not found.
- The Android 13 `Spatializer` javadoc for `isHeadTrackerAvailable` was not read for exact semantics (e.g. whether it reflects the current output device only).

---

## Q5. HFP vendor-specific AT channel (`sendVendorSpecificResultCode`, `ACTION_VENDOR_SPECIFIC_HEADSET_EVENT`)

### Takeaway
Both are **public** and need only `BLUETOOTH_CONNECT`, but they are **not a settings channel**:
- **Phone → headset:** `sendVendorSpecificResultCode` may send only `+ANDROID: <arg>`; any other command returns `false`.
- **Headset → phone:** the broadcast fires only for a fixed allow-list of headset-originated AT commands: `+XEVENT` (Plantronics, company 85), `+ANDROID`, `+CGMI/+CGMR/+CGMM/+CGSN` (Google), and `+XAPL`/`+IPHONEACCEV` (Apple). Other vendor ATs are dropped.

So an app can *listen* (battery, don/doff, PTT-style events) but cannot send Jabra, Poly or Apple settings commands over HFP. The app also cannot open the HFP RFCOMM channel itself, because the system holds it.

### Cited Findings
- `BluetoothHeadset.sendVendorSpecificResultCode(device, command, arg)` is public, `@RequiresPermission(BLUETOOTH_CONNECT)`, and sends `command + ": " + arg`. "Currently only VENDOR_RESULT_CODE_COMMAND_ANDROID is allowed as command". — [main BluetoothHeadset.java#1133](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/framework/java/android/bluetooth/BluetoothHeadset.java#1133)
- `HeadsetService.sendVendorSpecificResultCode` enforces this: `if (!command.equals(BluetoothHeadset.VENDOR_RESULT_CODE_COMMAND_ANDROID)) { Log.w(TAG, "Disallowed unsolicited result code command: " + command); return false; }`. It also requires the device to be in `STATE_CONNECTED`. The binder's `getService()` checks `checkConnectPermissionForDataDelivery`. — [main HeadsetService.java#2138](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/hfp/HeadsetService.java#2138), [#536](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/hfp/HeadsetService.java#536)
- `ACTION_VENDOR_SPECIFIC_HEADSET_EVENT` is a public broadcast (`@RequiresPermission(BLUETOOTH_CONNECT)`). It carries the extras `EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD`, `…_CMD_TYPE` (READ/TEST/SET/BASIC/ACTION) and `…_ARGS`. Its category is `VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_ID_CATEGORY + "." + companyId`. Example: `AT+XEVENT=foo,3` → CMD `+XEVENT`, type SET, args `foo, 3`. Constants include `+XAPL`, `+IPHONEACCEV` (battery indicator 1), and `+XEVENT` (battery key `"BATTERY"`). — [main BluetoothHeadset.java#140–265](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/framework/java/android/bluetooth/BluetoothHeadset.java#140)
- `HeadsetStateMachine` maps the allowed commands to company IDs: `+XEVENT` → `PLANTRONICS`, `+ANDROID` → `GOOGLE`, `+XAPL` and `+IPHONEACCEV` → `APPLE`, `+CGMI/+CGMR/+CGMM/+CGSN` → `GOOGLE`. Commands not in the map hit `Log.i(TAG, "processVendorSpecificAt: unsupported command: " + atString)` and are not broadcast. `+XAPL` is answered by the stack itself with `+XAPL=iPhone,2`. The broadcast is sent with `sendBroadcastAsUser(intent, UserHandle.ALL, BLUETOOTH_CONNECT, …)`. — [main HeadsetStateMachine.java#158](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/hfp/HeadsetStateMachine.java#158), [#1862](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/hfp/HeadsetStateMachine.java#1862), [#2313](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/hfp/HeadsetStateMachine.java#2313), [#2519](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/hfp/HeadsetStateMachine.java#2519)
- Android's developer guide presents the vendor-event broadcast as a way to learn a headset's battery level, e.g. from Plantronics `+XEVENT`. — [developer.android.com Bluetooth profiles](https://developer.android.com/develop/connectivity/bluetooth/profiles)
- A forum post relays a manufacturer statement that a PTT button sends `AT+XEVENT=TALK,1` / `TALK,0` over HFP. This is second-hand and not an official specification. — [B4X forum](https://b4x.com/android/forum/threads/hfp-at-commands.110259)
- Company IDs: `BluetoothAssignedNumbers.PLANTRONICS = 0x0055` (85), `APPLE = 0x004C` (76), `GOOGLE = 0x00E0` (224). `broadcastVendorSpecificEventIntent` builds the category with `Integer.toString(companyId)`, i.e. decimal: `…COMPANY_ID_CATEGORY.85` for Plantronics. — [main BluetoothAssignedNumbers.java#460](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/framework/java/android/bluetooth/BluetoothAssignedNumbers.java#460); [main HeadsetStateMachine.java#1880](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/hfp/HeadsetStateMachine.java#1880)
- Also public on `BluetoothHeadset` with `BLUETOOTH_CONNECT`: `isNoiseReductionSupported` and `isVoiceRecognitionSupported` (HFP feature bits from the headset). — [main BluetoothHeadset.java#651](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/framework/java/android/bluetooth/BluetoothHeadset.java#651)

### Inferences
- **Useful for Earshot:**
  - Passive listening for battery from `+IPHONEACCEV` (AirPods, Beats and many generic headsets emulate it) and `+XEVENT=BATTERY…` (Poly/Plantronics).
  - Plantronics don/doff and other `+XEVENT` notifications, as an "on-head" signal for Poly headsets.
  - `isNoiseReductionSupported` reports the HFP "EC/NR" feature bit, *not* ANC.
- **Not useful:** any phone→headset settings write. The `+ANDROID` result code exists for Android's own HF-side features (e.g. `AT+ANDROID=SINKAUDIOPOLICY`), not vendor settings.
- The `BluetoothHeadset` javadoc example writes the Plantronics category suffix as ".55", which is the hex value without a prefix. The code emits decimal ".85", so Earshot's intent filter should use the decimal form. This is an inference from the code; not tested on a device.

### Gaps
- No public Plantronics/Poly `+XEVENT` command reference was found, and none for Jabra or Apple vendor AT *settings* commands. Apple's `+XAPL`/`+IPHONEACCEV` accessory spec is not public.
- The Android version where the `+ANDROID`-only allow-list began was not determined; it was verified on `main` only. `+CGMI/+CGMR/+CGMM/+CGSN` support is likely recent (Android 14/15) but unverified.

---

## Q6. Battery: BAS over the app's own GATT client vs. the system broadcast

### Takeaway
BAS (0x180F) is **not restricted** on any Android version from 12 to 17. An app with `BLUETOOTH_CONNECT` can open its own LE GATT connection to a dual-mode or LE Audio headset and read or notify Battery Level (0x2A19), while the system's LE Audio stack is connected. Android's own BAS client does the same thing through the public `connectGatt` API and feeds the result into the `BATTERY_LEVEL_CHANGED` broadcast.

### Cited Findings
- AOSP has a system BAS client (`bas/BatteryService.java`, `bas/BatteryStateMachine.java`) since `android13-release`. — [android13-release bas/](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/android13-release/android/app/src/com/android/bluetooth/bas/), [main bas/](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/bas/)
- `BatteryStateMachine` defines `GATT_BATTERY_SERVICE_UUID = 0000180f-…` and `GATT_BATTERY_LEVEL_CHARACTERISTIC_UUID = 00002a19-…`. It connects with `mDevice.connectGatt(…, /* autoConnect= */ false, …, TRANSPORT_LE, …)` and reports via `mService.handleBatteryChanged(mDevice, batteryLevel)`. — [main BatteryStateMachine.java#60](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/bas/BatteryStateMachine.java#60), [#179](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/bas/BatteryStateMachine.java#179), [#211](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/bas/BatteryStateMachine.java#211)
- BAS (`0x180F`) appears in none of the restricted lists (FIDO, TV remote, the eight LE Audio UUIDs, head tracker, ANCS) in Android 12–17. — [main GattService.java#97–122](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/gatt/GattService.java#97); [android17 GattUtil.kt#32–60](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/android17-release/android/app/src/com/android/bluetooth/gatt/GattUtil.kt#32)
- Earlier research (in-repo): `BATTERY_LEVEL_CHANGED` reaches normal apps holding `BLUETOOTH_CONNECT`; `getBatteryLevel()` is a hidden SystemApi since Android 13. — [headset-landscape-2026-10.md](../../headset-landscape-2026-10.md)

### Inferences
- **Initial value:** reading BAS directly is a legitimate, unprivileged way to get the *initial* battery value on LE-capable headsets, without waiting for the next broadcast and without Shizuku. This fills the `getBatteryLevel()` gap for LE devices only.
- **Classic-only headsets:** these (e.g. the QC Ultra 2 on the user's Galaxy, AAC/SBC only) expose no LE GATT BAS. For them the sources stay the HFP indicators and vendor ATs (Q5) and Fast Pair adverts.
- **Per-bud levels:** BAS can have several instances (left/right/case) with a Characteristic Presentation Format descriptor. Whether earbuds expose per-bud BAS was not verified.
- **Android 17 audio routing:** an app's direct LE GATT connection to an LE Audio device should not make it the active audio device, per the `DONOT_STEAL_AUDIO_ON_GATT_CONN` logic (Q1). On Android 13–16 that protection applied only to allow-listed packages (`GATT_CLIENTS_NOTIFY_TO_ADAPTER_PACKAGES`). A background BAS read from Earshot could therefore, in theory, affect LE Audio auto-activation on those versions. This is an inference from code comments; not tested.

### Gaps
- No test or primary source confirms BAS reads from a third-party app while LE Audio streams on a real headset. It is inferred from the code: BAS is unrestricted, and the system's own BAS client uses the same public API.
- Which LE Audio earbuds expose multiple BAS instances (L/R/case), and how they label them, was not researched.
