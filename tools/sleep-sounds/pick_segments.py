"""
Finds the steadiest stretch of each original recording for a sleep loop, so nobody has to scrub
through minutes of audio by ear first (then listen to the result anyway).

    python tools/sleep-sounds/pick_segments.py <folder> <file>:<seconds> [<file>:<seconds> ...]

For every window of the given length (1 s steps) it scores the short-term level (0.5 s RMS after a
100 Hz high-pass): a low spread is good, and any half-second more than 5 dB above the window's
median (a voice, a car, a door, a thunder crack) costs a lot. Prints the best start per file with
the level, the spread, the loudest jump and a tonality figure (spectral flatness: near 0 = a hum
or whistle, near 1 = noise-like), and writes a spectrogram of the chosen stretch next to the file.
"""
import pathlib
import subprocess
import sys

import numpy as np

RATE = 16_000
WIN = 0.5


def decode_mono(path: pathlib.Path) -> np.ndarray:
    cmd = ["ffmpeg", "-v", "error", "-i", str(path), "-af", "highpass=f=100", "-ac", "1", "-ar", str(RATE), "-f", "f32le", "pipe:1"]
    return np.frombuffer(subprocess.run(cmd, check=True, capture_output=True).stdout, dtype=np.float32).astype(np.float64)


def flatness(x: np.ndarray) -> float:
    n = 4096
    frames = [np.abs(np.fft.rfft(x[s:s + n] * np.hanning(n))) ** 2 + 1e-18 for s in range(0, len(x) - n, n * 8)]
    p = np.mean(frames, axis=0)[5:]  # skip DC and the lowest bins
    return float(np.exp(np.mean(np.log(p))) / np.mean(p))


def best_window(levels: np.ndarray, length: int) -> tuple[int, float, float, float]:
    best = (0, float("inf"), 0.0, 0.0)
    for start in range(0, len(levels) - length + 1, int(1 / WIN)):
        w = levels[start:start + length]
        med = np.median(w)
        jump = float(w.max() - med)
        score = float(np.std(w)) + 2.0 * max(0.0, jump - 5.0)
        if score < best[1]:
            best = (start, score, float(med), jump)
    return best


def main() -> None:
    folder = pathlib.Path(sys.argv[1])
    for spec in sys.argv[2:]:
        name, seconds = spec.rsplit(":", 1)
        path = folder / name
        x = decode_mono(path)
        n = int(WIN * RATE)
        frames = len(x) // n
        levels = 20 * np.log10(np.sqrt(np.mean(x[: frames * n].reshape(frames, n) ** 2, axis=1)) + 1e-9)
        length = int(float(seconds) / WIN)
        if frames < length:
            print(f"{name:<14} too short: {len(x) / RATE:.0f} s < {seconds} s")
            continue
        start, score, med, jump = best_window(levels, length)
        start_s = start * WIN
        seg = x[int(start_s * RATE): int((start_s + float(seconds)) * RATE)]
        print(f"{name:<14} {len(x) / RATE:6.0f} s  start {start_s:6.1f} s  level {med:6.1f} dB  spread {np.std(levels[start:start + length]):4.1f}  "
              f"jump {jump:4.1f} dB  flatness {flatness(seg):4.2f}")
        png = folder / f"{path.stem}_pick.png"
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", str(start_s), "-t", str(seconds), "-i", str(path),
                        "-lavfi", "showspectrumpic=s=1200x360:legend=1:fscale=log:color=intensity", str(png)], check=True)


if __name__ == "__main__":
    main()
