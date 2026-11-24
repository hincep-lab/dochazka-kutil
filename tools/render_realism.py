"""Realističtější prvky: 3D kohoutek (raymarching), ruční kolo ve fázích otáčení, vpusť v perspektivě,
obkladová stěna technické místnosti a bezešvá textura kaustik pro vodu.

Spuštění:  python3 tools/render_realism.py app/src/main/res/drawable-nodpi
Vypíše konstanty (poloha ústí kohoutku, výřez kola), které jsou zapsané ve Water.kt.
"""
import os
import sys
import numpy as np
from PIL import Image, ImageFilter

sys.argv = [sys.argv[0], sys.argv[1] if len(sys.argv) > 1 else '/tmp/real']
OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)
sys.path.insert(0, os.path.dirname(__file__))
import render_assets as ra  # noqa: E402  (sdílí stínování chromu a kapky)

PITCH = np.radians(22)  # pohled mírně shora
CA, SA = np.cos(PITCH), np.sin(PITCH)


def to_obj(p):
    """Bod v prostoru obrazovky -> prostor objektu (otočení kolem osy x)."""
    x, y, z = p[..., 0], p[..., 1], p[..., 2]
    return np.stack([x, y * CA + z * SA, -y * SA + z * CA], -1)


def to_screen(o):
    x, y, z = o
    return x, y * CA - z * SA


def length(*a):
    return np.sqrt(sum(v * v for v in a))


def sd_cyl_x(p, x0, x1, cy, cz, r):
    d_r = length(p[..., 1] - cy, p[..., 2] - cz) - r
    d_x = np.maximum(x0 - p[..., 0], p[..., 0] - x1)
    return np.maximum(d_r, d_x)


def sd_cyl_y(p, y0, y1, cx, cz, r):
    d_r = length(p[..., 0] - cx, p[..., 2] - cz) - r
    d_y = np.maximum(y0 - p[..., 1], p[..., 1] - y1)
    return np.maximum(d_r, d_y)


def smin(a, b, k):
    h = np.clip(0.5 + 0.5 * (b - a) / k, 0, 1)
    return b * (1 - h) + a * h - k * h * (1 - h)


def march(W, H, scene, steps=170):
    yy, xx = np.mgrid[0:H, 0:W].astype(float) + 0.5
    P0 = np.stack([xx, yy, np.full_like(xx, 400.0)], -1)
    t = np.zeros(xx.shape)
    hit = np.zeros(xx.shape, bool)
    for _ in range(steps):
        p = P0.copy(); p[..., 2] -= t
        d, _ = scene(to_obj(p))
        hit |= d < 0.04
        t = np.where(hit, t, t + np.maximum(d * 0.9, 0.04))
    p = P0.copy(); p[..., 2] -= t
    o = to_obj(p)
    d, mat = scene(o)
    hit &= t < 790
    e = 0.35
    def grad(axis):
        dp = np.zeros(3); dp[axis] = e
        return scene(o + dp)[0] - scene(o - dp)[0]
    nx, ny, nz = grad(0), grad(1), grad(2)
    # normála zpět do prostoru obrazovky
    sny = ny * CA - nz * SA
    snz = ny * SA + nz * CA
    nn = length(nx, sny, snz) + 1e-9
    alpha = np.asarray(Image.fromarray((hit * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(0.55)), float) / 255
    return nx / nn, sny / nn, snz / nn, mat, alpha, o


# ------------------------------------------------------------------------------- kohoutek
FW, FH = 600, 400
BODY_Y = 185.0
X0 = 210.0       # konec rovné části těla
RB = 62.0        # poloměr ohybu
OUT_X = X0 - RB  # osa výtoku
WALL_X = FW - 40
HANDLE_X = 400.0


def faucet_scene(p, wheel_angle=None):
    x, y, z = p[..., 0], p[..., 1], p[..., 2]
    flange = sd_cyl_x(p, WALL_X - 6, WALL_X + 14, BODY_Y, 0, 52) - 2
    collar = sd_cyl_x(p, WALL_X - 34, WALL_X - 4, BODY_Y, 0, 36) - 2
    body = sd_cyl_x(p, X0, WALL_X - 20, BODY_Y, 0, 30)
    # koleno (čtvrtina toru v rovině xy)
    cx, cy = X0, BODY_Y + RB
    qx = length(x - cx, y - cy) - RB
    tor = length(qx, z) - 27
    tor = np.where((x <= cx) & (y <= cy), tor, 1e3)
    outlet = sd_cyl_y(p, cy - 1, cy + 36, OUT_X, 0, 25)
    aer = sd_cyl_y(p, cy + 30, cy + 50, OUT_X, 0, 27.5) - 1.5
    # hlava ventilu na těle
    bonnet = sd_cyl_y(p, BODY_Y - 62, BODY_Y - 10, HANDLE_X, 0, 25) - 3
    nut = sd_cyl_y(p, BODY_Y - 78, BODY_Y - 60, HANDLE_X, 0, 17) - 2
    stem = sd_cyl_y(p, BODY_Y - 104, BODY_Y - 76, HANDLE_X, 0, 7)
    chrome = smin(smin(smin(body, tor, 6), outlet, 4), smin(collar, smin(bonnet, nut, 5), 3), 3)
    chrome = np.minimum(chrome, flange)
    steel = np.minimum(aer, stem)
    d = np.minimum(chrome, steel)
    mat = np.where(steel < chrome, 1, 0)
    if wheel_angle is not None:
        w = sd_wheel(p, wheel_angle)
        mat = np.where(w < d, 2, mat)
        d = np.minimum(d, w)
    return d, mat


WHEEL_Y = BODY_Y - 104
WHEEL_R = 62.0


def sd_wheel(p, ang):
    q = p - np.array([HANDLE_X, WHEEL_Y, 0])
    x, y, z = q[..., 0], q[..., 1], q[..., 2]
    ring = length(length(x, z) - WHEEL_R, y) - 9
    best = ring
    for k in range(4):
        a = ang + k * np.pi / 2
        dx, dz = np.cos(a), np.sin(a)
        proj = np.clip(x * dx + z * dz, 0, WHEEL_R)
        best = np.minimum(best, length(x - proj * dx, y, z - proj * dz) - 6)
    hub = np.maximum(length(x, z) - 15, np.abs(y) - 8)
    return smin(best, hub, 4)


def chrome_studio(nx, ny, nz):
    """Leštěný chrom v „ateliéru“: světlý strop, tmavý horizont, svislé světelné pruhy (okna/softboxy)."""
    rx, ry, rz = 2 * nz * nx, 2 * nz * ny, 2 * nz * nz - 1
    u = ry
    sky = np.interp(u, [-1, -0.5, -0.15, -0.02, 0.0, 0.08, 0.5, 1], [245, 225, 160, 70, 22, 40, 85, 120])
    col = np.stack([sky * 0.93, sky * 0.97, sky * 1.02], -1)
    for cx, w, a in ((0.42, 0.06, 1.0), (-0.55, 0.09, 0.7), (0.12, 0.025, 0.6)):
        strip = np.exp(-((rx - cx) / w) ** 2) * (ry < 0.25)
        col += 255 * a * strip[..., None]
    # modrý nádech od obkladu kolem
    col += np.array([0, 18, 45]) * (1 - np.abs(u))[..., None]
    fres = (1 - np.clip(nz, 0, 1)) ** 3
    col = col * (0.85 + 0.25 * fres[..., None])
    L = np.array([0.35, -0.8, 0.5]); L /= np.linalg.norm(L)
    spec = np.clip(rx * L[0] + ry * L[1] + rz * L[2], 0, 1)
    col += 255 * (spec ** 120)[..., None]
    return np.clip(col, 0, 255)


def shade_mat(nx, ny, nz, mat):
    chrome = chrome_studio(nx, ny, nz)
    steel = ra.chrome_col(nx, ny, nz, 'steel')
    red = ra.shade(nx, ny, nz, 'red')
    col = np.where((mat == 1)[..., None], steel, chrome)
    col = np.where((mat == 2)[..., None], red, col)
    return col


def ao(o, scene):
    """Jednoduché okluzní stínění – kouty jsou tmavší."""
    acc = 0
    for k in (4, 9, 16):
        d, _ = scene(o + 0)  # vzdálenost v bodě (≈0)
        acc += 0
    return 1.0


def render_faucet():
    nx, ny, nz, mat, alpha, o = march(FW, FH, lambda p: faucet_scene(p))
    col = shade_mat(nx, ny, nz, mat)
    col = ra.grain(col, alpha, 3)
    col = ra.droplets(col, alpha, 14, 2.5, 5.5, 71, 0.9)
    img = ra.rgba(col, alpha)
    img.save(os.path.join(OUT, 'faucet_body.webp'), 'WEBP', quality=92, method=6)
    # ústí výtoku v obrázku
    sx, sy = to_screen((OUT_X, BODY_Y + RB + 50, 0))
    print(f'FAUCET size {FW}x{FH} nozzle x={sx / FW:.4f} y={sy / FH:.4f}')


def render_wheel_frames(n=6):
    """Ruční kolo ve fázích otočení (4 paprsky -> stačí 90°)."""
    frames = []
    for i in range(n):
        ang = i * (np.pi / 2) / n
        def scene(p):
            w = sd_wheel(p, ang)
            return w, np.full(w.shape, 2)
        nx, ny, nz, mat, alpha, o = march(FW, int(BODY_Y), scene, 130)
        frames.append((shade_mat(nx, ny, nz, mat), alpha))
    # společný výřez
    a_all = np.max([f[1] for f in frames], axis=0)
    ys, xs = np.where(a_all > 0.01)
    x0, x1, y0, y1 = xs.min() - 2, xs.max() + 3, max(0, ys.min() - 2), ys.max() + 3
    for i, (c, a) in enumerate(frames):
        ra.rgba(c[y0:y1, x0:x1], a[y0:y1, x0:x1]).save(os.path.join(OUT, f'faucet_wheel_{i}.webp'), 'WEBP', quality=92, method=6)
    print(f'WHEEL crop x={x0 / FW:.4f} y={y0 / FH:.4f} w={(x1 - x0) / FW:.4f} h={(y1 - y0) / FH:.4f} frames={n}')


# ------------------------------------------------------------------------------- vpusť
def render_drain():
    W, H = 300, 200
    C = np.array([W / 2, 0.0, 0.0])
    PITCH_D = np.radians(32)
    ca, sa = np.cos(PITCH_D), np.sin(PITCH_D)
    cy_screen = H / 2

    def obj(p):
        x, y, z = p[..., 0] - W / 2, p[..., 1] - cy_screen, p[..., 2]
        return np.stack([x, y * ca - z * sa, y * sa + z * ca], -1)

    def scene(q):
        # rovina vpusti je xz (osa y = svislá v objektu, kladná dolů)
        x, y, z = q[..., 0], q[..., 1], q[..., 2]
        r = length(x, z)
        rim = length(r - 122, y + 3) - 15
        plate_disc = np.maximum(r - 112, np.abs(y - 4) - 5)
        cell, bar = 30.0, 9.0
        mx = np.abs(np.mod(x + cell / 2, cell) - cell / 2)
        mz = np.abs(np.mod(z + cell / 2, cell) - cell / 2)
        # díra = čtverec uprostřed buňky
        hole = np.maximum(mx, mz) - (cell - bar) / 2
        plate = np.maximum(plate_disc, -hole)
        pit = np.maximum(r - 112, np.abs(y - 45) - 3)  # tmavé dno pod mříží
        # neviditelná podlaha: zakryje vnější stěnu jímky (vpusť je zapuštěná do podlahy)
        floor = np.maximum(118 - r, -y)
        d = np.minimum(np.minimum(np.minimum(rim, plate), pit), floor)
        mat = np.where(rim <= np.minimum(plate, pit), 0, np.where(plate <= pit, 1, 3))
        mat = np.where(floor < np.minimum(np.minimum(rim, plate), pit), 4, mat)
        return d, mat

    yy, xx = np.mgrid[0:H, 0:W].astype(float) + 0.5
    P0 = np.stack([xx, yy, np.full_like(xx, 500.0)], -1)
    t = np.zeros(xx.shape); hit = np.zeros(xx.shape, bool)
    for _ in range(200):
        p = P0.copy(); p[..., 2] -= t
        d, _ = scene(obj(p))
        hit |= d < 0.05
        t = np.where(hit, t, t + np.maximum(d * 0.85, 0.05))
    p = P0.copy(); p[..., 2] -= t
    q = obj(p)
    d, mat = scene(q)
    hit &= t < 990
    e = 0.4
    def g(axis):
        dp = np.zeros(3); dp[axis] = e
        return scene(q + dp)[0] - scene(q - dp)[0]
    nx, ny, nz = g(0), g(1), g(2)
    sny = ny * ca + nz * sa
    snz = -ny * sa + nz * ca
    nn = length(nx, sny, snz) + 1e-9
    nx, sny, snz = nx / nn, sny / nn, snz / nn
    rim = chrome_studio(nx, sny, snz)
    cast = ra.chrome_col(nx, sny, snz, 'dark') * 1.15
    pit = np.zeros_like(rim) + np.array([4, 9, 18])
    col = np.where((mat == 0)[..., None], rim, np.where((mat == 1)[..., None], cast, pit))
    vis = hit & (mat != 4)
    # otvory mřížky: tmavá jímka (paprsek protne rovinu mříže uvnitř kruhu)
    pa = obj(P0)
    pb = obj(P0 - np.array([0, 0, 1.0]))
    dy = pb[..., 1] - pa[..., 1]
    tt = (4 - pa[..., 1]) / np.where(np.abs(dy) < 1e-6, 1e-6, dy)
    cross = pa + (pb - pa) * tt[..., None]
    inside = length(cross[..., 0], cross[..., 2]) < 112
    holes = inside & ~vis
    col = np.where(holes[..., None], np.array([3, 7, 14]) + 10 * (1 - length(cross[..., 0], cross[..., 2]) / 112)[..., None], col)
    vis = vis | holes
    alpha = np.asarray(Image.fromarray((vis * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(0.6)), float) / 255
    col = ra.grain(col, alpha, 4)
    col = ra.droplets(col, alpha * (mat != 3), 30, 2.5, 6, 81, 0.9)
    ra.rgba(col, alpha).save(os.path.join(OUT, 'drain_persp.webp'), 'WEBP', quality=90, method=6)
    print('DRAIN', W, H)


# ------------------------------------------------------------------------------- kaustiky
def periodic_noise(n, freq, seed):
    r = np.random.default_rng(seed)
    f = np.fft.fftfreq(n)
    fx, fy = np.meshgrid(f, f)
    rad = np.sqrt(fx * fx + fy * fy)
    amp = np.exp(-((rad - freq / n) ** 2) / (2 * (freq / n * 0.35) ** 2))
    spec = amp * np.exp(2j * np.pi * r.random((n, n)))
    out = np.real(np.fft.ifft2(spec))
    return (out - out.min()) / (out.max() - out.min())


def render_caustics():
    N = 256
    a = periodic_noise(N, 6, 1)
    b = periodic_noise(N, 9, 2)
    c = 1 - np.abs(a - 0.5) * 2
    d2 = 1 - np.abs(b - 0.5) * 2
    v = np.clip((c ** 7) * 0.8 + (d2 ** 9) * 0.6, 0, 1)
    v = np.asarray(Image.fromarray((v * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(0.8)), float) / 255
    img = np.dstack([np.full((N, N), 210), np.full((N, N), 240), np.full((N, N), 255), (v * 255)]).astype(np.uint8)
    Image.fromarray(img, 'RGBA').save(os.path.join(OUT, 'water_caustics.webp'), 'WEBP', quality=90, method=6)


# ------------------------------------------------------------------------------- obkladová stěna
def render_tiles():
    W, H = 720, 1600
    T = 150          # obkladačka ~ 85 dp
    G = 5            # spára
    BEV = 9
    yy, xx = np.mgrid[0:H, 0:W].astype(float)
    gx, gy = np.mod(xx, T), np.mod(yy, T)
    ix, iy = (xx // T).astype(int), (yy // T).astype(int)
    # vzdálenost k okraji obkladačky
    ex = np.minimum(gx - G / 2, T - G / 2 - gx)
    ey = np.minimum(gy - G / 2, T - G / 2 - gy)
    grout = (ex < 0) | (ey < 0)
    # normála zkosené hrany
    nx = np.where(ex < BEV, np.where(gx < T / 2, -1, 1) * (1 - ex / BEV), 0) * 0.55
    ny = np.where(ey < BEV, np.where(gy < T / 2, -1, 1) * (1 - ey / BEV), 0) * 0.55
    nx = np.where(ex < ey, nx, 0 * nx + np.where(ex < BEV, nx, 0) * 0)
    ny = np.where(ey <= ex, ny, 0)
    nz = np.sqrt(np.clip(1 - nx * nx - ny * ny, 0.05, 1))
    # základní barva: hluboká modrá, každá obkladačka trochu jiná
    r = np.random.default_rng(5)
    var = r.normal(0, 1, (H // T + 2, W // T + 2))[iy, ix]
    base = np.array([20, 46, 84]) * (1 + 0.06 * var[..., None])
    k = (yy / H)[..., None]
    base = base * (1.15 - 0.55 * k)
    # lesk glazury: odraz světla od lampy vpravo nahoře
    L = np.array([0.45, -0.75, 0.5]); L /= np.linalg.norm(L)
    spec = np.clip(nx * L[0] + ny * L[1] + nz * L[2], 0, 1)
    lamp = np.exp(-(((xx - W * 0.82) / (W * 0.55)) ** 2 + ((yy - H * 0.05) / (H * 0.45)) ** 2))
    col = base * (0.75 + 0.35 * spec[..., None]) + (255 * (spec ** 30) * 0.5)[..., None] * (0.3 + 0.7 * lamp[..., None])
    # široký lesklý odraz na glazuře (pruh)
    sheen = np.exp(-((xx * 0.6 + yy * 0.35 - W * 0.9) / (W * 0.18)) ** 2) * 0.18
    col = col + (np.array([120, 170, 230]) * sheen[..., None])
    col = col + np.array([40, 90, 160]) * (lamp[..., None] * 0.25)
    # spáry – tmavé, matné
    col = np.where(grout[..., None], np.array([10, 18, 30]) * (1.2 - 0.6 * k), col)
    # jemný šum glazury
    n = ra.value_noise(H, W, 6, 9) - 0.5
    col = col * (1 + 0.05 * n[..., None])
    # stékající voda a kapky
    col = ra.droplets(col, np.ones((H, W)), 90, 3, 7, 61, 0.55)
    col = ra.droplets(col, np.ones((H, W)), 30, 7, 13, 62, 0.6)
    vx, vy = (xx / W - 0.5) * 1.5, (yy / H - 0.45) * 1.2
    col = col * np.clip(1 - 0.5 * (vx * vx + vy * vy), 0.45, 1)[..., None]
    Image.fromarray(np.clip(col, 0, 255).astype(np.uint8)).save(os.path.join(OUT, 'bg_wet.webp'), 'WEBP', quality=82, method=6)


if __name__ == '__main__':
    what = set(os.environ.get('ONLY', 'faucet,wheel,drain,caustics,tiles').split(','))
    if 'faucet' in what: render_faucet()
    if 'wheel' in what: render_wheel_frames()
    if 'drain' in what: render_drain()
    if 'caustics' in what: render_caustics()
    if 'tiles' in what: render_tiles()
