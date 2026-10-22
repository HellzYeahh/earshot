# momentum4-control

Source: https://github.com/Ariazonaa/momentum4-control (MIT, licence in `LICENSE` next to this file).

A Windows / Linux controller for the Sennheiser MOMENTUM 4 (and MOMENTUM 5) over RFCOMM and
Qualcomm GAIA v3, with hardware captures from a Momentum 4 (firmware 3.37.3 and 3.38.3) and a
Momentum 5 (firmware 6.21.5) and a command catalogue that classes every command as Verified,
NeedsHardwareTest or Blocked.

What Earshot uses from it (no code copied; Earshot's GAIA codec and Sennheiser driver are its own):

- Frame bytes from its captures (`tests/CapturedPackets/`) as golden frames in
  `protocol/src/test/kotlin/dev/earshot/protocol/gaia/` and `…/sennheiser/`: API version,
  supported-features lists, model id, firmware, battery, registration dump, ANC writes and their
  side effects, auto-off, EQ bands and bass boost, sidetone, behaviour setters.
- The behaviour `VirtualSennheiser` reproduces: ANC on switches transparency off and the level to
  0, transparency on sets the level to 100 and off restores it, mode-table writes switch
  transparency off, auto-off writes push nothing, registration dumps state.
- Its Blocked list as a cross-check for `SennheiserWriteGuard`.
