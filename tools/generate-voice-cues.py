"""Original short PCM earcons; same assets in Android and web. No external recordings."""
from pathlib import Path
import math, struct, wave, io

root = Path(__file__).resolve().parents[1]
for cue, notes in {'join': [523.25, 783.99], 'leave': [659.25, 392], 'mute': [440, 329.63], 'unmute': [440, 659.25]}.items():
    rate = 22050; samples = []
    for hz in notes:
        for n in range(int(rate * .105)):
            t = n / rate; envelope = min(1, t / .012) * max(0, min(1, (.105 - t) / .03))
            samples.append(int(32767 * .32 * envelope * (math.sin(2 * math.pi * hz * t) + .15 * math.sin(4 * math.pi * hz * t)) / 1.15))
        samples.extend([0] * int(rate * .018))
    out = io.BytesIO()
    with wave.open(out, 'wb') as wav:
        wav.setparams((1, 2, rate, 0, 'NONE', 'not compressed')); wav.writeframes(struct.pack('<' + 'h' * len(samples), *samples))
    for path in [root / 'app/src/main/res/raw' / ('voice_' + cue + '.wav'), root / 'NoWing-web/public/sounds' / ('voice-' + cue + '.wav')]:
        path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(out.getvalue())
