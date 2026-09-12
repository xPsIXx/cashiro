#!/usr/bin/env python3
"""Cashiro adaptive + legacy launcher icons (cream + rose C)."""

from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
CREAM = (244, 235, 227, 255)
ROSE = (226, 92, 14, 255)
INK = (255, 252, 248, 255)
SERIF = "/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf"

DENSITIES = {
    "mdpi": 1,
    "hdpi": 1.5,
    "xhdpi": 2,
    "xxhdpi": 3,
    "xxxhdpi": 4,
}


def circle(size, fill, pad=0):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse((pad, pad, size - 1 - pad, size - 1 - pad), fill=fill)
    return img


def glyph_c(size, color):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    f = ImageFont.truetype(SERIF, int(size * 0.58))
    d.text((size / 2, size / 2 - size * 0.03), "C", font=f, fill=color, anchor="mm")
    return img


def full_icon(px):
    img = Image.new("RGBA", (px, px), CREAM)
    pad = int(px * 0.14)
    coin = circle(px, ROSE, pad)
    img = Image.alpha_composite(img, coin)
    img = Image.alpha_composite(img, glyph_c(px, INK))
    return img


def foreground(px):
    """108dp adaptive foreground: glyph lives in the inner 66%."""
    img = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    inner = int(px * 0.62)
    off = (px - inner) // 2
    coin = circle(inner, ROSE, 0)
    layer = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    layer.paste(coin, (off, off), coin)
    c = glyph_c(inner, INK)
    cl = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    cl.paste(c, (off, off), c)
    return Image.alpha_composite(layer, cl)


def monochrome(px):
    img = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    return Image.alpha_composite(img, glyph_c(px, (255, 255, 255, 255)))


def save_webp(img, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, "WEBP", quality=92, method=6)
    print("wrote", path, img.size)


def main():
    for name, scale in DENSITIES.items():
        folder = RES / f"mipmap-{name}"
        launcher = int(48 * scale)
        fg = int(108 * scale)
        save_webp(full_icon(launcher), folder / "ic_launcher.webp")
        save_webp(full_icon(launcher), folder / "ic_launcher_round.webp")
        save_webp(foreground(fg), folder / "ic_launcher_foreground.webp")
        save_webp(monochrome(fg), folder / "ic_launcher_monochrome.webp")

    play = full_icon(512).convert("RGB")
    play.save(ROOT / "fastlane/metadata/android/en-US/images/icon.png", "PNG", optimize=True)
    print("wrote play icon 512")


if __name__ == "__main__":
    main()
