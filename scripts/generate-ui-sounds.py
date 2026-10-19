"""Generates miel's UI sounds, short synthesized tones, into the app's assets."""

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
# (start time, frequency, duration, amplitude). No sampled or third-party audio.
TONES = {
    "navigate": [(0, 740, 0.065, 0.26), (0.008, 1110, 0.045, 0.07)],
    "confirm": [(0, 660, 0.11, 0.23), (0.065, 990, 0.16, 0.20)],
    "back": [(0, 660, 0.09, 0.20), (0.055, 440, 0.12, 0.18)],
    "open": [(0, 550, 0.09, 0.20), (0.045, 825, 0.10, 0.15)],
    "error": [(0, 330, 0.09, 0.18), (0.11, 330, 0.10, 0.14)],
}

OUTPUT.mkdir(parents=True, exist_ok=True)
for name, notes in TONES.items():
    length = max(start + duration for start, _, duration, _ in notes) + 0.015
    samples = []
    for i in range(math.ceil(length * RATE)):
        t = i / RATE
        value = 0.0
        for start, hz, duration, amplitude in notes:
            age = t - start
            if 0 <= age < duration:
                # Gentle attack and release keep the tones free of hard clicks.
                envelope = min(1, age / 0.006) * (1 - age / duration) ** 2
                value += amplitude * envelope * math.sin(2 * math.pi * hz * age)
        samples.append(round(max(-1, min(1, value)) * 32767))
    with wave.open(str(OUTPUT / f"{name}.wav"), "wb") as audio:
        audio.setparams((1, 2, RATE, 0, "NONE", "not compressed"))
        audio.writeframes(struct.pack(f"<{len(samples)}h", *samples))
    print(f"{name}: {len(samples) / RATE:.3f}s")
