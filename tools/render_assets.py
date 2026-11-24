"""Renderer kovových prvků pro Docházku Kutil (chromové trubky, kolena, objímky, šoupátko, mříž, pozadí).

Vše se počítá fyzikálně jednoduchým modelem: normála povrchu -> odraz okolí (nebe nahoře světlé,
horizont tmavý, podlaha modrošedá) + odlesk světla zleva shora. Výstup: WebP s průhledností.
"""
import sys, os
import numpy as np
from PIL import Image, ImageFilter

OUT = sys.argv[1] if len(sys.argv) > 1 else '/tmp/assets'
os.makedirs(OUT, exist_ok=True)
rng = np.random.default_rng(7)

L = np.array([-0.35, -0.85, 0.45]); L = L / np.linalg.norm(L)

ENV_U = np.array([-1.0, -0.6, -0.3, -0.08, 0.0, 0.1, 0.45, 1.0])
ENV_C = np.array([
    [238, 243, 248], [214, 224, 236], [150, 166, 188], [78, 92, 114],
    [30, 38, 54], [52, 64, 84], [98, 114, 138], [140, 154, 174]], float)


def env(u):
    out = np.zeros(u.shape + (3,))
    for c in range(3):
        out[..., c] = np.interp(u, ENV_U, ENV_C[:, c])
    return out


def shade(nx, ny, nz, mat='chrome'):
    """Barva podle normály. nx, ny ve směru obrazovky (y dolů), nz k divákovi."""
    rx, ry, rz = 2 * nz * nx, 2 * nz * ny, 2 * nz * nz - 1
    spec = np.clip(rx * L[0] + ry * L[1] + rz * L[2], 0, 1)
    diff = np.clip(nx * L[0] + ny * L[1] + nz * L[2], 0, 1)
    e = env(ry + 0.15 * rx)
    if mat == 'chrome':
        col = e * 0.92 + 255 * (spec ** 60) * 0.9 + 255 * (spec ** 8) * 0.12
    elif mat == 'steel':  # matnější tmavší (objímky)
        col = e * 0.75 + 30 + 255 * (spec ** 25) * 0.55
    elif mat in ('brass', 'copper'):
        tint = np.array([235, 180, 95]) if mat == 'brass' else np.array([222, 128, 72])
        lum = (e.mean(axis=-1, keepdims=True) / 255.0)
        col = tint * (0.22 + 0.45 * lum + 0.75 * diff[..., None]) + 255 * (spec[..., None] ** 40) * 0.8
        return np.clip(col, 0, 255)
    elif mat == 'red':
        base = np.array([200, 28, 22])
        col = base * (0.28 + 0.8 * diff[..., None]) + 255 * (spec[..., None] ** 50) * 0.85 + e * 0.08
        return np.clip(col, 0, 255)
    elif mat == 'dark':  # černá litina (mříž)
        col = e * 0.32 + 12 + 255 * (spec ** 30) * 0.35
    else:
        raise ValueError(mat)
    if col.ndim == 2:
        col = np.repeat(col[..., None], 3, -1)
    return np.clip(col, 0, 255)


def fix(col):
    """Pomocná – shade vrací pro chrome už (h,w,3) přes env, sjednotit."""
    return col


def chrome_col(nx, ny, nz, mat='chrome'):
    rx, ry, rz = 2 * nz * nx, 2 * nz * ny, 2 * nz * nz - 1
    spec = np.clip(rx * L[0] + ry * L[1] + rz * L[2], 0, 1)[..., None]
    e = env(ry + 0.15 * rx)
    if mat == 'chrome':
        # ocel s nádechem do modra jako na předloze
        col = e * np.array([0.9, 0.94, 1.0]) + 255 * (spec ** 60) * 0.9 + 255 * (spec ** 8) * 0.12
    elif mat == 'steel':
        col = e * 0.72 + 28 + 255 * (spec ** 25) * 0.5
    elif mat == 'dark':
        col = e * 0.30 + 10 + 255 * (spec ** 30) * 0.30
    else:
        return shade(nx, ny, nz, mat)
    return np.clip(col, 0, 255)


def rgba(col, alpha):
    a = np.clip(alpha, 0, 1)
    img = np.dstack([np.clip(col, 0, 255), a * 255]).astype(np.uint8)
    return Image.fromarray(img, 'RGBA')


def grain(col, mask, amount=6, streak_axis=None):
    n = rng.normal(0, 1, col.shape[:2])
    if streak_axis is not None:
        im = Image.fromarray(((n * 40) + 128).clip(0, 255).astype(np.uint8))
        im = im.filter(ImageFilter.BoxBlur(0))
        k = (12, 0) if streak_axis == 'x' else (0, 12)
        im = im.resize((max(1, col.shape[1] // (6 if streak_axis == 'x' else 1)), max(1, col.shape[0] // (6 if streak_axis == 'y' else 1))), Image.BILINEAR)
        im = im.resize((col.shape[1], col.shape[0]), Image.BILINEAR)
        n = (np.asarray(im, float) - 128) / 40
    return col + (n * amount)[..., None] * mask[..., None]


def droplets(col, alpha, count, rmin, rmax, seed=1, strength=1.0):
    """Kapky vody: světlejší střed, tmavý spodní okraj, bílý odlesk vlevo nahoře."""
    r = np.random.default_rng(seed)
    h, w = alpha.shape
    yy, xx = np.mgrid[0:h, 0:w]
    for _ in range(count):
        cx, cy = r.uniform(0, w), r.uniform(0, h)
        rad = r.uniform(rmin, rmax)
        if alpha[int(min(cy, h - 1)), int(min(cx, w - 1))] < 0.9:
            continue
        sx = rad * r.uniform(0.8, 1.05)
        sy = rad * r.uniform(0.9, 1.2)
        x0, x1 = int(max(0, cx - sx - 3)), int(min(w, cx + sx + 3))
        y0, y1 = int(max(0, cy - sy - 3)), int(min(h, cy + sy + 3))
        if x1 <= x0 or y1 <= y0:
            continue
        X = (xx[y0:y1, x0:x1] - cx) / sx
        Y = (yy[y0:y1, x0:x1] - cy) / sy
        d = np.sqrt(X * X + Y * Y)
        inside = np.clip((1 - d) * rad, 0, 1)
        nz = np.sqrt(np.clip(1 - d * d, 0, 1))
        rim = np.clip(Y, 0, 1) * (d > 0.55) * inside
        top = np.clip(-Y, 0, 1) * inside
        hl = np.exp(-(((X + 0.35) / 0.22) ** 2 + ((Y + 0.4) / 0.18) ** 2))
        shadow = np.exp(-((X - 0.15) ** 2 + (Y - 0.35) ** 2) / 0.5) * (d > 0.9) * (d < 1.5)
        patch = col[y0:y1, x0:x1]
        patch[:] = patch * (1 - 0.35 * strength * rim[..., None]) + 40 * strength * (inside * (1 - nz) * 0.5)[..., None]
        patch[:] = patch * (1 - 0.18 * strength * shadow[..., None])
        patch[:] = patch + (255 - patch) * (0.95 * strength * hl * inside)[..., None]
        patch[:] = patch + (255 - patch) * (0.10 * strength * top)[..., None]
    return col


# ---------------------------------------------------------------- trubky
D = 64          # průměr trubky v px (kreslí se cca na 18 dp)
A = D / 2
RB = 52         # poloměr ohybu (osa kolena)
S = int(RB + A)  # velikost obrázku kolena


def save(img, name, q=92):
    img.save(os.path.join(OUT, name + '.webp'), 'WEBP', quality=q, method=6)


def straight(length, vertical=False, mat='chrome', drops=6, seed=3):
    h, w = (length, D) if vertical else (D, length)
    yy, xx = np.mgrid[0:h, 0:w].astype(float)
    t = ((xx + 0.5 - A) / A) if vertical else ((yy + 0.5 - A) / A)
    nz = np.sqrt(np.clip(1 - t * t, 0, 1))
    nx, ny = (t, 0 * t) if vertical else (0 * t, t)
    col = chrome_col(nx, ny, nz, mat)
    alpha = np.clip((1 - np.abs(t)) * A, 0, 1)
    col = col * (0.55 + 0.45 * nz[..., None] ** 0.35)  # tmavší obrys
    col = grain(col, alpha, 5, 'y' if vertical else 'x')
    col = droplets(col, alpha, drops, 2.5, 5.5, seed)
    return rgba(col, alpha)


def elbow(corner, mat='chrome', seed=5):
    yy, xx = np.mgrid[0:S, 0:S].astype(float) + 0.5
    cx = S if corner in ('tl', 'bl') else 0
    cy = S if corner in ('tl', 'tr') else 0
    dx, dy = xx - cx, yy - cy
    r = np.sqrt(dx * dx + dy * dy) + 1e-6
    d = (r - RB) / A
    nz = np.sqrt(np.clip(1 - d * d, 0, 1))
    nx, ny = dx / r * d, dy / r * d
    col = chrome_col(nx, ny, nz, mat)
    alpha = np.clip((1 - np.abs(d)) * A, 0, 1)
    col = col * (0.55 + 0.45 * nz[..., None] ** 0.35)
    col = grain(col, alpha, 4)
    col = droplets(col, alpha, 3, 2.5, 5, seed)
    return rgba(col, alpha)


def clamp(vertical=False):
    """Objímka s šroubem – širší ocelový prstenec napříč trubkou."""
    T = A + 7
    W = 30
    h, w = (W, int(2 * T)) if vertical else (int(2 * T), W)
    yy, xx = np.mgrid[0:h, 0:w].astype(float) + 0.5
    t = ((xx - T) / T) if vertical else ((yy - T) / T)
    u = ((yy - W / 2) / (W / 2)) if vertical else ((xx - W / 2) / (W / 2))
    nz = np.sqrt(np.clip(1 - t * t, 0, 1))
    # zaoblení hran prstence
    bev = np.clip((1 - np.abs(u)) * 4, 0, 1)
    nu = np.sign(u) * (1 - bev) * 0.8
    if vertical:
        nx, ny = t * bev, nu
    else:
        nx, ny = nu, t * bev
    nzz = np.sqrt(np.clip(1 - nx * nx - ny * ny, 0.02, 1))
    col = chrome_col(nx, ny, nzz, 'steel')
    col = col * (0.55 + 0.45 * nz[..., None] ** 0.35)
    alpha = np.clip((1 - np.abs(t)) * T, 0, 1) * np.clip((1 - np.abs(u)) * W / 2, 0, 1)
    # šroub uprostřed
    bx, by = w / 2, h / 2
    br = 6.5
    X, Y = (xx - bx) / br, (yy - by) / br
    dd = np.sqrt(X * X + Y * Y)
    m = dd < 1
    bz = np.sqrt(np.clip(1 - dd * dd, 0, 1))
    bc = chrome_col(X * 0.8, Y * 0.8, np.sqrt(np.clip(1 - (X * 0.8) ** 2 - (Y * 0.8) ** 2, 0, 1)), 'chrome')
    slot = (np.abs(X - Y) < 0.22) & m
    bc[slot] = bc[slot] * 0.35
    col[m] = bc[m] * 0.9
    ring = (dd > 1) & (dd < 1.35)
    col[ring] *= 0.55
    return rgba(col, alpha)


def valve():
    """Šoupátko: červené ruční kolo (torus nakloněný k divákovi), mosazné vřeteno a hlava."""
    W, H = 210, 168
    yy, xx = np.mgrid[0:H, 0:W].astype(float) + 0.5
    P0 = np.stack([xx, yy, np.full_like(xx, 200.0)], -1)
    dirv = np.array([0, 0, -1.0])
    C = np.array([W / 2, 44.0, 0])
    Rw, rw = 84.0, 11.0
    phi = np.radians(18)  # jak moc je kolo naklopené k nám
    ca, sa = np.cos(phi), np.sin(phi)

    def to_local(p):
        q = p - C
        # osa kola = svislá y nakloněná k divákovi (rotace kolem x)
        y = q[..., 1] * ca - q[..., 2] * sa
        z = q[..., 1] * sa + q[..., 2] * ca
        return np.stack([q[..., 0], y, z], -1)

    def sd_torus(p):
        l = to_local(p)
        qx = np.sqrt(l[..., 0] ** 2 + l[..., 2] ** 2) - Rw
        return np.sqrt(qx * qx + l[..., 1] ** 2) - rw

    def sd_spokes(p):
        l = to_local(p)
        best = np.full(l.shape[:-1], 1e9)
        for k in range(3):
            ang = np.radians(90 + k * 120)
            dx, dz = np.cos(ang), np.sin(ang)
            proj = np.clip(l[..., 0] * dx + l[..., 2] * dz, 0, Rw)
            ex = l[..., 0] - proj * dx
            ez = l[..., 2] - proj * dz
            best = np.minimum(best, np.sqrt(ex * ex + l[..., 1] ** 2 + ez * ez) - 6.5)
        hub = np.sqrt(l[..., 0] ** 2 + l[..., 2] ** 2)
        hubd = np.maximum(hub - 17, np.abs(l[..., 1]) - 9)
        return np.minimum(best, hubd)

    def sd_stem(p):
        q = p - np.array([W / 2, 0, 0])
        rad = np.sqrt(q[..., 0] ** 2 + q[..., 2] ** 2)
        stem = np.maximum(rad - 10, np.maximum(44 - q[..., 1], q[..., 1] - 175))
        # hlava ventilu (šestihran přibližně válcem) + matice
        bon = np.maximum(rad - 24, np.maximum(105 - q[..., 1], q[..., 1] - 150))
        nut = np.maximum(rad - 17, np.maximum(88 - q[..., 1], q[..., 1] - 106))
        return np.minimum(stem, np.minimum(bon, nut))

    def scene(p):
        a, b, c = sd_torus(p), sd_spokes(p), sd_stem(p)
        d = np.minimum(np.minimum(a, b), c)
        mat = np.where(c <= np.minimum(a, b), 2, np.where(a <= b, 0, 1))
        return d, mat

    t = np.zeros(xx.shape)
    hit = np.zeros(xx.shape, bool)
    for _ in range(140):
        p = P0 + t[..., None] * dirv
        d, _m = scene(p)
        hit |= d < 0.05
        t = np.where(hit, t, t + np.maximum(d, 0.05))
        if t.min() > 420:
            break
    p = P0 + t[..., None] * dirv
    dist, mat = scene(p)
    hit &= t < 400
    e = 0.4
    nx = scene(p + [e, 0, 0])[0] - scene(p - [e, 0, 0])[0]
    ny = scene(p + [0, e, 0])[0] - scene(p - [0, e, 0])[0]
    nz = scene(p + [0, 0, e])[0] - scene(p - [0, 0, e])[0]
    nn = np.sqrt(nx * nx + ny * ny + nz * nz) + 1e-9
    nx, ny, nz = nx / nn, ny / nn, nz / nn
    red = shade(nx, ny, nz, 'red')
    brass = shade(nx, ny, nz, 'brass')
    col = np.where((mat == 2)[..., None], brass, red)
    # spodní polovina kola je za vřetenem? – pořadí řeší raymarching sám
    a_img = Image.fromarray((hit * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(0.6))
    alpha = np.asarray(a_img, float) / 255
    return rgba(col, alpha)


def grate():
    """Kulatá kanálová vpusť s mřížkou – pohled shora."""
    N = 420
    yy, xx = np.mgrid[0:N, 0:N].astype(float) + 0.5
    c = N / 2
    dx, dy = xx - c, yy - c
    r = np.sqrt(dx * dx + dy * dy) + 1e-6
    Ro, Ri = 200, 160
    # vnější prstenec – zaoblený
    rc, rw = (Ro + Ri) / 2, (Ro - Ri) / 2
    d = (r - rc) / rw
    nz = np.sqrt(np.clip(1 - d * d * 0.85, 0, 1))
    col = chrome_col(dx / r * d * 0.9, dy / r * d * 0.9, nz, 'steel')
    alpha = np.clip(Ro - r, 0, 1)
    # vnitřek: mříž se čtvercovými otvory
    inner = r < Ri
    cell, bar = 44, 13
    gx = np.mod(xx - c + cell / 2, cell)
    gy = np.mod(yy - c + cell / 2, cell)
    ex = np.minimum(gx, cell - gx)  # vzdálenost od mřížky
    ey = np.minimum(gy, cell - gy)
    hole = (ex > bar / 2) & (ey > bar / 2)
    # normála příčlí – zkosení
    bx = np.where(ex <= bar / 2, (gx - (cell / 2 if False else 0)), 0)
    sx = np.where(ex < bar / 2, np.sign(np.where(gx < cell / 2, -1, 1)) * (ex / (bar / 2)), 0) * -0.6
    sy = np.where(ey < bar / 2, np.sign(np.where(gy < cell / 2, -1, 1)) * (ey / (bar / 2)), 0) * -0.6
    nzg = np.sqrt(np.clip(1 - sx * sx - sy * sy, 0.05, 1))
    gcol = chrome_col(sx * 0.6, sy * 0.6, nzg, 'dark')
    holecol = np.zeros_like(gcol) + np.array([6, 10, 18])
    # odlesk vody v otvorech
    holecol += (np.exp(-((dx + 40) ** 2 + (dy + 60) ** 2) / 9000) * 30)[..., None]
    g = np.where(hole[..., None], holecol, gcol)
    col = np.where(inner[..., None], g, col)
    # stín pod prstencem dovnitř
    sh = np.clip((r - (Ri - 14)) / 14, 0, 1) * inner
    col = col * (1 - 0.5 * sh[..., None])
    col = grain(col, alpha, 6)
    col = droplets(col, alpha * (~hole | ~inner), 40, 3, 8, 11)
    return rgba(col, alpha)


def screw():
    N = 40
    yy, xx = np.mgrid[0:N, 0:N].astype(float) + 0.5
    X, Y = (xx - N / 2) / (N / 2 - 1), (yy - N / 2) / (N / 2 - 1)
    d = np.sqrt(X * X + Y * Y)
    nz = np.sqrt(np.clip(1 - (d * 0.75) ** 2, 0, 1))
    col = chrome_col(X * 0.75, Y * 0.75, nz, 'chrome')
    slot = (np.abs(X * 0.7 + Y * 0.7) < 0.14) & (d < 0.8)
    col[slot] *= 0.3
    col *= (0.6 + 0.4 * nz[..., None])
    alpha = np.clip((1 - d) * (N / 2), 0, 1)
    return rgba(col, alpha)


def value_noise(h, w, cell, seed):
    r = np.random.default_rng(seed)
    g = r.random((h // cell + 3, w // cell + 3))
    im = Image.fromarray((g * 255).astype(np.uint8)).resize((w + 2 * cell, h + 2 * cell), Image.BICUBIC)
    return np.asarray(im, float)[cell:cell + h, cell:cell + w] / 255


def background():
    W, H = 1080, 2400
    yy, xx = np.mgrid[0:H, 0:W].astype(float)
    top = np.array([24, 50, 86]); bot = np.array([8, 18, 36])
    k = (yy / H)[..., None]
    col = top * (1 - k) + bot * k
    n = sum(value_noise(H, W, c, s) * wgt for c, s, wgt in [(300, 1, 0.5), (120, 2, 0.35), (40, 3, 0.22), (10, 4, 0.12), (3, 5, 0.08)])
    n = (n - n.mean()) / n.std()
    col = col * (1 + 0.16 * n[..., None])
    # jemné svislé stékající pruhy
    streak = value_noise(H, W // 8 * 8, 8, 9)
    st = Image.fromarray((streak * 255).astype(np.uint8)).resize((W // 6, 12)).resize((W, H), Image.BICUBIC)
    st = (np.asarray(st, float) / 255 - 0.5)
    col = col * (1 + 0.08 * st[..., None])
    # vinětace
    vx, vy = (xx / W - 0.5) * 1.6, (yy / H - 0.45) * 1.2
    v = np.clip(1 - 0.45 * (vx * vx + vy * vy), 0.5, 1)
    col = col * v[..., None]
    col = grain(col, np.ones((H, W)), 3)
    alpha = np.ones((H, W))
    col = droplets(col, alpha, 260, 3, 6, 21, 0.35)
    col = droplets(col, alpha, 120, 6, 12, 22, 0.5)
    col = droplets(col, alpha, 28, 12, 22, 23, 0.55)
    Image.fromarray(np.clip(col, 0, 255).astype(np.uint8)).save(os.path.join(OUT, 'bg_wet.webp'), 'WEBP', quality=80, method=6)


def brushed():
    """Bezešvá textura kartáčovaného hliníku pro světlé karty (512×512, opakuje se)."""
    N = 512
    r = np.random.default_rng(31)
    n = r.normal(0, 1, (N, N))
    # rozmazání podél x s periodickým okrajem -> bezešvé
    k = np.ones(61) / 61
    f = np.fft.rfft(n, axis=1) * np.fft.rfft(np.pad(k, (0, N - len(k))), N)
    n = np.fft.irfft(f, N, axis=1)
    n = (n - n.mean()) / n.std()
    big = value_noise(N, N, 128, 32)
    col = 228 + 6 * n + 8 * (big - 0.5)
    col = np.dstack([col - 4, col - 1, col + 3])
    Image.fromarray(np.clip(col, 0, 255).astype(np.uint8)).save(os.path.join(OUT, 'tex_brushed.webp'), 'WEBP', quality=85, method=6)


def drops_overlay():
    """Průhledná vrstva kapek na zelený / oranžový panel."""
    W, H = 900, 420
    col = np.full((H, W, 3), 128.0)
    alpha = np.ones((H, W))
    acc = np.zeros((H, W))
    r = np.random.default_rng(41)
    yy, xx = np.mgrid[0:H, 0:W]
    out = np.zeros((H, W, 4))
    for i in range(110):
        cx, cy = r.uniform(0, W), r.uniform(0, H)
        if r.random() < 0.65:  # víc kapek vpravo nahoře jako na předloze
            cx = r.uniform(W * 0.55, W); cy = r.uniform(0, H * 0.55)
        rad = r.uniform(2.5, 7.5)
        X, Y = (xx - cx) / rad, (yy - cy) / (rad * 1.1)
        d = np.sqrt(X * X + Y * Y)
        m = d < 1
        hl = np.exp(-(((X + 0.35) / 0.25) ** 2 + ((Y + 0.4) / 0.2) ** 2)) * m
        rim = np.clip(Y, 0, 1) * m * (d > 0.5)
        out[..., :3] = np.maximum(out[..., :3], (hl * 255)[..., None])
        out[..., 3] = np.maximum(out[..., 3], np.maximum(hl * 0.95, rim * 0.35))
        out[..., 3] = np.maximum(out[..., 3], m * 0.06)
    outc = np.clip(out[..., :3], 0, 255)
    # tmavý okraj = černá s alfou tam, kde není odlesk
    img = np.dstack([outc, np.clip(out[..., 3], 0, 1) * 255]).astype(np.uint8)
    Image.fromarray(img, 'RGBA').save(os.path.join(OUT, 'drops_overlay.webp'), 'WEBP', quality=90, method=6)


if __name__ == '__main__':
    save(straight(320), 'pipe_h')
    save(straight(320, vertical=True, seed=4), 'pipe_v')
    for c in ('tl', 'tr', 'bl', 'br'):
        save(elbow(c, seed=hash(c) % 100), 'pipe_elbow_' + c)
    save(clamp(False), 'pipe_clamp_h')
    save(clamp(True), 'pipe_clamp_v')
    save(straight(320, mat='copper', drops=3), 'pipe_copper_h')
    save(valve(), 'pipe_valve')
    save(grate(), 'drain_grate', 88)
    save(screw(), 'screw')
    background()
    brushed()
    drops_overlay()
    print('ok')
