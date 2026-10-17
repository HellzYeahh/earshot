"""Bose Ultra Open Earbuds device configuration.

Codename "serena", product ID 0x4068, firmware 4.0.22+g1b923b0.

Built from a device report (#25) that confirmed on hardware: ProductInfo,
product name, battery, Settings block dump and SETGET writes, EQ, AudioModes
dump, and mode switching via START [31.3]. No CNC entry: this is an
open-ear design with no noise cancelling. Mode names and the ModeConfig
STATUS length were not captured, so mode_config reuses the 48-byte QC Ultra
parser and carries no builder — modes can be read and switched, not edited.
"""

from . import parsers

RFCOMM_CHANNEL = 2

DEVICE_INFO = {
    "name": "Bose Ultra Open Earbuds",
    "codename": "serena",
    "platform": "QCC",
    "product_id": 0x4068,
    "variant": 0x00,
}

FEATURES = {
    "battery": {
        "addr": (2, 2),
        "parser": parsers.parse_battery,
    },
    "firmware": {
        "addr": (0, 5),
        "parser": parsers.parse_firmware,
    },
    "product_name": {
        "addr": (1, 2),
        "parser": parsers.parse_product_name,
    },
    "voice_prompts": {
        "addr": (1, 3),
        "parser": parsers.parse_voice_prompts,
        "builder": parsers.build_voice_prompts,
    },
    "eq": {
        "addr": (1, 7),
        "parser": parsers.parse_eq,
        "builder": parsers.build_eq_band,
    },
    "multipoint": {
        "addr": (1, 10),
        "parser": parsers.parse_multipoint,
        "builder": parsers.build_toggle,
    },
    "pairing": {
        "addr": (4, 8),
    },
    "source": {
        "addr": (5, 1),
        "parser": parsers.parse_source,
    },
    "power": {
        "addr": (7, 4),
    },
    # AudioModes block (31) — switching confirmed; layout of [31.6] inferred
    "get_all_modes": {
        "addr": (31, 1),
    },
    "current_mode": {
        "addr": (31, 3),
    },
    "mode_config": {
        "addr": (31, 6),
        "parser": parsers.parse_mode_config_48,
        # No builder until a STATUS capture fixes the SETGET layout
    },
}

# Mode names were not in the report; set_mode() resolves names through
# get_all_modes at runtime, so `bosectl switch <name>` still works.
PRESET_MODES = {}

MODE_BY_IDX = {}

EDITABLE_SLOTS = []

STATUS_OFFSETS = {}
