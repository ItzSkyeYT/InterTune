#!/usr/bin/env python3
"""
Render the InterTune landscape now-playing screen, before and after, as one SVG.

The GEOMETRY is computed from the dp constants in the source rather than traced
from a screenshot, so the picture cannot drift away from the code:

  before  upstream v0.10.1            after   this fork
  ------------------------------------------------------------------
  collapsedBound   dismissed + 48dp   |  dismissedBound (landscape)
  gutter           32dp               |  24dp
  thumb gutter     32dp, centred      |  8dp, start-aligned
  title / artist   22sp / 16sp        |  25sp / 19sp
  transport icon   32dp               |  42dp
  play button      72dp               |  84dp
  system bars      visible            |  hidden while expanded
"""

W, H = 832, 384          # landscape phone in dp (3120x1440 @ 600dpi)
STATUS, NAV = 24, 16
PEEK = 48                # QueuePeekHeight
ARROW = 48               # the expand arrow's IconButton

TRACK = "Freed from Desire (Full Vocals Mixx)"
ARTIST = "Phil Jay, Molella, Gala"
ELAPSED, TOTAL, FRAC = "1:31", "4:18", 0.35


# ---------------------------------------------------------------- geometry
def layout(after: bool):
    top_inset = 0 if after else STATUS
    bot_inset = 0 if after else NAV

    v = max(top_inset, bot_inset)
    if after:
        v = max(v, 16)                      # the fork floors this
    row_top, row_bot = v, H - v

    dismissed = PEEK + NAV
    collapsed = dismissed if after else dismissed + PEEK
    strip = H - collapsed

    half = W / 2
    gutter = 8 if after else 32
    inner = half - gutter * 2
    side = min(inner, row_bot - row_top)
    art_x = gutter if after else gutter + (inner - side) / 2

    pad = 24 if after else 32
    title_h, artist_h = (32, 26) if after else (28, 22)
    prog_h, play, icon, act_h = 44, (84 if after else 72), (42 if after else 32), 48
    col_bot = strip if after else row_bot

    if after:
        actions_y = row_top
        block = title_h + artist_h + 12 + prog_h + 16 + play
        y = row_top + act_h + (col_bot - row_top - act_h - block) / 2
    else:
        block = act_h + 16 + title_h + artist_h + 12 + prog_h + 16 + play
        y = row_top + (col_bot - row_top - block) / 2
        actions_y = y
        y += act_h + 16

    return dict(row=(row_top, row_bot), art=(art_x, row_top, side, side),
                half=half, pad=pad, actions=(actions_y, act_h),
                title=(y, title_h), artist=(y + title_h, artist_h),
                prog=(y + title_h + artist_h + 12, prog_h),
                trans=(y + title_h + artist_h + 12 + prog_h + 16, play, icon),
                strip=strip, bars=(top_inset, bot_inset), collapsed=collapsed)


# ---------------------------------------------------------------- drawing
FONT = "system-ui,-apple-system,Segoe UI,Roboto,sans-serif"
BAD, GOOD, MUTED = "#f28b82", "#81c995", "#6b7280"


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def fit(s, width, size, weight=1.0):
    """Truncate with an ellipsis the way Text(maxLines = 1, Ellipsis) would.

    The larger landscape type means the title clips *sooner*, which is real
    behaviour worth showing rather than letting the string run off the panel.
    """
    adv = size * 0.52 * weight           # rough advance for this font stack
    n = int(width / adv)
    return s if len(s) <= n else s[:max(1, n - 1)].rstrip() + "…"


def icons(cx, cy, s, kind, col):
    """Transport glyphs, drawn to fit a box of side s centred on (cx, cy)."""
    u = s / 24.0                                   # 24dp design grid
    def p(d, w=2.0, fill="none"):
        return (f'<path d="{d}" fill="{fill}" stroke="{col}" stroke-width="{w*u}" '
                f'stroke-linecap="round" stroke-linejoin="round"/>')
    x, y = cx - s / 2, cy - s / 2
    X = lambda v: x + v * u
    Y = lambda v: y + v * u
    if kind == "shuffle":
        return (p(f"M{X(3)} {Y(8)} h4 l3 4") + p(f"M{X(3)} {Y(16)} h4 l6 -8 h4") +
                p(f"M{X(17)} {Y(5)} l3 3 l-3 3") + p(f"M{X(17)} {Y(13)} l3 3 l-3 3"))
    if kind == "prev":
        return (p(f"M{X(7)} {Y(6)} v12", 2.4) +
                p(f"M{X(18)} {Y(6)} l-8 6 l8 6 z", 1.6, col))
    if kind == "next":
        return (p(f"M{X(17)} {Y(6)} v12", 2.4) +
                p(f"M{X(6)} {Y(6)} l8 6 l-8 6 z", 1.6, col))
    if kind == "play":
        return p(f"M{X(8)} {Y(5)} l11 7 l-11 7 z", 1.4, col)
    if kind == "repeat":
        return (p(f"M{X(5)} {Y(9)} a5 4 0 0 1 5 -4 h9") + p(f"M{X(17)} {Y(2)} l3 3 l-3 3") +
                p(f"M{X(19)} {Y(15)} a5 4 0 0 1 -5 4 h-9") + p(f"M{X(7)} {Y(16)} l-3 3 l3 3"))
    if kind == "heart":
        return p(f"M{X(12)} {Y(19)} c-6 -4 -8 -7.5 -8 -11 a4 4 0 0 1 8 -2 "
                 f"a4 4 0 0 1 8 2 c0 3.5 -2 7 -8 11 z", 1.4, col)
    if kind == "more":
        return "".join(f'<circle cx="{cx}" cy="{cy + d*5.5*u}" r="{1.9*u}" fill="{col}"/>'
                       for d in (-1, 0, 1))
    return ""


def album(x, y, s):
    """A stylised sleeve. Drawn, not embedded - the file stays self-contained."""
    g = [f'<rect x="{x}" y="{y}" width="{s}" height="{s}" rx="{s*0.045}" fill="url(#sleeve)"/>']
    m = s * 0.14
    g.append(f'<rect x="{x+m}" y="{y+m*1.5}" width="{s-2*m}" height="{s-2.6*m}" '
             f'rx="{s*0.05}" fill="#20242b"/>')
    # abstract skyline inside the photo window
    bx, by, bw, bh = x + m, y + m * 1.5, s - 2 * m, s - 2.6 * m
    for i, (fx, fw, fh) in enumerate([(.06, .16, .42), (.26, .13, .62), (.43, .18, .35),
                                      (.64, .12, .55), (.79, .15, .30)]):
        g.append(f'<rect x="{bx+bw*fx}" y="{by+bh*(1-fh)}" width="{bw*fw}" '
                 f'height="{bh*fh}" fill="#39414d"/>')
    g.append(f'<circle cx="{bx+bw*0.74}" cy="{by+bh*0.22}" r="{bh*0.09}" fill="#c9a227"/>')
    g.append(f'<text x="{x+s/2}" y="{y+s-m*0.34}" fill="#1c1a12" font-size="{s*0.085}" '
             f'text-anchor="middle" font-family="{FONT}" font-weight="700" '
             f'letter-spacing="{s*0.004}">FREED FROM DESIRE</text>')
    return "".join(g)


def panel(L, ox, oy, heading, after):
    o, a = [], lambda s: o.append(s)
    sub = ("system bars hidden · queue peek 64dp · artwork start-aligned" if after
           else "system bars visible · queue peek 112dp · artwork centred")
    a(f'<text x="{ox}" y="{oy-30}" fill="#e8eaed" font-size="18" font-family="{FONT}" '
      f'font-weight="700">{esc(heading)}</text>')
    a(f'<text x="{ox}" y="{oy-11}" fill="{MUTED}" font-size="12.5" '
      f'font-family="{FONT}">{esc(sub)}</text>')

    a(f'<clipPath id="cl{int(after)}"><rect x="{ox}" y="{oy}" width="{W}" height="{H}" rx="12"/></clipPath>')
    a(f'<g clip-path="url(#cl{int(after)})">')
    a(f'<rect x="{ox}" y="{oy}" width="{W}" height="{H}" fill="url(#bgw)"/>')

    tb, bb = L["bars"]
    if tb:
        a(f'<rect x="{ox}" y="{oy}" width="{W}" height="{tb}" fill="#000" opacity="0.28"/>')
        a(f'<text x="{ox+14}" y="{oy+16}" fill="#e8eaed" font-size="11.5" '
          f'font-family="{FONT}" font-weight="600">9:41</text>')
        for i, bw in enumerate([3, 5, 7, 9]):
            a(f'<rect x="{ox+W-58+i*6}" y="{oy+16-bw}" width="3.4" height="{bw}" '
              f'fill="#e8eaed" rx="1"/>')
        a(f'<rect x="{ox+W-26}" y="{oy+7}" width="16" height="9" rx="2.6" fill="none" '
          f'stroke="#e8eaed" stroke-width="1.2"/>')
        a(f'<rect x="{ox+W-24.5}" y="{oy+8.5}" width="11" height="6" rx="1.4" fill="#e8eaed"/>')
    if bb:
        a(f'<rect x="{ox}" y="{oy+H-bb}" width="{W}" height="{bb}" fill="#000" opacity="0.28"/>')
        a(f'<rect x="{ox+W/2-46}" y="{oy+H-bb/2-1.6}" width="92" height="3.4" rx="1.7" fill="#e8eaed"/>')

    st = L["strip"]
    a(f'<rect x="{ox}" y="{oy+st}" width="{W}" height="{H-st}" fill="#000" opacity="0.16"/>')
    a(f'<line x1="{ox}" y1="{oy+st}" x2="{ox+W}" y2="{oy+st}" stroke="#ffffff" '
      f'stroke-opacity="0.18" stroke-dasharray="5 4"/>')

    ax, ay, aw, _ = L["art"]
    a(album(ox + ax, oy + ay, aw))

    half, pad = L["half"], L["pad"]
    lx, rx = ox + half + pad, ox + W - pad

    ay2, ah2 = L["actions"]
    for i, k in enumerate(("heart", "more")):
        cx = rx - 22 - (1 - i) * 56
        a(f'<circle cx="{cx}" cy="{oy+ay2+ah2/2}" r="21" fill="#ffffff" opacity="0.20"/>')
        a(icons(cx, oy + ay2 + ah2 / 2, 22, k, "#f4f6f8"))

    ty, th = L["title"]
    fs = th * 0.80
    a(f'<text x="{lx}" y="{oy+ty+th*0.78}" fill="#ffffff" font-size="{fs}" '
      f'font-family="{FONT}" font-weight="700">'
      f'{esc(fit(TRACK, rx - lx, fs, 1.06))}</text>')
    ry, rh = L["artist"]
    afs = rh * 0.80
    a(f'<text x="{lx}" y="{oy+ry+rh*0.76}" fill="#e2e5e9" font-size="{afs}" '
      f'font-family="{FONT}">{esc(fit(ARTIST, rx - lx, afs))}</text>')

    py, _ = L["prog"]
    a(f'<rect x="{lx}" y="{oy+py+7}" width="{rx-lx}" height="7" rx="3.5" fill="#ffffff" opacity="0.26"/>')
    a(f'<rect x="{lx}" y="{oy+py+7}" width="{(rx-lx)*FRAC}" height="7" rx="3.5" fill="#f4f6f8"/>')
    a(f'<circle cx="{lx+(rx-lx)*FRAC}" cy="{oy+py+10.5}" r="6" fill="#ffffff"/>')
    a(f'<text x="{lx}" y="{oy+py+34}" fill="#e2e5e9" font-size="13" font-family="{FONT}">{ELAPSED}</text>')
    a(f'<text x="{rx}" y="{oy+py+34}" fill="#e2e5e9" font-size="13" font-family="{FONT}" '
      f'text-anchor="end">{TOTAL}</text>')

    tyy, play, icon = L["trans"]
    mid, cxm = oy + tyy + play / 2, (lx + rx) / 2
    a(f'<rect x="{cxm-play/2}" y="{mid-play/2}" width="{play}" height="{play}" '
      f'rx="{play*0.32}" fill="#f4f6f8"/>')
    a(icons(cxm, mid, play * 0.46, "play", "#1d2026"))
    for dx, k in ((-1, "prev"), (1, "next")):
        a(icons(cxm + dx * (play / 2 + 40), mid, icon, k, "#f4f6f8"))
    for dx, k in ((-1, "shuffle"), (1, "repeat")):
        a(icons(cxm + dx * (play / 2 + 40 + 64), mid, icon, k, "#f4f6f8"))

    a(f'<circle cx="{ox+W/2}" cy="{oy+st+ARROW/2}" r="{ARROW/2}" fill="#ffffff" opacity="0.14"/>')
    a(f'<path d="M {ox+W/2-10} {oy+st+ARROW/2+5} l 10 -10 l 10 10" fill="none" '
      f'stroke="#ffffff" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"/>')

    t_bot, s_top = oy + tyy + play, oy + st
    if s_top < t_bot:
        a(f'<rect x="{ox+half}" y="{s_top}" width="{W/2}" height="{t_bot-s_top}" fill="{BAD}" opacity="0.26"/>')
    a("</g>")
    a(f'<rect x="{ox}" y="{oy}" width="{W}" height="{H}" rx="12" fill="none" stroke="#39404d"/>')

    if s_top < t_bot:
        a(f'<text x="{ox+W/2}" y="{oy+H+20}" fill="{BAD}" font-size="13" text-anchor="middle" '
          f'font-family="{FONT}" font-weight="600">'
          f'expand arrow overlaps the transport row by {t_bot-s_top:.0f}dp</text>')
    else:
        a(f'<text x="{ox+W/2}" y="{oy+H+20}" fill="{GOOD}" font-size="13" text-anchor="middle" '
          f'font-family="{FONT}" font-weight="600">'
          f'{s_top-t_bot:.0f}dp of clearance · artwork {L["art"][2]:.0f}dp, was '
          f'{layout(False)["art"][2]:.0f}dp</text>')
    return "\n".join(o)


b, af = layout(False), layout(True)
PAD, GAP = 28, 74
tw = W + PAD * 2
th = PAD + 34 + H + GAP + 34 + H + PAD + 30

svg = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{tw}" height="{th}" '
       f'viewBox="0 0 {tw} {th}" role="img" '
       f'aria-label="InterTune landscape player before and after">',
       '<defs>',
       '<linearGradient id="bgw" x1="0" y1="0" x2="1" y2="1">'
       '<stop offset="0" stop-color="#8a7a1e"/><stop offset="0.5" stop-color="#6f6a33"/>'
       '<stop offset="1" stop-color="#4a4a2c"/></linearGradient>',
       '<linearGradient id="sleeve" x1="0" y1="0" x2="0.4" y2="1">'
       '<stop offset="0" stop-color="#f5d020"/><stop offset="1" stop-color="#e0b410"/></linearGradient>',
       '</defs>',
       f'<rect width="{tw}" height="{th}" fill="#0d0f12"/>']
svg.append(panel(b, PAD, PAD + 34, "BEFORE — upstream v0.10.1", False))
svg.append(panel(af, PAD, PAD + 34 + H + GAP + 34, "AFTER — InterTune", True))
svg.append(f'<text x="{PAD}" y="{th-9}" fill="{MUTED}" font-size="11.5" font-family="{FONT}">'
           f'Geometry computed from the layout constants in source at 832x384dp '
           f'(3120x1440 @ 600dpi) — drawn, not screenshotted.</text>')
svg.append("</svg>")

out = __import__("os").path.join(__import__("os").path.dirname(__import__("os").path.dirname(__import__("os").path.abspath(__file__))), "assets", "landscape-before-after.svg")
open(out, "w").write("\n".join(svg))
print("wrote", out)
for n, L in (("before", b), ("after", af)):
    t0, pl, _ = L["trans"]
    print(f"{n:7} art {L['art'][2]:6.1f}dp   strip@{L['strip']:5.1f}dp   "
          f"transport {t0:.1f}-{t0+pl:.1f}dp   "
          f"{'OVERLAP %.0fdp' % (t0+pl-L['strip']) if L['strip'] < t0+pl else 'clear %.0fdp' % (L['strip']-t0-pl)}")
