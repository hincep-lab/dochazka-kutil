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


def clank():
    total = 0.5
    n = int(total * SR); t = np.arange(n) / SR
    x = np.zeros(n)
    for f, a, dcy in ((820, 1.0, 0.16), (1313, 0.7, 0.12), (2154, 0.5, 0.08), (3390, 0.35, 0.05), (4870, 0.2, 0.03)):
        x += a * np.sin(2 * np.pi * f * t) * np.exp(-t / dcy)
    x += rng.normal(0, 1, n) * np.exp(-t / 0.004) * 0.6
    save('gag_clank', x, -10)


def splash():
    total = 0.7
    n = int(total * SR); t = np.arange(n) / SR
    noise = rng.normal(0, 1, n)
    spec = np.fft.rfft(noise); f = np.fft.rfftfreq(n, 1 / SR)
    band = np.exp(-((np.log(f + 1) - np.log(2200)) ** 2) / (2 * 0.7 ** 2))
    x = np.fft.irfft(spec * band, n)
    x = x / np.max(np.abs(x)) * np.exp(-t / 0.18) * np.minimum(1, t / 0.01)
    for _ in range(18):
        t0 = rng.uniform(0.02, 0.45); f0 = rng.uniform(600, 1500)
        x += chirp(0.05, f0, f0 * 1.6, 0.015, t0, total) * 0.25
    save('gag_splash', x, -9)


def boing():
    total = 0.75
    n = int(total * SR); t = np.arange(n) / SR
    f = 160 + 380 * (1 - np.exp(-t / 0.08)) + 60 * np.sin(2 * np.pi * 14 * t) * np.exp(-t / 0.35)
    ph = 2 * np.pi * np.cumsum(f) / SR
    x = (np.sin(ph) + 0.3 * np.sin(2 * ph)) * np.exp(-t / 0.3) * np.minimum(1, t / 0.005)
    save('gag_boing', x, -11)


def thud():
    total = 0.45
    n = int(total * SR); t = np.arange(n) / SR
    f = 95 * np.exp(-t / 0.2) + 45
    ph = 2 * np.pi * np.cumsum(f) / SR
    x = np.sin(ph) * np.exp(-t / 0.12) + rng.normal(0, 1, n) * np.exp(-t / 0.02) * 0.3
    # „žbluňk“ – sedl si do louže
    x += chirp(0.08, 500, 900, 0.03, 0.02, total) * 0.4
    save('gag_thud', x, -9)


def hiss():
    dur = 2.5
    n = int(dur * SR); t = np.arange(n) / SR
    noise = rng.normal(0, 1, n)
    spec = np.fft.rfft(noise); f = np.fft.rfftfreq(n, 1 / SR)
    band = np.exp(-((np.log(f + 1) - np.log(4200)) ** 2) / (2 * 0.6 ** 2))
    x = np.fft.irfft(spec * band, n)
    x = x / np.max(np.abs(x)) * (0.8 + 0.2 * np.sin(2 * np.pi * 3.1 * t))
    fade = int(0.2 * SR); w = np.linspace(0, 1, fade)
    x[:fade] = x[:fade] * w + x[-fade:] * (1 - w)
    save('gag_spray', x[:-fade], -16)


drip()
overflow()
clank()
splash()
boing()
thud()
hiss()
print('ok')
