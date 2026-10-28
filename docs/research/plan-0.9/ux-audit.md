# Earshot 0.9 "Tie it together": UX and coherence audit

Audited 2026-10-26 on `main` (0.8.0). Read-only code review of `app/src/main/kotlin/dev/earshot/ui/**`,
`extras/**`, `service/ServiceNotification.kt`, the state/capability models, `strings.xml`, the manifest and the
docs. Nothing was run on a device, so anything marked "verify on device" is inferred from code.
Paths below are relative to `app/src/main/kotlin/dev/earshot/` unless they start with `res/`.

Ratings: impact H/M/L (how many users, how badly), effort S (hours) / M (a day or two) / L (a week+).

---------------------------------------------------------------------------------------------------

## 1. The themes (what "tie it together" means in code)

1. **One Headset page per device.** Everything about one headset is spread over six places today: identity and
   battery (Home header), its own settings (More card + Home quick card + Tools → Controls "Headphone controls"),
   rename / power off / paired devices (Controls), connect / disconnect / forget / link (Controls → Universal remote,
   bottom of a long scroll), "Headphone type" (App preferences), its EQ memory (silent, Sound), shortcut choice
   (Controls), Bluetooth history (Lab). One page per headset (name, tier, link status, what works, remote
   actions, per-headset memory, history, Find) would replace the scatter and give 0.8's features a home.
2. **One status language.** Three different facts are all called "connected": Earshot's control link
   (`ConnectionStatus`), the audio link (`audioLinked` / `isAudioActive`) and the companion link (CDM). Home says
   "Not connected / Headphones not connected" while music plays through the headset. Tile says "Connect", widget
   and notification say "Disconnected", Home says "Not connected". Define three explicit states and one set of words
   used by header, card, tile, widget, notification, Find and Controls.
3. **Tier and capability as a first-class, shown thing.** The app never says "Tested", "Battery only" or "Any
   headset" anywhere the user looks. Tier shows up as three unrelated mechanisms: an English `supportNote` banner on
   Home (service layer, jargon), an `info_unverified` line at the bottom of More, and nothing at all for the
   experimental Bose models. Replace with one badge + one "What works with this headset" component used on Home,
   the switcher, onboarding, About and the report.
4. **Errors that carry their remedy.** 172 hard-coded `HeadphoneCommandException` messages, one 4-second snackbar
   without an action, one typed `ConnectionProblem` (only `ChannelHeld`). Give failures a typed cause with an
   action (Open app info, Link this headset, Use as any headset, Open Bluetooth settings, Grant) and show them as
   cards or snackbars with a button and a readable duration.
5. **"Link once" as the setup step that unlocks things.** The companion link now means: connect/disconnect (A17),
   rename, forget, **auto-start when the headset connects** (0.8), **codec switching without Shizuku** (0.8). None of
   that is said where the user needs it (Codec screen, Home, Find). Present linking as a per-headset setup checklist
   (Link, Notifications, Tile/widget, optional Shizuku) surfaced where each benefit matters.
6. **Discoverability by design, not by changelog.** Find, Bluetooth history, link, tile, widget, Headphone type and
   Demo are each reachable from exactly one obscure place; 0.8 shipped with a stale "What's new in 0.75" sheet and
   no in-app pointer to its three new features. Add a quick-actions row, launcher shortcuts, add-tile / pin-widget
   prompts, a what's-new for 0.8, and cross-links (Find ↔ history ↔ report).
7. **One mode shortlist.** Favourites (long-press a chip) feed the notification; a separate "Quick Settings tile"
   choice in Preferences feeds the tile **and the widget**; automations store their own. Two sources of truth, one
   mislabelled. Collapse to one "Shortlist" with a preview of what each surface shows.
8. **A report loop for the Experimental tier, and a vocabulary.** Experimental drivers depend on user reports, yet
   the only path is "copy to clipboard and find GitHub yourself" (no share sheet, no tappable links). Alongside:
   fix headset / headphones / earbuds, "Any headset / Other headset / Generic / Audio device", mode / listening mode,
   link / channel / connection, and move service-layer English into resources (a 1.0 localisation blocker anyway).

---------------------------------------------------------------------------------------------------

## 2. Top 10 (the order I would do them in)

| # | Finding | Area | Impact | Effort |
|---|---|---|---|---|
| 1 | What's new still says 0.75; 0.8's features are invisible (`ui/whatsnew/WhatsNew.kt:14`, `strings.xml:627`) | onboarding | H | S |
| 2 | "Try demo mode" does nothing when Bluetooth permission is missing (`ui/EarshotApp.kt:147`) | onboarding | H | S |
| 3 | Codec screen never offers the no-Shizuku path (link); it is at the bottom of Controls (`ui/tools/CodecScreen.kt:119-134`) | Tools | H | S |
| 4 | Home says "not connected" while audio plays; permanent refusals look like failures (`ui/home/DisconnectedCard.kt`, `ConnectionHeader.kt:250`) | Home | H | M |
| 5 | Any-headset Home is a header and a banner; nothing links to the features that work (`ui/home/HomeScreen.kt:177`) | Home | H | M |
| 6 | Tier is never shown consistently; Sleep A30 (Tested) is labelled Experimental / unverified in-app | cross | H | M |
| 7 | Sound: Pro EQ is off by default so the first visit is a wall of disabled controls; per-headset memory is invisible | Sound | H | S |
| 8 | Tools is a flat list of 7; Find and Bluetooth history are buried; Controls is a dumping ground | Tools | H | M |
| 9 | Failures are 4-second snackbars with no action; no typed remedies | cross | H | M |
| 10 | Experimental-tier report loop: copy-only, no share sheet, no tappable links, Lab folder unreachable | cross | H | M |

---------------------------------------------------------------------------------------------------

## 3. Findings by area

### 3.1 Onboarding and first run

**O1. What's new is stale; 0.8 has no in-app announcement. (H / S)**
`ui/whatsnew/WhatsNew.kt:14` `RELEASE = "0.75.0"`; `strings.xml:627-635` title and bullets are the 0.75 ones. A
0.75 user updating to 0.8 sees nothing, and `WhatsNewStore.decide` thinks 0.75 is current. Find my earbuds,
Bluetooth history, codec-without-Shizuku, JBL and "linked headsets bring Earshot back" are invisible.
Fix: bump `RELEASE`, new bullets with a button each ("Try Find", "Link this headset"), deep-linking through
`NavRequest(tab, route)` which already exists. Make "bump WhatsNew" a release-checklist item.

**O2. "Try demo mode" is a no-op without Bluetooth permission. (H / S)**
`OnboardingScreen` shows the demo button under the permission card (`ui/onboarding/OnboardingScreen.kt:157`).
`onTryDemo` sets demo mode and connects (`:96-101`), and `ConnectionManager.runLoop` runs the demo before any
permission check. But the root gates on **permission only**: `gated = !hasBluetooth || NO_PERMISSION || NO_PAIRED_DEVICE`
(`ui/EarshotApp.kt:147`), and `hasBluetooth` is `Permissions.has(...)`. So with the permission refused (or not yet
granted) the demo connects invisibly and the user is still looking at the permission card. A store reviewer with no
headset, or a privacy-minded user who refuses "Nearby devices", cannot see the app. Fix: `gated` must be false while
demo mode is on (or while `connection.status == CONNECTED`); also show "Try demo" as a first-class secondary
button, not a text link at the bottom. (Verify on device with permission revoked.)

**O3. Onboarding explains the product, not the tiers or what to expect. (M / M)**
`strings.xml:87` `onboarding_body` and `:543` `about_tagline` omit JBL (0.8) and AirPods battery, never say
"Tested" / "Experimental" / "Any headset", and don't mention "close the maker's app" until a connect fails. The
picker rows only say "Recognised headphones" (`OnboardingScreen.kt:195`, driven by `looksLikeBose`, which is
really "has a driver") or "Audio device" for everything else. A Sony owner is told "Recognised" and then sees an
Experimental banner. Fix: tier chips on every picker row ("Bose · tested", "Sony · experimental", "Any headset"),
a three-line "what you get" under the title, and a one-line pre-flight ("Close the Bose / Sony / Soundcore app
first") shown for headsets whose family needs a control channel.

**O4. Permission prompts arrive back to back with no explanation. (M / S)**
`ui/EarshotApp.kt:93-125`: Bluetooth, then Nearby-devices scan, then notifications, one after another on first
resume. Only the Bluetooth card has a rationale. Notification denial gets a snackbar only if permanently denied.
Fix: a one-screen "Three things Earshot asks for" sheet (Bluetooth: control; Notifications: battery alerts and the
status notification; Microphone / Location later, only when you use them) before the first dialog.

**O5. Maker's app holds the channel: good diagnosis, no action. (M / S)**
`ConnectionProblem.ChannelHeld(appName)` drives "Close the %1$s app" (`DisconnectedCard.kt:62-117`), but there is
only text. Fix: carry the package name where known and offer "Open app info" (`ACTION_APPLICATION_DETAILS_SETTINGS`
→ Force stop), and "Try again" that auto-retries when the app closes.

**O6. No-paired-device state hides the whole tab bar. (L / S)** `gated` (EarshotApp.kt:147) removes the bottom
bar, so About / Preferences / Demo (preferences) are unreachable except through the inline demo link. Acceptable,
but pair it with a "Pair a headset" button that opens Android Bluetooth settings (it exists as
`onOpenBluetoothSettings`) and a visible "Learn what works without a headset".

### 3.2 Home

**H1. Home for an "Any headset" is a header and a banner. (H / M)**
`HomeScreen.kt:177` renders `SupportNoteCard(caps.supportNote)` and nothing else for a generic headset
(`modesOnHome`/`listeningModes` are false; vendor controls empty; no noise card). The banner text
(`GenericHeadphoneRepository.SUPPORT_NOTE`) lists "Pro EQ, the codec panel, call audio, Controls and automations
work with it" as **plain text, not links**. The user's best features are 2-3 taps away and unlisted as targets.
Fix: replace the banner with a **"What works with this headset" card** of tappable rows (Pro EQ preset chips with
current preset, Codec "AAC 44.1 kHz", Call boost on/off, Find, Link this headset, Report this headset), plus a
quick-actions row for every headset (Find, EQ preset, Codec) and the Bose-style controls when they exist.

**H2. "Not connected" while the music is playing; permanent refusals rendered as failures. (H / M)**
- `ConnectionHeader.statusLabel` (`ConnectionHeader.kt:250`) and `DisconnectedCard` title/body use
  `ConnectionStatus` only. `audioLinked` / `isAudioActive` exist (`HeadphoneModels.kt:506-512`) and drive the
  service, EQ and call boost, but the only UI that reads them is Find. A user on a Bose with the maker's app
  holding the channel is playing music and is told "Headphones not connected".
- `ExplainedConnectException(retry = false)` (JBL 2025+/Airoha, Sennheiser legacy GAIA, unknown JBL id) surfaces
  as `connection.message`, which `DisconnectedCard` prints in red **under** the generic "Make sure the headphones
  are on... then tap Connect", with a Connect button and the "If they don't connect" tips (`DisconnectedCard.kt:70-117`).
  The manager stands down, so Connect can never succeed; nothing offers the obvious exit "Use it as an Any-headset"
  (`DevicePrefs.setFamilyOverride("generic")`), and `docs/supported-headsets.md` promises "those stay generic".
- While `CONNECTING` (up to ~20 s on the first walk, `BmapChannelSearch`) the card still reads "Headphones not
  connected... tap Connect" with a spinner on the button.
Fix: three Home states with distinct cards: *Connecting* (what Earshot is trying, with a "Taking long? Close the
maker's app" nudge after 8 s), *Audio linked, controls unavailable* (reason + action: Reconnect / Open app info /
Use as Any-headset), *Not linked* (Connect via the companion link when available, else Open Bluetooth settings).
Add a typed `ConnectionProblem.ModelNotDriven(reason)` next to `ChannelHeld`.

**H3. "Connect" means two things on one screen family. (M / M)**
Home's Connect re-opens Earshot's control channel (`HomeViewModel.connect`); Universal remote's Connect asks
Android to connect the Bluetooth headset (A17). A user whose headset is simply off cannot use the one that would
help from Home, even when the headset is already linked. Fix: when the headset is linked and `canConnect`, Home's
primary button becomes "Connect <name>" (CDM), else the current retry labelled "Reconnect Earshot".

**H4. Device switcher and picker don't say what you'll get. (M / S)**
`DeviceSwitcherSheet.kt` shows Bose-like products first with no heading, then "Other headsets" (`switcher_other_audio`);
the `looksLikeBose` field is documented as "name says Bose" but is set from `DeviceFamily.forName != GENERIC`
(`bt/DeviceFinder.kt:237`), so it silently means "has a driver". Rows show only the address. Fix: rename the field,
group by tier (Tested / Experimental / Any headset), show the model line + tier chip, and word the section titles
by form factor ("Choose headphones" is hard-coded for earbuds, `strings.xml:action_choose_headphones`).

**H5. Mode shortlist is a hidden gesture. (M / M)**
Favourites come from a long-press whose only explanation is an 11 sp mono caps hint (`HomeScreen.kt:~215`,
`home_modes_hint`), and they silently drive the notification buttons (else its first three modes) while the
tile/widget use a different Preferences choice (see M3). Long-press has no `onLongClickLabel` (`ModeCarousel.kt`), so TalkBack announces a bare
"double-tap and hold". Fix: a visible star toggle on the chip (or a "Shortlist" row in the mode editor) and a
single source of truth with a preview ("Tile: Quiet → Aware, Notification: Quiet, Aware, Immersion").

**H6. Jargon chip and tiny info text. (L / S)**
"Live updates / Headphones report changes instantly" (`chip_live_updates`) is internal protocol vocabulary. Status
and hint lines are `labelSmall` 11 sp uppercase mono (`ConnectionHeader.MetaLine`, `home_modes_hint`) yet carry
information (time left, charging, the long-press tip). Use `bodySmall` for sentences; keep mono caps for tags.

**H7. Find has no entry on Home. (M / S)** See T5. Add it to the quick-actions row; it is the one feature people
need when they are *not* in the app flow.

### 3.3 Sound

**S1. First visit is a wall of disabled controls. (H / S)**
`TuningSettings.enabled` defaults to `false` (`dsp/.../EqModel.kt:55`). `ProEqSection` disables presets, curve,
editors, preamp, import and save until the switch at the top is flipped (`SoundScreen.kt:224`), and Boost is a
second card that only says "Turn on Pro EQ to use volume boost" (`:427`). Picking a preset should be the on switch.
Fix: tapping a preset chip enables Pro EQ; collapse Graphic/Parametric/preamp/import behind "Customise"; fold Boost
into the Pro EQ card as a sub-section so the "why is this grey" text disappears.

**S2. No headset context; per-headset memory is silent. (M / S-M)**
`audio/HeadsetEqMemory.kt` re-applies the last explicit pick per headset on connect (a good 0.75 feature), but the
Sound tab has no header, no "Applies to <name>", no "Remembered for this headset", and nothing when no headset is
audio-active. A user who changes preset on AirPods and later finds the Bose "remembers" a different one cannot see
why. Fix: a one-line context row ("Pro EQ for Bose QC Ultra · remembered for this headset", with a "Use for all
headsets" toggle) and the same row in the Controls "Cycle EQ preset" and automation EQ pickers.

**S3. Two EQs, and the headset's own EQ may be somewhere else. (M / M)**
The Bose 3-band shows as "Headphone EQ" on Sound only when `state.eq != null`; Sony's EQ preset and bands are
`VendorControl`s drawn on **More** (`SonyStateMapping.kt:~330-351`), while Sennheiser fills `eq`
(`SennheiserStateMapping.kt:252`, so it should land on Sound - verify on the virtual device), so for one brand "the
headset EQ" is on Sound and for another it is a slider group in More. Fix: add `group`/`kind` to `VendorControl` and route `kind = EQ` into a
"Headset EQ" section on Sound, with a one-line explainer of Headset EQ vs Pro EQ and which wins.

**S4. AutoEq import is a paste box. (M / S-M)**
`ImportAutoEqDialog` asks the user to paste the text of a file they must have found and opened elsewhere. Add
`ACTION_OPEN_DOCUMENT` for `ParametricEQ.txt`, a "Paste from clipboard" button, and accept share-to-Earshot of a
text file. (The roadmap notes the AutoEq name-match was not built; with no INTERNET permission, picker + clipboard
is the practical route.)

**S5. Preset chips are not accessible and delete is long-press only. (M / S)**
`PresetChips` (`SoundScreen.kt:~300`) is a `Surface + combinedClickable` with **no role or `selected` semantics**
(compare `ModeChip`), so the active preset is conveyed by colour only, and "Hold a saved preset to delete it" has no
`onLongClickLabel`. Fix: `semantics { role = Role.RadioButton; selected = ... }`, long-click label, plus a visible
overflow / "Manage presets" for delete and rename.

**S6. Polish. (L / S)** `sound_pro_eq_attached` "audio session(s)" → plural resource and plain words ("Active in 2
apps"); "Phone-side equaliser for apps that share their audio session" is jargon; Headphone EQ / Pro EQ are
disabled when disconnected with no explanation (More has a "Connect the headphones" banner, Sound doesn't).

### 3.4 Tools

**T1. Flat, ungrouped list; the 0.8 features sit at the bottom. (H / M)**
`ToolsScreen.kt:101-148`: Controls, Connection & latency, Codec, Call audio, Mic check, Find, Lab - seven 88 dp
cards, Find and Lab below the fold on a typical phone. "Connection & latency" repeats rows for Codec and Call
audio (`ConnectionScreen.kt:~135`) and shows the Bose-only LE Audio card to every brand. **Bluetooth history is two
levels down inside "Lab - Protocol explorer (scanner reads only)"** (`LabScreen.kt:66,112`, `tools_lab_subtitle`) -
a support feature people need when something is wrong, hidden under the developer label.
Fix: group with section headings:
*This headset* (Buttons, Remote & rename), *Audio* (Codec, Call audio, Mic check, Latency), *Find & history* (Find,
Bluetooth history), *Diagnostics* (Lab, Copy report). Keep "Tools never hide" by keeping every row visible.

**T2. Controls is a dumping ground. (H / M)**
`ShortcutsRemapperScreen.kt` stacks: headset header; Shortcut Button 1 + Detect; extra buttons; **Headphone
controls** (touch controls, voice prompts + language, Devices, **Rename**, **Power off**) at `:288`; then
**Universal remote** (link, connect, disconnect, **Rename "Name on this phone"**, Forget, Unlink) at `:297`. Two
renames, two "power" ideas, a button remapper that shares a page with a destructive "Forget headset", and for an
Any-headset the Headphone controls card still shows switches (touch controls shows `?: true` for an unreported
value, `HeadphoneControlsSection.kt:~60`) that can only answer "Earshot has no driver...". Fix: split into
*Buttons* (shortcut + detect), *Headset settings* (touch, prompts, devices, rename, power - hidden/explained by
driver), and the **Headset page**'s remote section (theme 1). Merge the two renames: one "Name" row that edits the
headset's own name when the driver can and the phone alias otherwise, saying which.

**T3. The no-Shizuku codec path is undiscoverable. (H / S)**
The 0.8 headline for Codec is "works without Shizuku when the headset is linked". The Codec screen's banners say the
opposite until the link already exists: `codec_priv_unavailable` "Switching codecs needs Shizuku..." and
`CODEC_SWITCH_UNSUPPORTED_MESSAGE` "Start Shizuku and grant Earshot..." (`CodecScreen.kt:119-134`, `core/CodecRepository.kt:63`).
Only after linking does `codec_via_companion_link` appear, and it points back at "Tools → Controls → Universal
remote" in plain text. Fix: add a **"Link this headset (no Shizuku)"** action in the banner that launches the CDM
dialog directly (`CompanionRemote.link`), and show the codec row's state as "Switchable: link needed / Shizuku".
Call audio's codec readout (`call_codec_unknown_hint`) still says Shizuku only; align.

**T4. Universal remote doesn't say what linking gives. (M / S)**
`remote_link_subtitle` = "One Android dialog. It stays paired as it is." Missing: linking also makes Earshot start
by itself when the headset connects (0.8), allows codec switching without Shizuku, and enables Connect / Disconnect
(A17) / Rename / Forget. Rows the OS can't do stay tappable and explain on tap (fine, matches "never hide") but the
headline benefits should sit in the card. `remote_no_headset` is a dead end ("Connect a headset, or pick one on
Home") with no button to Home's switcher. "Unlink" sits right above "Forget headset" with similar weight; make
Forget visually destructive and separate.

**T5. Find: enabled when it can't work, and a paradox for the main use. (M / S)**
`FindScreen.kt:136-151`: the Left / Both / Right / Play buttons have no `enabled`; with no headset the screen says
"Connect a headset first: Earshot can only ring one the phone is connected to", i.e. exactly the state when your
earbud is lost. The useful information in that state (when it was last connected) is a sentence; there is no link
to that headset's Bluetooth history, which is where the "last seen" comes from. Fix: disable the ring buttons with
the reason; when disconnected show last-seen + "Open Bluetooth history for this headset" + a short "Looking for a
lost bud" checklist (walk, listen, check the case, last-known area is not recorded - say so). Entry points: Home
quick action, a launcher shortcut, the notification (see W3).

**T6. Bluetooth history is a log without filter or care. (M / S)**
`TimelineScreen.kt`: all headsets mixed, no per-headset filter (Find / Controls could open it pre-filtered);
`IconButton(onClick = onClear)` clears 500 events with **no confirmation** (`:77`); the time format is a
hard-coded `HH:mm:ss` (`:72`) so a 12-hour locale reads wrong; unknown names show raw MACs. Fix: filter chips per
headset, `ConfirmDialog`, `DateFormat.getTimeInstance`, and an "Explain this event" subtitle for the problem rows it
already colours (`KEY_MISSING`, `PROFILE_DOWN`).

**T7. Call audio and Mic check. (L / S)** `CallAudioScreen.kt:115` prints `lastError` as bare red text while Codec
uses `InfoBanner(ERROR)`; Mic check, when permission is permanently denied, shows text only
(`MicCheckScreen.kt:166` `mic_permission_hint`) whereas the Controls screen silently *opens* app settings after a
denial (`ShortcutsRemapperScreen.kt:123`) and the notification flow offers an action button. See X8.

**T8. Lab as the front door for support. (M / S)** Lab opens with "The scanner only reads..." (a developer
disclaimer) before "Connection report"; the scan result folder `Android/data/dev.earshot/files/lab`
(`lab_deep_scan_hint`) isn't visible in file managers on Android 11+. Move "Copy / Share connection report" to the
top of a renamed screen ("Diagnostics"), keep the scanner under "Advanced", and add a Share action (X7).

### 3.5 More and App preferences

**M1. More is a grab-bag; the same headset settings appear three times. (M / M)**
Home "Headphone controls" (quick vendor controls), More "Headphones" (all vendor controls again, including the
`onHome` ones - `MoreScreen.kt:146`), Tools → Controls "Headphone controls": three cards, near-identical titles
(`home_vendor_controls`, `settings_headphones`, `tools_headphone_controls`), different contents. `VendorControlsCard`
is one flat card: Sony alone produces ~15-25 rows (EQ bands, speak-to-chat, DSEE, general settings) in one list with
no groups or search. Fix: `VendorControl.group` (Noise, Sound, Calls, Buttons, Power, Other) rendered as sub-sections
(collapsed beyond the first), vendor EQ → Sound (S3), and one canonical name ("Headset settings").

**M2. "Headphone type" is the fix for wrong detection and unsupported models, but it looks app-wide. (H / S-M)**
`PreferencesScreen.kt:200-222`: eight chips in the App preferences screen, labelled inconsistently ("Soundcore
(Sleep A30)", "Sennheiser (Experimental)", "Skullcandy Crusher" - covers Method too, "Other headset"). The setting
is stored for **one** device only (`DevicePrefs.KEY_FAMILY_ADDRESS`, `bt/DevicePrefs.kt:115-140`), the screen doesn't
say which headset, and a second headset shows "Automatic". Tapping a chip disconnects and reconnects immediately
(`:46-56`) with no confirmation or message. Fix: move it to the Headset page ("Treat this headset as ..."), per
device, tier-labelled, with "Reconnecting..." feedback; link to it from every refusal (H2) as a button.

**M3. Tile card also controls the widget; the notification has a third source. (M / M)**
`prefs_tile` ("Quick Settings tile") is read by `EarshotWidget` (`extras/widget/EarshotWidget.kt:65`
`TileCycle.order`), but the card never says so; the persistent notification uses **favourites, else the headset's first three modes**
(`ServiceNotification.modeButtons`), never the tile choice. Rename to "Quick switch (tile, widget)", say what the notification uses, and unify
with H5. The card is also empty for any headset without modes (no chips, just "None selected...").

**M4. Smaller preferences issues. (L-M / S)** "Forget secure pairing" and the Demo switch act immediately (disconnect
+ connect) without a confirmation or "Reconnecting..." message (`PreferencesScreen.kt:44-48,224`); "Alerts and
connection" mixes alerts, the persistent notification and auto-connect and says nothing about the 0.8 presence
wake-up for linked headsets; "Appearance" is hidden below Android 12 (fine) but Field vs System colours has no
preview.

**M5. Automations. (M / M)** Reachable only via More → App → Automations. `AutomationEditorScreen.kt:230`
launches the location permission dialog the moment the Wi-Fi trigger is selected (before the rationale below it);
rules do not say which headset(s) they apply to (they resolve by meaning on whichever is connected); for an
Any-headset the editor offers "Set mode" with generic meanings (`ModeResolver.GENERIC_CHOICES`) that can never run
and "Set noise settings" (Bose-style ANC/Wind/Immersive) with no explanation; "Recent activity" is raw log lines.
Fix: rationale-first permission step, an "Applies to: any headset / <name>" row, actions filtered or explained by
driver, and friendlier log lines with a tap to the rule.

**M6. About does not help the reader act. (M / S)** Credits are plain `Text` URLs (`AboutScreen.kt:CreditLine`,
no `ACTION_VIEW` anywhere in the app apart from the voice memo), there is no "Report a problem", no tier legend or
supported list, no "Share connection report". Opening a link needs no INTERNET permission, so privacy is unaffected.

**M7. Navigation highlight. (L / S)** `Routes.DEVICES = "more/devices"` is opened from Tools → Controls; the bottom bar
highlights **More** while the user is inside the Tools back stack (`Tab.forRoute` matches by prefix). Rename the
route to `tools/devices`. Re-tapping the current tab appears to restore the sub-screen rather than pop to the tab
root (`navigateToTab` with `restoreState = true`) - verify on device.

### 3.6 Tile, widget, notification, launcher

**W1. None of them can be added from the app, and there are no launcher shortcuts. (M / S-M)**
No `requestAddTileService` / `requestPinAppWidget` / shortcuts anywhere in the code; the widget XML has no
`previewImage` / `previewLayout` (`res/xml/earshot_widget_info.xml`), so the picker shows a generic card. Add "Add
the tile" and "Add the widget" buttons (A13+/A8+) to the setup checklist and quick-settings card, a widget preview,
and static/dynamic launcher shortcuts (Find, Connect, Switch mode) - the cheapest way to make Find reachable.

**W2. The notification's Find button is missing for the main device. (M / S)**
`ServiceNotification.kt:139`: Find is added only `if (actions < MAX_ACTIONS)`. On a QC Ultra with three named modes,
two mode buttons + the ANC toggle already fill all three slots, so the user the feature was built on never sees it.
Fix: when full, drop the ANC action for Find on earbuds, or make Find a persistent second notification action on the
HeadsetPresenceService notification, or give it the launcher shortcut (W1).

**W3. Hard-coded English on every surface; wording differs per surface. (M / S)**
Tile: "Connect", "Connecting…", "Open Earshot", "Earbuds", "Headphones", ". Tap to switch mode"
(`ModeTileService.kt:109-114,118`); widget: "Connect", "Connecting…", "EQ flat", "Disconnected",
"Bluetooth permission needed", "No headphones paired" (`EarshotWidget.kt:97,132,151,216-218`); notification: channel
"Connection", "Disconnected", "ANC off/on", "Find" (`ServiceNotification.kt:86,104,134,139`). The same state is
"Not connected" on Home, "Connect" on the tile, "Disconnected" on widget and notification. `tile_label` is "Headphone
mode" even for earbuds. Move to `strings.xml` and use the shared status language (theme 2).

**W4. Failures on these surfaces are invisible. (M / S)**
Tile tap and widget buttons log failures and do nothing visible (`ModeTileService.kt:75`, `WidgetActions.kt:29`).
Fix: flash the tile into `STATE_UNAVAILABLE` with the reason as the subtitle for a few seconds; show a one-line
error in the widget; a notification message for service actions.

**W5. Widget details. (L / S)** "EQ <preset>" reads `tuning.state.activePreset` and may be shown while Pro EQ is off
(verify); the regular layout has no action for headsets without modes except Open; the compact layout's Connect
button always uses the control-channel path (see H3).

### 3.7 Cross-cutting

**X1. Single status language. (H / M)** See theme 2 and H2/H3/W3. Introduce one `LinkSummary` (audio, controls, remote)
with one set of strings used by `ConnectionHeader`, `DisconnectedCard`, tile, widget, notification, Find, Controls.

**X2. Tier badges and "what works". (H / M)** See theme 3 and O3/H1/H4. Concrete inconsistencies:
- Sleep A30 is **Tested** in README / `supported-headsets.md`, but `SoundcoreStateMapping.kt:100` sets
  `verified = false` and `SUPPORT_NOTE` = "Experimental: the first Soundcore driver, built from protocol notes"
  (`:25`), so Home shows an Experimental banner and More's DeviceInfoCard says "Earshot hasn't been tested on this
  model yet" (`DeviceInfoCard.kt:47`).
- Experimental Bose models (`verified = false`) get no Home note at all; Sony / Sennheiser / JBL / Soundcore /
  Skullcandy get persistent, undismissable developer-voice banners ("built from protocol notes and other people's
  captures and not yet tried on our own headphones").
- "Any headset" is also the title of the *no headset connected* Controls state (`shortcut_any_headset`), "Other
  headset" in Headphone type, "Other headsets" in the switcher, "Audio device" in onboarding, "Generic" in code/report.
Fix: one `SupportTier` enum on `HeadphoneCapabilities` (TESTED, EXPERIMENTAL, BATTERY_ONLY, ANY), one badge, one
user-voice sentence per tier ("Tested on real hardware" / "Built from protocol research - please tell us how it
works"), dismissible but re-findable.

**X3. Errors and refusals. (H / M)**
- `UiMessenger.report` shows raw exception text or "Something went wrong"; `FirmwareUnsupported` is one fixed Bose-era
  sentence ("The headphones' firmware doesn't support changing this", `strings.xml:error_firmware_unsupported`) for
  every brand and form factor, with no mention of *which* setting.
- Snackbars use the default duration (4 s) and are collected sequentially (`ui/EarshotApp.kt:130-136`): a 190-character
  refusal (`GenericHeadphoneRepository.UNSUPPORTED`) disappears before it can be read, and two quick failures queue.
- 172 `HeadphoneCommandException("...")` call sites carry English text, some developer-voice: "Not implemented yet",
  "Not supported by this protocol", "Noise settings not loaded yet", "Unexpected reply from the headphones
  (AA:BB…): detail", "Couldn't read the current audio settings".
- Remedies appear as prose paths ("choose that brand in More → App preferences → Headphone type") instead of buttons.
Fix: `HeadphoneCommandException(message, remedy: Remedy?)` where `Remedy` is a small sealed type (OpenHeadphoneType,
LinkHeadset, OpenAppInfo(pkg), OpenBluetoothSettings, GrantPermission(p), CopyReport); messenger renders an action
button, uses `Long` for messages with a remedy, and groups a burst into one message. Keep the "Tools never hide
settings" rule: rows stay visible, but a row that can never work for this driver (known from `capabilities`) opens
the explanation instead of sending a command that is certain to fail.

**X4. Vocabulary glossary. (M / S then M)** Counts in `strings.xml`: "headphones" 108, "headset" 73, "earbuds" 32.
0.75+ strings say headset, older Bose strings say headphones, only some have `_buds` variants. Rules to adopt:
*headset* = any device in general text; *headphones / earbuds* by form factor in headset-specific text; *mode* in
UI, *listening mode* only on first mention (`modes_title`, `tile_label`, `home_modes_title` differ today); *connect*
= Bluetooth link between headset and phone, *reconnect* = Earshot's control link, *link* = companion link only;
*Controls* means one thing (buttons). Use one breadcrumb glyph (`→` in most strings, `›` in `info_unverified`).
Tone: friendly second person ("Can't hear it? Raise the media volume") vs protocol voice ("AT+NREC", "HFP 0-15",
"absolute volume off") - keep the latter inside "Advanced" blocks.

**X5. Hard-coded text in the service layer. (M / L; 1.0 blocker for localisation)**
Support notes, `VendorControl` titles/subtitles/options (e.g. "Speak-to-chat", "Anti-wind"), error messages, tile,
widget and notification text live in Kotlin. Introduce a thin `UserText` (resource id + args, resolved in the UI /
notification layer) and migrate in this order: tile/widget/notification (S), support notes and tier (S), vendor
controls (M), exception messages (L, opportunistically with X3). Also delete dead strings (`disconnected_body_skullcandy`).

**X6. Per-headset vs global settings are not signposted. (M / M)**
Per headset today: Pro EQ preset (silent), shortcut choice (Controls header shows it), Headphone type (single slot),
detected button names, tile choice (by meaning, per headset), alias (CDM). Global: Pro EQ on/off, boost, automations,
alerts. The UI never separates them. A Headset page (theme 1) with a "Remembered for this headset" section fixes it.

**X7. Report loop. (H / M)** `HomeScreen.kt:70-78`, `LabScreen.kt:92-93`: reports go to the clipboard only; there is no
share sheet (roadmap rule 4 says "only through the share sheet" but nothing implements `ACTION_SEND`), no
`FileProvider`, no tappable links. For an Experimental tier whose entire promise is "reports are welcome", add:
*Report this headset* on the Headset page (model, tier, driver version, what worked, last 40 events, optional
free text) → share sheet / open the issue URL in the browser; a one-tap "Did this work? Yes / No" after the first
successful write on an Experimental driver (stored locally, included in the report).

**X8. Permission handling uses five patterns. (M / S-M)**
Onboarding: card + "Open app settings" button; notifications: snackbar with action; Mic check: text only
(`MicCheckScreen.kt:166`); Controls: opens app settings automatically after a permanent denial
(`ShortcutsRemapperScreen.kt:123`); Wi-Fi automation: dialog fired on entering the trigger (`AutomationEditorScreen.kt:230`).
One `PermissionGate` composable (why, button, "Open settings" when permanently denied) used by all.

**X9. "Tools never hide settings" vs "More hides null rows". (M / M)**
The code comment on `HeadphoneControlsSection` states "Tools never hide a setting" while its KDoc says rows are
hidden once loaded; More hides rows whose value is null after the 2.5 s settle (`MoreScreen.kt:78`), so identical
settings are hidden on one tab and tryable on the next. This is the user's decision to keep visibility; the fix is
consistency: every settings row either shows its real state or an "Unavailable on this headset - why" sheet. For
Any-headset this removes a screen of switches that can only fail.

**X10. Accessibility and polish basics. (M / S)**
- Good already: no hard-coded colours outside `ui/theme`; 48 dp targets on icon buttons, segmented rows, switches
  and sliders; `Role.Switch` rows; content descriptions on every `IconButton`; semantic labels on the battery dial.
- Gaps: preset chips lack `selected`/role (S5); long-press-only gestures lack `onLongClickLabel` (mode chips, preset
  delete); `NavRow` / `ToolCard` / `AssistantRow` use bare `clickable` without `Role.Button`; information carried in 11 sp
  uppercase mono (H6); status colours as the only difference between selected / unselected presets; no
  contrast check recorded for `InkMuted` on `surfaceContainer` (run Accessibility Scanner, light and dark);
  `Timeline` fixed 24 h time; `sound_pro_eq_attached` "(s)"; `Text(AutoEq url)` is not a link.
- Not verified: TalkBack order on `ConnectionHeader` (a clickable card containing a refresh button), dynamic font scale
  on the 80 dp tab bar and 56 dp mode chips, landscape and foldable layouts (the user's test device is a Fold7).

---------------------------------------------------------------------------------------------------

## 4. Suggested sequencing for 0.9

**Quick wins (1-2 days total, high return):** O1 what's new; O2 demo gating; T3 link action in the Codec banner; S1
preset-enables-Pro-EQ; W3 tile/widget/notification strings into resources; X2 Sleep A30 tier fix and tier wording; T6
confirm-dialog and locale time; S5 chip semantics and long-click labels; M7 route rename; W2 Find in the notification.

**Core 0.9 work (the "tie it together"):** a shared `LinkSummary` + status language (X1, H2, H3); `SupportTier` and
the "What works" card (X2, H1, H4, O3); typed remedies (X3, O5, H2); Headset page (theme 1; absorbs T2, T4, M2, X6,
Find, history, report); Tools regrouping (T1); shortlist unification (H5, M3).

**Needs a product decision:**
1. Whether the Headset page replaces Controls or sits beside it (recommendation: replace; keep Controls = buttons only).
2. How to honour "Tools never hide settings" for rows that cannot work: recommendation is "visible, tappable, opens an
   explanation" instead of firing a command (X3, X9).
3. Tier names in user language ("Tested", "Experimental", "Battery only", "Any headset") - confirm and reuse them in
   the README, store listing and app.
4. Whether the 0.9 "special" ideas (the roadmap mentions making Earshot feel special) should include the setup
   checklist and the "Did this work?" loop; both are low-cost and fit the Experimental tier.

## 5. What is already good (do not regress)

- Typed `ConnectionProblem.ChannelHeld` and the Disconnected card's tips and Copy report.
- `rememberSettingsLoaded` (rows don't jump during connect), `SettingsLoad` latch.
- Field theme: no raw colour literals outside `ui/theme`, previews for light and dark, consistent `SectionCard` /
  `SwitchRow` / `NavRow` / `SubScreenScaffold` building blocks.
- Find screen keeps the display on while ringing; Controls highlights the section a hint refers to; the automation
  mode picker is by meaning with a visible fallback for modes a headset lacks.
- Permission-denied onboarding with "Open app settings".
