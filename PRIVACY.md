# Earshot privacy policy

Last updated: 2026-10-28. Applies to Earshot from Google Play (`dev.earshot`) and from GitHub,
F-Droid or IzzyOnDroid (`dev.earshot.foss`).

## The short version

Earshot collects no personal data and sends nothing anywhere. It has no account, no analytics, no
ads and no crash reporting service. **The app doesn't have Android's internet permission**, so it
can't reach a server even by mistake. Everything it stores stays on your phone.

## What Earshot stores on your phone

These are kept in the app's private storage. Removing the app deletes them.
- Your settings: EQ and presets, per-app sound profiles, automations, button shortcuts, the Sleep
  plan, alarms and mixes.
- The headsets you used with Earshot: their name, Bluetooth address, model and the settings
  Earshot learned about them.
- Sounds you add to the Sleep tab: a private copy of each file you pick.
- A short connection log and a Bluetooth history of your headsets' connects and disconnects. These
  help diagnose problems.
- Voice memos you record with the headset shortcut, if you use that feature. They're saved in your
  phone's Recordings folder, where your file apps can see them.

## When something leaves your phone

Only when you send it yourself:
- **Connection report** (Tools → Lab → Copy connection report). It contains:
  - your phone model;
  - the headset's name, Bluetooth address and firmware;
  - the recent connection log.

  It goes to the clipboard or the share sheet, and only where you paste or send it. Read it over
  before posting it publicly.
- **Settings backup** (More → Back up and restore) writes one file to the place you choose.

## Permissions and why

| Permission | Used for |
|---|---|
| Nearby devices (Bluetooth connect and scan) | Talking to your headset; reading AirPods/Beats battery from their Bluetooth adverts. Scanning is marked as never used for location. |
| Notifications | The connection notification, the sleep timer and alarms |
| Foreground services (connected device, media playback, microphone) | Keeping the headset connection, playing sleep sounds with the screen off, recording a voice memo |
| Alarms & reminders (exact alarms) | Sleep timer steps and wake-up alarms on time |
| Full-screen alarm, vibration, wake lock | Showing the alarm over the lock screen |
| Do Not Disturb access (only if you turn it on for the night) | Turning Do Not Disturb on for the night and back off in the morning |
| Microphone | Mic check and voice memos, only when you start them |
| Location (only for Wi-Fi automations, and Bluetooth scans on Android 11 and older) | Android requires it to read the Wi-Fi network name for a "when I join this Wi-Fi" automation. On Android 11 and older it also requires it for the Bluetooth scans that find AirPods/Beats battery adverts and a JBL headset's control address; there the scan is never used for location. Earshot never reads or stores your location. |
| Usage access (only for app automations) | Knowing which app is in front, for a "when this app opens" automation |
| See installed apps | Listing your apps for per-app sound profiles and automations |
| Companion device (link a headset) | The universal remote: connect, disconnect, rename and forget a headset you link |
| Shizuku (optional, a separate app you install yourself) | Switching the Bluetooth codec on phones that need it |

## Children

Earshot isn't directed at children and collects no data from anyone.

## Changes and contact

Changes to this policy are listed in the repository's history:
https://github.com/HellzYeahh/earshot/blob/main/PRIVACY.md. Questions: open an issue at
https://github.com/HellzYeahh/earshot/issues.
