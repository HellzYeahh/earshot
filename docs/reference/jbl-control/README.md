# jbl-control (reference copy)

Files from [AhmadRun/jbl-control](https://github.com/AhmadRun/jbl-control) (MIT, see `LICENSE`),
branch `jbl-control`, fetched 2026-10-25:

- `protocol.py`: the H-AA frame layout, command ids and the EQ presets Earshot's
  `protocol/jbl` follows (no code copied; Earshot's Kotlin is its own).
- `wave-buds-2-capture.txt`: its `tests/fixtures/wave-buds-2.txt`, a capture of a JBL Wave Buds 2
  on firmware 2.7.0. `JblWireTest` and the virtual Wave Buds 2 use these frames.

JBL's own app and its bundled Airoha SDK are proprietary: the research notes record facts from
them only (`docs/research/research_notes/Top brand headset protocols/jbl.md`).
