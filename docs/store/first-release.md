# Your first Google Play release, step by step

Written 2026-10-30 for a first-time publisher with a new **personal** developer account. Each step
links to the detailed paperwork. Expect **about 3–4 weeks** from creating the account to being
live, mostly waiting: identity checks, then a 14-day closed test, then a production review.

## 0. Today, on your PC (30 minutes)

1. **Make the two signing keys and back them up:** [release-signing.md](../release-signing.md),
   steps 1–3.
   - The Play upload key signs what you upload. Google keeps the real app-signing key.
   - The GitHub key signs the GitHub APK. Nobody can recover it, so back it up off the PC before
     anything else.

## 1. Create the developer account (day 1; verification can take a few days)

Go to **play.google.com/console** and sign up. Have these ready:
- **A Google account** for publishing. A separate one just for Earshot keeps your personal Gmail
  out of it.
- **A card** for the one-time registration fee (US$25 at the time of writing).
- **Account type: Personal.**
- **A developer name** (shown on Play; you can change it later), for example "Earshot" or your own
  name.
- **Your legal name and address.** They come from the Google Payments profile you link, and must
  be verified before you can publish. Have an ID document ready.
- **Contact email, contact phone and a developer email.** Each one is confirmed with a code. The
  developer email is public.
- **An Android phone** with the Google Play Console app, in case Play asks you to prove you have
  one (your Fold7 is fine).

**What Play shows publicly:** your legal name, your country and your developer email. **If you
later sell something** (the $3.99 unlock), Play also shows your **full address**, and you must
verify a payment method, which can take up to 5 days. Decide on that before the unlock ships.
Source: https://support.google.com/googleplay/android-developer/answer/13628312

## 2. Set up the app in the Console (an evening, once the account is verified)

1. **Create app:** name "Earshot", default language English, App, Free. Accept the declarations.
   The package name `dev.earshot` gets fixed by your first upload and can never change.
2. **App content forms:** fill in every form in [play-console.md](play-console.md) section 2. The
   answers are written out to paste:
   - privacy policy, app access (demo mode), ads: none, content rating, target audience;
   - Data safety: no data collected or shared;
   - the foreground-service, full-screen alarm and (from November) precise-location declarations.
   - The privacy-policy link only works once `PRIVACY.md` is pushed to GitHub.
3. **Store listing:** copy the texts from [listing.md](listing.md) and add the graphics:
   - the app icon, 512 × 512;
   - a feature graphic, 1024 × 500;
   - at least 2 phone screenshots. The emulator shots in `docs/screenshots/` are a start; listing.md
     says which ones to add.

## 3. The closed test (14 days, required for new personal accounts)

Play won't let a new personal account publish to everyone until **at least 12 testers have stayed
opted in to a closed test for 14 days in a row.** The full guide, with invitation texts, is in
[closed-test.md](closed-test.md). In short:
1. **Build the release:** tell Claude the keys exist. It builds the signed `.aab` and runs the
   release gate on it.
2. **Set up the track:** in the Console, go to Testing → Closed testing, create a track, upload the
   `.aab` and add your testers' Google account emails (12 or more; invite 15–20 so a few dropping
   out doesn't reset the count).
3. **Wait for Google's check:** it reviews the test release (hours to a few days), then the
   opt-in link works.
4. **Testers join and stay:** they opt in from the link and install from Play. They must stay
   opted in for the whole 14 days. Uninstalling is fine; leaving the test is not.
5. **Fixes along the way:** fix what testers report. A new upload during the test is fine, but
   raise `versionCode` each time.

## 4. Apply for production (after day 14)

- **Apply:** Dashboard → "Apply for production". The questions ask about the test, what you
  changed and why the app is ready. Draft answers are in [closed-test.md](closed-test.md)
  section 7.
- **Google's review:** it takes a few days (community reports say up to about a week).
- **Roll out:** once approved, create a production release from the same `.aab` (or a newer one).
  A staged rollout, for example 20 % first, is a gentle start.

## 5. GitHub, at the same time (no Google needed)

1. **The 0.9.1 bridge:** a last debug-signed `dev.earshot` with Back up and restore, so today's
   GitHub users can move their settings. The recipe is in
   [release-signing.md](../release-signing.md) under "Moving existing users".
2. **Then 1.0:** `dev.earshot.foss` signed with your GitHub key, as `earshot-1.0.0.apk`.
3. **Before 2027:** register `dev.earshot.foss` and the GitHub key in the Play Console's developer
   verification page, so phones in every country keep installing it.

## Things first-timers trip over

- **The package name is permanent** after the first upload. Ours is `dev.earshot` for Play.
- **Keep the upload key backed up.** Google can reset a lost upload key, but it takes a support
  request and days.
- **Every upload needs a higher `versionCode`** than the one before.
- **Play shows your legal name.** If you'd rather show a business name, that needs an
  organisation account (with a D-U-N-S number). Sources disagree on whether a personal account can
  be converted later, so choose before you sign up.
- **Rejections usually point at one form.** The answers in play-console.md were written to avoid
  the common ones (foreground services, full-screen alarm, location, Data safety), so re-check that
  form first if a review comes back.
