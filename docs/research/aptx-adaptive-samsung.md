# aptX Adaptive on a Samsung phone: can Earshot turn it on?

Researched 2026-10-26 on the user's Galaxy Z Fold7 (SM-F966U1, One UI 9, Android 17) with the
Bose QC Ultra Headphones 2nd Gen (firmware 10.12.12). The evidence is read-only: `dumpsys
bluetooth_manager`, `getprop`, and the Bluetooth libraries pulled from the phone and read for
strings. Nothing was written to the phone or the headphones.

**Short answer: no.** It can't be done with Shizuku, through the companion-device link, or with
root. The phone's Bluetooth software has no aptX Adaptive encoder, and a codec request can only
pick a codec the stack already offers.

## Why not

| Layer | What it has | Why it blocks |
| --- | --- | --- |
| Samsung's Bluetooth stack (`com.android.bt` APEX, `libbluetooth_jni.so`) | A2DP source codecs registered: SBC, AAC, aptX (classic, 44.1 kHz/16-bit), LDAC, Samsung Scalable (SSC). aptX HD and Opus are compiled in at priority −1 (off). Codec index 7 (priority 5002) has no endpoint; it is probably LHDC V5 for China models. | There is no `a2dp_vendor_aptx_adaptive` module. The aptX Adaptive strings in the binary are only the audio HAL's type names. |
| Codec extensibility (AOSP `a2dp_ext`) | It would let the audio HAL offer extra codecs. | It needs `a2dp_offload_v2`. The controller reports `a2dp_offload_v2_support = 0` (version 0x0103), and the offload mask (31) is SBC/AAC/aptX/aptX HD/LDAC. |
| Qualcomm vendor partition | `/vendor/etc/audio/sku_sun/resourcemanager.xml` lists `CODEC_TYPE_APTX_AD` (`lib_bt_aptx.so`). Properties: `persist.vendor.qcom.bluetooth.aptxadaptiver2_1_support=false`, `lossless_aptx_adaptive_le.enabled=false`. | The hardware side is present but unused. The shell user (Shizuku, uid 2000) can't set or even read these properties, and the stack above wouldn't use them anyway. |
| `setCodecConfigPreference` (Earshot's codec panel, Shizuku or the companion link) | It picks among the codecs both sides already list as selectable. | With the Bose those are AAC and SBC only. |
| Root | — | The US Fold7's bootloader can't be unlocked (One UI 8 removed OEM unlock; Knox e-fuse). |
| Community encoders (ExtA2DP, AKS-Logic) | — | ExtA2DP skips Samsung and stops at Android 14. The AKS-Logic encoders are rebuilt from Qualcomm binaries and carry patent caveats, so Earshot can't ship them. |

On the headphone side, the Bose currently streams **AAC 44.1 kHz/16-bit at about 192 kbps**.
`dumpsys` reports 3 A2DP sinks (endpoints) received and 2 supported. The third sink is very
probably aptX Adaptive, which the Bose and aptx.com list for these headphones with Snapdragon
Sound. The Bose offers no classic aptX, aptX HD or LDAC, so Earshot can't even switch to the plain
aptX that Samsung does have.

This matches Samsung's long-standing position: Galaxy phones ship classic aptX only, never aptX
HD, Adaptive or Lossless, and Samsung pushes its own SSC codec instead. MakeUseOf's
"Samsung left out Qualcomm's best Bluetooth codec" (Brady Snyder, 2026-08-28) reaches the same
conclusion: the hardware supports it, but the software doesn't. It also suggests a USB-C
transmitter as the way around this.

## What does work, best first

1. **Wired USB-C digital audio.** The QC Ultra 2 plays over USB-C at 16-bit 44.1/48 kHz; one
   source says 24-bit, which is unresolved. This is the best quality available from this phone.
2. **LE Audio (LC3).** Firmware 10.12.12 has it as a beta, and Earshot already has the switch
   (`[1.37]`, Tools → Connection → LE Audio card). Switching it restarts the headphones, and Spotify
   Tap and the assistant shortcut stop working. The phone's per-device policy for the Bose currently
   says `le_audio: FORBIDDEN`. That is probably the "LE audio" switch on the headphones' page in
   Android's Bluetooth settings, which only the user can change. The Bose LC3 sink allows 75–190
   bytes per 10 ms frame (up to about 152 kbps per channel). How it compares with AAC by ear is
   unmeasured.
3. **A USB-C Snapdragon Sound dongle** (e.g. Creative BT-W6 or FiiO Air Link) streams aptX
   Adaptive (and Lossless) to the headphones from any phone. BT-W5 and Avantree C81 lack Lossless.
   With multipoint, the phone could stay connected for Earshot's controls while the dongle carries
   the audio. That combination is **untested**.
4. **A Snapdragon Sound phone** (most non-Samsung Snapdragon flagships).

## What Earshot could add (proposed, not built)

- A pure-Kotlin classifier in `codec/` that explains aptX Adaptive for the current connection:
  the phone lacks it / the headphones don't offer it / selectable / unknown. Pin it with the
  existing One UI dumpsys capture test.
- A card on the Codec screen after "What both support": "This phone's Bluetooth software doesn't
  offer aptX Adaptive", plus a "Try LE Audio" link to the LE Audio card and the USB-C/dongle notes.
  Use plain nominative wording ("aptX" and "Snapdragon Sound" are Qualcomm marks) with no
  "certified" claims.
- Small gaps found on the way: `CodecNames` has no numeric id for aptX Adaptive and no "aptX
  Lossless" label, unknown codecs collapse to "Unknown", and the Codec screen says "Not streaming"
  during LE Audio.

## Still unverified

- **What the third A2DP sink is.** Confirming it needs an HCI snoop log, which changes the phone's
  settings, so it is the user's call.
- **Whether LC3 actually streams** from the Fold7 to the Bose once LE Audio is allowed, and which
  configuration Samsung picks.
- **aptX Adaptive over LE Audio:** `getprop bluetooth.core.le_audio.codec_extension_aidl.enabled`
  and the Bose's LE vendor PAC (company 0x000A) are both unchecked.
- **Bit depth over USB-C** (`dumpsys media.audio_flinger` / `dumpsys usb` with the cable in).
- **Why LE Audio is FORBIDDEN.** Possible causes: Samsung's per-device switch, an allow-list (AOSP's
  "Bypass LE Audio allow list"), or Earshot's own `[1.37]` state. BMAP `[4.19]` LeAudioCheck START
  has never been sent; check it against `bt/WriteGuard` before trying.
