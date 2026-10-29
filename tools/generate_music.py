"""Render two original instrumental scores; no samples or source-game audio."""
from __future__ import annotations

import json
import math
from pathlib import Path
import wave

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
RATE = 22_050


def note(track, start, length, midi, gain, voice, pan=0.0):
    count = round(length * RATE)
    t = np.arange(count, dtype=np.float64) / RATE
    hz = 440 * 2 ** ((midi - 69) / 12)
    phase = 2 * math.pi * hz * t
    if voice == "pad":
        signal = np.sin(phase) + .23 * np.sin(phase * 2) + .08 * np.sin(phase * 3)
        envelope = np.minimum(t / .65, 1) * np.minimum((length - t) / 1.1, 1)
        envelope = np.sin(envelope.clip(0, 1) * math.pi / 2) ** 2
    elif voice == "bell":
        signal = np.sin(phase) + .27 * np.sin(phase * 2.01) * np.exp(-t * 1.4)
        signal += .09 * np.sin(phase * 3.98) * np.exp(-t * 3)
        envelope = (1 - np.exp(-t * 70)) * np.exp(-t * 1.55)
        envelope *= np.minimum((length - t) / .18, 1).clip(0, 1)
    elif voice == "harp":
        signal = np.sin(phase) + .18 * np.sin(phase * 2) + .06 * np.sin(phase * 3)
        envelope = (1 - np.exp(-t * 90)) * np.exp(-t * 3.1)
        envelope *= np.minimum((length - t) / .12, 1).clip(0, 1)
    elif voice == "pulse":
        signal = np.sin(2 * math.pi * (hz * t + 12 * (1 - np.exp(-t * 16))))
        envelope = (1 - np.exp(-t * 130)) * np.exp(-t * 17)
        envelope *= np.minimum((length - t) / .03, 1).clip(0, 1)
    else:
        signal = np.sin(phase) + .08 * np.sin(phase * 2)
        envelope = np.minimum(t / .08, 1) * np.minimum((length - t) / .25, 1)
        envelope = np.sin(envelope.clip(0, 1) * math.pi / 2) ** 2
    mono = signal * envelope * gain
    stereo = mono[:, None] * np.array([math.sqrt((1 - pan) / 2), math.sqrt((1 + pan) / 2)])
    offset = round(start * RATE) % len(track)
    first = min(count, len(track) - offset)
    track[offset:offset + first] += stereo[:first]
    if first < count:
        track[:count - first] += stereo[first:]


def render(name, tempo, chords, melody, rhythmic):
    beat = 60 / tempo
    bar = beat * 4
    duration = bar * 32
    track = np.zeros((round(duration * RATE), 2), dtype=np.float64)
    # Four eight-bar phrases: a statement, an answer, an upper-register variation, a return.
    for measure in range(32):
        chord = chords[measure % 8]
        start = measure * bar
        phrase = measure // 8
        dynamic = [.88, .98, 1.06, .9][phrase]
        for index, pitch in enumerate(chord):
            note(track, start - .45, bar + 1.35, pitch, .035 * dynamic, "pad", (index - 1.5) * .27)
        note(track, start, bar * .85, chord[0] - 12, .045 * dynamic, "bass", -.08)
        line = melody[measure % 8]
        for index, pitch in enumerate(line):
            if pitch is not None:
                transpose = 12 if phrase == 2 and index == 2 else 0
                note(track, start + index * beat, beat * 2.6, pitch + transpose,
                     .105 * dynamic * (1 if index % 2 == 0 else .78), "bell", .18)
        for step in range(8 if rhythmic else 4):
            offset = step * beat * (.5 if rhythmic else 1)
            pitch = chord[(step + measure) % 4] + 12
            note(track, start + offset + beat * .12, beat * 1.7, pitch,
                 (.045 if rhythmic else .026) * dynamic, "harp", -.38 if step % 2 else .38)
        if rhythmic:
            for step in (0, 2):
                note(track, start + step * beat, .24, 42 if step == 0 else 49, .035, "pulse", 0)
    # Circular stereo echoes preserve the score's continuous loop, including its decaying tails.
    dry = track.copy()
    for delay, gain in ((.227, .16), (.463, .105), (.811, .07), (1.207, .04)):
        track += np.roll(dry[:, ::-1], round(delay * RATE), axis=0) * gain
    track -= track.mean(axis=0)
    track *= .55 / np.max(np.abs(track))
    pcm = np.rint(track * 32767).astype("<i2")
    path = ROOT / "app/src/main/res/raw" / name
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as output:
        output.setparams((2, 2, RATE, len(pcm), "NONE", "not compressed"))
        output.writeframes(pcm.tobytes())
    metrics = {
        "file": name, "seconds": round(len(pcm) / RATE, 6), "tempo_bpm": tempo,
        "bars": 32, "sample_rate": RATE, "channels": 2, "bits": 16,
        "peak": round(float(np.max(np.abs(track))), 6),
        "rms": round(float(np.sqrt(np.mean(track ** 2))), 6),
        "loop_boundary_jump": round(float(np.max(np.abs(track[0] - track[-1]))), 8),
        "maximum_regular_sample_jump": round(float(np.max(np.abs(np.diff(track, axis=0)))), 8),
        "clipped_samples": int(np.count_nonzero(np.abs(pcm.astype(np.int32)) >= 32767)),
        "bytes": path.stat().st_size,
    }
    windows = [float(np.sqrt(np.mean(track[i:i + RATE * 4] ** 2)))
               for i in range(0, len(track) - RATE * 4, RATE * 4)]
    assert 80 <= metrics["seconds"] <= 100
    assert metrics["clipped_samples"] == 0 and metrics["peak"] <= .551
    assert .025 < metrics["rms"] < .25 and min(windows) > .02
    assert metrics["loop_boundary_jump"] < .015
    assert metrics["loop_boundary_jump"] <= metrics["maximum_regular_sample_jump"]
    with wave.open(str(path), "rb") as check:
        assert check.getnframes() == len(pcm) and check.getnchannels() == 2
        assert check.getsampwidth() == 2 and check.getframerate() == RATE
    return metrics


if __name__ == "__main__":
    menu = render("menu_music.wav", 84,
        [(57, 60, 64, 71), (53, 57, 60, 64), (60, 64, 67, 74), (55, 59, 62, 69),
         (62, 65, 69, 76), (57, 60, 64, 67), (53, 57, 60, 64), (52, 56, 59, 66)],
        [(76, None, 81, 79), (76, 72, None, 69), (79, None, 83, 81), (79, 74, None, 71),
         (77, None, 81, 76), (76, 72, None, 71), (72, 76, 77, None), (76, 75, 71, None)], False)
    game = render("game_music.wav", 88,
        [(50, 57, 62, 64), (46, 53, 57, 62), (53, 60, 64, 69), (48, 55, 60, 62),
         (55, 58, 62, 69), (50, 57, 60, 65), (46, 53, 57, 64), (45, 52, 61, 67)],
        [(77, 81, 79, 76), (77, None, 74, 72), (76, 79, 81, None), (79, 76, 74, 72),
         (79, 81, 82, None), (81, 77, 76, 74), (77, 76, 74, None), (76, 73, 76, None)], True)
    print(json.dumps([menu, game], indent=2))
