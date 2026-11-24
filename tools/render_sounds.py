"""Syntéza zvuků vody (bez cizích nahrávek): kapka dopadající na hladinu a smyčka přetékající vody.
Spuštění: python3 tools/render_sounds.py app/src/main/res/raw"""
import sys, os, wave
import numpy as np

OUT = sys.argv[1] if len(sys.argv) > 1 else '/tmp/snd'
os.makedirs(OUT, exist_ok=True)
SR = 22050
rng = np.random.default_rng(3)


def save(name, x, gain_db=-8.0):
    x = x / (np.max(np.abs(x)) + 1e-9) * (10 ** (gain_db / 20))
    pcm = (np.clip(x, -1, 1) * 32767).astype('<i2')
    with wave.open(os.path.join(OUT, name + '.wav'), 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm.tobytes())


def chirp(dur, f0, f1, decay, t0=0.0, total=None):
    n = int((total or dur) * SR)
    t = np.arange(n) / SR
    out = np.zeros(n)
    m = (t >= t0) & (t < t0 + dur)
    tt = t[m] - t0
    f = f0 + (f1 - f0) * (tt / dur)
    ph = 2 * np.pi * np.cumsum(f) / SR
    out[m] = np.sin(ph) * np.exp(-tt / decay)
    return out


def drip():
    total = 0.42
    n = int(total * SR)
    t = np.arange(n) / SR
    # dopad: krátké cvaknutí
    click = rng.normal(0, 1, n) * np.exp(-t / 0.002) * 0.25
    # rezonance bublinky pod hladinou – typické „plink“ se stoupající výškou
    plink = chirp(0.09, 950, 1750, 0.035, 0.004, total) * 1.0
    plink2 = chirp(0.06, 1500, 2300, 0.02, 0.020, total) * 0.35
    tail = chirp(0.20, 700, 760, 0.08, 0.010, total) * 0.18
    x = click + plink + plink2 + tail
    # jemný dozvuk místnosti
    rev = np.zeros(n)
    for dly, g in ((0.031, 0.25), (0.047, 0.18), (0.071, 0.12), (0.103, 0.08)):
        k = int(dly * SR)
        rev[k:] += x[:-k] * g
    save('water_drip', x + rev, -9)


def overflow():
    dur = 3.0
    n = int(dur * SR)
    t = np.arange(n) / SR
    noise = rng.normal(0, 1, n)
    # pásmová propust 600–3500 Hz přes FFT
    spec = np.fft.rfft(noise)
    f = np.fft.rfftfreq(n, 1 / SR)
    band = np.exp(-((np.log(f + 1) - np.log(1500)) ** 2) / (2 * 0.55 ** 2))
    hiss = np.fft.irfft(spec * band, n)
    hiss /= np.max(np.abs(hiss))
    # pomalé kolísání proudu
    mod = 0.65 + 0.35 * np.sin(2 * np.pi * 0.7 * t) * np.sin(2 * np.pi * 0.23 * t + 1)
    x = hiss * mod * 0.5
    # spousta drobných bublinek (bublání odtoku)
    for _ in range(140):
        t0 = rng.uniform(0, dur - 0.05)
        f0 = rng.uniform(500, 1400)
        x += chirp(0.04, f0, f0 * rng.uniform(1.3, 1.9), rng.uniform(0.008, 0.02), t0, dur) * rng.uniform(0.1, 0.35)
    # plynulá smyčka: prolnutí konce se začátkem
    fade = int(0.25 * SR)
    w = np.linspace(0, 1, fade)
    x[:fade] = x[:fade] * w + x[-fade:] * (1 - w)
    x = x[:-fade]
    save('water_overflow', x, -14)


drip()
overflow()
print('ok')
