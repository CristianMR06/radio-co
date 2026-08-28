"""Genera icon-192.png e icon-512.png para la PWA (y la base del icono Android)."""
from PIL import Image, ImageDraw
import math, os

BG = (11, 15, 20)
ARC1 = (251, 113, 133)   # rosa - Olimpica
ARC2 = (56, 189, 248)    # azul - La Mega
DOT = (232, 238, 245)

def make(size: int) -> Image.Image:
    s = size * 4  # supersampling
    img = Image.new("RGBA", (s, s), BG + (255,))
    d = ImageDraw.Draw(img)

    cx, cy = s * 0.5, s * 0.62
    r0 = s * 0.055
    d.ellipse([cx - r0, cy - r0, cx + r0, cy + r0], fill=DOT)

    # ondas concentricas: alternan color, abiertas hacia arriba
    for i, radius in enumerate([0.15, 0.24, 0.33]):
        r = s * radius
        w = int(s * 0.028)
        color = ARC1 if i % 2 == 0 else ARC2
        d.arc([cx - r, cy - r, cx + r, cy + r], start=200, end=340, fill=color, width=w)

    return img.resize((size, size), Image.LANCZOS)

here = os.path.dirname(os.path.abspath(__file__))
for n in (192, 512):
    p = os.path.join(here, f"icon-{n}.png")
    make(n).save(p)
    print("escrito", p)
