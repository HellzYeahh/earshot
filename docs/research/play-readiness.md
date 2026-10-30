# Google Play readiness: research checklist (1.0 plan item 5)

Researched 2026-10-29 for roadmap item 5 ("Play Store readiness", `docs/roadmap.md:98-112`). This was read-only: no files were edited, and no Gradle or adb was run. Line numbers refer to the `main` working tree on 2026-10-29, which has uncommitted changes. Sources were read on the same day. Where a source is not Google's own, the item says so.

Each item has a tag:
- **[code]**: change code, the manifest or the tools.
- **[console]**: fill in a Play Console form.
- **[listing]**: store listing text or images.
- **[decision]**: the user decides.
- **[none]**: nothing to do. Either it is already compliant or it does not apply. Some of these still name a check to run.

## Summary

- **Target API.** Play requires API 36 for new apps and updates since 2026-08-31. Developers could ask for an extension to 2026-11-01. Earshot targets 36 (`app/build.gradle.kts:47`), so it is compliant today. **Play does not require API 37 yet.** The requirements page names no date for 37. Going by the yearly pattern, the guess is around 2027-08-31, but Google has not announced it.
- **Moving to 37 is still worth doing for 1.0**, as its own commit with a phone pass. Six changes affect Earshot:
  - background audio needs while-in-use (WIU) status: call boost and the morning volume restore (A1);
  - RFCOMM `read()` returns -1 when the link drops (A2);
  - non-SDK reflection could be blocked, which is a risk to the Bose connect path (A3);
  - large screens can no longer opt out of resizing (A4);
  - the location-button rule applies to apps targeting 37 (B4).
  - Nothing new on foreground services, edge-to-edge or predictive back.
- **New since the roadmap was written:**
  - Play's precise-location declaration. It opens in November 2026 and compliance is mandatory from 2027-01-27. Earshot requests `ACCESS_FINE_LOCATION`.
  - The closed test that new personal developer accounts must run: 12 testers for 14 days.
  - The release gate checks the APKs but not the `.aab` that is actually uploaded.
  - The Billing library reportedly adds INTERNET to the manifest.
- **Already in place:**
  - `QUERY_ALL_PACKAGES` is gone and replaced by `<queries>`.
  - Foreground-service types are declared in the manifest and passed to `startForeground`.
  - The alarm degrades gracefully without the full-screen permission.
  - Wake alarms can only be switched on with exact-alarm access.
  - The Bluetooth scan is marked `neverForLocation`, and there is no background location.
  - There is no INTERNET permission, and the release gate enforces that.
  - Flavours and signing are set up.
  - The not-affiliated text is in About and the README.
  - The two native libraries are 16 KB aligned.

---

## A. Target API 37 (Android 17) behaviour changes that touch Earshot

### A1. Background audio hardening (all apps on 17; stricter at target 37) **[code]**
- **Rule.** Playback, audio focus and volume calls need a visible activity or a foreground service that is not `SHORT_SERVICE`. At target 37 the foreground service must also have while-in-use (WIU) status.
  - A service gets WIU if it was started while the app was visible, or from a notification tap, a widget or a media key.
  - Most services started from the background do not get WIU.
  - The WIU rule is waived for streams with `USAGE_ALARM` when the app holds the exact-alarm permission.
  - A failed call does nothing and reports nothing; a focus request returns `AUDIOFOCUS_REQUEST_FAILED`.
  - In `set-enable-hardening enable` mode the alarm waiver is *not* honoured.
  - Sources: https://developer.android.com/about/versions/17/changes/bg-audio, https://developer.android.com/about/versions/17/behavior-changes-17
- **Earshot today, and what to change:**
  1. **Call boost.**
     - Today: `call/CallAudioRepositoryImpl.kt:313` calls `setStreamVolume(STREAM_VOICE_CALL)` from the headset service. That service is often started in the background by `ACL_CONNECTED` or companion presence (`service/HeadphoneService.kt:252-262`), so it has no WIU. At target 37 the call is ignored. The only sign is a log line (`:323`).
     - Change: when the app is not visible, mark the boost as "needs Earshot open on Android 17" in `CallAudioState`. Optionally, post a "Boost call volume" notification action, because a tap gives WIU. The headset-side boost (`maybeRaiseHeadset`) is not an audio API and keeps working.
  2. **Morning volume restore.**
     - Today: `sleep/SleepRepositoryImpl.kt:579-580` `canTouchVolume()` follows the target-36 rule. It counts `ring != null` (the alarm service, started by AlarmManager in the background) and `headphones.state.value.isAudioActive` (the headset service, usually started in the background). At target 37 neither gives WIU for `STREAM_MUSIC`. The alarm waiver covers alarm streams only.
     - Change: at target 37 or higher, `canTouchVolume()` should be true only when the app is visible, or the night's service was started from the visible tab, a notification tap or the launcher shortcut. Otherwise keep the restore pending, as `PhoneNightExtras.kt:121-130` already allows.
     - The alarm's Stop runs through `PendingIntent.getForegroundService` (`sleep/SleepNotifications.kt:116-120`). That is a notification tap, so it gives WIU and the restore may run there.
  3. **A night rebuilt after process death.**
     - Today: `SleepAlarmReceiver` restarts `SleepService` from an exact-alarm broadcast, in the background and without WIU. `SleepSoundPlayer` (`USAGE_MEDIA`, `sleep/SleepSoundPlayer.kt:248`) is then silenced, and `OtherAudioPauser`'s focus request and volume call (`sleep/OtherAudioPauser.kt:32,46`) fail.
     - Change: detect this case, for example from a flag saying where the service was started. Post a "Tap to resume sounds" notification instead of playing into silence.
  4. **Swapping the night notification for the alarm one.**
     - Today: `sleep/SleepService.kt:91-92` calls `stopForeground(REMOVE)` and then `startForeground` again, in the background. Whether the service keeps its WIU across that swap is not documented. The alarm itself is safe: `AlarmPlayer` uses `USAGE_ALARM` (`sleep/AlarmPlayer.kt:83,255`), and alarms can only be on with exact-alarm access (`SleepScheduler.kt:133`).
     - Change: keep that invariant (never `USAGE_MEDIA` for the alarm). Check the swap with a target-37 build (see section H).
  5. **Testing.**
     - `docs/PHONE_TEST_CHECKLIST.md:92` tests with `set-enable-hardening enable`. That mode ignores the alarm waiver, so the alarm can fail there even though it works in a real target-37 build.
     - Split the line: run `throw` mode for the night sounds and the call boost, and test the alarm with a real `targetSdk = 37` build.
  6. **No change needed:**
     - Find my earbuds, Mic check, the voice-memo beep (`shortcut/VoiceMemoService.kt:313`, started from `ShortcutTriggerActivity` while it is visible) and the EQ (AudioEffect is not on the list).
     - Spoken feedback (`shortcut/SpokenFeedback.kt:49-58`): the TTS engine plays in its own process. Check it once under `throw` mode.

### A2. RFCOMM `read()` returns -1 when the link drops (target 37) **[code]**
- **Rule.** At target 37, `read()` on an RFCOMM socket's input stream returns -1 when the socket closes or the link drops, instead of throwing. Source: https://developer.android.com/about/versions/17/behavior-changes-17
- **Earshot today.**
  - The reader in `bt/RfcommTransport.kt:90-94` already exits on `n < 0`, but it closes the channel without a cause (`chunks.close()`, `:94`).
  - `BmapConnection` then calls `finish(null)` (`bt/BmapConnection.kt:207`). A dropped link therefore looks like a local close:
    - `closeCause` stays null;
    - the log says "closed locally" (`bt/ConnectionManager.kt:254`).
  - Reconnecting still works, because `SessionEnd.classify` treats a null cause as connection lost (`bt/SessionEnd.kt:25-34`). The connection report would mislead, though, and so would any code that reads `closeCause`, including `BragiConnection` and `LivenessProbe.kt:114`.
- **Change.**
  - On `n < 0 && !closed`, call `chunks.close(IOException("RFCOMM end of stream"))`.
  - In `drainGreeting`, stop on -1 (`:131-132`).
  - Add a unit test with an InputStream that returns -1. Every RFCOMM stack (Bose, Sony, Sennheiser, Soundcore) shares this transport.

### A3. Non-SDK interfaces (risk at any new target) **[code]** (verify first)
- **Rule.** An interface on a `max-target-X` list is blocked once the app targets above X. The restrictions page has no Android 17 section yet; its newest list is for Android 16. Play's pre-launch report flags non-SDK use. Source: https://developer.android.com/guide/app-compatibility/restrictions-non-sdk-interfaces
- **Earshot today.** These calls run in the app's own process:
  - `createInsecureRfcommSocket(int)`, the **Bose connect path** (`bt/RfcommTransport.kt:198-202`);
  - `BluetoothDevice.getBatteryLevel` (`bt/DeviceBattery.kt:16`; its comment says it is on the SDK list in Android 16);
  - `getCodecStatus` and `setCodecConfigPreference` (`codec/AssociationCodecPath.kt:39,53`);
  - `BluetoothHeadset.getCodecType` (`bt/TimelineRecorder.kt:201`).
  - The Shizuku-side reflection runs in Shizuku's `app_process`, which has no hidden-API policy (`codec/shizuku/A2dpBinderLocator.kt:24-26`).
- **Change.**
  - Build `playDebug` with `targetSdk = 37`, run it on the Fold7 and check logcat for `Accessing hidden method` with the list name.
  - Add StrictMode `detectNonSdkApiUsage()` to debug builds.
  - If `createInsecureRfcommSocket` turns out to be `max-target-36`, the Bose path needs the SDP-only fallback order before the bump. That decision would go to the integrator.

### A4. Large screens: orientation and resizability are ignored, with no opt-out at 37 **[none]** (test)
- **Rule.** On displays with smallest width of 600 dp or more, orientation, resizability and aspect-ratio settings are ignored. The Android 16 opt-out property no longer works at target 37. Sources: https://developer.android.com/about/versions/17/behavior-changes-17, https://developer.android.com/about/versions/16/behavior-changes-16
- **Earshot today.** The manifest has no `screenOrientation`, `resizeableActivity` or `PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY`, and the code does not call `setRequestedOrientation`.
- **Change.** None. Test on the Fold7's inner screen: the alarm screen over the lock screen, onboarding, and state kept across rotation. Also on Android 17 (all apps), the keyboard is no longer shown again after a rotation, which matters for the automation editor's text fields.

### A5. Edge-to-edge and predictive back **[none]**
- **Rule.** Neither changes at target 37. At target 36, edge-to-edge can no longer be switched off and predictive back is on by default, so `onBackPressed` is not called. Source: https://developer.android.com/about/versions/16/behavior-changes-16
- **Earshot today.** It already targets 36. `MainActivity.kt:29` and `sleep/AlarmActivity.kt:27` call `enableEdgeToEdge()`, nothing overrides `onBackPressed`, and Navigation Compose 2.9.0 handles back.

### A6. Foreground services **[none]**
- **Rule.** There are no new foreground-service rules for Android 17; the changes page stops at Android 16. A `connectedDevice` service needs `BLUETOOTH_CONNECT` granted. A `microphone` service needs `RECORD_AUDIO` and can't start from the background. A `mediaPlayback` service can't start from `BOOT_COMPLETED` at target 35 or higher. Sources: https://developer.android.com/develop/background-work/services/fgs/changes, https://developer.android.com/develop/background-work/services/fgs/service-types
- **Earshot today:**
  - The types are declared (`AndroidManifest.xml:128-131, 240-243, 248-252`) and passed to `startForeground` (`HeadphoneService.kt:181-182`, `SleepService.kt:114`, `VoiceMemoService.kt:105-111`). Refusals are caught.
  - `BOOT_COMPLETED` only re-arms alarms (`AndroidManifest.xml:142-143`; `docs/sleep-mode.md` §4.3).
  - The voice memo starts while `ShortcutTriggerActivity` is visible.
  - Still unknown: does a headset service started from the `CompanionDeviceService` binding (`service/HeadsetPresenceService`) get WIU? This matters only for A1.

### A7. Intents and launching activities **[none]**
- **Rule.**
  - At target 37 the background-activity-launch rules extend to `IntentSender`; `MODE_BACKGROUND_ACTIVITY_START_ALLOWED` should become `..._ALLOW_IF_VISIBLE`.
  - From Android 18, URI grants for `ACTION_SEND` and `ACTION_IMAGE_CAPTURE` will have to be explicit.
  - Strict intent matching (Android 16) is opt-in.
  - Sources: https://developer.android.com/about/versions/17/behavior-changes-17, https://developer.android.com/about/versions/17/behavior-changes-all
- **Earshot today:**
  - No code sets a background-start mode. The companion-device `IntentSender` is started from a visible activity.
  - Nothing sends `ACTION_SEND` with a URI. The voice-memo VIEW intent already adds `FLAG_GRANT_READ_URI_PERMISSION` (`shortcut/VoiceMemoNotifications.kt:56`).
  - Don't turn on `intentMatchingFlags="enforceIntentFilter"`: `SleepAlarmReceiver` gets explicit intents with actions that are not in its filter (`sleep/SleepScheduler.kt:82-95`).

### A8. Other target-37 and all-app changes **[none]**
- **Static final fields can't be changed by reflection.** Nothing writes one. `A2dpBinderLocator` only reads fields, inside Shizuku's process.
- **Lock-free `MessageQueue`.** No reflection on it.
- **Widget bitmap memory limit.** The Glance widget uses no bitmaps (`res/xml/earshot_widget_info.xml`).
- **`System.load` must load read-only files.** Not used; the AndroidX libraries use `loadLibrary`.
- **Local network permission, ECH, certificate transparency, cleartext.** Earshot uses no network at all.
- **Content capture.** Not used.
- **Keystore key limit.** Far above anything `bt/AndroidBragiTrustStore.kt` could reach.
- **Memory limiter (all apps).** Check `ApplicationExitInfo` for `MemoryLimiter:AnonSwap` in the reliability runs (`docs/reliability.md`).
- **Autonomous Bluetooth re-pairing (all apps).** `ACTION_KEY_MISSING` now arrives only when re-pairing fails. The Bluetooth history (`bt/TimelineRecorder.kt:118`) will show fewer key-missing events, which is expected. Note it in the docs.
- Sources: https://developer.android.com/about/versions/17/behavior-changes-17, https://developer.android.com/about/versions/17/behavior-changes-all

---

## B. Permissions and sensitive APIs (Play policy)

### B1. Foreground-service declarations **[console]**
- **Rule.** Apps targeting 34 or higher declare each foreground-service type on the App content page. For each type: a description, the user impact if the system defers or interrupts the task, a video link showing how the user triggers it, and a use case from Google's list. Source: https://support.google.com/googleplay/android-developer/answer/13392821
- **Earshot today.** Three types are declared in the manifest (`AndroidManifest.xml:17,19,31`).

**connectedDevice** (`service.HeadphoneService`)
- Use case: "Continuous Data Transfer to an External Device" (headsets).
- What it does: keeps the control link to the user's headset open while it is connected. It receives battery and mode updates, serves the notification's mode and ANC buttons, and runs the user's automations. It stops two minutes after the headset disconnects (`HeadphoneService.kt:166-171, 245`).
- If deferred: notification controls don't respond, battery and mode go stale, and automations don't fire on connect.
- Video: Google requires "pairing and connecting to an external device directly inside the app". Use the real QC Ultra 2, not demo mode. Show onboarding, pick the headset, Connect, then change the mode from the notification with the app closed.

**mediaPlayback** (`sleep.SleepService`)
- Use case: "Media Playback". Playback must be started by the user.
- What it does: plays the sleep sounds the user started, through the night with the screen off, and rings the wake alarm the user set.
- If deferred: the sounds stop and the alarm stays silent. Earshot falls back to the phone (`docs/sleep-mode.md` §4.7).
- Video: Sleep tab, pick sounds, Start, lock the phone (sound continues), then an alarm rings and is stopped.
- Fallback if review objects to the alarm part: `systemExempted` is allowed for apps holding `SCHEDULE_EXACT_ALARM` (https://developer.android.com/develop/background-work/services/fgs/service-types).

**microphone** (`shortcut.VoiceMemoService`)
- Use case: "Background Audio Access" (voice recording).
- Video: on the Shortcuts remapper, assign "Voice memo". Press and hold the headset button, pick Earshot in Android's chooser, the recording notification appears, stop it, and the memo is saved.

### B2. `USE_FULL_SCREEN_INTENT` **[console]**
- **Rule.**
  - A Play Console declaration is required (since 2024-05-31).
  - Since 2025-01-22, for apps targeting 14 or higher, only apps whose *core functionality* is calls or alarms get it by default.
  - Other apps must ask the user and "gracefully degrade the experience if denied".
  - Sources: https://support.google.com/googleplay/android-developer/answer/13392821, https://support.google.com/googleplay/android-developer/answer/9888170
- **Earshot today.** It already degrades gracefully:
  - The check is `SleepRepositoryImpl.kt:254-257`, and the banner that opens `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` is `ui/sleep/SleepPermissionBanners.kt:37-40, 72-79`.
  - Without the permission, the alarm still sounds from the foreground service. It shows as a heads-up and lock-screen notification with Stop and Snooze (`SleepNotifications.kt:82-100`).
- **Change.**
  - In the declaration, say that alarms are a feature but not Earshot's core function; the Sleep tab is one tab out of five. Expect no default grant.
  - Keep the banner. The copy (`res/values/strings_sleep.xml:42`) is accurate.
  - Test the alarm with full-screen notifications switched off for Earshot (section H).

### B3. Exact alarms **[none]**
- **Rule.** `USE_EXACT_ALARM` is restricted to apps whose core function needs exact alarms, such as alarm, timer and calendar apps. `SCHEDULE_EXACT_ALARM` does the same job but the user must grant it. Source: https://support.google.com/googleplay/android-developer/answer/9888170
- **Earshot today:**
  - It declares only `SCHEDULE_EXACT_ALARM` (`AndroidManifest.xml:32`).
  - It checks the grant (`sleep/SleepScheduler.kt:36-37`) and falls back to inexact alarms (`:121-125`).
  - It won't switch a wake alarm on without exact access (`:133`).
  - The banner is `SleepPermissionBanners.kt:64-71`; the permission-change broadcast is in `AndroidManifest.xml:263`.
- **Change.** None. Never add `USE_EXACT_ALARM`. The exact-alarm grant is also what earns the alarm-stream waiver in A1. If the Console ever asks about exact alarms, answer: wake alarms and sleep-timer steps the user sets, granted by the user in "Alarms & reminders".

### B4. Location: the new precise-location declaration and prominent disclosure **[console] [code] [decision]**
- **Rule.**
  - **Declaration.** "All apps requesting precise location (`ACCESS_FINE_LOCATION`) must complete a declaration in the Play Console." It becomes available in November 2026, compliance is mandatory from 2027-01-27, and the FAQ expects enforcement for apps targeting 17 or higher in late January 2027. 30-day extensions are offered.
    - Question 1: which features need it, picked from a chart that includes "Nearby devices" (for Bluetooth on Android 11 and lower) and "Other".
    - Question 2: why coarse location or the location button is not enough.
    - Source: https://support.google.com/googleplay/android-developer/answer/17033915
  - **Location button.** At target 37, features that need precise location only once must use the location button (Jetpack `androidx.core:core-locationbutton`, still alpha). Source: https://developer.android.com/guide/topics/permissions/private-alternatives/location-button
  - **Minimum scope.** Request the minimum scope, and never for ads or analytics. Source: https://support.google.com/googleplay/android-developer/answer/9888170
  - **Prominent disclosure.** If access goes beyond what users would expect, an in-app disclosure must come *before* the runtime prompt, and consent needs an affirmative action. Source: https://support.google.com/googleplay/android-developer/answer/10144311
- **Earshot today:**
  - It declares fine and coarse location (`AndroidManifest.xml:45-46`). They are needed for two things:
    - the Wi-Fi network name for the "when I join this Wi-Fi" trigger (`extras/automation/WifiSsidWatcher.kt:19-24, 47-50`);
    - Bluetooth scans on Android 9 to 11 (`ui/home/BleScanBanner.kt:38-46`).
  - There is no `ACCESS_BACKGROUND_LOCATION`, on purpose (`WifiSsidWatcher.kt:21-24`).
  - The Bluetooth banner explains first and asks only on tap (`BleScanBanner.kt:94-125`). That is compliant.
  - **The Wi-Fi trigger shows Android's prompt automatically as soon as the trigger is picked** (`ui/more/AutomationEditorScreen.kt:323-328`). The banner appears at the same moment, so it does not come before the prompt.
- **Change.**
  - [code] Remove the automatic request in `AutomationEditorScreen.kt:323-328` and launch only from the banner's "Allow location" button (`:335`). Expand `trigger_wifi_location_needed` (`res/values/strings.xml:118`): "Android shows the Wi-Fi name only to apps with precise location. Earshot compares it on this phone to start your automation; it never reads, stores or sends your location."
  - [console] Fill in the declaration when it opens in November 2026:
    - Q1: "Nearby devices" (Bluetooth scans on Android 9 to 11: AirPods and Beats battery, the JBL control link) and "Other" (reading the connected Wi-Fi network's name for a trigger the user creates).
    - Q2: the name is hidden with coarse location. The location button gives one session only, but the trigger has to see every Wi-Fi change while a headset is connected.
  - [decision] Or drop the Wi-Fi trigger from the play flavour. Then cap both location permissions with `android:maxSdkVersion="30"`, so the Bluetooth case on Android 11 and older still works. The declaration would still apply on those Android versions, but with a much smaller scope.
  - [none] Never add `ACCESS_BACKGROUND_LOCATION`.

### B5. Usage access (`PACKAGE_USAGE_STATS`) **[code]** (copy only)
- **Rule.** It is not on Play's list of restricted permissions, so no declaration form is needed. Inventory of installed apps is "personal and sensitive user data" under the User Data policy, and app usage falls under "other sensitive device or usage data". Sources: https://support.google.com/googleplay/android-developer/answer/9888170, https://support.google.com/googleplay/android-developer/answer/10144311
- **Earshot today:**
  - It is declared at `AndroidManifest.xml:48` and checked through AppOps (`extras/automation/AutomationRepositoryImpl.kt:167-185`).
  - Polling runs only while a headset is connected and an app rule exists (`ForegroundAppWatcher.kt:20-24`).
  - The banner "Usage access needed to notice which app is in front." (`strings.xml:519`) appears before the Settings jump.
- **Change.** Add "The app list stays on this phone." to that banner. Mention the optional "when this app opens" trigger in the listing.

### B6. Package visibility **[none]**
- **Rule.** `QUERY_ALL_PACKAGES` is not allowed when targeted `<queries>` declarations are enough. Source: https://support.google.com/googleplay/android-developer/answer/9888170
- **Earshot today.** There is no `QUERY_ALL_PACKAGES` (`AndroidManifest.xml:49-50`), only targeted `<queries>` (`:65-106`), and the release gate refuses it (`tools/release/check_release.py:66`). The installed-app list is shown only in pickers the user opens, and stays on the phone.

### B7. Other permissions **[none]**
- `RECEIVE_BOOT_COMPLETED`, `ACCESS_NOTIFICATION_POLICY`, `VIBRATE`, `WAKE_LOCK`, `MODIFY_AUDIO_SETTINGS` and the two companion-device permissions are normal permissions or special accesses with no Play form.
- The roadmap mentions dropping `RECEIVE_BOOT_COMPLETED`. **Play does not require that.** Keep it, because the process start re-arms schedule alarms.
- `RECORD_AUDIO` and `BLUETOOTH_CONNECT`/`SCAN` (scan marked `neverForLocation`, `AndroidManifest.xml:10-13`) are runtime permissions the user grants in context.
- No AccessibilityService, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `SYSTEM_ALERT_WINDOW`, `REQUEST_INSTALL_PACKAGES` or `AD_ID`.

### B8. Shizuku in the Play build **[decision]**
- **Rule.** No Play policy names Shizuku or non-SDK interfaces. The Device and Network Abuse policy forbids accessing "the user's device" in an unauthorized way and getting around sandbox protections to learn about other apps. Source: https://support.google.com/googleplay/android-developer/answer/9888379
- **Earshot today:**
  - The Shizuku API, the permission and the provider are in every flavour (`app/build.gradle.kts:147-148`; `AndroidManifest.xml:53, 97, 280-286`).
  - The Shizuku service calls hidden Bluetooth internals.
  - Codec switching through the companion link already works on the Fold7 (Android 13 and later) without Shizuku (`codec/AssociationCodecPath.kt`).
- **Options:**
  - **(a) Keep it.** It is user-authorised and optional, and other apps on Play use Shizuku.
  - **(b) GitHub build only.** Move the dependency to `fossImplementation`, the provider, permission and `<queries>` entry into `app/src/foss/AndroidManifest.xml`, and the `codec/shizuku/*` code into the foss source set with a stub for Play. This needs an `AppContainer` change, which is integrator-owned.
  - (b) shrinks what reviewers see and what the pre-launch report flags; the cost is codec switching on Android 12 and older for Play users.

---

## C. Play Console App content forms

Each form below is **[console]**.

- **Privacy policy URL.**
  - Rule: a link must be in the Console and a link or the text must be in the app. The policy must be "active, publicly accessible, non-geofenced, non-editable", not a PDF. It must name the app or the store-listing entity, give a contact, list the data handled, the security measures, and retention and deletion. Source: https://support.google.com/googleplay/android-developer/answer/10144311
  - Earshot today: `PRIVACY.md`, with contact through GitHub issues (`:61-62`). The in-app privacy card (`strings.xml:557`, `AboutScreen.kt:86-88`) has **no link**.
  - **[code]** Add the policy URL to the About privacy card. A plain `ACTION_VIEW` link needs no INTERNET permission.
  - **[listing / docs]** Add to `PRIVACY.md`:
    - a contact email or the developer name as it appears on Play;
    - retention: the connection log is the last 400 lines, in memory only (`bt/LogRing.kt:12-15`); the Bluetooth history is the last 500 events in app storage (`bt/BtTimeline.kt:58`);
    - deletion: uninstall, or Clear storage;
    - a one-line security note: private app storage, and backups only where the user chooses.
- **Data safety.**
  - Rule: "collect" means sending data off the device from the app. Data processed only on the device is not disclosed. A transfer the user starts is exempt from "sharing". The form is required even if nothing is collected. Data that libraries send off the device counts. Source: https://support.google.com/googleplay/android-developer/answer/10787469
  - Answer: **no data collected or shared.** The reasons, type by type:
    - Location is only used on the phone to read the Wi-Fi name.
    - Audio (memos, mic check) stays on the phone.
    - App activity (installed apps, usage access) stays on the phone.
    - Device IDs: the headsets' Bluetooth addresses stay on the phone.
    - Files (the user's own sounds) stay on the phone.
    - The connection report goes to the clipboard or the share sheet, started by the user. The developer never receives it.
    - The settings backup is written through the system file picker to a place the user picks.
    - With no INTERNET permission, nothing can be sent.
  - Re-check these answers when billing is added (G).
- **Content rating (IARC questionnaire).** No violence, user-generated content, location sharing or purchases. "Digital purchases" becomes yes once the unlock ships.
- **Target audience.** 18+, or 13+ without under-13s, to stay out of the Families policy. This matches PRIVACY.md "Children" (`:54-56`).
- **Ads.** None.
- **Advertising ID.** Answer "No". No dependency adds `AD_ID`; confirm in the merged manifest (F2).
- **App access.**
  - Rule: reviewers must be able to reach every feature. Most of Earshot needs headphones.
  - Answer: "Tap *Try demo mode — no headphones needed* on the first screen (`strings.xml:38`). Virtual headphones then open every screen. The Sleep tab works without a headset."
- **Health apps declaration.**
  - Rule: required for every app. "Sleep management" covers sleep apps that "may also include features for relaxation, such as sounds". Source: https://support.google.com/googleplay/android-developer/answer/14738291
  - Answer: declare **Sleep management** (sleep sounds, sleep timer, wake alarm). Make no medical claims; the Sleep tab rules already require that.
- **Financial features, Government apps, News apps.** Answer "none" or "no".
- **Foreground services** (B1), **full-screen intent** (B2), **precise location** (B4): as above.

---

## D. Account, signing and developer verification

- **D1. Closed test for new personal accounts** **[console]**
  - Rule: personal developer accounts created after 2023-11-13 must run a closed test with **at least 12 testers opted in for 14 days in a row** before they can apply for production. The application then asks about the test, the app and how ready it is. Organisation accounts are exempt. Source: https://support.google.com/googleplay/android-developer/answer/14151465
  - Change: if the account is new and personal, plan this before the 1.0 date: about three weeks including review. Testers will need a working headset or demo mode. Write down what the test found, for the application form.
- **D2. Developer verification and package registration** **[console]**
  - Rule:
    - Most Play apps are registered automatically. The remaining ones had to be registered by 2026-09-30.
    - Play Console can also register apps distributed outside Play.
    - Enforcement began on 2026-09-30, for installs from participating stores (Google Play, Galaxy Store and others) in Brazil, Indonesia, Singapore and Thailand. It goes global in 2027.
    - adb and the "advanced flow" can still install unregistered apps.
    - Sources: https://android-developers.googleblog.com/2026/06/android-developer-verification.html, https://developer.android.com/developer-verification
  - Change:
    - `dev.earshot` is registered when the Play app is created.
    - **Register `dev.earshot.foss` in Play Console with the SHA-256 of the foss release key** before the 2027 rollout.
    - For F-Droid, ask for a reproducible build published under the developer's signature, so one key covers the package. Community reports say a package name can claim only one key (https://forum.f-droid.org/t/moving-to-distributing-self-signed-apks/34312, unofficial). Separate app IDs for the two flavours already avoid a clash between Play and F-Droid.
    - The debug-signed 0.9.1 bridge (`app/build.gradle.kts:26-38`) can't be registered sensibly. Ship it before 2027 and keep it a one-off.
- **D3. Play App Signing and the AAB** **[none]**
  - Already documented in `docs/release-signing.md:7, 82, 101-102`: an upload key, Google's app-signing key, and an `.aab` upload. Keep the upload key backed up, and never debug-sign the play build.

---

## E. Store listing

- **E1. Brand names, trademarks and impersonation** **[listing]**
  - Rule: no title or icon "so similar" to an existing product that users may be misled, no false "official" claims, no copied logos, and nothing that implies a relationship or approval that doesn't exist. Source: https://support.google.com/googleplay/android-developer/answer/9888374
  - Metadata rules: a title of 30 characters or fewer; no emojis or ALL CAPS; no ranking claims; "Avoid using repetitive or unrelated keywords"; "Don't use … a brand's logo without permission"; screenshots must reflect the real app. Source: https://support.google.com/googleplay/android-developer/answer/9898842
  - Change:
    - **Title:** no brand name. For example "Earshot: Headset Companion" (26 characters).
    - **Short description:** no list of brands.
    - **Full description:**
      - one factual compatibility paragraph using the support tiers: Tested (Bose QC Ultra Headphones 2nd Gen, Soundcore Sleep A30), then Experimental, then Any headset;
      - then the not-affiliated and trademark paragraph from `strings.xml:550` and `README.md:68-73`;
      - no long model lists (not the 35 Soundcore models), no "official" or "replacement for the Bose app" wording, no maker logos or product photos;
      - a line saying the maker's app must be closed while Earshot is connected (`strings.xml:551`).
    - **Screenshots:** demo mode is fine; the headset's model name may appear as a device name.
    - **Developer name:** must not suggest a headset brand.
- **E2. "No internet permission" claim** **[listing]**
  - Keep it only while the play build has no INTERNET permission. See G2; the release gate enforces it (`tools/release/check_release.py:61`).
- **E3. Permissions explained** **[listing]**
  - Put a short "Permissions and why" list in the description, drawn from `PRIVACY.md:37-52`. Mention location for the Wi-Fi trigger and usage access for the app trigger.

---

## F. Build and release gate

- **F1. Check the AAB as well as the APKs** **[code]**
  - Today: CI builds `bundlePlayRelease` (`.github/workflows/ci.yml:66`), but the gate checks only the two APKs (`ci.yml:77-83`). The `.aab` is what gets uploaded.
  - Change: in `check_release.py`, add `--aab`, which reads the base manifest with `bundletool dump manifest`. Apply the same checks: no INTERNET or `QUERY_ALL_PACKAGES`, not debuggable, version matches the tag, and also no `AD_ID`.
- **F2. Merged-manifest audit** **[code]**
  - Change: have the gate print the full list of permissions per flavour, to catch any a library adds (Billing, see G2).
- **F3. 16 KB page size** **[none]** (add a check)
  - Rule: apps targeting 35 or higher must support 16 KB pages; from 2027-02-01, updates that don't can't be released. Source: https://developer.android.com/guide/practices/page-sizes
  - Today: the two native libraries (`libandroidx.graphics.path.so`, `libdatastore_shared_counter.so`) have 16 KB LOAD alignment in the current foss release APK. AGP 9.4.1 (`gradle/libs.versions.toml:2`) handles zip alignment.
  - Change: add `zipalign -c -P 16 -v 4` on the signed APK to the gate's PC-side step.

---

## G. Later: the paid unlock and the Billing library (do not start yet)

- **G1. Library version** **[code]** (later)
  - Rule: v7 was accepted until 2026-08-31 (extension to 2026-11-01). v8 is accepted until 2027-08-31. The integration guide shows 9.1.0. Source: https://developer.android.com/google/play/billing/deprecation-faq
  - Change: use the current 9.x, as `playImplementation` only. It would be new in the version catalogue, so the user has to approve it.
- **G2. Permissions it adds to the manifest** **[code]** (later)
  - Facts:
    - `com.android.vending.BILLING` comes from the library itself. The gate already allows it on play and forbids it on foss (`check_release.py:62`).
    - Google's SDK Index reports that `ACCESS_NETWORK_STATE` was added in v6. Earshot already declares that permission (`AndroidManifest.xml:44`).
    - A 2024 r/androiddev post (unofficial) says v6 and later also add INTERNET.
  - Change:
    - Check the merged play manifest before anything else.
    - If INTERNET is there, add `<uses-permission android:name="android.permission.INTERNET" tools:node="remove"/>` to `app/src/play/AndroidManifest.xml`. Purchases go through the Play Store app, so the library probably doesn't need it, but that is unverified.
    - Then test buying and restoring after a reinstall with license testers.
    - The gate already fails any build with INTERNET.
- **G3. Console forms when the unlock ships** **[console]** (later)
  - Set up a payments profile and an in-app product.
  - Content rating: answer "digital purchases" with yes.
  - Re-check Data safety against the Billing SDK's own disclosure.
  - Check whether the Console asks about EU trader status. This was not verified for Play.

---

## H. Phone pass for the target-37 bump (Fold7, Android 17)

**Done 2026-10-30 with a targetSdk 37 playDebug build: all checks passed.**
- **Hidden APIs:** the only one is `createInsecureRfcommSocket`, logged as `api=unsupported`, which means allowed, not blocked.
- **Link drop:** logged as `RFCOMM end of stream`, then an automatic reconnect.
- **Sleep:** sounds from the tab kept playing with the screen off, and the alarm rang on its minute in the earbuds and stopped cleanly. No AudioHardening refusals.
- **Wi-Fi trigger:** no location dialog until "Allow location" is tapped.
- **Rotation:** state survives on the inner screen.
- **Not run:** the call boost with Earshot closed (it needs a call), and the full-screen permission switched off. Numbers are in `docs/reliability.md`.


1. Build `playDebug` with `targetSdk = 37` as its own change.
   - Check logcat for `Accessing hidden method` (A3), especially on a Bose connect.
   - Turn on StrictMode `detectNonSdkApiUsage` in debug builds.
2. Link drop: switch the headphones off while connected. The log should show a dropped link (A2), and the reconnect should behave as before.
3. Run `adb shell cmd audio set-enable-hardening throw` (https://developer.android.com/about/versions/17/changes/bg-audio), then try each of these and check `dumpsys audio` for `AudioHardening`:
   - call boost with Earshot closed (A1.1);
   - sleep sounds started from the tab, then the screen off;
   - the night's end with "pause other audio" on;
   - the morning restore with the app closed (A1.2);
   - spoken feedback.
4. Alarm, with `set-enable-hardening disable` and the real target-37 build (the `enable` mode ignores the alarm waiver):
   - it rings from the locked phone;
   - with full-screen notifications switched off for Earshot, it still rings as a notification and Stop and Snooze work (B2);
   - with "Alarms & reminders" off, wake alarms can't be switched on (B3).
5. Fold7 inner screen: rotate on Home, Sleep and the alarm screen, and check that state survives (A4).
6. Wi-Fi trigger: pick it. No system prompt should appear until "Allow location" is tapped (B4).

## Decisions for the user

1. The Wi-Fi trigger in the Play build: keep it and file the precise-location declaration, or drop it from play and cap location at API 30 (B4).
2. Shizuku in the Play build: keep it, or GitHub only (B8).
3. Ship 1.0 at target 37 (recommended, after section H passes) or at 36. Both are allowed today; 36 is fine until Play's next deadline, which Google has not announced.
4. The account type: if it is a new personal account, the 14-day closed test with 12 testers sets the earliest launch date (D1).

## Sources

**Android developer docs**
- Android 17, apps targeting 17: https://developer.android.com/about/versions/17/behavior-changes-17
- Android 17, all apps: https://developer.android.com/about/versions/17/behavior-changes-all
- Android 17 background audio hardening: https://developer.android.com/about/versions/17/changes/bg-audio
- Android 16, apps targeting 16: https://developer.android.com/about/versions/16/behavior-changes-16
- Foreground service types: https://developer.android.com/develop/background-work/services/fgs/service-types
- Foreground service changes: https://developer.android.com/develop/background-work/services/fgs/changes
- Non-SDK interface restrictions: https://developer.android.com/guide/app-compatibility/restrictions-non-sdk-interfaces
- Location button: https://developer.android.com/guide/topics/permissions/private-alternatives/location-button
- 16 KB page sizes: https://developer.android.com/guide/practices/page-sizes
- Play Billing deprecation: https://developer.android.com/google/play/billing/deprecation-faq
- Developer verification: https://developer.android.com/developer-verification and https://android-developers.googleblog.com/2026/06/android-developer-verification.html

**Play Console help and policy**
- Target API level: https://support.google.com/googleplay/android-developer/answer/11926878
- Foreground services and full-screen intent: https://support.google.com/googleplay/android-developer/answer/13392821 (April 2026 update: https://support.google.com/googleplay/android-developer/answer/16965181)
- Permissions and APIs that access sensitive information: https://support.google.com/googleplay/android-developer/answer/9888170
- Precise location (minimum scope): https://support.google.com/googleplay/android-developer/answer/17033915
- User Data (prominent disclosure, privacy policy): https://support.google.com/googleplay/android-developer/answer/10144311
- Data safety: https://support.google.com/googleplay/android-developer/answer/10787469
- Health apps declaration: https://support.google.com/googleplay/android-developer/answer/14738291
- Testing for new personal accounts: https://support.google.com/googleplay/android-developer/answer/14151465
- Impersonation: https://support.google.com/googleplay/android-developer/answer/9888374
- Metadata: https://support.google.com/googleplay/android-developer/answer/9898842
- Device and Network Abuse: https://support.google.com/googleplay/android-developer/answer/9888379

**Unofficial, used only where marked**
- r/androiddev, "Play Billing Library v6 silently adds the …" (2024): https://lr.eu.psf.lt/r/androiddev/comments/1e4ctxd/psa_play_billing_library_v6_silently_adds_the/ldf1exo/?context=3
- F-Droid forum on self-signed APKs and verification: https://forum.f-droid.org/t/moving-to-distributing-self-signed-apks/34312
