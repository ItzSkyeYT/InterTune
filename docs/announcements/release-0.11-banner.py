#!/usr/bin/env python3
"""The 0.11 banner: the brand's waveform on the dark coral ground, a glass "0.11" over it, and two
phones showing the new look.

Usage: release-0.11-banner.py <repo root> <out folder> [--caption "text"]
Reads two store screenshots from the repo (fastlane phoneScreenshots 01 = Home with Liquid glass,
02 = Player) and draws the launcher icon itself. Writes release-0.11.png (1600x900) and
release-0.11.webp, the picture at the top of the 0.11 announcement. Needs Pillow, numpy and Noto Sans.
Everything is drawn at twice the size and scaled down, so edges are smooth.
"""
import math, sys
import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

S = 2                                   # supersampling
W, H = 1600 * S, 900 * S
CORAL = (237, 85, 100)                  # #ed5564, the launcher icon's colour
BLACK = '/usr/share/fonts/noto/NotoSans-Black.ttf'
BOLD = '/usr/share/fonts/noto/NotoSans-Bold.ttf'
MEDIUM = '/usr/share/fonts/noto/NotoSans-Medium.ttf'


def background():
    """Dark diagonal ground with three soft lights. Float RGB, 0..1."""
    y, x = np.mgrid[0:H, 0:W].astype(np.float32)
    t = (x / W * 0.55 + y / H * 0.45)[..., None]
    a, b, c = np.array([0x12, 0x0d, 0x15]) / 255, np.array([0x1f, 0x13, 0x1d]) / 255, np.array([0x2e, 0x18, 0x22]) / 255
    img = np.where(t < 0.5, a + (b - a) * (t / 0.5), b + (c - b) * ((t - 0.5) / 0.5)).astype(np.float32)

    def light(cx, cy, rx, ry, colour, strength):
        d = ((x - cx * W) / (rx * W)) ** 2 + ((y - cy * H) / (ry * H)) ** 2
        g = np.exp(-d * 2.2)[..., None] * strength
        return img + (np.array(colour) / 255 - img) * g

    img = light(0.66, 0.50, 0.42, 0.55, CORAL, 0.42)
    img = light(0.10, 0.95, 0.35, 0.50, (120, 44, 120), 0.34)
    img = light(1.00, 0.02, 0.30, 0.42, (255, 132, 92), 0.26)
    return np.clip(img, 0, 1)


def waveform(base):
    """The brand's bars, across the whole width, brighter to the right."""
    layer = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    n, pitch = 58, W / 58
    bar = pitch * 0.40
    for i in range(n):
        u = i / (n - 1)
        env = 0.30 + 0.70 * abs(math.sin(u * math.pi * 2.6 + 0.4)) * (0.55 + 0.45 * math.sin(u * math.pi * 7.3 + 1.1) ** 2)
        env *= 0.55 + 0.45 * math.sin(u * math.pi) ** 0.6
        h = H * 0.62 * env
        cx, cy = pitch * (i + 0.5), H * 0.52
        alpha = int(255 * (0.16 + 0.62 * u ** 0.8))
        d.rounded_rectangle([cx - bar / 2, cy - h / 2, cx + bar / 2, cy + h / 2], radius=bar / 2, fill=CORAL + (alpha,))
    glow = layer.filter(ImageFilter.GaussianBlur(22 * S))
    out = Image.alpha_composite(base, glow)
    return Image.alpha_composite(out, layer)


def phone(path, height, angle):
    """A screenshot as a phone: round corners, a thin light edge, turned by [angle] degrees."""
    shot = Image.open(path).convert('RGB')
    w = round(height * shot.width / shot.height)
    shot = shot.resize((w, height), Image.LANCZOS)
    r = round(w * 0.105)
    mask = Image.new('L', (w, height), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, w - 1, height - 1], radius=r, fill=255)
    body = Image.new('RGBA', (w, height), (0, 0, 0, 0))
    body.paste(shot, (0, 0), mask)
    edge = Image.new('RGBA', (w, height), (0, 0, 0, 0))
    ImageDraw.Draw(edge).rounded_rectangle([1, 1, w - 2, height - 2], radius=r, outline=(255, 255, 255, 70), width=3 * S)
    body = Image.alpha_composite(body, edge)
    return body.rotate(angle, resample=Image.BICUBIC, expand=True)


def place(base, item, centre, shadow=0.55):
    """Composite [item] centred at [centre], over a soft shadow of itself."""
    x, y = round(centre[0] - item.width / 2), round(centre[1] - item.height / 2)
    alpha = item.getchannel('A')
    sh = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    dark = Image.new('RGBA', item.size, (6, 3, 8, 255))
    dark.putalpha(alpha.point(lambda v: int(v * shadow)))
    sh.paste(dark, (x + 18 * S, y + 30 * S), dark)
    base = Image.alpha_composite(base, sh.filter(ImageFilter.GaussianBlur(26 * S)))
    layer = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    layer.paste(item, (x, y), item)
    return Image.alpha_composite(base, layer)


def glass_text(base, text, font, origin):
    """[text] as a pane of glass over [base]: what is behind it shows through frosted, bent at the
    edges and a little brighter, with a rim that catches light from the top left."""
    mask = Image.new('L', (W, H), 0)
    ImageDraw.Draw(mask).text(origin, text, font=font, fill=255)
    m = np.asarray(mask, dtype=np.float32) / 255

    # Which way the edge faces, from a softened copy of the shape.
    soft = np.asarray(mask.filter(ImageFilter.GaussianBlur(9 * S)), dtype=np.float32) / 255
    gy, gx = np.gradient(soft)
    norm = np.sqrt(gx ** 2 + gy ** 2) + 1e-6

    # Frost, then bend it: near an edge the glass shows what lies a little further in.
    frost = np.asarray(base.convert('RGB').filter(ImageFilter.GaussianBlur(15 * S)), dtype=np.float32) / 255
    yy, xx = np.mgrid[0:H, 0:W]
    bend = 150 * S
    sx = np.clip(xx + gx * bend, 0, W - 1).astype(np.int32)
    sy = np.clip(yy + gy * bend, 0, H - 1).astype(np.int32)
    inside = frost[sy, sx]
    grey = inside.mean(axis=2, keepdims=True)
    inside = np.clip(grey + (inside - grey) * 1.35, 0, 1)             # vibrancy
    inside = inside * 1.12 + 0.085                                    # lift
    ramp = (1 - (yy - origin[1]) / (font.size * 1.1)).clip(0, 1)[..., None]
    inside = np.clip(inside + 0.07 * ramp, 0, 1)                      # lighter toward the top

    # The rim: a thin band just inside the outline, bright where it faces the light.
    eroded = np.asarray(mask.filter(ImageFilter.MinFilter(2 * (4 * S) + 1)), dtype=np.float32) / 255
    band = np.clip(m - eroded, 0, 1)
    facing = ((-gx / norm) * -0.62 + (-gy / norm) * -0.78)            # outward normal · light direction
    rim = band * (0.20 + 0.80 * np.clip(facing, 0, 1) ** 1.4)
    back = band * 0.30 * np.clip(-facing, 0, 1)                       # a fainter glint on the far side

    # A sheen falling across the pane from the upper left, and shade inside its far edge.
    diag = ((xx - origin[0]) * 0.55 + (yy - origin[1]) * 1.0) / (font.size * 1.05)
    sheen = np.exp(-((diag - 0.42) / 0.20) ** 2) * 0.13 + np.clip(0.55 - diag, 0, 1) * 0.07
    wide = np.clip(m - np.asarray(mask.filter(ImageFilter.MinFilter(2 * (13 * S) + 1)).filter(ImageFilter.GaussianBlur(5 * S)), dtype=np.float32) / 255, 0, 1)
    shade = wide * 0.34 * np.clip(-facing, 0, 1)
    inside = np.clip(inside + sheen[..., None], 0, 1) * (1 - shade[..., None])

    rgb = np.asarray(base.convert('RGB'), dtype=np.float32) / 255
    # Shadow of the pane on what is behind it.
    sh = np.asarray(ImageChops.offset(mask, 10 * S, 22 * S).filter(ImageFilter.GaussianBlur(20 * S)), dtype=np.float32) / 255
    rgb = rgb * (1 - 0.42 * sh[..., None] * (1 - m[..., None]))
    out = rgb * (1 - m[..., None]) + inside * m[..., None]
    out = out + (1 - out) * np.clip(rim + back, 0, 1)[..., None]
    return Image.fromarray((np.clip(out, 0, 1) * 255).astype(np.uint8), 'RGB').convert('RGBA')


def logo(size):
    """The launcher icon: white play shape with the letters cut out of it, on a coral tile."""
    q = 8
    v = 114.65
    k = size * q / v
    tile = Image.new('RGBA', (size * q, size * q), (0, 0, 0, 0))
    d = ImageDraw.Draw(tile)
    d.rounded_rectangle([0, 0, size * q - 1, size * q - 1], radius=size * q * 0.26, fill=CORAL + (255,))
    P = lambda x, y: ((x - 2.68) * k, (y + 3.33) * k)
    tri = [P(34, 26), P(34, 82), P(86, 54)]
    d.polygon(tri, fill='white')
    d.line(tri + [tri[0], tri[1]], fill='white', width=round(15 * k), joint='curve')
    for pts, wd in (([(36, 39), (45, 39), (45, 69), (36, 69)], 6), ([(54, 40), (54, 68)], 5), ([(70, 46), (63, 46), (63, 62), (70, 62)], 6)):
        xy = [P(*p) for p in pts]
        d.line(xy, fill=CORAL + (255,), width=round(wd * k), joint='curve')
        for p in (xy[0], xy[-1]):
            rr = wd * k / 2
            d.ellipse([p[0] - rr, p[1] - rr, p[0] + rr, p[1] + rr], fill=CORAL + (255,))
    return tile.resize((size, size), Image.LANCZOS)


def build(repo, caption):
    shots = f'{repo}/fastlane/metadata/android/en-US/images/phoneScreenshots'
    rgb = background()
    img = Image.fromarray((rgb * 255).astype(np.uint8), 'RGB').convert('RGBA')
    img = waveform(img)
    # The player behind, running off the edge; Home in front and whole, since its glass bars are the point.
    img = place(img, phone(f'{shots}/02.jpg', round(H * 0.92), -9), (W * 0.895, H * 0.54))
    img = place(img, phone(f'{shots}/01.jpg', round(H * 0.90), 5), (W * 0.700, H * 0.500))

    number = ImageFont.truetype(BLACK, 372 * S)
    img = glass_text(img, '0.11', number, (84 * S, 246 * S))

    d = ImageDraw.Draw(img)
    mark = logo(84 * S)
    img.alpha_composite(mark, (96 * S, 118 * S))
    d = ImageDraw.Draw(img)
    d.text((204 * S, 160 * S), 'InterTune', font=ImageFont.truetype(BOLD, 62 * S), fill=(255, 255, 255, 255), anchor='lm')
    if caption:
        d.text((98 * S, 752 * S), caption, font=ImageFont.truetype(MEDIUM, 37 * S), fill=(255, 232, 235, 240), anchor='lm')

    # Darker corners, then a little grain so the soft gradients do not band once compressed.
    a = np.asarray(img.convert('RGB'), dtype=np.float32) / 255
    y, x = np.mgrid[0:H, 0:W].astype(np.float32)
    v = 1 - 0.30 * np.clip((((x - W / 2) / (W / 2)) ** 2 + ((y - H / 2) / (H / 2)) ** 2 - 0.55) / 1.2, 0, 1)
    a = a * v[..., None]
    out = Image.fromarray((np.clip(a, 0, 1) * 255).astype(np.uint8), 'RGB').resize((W // S, H // S), Image.LANCZOS)
    grain = np.random.default_rng(11).normal(0, 1.6, (H // S, W // S, 1))
    return Image.fromarray(np.clip(np.asarray(out, dtype=np.float32) + grain, 0, 255).astype(np.uint8), 'RGB')


if __name__ == '__main__':
    args = sys.argv[1:]
    caption = None
    if '--caption' in args:
        i = args.index('--caption'); caption = args[i + 1]; del args[i:i + 2]
    repo, out = args[0].rstrip('/'), args[1].rstrip('/')
    im = build(repo, caption)
    im.save(f'{out}/release-0.11.png')
    im.save(f'{out}/release-0.11.webp', quality=90, method=6)
    import os
    print('wrote', im.size, 'png', os.path.getsize(f'{out}/release-0.11.png') // 1024, 'KB, webp', os.path.getsize(f'{out}/release-0.11.webp') // 1024, 'KB')
