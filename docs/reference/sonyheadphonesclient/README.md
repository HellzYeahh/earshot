# SonyHeadphonesClient (mos9527) — what Earshot's Sony driver uses

Upstream: <https://github.com/mos9527/SonyHeadphonesClient> (MIT, see [LICENSE](LICENSE)), the maintained
continuation of Plutoberth/SonyHeadphonesClient (MIT, archived 2025-07-19). Read for Earshot 0.75 on
2026-10-21; nothing is vendored as source.

Ported, with attribution in the Kotlin sources (`protocol/.../sony/`, `app/.../bt/SonyConnection.kt`):

- The Tandem frame codec (`libmdr/src/Command.cpp`): `3E | escape(type, seq, len u32 BE, payload, sum8) | 3C`,
  escaping 0x3C/0x3D/0x3E as `3D 2C/2D/2E`.
- MDR v2 message layouts from the generated `libmdr/include/mdr/ProtocolV2T1.hpp` / `ProtocolV2T2.hpp` /
  `ProtocolV2.hpp`: command bytes, `FunctionType` ids, the NCASM param structs (types 0x13–0x19, 0x22), EQ
  capability / param (6 values = Clear Bass + 5 bands at ±10, 10 bands at ±6), auto power off, general
  settings, DSEE, connection mode, speak-to-chat, pause when taken off, voice guidance, alerts.
- The init order of `libmdr/src/HeadphonesV2.cpp` (minus its `LOG_SET_STATUS` enable, which Earshot never sends).

Golden frames in the tests are received frames from its replay captures (`tests/WF-1000XM5-6.1.0/`,
`tests/WF-LC900-2.0.3-equalizer/`), inlined as hex in `protocol/src/test/kotlin/dev/earshot/protocol/sony/`:
protocol info, device info, both support-function lists (the table-2 one carries an escaped 0x3D), L/R and
case battery, NCASM 0x17, EQ param and capability, auto power off, general settings, DSEE, speak-to-chat,
pause when taken off, voice guidance. Frames carrying names or addresses (paired devices, capability info)
were not copied.

Facts that came from elsewhere (Sound Connect 13.3.0 static analysis, Gadgetbridge, mdr-protocol) are
facts only; see `docs/research/research_notes/Top brand headset protocols/sony.md`.
