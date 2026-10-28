"""
Makes the Sleep tab's bundled recordings: seamless 48 kHz stereo Ogg Vorbis loops in
app/src/main/assets/sleep/<id>.ogg, from the original recordings listed in sources.json.

    python tools/sleep-sounds/make_loops.py <folder with the downloaded originals>

For each entry: cut `length_s + crossfade_s` seconds from `start_s` (high-passed at `highpass_hz`
to drop rumble and DC, resampled to 48 kHz stereo by ffmpeg), crossfade the extra seconds at the end into
the start with an equal-power curve so the loop's end runs straight into its beginning, level it
to `target_rms_db` (peaks kept under -1 dBFS) and encode with libvorbis at quality 3.

Needs ffmpeg (with libvorbis) on PATH and numpy. The originals are not kept in the repo; the
manifest says where each came from and under which licence (all CC0 / public domain or CC BY).
"""
import json
import pathlib
import subprocess
import sys

import numpy as np

RATE = 48_000
HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent.parent
OUT = REPO / "app" / "src" / "main" / "assets" / "sleep"


def decode(path: pathlib.Path, start: float, seconds: float, highpass_hz: float) -> np.ndarray:
    # Two poles of high-pass: drops wind rumble, handling thumps and DC that a loop would repeat.
    cmd = [
        "ffmpeg", "-v", "error", "-ss", str(start), "-t", str(seconds), "-i", str(path),
        "-af", f"highpass=f={highpass_hz}:poles=2", "-ac", "2", "-ar", str(RATE), "-f", "f32le", "pipe:1",
    ]
    raw = subprocess.run(cmd, check=True, capture_output=True).stdout
    return np.frombuffer(raw, dtype=np.float32).reshape(-1, 2).astype(np.float64)


def seamless(seg: np.ndarray, length: int, fade: int) -> np.ndarray:
    """seg holds length + fade frames; its last `fade` frames are blended into the first ones."""
    loop = seg[:length].copy()
    t = np.linspace(0.0, 1.0, fade, endpoint=False)[:, None]
    fade_in = np.sin(t * np.pi / 2)
    fade_out = np.cos(t * np.pi / 2)
    loop[:fade] = seg[:fade] * fade_in + seg[length:length + fade] * fade_out
    return loop


def seam_score(loop: np.ndarray) -> float:
    """How big the jump at the wrap is, against the recording's own sample-to-sample steps (1 = invisible)."""
    steps = np.abs(np.diff(loop, axis=0)).mean()
    jump = np.abs(loop[0] - loop[-1]).mean()
    return float(jump / max(steps, 1e-12))


def level(loop: np.ndarray, target_rms_db: float) -> np.ndarray:
    rms = np.sqrt(np.mean(loop ** 2))
    gain = 10 ** (target_rms_db / 20) / max(rms, 1e-9)
    peak = np.abs(loop).max() * gain
    ceiling = 10 ** (-1 / 20)
    if peak > ceiling:
        gain *= ceiling / peak
    return loop * gain


def encode(loop: np.ndarray, out: pathlib.Path) -> None:
    cmd = [
        "ffmpeg", "-v", "error", "-y", "-f", "f32le", "-ar", str(RATE), "-ac", "2", "-i", "pipe:0",
        "-c:a", "libvorbis", "-q:a", "3", "-map_metadata", "-1", str(out),
    ]
    subprocess.run(cmd, check=True, input=loop.astype(np.float32).tobytes())


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    originals = pathlib.Path(sys.argv[1])
    sources = json.loads((HERE / "sources.json").read_text(encoding="utf-8"))
    OUT.mkdir(parents=True, exist_ok=True)
    for s in sources["recordings"]:
        length, fade = int(s["length_s"] * RATE), int(s["crossfade_s"] * RATE)
        seg = decode(originals / s["file"], s["start_s"], s["length_s"] + s["crossfade_s"], s.get("highpass_hz", 25))
        if len(seg) < length + fade:
            sys.exit(f"{s['id']}: the original is too short for start {s['start_s']} s + {s['length_s'] + s['crossfade_s']} s")
        loop = level(seamless(seg, length, fade), s["target_rms_db"])
        out = OUT / f"{s['id']}.ogg"
        encode(loop, out)
        print(f"{s['id']:<12} {len(loop) / RATE:5.1f} s  seam {seam_score(loop):4.2f}  peak {20 * np.log10(np.abs(loop).max()):6.1f} dBFS  {out.stat().st_size / 1024:6.0f} KB")


if __name__ == "__main__":
    main()
