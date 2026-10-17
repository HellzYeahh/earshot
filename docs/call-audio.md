# Call audio

Two complaints this feature targets: calls through the headphones are too quiet, and your voice
doesn't sound great to the person on the other end. Earshot can fix the first one. For the second
it can only show you what is going on and point you at what helps, because Android doesn't let
apps touch the call microphone signal.

## What it does

**Call volume boost.** When a call (phone, WhatsApp, Meet, Teams, Signal...) starts and its audio
is on the headphones, Earshot raises the phone's call volume to your target (100 % of the maximum
by default). It does this twice: about 1 s after the call starts, once the headset audio link is
up, and again at 3 s, because One UI puts back its own saved call volume shortly after a call
connects. It only ever **raises** the volume. If the call is already louder than your target, it
leaves it alone, and if you turn it down later in the call (after those first 3 s) it doesn't push
it back up. "Boost now"
sets the volume to exactly your target whenever you press it during a call. If you turn on "also
raise the headphones", the headphones' own volume is raised too (once that control is available
for the QC Ultra 2).

Android keeps a separate call volume for each output (earpiece, speaker, each Bluetooth headset),
so boosting a call on the headphones doesn't change how loud the earpiece is.

**Call codec readout.** While a call is on the headphones, Earshot shows which voice codec the
Bluetooth link is using (needs Shizuku, the same setup as the codec panel):

| Codec | Audio bandwidth | What it sounds like |
|---|---|---|
| CVSD | 8 kHz sampling (about 3.5 kHz of audio) | Old-style narrowband phone. Muffled, "s" and "f" sounds blur together. |
| mSBC ("HD Voice", wideband) | 16 kHz (about 7 kHz of audio) | Clearly better. This is what you want on classic Bluetooth. |
| LC3 (super-wideband) / aptX SWB | 32 kHz | Better still. Needs both phone and headset to support HFP 1.9 / Qualcomm voice. |
| LC3 (LE Audio) | usually 16 to 32 kHz | LE Audio calls. Better microphone quality, and the headset doesn't have to drop to a separate "call mode" link. Only if both phone and headphones have LE Audio turned on. |

This is the Bluetooth link between the phone and the headphones in **both directions**, so it is
also how good your microphone sounds by the time it reaches the phone. If you see **CVSD**, the
headset link is the bottleneck. If you see mSBC or LC3 and the other person still says you sound
bad, the problem is further along: the mobile network or the calling app.

**Absolute volume switch (advanced, needs Shizuku).** Normally Android links the phone volume and
the headphones' volume into one slider ("absolute volume"). Turning that link off gives each side
its own volume, and on some Samsung phones that leaves more headroom so calls can get louder.
Treat it as an experiment:

- **It only takes effect after the headphones reconnect.** Turn them off and on, or toggle
  Bluetooth. Earshot shows "reconnect to apply" until that happens.
- It mostly changes how *media* volume behaves. Once it's off you may need to turn up both the
  phone and the headphones for music, and the volume steps can feel different.
- The in-call effect isn't guaranteed. Call volume is synced over a separate channel (HFP), and
  whether this helps depends on the phone.
- **Reverting:** flip it back in Earshot and reconnect the headphones. Without Earshot: Settings →
  Developer options → "Disable absolute volume" (switch it off), or from a computer:
  `adb shell settings put global bluetooth_disable_absolute_volume 0`. Then reconnect.

## What it does not do

- **It can't process your voice on calls.** On Android the call microphone signal goes from the
  headset (or phone mic) to the modem or the calling app without ever passing through other
  apps. No app can EQ it, de-noise it or make it louder. Apps that claim to do this either only
  work in their own recordings or need a rooted phone.
- **The headphones have no mic EQ.** The QC Ultra 2 firmware does its own beamforming and noise
  reduction on the microphones. There is no setting for it over the protocol Earshot uses.
- **It can't go past Android's maximum call volume.** 100 % is the stream maximum. If calls are
  still too quiet at 100 %, try the absolute-volume switch, and check the volume on the other
  person's end too.

## Things that do help your voice

1. **Get a wideband codec.** If Earshot shows CVSD, disconnect and reconnect the headphones and
   make sure no other phone or laptop is holding the headset's call link (multipoint). Some
   phones fall back to CVSD after a link error and only renegotiate on reconnect.
2. **Use HD Voice / VoLTE / Wi-Fi Calling** for phone calls (carrier settings, under Connections →
   Mobile networks / Wi-Fi Calling). The network codec (AMR-WB, EVS) usually limits phone calls
   more than Bluetooth does. Both phones need it, and so does the carrier.
3. **For apps** (WhatsApp, Meet, Teams), a good data connection matters more than anything on the
   phone side.
4. **Sidetone** (Earshot → headphone settings) lets you hear your own voice in the headphones, so
   you don't end up shouting or mumbling with noise cancelling on.
5. **Wind.** Outdoors, use a mode with Wind Block on. Wind noise is the most common reason the
   other side can't hear you.

## Privacy

Everything stays on the phone. Earshot works out that a call is happening from Android's audio
mode, not from your call log or phone state (there is no phone-state permission), and it reads
the codec from the phone's own Bluetooth status through Shizuku. It never sees who you call or
what is said.

## For troubleshooting

Logcat tag `Earshot/CallAudio` logs call start and end, the route, each boost (before → after
volume) and the codec found. If the codec says "unknown" during a call even though Shizuku is
running, the log lists the lines it looked at. Send those (or
`adb shell dumpsys bluetooth_manager > call.txt` taken **during** a call) so the parser can be
matched to One UI's exact format.
