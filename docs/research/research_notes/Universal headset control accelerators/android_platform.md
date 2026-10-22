# Android platform features a normal app can use for any Bluetooth headset (Android 15, 16 and 17)

Scope: non-root, Play-compliant capabilities, as of 2026-10-20. Prior research already covers the battery and codec broadcasts, CDM presence and background starts in general, the hidden-API status of `getBatteryLevel` and the codec calls, and the HFP voice-recognition trigger. Those topics are only mentioned here where something new was found.

Conventions:
- "API 37" is Android 17. "version 36.1" and "version 37.2" are minor SDK releases, as developer.android.com labels them. Check them with `Build.VERSION.SDK_INT_FULL`. I could not confirm which shipping builds already include 37.2.
- Licences: AOSP is Apache-2.0. LibrePods and Key Mapper are GPL-3.0. Only facts were taken from them, no code.
- The KeyMapperDocs repository declares no licence.

---

## 1. Public Bluetooth socket APIs: RFCOMM by channel or classic L2CAP by PSM without reflection? What did Android 17 change for AirPods?

### Takeaway
Android 16 (API 36) added `BluetoothSocketSettings` with `BluetoothDevice.createUsingSocketSettings` and `BluetoothAdapter.listenUsingSocketSettings`. The public socket types are limited to:
- RFCOMM found **by UUID through SDP**;
- **LE** L2CAP CoC with PSM 128–255.

There is still **no public way to open RFCOMM by channel number or a classic (BR/EDR) L2CAP channel by PSM**, and Android 17 added none.

The AirPods change is a **Bluetooth-stack fix**, not a new API: it removes the L2CAP channel-mode rejection (Google issue 371713238). It ships on Pixel with Android 16 QPR3 plus the latest Play system update and on ColorOS/OxygenOS 16, and is expected for all devices with Android 17. LibrePods still reaches classic L2CAP PSM 0x1001 with blocklisted reflection on the private `BluetoothSocket` constructor. It unlocks that reflection with a JNI `VMRuntime.setHiddenApiExemptions` trick.

### Cited Findings
- **The builder.** `BluetoothSocketSettings.Builder` was added in API 36.
  - Its only public setters are `setSocketType`, `setL2capPsm`, `setRfcommUuid`, `setRfcommServiceName`, `setAuthenticationRequired` and `setEncryptionRequired` (both default false). — [BluetoothSocketSettings.Builder reference](https://developer.android.com/reference/android/bluetooth/BluetoothSocketSettings.Builder)
  - `setSocketType` docs: "Must be one of: BluetoothSocket.TYPE_RFCOMM, BluetoothSocket.TYPE_LE"; the default is RFCOMM. — [BluetoothSocketSettings.Builder reference](https://developer.android.com/reference/android/bluetooth/BluetoothSocketSettings.Builder)
  - The AOSP implementation throws `IllegalArgumentException("invalid socketType")` for any value other than `TYPE_RFCOMM` or `TYPE_LE`. Classic `TYPE_L2CAP` (= 3, alias `TYPE_L2CAP_BREDR`) is therefore rejected. — [AOSP BluetoothSocketSettings.java](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/framework/java/android/bluetooth/BluetoothSocketSettings.java); [AOSP BluetoothSocket.java](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/framework/java/android/bluetooth/BluetoothSocket.java)
- **The PSM.** `setL2capPsm(int)` "is only used for BluetoothSocket.TYPE_LE sockets". Valid values are 128 (0x80) to 255 (0xFF); anything else throws `IllegalArgumentException`. — [BluetoothSocketSettings.Builder reference](https://developer.android.com/reference/android/bluetooth/BluetoothSocketSettings.Builder)
- **Creating the socket.** `BluetoothDevice.createUsingSocketSettings(BluetoothSocketSettings)` was added in API 36.
  - For `TYPE_RFCOMM` "The RFCOMM UUID must be provided"; for `TYPE_LE` the PSM must be provided.
  - It throws `IllegalArgumentException` "if TYPE_RFCOMM socket with no UUID is passed".
  - The reference entry mentions no Companion Device Manager (CDM) requirement.

  — [BluetoothDevice reference](https://developer.android.com/reference/android/bluetooth/BluetoothDevice#createUsingSocketSettings(android.bluetooth.BluetoothSocketSettings))
- The AOSP `createUsingSocketSettings` is `@FlaggedApi(FLAG_SOCKET_SETTINGS_API)`. It passes type, auth, encrypt, PSM and UUID to the package-private `BluetoothSocket` constructor. — [AOSP BluetoothDevice.java](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/framework/java/android/bluetooth/BluetoothDevice.java)
- **Hardware offload is SystemApi.** `setDataPath(DATA_PATH_HARDWARE_OFFLOAD)`, `setSocketName`, `setHubId`, `setEndpointId` and the requested maximum packet size (offload to a context hub) are all `@SystemApi`. — [AOSP BluetoothSocketSettings.java](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/framework/java/android/bluetooth/BluetoothSocketSettings.java)
- **Permissions.** `BluetoothSocket.connect()` requires `BLUETOOTH_CONNECT` (target S+). It needs `BLUETOOTH_PRIVILEGED` "only when mDataPath is different from DATA_PATH_NO_OFFLOAD". — [BluetoothSocket reference](https://developer.android.com/reference/android/bluetooth/BluetoothSocket)
- `BluetoothSocket.TYPE_LE` (= 4, "L2CAP socket on LE transport") was added in API 36. — [BluetoothSocket reference](https://developer.android.com/reference/android/bluetooth/BluetoothSocket)
- The older public `createL2capChannel(int)` / `createInsecureL2capChannel(int)` (API 29) say: "The supported Bluetooth transport is LE only." — [BluetoothDevice reference](https://developer.android.com/reference/android/bluetooth/BluetoothDevice)
- **The hidden methods in AOSP main:**
  - `createL2capSocket(int)` and `createInsecureL2capSocket(int)` (classic `TYPE_L2CAP`, "Valid L2CAP PSM channels are in range 1 to 2^16") are `@hide` with **no `@UnsupportedAppUsage`**.
  - `createRfcommSocket(int)` is `@UnsupportedAppUsage`.
  - `createInsecureRfcommSocket(int)` is `@UnsupportedAppUsage(publicAlternatives = "Use createInsecureRfcommSocketToServiceRecord")`.

  — [AOSP BluetoothDevice.java](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/framework/java/android/bluetooth/BluetoothDevice.java)
- None of the package-private `BluetoothSocket(...)` constructors carries `@UnsupportedAppUsage`. — [AOSP BluetoothSocket.java](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/framework/java/android/bluetooth/BluetoothSocket.java)
- Non-SDK interfaces that are not on the unsupported (formerly greylist) list are blocked for apps. — [Restrictions on non-SDK interfaces](https://developer.android.com/guide/app-compatibility/restrictions-non-sdk-interfaces)
- **Why AirPods failed: the ERTM check.**
  - In the AOSP socket layer, outgoing classic (non-LE) L2CAP sockets always request ERTM: `obex_l2c_etm_opt = {L2CAP_FCR_ERTM_MODE /* Mandatory for OBEX over l2cap */}`. — [AOSP btif_sock_l2cap.cc](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/system/btif/src/btif_sock_l2cap.cc)
  - `l2c_fcr_chk_chan_modes()` logs "Peer does not support our desired channel types" and returns false when the peer lacks ERTM in its extended features while ERTM is preferred. The channel state machine then releases the channel with `L2CAP_CONN_OTHER_ERROR`. — [AOSP l2c_fcr.cc](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/system/stack/l2cap/l2c_fcr.cc); [AOSP l2c_csm.cc](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/system/stack/l2cap/l2c_csm.cc)
  - LibrePods' root/Xposed workaround hooks exactly `l2c_fcr_chk_chan_modes` in `libbluetooth_jni.so` / `libbluetooth_qti.so` and forces it to return 1 (GPL-3.0). — [LibrePods l2c_fcr_hook.cpp](https://github.com/kavishdevar/librepods/blob/main/android/app/src/main/cpp/l2c_fcr_hook.cpp)
- **Where the fix ships (LibrePods README):**
  - LibrePods "does not need root for most features" on ColorOS/OxygenOS 16, realme UI 7.0 and "Pixel devices on Android 16 QPR3 (with the latest Google Play system update)".
  - Elsewhere it needs root "because of a bug in the Android Bluetooth stack Fluoride/non-compliance of Apple with Bluetooth standards", tracked at issuetracker.google.com/issues/371713238. The issue "has already been resolved and should be available in Android 17 for all devices".
  - VendorID-hook features always need root.

  — [LibrePods android/README.md](https://github.com/kavishdevar/librepods/blob/main/android/README.md)
- **How LibrePods opens the socket (Play flavour):**
  - The `play` flavour has `minSdk = 36`; the app targets and compiles against SDK 37. — [LibrePods build.gradle.kts](https://github.com/kavishdevar/librepods/blob/main/android/app/build.gradle.kts)
  - It builds the AirPods socket by reflecting on `BluetoothSocket`'s private constructors with `type = 3 // L2CAP`, auth and encrypt true, and PSM `4097` (0x1001). It tries several constructor signatures; the first is labelled `// A16QPR3` (adapter, device, type, auth, encrypt, psm, uuid). — [LibrePods BluetoothConnectionManager.kt](https://github.com/kavishdevar/librepods/blob/main/android/app/src/main/java/me/kavishdevar/librepods/bluetooth/BluetoothConnectionManager.kt); [AirPodsService.kt](https://github.com/kavishdevar/librepods/blob/main/android/app/src/main/java/me/kavishdevar/librepods/services/AirPodsService.kt)
  - LibrePods' native `JNI_OnLoad` calls `dalvik.system.VMRuntime.setHiddenApiExemptions` with the `Landroid/bluetooth/BluetoothSocket;` and `Landroid/bluetooth/BluetoothDevice;` prefixes (the strings are obfuscated). The service logs "lib exempt worked" after checking that `BluetoothSocket::class.java.declaredConstructors` no longer throws. — [LibrePods bluetooth_socket.cpp](https://github.com/kavishdevar/librepods/blob/main/android/app/src/main/cpp/bluetooth_socket.cpp); [AirPodsService.kt](https://github.com/kavishdevar/librepods/blob/main/android/app/src/main/java/me/kavishdevar/librepods/services/AirPodsService.kt)
- An Android Authority report (April 2026) says LibrePods is on the Play Store and works without root "after Google recently patched a longstanding bug", with a limited device list. Seen only as a search-result snippet; the page returned 403. — [Android Authority](https://www.androidauthority.com/librepods-using-airpods-with-android-unlock-3661340/)
- The LibrePods README points unrooted users on "Android version 16 QPR3 or below" to CAPod instead. — [LibrePods README](https://github.com/kavishdevar/librepods/blob/main/README.md)
- **New read() behaviour.** For apps targeting Android 17 (API 37), "the read() method of the InputStream obtained from an RFCOMM-based BluetoothSocket now returns -1 when the socket is closed or the connection is dropped". Read loops that rely only on `IOException` must check for -1. — [Android 17 behavior changes (targeting 17)](https://developer.android.com/about/versions/17/behavior-changes-17)
- **Static final fields.** Apps targeting Android 17 "cannot change static final fields". Reflection gets `IllegalAccessException`, and JNI `SetStatic*Field` crashes the app. — [Android 17 behavior changes (targeting 17)](https://developer.android.com/about/versions/17/behavior-changes-17)

### Inferences
- **Nothing new for BMAP.** Earshot's reflection call `createInsecureRfcommSocket(int)` (unsupported list, allowed) is still the only way to pick an RFCOMM channel. `BluetoothSocketSettings` with `TYPE_RFCOMM` is functionally the same as `create(Insecure)RfcommSocketToServiceRecord(UUID)`: SDP only. It adds nothing for BMAP except a tidier API for the SDP fallbacks on API 36+.
- **"Android 17 opened L2CAP for AirPods" is only half true.** The stack now lets the AirPods channel come up, but opening classic L2CAP still needs a **blocklisted** non-SDK constructor. That means a hidden-API bypass, either a JNI `setHiddenApiExemptions` call as LibrePods does or a bypass library. Roadmap item C2 should be treated as "requires a hidden-API bypass" and as a policy/stability risk: Google can close bypasses in any release. Android 17's static-final rule shows Google is still tightening reflection.
- **Clean-room only.** Any Earshot implementation must be written from our own knowledge or from the AOSP signatures, not from LibrePods code (GPL-3.0).
- **Delivery.** On Pixel the fix reached Android 16 QPR3 through a Play system update, which suggests it lives in the updatable Bluetooth module. Devices whose OEM ships a modified stack may not get it until their Android 17 build.
- **Action item.** Earshot's `BmapConnection` and Bragi read loops must treat `read() == -1` as end-of-stream before Earshot targets API 37.

### Gaps
- I could not find the AOSP commit that fixed issue 371713238: googlesource history pages require sign-in, and the issue tracker is JavaScript-only. In AOSP main, `btif_sock_l2cap.cc` still requests ERTM for classic sockets and `l2c_fcr_chk_chan_modes` still rejects peers without ERTM. The fix must therefore sit elsewhere (configuration negotiation or a flag). Unverified.
- No source confirms whether Samsung One UI 8/9 (Android 16/17) includes the fix. Earshot's test phone is a Galaxy.
- I did not read the API 37 `hiddenapi-flags.csv` directly. The blocklist status of the `BluetoothSocket` constructors and `createInsecureL2capSocket` is inferred from the missing `@UnsupportedAppUsage` and from LibrePods needing an exemption.
- Whether Google Play would object to a JNI hidden-API exemption: LibrePods is on Play, but no policy statement addresses it.

---

## 2. Companion Device Manager in Android 14–17: what an association enables for a headset app

### Takeaway
A CDM association (one system dialog per headset; an already-bonded headset can be picked by MAC address) now unlocks a useful "universal remote" set:
- `setAlias` renames the headset on the phone (API 31+);
- **`BluetoothDevice.connect()` / `disconnect()`** connect or disconnect all profiles (API 37);
- `disconnectAcl` (version 37.2);
- `CompanionDeviceManager.removeBond` unpairs (API 36);
- presence binding via `startObservingDevicePresence` with `onDevicePresenceEvent` (API 36);
- normal-level background-start permissions.

There is no headset device profile. The Android 14–17 additions (system data sync, trust pairing, action requests) target watches, glasses and computers.

### Cited Findings
- **Rename: `setAlias(String)` (API 31).**
  - It requires `BLUETOOTH_CONNECT`; additionally "an app must either have the BLUETOOTH_PRIVILEGED or be associated with the Companion Device manager".
  - The alias is "saved in local storage so that the change is preserved over power cycles". `null` clears it.
  - Return codes include `ERROR_DEVICE_NOT_BONDED`; an empty string throws `IllegalArgumentException`.
  - `getAlias()` (API 30) needs only `BLUETOOTH_CONNECT`. `ACTION_ALIAS_CHANGED` (API 30) is broadcast on change.

  — [BluetoothDevice reference](https://developer.android.com/reference/android/bluetooth/BluetoothDevice#setAlias(java.lang.String))
- **Connect and disconnect (API 37).**
  - `connect()` "Connects all user enabled and supported bluetooth profiles … If the device is not already connected, this will page the device before initiating profile connections". It needs `BLUETOOTH_CONNECT` plus `BLUETOOTH_PRIVILEGED` **or a CDM association**.
  - `disconnect()` (API 37) carries the same requirement.

  — [BluetoothDevice reference](https://developer.android.com/reference/android/bluetooth/BluetoothDevice#connect())
- `disconnectAcl(int transport)` ("Added in version 37.2") disconnects the ACL for `TRANSPORT_AUTO`, `TRANSPORT_BREDR` or `TRANSPORT_LE`. It needs `BLUETOOTH_CONNECT` plus `BLUETOOTH_PRIVILEGED` or "a Companion Device Manager association with this device". — [BluetoothDevice reference](https://developer.android.com/reference/android/bluetooth/BluetoothDevice)
- **Unpair (API 36).** "All apps targeting Android 16 are now able to unpair bluetooth devices using a public API in CompanionDeviceManager". `removeBond(int associationId)` works on an associated device; watch `ACTION_BOND_STATE_CHANGED`. — [Android 16 behavior changes (targeting 16)](https://developer.android.com/about/versions/16/behavior-changes-16); [CompanionDeviceManager reference](https://developer.android.com/reference/android/companion/CompanionDeviceManager)
- **Presence (API 36).**
  - `startObservingDevicePresence(ObservingDevicePresenceRequest)` (API 36) gives callbacks for "BLE range changes (in/out)" and "Bluetooth connection status changes (connected/disconnected)". The caller must implement `CompanionDeviceService`. — [CompanionDeviceManager reference](https://developer.android.com/reference/android/companion/CompanionDeviceManager)
  - `stopObservingDevicePresence` requires `REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE` (a permission since API 31). — [CompanionDeviceManager reference](https://developer.android.com/reference/android/companion/CompanionDeviceManager); [Manifest.permission](https://developer.android.com/reference/android/Manifest.permission)
  - Android 16: the service "will be bound when BLE is in range and Bluetooth is connected and … unbound when BLE is out of range or Bluetooth is disconnected", with a new `onDevicePresenceEvent()` callback. — [Android 16 features](https://developer.android.com/about/versions/16/features)
  - The service must be declared with `android.permission.BIND_COMPANION_DEVICE_SERVICE` and the `android.companion.CompanionDeviceService` intent filter. The system binds it on `EVENT_BLE_APPEARED`, `EVENT_BT_CONNECTED` or `EVENT_SELF_MANAGED_APPEARED`. Binding "elevates the priority of the process … may prevent the Low-memory killer from killing the process". — [CompanionDeviceService reference](https://developer.android.com/reference/android/companion/CompanionDeviceService)
  - `onDeviceAppeared` / `onDeviceDisappeared` are deprecated in API 36 in favour of `onDevicePresenceEvent(DevicePresenceEvent)`. — [CompanionDeviceService reference](https://developer.android.com/reference/android/companion/CompanionDeviceService)
  - `DevicePresenceEvent` events:
    - API 36: `EVENT_BLE_APPEARED`, `EVENT_BLE_DISAPPEARED` (1), `EVENT_BT_CONNECTED`, `EVENT_BT_DISCONNECTED` (3), `EVENT_SELF_MANAGED_APPEARED`/`DISAPPEARED` (5);
    - version 36.1: `EVENT_ASSOCIATION_REMOVED` (6);
    - API 37: `EVENT_SELF_MANAGED_NEARBY` / `NOT_NEARBY` (8).

    — [DevicePresenceEvent reference](https://developer.android.com/reference/android/companion/DevicePresenceEvent)
  - `ObservingDevicePresenceRequest` takes either `setAssociationId` or `setUuid`. UUID mode needs `REQUEST_OBSERVE_DEVICE_UUID_PRESENCE`, which is "Not for use by third-party applications". — [ObservingDevicePresenceRequest reference](https://developer.android.com/reference/android/companion/ObservingDevicePresenceRequest); [Manifest.permission](https://developer.android.com/reference/android/Manifest.permission)
- **Background permissions** (all protection level **normal**):
  - `REQUEST_COMPANION_RUN_IN_BACKGROUND` implies starting a foreground service from the background;
  - `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`;
  - `REQUEST_COMPANION_USE_DATA_IN_BACKGROUND`.

  — [Manifest.permission](https://developer.android.com/reference/android/Manifest.permission)
- **The association request.**
  - "When scanning for a single device with a specific BluetoothDeviceFilter mac address, bonded devices are also searched" (`isSingleDevice`).
  - Device profiles include APP_STREAMING, AUTOMOTIVE_PROJECTION and COMPUTER (all 33), GLASSES and NEARBY_DEVICE_STREAMING (34), and FITNESS_TRACKER and MEDICAL (37). There is **no audio/headset profile**.
  - API 37 adds `getExtraPermissions()` for "non-profile device".

  — [AssociationRequest reference](https://developer.android.com/reference/android/companion/AssociationRequest)
- **Android 16 discovery timeout.** On Android 16 companion apps "are no longer directly notified of discovery timeout". The user sees a dialog, the app gets `RESULT_USER_REJECTED`, and the search is longer than 20 s. — [Android 16 behavior changes (all apps)](https://developer.android.com/about/versions/16/behavior-changes-all)
- **API 37 action requests.** `CompanionDeviceService.onActionRequested(AssociationInfo, ActionRequest)` carries `REQUEST_NEARBY_SCANNING`, `REQUEST_NEARBY_ADVERTISING` or `REQUEST_TRANSPORT` with `OP_ACTIVATE`/`OP_DEACTIVATE`; the app answers with `notifyActionResult`. — [ActionRequest reference](https://developer.android.com/reference/android/companion/ActionRequest); [CompanionDeviceManager reference](https://developer.android.com/reference/android/companion/CompanionDeviceManager)
- **System data sync.** The flags are `FLAG_CALL_METADATA` (34) and, from API 37, `FLAG_TASK_CONTINUITY`, `FLAG_UNIVERSAL_MODES` and `FLAG_AIRPLANE_MODE`. They need an attached system data transport (`attachSystemDataTransport`, `DELIVER_COMPANION_MESSAGES`). Trust pairing (`requestDeviceTrustPairing`, version 37.2) targets cross-device authentication and screen automation. — [CompanionDeviceManager reference](https://developer.android.com/reference/android/companion/CompanionDeviceManager)
- **Manifest feature.** `getMyAssociations`, `disassociate` and `enableSystemDataSyncForTypes` require a `uses-feature FEATURE_COMPANION_DEVICE_SETUP` declaration. — [CompanionDeviceManager reference](https://developer.android.com/reference/android/companion/CompanionDeviceManager)
- **Codec calls stay SystemApi.**
  - `BluetoothA2dp.getCodecStatus` / `setCodecConfigPreference` are documented as accepting a CDM association instead of `BLUETOOTH_PRIVILEGED` (`@RequiresPermission(..., conditional = true)`), but they remain `@SystemApi @hide`.
  - So are `setAvrcpAbsoluteVolume`, `isOptionalCodecsEnabled`, `setOptionalCodecsEnabled`, `getDynamicBufferSupport` and `setBufferLengthMillis`.

  — [AOSP BluetoothA2dp.java](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/framework/java/android/bluetooth/BluetoothA2dp.java)

### Inferences
- **Best new universal feature: a "Connect" button (API 37).** `connect()` after association gives Earshot a real "connect / switch to this headset" button and a "disconnect" button for any brand. That needs only an association, no reflection.
- **Gating with minSdk 28:**
  - rename: API 31+;
  - unpair: API 36+;
  - presence binding: API 36+, with the older `onDeviceAppeared` flow on API 31–35;
  - connect/disconnect: API 37+;
  - ACL disconnect: version 37.2.
- **Association onboarding.** A per-headset association with `setSingleDevice(true)` and a MAC filter should not require re-pairing, because bonded devices are searched. This fits onboarding item F4 in the roadmap.
- **Wiring.** The presence service can replace the ACL receiver for associated headsets. `EVENT_BT_CONNECTED` / `EVENT_BT_DISCONNECTED` should work for classic-only headsets; BLE appear events are a bonus for dual-mode devices.

### Gaps
- No source says whether a foreground service started from a CDM-bound `CompanionDeviceService` gets while-in-use (WIU) capability. This matters for Android 17 background audio hardening (section 6).
- The reference page shows no protection level for `REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE`.
- Not tested: whether `connect()` on a multipoint headset steals it from another phone, and whether OEM stacks honour it.

---

## 3. Media-button remapping for any headset: how Key Mapper works, its limits, and Play's AccessibilityService policy

### Takeaway
Key Mapper (GPL-3.0, 10M+ Play installs) remaps keys with a **non-tool AccessibilityService that filters key events**. That only sees keys travelling through the input pipeline.

In the AOSP Bluetooth stack, a headset's AVRCP play/pause/next/previous presses never become input events. The stack injects them straight into `MediaSessionManager.dispatchMediaKeyEvent()`, which routes them to the media session that last played audio. So no ordinary app can intercept them; Key Mapper's own FAQ says many Bluetooth headphones "aren't supported … out of the box".

The routes that do work universally are:
- the HFP voice-recognition / assistant button (Key Mapper's "Voice assistant" trigger: double press yes, long press no);
- vendor protocols.

Earshot's UI line is essentially correct for Bluetooth headphones. Play allows a non-tool remapper if it uses in-app disclosure plus a Console declaration, but Android 17's Advanced Protection Mode (AAPM) blocks non-tool accessibility services.

### Cited Findings
- **The AVRCP path in AOSP.**
  - The AVRCP target hands passthrough key presses to `media_interface_->SendKeyEvent(...)`. — [AOSP system/profile/avrcp/device.cc](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/system/profile/avrcp/device.cc)
  - The Java side builds a `KeyEvent` and calls `mMediaSessionManager.dispatchMediaKeyEvent(event, false)` (`MediaPlayerList.sendMediaKeyEvent`). — [AOSP MediaPlayerList.java](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/audio_util/MediaPlayerList.java)
  - AOSP main `btif_rc.cc` contains no `uinput` code (a grep for uinput found nothing). The legacy keylayout `AVRCP.kl` ("Key layout used for Bluetooth AVRCP support": key 200 MEDIA_PLAY, 201 MEDIA_PAUSE, 163 MEDIA_NEXT …) still exists in frameworks/base. — [AOSP btif_rc.cc](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/system/btif/src/btif_rc.cc); [AOSP AVRCP.kl](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/data/keyboards/AVRCP.kl)
- **Media button routing** (developer guide):
  1. the foreground activity first;
  2. then, on Android 8.0+, "the last app with a MediaSession that played audio locally". An inactive session with a media-button receiver gets restarted; otherwise "the system discards the media button event".

  — [Responding to media buttons](https://developer.android.com/media/legacy/media-buttons)
- **Key Mapper's accessibility service** declares `canRequestFilterKeyEvents`, `canPerformGestures`, `canRetrieveWindowContent` and `canRequestFingerprintGestures`. It does **not** set `isAccessibilityTool`. — [Key Mapper config_accessibility_service.xml](https://github.com/keymapperorg/KeyMapper/blob/develop/base/src/main/res/xml/config_accessibility_service.xml) (repo licence GPL-3.0 per GitHub API)
- **Key Mapper on Play:**
  - "10M+ Downloads", "Updated on Sep 27, 2026".
  - Claims support for "Headsets and headphones" with an asterisk: "Most devices are already supported … Let us know if it's not working".
  - Includes an in-listing "Security and accessibility services" disclosure: the service "will monitor key strokes", "will NOT collect any user data or connect to the internet", and "is only triggered by the user when pressing a physical key".

  — [Key Mapper on Google Play](https://play.google.com/store/apps/details?id=io.github.sds100.keymapper)
- Key Mapper FAQ: "Many Bluetooth devices (like headphones) aren't supported by Key Mapper out of the box. Bluetooth keyboards work most of the time." — [KeyMapperDocs faq.md](https://github.com/keymapperorg/KeyMapperDocs/blob/develop/docs/faq.md) (docs repo: no licence declared)
- Screen-off option: "Detect trigger when screen is off. ROOT only." Buttons detectable with the screen off include Volume Up/Down and "Headset button". — [KeyMapperDocs trigger-when-screen-off.md](https://github.com/keymapperorg/KeyMapperDocs/blob/develop/docs/includes/trigger-options/trigger-when-screen-off.md)
- **Gestures:**
  - click types are short, long (500 ms default) and double (300 ms default);
  - parallel triggers can only be short or long.
  - The "Voice assistant" trigger fires from "the hands-free voice button on keyboards/headsets" once Key Mapper is chosen as the assistant: "It is not possible to create long-press key maps with this trigger! But you can do double press."

  — [KeyMapperDocs keymaps.md](https://github.com/keymapperorg/KeyMapperDocs/blob/develop/docs/user-guide/keymaps.md)
- The Key Mapper source tree has an evdev path (`EvdevDevicesDelegate`, `EvdevTriggerKey`; Rust `evdev_manager`) used with Shizuku/root. Its test data includes a Pixel 7 `AVRCP.kl`. — [KeyMapper repo](https://github.com/keymapperorg/KeyMapper)
- **Google Play AccessibilityService policy:**
  - `isAccessibilityTool` is only for services "designed to help people with disabilities". "automation tools" and "assistants" are listed as **not** accessibility tools.
  - Non-tools need a prominent in-app disclosure (not only in the store description or privacy policy) and "affirmative user action for consent".
  - Apps targeting API 31+ with an AccessibilityService must complete the Play Console declaration (non-tools submit a video of the disclosure flow) and resubmit if their use changes.
  - Prohibited for non-tools: any use that lets an app "autonomously initiate, plan, and execute actions". Allowed: "deterministic, rule-based automation, where behavior follows a static, human-defined script" ("If Trigger X occurs, perform Action Y").
  - The declaration requirement dates from 2021-11-03.

  — [Play Console Help: Use of the AccessibilityService API](https://support.google.com/googleplay/android-developer/answer/10964491)
- **AAPM in Android 17.** With Advanced Protection Mode on, AccessibilityService access is limited to apps declared as accessibility tools. This was reported for Android 17 Beta 2 (March 2026) and again in an October 2026 Google announcement. One report says existing grants are revoked; single source. — [The Hacker News, 2026-03](https://thehackernews.com/2026/03/android-17-blocks-non-accessibility.html); [The Hacker News, 2026-10](https://thehackernews.com/2026/10/android-17-advanced-protection-locks.html); [Security Affairs](https://securityaffairs.com/189497/security/advanced-protection-mode-in-android-17-prevents-apps-from-misusing-accessibility-services.html)
- Apps can detect AAPM with the `AdvancedProtectionManager` API. — [Android 17 features](https://developer.android.com/about/versions/17/features)

### Inferences
- **What an accessibility remapper sees.** It sees wired-headset keys (`KEYCODE_HEADSETHOOK` / `KEYCODE_MEDIA_*`), USB and BT-HID keyboard keys, and any OEM stack that still injects AVRCP presses through an input device. It does **not** see AOSP AVRCP passthrough from Bluetooth headphones, so for the core Bluetooth headphone case it adds little.
- **Copy suggestion.** Earshot's copy could say: "Android sends your headphones' play/pause and track buttons straight to the music app, so other apps can't remap them. Earshot can remap the assistant button and, on supported models, the headphones' own controls."
- **The "media-session owner" trick.** Earshot could hold an active `MediaSession` and play (near-)silent audio so it is "the last app that played audio", then re-send the keys to the real player through `MediaController` (session access needs notification-listener permission). This is fragile:
  - any real playback takes the session back;
  - it hijacks the system media controls;
  - on Android 17 background playback needs a foreground service with WIU, and target 37 is stricter (section 6).

  Not recommended.
- **Volume buttons.** With absolute volume, a headset's volume buttons arrive as AVRCP volume changes, not key events. An app can only observe the resulting stream-volume change, not intercept or remap it.
- **If Earshot adds an AccessibilityService** (for wired or other keys), it would be a non-tool. It would need the disclosure, the Console declaration and video, and a fallback when AAPM is on.

### Gaps
- Samsung One UI's AVRCP handling (whether it injects through an input device that accessibility could see) is unverified. Key Mapper's "most devices supported" claim may reflect OEM stacks or wired headsets.
- Not verified this session: that `MediaSessionManager.setOnMediaKeyListener` is SystemApi with a privileged permission, and the exact `MediaSessionService` routing for `asSystemService = false`.
- How Key Mapper's Play declaration is worded is not public.

---

## 4. Audio-side universal features: routing, device types, Spatializer, volume and effects, Android 16/17 audio changes

### Takeaway
What normal apps get:
- communication-device routing (`setCommunicationDevice`, API 31; SCO off-call is now always supported per API 37);
- device-type discovery (new BLE hearing-aid and "central" types in API 37);
- read-only Spatializer state;
- the HFP codec in use (`BluetoothHeadset.getCodecType`, version 37.2);
- LE Audio grouping (`getGroupId`).

Bit-perfect mixer attributes are USB-only. LE Audio broadcast (Auracast/sharing), A2DP codec, absolute-volume and buffer controls are all SystemApi. For "too loud at the lowest step" the only public lever is a gain-reducing effect: per session through the effect-control broadcast, or the deprecated session 0. Android 17's background audio hardening silently ignores volume APIs from background apps without a qualifying foreground service.

### Cited Findings
- **Communication routing.**
  - `AudioManager.setCommunicationDevice(AudioDeviceInfo)` (API 31) selects the device for voice/video calls. Only sink devices can be given; "The matching source device is selected automatically".
  - The choice holds while the process lives or until `clearCommunicationDevice()`. With several requesters, priority goes to the app controlling the audio mode (`MODE_IN_COMMUNICATION`).
  - `getAvailableCommunicationDevices()` has existed since API 31.

  — [AudioManager reference](https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo))
- `isBluetoothScoAvailableOffCall()` is deprecated in API 37: "the framework always supports SCO off call". — [AudioManager reference](https://developer.android.com/reference/android/media/AudioManager)
- **Device types:**
  - `TYPE_BLE_HEARING_AID` (33), `TYPE_BLE_CENTRAL` (34: Android acting as a Bluetooth audio *peripheral* in LE Audio, A2DP or HFP) and `TYPE_BLE_CENTRAL_BROADCAST` (35) were added in API 37;
  - `TYPE_MULTICHANNEL_GROUP` in API 36;
  - `TYPE_BLE_SPEAKER` in API 31;
  - `getSpeakerLayoutChannelMask()` (API 36) is valid only for built-in speakers.

  — [AudioDeviceInfo reference](https://developer.android.com/reference/android/media/AudioDeviceInfo)
- `AudioManager.getSupportedDeviceTypes(direction)` was added in API 35. — [AudioManager reference](https://developer.android.com/reference/android/media/AudioManager)
- **Bit-perfect is USB only.** `setPreferredMixerAttributes` (API 34) needs `MODIFY_AUDIO_SETTINGS`; "Currently, only AudioAttributes.USAGE_MEDIA is supported" and "Currently, only USB device will be allowed". `getSupportedMixerAttributes`: "only USB devices are guaranteed to expose configurable mixer attributes". — [AudioManager reference](https://developer.android.com/reference/android/media/AudioManager)
- `getDirectProfilesForAttributes(AudioAttributes, int uid)` was added in version 37.2. — [AudioManager reference](https://developer.android.com/reference/android/media/AudioManager)
- **Assistant audio:**
  - `STREAM_ASSISTANT` (API 37), `MODE_ASSISTANT_CONVERSATION` (version 36.1) and `canManageAssistantAudio()` (version 37.2, request access via `Settings.ACTION_MANAGE_ASSISTANT_AUDIO`). — [AudioManager reference](https://developer.android.com/reference/android/media/AudioManager)
  - Android 17 adds a dedicated Assistant volume stream for `USAGE_ASSISTANT`, controllable "with connected Bluetooth peripherals" during an assistant session. — [Android 17 features](https://developer.android.com/about/versions/17/features)
- **Spatializer is read-only for apps:** `isAvailable`, `isEnabled`, `getImmersiveAudioLevel`, `isHeadTrackerAvailable`, `canBeSpatialized`, state and head-tracker listeners, and `getSpatializedChannelMasks` (API 36). There is no setter. — [Spatializer reference](https://developer.android.com/reference/android/media/Spatializer)
- **Effects:**
  - "attaching an Equalizer to the global audio output mix by use of session 0 is deprecated". — [Equalizer reference](https://developer.android.com/reference/android/media/audiofx/Equalizer)
  - `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION`: players "can broadcast this intent before starting playback", with mandatory `EXTRA_PACKAGE_NAME` and `EXTRA_AUDIO_SESSION`. — [AudioEffect reference](https://developer.android.com/reference/android/media/audiofx/AudioEffect)
- **How third-party equalizers attach** (Esper analysis):
  - session 0 is deprecated but "some devices still support it" (Wavelet "Legacy mode" tries it);
  - since Android 8.0 the equalizer must be in the foreground or run a foreground service to receive the session broadcasts, and "many [players] don't" send them;
  - Wavelet's "enhanced session detection" needs `DUMP` granted over adb;
  - MediaProjection capture (PhantomAmp) needs Android 10+ and the microphone permission, blocks screen recording and adds latency;
  - DynamicsProcessing arrived in Android 9.

  — [Esper, "Android equalizer apps inconsistent"](https://www.esper.io/blog/android-equalizer-apps-inconsistent)
- **LE Audio broadcast (Auracast / sharing) is SystemApi.**
  - `BluetoothLeBroadcastAssistant` is `@SystemApi`. — [AOSP BluetoothLeBroadcastAssistant.java](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/framework/java/android/bluetooth/BluetoothLeBroadcastAssistant.java)
  - The public `android.bluetooth` class list has no `BluetoothLeBroadcast*`. Apps only get the feature checks `isLeAudioSupported()`, `isLeAudioBroadcastSourceSupported()` and `isLeAudioBroadcastAssistantSupported()` (API 33). — [BluetoothAdapter reference](https://developer.android.com/reference/android/bluetooth/BluetoothAdapter)
- **LE Audio grouping.** `BluetoothLeAudio.getGroupId(device)` (API 33): "Devices with same group id belong to same group (i.e left and right earbud)"; also `getConnectedGroupLeadDevice` (33). — [BluetoothLeAudio reference](https://developer.android.com/reference/android/bluetooth/BluetoothLeAudio)
- **HFP codec.** `BluetoothHeadset.getCodecType(BluetoothDevice)` (version 37.2) needs only `BLUETOOTH_CONNECT`. It returns `CODEC_TYPE_CVSD`, `MSBC`, `LC3_SWB`, `VENDOR_SPECIFIC` or `UNSUPPORTED`, and is available after codec negotiation "regardless of whether a SCO audio connection is currently active". — [BluetoothHeadset reference](https://developer.android.com/reference/android/bluetooth/BluetoothHeadset#getCodecType(android.bluetooth.BluetoothDevice))
- `BluetoothA2dp.getSupportedCodecTypes()` (API 35) is public but "Requires BLUETOOTH_PRIVILEGED". — [BluetoothA2dp reference](https://developer.android.com/reference/android/bluetooth/BluetoothA2dp)
- Android 15's `LoudnessCodecController` (CTA-2075) applies loudness metadata to an app's *own* AAC decoding session. — [Android 15 features](https://developer.android.com/about/versions/15/features)
- Absolute-volume writes (`setAvrcpAbsoluteVolume`) and A2DP dynamic-buffer (latency) controls are `@SystemApi` with `BLUETOOTH_PRIVILEGED`. — [AOSP BluetoothA2dp.java](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/framework/java/android/bluetooth/BluetoothA2dp.java)

### Inferences
- **"Too loud at the lowest step".** The implementable fix is an Earshot "volume floor" effect: a DynamicsProcessing input-gain cut and/or limiter, applied per session through `audio.AudioSessionReceiver` for players that broadcast, with session 0 as a device-dependent fallback.
  - The volume-step count (a system property) and absolute volume (a developer option / SystemApi) cannot be changed.
  - The Android 17 hardening list names playback, focus and volume APIs but not AudioEffect, so effects should keep working. This is unverified.
- **No Bluetooth wins.** Bit-perfect, Auracast, codec switching and gaming/low-latency modes offer nothing to normal apps on Bluetooth. At most Earshot can show "LE Audio sharing supported on this phone" from the feature checks.
- **Call-quality label.** The HFP codec label (narrowband CVSD / wideband mSBC / super-wideband LC3) is a cheap universal call-quality indicator on version 37.2+.

### Gaps
- No source found for OEM (Samsung One UI 8/9) support of session-0 DynamicsProcessing.
- No public Android 16/17 intent for launching LE Audio sharing found (not searched exhaustively).
- No public API for a Bluetooth "gaming / low-latency" mode found.

---

## 5. Quick universal wins on any connected headset, and their blockers

### Takeaway
All five ideas can be built with public APIs:
- L/R "find my earbud" tone;
- balance/mono check;
- latency estimate;
- mic test;
- connection history.

Android 16/17 adds richer connection-history data: disconnect reasons, bond-loss and encryption intents, LE Appearance. The main new blocker is Android 17 background audio hardening: tones must start from a visible activity or a qualifying foreground service. Unverified risks are user mono/balance settings and a bud sitting in its case.

### Cited Findings
- **Background audio (Android 17).** Playback is silenced, focus fails and volume calls are ignored unless the app has a visible activity or a non-`SHORT_SERVICE` foreground service (all apps on 17). For target 37 the background foreground service must also have WIU. Foreground services started from the background get WIU for "notification clicks, widget interactions, or media key events from an external device". — [Android 17 background audio hardening](https://developer.android.com/about/versions/17/changes/bg-audio)
- **Mic test (HFP / LE).** Route with `setCommunicationDevice` (sink only; the source follows automatically). — [AudioManager reference](https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo)) Label the codec with `BluetoothHeadset.getCodecType` (version 37.2). — [BluetoothHeadset reference](https://developer.android.com/reference/android/bluetooth/BluetoothHeadset#getCodecType(android.bluetooth.BluetoothDevice))
- **Connection-history data:**
  - `BluetoothA2dp.EXTRA_DISCONNECTED_REASON` (API 37) gives a failure reason on connection-state changes: `ERROR_SDP_DISCOVERY_FAILED`, `ERROR_STREAM_CONNECTION_FAILED`, `ERROR_ROLE_SWITCH_FAILED`, `ERROR_AVDTP_DISCOVERY_FAILED`, `ERROR_INSUFFICIENT_RESOURCES`. — [BluetoothA2dp reference](https://developer.android.com/reference/android/bluetooth/BluetoothA2dp)
  - `BluetoothHeadset` also has `EXTRA_DISCONNECTED_REASON` (API 37). — [BluetoothHeadset reference](https://developer.android.com/reference/android/bluetooth/BluetoothHeadset)
  - `ACTION_KEY_MISSING` (API 36) needs only `BLUETOOTH_CONNECT` from Android 16 (before that, `BLUETOOTH_PRIVILEGED`). `EXTRA_BOND_LOSS_REASON` (version 36.1) adds a reason code.
  - `ACTION_ENCRYPTION_CHANGE` (API 36) carries key size and algorithm.
  - Also: `getEncryptionStatus()` and `getKeyMissingCount()` (version 36.1), `isConnected(int transport)` (version 36.1) and `getBondStatus(int transport)` (API 37).

  — [BluetoothDevice reference](https://developer.android.com/reference/android/bluetooth/BluetoothDevice)
- **Device-type icon.** `getLeAppearance()` and `ACTION_LE_APPEARANCE_CHANGED` (version 37.2) return the cached GAP Appearance even when disconnected. `EXTRA_LE_APPEARANCE` is also included in audio-profile `ACTION_*_CONNECTION_STATE_CHANGED` intents. — [BluetoothDevice reference](https://developer.android.com/reference/android/bluetooth/BluetoothDevice)
- **Autonomous re-pairing (Android 17).** `ACTION_PAIRING_REQUEST` carries `EXTRA_PAIRING_CONTEXT` (`PAIRING_CONTEXT_REPAIRING` = system re-pairing), and `ACTION_KEY_MISSING` is now sent only if re-pairing fails. — [Android 17 behavior changes (all apps)](https://developer.android.com/about/versions/17/behavior-changes-all); [BluetoothDevice reference](https://developer.android.com/reference/android/bluetooth/BluetoothDevice)
- **Earbud pairs.** LE Audio earbuds that form a pair share a group id (`getGroupId`). — [BluetoothLeAudio reference](https://developer.android.com/reference/android/bluetooth/BluetoothLeAudio)
- **Connect / disconnect button.** `connect()` / `disconnect()` (API 37, needs a CDM association) enable "reconnect" and "disconnect" buttons. — [BluetoothDevice reference](https://developer.android.com/reference/android/bluetooth/BluetoothDevice#connect())

### Inferences
- **Find my earbud (L/R tone).** Play a stereo `AudioTrack` with one channel zeroed (or with a channel volume set), routed with `setPreferredDevice` to the headset's `AudioDeviceInfo` (A2DP or BLE headset type). Start it from the Earshot UI, the widget, or a notification action so it keeps WIU on Android 17.
  - Likely blockers (not sourced): Android's accessibility "Mono audio" and "Audio balance" settings are applied after mixing and would defeat a one-sided tone. A bud in a closed case is usually disconnected or not rendering.
  - With LE Audio each bud receives its own channel, so one-sided playback should map cleanly.
- **Balance/mono check.** Same mechanism: alternate L, R and both channels, then ask the user.
- **Latency estimate.** Option 1: compare `AudioTrack.getTimestamp()` presentation time with write time (whether the Bluetooth sink delay is included is unverified). Option 2: an acoustic loopback (phone mic near the bud, measure click-to-capture). Both are estimates.
- **Connection history.** The ACL receiver plus the API 36/37 extras above give a timeline with reasons (bond lost, encryption weak, SDP or AVDTP failure). That is a useful, brand-agnostic diagnostics screen.

### Gaps
- I found no source on the exact behaviour of mono-audio/balance settings with one-sided `AudioTrack` output, or on whether reading `master_mono` / `master_balance` is allowed for third-party apps.
- I found no source on whether `AudioTrack.getTimestamp()` includes A2DP/LE sink delay reports on current Android.
- Whether a QS tile click grants WIU is not listed in the Android 17 background audio page.

---

## 6. Android 15–17 behaviour changes and restrictions that would hurt such an app

### Takeaway
Ranked by impact on Earshot:
1. **Android 17 background audio hardening** (all apps; stricter for target 37): automations that change volume or play tones from the background now fail silently.
2. **RFCOMM `read()` returning -1** (target 37).
3. **AAPM blocking non-tool accessibility services.**
4. Static-final reflection lock (target 37).
5. Autonomous re-pairing.
6. Android 15's foreground-service-from-`BOOT_COMPLETED` and audio-focus rules.

Android 17's behaviour-change pages list **no** new Bluetooth scan or `connectedDevice` foreground-service restrictions. The new local-network permission does not apply to an app without network use.

### Cited Findings
- **Background audio hardening (Android 17, all apps):**
  - Audio playback, focus and volume calls need a visible activity or a non-`SHORT_SERVICE` foreground service.
  - Target 37 also needs WIU on that service (waived only for exact-alarm apps on `USAGE_ALARM`).
  - Silently ignored: `setStreamVolume`, `setStreamMute`, `adjustStreamVolume`, `adjustVolume`, `adjustSuggestedStreamVolume`, `setRingerMode`.
  - `AudioTrack.write` / AAudio playback is silenced; `requestAudioFocus` returns `AUDIOFOCUS_REQUEST_FAILED`.
  - A foreground service started from `BOOT_COMPLETED` and then using audio "will be suppressed".
  - Test with `adb shell cmd audio set-enable-hardening <enable|disable|throw>`; look for `AudioHardening` in dumpsys.

  — [Android 17 background audio hardening](https://developer.android.com/about/versions/17/changes/bg-audio); [Android 17 behavior changes (all apps)](https://developer.android.com/about/versions/17/behavior-changes-all)
- RFCOMM `read()` returns -1 on close or drop for target 37. — [Android 17 behavior changes (targeting 17)](https://developer.android.com/about/versions/17/behavior-changes-17)
- Static final fields cannot be changed through reflection or JNI for target 37. — [Android 17 behavior changes (targeting 17)](https://developer.android.com/about/versions/17/behavior-changes-17)
- **Local network permission.** `ACCESS_LOCAL_NETWORK` (dangerous, in the `NEARBY_DEVICES` group) is mandatory for target 37 to reach LAN devices; it is opt-in on Android 16. — [Android 17 behavior changes (targeting 17)](https://developer.android.com/about/versions/17/behavior-changes-17); [Manifest.permission](https://developer.android.com/reference/android/Manifest.permission)
- **Autonomous re-pairing** after bond loss brings new pairing-context extras and changes the timing of `ACTION_KEY_MISSING`. — [Android 17 behavior changes (all apps)](https://developer.android.com/about/versions/17/behavior-changes-all)
- **App memory limits** based on device RAM (Android 17, all apps). The exit reason shows "MemoryLimiter:AnonSwap". — [Android 17 behavior changes (all apps)](https://developer.android.com/about/versions/17/behavior-changes-all)
- **AAPM** restricts AccessibilityService to accessibility tools (see section 3). — [The Hacker News, 2026-10](https://thehackernews.com/2026/10/android-17-advanced-protection-locks.html)
- **Android 16 changes:**
  - bond-loss intents (`ACTION_KEY_MISSING`, `ACTION_ENCRYPTION_CHANGE`) and the CDM `removeBond`; — [Android 16 behavior changes (targeting 16)](https://developer.android.com/about/versions/16/behavior-changes-16)
  - CDM discovery-timeout change, and manifest `android:priority` ordering no longer guaranteed across processes. — [Android 16 behavior changes (all apps)](https://developer.android.com/about/versions/16/behavior-changes-all)
- **Android 15 changes:**
  - `BOOT_COMPLETED` receivers may not start `dataSync`, `camera`, `mediaPlayback`, `phoneCall`, `mediaProjection` or `microphone` foreground services (`connectedDevice` is not on the list);
  - target-35 apps must be the top app or run a foreground service to request audio focus.

  — [Android 15 behavior changes (targeting 15)](https://developer.android.com/about/versions/15/behavior-changes-15)
- **Absence finding.** The headings of the Android 17 behaviour-change pages ("all apps" and "targeting 17") contain no Bluetooth-scan, `BLUETOOTH_SCAN neverForLocation` or foreground-service-type changes. Connectivity items are only RFCOMM `read()` and autonomous re-pairing. — [Android 17 behavior changes (all apps)](https://developer.android.com/about/versions/17/behavior-changes-all); [Android 17 behavior changes (targeting 17)](https://developer.android.com/about/versions/17/behavior-changes-17)

### Inferences
- **Volume automations.** Earshot automations that set volume or play a confirmation tone when a headset connects (from `BluetoothConnectionReceiver` or a CDM presence callback) will fail silently on Android 17 unless they run inside a foreground service with WIU. A background-started `connectedDevice` service probably lacks WIU (see the section 2 gap). Mitigations:
  - do audio actions from user-initiated paths (widget, tile, notification action);
  - or post a notification that the user taps.
- **Local network permission** is irrelevant: Earshot has no INTERNET permission and uses no IP LAN.
- **Target 37 checklist.** Play's yearly target-SDK rule (target 36 from 2026-08-31, per earlier research) implies target 37 around August 2027. The RFCOMM -1, static-final and WIU-audio changes should be on that checklist.

### Gaps
- I found no official Android or Play statement on the timeline for requiring target SDK 37.
- I did not check whether Android 17 changes `connectedDevice` foreground-service prerequisites in the foreground-service-types docs; only the behaviour-change pages were read.
