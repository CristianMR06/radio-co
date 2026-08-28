"""Genera los mipmap ic_launcher / ic_launcher_round del APK."""
from PIL import Image, ImageDraw
import os

BG = (11, 15, 20)
ARC1 = (251, 113, 133)
ARC2 = (56, 189, 248)
DOT = (232, 238, 245)

DENSITIES = {
    "mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192,
}

def art(size: int, round_icon: bool) -> Image.Image:
    s = size * 4
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    if round_icon:
        d.ellipse([0, 0, s - 1, s - 1], fill=BG + (255,))
    else:
        r = int(s * 0.22)
        d.rounded_rectangle([0, 0, s - 1, s - 1], radius=r, fill=BG + (255,))

    cx, cy = s * 0.5, s * 0.62
    r0 = s * 0.055
    d.ellipse([cx - r0, cy - r0, cx + r0, cy + r0], fill=DOT)

    for i, radius in enumerate([0.15, 0.24, 0.33]):
        r = s * radius
        w = int(s * 0.028)
        color = ARC1 if i % 2 == 0 else ARC2
        d.arc([cx - r, cy - r, cx + r, cy + r], start=200, end=340, fill=color, width=w)

    return img.resize((size, size), Image.LANCZOS)

res = os.path.join(os.path.dirname(os.path.abspath(__file__)), "app", "src", "main", "res")
for dens, px in DENSITIES.items():
    d = os.path.join(res, "mipmap-" + dens)
    os.makedirs(d, exist_ok=True)
    art(px, False).save(os.path.join(d, "ic_launcher.png"))
    art(px, True).save(os.path.join(d, "ic_launcher_round.png"))
    print("mipmap-%s -> %dpx" % (dens, px))
