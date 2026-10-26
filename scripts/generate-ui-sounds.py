"""Generate miel's rounded UI taps using only the Python standard library."""

from dataclasses import dataclass
import math
from pathlib import Path
import struct
import wave

RATE = 44100
OUTPUT = (
    Path(__file__).resolve().parents[1]
    / "android"
    / "app"
    / "src"
    / "main"
    / "assets"
    / "sounds"
)


@dataclass(frozen=True)
class Tap:
    start_hz: float
    end_hz: float
    duration: float
    decay: float
    amplitude: float


# A single continuous pitch contour per cue; no delayed notes or sampled audio.
TAPS = {
    "navigate": Tap(310, 240, 0.065, 0.014, 0.105),
    "confirm": Tap(390, 470, 0.110, 0.024, 0.140),
    "back": Tap(300, 190, 0.090, 0.020, 0.120),
    "open": Tap(260, 370, 0.100, 0.023, 0.125),
    "error": Tap(185, 145, 0.125, 0.028, 0.140),
}
ATTACK = 0.004
RELEASE = 0.012


def synthesize(tap: Tap) -> list[int]:
    samples = []
    count = math.ceil(tap.duration * RATE)
    for i in range(count):
        t = i / RATE
        attack = math.sin(math.pi / 2 * min(1, t / ATTACK)) ** 2
        remaining = (count - 1 - i) / RATE
        release = math.sin(math.pi / 2 * min(1, remaining / RELEASE)) ** 2
        envelope = attack * math.exp(-t / tap.decay) * release
        phase = 2 * math.pi * (
            tap.start_hz * t
            + (tap.end_hz - tap.start_hz) * t * t / (2 * tap.duration)
        )
        value = tap.amplitude * envelope * (math.sin(phase) + 0.08 * math.sin(2 * phase))
        samples.append(round(value * 32767))
    return samples


def generate(output: Path = OUTPUT) -> None:
    output.mkdir(parents=True, exist_ok=True)
    for name, tap in TAPS.items():
        samples = synthesize(tap)
        with wave.open(str(output / f"{name}.wav"), "wb") as audio:
            audio.setparams((1, 2, RATE, 0, "NONE", "not compressed"))
            audio.writeframes(struct.pack(f"<{len(samples)}h", *samples))
        print(f"{name}: {len(samples) / RATE:.3f}s")


if __name__ == "__main__":
    generate()
