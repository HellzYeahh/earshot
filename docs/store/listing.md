# Google Play store listing

The text and images for Earshot's main store listing on Google Play (`dev.earshot`). Written
2026-10-29 from [research/play-readiness.md](../research/play-readiness.md) E1 to E3, the README,
PRIVACY.md and the app's own strings, and checked against the code on that day. The Console forms
are in [play-console.md](play-console.md).

Play Console → your app → Store presence → **Main store listing**. Default language: English
(United Kingdom).

## Rules this listing follows

From Google's impersonation and metadata policies (sources at the end):
- **No brand in the title**, no maker's logo, no product photos, nothing that suggests the makers
  approve of Earshot. Brands appear only to say which headsets work, by name, in plain text
  (nominative use), plus one "not affiliated" paragraph.
- **No "official", "certified", "authorised", "approved" or "replacement for the X app"**, and no
  ranking or price claims ("best", "#1", "free forever").
- Title 30 characters at most, short description 80, full description 4,000. No emojis, no ALL CAPS,
  no repeated or unrelated keywords, no long model lists (the 35 Soundcore models stay in
  [supported-headsets.md](../supported-headsets.md)).
- The support tiers are the app's own: **Tested / Experimental / Battery only / Any headset**
  (`res/values/strings_support.xml`). Only the Bose QC Ultra Headphones (2nd Gen) and the Soundcore
  Sleep A30 are Tested.
- The privacy promise says exactly what is true: the app **has no internet permission**, and data
  leaves the phone only when the user sends it. It holds only while the play build has no
  `INTERNET` permission, which the release gate enforces (`tools/release/check_release.py`).
- Screenshots show the real app (demo mode is the real app).

## App name

```text
Earshot: Headset Companion
```

26 of 30 characters. Before you save it, search Play for "Earshot" and make sure no app with a
similar name and icon could be confused with it.

Alternatives if you want a different second half (both under 30): `Earshot: Headset EQ & Sleep` (27),
`Earshot: Headset Controls` (25).

## Short description

```text
Control, tune and sleep with your Bluetooth headset. No account, no internet.
```

77 of 80 characters. "No internet" is accurate: the app has no internet permission.

Alternative: `EQ, sleep sounds, alarms and controls for your Bluetooth headset. No internet.` (78).

## Full description

3,986 of 4,000 characters (newlines counted as one each): anything you add needs a cut elsewhere. The bullets are the "•"
character, which Play shows as is; there is no Markdown on Play.

```text
Earshot is one app for your Bluetooth headphones and earbuds, whatever the brand. No account, no ads, no analytics and no internet access.

With any headset
• Pro EQ: a parametric equaliser that remembers your preset for each headset, AutoEq import and a volume boost, in apps that share their audio session (other apps: experimental).
• Sleep: a timer from 15 minutes to 6 hours with rain, ocean, stream, wind and fire recordings, white, pink or brown noise, a fan or your own sounds as seamless loops. At the end it can pause other apps and disconnect the headset.
• Wake-up alarms in the earbuds, on the phone or both. If the earbuds can't ring, or nobody stops the alarm within a minute, it rings on the phone.
• Controls: give the headset's assistant button a new job: your assistant, an app, the next EQ preset or a voice memo.
• Codec panel: the codec playing now and what both sides support. Switch codecs through a linked headset on Android 13 and newer (experimental), or in Developer options.
• Louder calls, a mic check, Find my earbuds, a Bluetooth history, a Quick Settings tile and a widget.
• Universal remote: link a headset once to rename it on this phone (Android 13+), forget it (Android 16+), and connect or disconnect it (Android 17).
• Automations: when the headset connects, at a set time, on a Wi-Fi network or when an app opens, switch a listening mode, the noise settings or the EQ preset.
• Back up your settings to a file and restore them on a new phone.

Headsets Earshot controls
Some headsets also get their own controls over Bluetooth: listening modes, noise cancelling, their EQ, buttons and settings, where the model has them. Each headset shows one of four labels:
• Tested, tried on real hardware: Bose QC Ultra Headphones (2nd Gen) and Soundcore Sleep A30.
• Experimental, built from protocol research and a virtual headset, not yet tried on real hardware: most other Bose models, many Soundcore and JBL models, and some Sony, Sennheiser and Skullcandy models.
• Battery only: AirPods and Beats.
• Any headset: everything else gets the features above, and Earshot never sends it anything.
Earshot never sends firmware updates, factory resets or unpairing commands. Close the maker's app while Earshot controls the headset: only one app can use its control channel at a time.

No headset? Try demo mode: on the first screen once Bluetooth is allowed, or in More, App preferences.

Privacy
Earshot doesn't have Android's internet permission, so the app itself can't send anything to a server. Your settings stay on your phone. Something leaves it only when you send it yourself: a connection report through your share menu, or a backup file saved where you choose.

Permissions and why
• Nearby devices: talking to your headset; AirPods and Beats battery.
• Notifications: the headset notification, the sleep timer and alarms.
• Alarms & reminders: sleep timer steps and wake-up alarms on time.
• Full-screen alarm: the alarm over the lock screen. Without it, the alarm still rings as a notification.
• Microphone: the mic check and voice memos, only when you start them.
• Location: only to read the Wi-Fi name for a Wi-Fi automation, and for Bluetooth scans on Android 11 and older. Earshot never reads or stores where you are.
• Usage access: only for a "when this app opens" automation. The app list stays on your phone.
• Do Not Disturb access: only if you turn it on for the night.

Installed Earshot from GitHub before 1.0? Google Play can't install over it. Save a backup in it (More, Back up and restore, from 0.9.1), uninstall it, then install Earshot here and restore the file.

Open source, MIT licence: github.com/HellzYeahh/earshot

Earshot is an independent app. It isn't affiliated with, endorsed by or supported by Bose, Apple, Anker (Soundcore), Sony, Sennheiser, Skullcandy, JBL (Harman) or any other headphone maker. Brand and product names are trademarks of their owners and are used only to say which headsets work with Earshot.
```

### Where each claim comes from

Check these again before each listing update; a line that stops being true comes out.

| Claim | Source in the code or docs |
|---|---|
| No account, ads or analytics; no internet permission | `AndroidManifest.xml` (no `INTERNET`), `app/build.gradle.kts` (no analytics or ad library), the gate's `FORBIDDEN` set |
| Pro EQ works in apps that share their session; other apps experimental | `sound_pro_eq_subtitle`, `sound_global_session` in `res/values/strings.xml` |
| Pro EQ remembers the user's preset per headset (not a built-in preset per model) | `audio/HeadsetEqMemory.kt` (only explicit picks are remembered) |
| Sleep: 15 min to 6 h, the recordings, noises, fan, own sounds as loops | `assets/sleep/` (rain, heavy rain, ocean, stream, wind, fire), `strings_sleep.xml`, `dsp/sleep/LoopMaker` |
| At the end: pause other apps, disconnect | `sleep_timer_only_hint`, `sleep_end_disconnect`; disconnecting needs a linked headset on Android 17 or a driver |
| Alarm moves to the phone within a minute | `PHONE_FALLBACK_SECONDS` = 60 in `core/SleepModels.kt`, CLAUDE.md "Sleep tab" safety rules |
| Controls choices | `shortcut_choice_*`, `shortcut_action_cycle_eq`, `shortcut_action_voice_memo` |
| Codec switching via a link, Android 13+ | `codec/AssociationCodecPath.kt` (`TIRAMISU`); the play build has no Shizuku (user decision 2026-10-29) |
| Universal remote by Android version | `bt/CompanionRemote.kt` `RemoteAbilities.forSdk`: rename 13, forget 16, connect 17 |
| Automation triggers and actions | `core/AutomationRepository.kt` (`OnConnect`, `Schedule`, `WifiNetwork`, `ForegroundApp`; `SetMode`, `SetNoise`, `SetEqPreset`) |
| The four tiers and who is Tested | `core/SupportTier.kt`, `strings_support.xml`, [supported-headsets.md](../supported-headsets.md) |
| Never firmware, reset or unpairing commands | `bt/WriteGuard` and each vendor guard (CLAUDE.md) |
| One app per control channel | `about_control_channel` |
| Demo mode on the first screen, or in More, App preferences | `onboarding_try_demo` (shown only once Bluetooth is allowed and while the welcome screen is up, `ui/onboarding/OnboardingScreen.kt`, `ui/EarshotApp.kt` `gated`); `prefs_demo_mode` under `settings_preferences` |
| What leaves the phone | PRIVACY.md "When something leaves your phone"; `ui/common/SupportTierChip.kt` (share menu), `ui/backup/BackupScreen.kt` (file picker) |
| Permissions list | PRIVACY.md "Permissions and why", the manifest; usage access banner `trigger_app_usage_access` |
| GitHub before 1.0 can't be replaced from Play | `distribution/Distribution.kt` (play: `LEGACY_PACKAGE` comment), [release-signing.md](../release-signing.md) "Moving existing users" |
| Not-affiliated wording | `about_not_affiliated` and README "Not affiliated" (shortened here to fit; the full trademark list stays in the app's About screen) |

### Never put in the listing

- "Official", "certified", "authorised", "approved by", "partner", "works like the Bose app" or
  "replaces the Sony app".
- Any maker's logo, product photo or box art, in text or images.
- A model list longer than the tested ones.
- "Works with every headset" without the tiers, or "Tested" for anything but the two tested models.
- "Encrypted", "secure" or "100 % private" claims beyond the internet-permission fact.
- Shizuku (GitHub version only), the paid unlock (not built yet), or medical claims about sleep.

## Contact details and category

Set under Store settings; see [play-console.md](play-console.md) §3. Category: Music & Audio.
Website: `https://github.com/HellzYeahh/earshot`.

## Graphics

### App icon

512 × 512, 32-bit PNG with alpha, up to 1 MB. Use the "Strike" icon as it is, day version: the sand
background layer (`drawable/ic_launcher_background.xml`) under the foreground
(`drawable/ic_launcher_foreground.xml`), as a full square. Play rounds the corners itself, so don't
add a mask, shadow or badge. Android Studio's Image Asset tool writes an `ic_launcher-playstore.png`
at 512 × 512 when it builds launcher icons; rendering the two vector layers at 512 px works too.

### Feature graphic

1024 × 500, JPEG or 24-bit PNG, no alpha.

Idea, in Field's own look:
- Background: Field sand `#D8DCCD` with the app's faint dot grid (`ui/theme/DotGrid.kt`). Google
  advises against pure white, black or dark grey, so use the day palette, not the night one.
- Left of centre: the Strike icon, about 300 px tall.
- Right of it: "Earshot" in Barlow (bundled, SIL OFL) in ink `#1C2316`, and under it one line in
  muted ink `#4F5A44`: "One app for your headset". A short signal-orange (`#FF6A1F`) rule between
  them, echoing the icon's left channel.
- Keep everything inside the middle of the canvas (Google warns that the edges can be cut).
- No phone frame, no headphone photo, no brand names, no store badge, no claims.

### Phone screenshots

Requirements: 2 to 8 per device type, JPEG or 24-bit PNG **without alpha**, each side 320 to
3,840 px, the long side at most twice the short side. For Play's recommendations: at least four,
9:16 portrait, at least 1080 × 1920.

**The README screenshots can't be uploaded as they are**: `docs/screenshots/*.png` are 1080 × 2400
with an alpha channel, so they are too tall (2.22:1) and transparent-capable. Make new 9:16 files:
scale each capture to 1920 px tall and centre it on a 1080 × 1920 canvas of Field sand
(`#D8DCCD`), optionally with a caption in the space above it, then save without alpha.

How to capture:
- Demo mode (More → App preferences → Demo mode), day theme, English. The virtual QC Ultra
  Headphones (2nd Gen) is the default device; its model name showing as plain text is fine (it's
  the device's name, not a logo).
- Clean status bar: full battery and signal, no notifications, no carrier name. Android's SystemUI
  demo mode (`adb shell settings put global sysui_demo_allowed 1`, then the
  `com.android.systemui.demo` broadcasts) does it on many phones; if One UI ignores it, switch on
  Do Not Disturb, charge to 100 % and tidy the status bar in an editor.
- No time-sensitive text (no dates in view, no "new in 0.9").
- Captions, if any: short, at most 20 % of the image, no claims or calls to action.

The set, in order (the first three matter most). Alt text is at most 140 characters each.

| # | Screen | Caption (optional) | Alt text |
|---|---|---|---|
| 1 | Home: battery dial, listening modes, noise slider | Your headset at a glance | Home screen with the headset's battery, listening mode buttons and a noise-cancelling slider |
| 2 | Sound: the Pro EQ with a curve | A parametric EQ for every headset | Sound screen with a parametric equaliser curve and its presets |
| 3 | Sleep: timer, a sound picked, wake-up alarm | Sleep sounds, timer and alarm | Sleep tab with a sleep timer, rain chosen as the sound and a wake-up alarm for the morning |
| 4 | The alarm screen over the lock screen | Wake up in your earbuds or on the phone | Full-screen wake-up alarm with large Stop and Snooze buttons |
| 5 | Tools | Tools for any headset | Tools screen listing Controls, Connection, Codec, Call audio, Mic check, Find my headphones and Lab |
| 6 | Codec panel | See and switch the codec | Codec panel showing the codec playing now and the codecs both phone and headset support |
| 7 | Automation editor with the trigger choices | Automations for your headset | Automation editor with triggers for connect, Wi-Fi, app and schedule |
| 8 | The support sheet (tap the tier tag) | Honest support labels | Sheet explaining what works on the connected headset's support tier |

For the Sleep shots, a night theme capture looks natural; keep the rest in the day theme.

### Large screen: the Fold7's inner screen

Play shows tablet screenshots on large screens and has no separate foldable slot; upload this set
where the Console asks for tablet (7-inch and 10-inch) screenshots.

Requirements: at least 4, 16:9 or 9:16, each side 1,080 to 7,680 px, no text that isn't part of the
app (it can be cut off on some Play pages).

The inner screen is nearly square (about 2184 × 1968; check the PNG the phone saves), so it fits
neither ratio. Put each capture, unscaled or scaled to 2,160 px tall, in the middle of a 3840 × 2160
landscape canvas of Field sand, with no device frame and no caption.

Take them after the inner-screen pass (research A4: layout, rotation, alarm screen), in this order:
1. Home (with the headset card and modes side by side if the layout spreads out).
2. Sound (Pro EQ).
3. Sleep.
4. Tools.
5. Optional: the alarm screen, the automation editor.

Alt text: the same as the phone set's matching rows.

### Promo video

Optional, and not needed for launch. If you add one later: a YouTube link, the real app only, no
maker logos, sound optional.

## Release notes

At most 500 characters per language.

**First closed-test release** (365 characters):

```text
First test build on Google Play. Please use Earshot with your headset, or try demo mode, and tell me what works and what doesn't: the feedback address is on this page.
Have an Earshot from GitHub older than 1.0? Google Play can't install over it. Save a backup in it (More, Back up and restore, from 0.9.1), uninstall it, then install this one and restore the file.
```

**First production release** (406 characters):

```text
Earshot 1.0 on Google Play: one app for your Bluetooth headset, with Pro EQ, sleep sounds and alarms, button shortcuts, automations and, for supported headsets, their own controls. No account and no internet access.
Used Earshot from GitHub before 1.0? Google Play can't install over it. Save a backup in it (More, Back up and restore, from 0.9.1), uninstall it, then install this one and restore the file.
```

The GitHub line follows [release-signing.md](../release-signing.md) "Moving existing users". If the
0.9.1 bridge never ships, change "from 0.9.1" to "set Earshot up again here".

## When to update the listing

- **Paid unlock ships:** say which features it unlocks and that everything else stays free; the
  in-app purchases label appears by itself. Re-check the "no internet" lines against the merged
  play manifest first.
- **A driver moves to Tested:** add the model to the Tested line, nowhere else.
- **A feature changes by Android version** (targetSdk 37, new remote abilities): fix the version
  notes in the bullets.
- **A new permission:** add it to "Permissions and why" and to PRIVACY.md.

## Sources

- Impersonation policy: https://support.google.com/googleplay/android-developer/answer/9888374
- Metadata policy: https://support.google.com/googleplay/android-developer/answer/9898842
- Preview assets (icon, feature graphic, screenshots): https://support.google.com/googleplay/android-developer/answer/9866151
- Research: [play-readiness.md](../research/play-readiness.md) E1 to E3.
