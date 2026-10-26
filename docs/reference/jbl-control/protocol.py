"""JBL "Protocol 3" — the AA-framed control protocol JBL earbuds speak over the
BLE "excelpoint.com" GATT service, plus the Google Fast Pair Message Stream
frames that carry battery and the buds' BLE address over RFCOMM.

Pure functions only: no sockets, no D-Bus. Everything here is unit-tested
against frames captured from real hardware (tests/fixtures/).

Frame:   AA <cmd> <len> <payload...>         no sequence byte, no checksum
         AA <cmd> <len lo> <len hi> <...>    for the long-length commands

Protocol knowledge credits (no code copied): ncr/omarchy-headphones (MIT),
Asklios/jbl-vibe-buds-2-gnome (BSD-3), emperorbug46/JblTray (MIT),
FajkPes/JBL-Headphones-App.
"""
import struct
import uuid

SERVICE_UUID = uuid.UUID("65786365-6c70-6f69-6e74-2e636f6d0000")
NOTIFY_UUID = uuid.UUID("65786365-6c70-6f69-6e74-2e636f6d0001")
WRITE_UUID = uuid.UUID("65786365-6c70-6f69-6e74-2e636f6d0002")
FAST_PAIR_STREAM_UUID = "df21fe2c-2515-4fdb-8886-f12c4d67927c"
JBL_VENDOR_ID = 0x0ECB
# 16-bit service data UUID in the buds' "<name>-LE" advertisement.
HARMAN_SERVICE = "0000fddf-0000-1000-8000-00805f9b34fb"

HEADER = 0xAA

# Commands whose frames from the buds carry a 2-byte little-endian length.
LONG_LEN_IN = frozenset({0x06, 0xA2})
# Commands we send with a 2-byte length. EQ is special-cased: its short GET is
# 1-byte, its full SET is 2-byte (see eq_set()).
LONG_LEN_OUT = frozenset({0x06})

# Never sent, whatever asks for it. 0x95 is factory reset.
BLOCKED_COMMANDS = frozenset({0x95})

CMD_ACK = 0x00
CMD_INFO = 0x06
CMD_NAME = 0x12
CMD_FIND = 0x23
CMD_GESTURE = 0x77
CMD_SAV = 0x80
CMD_ANC = 0x91
CMD_VOICE_PROMPT = 0x93
CMD_SERIAL = 0x94
CMD_STANDBY = 0x96
CMD_POWER_OFF = 0x97
CMD_VOICE_AWARE = 0x98
CMD_NOTIFY_ON = 0x9B
CMD_EQ = 0xA2

MODES = ("off", "anc", "ambient", "talkthru")

# Device-info feature ids carried in the 0x06 TLV frames.
FID_NAME = 0x01
FID_PRODUCT_ID = 0x02
FID_COLOR = 0x03
FID_MAC = 0x04
FID_FIRMWARE = 0x05
FID_TWS = 0x06
FID_AUTO_OFF = 0x0B
FID_SPATIAL = 0x0C
FID_SIDETONE = 0x0D
FID_BATT_LEFT = 0x1A
FID_BATT_RIGHT = 0x1B
FID_BATT_CASE = 0x1C
FID_CALL = 0x1F
FID_SAFE_SOUND = 0x20

INFO_PROBE_FIDS = (FID_NAME, FID_FIRMWARE, FID_TWS, FID_AUTO_OFF, FID_SPATIAL,
                   FID_SIDETONE, FID_BATT_LEFT, FID_BATT_RIGHT, FID_BATT_CASE,
                   FID_SAFE_SOUND)

SPATIAL_SCENES = {1: "movie", 2: "music", 3: "game"}

EQ_BAND_FREQS = (32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
EQ_OFF = 0
EQ_CUSTOM = 230
EQ_CURRENT = 0xFF
# category id -> (label, gains in dB for EQ_BAND_FREQS), from the JBL app.
EQ_PRESETS = {
    1: ("Jazz", (4.0, 3.0, 1.0, 2.5, -1.5, -1.5, 0.0, 1.0, 3.0, 4.0)),
    2: ("Vocal", (-1.5, -3.0, -3.0, 1.0, 3.5, 3.5, 3.0, 1.5, 0.0, -1.5)),
    3: ("Bass", (4.0, 4.0, 3.0, 2.5, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0)),
    6: ("Club", (2.0, 1.5, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 1.0, 2.0)),
    7: ("Studio", (-2.0, -1.0, -1.0, -0.5, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)),
    9: ("Extreme Bass", (4.0, 4.5, 4.5, 3.5, -1.0, 0.0, 0.0, 2.0, 2.0, 0.0)),
}

GESTURES = {
    "double-tap-left": 3, "double-tap-right": 4,
    "long-press-left": 5, "long-press-right": 6,
    "triple-tap-left": 7, "triple-tap-right": 8,
}
GESTURE_ACTIONS = {
    "none": 0, "volume-up": 1, "volume-down": 2, "ambient-aware": 3,
    "talkthru": 4, "next-track": 5, "previous-track": 6, "play-pause": 8,
    "noise-control": 9, "anc-off": 12, "ambient-off": 13, "voice-assistant": 161,
}

# Smart Audio & Video (cmd 0x80) codec blobs, byte-identical to the JBL app on a
# Tune Flex 2. They are opaque codec settings, so they are only ever sent to a
# device whose own status reply already matches one of them (see sav_mode()).
SAV_FRAMES = {
    "off": bytes.fromhex("aa80 0900 c500 2e00 1801 ffff"),
    "audio": bytes.fromhex("aa80 0900 0001 2e00 1801 ffff"),
    "video": bytes.fromhex("aa80 0900 c500 2e00 7800 ffff"),
}


class BlockedCommand(ValueError):
    pass


# ---------------------------------------------------------------- encoding

def encode(cmd, payload=b"", long_len=None):
    if cmd in BLOCKED_COMMANDS:
        raise BlockedCommand("command 0x%02x is blocked" % cmd)
    payload = bytes(payload)
    if long_len is None:
        long_len = cmd in LONG_LEN_OUT
    if long_len:
        return bytes([HEADER, cmd]) + struct.pack("<H", len(payload)) + payload
    if len(payload) > 255:
        raise ValueError("payload too long for a 1-byte length")
    return bytes([HEADER, cmd, len(payload)]) + payload


def notify_on():
    return encode(CMD_NOTIFY_ON, b"\x01\x01")


def mode_get():
    return encode(CMD_ANC, b"\x11")


def mode_set(mode):
    if mode not in MODES:
        raise ValueError("unknown mode %r" % mode)
    return encode(CMD_ANC, bytes([0x10, 1, int(mode == "anc"), 2, int(mode == "ambient"),
                                  3, int(mode == "talkthru")]))


def anc_level_get():
    return encode(CMD_ANC, b"\x21")


def anc_level_set(level):
    return encode(CMD_ANC, bytes([0x20, 0x04, max(1, min(7, int(level)))]))


def info_get(fids=INFO_PROBE_FIDS):
    return encode(CMD_INFO, b"\x01" + b"".join(bytes([0, f]) for f in fids))


def info_set(fid, value):
    value = bytes(value)
    return encode(CMD_INFO, bytes([0x00, 0x00, fid, len(value)]) + value)


def auto_off_set(enabled, minutes):
    return info_set(FID_AUTO_OFF, bytes([int(bool(enabled))]) + struct.pack("<H", int(minutes)))


def spatial_set(enabled, scene="music"):
    scenes = {v: k for k, v in SPATIAL_SCENES.items()}
    return info_set(FID_SPATIAL, bytes([int(bool(enabled)), scenes.get(scene, 2)]))


def safe_sound_set(enabled):
    return info_set(FID_SAFE_SOUND, bytes([int(bool(enabled))]))


def eq_get():
    return encode(CMD_EQ, bytes([0x01, EQ_CURRENT]))


def eq_set(category, gains):
    gains = [float(g) for g in gains]
    if len(gains) != len(EQ_BAND_FREQS):
        raise ValueError("expected %d band gains" % len(EQ_BAND_FREQS))
    body = bytearray([0x03, category & 0xFF])
    body += struct.pack("<f", -1.0)            # calibration
    body += bytes([48])                        # sample rate (kHz)
    body += struct.pack("<ff", 0.0, 0.0)       # left / right gain
    body += bytes([len(gains)])
    for gain, freq in zip(gains, EQ_BAND_FREQS):
        gain = max(-6.0, min(6.0, gain))
        body += b"\x01" + struct.pack("<ff", gain, float(freq)) + bytes([10])  # peak, Q=1.0
    return encode(CMD_EQ, body, long_len=True)


def eq_preset_set(category):
    if category not in EQ_PRESETS:
        raise ValueError("unknown EQ preset %r" % category)
    return eq_set(category, EQ_PRESETS[category][1])


def voice_prompts_get():
    return encode(CMD_VOICE_PROMPT, b"\x04")


def voice_prompts_set(enabled):
    return encode(CMD_VOICE_PROMPT, bytes([0x03, int(bool(enabled))]))


def voice_aware_get():
    return encode(CMD_VOICE_AWARE, b"\x01")


def voice_aware_set(level, extra=0):
    """VoiceAware takes <level> <extra>. With the second byte left out, a Wave
    Buds 2 fills it with 0xf8, so the value it reported is always sent back."""
    return encode(CMD_VOICE_AWARE, bytes([0x00, max(0, min(255, int(level))), int(extra) & 0xFF]))


def find_get():
    return encode(CMD_FIND, b"\x01")


def find_set(left=None, right=None):
    def side(v):
        return 0xFF if v is None else int(bool(v))
    return encode(CMD_FIND, bytes([0x00, side(left), side(right)]))


def gestures_set(pairs):
    body = bytearray([0x00])
    for gesture, action in pairs:
        body += bytes([GESTURES[gesture], GESTURE_ACTIONS[action]])
    return encode(CMD_GESTURE, body)


def standby_set(enabled):
    on = int(bool(enabled))
    return encode(CMD_STANDBY, bytes([0x00, 0x01, on, 0x02, 1 - on]))


def serial_get():
    return encode(CMD_SERIAL, b"\x01")


def sav_get():
    return encode(CMD_SAV, b"\x01")


def sav_set(mode):
    return SAV_FRAMES[mode]


def raw_frame(data):
    """A hand-written frame (jblctl raw / probing), still subject to the blocklist."""
    data = bytes(data)
    if len(data) < 3 or data[0] != HEADER:
        raise ValueError("a frame starts with AA and has at least 3 bytes")
    if data[1] in BLOCKED_COMMANDS:
        raise BlockedCommand("command 0x%02x is blocked" % data[1])
    return data


def power_off():
    return encode(CMD_POWER_OFF)


# ---------------------------------------------------------------- framing

class FrameReader:
    """Reassembles AA frames from GATT notifications.

    A frame normally arrives in one notification once the ATT MTU has been
    raised. A notification that starts a new frame while an older one is still
    incomplete means the older one was truncated (small MTU): it is returned
    as-is with truncated=True, so callers can still use the fields that made it.
    """

    def __init__(self):
        self.buf = bytearray()

    @staticmethod
    def _need(buf):
        if len(buf) < 3:
            return None
        if buf[1] in LONG_LEN_IN:
            if len(buf) < 4:
                return None
            return 4 + struct.unpack_from("<H", buf, 2)[0]
        return 3 + buf[2]

    def feed(self, data, last=False):
        """`last`: the notification was shorter than the MTU allows, so a frame
        still incomplete after it will not be continued; surface it now."""
        frames = self._feed(data)
        if last:
            frames += self.flush()
        return frames

    def _feed(self, data):
        frames = []
        data = bytes(data)
        if self.buf and data[:1] == bytes([HEADER]):
            need = self._need(self.buf)
            if need is not None and len(self.buf) < need:
                frames.append(self._make(bytes(self.buf), truncated=True))
                self.buf.clear()
        self.buf += data
        while self.buf:
            start = self.buf.find(HEADER)
            if start < 0:
                self.buf.clear()
                break
            del self.buf[:start]
            need = self._need(self.buf)
            if need is None or len(self.buf) < need:
                break
            frames.append(self._make(bytes(self.buf[:need])))
            del self.buf[:need]
        return frames

    def flush(self):
        if not self.buf:
            return []
        frame = self._make(bytes(self.buf), truncated=True)
        self.buf.clear()
        return [frame]

    @staticmethod
    def _make(raw, truncated=False):
        cmd = raw[1] if len(raw) > 1 else None
        offset = 4 if cmd in LONG_LEN_IN else 3
        return {"cmd": cmd, "payload": raw[offset:], "raw": raw, "truncated": truncated}


# ---------------------------------------------------------------- decoding

def battery_value(charging, level):
    if level is None or level > 100:
        return None
    return {"level": int(level), "charging": bool(charging)}


def parse_tlv(payload):
    """0x06 body: <marker> then entries of <fid hi> <fid lo> <vlen> <value>.

    Returns (marker, {fid: bytes}). Tolerates a truncated last entry by
    keeping whatever bytes of its value arrived.
    """
    if not payload:
        return None, {}
    marker = payload[0]
    out = {}
    i = 1
    while i + 3 <= len(payload):
        fid = (payload[i] << 8) | payload[i + 1]
        vlen = payload[i + 2]
        out[fid] = bytes(payload[i + 3:i + 3 + vlen])
        i += 3 + vlen
    return marker, out


def decode_info(payload):
    """Device-info TLVs -> state fragment."""
    _marker, tlv = parse_tlv(payload)
    state = {}
    battery = {}
    for fid, key in ((FID_BATT_LEFT, "left"), (FID_BATT_RIGHT, "right"), (FID_BATT_CASE, "case")):
        v = tlv.get(fid)
        if v is not None and len(v) >= 2:
            battery[key] = battery_value(v[0], v[1])
    if battery:
        state["battery"] = battery
    v = tlv.get(FID_NAME)
    if v:
        state["name"] = v.rstrip(b"\x00").decode("utf-8", "replace")
    v = tlv.get(FID_FIRMWARE)
    if v and len(v) >= 3:
        state["firmware"] = ".".join(str(b) for b in v[:3])
    v = tlv.get(FID_AUTO_OFF)
    if v and len(v) >= 3:
        state["autoOff"] = {"enabled": bool(v[0]), "minutes": struct.unpack_from("<H", v, 1)[0]}
    v = tlv.get(FID_SPATIAL)
    if v and len(v) >= 2 and v[1] in SPATIAL_SCENES:
        # Scenes are 1..3. A device without spatial sound still answers, with 00 00.
        state["spatial"] = {"enabled": bool(v[0]), "scene": SPATIAL_SCENES[v[1]]}
    v = tlv.get(FID_SAFE_SOUND)
    if v and len(v) >= 1 and v[0] <= 1:
        state["safeSound"] = bool(v[0])
    v = tlv.get(FID_SIDETONE)
    if v and len(v) >= 1:
        state["sidetone"] = v[0]
    v = tlv.get(FID_TWS)
    if v and len(v) >= 1:
        state["tws"] = v[0]
    return state, set(tlv)


def decode_mode(payload):
    """0x91 body. Returns a state fragment or {} for frames it does not know."""
    if not payload:
        return {}
    sub = payload[0]
    pairs = payload[1:]
    if sub in (0x10, 0x12) and len(pairs) >= 2:
        flags = {pairs[i]: pairs[i + 1] for i in range(0, len(pairs) - 1, 2)}
        mode = "off"
        for slot, name in ((1, "anc"), (2, "ambient"), (3, "talkthru")):
            if flags.get(slot):
                mode = name
                break
        return {"mode": mode, "modeSlots": sorted(flags)}
    if sub in (0x20, 0x22) and len(pairs) >= 2:
        flags = {pairs[i]: pairs[i + 1] for i in range(0, len(pairs) - 1, 2)}
        if 4 in flags:
            return {"ancLevel": flags[4]}
    return {}


def decode_eq(payload):
    if len(payload) < 16:
        return {}
    category = payload[1]
    count = payload[15]
    gains = []
    i = 16
    for _ in range(count):
        # A Wave Buds 2 cuts this reply 4 bytes short; the gains still all fit.
        if i + 5 > len(payload):
            break
        gains.append(round(struct.unpack_from("<f", payload, i + 1)[0], 1))
        i += 10
    if category == EQ_OFF:
        # A Wave Buds 2 with EQ off answers category 0 and no bands.
        preset = "off"
        gains = gains or [0.0] * len(EQ_BAND_FREQS)
    elif category in EQ_PRESETS:
        preset = category
    else:
        preset = "custom"
    return {"eq": {"preset": preset, "gains": gains}}


def decode_voice_prompt(payload):
    if len(payload) >= 2 and payload[0] == 0x05:
        return {"voicePrompts": bool(payload[1])}
    return {}


def decode_voice_aware(payload):
    # <dir 02> <level> <extra>, e.g. 02 02 00 on a Wave Buds 2.
    if len(payload) >= 2 and payload[0] in (0x00, 0x02):
        out = {"voiceAware": payload[1]}
        if len(payload) >= 3:
            out["voiceAwareExtra"] = payload[2]
        return out
    return {}


def decode_find(payload):
    if len(payload) >= 3:
        return {"finding": {"left": payload[1] == 1, "right": payload[2] == 1}}
    return {}


def decode_serial(payload):
    if len(payload) >= 2:
        return {"serial": bytes(payload[1:]).rstrip(b"\x00").decode("ascii", "replace")}
    return {}


def decode_name(payload):
    name = bytes(payload).split(b"\x00", 1)[0].decode("utf-8", "replace").strip()
    return {"name": name} if name else {}


def sav_mode(raw):
    """Which known codec blob a 0x80 frame carries. Byte 3 is the direction
    (00 set, 02 return), so it is left out of the comparison."""
    for mode, frame in SAV_FRAMES.items():
        if len(raw) >= len(frame) and raw[:3] == frame[:3] and raw[4:len(frame)] == frame[4:]:
            return mode
    return None


def decode_gestures(payload):
    names = {v: k for k, v in GESTURES.items()}
    actions = {v: k for k, v in GESTURE_ACTIONS.items()}
    out = {}
    for i in range(1, len(payload) - 1, 2):
        g = names.get(payload[i])
        if g:
            out[g] = actions.get(payload[i + 1], "id%d" % payload[i + 1])
    return {"gestures": out} if out else {}


def decode(frame):
    """Frame dict from FrameReader -> state fragment (possibly empty)."""
    cmd, payload = frame["cmd"], frame["payload"]
    if cmd == CMD_ANC:
        return decode_mode(payload)
    if cmd == CMD_INFO:
        return decode_info(payload)[0]
    if cmd == CMD_EQ:
        return decode_eq(payload)
    if cmd == CMD_VOICE_PROMPT:
        return decode_voice_prompt(payload)
    if cmd == CMD_VOICE_AWARE:
        return decode_voice_aware(payload)
    if cmd == CMD_FIND:
        return decode_find(payload)
    if cmd == CMD_SERIAL:
        return decode_serial(payload)
    if cmd == CMD_NAME:
        return decode_name(payload)
    # 0x77 (touch controls) is not decoded into state: the pair layout below is
    # the Vibe Buds 2's, and a Wave Buds 2 answers its GET with something else.
    if cmd == CMD_SAV:
        mode = sav_mode(frame["raw"])
        return {"lowLatency": mode == "video", "audioMode": mode} if mode else {}
    return {}


# ---------------------------------------------------------------- Fast Pair

def parse_fast_pair(buf):
    """Message Stream frames: <group> <code> <len u16 BE> <payload>.

    Returns (messages, rest) where rest is an incomplete tail to keep.
    """
    msgs = []
    i = 0
    while i + 4 <= len(buf):
        group, code = buf[i], buf[i + 1]
        n = struct.unpack_from(">H", buf, i + 2)[0]
        if i + 4 + n > len(buf):
            break
        msgs.append((group, code, bytes(buf[i + 4:i + 4 + n])))
        i += 4 + n
    return msgs, bytes(buf[i:])


def fast_pair_battery_byte(b):
    level = b & 0x7F
    if level == 0x7F:
        return None
    return {"level": level, "charging": bool(b & 0x80)}


def decode_fast_pair(group, code, payload):
    if group != 0x03:
        return {}
    if code == 0x01 and len(payload) == 3:
        return {"model": payload.hex()}
    if code == 0x02 and len(payload) == 6:
        return {"leAddress": ":".join("%02X" % b for b in payload)}
    if code == 0x03 and payload:
        # One byte on over-ear headphones, left/right/case on earbuds.
        keys = ("main",) if len(payload) == 1 else ("left", "right", "case")
        return {"battery": {k: fast_pair_battery_byte(b) for k, b in zip(keys, payload)}}
    return {}
