# Closed test: 12 testers for 14 days

A new **personal** Play developer account (created after 2023-11-13) can't publish to production
until it has run a closed test with **at least 12 testers opted in for 14 days in a row**, and then
applied for production access. Organisation accounts are exempt; Earshot's account is a new personal
one (user decision, 2026-10-29). Written 2026-10-29 from Google's help page (sources at the end) and
[research/play-readiness.md](../research/play-readiness.md) D1.

The forms the test needs first are in [play-console.md](play-console.md); the listing testers see is
[listing.md](listing.md).

## The rule, exactly

- At least 12 testers, each **opted in continuously for the 14 days** before you apply.
- A tester who opts in and leaves before 14 days doesn't count. Opting out and back in restarts
  that tester's 14 days.
- Google's help doesn't say whether testers must install the app, only that they must be opted in.
  Ask them to install it and use it: the application asks how much they used it.
- When the 14 days are done: Dashboard → **Apply for production**, three short sections of
  questions (drafts in §7). Google usually answers within 7 days, and may ask you to keep testing if
  there were too few testers or too little use.

## Timeline

About three to four weeks from the first upload to production:

| When | What |
|---|---|
| Week before | Account verified (play-console.md §4.1), App content forms and listing done, 15–20 testers recruited with their Google account emails. |
| Day 0 | Closed-test release sent for review. A new app's first review can take several days. |
| Day 1 | Approved: send the opt-in link. The 14 days run from the day the twelfth tester is opted in. |
| Days 1–14 | Testing, check-ins on days 3, 8 and 13, fixes shipped to the same track. |
| Day 15 | Apply for production. |
| Up to a week later | Approval; then the production release (§8). |

Recruit **15 to 20** people: a few always drop out, and falling below 12 restarts the wait.

## 1. Before the first upload

- [ ] Account: identity and device verification done (play-console.md §4.1).
- [ ] Every App content form filled in (play-console.md §2), including the three foreground-service
      videos. The precise-location form only exists from November 2026; file it when it appears.
- [ ] Main store listing filled in (listing.md); testers see it when they open the link.
- [ ] The play bundle built and checked ([release-signing.md](../release-signing.md) §4):
      `.\gw.cmd :app:bundlePlayRelease`, signed with the upload key, passing the release gate (no
      `INTERNET`, not debuggable). Each upload needs a higher `versionCode`. Codes up to 11 belong
      to the old GitHub `dev.earshot` builds (11 is the 0.9.1 bridge), so start above 11.
- [ ] Optional but worth it: an **internal test** first (Testing → Internal testing; you as the only
      tester). It doesn't count towards the 14 days, but it proves the Play-signed build installs,
      connects to the QC Ultra 2 and runs a Sleep night before strangers see it.

## 2. Console steps

Labels are from Google's help pages on 2026-10-29; if one differs, the step's goal stays the same.

1. **Testing → Closed testing.** Use the closed track the Console creates (or create one named
   "Earshot closed test").
2. **Countries / regions:** add every country your testers live in. The app is only offered there.
3. **Testers tab:**
   - **Email lists → Create email list**, named "Earshot testers". Add each tester's Google account
     email (the one on their phone's Play Store), or upload a CSV. A Google Group works too, if
     you'd rather testers join by themselves.
   - **Feedback URL or email address:** your feedback address (§5). Testers see it on the opt-in
     page.
   - Save.
4. **Create new release** on the track:
   - Upload the `.aab` (or promote the internal-test release).
   - Release name: the version, for example `1.0.0 (12)`.
   - Release notes: the "First closed-test release" text in listing.md.
   - Save, review the release, and roll it out to the closed track.
5. **Publishing overview → Send changes for review.** If managed publishing is on, press Publish
   after approval.
6. After approval, back on the **Testers** tab, copy the opt-in link ("How testers join your test")
   and send it with the welcome message (§4).
7. Every few days, check the Dashboard's production-access section and the opted-in count. If it
   nears 12, recruit more at once.
8. **Updates during the test:** create a new release on the same closed track with a higher
   `versionCode`. Testers get it through Play; their opt-in, and the 14 days, carry on.
9. Day 15 or later: **Dashboard → Apply for production** (§7).

## 3. Who to invite

Aim for variety, because each kind of tester covers something we can't test ourselves:
- **Headsets:** Bose owners other than the QC Ultra 2 (earbuds, QC45, QC Headphones), and above all
  owners of Experimental headsets (Soundcore, Sony, Sennheiser, Skullcandy, JBL): their reports are
  how a driver moves to Tested. Then plain Bluetooth earbuds, and AirPods or Beats used with Android.
- **Phones:** Samsung, Pixel, Xiaomi (GitHub issue #1 was a Xiaomi on Android 16), OnePlus and
  others; at least one foldable or tablet.
- **Android versions:** one or two on Android 9 to 11 (the location banner for AirPods and JBL), and
  some on 12 to 16 as well as 17.
- **No headset:** fine. They test demo mode, Sleep on the phone, alarms, backups and the look.
  Without a headset, Sleep sounds don't play in demo mode: they switch Bluetooth off, then turn
  demo mode off (More, App preferences, Demo mode), and the sounds play on the phone's speaker.
  (With Bluetooth on and no paired headset, the welcome screen comes back once demo mode is off.)

Where to find them (Google's own tips): friends, family and colleagues first, then communities that
fit the app. People who opened GitHub issues already care; asking them is your call.

Every tester needs a Google account on an Android phone (Android 9 or newer, with Bluetooth).

## 4. Messages to testers

You send these yourself. Fill in the `<...>` parts.

### Invitation

```text
Subject: Help test Earshot on Google Play (14 days)

Hi <name>,

I'm putting Earshot, my Android app for Bluetooth headphones and earbuds, on Google Play. Google asks new developers to run a closed test first: at least 12 people have to stay signed up for 14 days in a row before the app can go public. Would you be one of them?

What Earshot is: one app for your headset, whatever the brand. An EQ, sleep sounds with a sleep timer and wake-up alarms, button shortcuts, a codec panel and automations, and for some headsets (Bose, Soundcore, Sony, Sennheiser, Skullcandy, JBL) their own noise and mode controls. No account, no ads, no analytics, and the app has no internet access.

What you need:
- an Android phone with Android 9 or newer, and its Google account;
- any Bluetooth headphones or earbuds (no headset? demo mode shows everything);
- ten minutes now and then over two weeks.

What I'd ask:
1. Reply with the Google account email your phone's Play Store uses. I only use it to add you to the test list.
2. When I send the link, open it on your phone, accept the invitation and install Earshot from Google Play.
3. Stay signed up and keep it installed for at least 14 days, even if you stop using it. If someone leaves early, the launch can slip by two weeks.
4. Tell me what works and what doesn't, by replying to my emails or with the feedback link on the Play page.

One catch: if you have an older Earshot from GitHub (before 1.0), Google Play can't install over it. Save a backup in it first (More, Back up and restore, in version 0.9.1), then uninstall it.

Thanks!
<your name>
```

### Welcome (with the link, after Google approves the release)

```text
Subject: Earshot test: your link and what to try

Thanks for joining! Here's the link: <opt-in link>
Open it on your phone, accept, then tap the Google Play link on that page to install Earshot.

Please stay signed up until <date 14 days after the twelfth tester joined>.

What to try: the list below. You don't need to do it all; tell me what you tried.
If something goes wrong, reply to this email with:
- what you did, what happened and what you expected, with the time it happened;
- the connection report, if it's about your headset: in Earshot, Tools, Lab, Copy connection report, then paste it into your email. On an Experimental headset you can also tap the Experimental tag on Home and then Send a report.

The report lists Earshot's version (and whether another Earshot is installed), your phone's model and Android version, your headset's name, Bluetooth address and model (and its LE Audio twin's name and address, if it has one), the Bluetooth services the phone has seen for it, Earshot's connection settings for it, its Bluetooth history on your phone and Earshot's recent connection log. Earshot never sends it by itself; it has no internet access. Read it over first, and remove the Bluetooth addresses if you'd rather not share them.

First night with the Sleep alarm: set a normal alarm in your Clock app as well. This is a test build.

<what to try: paste §4.1 here>

Thanks,
<your name>
<feedback address>
```

### 4.1 What to try

```text
Day 1 (10 minutes)
- Install Earshot, allow Bluetooth (Nearby devices), and connect your headset. No headset? Tap "Try demo mode" on the welcome screen, or turn on More, App preferences, Demo mode.
- Look around all five tabs: Home, Sound, Sleep, Tools, More.
- Check the label on your headset's card on Home (Tested, Experimental, Battery only or Any headset) and tap it.

Every day (no extra time)
- Use your headset as usual with Earshot installed. Does Earshot reconnect by itself when you put the headset on? Does the notification show the right battery?

Once during the two weeks, as far as your headset allows
- Sound: switch on the Pro EQ while music plays; try a preset.
- Sleep: one night with a sound and a timer. One morning with a wake-up alarm, in the earbuds or on the phone. Try Stop and Snooze. Without a headset, sounds don't play in demo mode: switch Bluetooth off, then turn demo mode off (More, App preferences, Demo mode); the sounds then play on the phone's speaker.
- Tools: Find my earbuds, Mic check, the Codec panel, Call audio during a call.
- Tools, Controls: give your headset's assistant button a new job (for example a voice memo) and try it.
- Tools, Controls, Universal remote: Link this headset.
- More, Automations: make a rule that runs when the headset connects. If you try a Wi-Fi rule: Android should ask for location only after you tap "Allow location".
- Add the Quick Settings tile and the home-screen widget.
- More, Back up and restore: save a backup file.
- Bose, Soundcore, Sony, Sennheiser, Skullcandy or JBL headset: switch listening modes, move the noise slider, open the headset's own settings.
- Foldable or tablet: unfold or rotate on Home, Sleep and the alarm screen.

Tell me about
- anything that crashes, freezes or looks wrong;
- a control that does nothing on your headset;
- connection drops, or battery drain on your phone;
- words or screens that confused you.
```

### Check-ins (days 3, 8 and 13)

Short, so people answer:

```text
Day 3: Is Earshot connecting to your headset? Anything broken or confusing so far?
Day 8: Did you try the Sleep tab or an alarm? How did it go? Remember to stay signed up until <date>.
Day 13: Last questions, one line each is plenty:
1. What did you use most?
2. What didn't work on your phone or headset?
3. What would make you keep using Earshot, or stop?
4. Phone model, Android version and headset, if you haven't told me yet.
```

## 5. Collecting reports without analytics

Earshot has no analytics, no crash reporter and no internet access, and the test doesn't change
that. You learn only what testers send. Make sending easy:

| Channel | How |
|---|---|
| Email | One feedback address (the same in the track's feedback field, the listing's contact email and PRIVACY.md). Most reports arrive here. |
| The app's own reports | Tools → Lab → **Copy connection report**, pasted into an email; on an Experimental headset, the tier tag → **Send a report** opens the share menu with the report and a "what works?" prompt. The tester picks the app and the recipient; Earshot transmits nothing. |
| Play's tester feedback | Testers can leave private feedback on the Play listing; it shows up in Play Console, not publicly. |
| GitHub issues | For testers happy to post publicly. Remind them a connection report contains their headset's Bluetooth address; PRIVACY.md says to read it over first. |
| A group (optional) | A Google Group can be the tester list and a place to talk. Google suggests one. |

Play's own signals, which need nothing in Earshot:
- **Android vitals:** crash and "app not responding" rates that Android reports for users who let
  their phone share usage and diagnostics with Google. Earshot adds no SDK for it; it isn't data
  Earshot collects.
- **Pre-launch report:** Google runs each release on its own test devices. Check it for crashes and
  for warnings about non-SDK interfaces (research A3: the Bose connect path uses one).

Keep a **tester log**, and keep it out of the public repo: tester emails are personal data. A
private spreadsheet or a note in your vault works. Use an alias per tester:

| Alias | Phone, Android | Headset (tier) | Opted in | What they tried | Reports (date, channel) | Fixed in |
|---|---|---|---|---|---|---|
| T01 | Pixel 9, 17 | Sony WF-1000XM5 (Experimental) | 2026-11-.. | First run, EQ, Sleep night | 11-05 email + report: ambient level slider does nothing | 1.0.0 (13) |

Every report becomes a line in the log, a fix or a "won't fix, because…", and a new build on the
closed track. Experimental drivers that owners confirm can move to Tested (roadmap 1.0 plan item 4);
update the listing's Tested line only then.

## 6. During the test

- Ship fixes on the closed track as often as needed; each needs a higher `versionCode`.
- Don't remove testers or the track until production access is granted.
- If the opted-in count drops below 12, invite more at once: the 14 days start over for the newcomers.
- Note what you changed and why: the application asks for it.

## 7. Apply for production: draft answers

Dashboard → **Apply for production**. Three sections; nothing is saved until you press Next or
Apply. Fill the `<...>` parts from the tester log.

**Part 1: about your closed test**

- *How easy was it to recruit testers?* Pick the honest option.
- *Tester engagement:*
  ```text
  <N> testers on <N> phone models (Android <x> to <y>) with <N> different headsets, including <brands>. Each got a checklist covering every tab. <N> used Earshot daily with their headset, <N> ran the Sleep tab overnight, <N> tested the wake-up alarm, and <N> without a headset used demo mode.
  ```
- *Did usage match what you expect in production? Differences?*
  ```text
  Mostly: testers used their own headsets in daily life, which is how Earshot is meant to be used. Differences: testers tried more features than a typical user would, and more owners of Experimental headsets took part than we expect at launch, because we asked for them.
  ```
- *Feedback received and how it was collected:*
  ```text
  Earshot has no analytics and no internet permission, so all feedback came from testers: <N> emails, <N> connection reports sent from the app's share menu, <N> GitHub issues and <N> private Play feedback messages, plus check-in questions on days 3, 8 and 13. Main themes: <themes>.
  ```

**Part 2: about your app**

- *Target audience:*
  ```text
  Android users aged 13 and over with Bluetooth headphones or earbuds who want one companion app for their headset, especially those who prefer an app with no account and no internet access, and people who fall asleep to sounds in their earbuds.
  ```
- *Value proposition:*
  ```text
  One app for any Bluetooth headset: a parametric EQ, sleep sounds with a sleep timer and wake-up alarms, button shortcuts, codec and call tools and automations for every headset, plus direct controls for supported Bose, Soundcore, Sony, Sennheiser, Skullcandy and JBL models. It works offline, needs no account and collects no data.
  ```
- *Estimated installs in the first year:* your pick.

**Part 3: production readiness**

- *Changes made after the closed test:*
  ```text
  <list: fix, the version it shipped in, and the tester report that led to it>
  ```
- *How you decided the app is ready:*
  ```text
  Every crash and connection problem testers reported is fixed or explained. The release gate passes on every build: unit tests, lint, no internet permission, a signed and non-debuggable bundle. The phone checklist (docs/PHONE_TEST_CHECKLIST.md) passed on <phones>, including overnight Sleep runs with the wake-up alarm. No tester reported a changed headset setting they didn't ask for or lost settings after an update.
  ```

## 8. After approval

- Production → create a release (promote the last closed-test build), pick the countries, use the
  "First production release" notes from listing.md.
- A staged rollout (for example 20 % first) limits the damage of a late surprise; widen it after a
  few days of quiet Android vitals and email.
- Thank the testers, and tell them they can leave the test once the public version is out (they
  then get updates from the public listing).

## Sources

- Testing requirements for new personal developer accounts: https://support.google.com/googleplay/android-developer/answer/14151465
- Research: [play-readiness.md](../research/play-readiness.md) D1.
