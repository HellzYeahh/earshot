# Google Play Console: every form for Earshot

What to type into Play Console for the Google Play version (`play` flavour, `dev.earshot`), field by
field, with the reason for each answer. Written 2026-10-29 from
[research/play-readiness.md](../research/play-readiness.md) (items B1, B2, B4, C, D1, D2) and
checked against the code on that day. The store listing text is in [listing.md](listing.md); the
14-day closed test is in [closed-test.md](closed-test.md).

The user's decisions (2026-10-29) behind these answers:
- The Wi-Fi automation stays in both versions, so Play gets the precise-location declaration (§2.14).
- Shizuku is in the GitHub (`foss`) version only. Nothing below mentions it.
- 1.0 ships at targetSdk 37 after a phone pass. The bump is its own change; §5 says what to re-check then.
- The Play account is a new **personal** account, so a 14-day closed test with 12 testers comes
  before production (§4 and closed-test.md).

How to read this page:
- Text in a `text` block is meant to be pasted as it is.
- Google doesn't publish character limits for these declaration fields. If a field refuses a block,
  cut from the end: every block puts the essentials first.
- Google doesn't publish every form label. Where this page names a label that Google's help
  pages don't confirm, it says so; the answer stays the same if the wording differs.
- Never type a password, key or keystore detail into a chat with Claude. The signing steps are in
  [release-signing.md](../release-signing.md) and stay on your PC.

## 0. Before you start

These must be true in the build and the repo you file against. Each one is a change outside
`docs/store/`; tick them off first.

- [ ] **PRIVACY.md is pushed.** The policy URL below is
      `https://github.com/HellzYeahh/earshot/blob/main/PRIVACY.md`, the same one the About screen
      opens (`ui/more/AboutScreen.kt`, `PRIVACY_POLICY_URL`). On 2026-10-29 the file was not yet
      committed, so that link led nowhere yet.
- [ ] **PRIVACY.md has what Play asks for** (research C): a contact email, the developer name as it
      appears on Play, how long the logs are kept (connection log: the last 400 lines, in memory
      only, `bt/LogRing.kt`; Bluetooth history: the last 500 events, `bt/BtTimeline.kt`), how to
      delete everything (uninstall, or Clear storage), and one line on security (private app
      storage, Android cloud backup off, backups only where you choose).
- [ ] **The Wi-Fi automation asks for location only after a tap** (research B4): no system prompt
      when the trigger is picked, only from the banner's "Allow location" button, and the banner
      says what the location is for before Android asks. Done in the working tree on 2026-10-29
      (`WifiLocationBanner` in `ui/more/AutomationEditorScreen.kt`, `trigger_wifi_location_needed`);
      make sure it is in the build you upload.
- [ ] **Shizuku is out of the play flavour** (user decision): no Shizuku permission, provider or
      `<queries>` entry in the merged `play` manifest.
- [ ] **The `.aab` passes the release gate** (research F1/F2): no `INTERNET`, no
      `QUERY_ALL_PACKAGES`, no `AD_ID`, not debuggable. The "no internet" answers below depend on it.
- [ ] **The real QC Ultra 2 is at hand** for the foreground-service videos (§2.12). Demo mode is not
      enough there.

## 1. Create the app

Play Console → Home → **Create app**.

| Field | Answer | Why |
|---|---|---|
| App name | `Earshot: Headset Companion` | No brand in the title (impersonation policy). Same as listing.md; 26 of 30 characters. |
| Default language | English (United Kingdom), en-GB | The app's own text is British English ("colour", "recognises"). |
| App or game | App | |
| Free or paid | **Free** | A free app can't become paid later, but it can sell an in-app product. The planned $3.99 unlock is an in-app product, so Free is right. |
| Declarations | Tick both after reading them (Developer Program Policies; US export laws) | The export box is an acknowledgement, not a filing. For your own notes: Earshot's only cryptography is the Skullcandy headset's pairing session over Bluetooth (`protocol/bragi`: ECDHE, HKDF, AES-CCM). |

Keep **Play App Signing** on when the first release asks (the default): Google holds the app-signing
key, you keep the upload key ([release-signing.md](../release-signing.md) §4).

## 2. App content forms

Play Console → your app → **App content** (under Policy; newer Consoles file it under "Monitor and
improve"). Every form below must be done before the first closed-test release can be reviewed.

### 2.1 Privacy policy

| Field | Answer |
|---|---|
| Privacy policy URL | `https://github.com/HellzYeahh/earshot/blob/main/PRIVACY.md` |

Why: Play wants an active, public, non-geofenced page that isn't a PDF, names the app, lists the
data handled and gives a contact. The same URL is linked inside the app (About → Privacy), which
Play also requires. A GitHub page qualifies; a GitHub Pages copy would look tidier but isn't needed.

### 2.2 App access

Choose **All or some functionality in my app is restricted**, then **Add instructions**.

| Field | Answer |
|---|---|
| Name | `Demo mode (no login)` |
| Username / password | Leave empty. If the form insists, write `Not needed: Earshot has no accounts`. |
| Other information / instructions | Paste the block below (638 characters). |

```text
No account or login. Most screens need a Bluetooth headset, so Earshot has virtual headphones built in:
1. Switch Bluetooth on. Open Earshot, tap Allow Bluetooth, then allow Nearby devices.
2. Tap "Try demo mode - no headphones needed". If the tabs already show instead, turn on More > App preferences > Demo mode.
All five tabs then work with a virtual Bose QC Ultra Headphones (2nd Gen). Other virtual models: Demo mode > Virtual device.
Sleep sounds don't play in demo mode. To hear them, switch Bluetooth off, then turn Demo mode off (More > App preferences > Demo mode). The tabs stay open and the sounds play on the phone's speaker.
```

Why:
- Reviewers have no headset, and the welcome screen keeps the tabs hidden until a headset (or the
  demo) is connected (`ui/EarshotApp.kt`, `gated`).
- The demo button appears only after the Bluetooth permission is granted
  (`ui/onboarding/OnboardingScreen.kt`, `onTryDemo`). That is why step 1 is there. If the roadmap's
  0.9 item "Demo mode works without the Bluetooth permission" ships first, drop step 1.
- With Bluetooth off, or when Earshot finds a paired headset by itself, the tabs show straight
  away and the welcome screen's button isn't there (`bt/ConnectionManager.kt`: Bluetooth off is
  "disconnected", not "no paired device"); the same switch is in More → App preferences.
- The app's label uses a long dash ("Try demo mode — no headphones needed"); the block uses a plain
  hyphen so nothing odd is pasted. Reviewers will still find the button.
- Sleep in demo mode: the virtual headset counts as a connected headset, so a night started there
  expects a Bluetooth output for it (`sleep/SleepRepositoryImpl.kt`, `soundOnHeadset`), finds none
  and stops the sounds rather than moving them to the speaker (`sleep/SleepSoundPlayer.kt`,
  `HEADSET_GONE`). Hence the last line. Bluetooth has to go off first: with demo mode off, Bluetooth
  on and no paired headset, the status is "no paired device" and the welcome screen hides the tabs
  again (`ui/EarshotApp.kt`, `gated`); with Bluetooth off it is "disconnected" and the tabs stay.
- **Code fix that would simplify this** (outside `docs/store/`): if `SleepRepositoryImpl.startLocked`
  stops counting the demo address (`ConnectionManager.DEMO_ADDRESS`) as a headset output, Sleep
  plays on the speaker in demo mode too, and the last line can become "Sleep sounds play on the
  phone's speaker when no real headset is connected."

### 2.3 Ads

**Does your app contain ads?** No. Earshot has no ad SDK and no internet permission.

### 2.4 Content rating

Start the questionnaire, give the email address that should receive the IARC certificate, and pick
the category for apps that aren't games and aren't social, communication or news apps. Google's
help pages don't list the category names; developers commonly report it as "All Other App Types",
but that label isn't confirmed here.

Answer **No** to every content question:

| Topic | Answer | Why |
|---|---|---|
| Violence, fear, sexual content, crude humour, bad language | No | None in the app. |
| Drugs, alcohol, tobacco | No | |
| Gambling or simulated gambling | No | |
| Users can interact or exchange content with each other | No | No accounts, no chat, no uploads. The connection report goes through Android's share menu to an app the user picks; Earshot itself connects people to nobody. |
| Shares the user's location with other users | No | Earshot never reads a location; it reads only the Wi-Fi name, on the phone. |
| Users can buy digital goods | No | **Change to Yes when the $3.99 unlock ships** (§5). |
| Unrestricted internet access (browser, search) | No | No internet permission. |

Expect the lowest rating everywhere (for example ESRB Everyone, PEGI 3). Fill the questionnaire in
again whenever a change would alter an answer.

### 2.5 Target audience and content

| Field | Answer | Why |
|---|---|---|
| Target age groups | 13–15, 16–17 and 18 and over | A headset utility for teenagers and adults. **Never tick an age group under 13**: that puts the app under the Families policy. Matches PRIVACY.md "Children". |
| Could the store listing unintentionally appeal to children? (if asked) | No | Plain listing, no cartoon art, no child-directed wording. |

### 2.6 News apps

**Is your app a news app?** No.

### 2.7 Data safety

Answer: **no data collected, no data shared.**

| Step | Answer |
|---|---|
| Overview | Next |
| Does your app collect or share any of the required user data types? | **No** |
| Account creation (if the form asks) | My app does not allow users to create an account |
| Encryption in transit, deletion requests | Not asked after "No" (Google's help asks them only after "Yes"). |
| Preview | Should read "No data shared with third parties" and "No data collected". Submit. |

Why, type by type. Google's definitions: **collect** means the app transmits data off the device;
data processed only on the device is not disclosed; a transfer the user starts and expects is not
**sharing**; data a user saves to their own cloud storage, which the app never reads back from
there, is not collected.

| Data Earshot touches | Where it goes | Collected or shared? |
|---|---|---|
| Location | Never read. Precise location is only Android's condition for reading the Wi-Fi name, which is compared on the phone with the name in the user's automation. | No |
| Audio (voice memos, mic check) | Voice memos go to the phone's Recordings folder; the mic check plays back on the phone. | No |
| Files (the user's own sleep sounds) | A private copy in the app's storage. | No |
| App activity (installed apps for pickers, the app in front for an automation) | Read on the phone, never stored beyond the user's chosen rules. | No |
| Device IDs (headsets' Bluetooth addresses and names) | App storage on the phone. | No |
| Diagnostics (connection log, Bluetooth history) | Memory and app storage on the phone. | No |
| Connection report (Lab → Copy connection report, or "Send a report" on an Experimental headset) | Clipboard or Android's share menu, only when the user taps it. The user's own app (mail, browser) sends it, if the user chooses. The developer gets it only if the user sends it to them. | No: Earshot transmits nothing; a transfer the user starts is not sharing. |
| Settings backup (More → Back up and restore) | One file, written through Android's file picker to a place the user picks. If that is a cloud folder, the user's storage app moves it under its own terms; Earshot never reads it from there. | No |
| Android cloud backup | Off (`android:allowBackup="false"`), so Android doesn't copy Earshot's data to Google Drive. | No |
| Libraries | AndroidX, Compose, Glance, DataStore and kotlinx only: no analytics, crash-reporting or ad SDK. | No |

With no `INTERNET` permission, nothing in the app can send data, and the release gate refuses a
build that gains it (`tools/release/check_release.py`, `FORBIDDEN`). Re-do this form when the Billing
library arrives (§5).

### 2.8 Government apps

**Is your app developed by or on behalf of a government?** No.

### 2.9 Financial features

**My app doesn't provide any financial features.** (The later $3.99 unlock is a purchase made
through Google Play, not a financial feature.)

### 2.10 Health apps **[decision]**

Every app must file this form, even one with no health features.

**Recommended answer: tick "Sleep Management" (under Health and fitness) and nothing else.**

Why:
- Google describes Sleep Management as apps that help improve or track sleep, and says they may
  include relaxation features such as sounds. The Sleep tab is a sleep timer, sleep sounds and wake
  alarms.
- "My app doesn't provide any health features" would be inaccurate, and Google reviews the
  declaration with the app. (The brief for this page said "no" for health; this is the one place
  where the page disagrees with it. Your call.)
- Sleep Management is a "Health and fitness" category, not "Medical", so no medical-device
  disclosures apply.
- If a second page asks more questions: Earshot measures nothing about sleep, reads no health data,
  doesn't use Health Connect, makes no medical claims and isn't a regulated medical device.

Don't tick "Stress Management, Relaxation, Mental Acuity": the sounds are there for sleep, which
Sleep Management already covers.

### 2.11 Advertising ID

**Does your app use advertising ID?** No. No library adds `com.google.android.gms.permission.AD_ID`;
check it in the merged manifest of the `.aab` (§0).

### 2.12 Foreground service permissions (B1)

Play asks, for each foreground-service type in the uploaded bundle: a description of the feature,
the user impact if the system defers or interrupts it, a video link, and a use case from Google's
list. The three types are declared in `AndroidManifest.xml` and passed to `startForeground`.

Videos: record with the phone's own screen recorder with "media sounds" on and touches shown. Upload
each one to YouTube as **Unlisted** and paste its link. Use the real QC Ultra 2. Pair it in
Android's Bluetooth settings before you start recording, close the Bose app, and start the
recording in Earshot. Google wants the device connection shown inside the app, not in system
settings. One short video per type (about 60 to 90 seconds) is easier to review than one long one.
Cut waiting time, but keep the steps.

#### connectedDevice (`service.HeadphoneService`)

**Use case:** Continuous Data Transfer to an External Device

**Description:**

```text
Earshot is a companion app for Bluetooth headphones and earbuds. While the user's headset is connected, this service keeps Earshot's control link to it open. Over that link the headset reports battery, listening mode and noise cancelling, and Earshot sends the changes the user makes from the persistent notification (mode and noise-cancelling buttons), the Quick Settings tile, the widget and the user's automations. It starts when the user opens Earshot, which connects to the headset (or taps Connect), or when a headset the user set up in Earshot connects to the phone (the user can turn this off with Auto-connect). It stops two minutes after the headset disconnects. For a headset Earshot has no driver for, it only follows the Bluetooth connection and sends the headset nothing.
```

**User impact if deferred or interrupted:**

```text
The control link to the headset closes. The notification's mode and noise-cancelling buttons, the tile and the widget stop working, the battery and mode they show go out of date, and automations that should run when the headset connects don't run. The user has to open Earshot and connect again.
```

**Video script** (real QC Ultra 2):
1. Open a fresh install of Earshot (or clear its storage first) on the welcome screen. Tap
   **Allow Bluetooth** and allow Nearby devices.
2. After the Nearby devices grant, Earshot finds the paired QC Ultra 2 and connects by itself; Home
   shows the battery and the listening modes. (Only if several headsets are paired and none is
   connected does "Your headphones" appear: pick the QC Ultra 2 there and tap **Connect**.)
3. In-app device link: Tools → Controls → Universal remote → **Link this headset**. Android's
   companion dialog appears inside Earshot; allow it. Earshot confirms the link.
4. Go to the home screen (Earshot in the background). Pull down the notification shade: Earshot's
   notification shows the battery and mode buttons. Tap another mode; a caption (or a second camera
   shot) shows the headset switching.
5. Lock the phone for a few seconds, unlock, tap the ANC button in the notification.
6. Switch the headset off: the notification shows it disconnected. Switch it on again: Earshot
   reconnects by itself and the notification is back.

Why it fits: the service exchanges data with a Bluetooth device the user connected, for as long as
it is connected, and stops when there's nothing to talk to (`HeadphoneService.kt`, `IDLE_STOP_MS`).

#### mediaPlayback (`sleep.SleepService`)

**Use case:** Media Playback

**Description:**

```text
Earshot's Sleep tab plays sleep sounds the user picks (recordings such as rain or ocean, generated noise, or the user's own audio files) for a sleep timer the user sets, from 15 minutes to 6 hours, and plays the wake-up alarm the user sets. This service keeps that playback going with the screen off and the phone locked. It starts only when the user taps Start sleep on the Sleep tab (or Earshot's "Start sleep timer" launcher shortcut, which opens Earshot and starts it from there), and again at the time of a wake-up alarm the user set, to play the alarm. If Android closes Earshot during a night the user started, the service starts again to continue that night; if Android doesn't allow that start, a notification asks the user to tap to resume the sounds, and that tap (or opening Earshot) starts it. With "no sound, timer only", the user plays their own podcast or music in another app, and the service lets Earshot fade and pause it when the timer ends. The service stops when nothing plays: the timer ended, the user tapped Stop, or the alarm was stopped or snoozed.
```

**User impact if deferred or interrupted:**

```text
The sleep sounds stop in the middle of the night, and the end of the timer (fade out, pausing other apps, disconnecting the headset) runs late or not at all. The wake-up alarm can't play in the earbuds; Earshot then falls back to an alarm notification on the phone.
```

**Video script:**
1. Open the Sleep tab. Pick Rain, then a timer.
2. Set a wake-up alarm two minutes from now, ringing in "Both".
3. Tap **Start sleep**. The sleep notification appears.
4. Lock the phone. Rain keeps playing (the recording's media sound shows it).
5. Cut to the alarm time: the alarm screen opens over the lock screen and rings. Tap **Stop alarm**.

Why it fits: the user starts the playback, and the alarm is one the user set. The alarm start from
the background is allowed because the app holds the user-granted exact-alarm access
(`SCHEDULE_EXACT_ALARM`). If review objects to the alarm part, Google's list allows `systemExempted`
for apps holding exact-alarm access; that would be a code change, so try the answer above first.

#### microphone (`shortcut.VoiceMemoService`)

**Use case:** Background Audio Access

**Description:**

```text
Earshot lets the user give a Bluetooth headset's assistant button a new job. One choice is a voice memo: after the user picks "Voice memo" in Earshot's Controls screen and allows the microphone, pressing and holding the headset's button opens Earshot, which starts recording from the headset's microphone (or the phone's). This service keeps the recording going while the screen is off or another app is in front, and its notification shows that a recording is in progress. Recording stops when the user presses the button again, taps Stop in the notification, after 30 minutes, or when the headset disconnects. The memo is saved to the phone's Recordings folder.
```

**User impact if deferred or interrupted:**

```text
The recording stops early or never starts, and the user loses what they were saying.
```

**Video script:**
1. Tools → Controls. Set Shortcut Button 1 to **Voice memo**. Tap **Allow** for the microphone.
2. Go to the home screen.
3. Press and hold the headset's shortcut button. The first time, Android asks which app to use:
   pick Earshot, then Always.
4. The "Recording a voice memo" notification appears. Speak a sentence.
5. Tap **Stop** in the notification. "Voice memo saved" appears; tap it to open the memo.

Why it fits: the user starts each recording with a deliberate press, and Android 14+ lets the
service start only because the press opens Earshot's trigger screen in the foreground
(`VoiceMemoService.kt`).

### 2.13 Full-screen intent (B2)

The form asks whether the app's **core** function is one that gets `USE_FULL_SCREEN_INTENT` by
default (alarms or calls). Google's help doesn't print the exact choices.

**Answer: Earshot's core function is not alarms or calls.** Pick the option that says so (it may be
worded as "other" or "my app doesn't have this core functionality"). If there is a text field,
paste:

```text
Earshot uses a full-screen notification only for the wake-up alarm the user sets on its Sleep tab: when the alarm rings, Earshot's alarm screen opens over the lock screen with Stop and Snooze. Alarms are one feature of Earshot, a companion app for Bluetooth headsets, not its core function. Earshot asks the user on the Sleep tab to allow full-screen notifications. Without that permission the alarm still rings from its foreground service and shows as a heads-up and lock-screen notification with Stop and Snooze.
```

Why:
- Claiming "alarm app" would be a stretch (one tab of five), and a rejected claim blocks the
  update.
- The app already degrades gracefully: the check is `sleep/SleepRepositoryImpl.kt` (`canShowFullScreenAlarm`),
  the banner that opens Android's setting is `ui/sleep/SleepPermissionBanners.kt`, and the alarm
  notification keeps Stop and Snooze (`sleep/SleepNotifications.kt`).
- Consequence: installs from Play on Android 14+ start without the permission, and the Sleep tab's
  banner asks for it. Test that path once (research H.4).

### 2.14 Precise location (B4), from November 2026

Google opens this declaration in **November 2026**. Compliance is mandatory from **2027-01-27**
(30-day extensions are offered in the Console). File it as soon as it appears under App content,
and before applying for production. It has two questions.

**Question 1: which user-facing features need `ACCESS_FINE_LOCATION`?** Tick:
- **Nearby devices**: Bluetooth scans on Android 9 to 11.
- **Other**: reading the Wi-Fi network name for an automation the user creates.

Don't tick "Area specific actions": that is geofencing, and Earshot never knows the area. Don't tick
"Network diagnostics": Earshot maps and measures nothing.

**Question 2: why aren't approximate location or the location button enough?** Paste:

```text
Earshot never reads a location fix, requests no background location, and stores and sends no location. It holds ACCESS_FINE_LOCATION only because Android requires it for two on-device features:

1. Wi-Fi automation (Other). The user can create a rule "when I join this Wi-Fi network" (for example, switch the headset to a quieter mode at work). To compare networks, Earshot reads the connected network's name (SSID) through ConnectivityManager with FLAG_INCLUDE_LOCATION_INFO (Android 12 and newer; WifiManager.getConnectionInfo on older versions). Since Android 10 the SSID is only given to apps holding ACCESS_FINE_LOCATION; with ACCESS_COARSE_LOCATION Android returns "<unknown ssid>", so approximate location can't run the rule. The location button isn't enough either: it grants a single session after a tap, while the rule has to check the network by itself whenever the Wi-Fi network changes while Earshot is open, with no tap. Earshot asks for the permission only after the user picks this trigger and taps "Allow location" in a banner that explains the use first.

2. Bluetooth on Android 9 to 11 (Nearby devices). On those Android versions a Bluetooth LE scan returns no results unless the app holds location permission, and on Android 10 and 11 it must be precise location for an app that targets Android 10 or higher, as Earshot does. Earshot scans only to read AirPods and Beats battery from their Bluetooth adverts and to find a JBL headset's Bluetooth LE control address. It asks from a banner the user taps, only on Android 9 to 11. On Android 12 and newer it uses BLUETOOTH_SCAN with neverForLocation and needs no location.
```

Why these answers hold, in the code:
- SSID: `extras/automation/WifiSsidWatcher.kt` (the callback with `FLAG_INCLUDE_LOCATION_INFO` on
  Android 12 and newer, `WifiManager.connectionInfo` before that; no
  background location on purpose, so the rule fires while Earshot is open or on the next network
  change after it comes to the front, as the trigger's hint in the app says).
- Bluetooth: `ui/home/BleScanBanner.kt` (Android 9 to 11 only; asks on tap).
- Scan flag: `AndroidManifest.xml`, `BLUETOOTH_SCAN` with `neverForLocation`.
- Google's "Nearby devices" text says "apps targeting Android 11 or lower". Earshot targets 36, but on
  phones running Android 9 to 11 those versions' rules apply, which is what the answer explains.
- At targetSdk 37 the location button becomes mandatory for **one-time** uses. Earshot's two uses are
  ongoing, so it does not declare `onlyForLocationButton`.
- Re-read the chart when the form opens: if Google adds an option that fits the Wi-Fi rule better
  than "Other", use it and keep the Question 2 text.

### 2.15 Permissions that need no form

| Permission | Why there's no form |
|---|---|
| `SCHEDULE_EXACT_ALARM` | Only `USE_EXACT_ALARM` needs a declaration, and Earshot must never add it. If ever asked: "wake-up alarms and sleep-timer steps the user sets, granted by the user in Alarms & reminders". |
| `PACKAGE_USAGE_STATS` (usage access) | Not on Play's restricted list. The listing mentions it (listing.md, permissions list). |
| Package visibility | No `QUERY_ALL_PACKAGES`; targeted `<queries>` only, and the gate refuses it. |
| Background location | Not requested, so the background-location form doesn't appear. |
| Bluetooth, notifications, microphone, Do Not Disturb, boot, companion device | Runtime or normal permissions with no Play form. |

## 3. Store settings

Play Console → **Store settings** (and Store presence → Main store listing for the text, see
[listing.md](listing.md)).

| Field | Answer |
|---|---|
| App category | Apps → **Music & Audio** (where headset companion apps sit). |
| Tags | Up to five from Google's list that match headphones, audio, equaliser and sleep. Pick from the list the Console shows; don't invent. |
| Email address (public) | A support address you're happy to show publicly; it should be the same one PRIVACY.md gives. |
| Website | `https://github.com/HellzYeahh/earshot` |
| Phone number | Leave empty. |
| External marketing | Your choice; it only lets Google show the listing outside Play. |

Not verified for Play: whether the Console asks personal accounts about EU "trader" status. If it
does, the answer depends on whether you sell anything; it becomes relevant with the paid unlock.

## 4. Account checks and developer verification

### 4.1 The new personal account (D1, D2)

1. Register the personal developer account (one-time fee) and complete **identity verification**:
   legal name and address, verified email and phone, and a government ID unless a verified Google
   Payments profile already covers it.
2. Complete **"Verify that you have access to an Android mobile device"** on the Console's Home
   page: scan its QR code, install the Play Console app on a physical, non-rooted phone running
   Android 10 or newer (the Fold7 qualifies), sign in as the account owner and tap Verify.
3. Pick the **developer name** shown on Play. It must not suggest a headset maker (impersonation
   policy). Your own name or a neutral studio name works; "Earshot" alone is fine too.
4. Run the **closed test** (12 testers opted in for 14 days in a row) and then **Apply for
   production** on the Dashboard: [closed-test.md](closed-test.md).

### 4.2 Android developer verification (package registration)

Google's 2026 developer verification requires apps on certified phones to come from a registered
developer and package. It started on 2026-09-30 for Brazil, Indonesia, Singapore and Thailand and
goes global in 2027.

- **`dev.earshot` (Play):** registered automatically when the app is created in Play Console. Nothing
  to do beyond checking the Home page for a registration task.
- **`dev.earshot.foss` (GitHub, IzzyOnDroid, F-Droid):** register it by hand in the same Play Console
  account (Google's path is "Distribute on and outside Google Play"), with proof that you own the
  **foss release key**. Do it before the 2027 rollout. Google's guide doesn't print the menu steps;
  follow the task the Console's Home page shows. The certificate's SHA-256 comes from
  `apksigner verify --print-certs` on the signed foss APK ([release-signing.md](../release-signing.md) §4);
  run it yourself, and keep the keystore and its passwords off any chat.
- **F-Droid:** a package can be registered with one signing key. If F-Droid signs with its own key,
  that clashes with the GitHub key; ask F-Droid for a reproducible build published under your
  signature (community reports, not Google's text). IzzyOnDroid ships your own signed APK.
- **The 0.9.1 bridge** (debug-signed `dev.earshot`, GitHub only) can't be registered sensibly. Ship it
  before the 2027 rollout and keep it a one-off.

## 5. When to come back to these forms

| Change | Forms to redo |
|---|---|
| The $3.99 unlock (Billing library) | Content rating: digital purchases **Yes**. Data safety: re-check against the Billing library's own disclosure, and confirm the merged play manifest still has no `INTERNET` (research G2). Payments profile and in-app product. Listing: in-app purchases label. Possibly EU trader status. |
| targetSdk 37 | Foreground services: nothing new. Precise location: re-read §2.14 (the location-button rule starts applying). Re-test the alarm without the full-screen permission (research H). |
| A new permission or foreground-service type | The matching declaration, Data safety, and listing.md's permissions list. |
| A library that could send data | Data safety, and the "no internet" lines in listing.md. The gate must still pass. |
| A new feature that touches health or location | §2.10 or §2.14. |

## Sources

Google's pages, read 2026-10-29:
- Foreground services and full-screen intent declarations: https://support.google.com/googleplay/android-developer/answer/13392821
- Permissions and APIs that access sensitive information: https://support.google.com/googleplay/android-developer/answer/9888170
- Precise location declaration: https://support.google.com/googleplay/android-developer/answer/17033915
- User Data policy (privacy policy, prominent disclosure): https://support.google.com/googleplay/android-developer/answer/10144311
- Data safety: https://support.google.com/googleplay/android-developer/answer/10787469
- Health apps declaration: https://support.google.com/googleplay/android-developer/answer/14738291
- Content ratings: https://support.google.com/googleplay/android-developer/answer/9898843
- Testing requirements for new personal accounts: https://support.google.com/googleplay/android-developer/answer/14151465
- Device verification for new personal accounts: https://support.google.com/googleplay/android-developer/answer/14316361
- Android developer verification: https://developer.android.com/developer-verification and https://developer.android.com/developer-verification/guides/google-play-console
- Foreground service types: https://developer.android.com/develop/background-work/services/fgs/service-types

Unofficial, used only where marked: F-Droid forum on self-signed APKs, https://forum.f-droid.org/t/moving-to-distributing-self-signed-apks/34312
