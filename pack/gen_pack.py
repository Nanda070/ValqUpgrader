"""Generates the VALQAUYM resource pack for the item upgrader GUI.

Usage: python gen_pack.py <mc_assets_dir> <out_zip>
  <mc_assets_dir> is a folder containing assets/minecraft/font/include/default.json
  and assets/minecraft/textures/font/*.png extracted from the 1.21.5 client jar
  (used only to draw labels into the background with the vanilla font).

Layout constants here MUST match UpgraderGui.java (slot hosts, model names).
"""
import hashlib
import io
import json
import math
import os
import sys
import zipfile

from PIL import Image, ImageDraw

MC = sys.argv[1]
OUT = sys.argv[2]
NS = "upgrader"

# ---------------------------------------------------------------- geometry
ROWS = 5
W, H = 176, 114 + ROWS * 18  # 176 x 204, vanilla generic_9x5 screen size


def slot_xy(slot):
    """Top-left of the 16x16 item area of a container slot."""
    return 8 + 18 * (slot % 9), 18 + 18 * (slot // 9)


def slot_center(slot):
    x, y = slot_xy(slot)
    return x + 8, y + 8


WHEEL_C = (88, 46)          # wheel centre in GUI px
WHEEL_R_OUT, WHEEL_R_IN = 26, 20
ARC_HOST, PTR_HOST, STATUS_HOST = 13, 22, 31
WAGER_SLOT, TARGET_SLOT = 10, 16
CH_HOSTS = [3, 4, 5, 12, 14, 21]        # chance digits
VL_HOSTS = [18, 19, 20, 27, 28, 29]     # wager value digits
VR_HOSTS = [24, 25, 26, 33, 34, 35]     # target value digits
CH_POS = (88, 46)     # centre of the chance text
VL_POS = (34, 75)     # centre of wager value text
VR_POS = (142, 75)    # centre of target value text
STATUS_POS = (88, 80)
PTR_FRAMES = 120      # 3 degrees per frame
ADV = 6               # digit cell advance (px)

# palette
BG = (24, 26, 38, 255)
BG2 = (31, 34, 50, 255)
PANEL = (36, 40, 58, 255)
PANEL_HI = (52, 57, 80, 255)
BORDER = (12, 13, 20, 255)
HEX = (46, 51, 72, 255)
TEXT = (232, 234, 245, 255)
MUTED = (150, 156, 182, 255)
GOLD = (255, 186, 40, 255)
GOLD_DK = (196, 128, 10, 255)
GREEN = (80, 230, 110, 255)
RED = (240, 70, 70, 255)
TRACK = (58, 63, 86, 255)
SLOT_BG = (20, 22, 32, 255)
SLOT_EDGE = (70, 76, 104, 255)

# ---------------------------------------------------------------- vanilla font
providers = []
for p in json.load(open(os.path.join(MC, "assets/minecraft/font/include/default.json"), encoding="utf-8"))["providers"]:
    if p["type"] != "bitmap":
        continue
    img = Image.open(os.path.join(MC, "assets/minecraft/textures", p["file"].split(":")[1])).convert("RGBA")
    rows = p["chars"]
    cw, chh = img.width // len(rows[0]), img.height // len(rows)
    glyphs = {}
    for r, row in enumerate(rows):
        for c, ch in enumerate(row):
            if ch == "\u0000":
                continue
            g = img.crop((c * cw, r * chh, c * cw + cw, r * chh + chh))
            px = g.load()
            width = 0
            for x in range(cw):
                if any(px[x, y][3] > 0 for y in range(chh)):
                    width = x + 1
            glyphs[ch] = (g, width)
    providers.append((glyphs, p.get("ascent", 7)))


def glyph(ch):
    for glyphs, asc in providers:
        if ch in glyphs:
            return glyphs[ch] + (7 - asc,)
    return None


def text_width(s):
    w = 0
    for ch in s:
        if ch == " ":
            w += 4
            continue
        g = glyph(ch)
        w += (g[1] + 1) if g else 6
    return w - 1 if s else 0


def draw_text(img, x, y, s, color, shadow=True):
    """Draws s with the vanilla font, top of the cap line at y."""
    for pass_, (dx, dy, col) in enumerate(
            ([(1, 1, tuple(int(v * 0.25) for v in color[:3]) + (255,))] if shadow else []) + [(0, 0, color)]):
        cx = x
        for ch in s:
            if ch == " ":
                cx += 4
                continue
            g = glyph(ch)
            if not g:
                cx += 6
                continue
            gi, gw, oy = g
            mask = gi.split()[3]
            solid = Image.new("RGBA", gi.size, col)
            img.paste(solid, (cx + dx, y + dy + oy), mask)
            cx += gw + 1


def draw_text_c(img, cx, y, s, color, shadow=True):
    draw_text(img, cx - text_width(s) // 2, y, s, color, shadow)


# ---------------------------------------------------------------- background
bg = Image.new("RGBA", (W, H), (0, 0, 0, 0))
d = ImageDraw.Draw(bg)


def rrect(box, fill, outline=None, r=3):
    d.rounded_rectangle(box, radius=r, fill=fill, outline=outline)


rrect((0, 0, W - 1, H - 1), BG, BORDER, 4)
rrect((2, 2, W - 3, H - 3), None, (44, 48, 68, 255), 3)


def hexagon(cx, cy, r, **kw):
    pts = [(cx + r * math.cos(math.radians(60 * i + 30)), cy + r * math.sin(math.radians(60 * i + 30))) for i in range(6)]
    d.polygon(pts, **kw)


def hex_panel(box):
    rrect(box, PANEL, BORDER, 3)
    x0, y0, x1, y1 = box
    # faint honeycomb
    r = 6
    hw = r * math.sqrt(3)
    row = 0
    y = y0 + 4
    while y < y1 - 2:
        off = (hw / 2) if row % 2 else 0
        x = x0 + 3 + off
        while x < x1 - 2:
            hexagon(x, y, r, outline=HEX)
            x += hw
        y += r * 1.5
        row += 1
    # mask honeycomb that spilled outside the panel
    d.rectangle((x0 - 8, y0, x0, y1), fill=BG)
    d.rectangle((x1, y0, x1 + 8, y1), fill=BG)
    d.rectangle((x0, y1, x1, y1 + 8), fill=BG)
    d.rectangle((x0, y0 - 8, x1, y0), fill=BG)
    d.rounded_rectangle(box, radius=3, outline=BORDER)


# left / right panels (cols 0-2 and 6-8, rows 0-3)
LP = (5, 15, 61, 91)
RP = (115, 15, 171, 91)
hex_panel(LP)
hex_panel(RP)

# big hex frames around wager / target slots
for s, col in ((WAGER_SLOT, GOLD_DK), (TARGET_SLOT, (90, 140, 255, 255))):
    cx, cy = slot_center(s)
    hexagon(cx, cy, 15, fill=BG2, outline=col)
    hexagon(cx, cy, 13, outline=(col[0] // 2, col[1] // 2, col[2] // 2, 255))
    x, y = slot_xy(s)
    d.rectangle((x - 1, y - 1, x + 16, y + 16), fill=SLOT_BG)

draw_text_c(bg, 33, 18, "Ставка", MUTED)
draw_text_c(bg, 143, 18, "Цель", MUTED)
draw_text_c(bg, 33, 61, "Ценность", MUTED)
draw_text_c(bg, 143, 61, "Ценность", MUTED)

# amount controls left/right of the target slot (slots 15 / 17)
for s, label, bx in ((15, "-", 117), (17, "+", 156)):
    x, y = slot_xy(s)
    rrect((bx, y + 2, bx + 11, y + 13), PANEL_HI, BORDER, 2)
    draw_text_c(bg, bx + 6, y + 4, label, TEXT)

# wheel (static track + ticks)
cx, cy = WHEEL_C
d.ellipse((cx - 30, cy - 30, cx + 30, cy + 30), fill=BG2, outline=BORDER)
d.ellipse((cx - WHEEL_R_OUT, cy - WHEEL_R_OUT, cx + WHEEL_R_OUT, cy + WHEEL_R_OUT), fill=TRACK)
d.ellipse((cx - WHEEL_R_IN, cy - WHEEL_R_IN, cx + WHEEL_R_IN, cy + WHEEL_R_IN), fill=BG2, outline=BORDER)
for k in range(0, 360, 30):
    a = math.radians(k - 90)
    d.line((cx + 27 * math.cos(a), cy + 27 * math.sin(a), cx + 29 * math.cos(a), cy + 29 * math.sin(a)), fill=(80, 86, 116, 255))
draw_text_c(bg, 88, 5, "АПГРЕЙД ПРЕДМЕТОВ", TEXT)

# bottom button row (row 4): spin = slots 36-38, presets = 39-44
x0, y0 = slot_xy(36)
x1, _ = slot_xy(38)
rrect((x0 - 2, y0 - 1, x1 + 17, y0 + 16), (60, 52, 20, 255), GOLD_DK, 3)
d.rectangle((x0, y0 + 1, x1 + 15, y0 + 7), fill=(84, 70, 22, 255))
draw_text_c(bg, (x0 + x1 + 16) // 2, y0 + 4, "КРУТИТЬ", GOLD)
for s, label in zip(range(39, 45), ["x2", "x4", "x8", "30%", "50%", "70%"]):
    x, y = slot_xy(s)
    rrect((x - 1, y - 1, x + 16, y + 16), PANEL_HI, BORDER, 2)
    draw_text_c(bg, x + 8, y + 4, label, TEXT if s < 42 else GOLD)

# player inventory slots
d.line((6, 108, W - 7, 108), fill=(44, 48, 68, 255))
for r in range(3):
    for c in range(9):
        x, y = 8 + 18 * c, 121 + 18 * r
        d.rectangle((x - 1, y - 1, x + 16, y + 16), fill=SLOT_BG, outline=SLOT_EDGE)
for c in range(9):
    x, y = 8 + 18 * c, 179
    d.rectangle((x - 1, y - 1, x + 16, y + 16), fill=SLOT_BG, outline=SLOT_EDGE)

# ---------------------------------------------------------------- overlay textures (64x64, drawn 1:1 at scale 4)
T = 64
C = T // 2


def ring_arc(color, pct):
    im = Image.new("RGBA", (T, T), (0, 0, 0, 0))
    if pct <= 0:
        return im
    dd = ImageDraw.Draw(im)
    half = pct * 1.8  # degrees each side of 12 o'clock
    box = (C - WHEEL_R_OUT, C - WHEEL_R_OUT, C + WHEEL_R_OUT, C + WHEEL_R_OUT)
    if pct >= 100:
        dd.ellipse(box, fill=color)
    else:
        dd.pieslice(box, -90 - half, -90 + half, fill=color)
    dd.ellipse((C - WHEEL_R_IN, C - WHEEL_R_IN, C + WHEEL_R_IN, C + WHEEL_R_IN), fill=(0, 0, 0, 0))
    # bright inner edge
    hi = tuple(min(255, v + 50) for v in color[:3]) + (255,)
    if pct < 100:
        dd.arc((C - WHEEL_R_OUT + 1, C - WHEEL_R_OUT + 1, C + WHEEL_R_OUT - 1, C + WHEEL_R_OUT - 1), -90 - half, -90 + half, fill=hi)
    return im


def pointer():
    im = Image.new("RGBA", (T, T), (0, 0, 0, 0))
    dd = ImageDraw.Draw(im)
    # marker sitting on the ring at 12 o'clock, pointing inward
    dd.polygon([(C - 4, 0), (C + 4, 0), (C, 8)], fill=(255, 255, 255, 255), outline=(30, 30, 30, 255))
    dd.rectangle((C - 1, 5, C + 1, 13), fill=(255, 255, 255, 255))
    dd.rectangle((C - 1, 12, C + 1, 13), fill=(255, 90, 40, 255))
    return im


def glyph_tex(ch, color):
    im = Image.new("RGBA", (T, T), (0, 0, 0, 0))
    draw_text_c(im, C, C - 4, ch, color)
    return im


def label_tex(s, color):
    im = Image.new("RGBA", (T, T), (0, 0, 0, 0))
    draw_text_c(im, C, C - 4, s, color)
    return im


def icon_tex():
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    dd = ImageDraw.Draw(im)
    dd.ellipse((1, 1, 14, 14), fill=(58, 63, 86, 255), outline=(20, 20, 28, 255))
    dd.pieslice((1, 1, 14, 14), -150, -30, fill=GOLD)
    dd.ellipse((4, 4, 11, 11), fill=(31, 34, 50, 255))
    dd.polygon([(7, 0), (9, 0), (8, 4)], fill=(255, 255, 255, 255))
    dd.point((8, 8), fill=GOLD)
    return im


# ---------------------------------------------------------------- pack assembly
files = {}


def png(path, im):
    b = io.BytesIO()
    im.save(b, "PNG", optimize=True)
    files[path] = b.getvalue()


def js(path, obj):
    files[path] = json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def item(name, texture, tx=0.0, ty=0.0, tz=0.0, scale=4.0, rot=0.0):
    js(f"assets/{NS}/items/{name}.json",
       {"model": {"type": "minecraft:model", "model": f"{NS}:item/{name}"}, "oversized_in_gui": True})
    js(f"assets/{NS}/models/item/{name}.json", {
        "parent": "minecraft:item/generated",
        "textures": {"layer0": f"{NS}:item/{texture}"},
        "display": {"gui": {"rotation": [0, 0, rot], "translation": [tx, ty, tz], "scale": [scale, scale, scale]}},
    })


def offset(host, pos):
    hx, hy = slot_center(host)
    return pos[0] - hx, -(pos[1] - hy)  # model +y is up


js("pack.mcmeta", {"pack": {
    "pack_format": 55, "supported_formats": [55, 999], "min_format": 55, "max_format": 999,
    "description": "§cVALQAUYM §7— апгрейдер"}})

# background glyph font
png(f"assets/{NS}/textures/gui/upgrader_bg.png", bg)
js(f"assets/{NS}/font/gui.json", {"providers": [
    {"type": "space", "advances": {"": -8, "": -1}},
    {"type": "bitmap", "file": f"{NS}:gui/upgrader_bg.png", "ascent": 13, "height": H, "chars": [""]},
]})

# blank (invisible clickable) + upgrader item icon
png(f"assets/{NS}/textures/item/blank.png", Image.new("RGBA", (16, 16), (0, 0, 0, 0)))
item("blank", "blank", scale=1)
png(f"assets/{NS}/textures/item/icon.png", icon_tex())
js(f"assets/{NS}/items/icon.json", {"model": {"type": "minecraft:model", "model": f"{NS}:item/icon"}})
js(f"assets/{NS}/models/item/icon.json", {"parent": "minecraft:item/generated", "textures": {"layer0": f"{NS}:item/icon"}})

# arcs: g = idle gold, w = win green, l = lose red ; p = displayed percent 0..100
ax, ay = offset(ARC_HOST, WHEEL_C)
for st, col in (("g", GOLD), ("w", GREEN), ("l", RED)):
    for p in range(0, 101):
        png(f"assets/{NS}/textures/item/arc_{st}_{p}.png", ring_arc(col, p))
        item(f"arc_{st}_{p}", f"arc_{st}_{p}", ax, ay, 0)

# pointer frames (rotation only, one texture). +z rotation is counter-clockwise, frame k = 3k deg clockwise.
png(f"assets/{NS}/textures/item/ptr.png", pointer())
px_, py_ = offset(PTR_HOST, WHEEL_C)
for k in range(PTR_FRAMES):
    item(f"ptr_{k}", "ptr", px_, py_, 2, rot=-(360.0 / PTR_FRAMES) * k)

# digits
GL = {str(i): str(i) for i in range(10)}
GL.update({"dot": ".", "pct": "%"})
for g, ch in GL.items():
    png(f"assets/{NS}/textures/item/gc_{g}.png", glyph_tex(ch, GOLD))
    if g.isdigit():
        png(f"assets/{NS}/textures/item/gv_{g}.png", glyph_tex(ch, TEXT))


def digit_models(prefix, hosts, centre, tex_prefix, glyph_names, max_len):
    for L in range(1, max_len + 1):
        d_ = max_len - L
        for i in range(L):
            cxp = centre[0] - 3 * L + ADV * i + 3
            for g in glyph_names:
                ox, oy = offset(hosts[i], (cxp, centre[1]))
                item(f"{prefix}_{i}_{d_}_{g}", f"{tex_prefix}_{g}", ox, oy, 1)


digit_models("ch", CH_HOSTS, CH_POS, "gc", list(GL.keys()), 6)
digit_models("vl", VL_HOSTS, VL_POS, "gv", [str(i) for i in range(10)], 6)
digit_models("vr", VR_HOSTS, VR_POS, "gv", [str(i) for i in range(10)], 6)

# status labels under the wheel
sx, sy = offset(STATUS_HOST, STATUS_POS)
for name, s, col in (("st_spin", "крутим...", GOLD), ("st_win", "УСПЕХ!", GREEN), ("st_lose", "НЕУДАЧА", RED), ("st_ready", "готово", MUTED)):
    png(f"assets/{NS}/textures/item/{name}.png", label_tex(s, col))
    item(name, name, sx, sy, 1)

with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
    for path in sorted(files):
        zi = zipfile.ZipInfo(path, date_time=(2026, 1, 1, 0, 0, 0))
        zi.compress_type = zipfile.ZIP_DEFLATED
        z.writestr(zi, files[path])

# ---------------------------------------------------------------- preview (emulates how the client places overlays)
def place(canvas, host, tex, tx, ty, rot=0.0):
    im = Image.open(io.BytesIO(files[f"assets/{NS}/textures/item/{tex}.png"]))
    if rot:
        im = im.rotate(rot, resample=Image.NEAREST)  # PIL: positive = counter-clockwise, like +z in model space
    hx, hy = slot_center(host)
    canvas.alpha_composite(im, (int(hx + tx - im.width / 2), int(hy - ty - im.height / 2)))


pv = bg.copy()
place(pv, ARC_HOST, "arc_g_12", ax, ay)
place(pv, PTR_HOST, "ptr", px_, py_, rot=-3 * 100)
s = "11.22%"
for i, ch in enumerate(s):
    g = {".": "dot", "%": "pct"}.get(ch, ch)
    m = json.loads(files[f"assets/{NS}/models/item/ch_{i}_{6 - len(s)}_{g}.json"])
    t = m["display"]["gui"]["translation"]
    place(pv, CH_HOSTS[i], f"gc_{g}", t[0], t[1])
for hosts, pre, val in ((VL_HOSTS, "vl", "122"), (VR_HOSTS, "vr", "978")):
    for i, ch in enumerate(val):
        m = json.loads(files[f"assets/{NS}/models/item/{pre}_{i}_{6 - len(val)}_{ch}.json"])
        t = m["display"]["gui"]["translation"]
        place(pv, hosts[i], f"gv_{ch}", t[0], t[1])
place(pv, STATUS_HOST, "st_spin", sx, sy)
pv.resize((W * 4, H * 4), Image.NEAREST).save(os.path.join(os.path.dirname(OUT) or ".", "preview.png"))
print(len(files), "files,", os.path.getsize(OUT), "bytes, sha1", hashlib.sha1(open(OUT, "rb").read()).hexdigest())
