#!/usr/bin/env python3
"""Channel cards for the Discord announcement, in the banner's colours. 1000x1000 WebP."""
import math
import numpy as np
from PIL import Image, ImageDraw, ImageFont

W = H = 1000
RED = (237, 85, 100)
BG_A, BG_B = np.array([23, 18, 26]), np.array([43, 26, 34])
FONT = '/usr/share/fonts/noto/NotoSans-{}.ttf'
CARDS = [
    ('announcements', 'New releases land here first, and anything that changes how the app works.', (0.78, 0.22)),
    ('help', 'Stuck, or not sure it is a bug? Ask here.', (0.25, 0.25)),
    ('bug-reports', 'Each post becomes a real tracker entry. No GitHub account needed.', (0.78, 0.78)),
    ('feature-requests', 'Ideas, and the things you wish it did.', (0.22, 0.75)),
    ('general', 'Questions, opinions, and what you are listening to.', (0.6, 0.4)),
]

def background(glow_at):
    y, x = np.mgrid[0:H, 0:W] / np.array([H, W])[:, None, None] if False else np.mgrid[0:H, 0:W] / float(W)
    t = np.clip((x + y) / 2, 0, 1)[..., None]
    img = BG_A * (1 - t) + BG_B * t
    gx, gy = glow_at
    d = np.sqrt((x - gx) ** 2 + (y - gy) ** 2) / 0.75
    a = (np.clip(1 - d, 0, 1) ** 2 * 0.34)[..., None]
    img = img * (1 - a) + np.array(RED) * a
    return Image.fromarray(img.astype(np.uint8), 'RGB').convert('RGBA')

def bars(draw, top, bottom, seed):
    n, left, right = 22, 70, W - 70
    step = (right - left) / n
    for i in range(n):
        env = 0.35 + 0.65 * abs(math.sin(i * 0.42 + seed)) * (0.55 + 0.45 * math.sin(i * 0.17 + seed * 2))
        h = max(18, (bottom - top) * abs(env))
        cx = left + step * (i + 0.5)
        mid = (top + bottom) / 2
        alpha = int(255 * (0.25 + 0.6 * i / n))
        draw.rounded_rectangle([cx - 7, mid - h / 2, cx + 7, mid + h / 2], radius=7, fill=RED + (alpha,))

def fit(text, weight, size, width):
    while size > 20:
        f = ImageFont.truetype(FONT.format(weight), size)
        if f.getlength(text) <= width: return f
        size -= 2
    return ImageFont.truetype(FONT.format(weight), size)

def wrap(text, font, width):
    lines, line = [], ''
    for word in text.split():
        trial = (line + ' ' + word).strip()
        if font.getlength(trial) <= width: line = trial
        else: lines.append(line); line = word
    lines.append(line)
    # No word left alone on the last line: pull words down from the line above while it fits.
    while len(lines) > 1 and len(lines[-1].split()) < 2 and len(lines[-2].split()) > 2:
        head, moved = lines[-2].rsplit(' ', 1)
        if font.getlength(moved + ' ' + lines[-1]) > width: break
        lines[-2], lines[-1] = head, moved + ' ' + lines[-1]
    return lines

for i, (name, desc, glow) in enumerate(CARDS):
    img = background(glow)
    layer = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    bars(d, 90, 250, seed=i * 1.3)
    hash_font = ImageFont.truetype(FONT.format('Bold'), 150)
    d.text((70, 300), '#', font=hash_font, fill=RED + (255,))
    name_font = fit(name, 'Bold', 104, W - 140)
    d.text((70, 480), name, font=name_font, fill=(245, 239, 241, 255))
    body = ImageFont.truetype(FONT.format('Regular'), 54)
    y = 480 + name_font.size + 50
    for line in wrap(desc, body, W - 140):
        d.text((70, y), line, font=body, fill=(214, 202, 207, 255)); y += 72
    small = ImageFont.truetype(FONT.format('Medium'), 40)
    d.text((70, H - 110), 'InterTune on Discord', font=small, fill=RED + (230,))
    out = Image.alpha_composite(img, layer).convert('RGB')
    out.save(f'discord-2026-09-{i + 1}.webp', 'WEBP', quality=90, method=6)
    out.save(f'card-{i + 1}.png')
    print(f'discord-2026-09-{i + 1}.webp', name)
