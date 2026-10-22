# momentumctl

Source: https://codeberg.org/galen/momentumctl (mirror: https://github.com/gjabell/momentumctl),
MIT, licence in `LICENSE` next to this file.

A Rust command-line controller for the Sennheiser MOMENTUM 4 over RFCOMM, opened by the GAIA v3
service UUID `a2129ff3-081b-4c45-8afe-469d9c4842ec`.

What Earshot uses from it (no code copied): a cross-check of the vendor 0x0495 command ids for
ANC, the mode table, the transparency level, on-head detection, smart pause, auto-answer, comfort
call and battery, and of connecting by service UUID rather than by channel number.
