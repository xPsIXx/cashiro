#!/usr/bin/env python3
"""Generate Cashiro README banner + phone mock screenshots with real type."""

from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
SHOTS = ROOT / "screenshots"
FASTLANE = ROOT / "fastlane/metadata/android/en-US/images"
PHONE = FASTLANE / "phoneScreenshots"

CREAM = (244, 235, 227)
CREAM_DEEP = (236, 220, 208)
WHITE = (255, 252, 248)
ROSE = (226, 92, 14)
ROSE_SOFT = (255, 214, 186)
INK = (44, 24, 16)
INK_MUTED = (110, 86, 74)
GREEN = (46, 125, 70)
RED = (176, 53, 34)
CHIP = (255, 236, 220)
DARK = (28, 22, 20)
DARK_CARD = (46, 36, 32)
DARK_INK = (250, 242, 234)

W, H = 1080, 1920
SANS = "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf"
SANS_B = "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf"
SERIF_B = "/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf"


def font(path, size):
    return ImageFont.truetype(path, size)


def rr(draw, box, r, fill, outline=None, width=1):
    draw.rounded_rectangle(box, radius=r, fill=fill, outline=outline, width=width)


def text(draw, xy, s, f, fill=INK, anchor="lt"):
    draw.text(xy, s, font=f, fill=fill, anchor=anchor)


def status_bar(draw, dark=False):
    ink = DARK_INK if dark else INK
    text(draw, (48, 28), "9:41", font(SANS_B, 28), ink)
    text(draw, (1032, 28), "LTE  84%", font(SANS, 24), ink, anchor="rt")


def top_bar(draw, title, dark=False):
    ink = DARK_INK if dark else INK
    text(draw, (540, 96), title, font(SANS_B, 44), ink, anchor="mt")


def nav(draw, active, dark=False):
    y = 1788
    bg = (38, 30, 28) if dark else WHITE
    rr(draw, (24, y - 18, 1056, 1900), 36, bg)
    items = ["Home", "Analytics", "Add", "Chat", "Settings"]
    xs = [108, 324, 540, 756, 972]
    for i, (label, x) in enumerate(zip(items, xs)):
        on = label == active
        col = ROSE if on else (INK_MUTED if not dark else (170, 150, 140))
        r = 10 if on else 7
        draw.ellipse((x - r, y + 8, x + r, y + 8 + 2 * r), fill=col)
        text(draw, (x, y + 52), label, font(SANS, 22), col, anchor="mt")


def card(draw, box, dark=False):
    rr(draw, box, 28, DARK_CARD if dark else WHITE)


def phone_base(dark=False):
    img = Image.new("RGB", (W, H), DARK if dark else CREAM)
    draw = ImageDraw.Draw(img)
    # warm wash
    wash = Image.new("RGB", (W, 420), ROSE if not dark else (90, 42, 22))
    wash = wash.filter(ImageFilter.GaussianBlur(1))
    img.paste(wash, (0, 0))
    overlay = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    od = ImageDraw.Draw(overlay)
    od.ellipse((-200, -280, 700, 520), fill=(*ROSE, 40 if dark else 55))
    od.ellipse((620, -200, 1280, 420), fill=(255, 180, 120, 40 if dark else 50))
    img = Image.alpha_composite(img.convert("RGBA"), overlay).convert("RGB")
    draw = ImageDraw.Draw(img)
    status_bar(draw, dark)
    return img, draw


def home(dark=False):
    img, d = phone_base(dark)
    ink = DARK_INK if dark else INK
    muted = (180, 160, 150) if dark else INK_MUTED
    top_bar(d, "Cashiro", dark)
    text(d, (540, 170), "This month", font(SANS, 26), muted, "mt")
    text(d, (540, 250), "AED 42,180.42", font(SANS_B, 72), ink, "mt")
    text(d, (540, 328), "FAB  ·  ENBD  ·  Liv", font(SANS, 26), ROSE, "mt")

    card(d, (48, 390, 1032, 560), dark)
    text(d, (88, 422), "Income", font(SANS, 24), muted)
    text(d, (88, 462), "AED 18,400.00", font(SANS_B, 36), GREEN)
    text(d, (560, 422), "Spent", font(SANS, 24), muted)
    text(d, (560, 462), "AED 9,220.35", font(SANS_B, 36), RED)

    chips = [("FAB ****4291", 48), ("ENBD ****8821", 390), ("Liv", 780)]
    for label, x in chips:
        rr(d, (x, 588, x + 300 if label != "Liv" else x + 180, 656), 22, ROSE_SOFT if not dark else (80, 44, 28))
        text(d, (x + 24, 608), label, font(SANS_B, 24), ROSE if not dark else ROSE_SOFT)

    text(d, (56, 700), "Recent", font(SANS_B, 32), ink)
    rows = [
        ("Carrefour", "Food & Dining", "− AED 186.50", RED),
        ("ENOC", "Fuel", "− AED 92.00", RED),
        ("Talabat", "Dining", "− AED 47.00", RED),
        ("Salary · FAB", "Income", "+ AED 18,400.00", GREEN),
        ("DEWA", "Bills", "− AED 420.00", RED),
    ]
    y = 752
    for name, cat, amt, col in rows:
        card(d, (48, y, 1032, y + 132), dark)
        draw_dot = (88, y + 48, 124, y + 84)
        d.ellipse(draw_dot, fill=ROSE_SOFT if not dark else (90, 48, 30))
        text(d, (148, y + 28), name, font(SANS_B, 30), ink)
        text(d, (148, y + 74), cat, font(SANS, 24), muted)
        text(d, (1000, y + 52), amt, font(SANS_B, 28), col, "rm")
        y += 148
    nav(d, "Home", dark)
    return img


def analytics():
    img, d = phone_base(False)
    top_bar(d, "Analytics")
    text(d, (56, 168), "September 2026", font(SANS, 26), INK_MUTED)
    text(d, (56, 214), "AED 9,220.35", font(SANS_B, 56), INK)
    text(d, (56, 282), "spent this month", font(SANS, 24), INK_MUTED)

    card(d, (48, 340, 1032, 720))
    text(d, (80, 368), "Week", font(SANS_B, 26), INK)
    days = ["M", "T", "W", "T", "F", "S", "S"]
    heights = [90, 140, 70, 200, 160, 110, 50]
    base = 660
    for i, (lab, h) in enumerate(zip(days, heights)):
        x = 110 + i * 130
        rr(d, (x, base - h, x + 70, base), 14, ROSE if i == 3 else ROSE_SOFT)
        text(d, (x + 35, 680), lab, font(SANS, 22), INK_MUTED, "mt")

    cats = [
        ("Food", "32%", "AED 2,950"),
        ("Bills", "22%", "AED 2,028"),
        ("Transport", "18%", "AED 1,660"),
        ("Shopping", "15%", "AED 1,383"),
        ("Other", "13%", "AED 1,199"),
    ]
    y = 756
    for name, pct, amt in cats:
        card(d, (48, y, 1032, y + 112))
        rr(d, (80, y + 70, 1000, y + 94), 10, CREAM_DEEP)
        rr(d, (80, y + 70, 80 + int(int(pct.strip("%")) * 9.2), y + 94), 10, ROSE)
        text(d, (80, y + 16), name, font(SANS_B, 26), INK)
        text(d, (1000, y + 28), f"{pct}  ·  {amt}", font(SANS, 24), INK_MUTED, "rt")
        y += 128
    nav(d, "Analytics")
    return img


def firefly():
    img, d = phone_base(False)
    top_bar(d, "Firefly III")
    text(d, (56, 168), "One-way PAT push  ·  Cashiro-owned journals only", font(SANS, 24), INK_MUTED)

    card(d, (48, 230, 1032, 560))
    text(d, (80, 258), "Connection", font(SANS_B, 28), INK)
    text(d, (80, 312), "URL", font(SANS, 22), INK_MUTED)
    rr(d, (80, 348, 1000, 416), 16, CREAM_DEEP)
    text(d, (100, 368), "https://firefly.home.arpa", font(SANS, 26), INK)
    text(d, (80, 436), "Personal access token", font(SANS, 22), INK_MUTED)
    rr(d, (80, 470, 700, 532), 16, CREAM_DEEP)
    text(d, (100, 488), "••••••••••••••••", font(SANS_B, 26), INK)
    rr(d, (724, 470, 1000, 532), 16, GREEN)
    text(d, (862, 500), "Connected", font(SANS_B, 24), WHITE, "mm")

    card(d, (48, 588, 1032, 980))
    text(d, (80, 616), "Account mapping", font(SANS_B, 28), INK)
    maps = [
        ("FAB ****4291", "First Abu Dhabi Bank"),
        ("ENBD ****8821", "Emirates NBD"),
        ("Liv", "Liv. bank"),
    ]
    y = 680
    for local, remote in maps:
        text(d, (80, y), local, font(SANS, 26), INK_MUTED)
        text(d, (80, y + 40), "→  " + remote, font(SANS_B, 28), INK)
        y += 92

    card(d, (48, 1012, 1032, 1420))
    text(d, (80, 1040), "Category mapping", font(SANS_B, 28), INK)
    cats = [("Food & Dining", "Groceries"), ("Fuel", "Transport"), ("Bills", "Utilities")]
    y = 1104
    for local, remote in cats:
        text(d, (80, y), f"{local}  →  {remote}", font(SANS, 28), INK)
        y += 64
    text(d, (80, 1328), "Pulled from Firefly for dropdowns only.", font(SANS, 22), INK_MUTED)
    text(d, (80, 1364), "Firefly-native journals are never overwritten.", font(SANS, 22), INK_MUTED)

    rr(d, (48, 1456, 340, 1548), 24, ROSE)
    text(d, (194, 1502), "Test tx", font(SANS_B, 26), WHITE, "mm")
    rr(d, (364, 1456, 700, 1548), 24, WHITE, ROSE, 3)
    text(d, (532, 1502), "Last 30 days", font(SANS_B, 26), ROSE, "mm")
    rr(d, (724, 1456, 1032, 1548), 24, WHITE, ROSE, 3)
    text(d, (878, 1502), "Resync all", font(SANS_B, 26), ROSE, "mm")

    nav(d, "Settings")
    return img


def chat():
    img, d = phone_base(False)
    top_bar(d, "Cashiro AI")
    d.ellipse((460, 280, 620, 440), fill=ROSE_SOFT)
    text(d, (540, 360), "AI", font(SANS_B, 48), ROSE, "mm")
    text(d, (540, 500), "Add a language-model key", font(SANS_B, 40), INK, "mt")
    for i, line in enumerate(
        [
            "Cashiro does not ship a built-in model.",
            "Paste an OpenAI, Anthropic, Gemini,",
            "Groq, OpenRouter, or xAI key in Settings.",
        ]
    ):
        text(d, (540, 580 + i * 40), line, font(SANS, 28), INK_MUTED, "mt")
    rr(d, (240, 780, 840, 880), 28, ROSE)
    text(d, (540, 830), "Add API key", font(SANS_B, 30), WHITE, "mm")
    text(d, (540, 930), "Optional: download on-device", font(SANS, 26), ROSE, "mt")

    chips = ["Food this month?", "Biggest expense?", "Over budget?"]
    x = 70
    for c in chips:
        w = 40 + len(c) * 14
        rr(d, (x, 1120, x + w, 1200), 24, WHITE, ROSE_SOFT, 2)
        text(d, (x + w / 2, 1160), c, font(SANS, 24), INK, "mm")
        x += w + 20

    rr(d, (48, 1640, 1032, 1752), 32, WHITE)
    text(d, (88, 1696), "Ask about your spending…", font(SANS, 28), INK_MUTED, "lm")
    nav(d, "Chat")
    return img


def detail():
    img, d = phone_base(False)
    top_bar(d, "Transaction")
    text(d, (540, 200), "Carrefour", font(SANS_B, 48), INK, "mt")
    text(d, (540, 270), "− AED 186.50", font(SANS_B, 64), RED, "mt")
    text(d, (540, 360), "12 Sep 2026  ·  SMS  ·  FAB ****4291", font(SANS, 24), INK_MUTED, "mt")

    rows = [
        ("Category", "Food & Dining"),
        ("Account", "FAB ****4291"),
        ("Type", "Expense"),
        ("Currency", "AED"),
        ("Firefly", "Synced  ·  pennywise-a1f3…"),
    ]
    y = 430
    for k, v in rows:
        card(d, (48, y, 1032, y + 120))
        text(d, (80, y + 24), k, font(SANS, 22), INK_MUTED)
        text(d, (80, y + 62), v, font(SANS_B, 30), INK)
        y += 136

    rr(d, (48, y + 12, 520, y + 112), 24, ROSE)
    text(d, (284, y + 62), "Resync", font(SANS_B, 28), WHITE, "mm")
    rr(d, (560, y + 12, 1032, y + 112), 24, WHITE, ROSE, 3)
    text(d, (796, y + 62), "Open in Firefly", font(SANS_B, 26), ROSE, "mm")
    nav(d, "Home")
    return img


def banner():
    w, h = 1920, 768
    img = Image.new("RGB", (w, h), CREAM)
    overlay = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    od = ImageDraw.Draw(overlay)
    od.ellipse((-240, -280, 720, 560), fill=(*ROSE, 48))
    od.ellipse((1280, -160, 2100, 520), fill=(255, 170, 110, 55))
    od.ellipse((900, 480, 1700, 980), fill=(255, 214, 186, 80))
    img = Image.alpha_composite(img.convert("RGBA"), overlay).convert("RGB")
    d = ImageDraw.Draw(img)
    text(d, (96, 210), "Cashiro", font(SERIF_B, 118), INK)
    text(d, (104, 360), "SMS-first money tracker for the UAE", font(SANS, 38), INK_MUTED)
    pills = ["AED", "FAB · ENBD · Liv", "Firefly III one-way", "BYOK AI"]
    x = 104
    for p in pills:
        tw = d.textlength(p, font=font(SANS_B, 26))
        rr(d, (x, 460, x + tw + 56, 528), 28, WHITE, ROSE, 3)
        text(d, (x + 28 + tw / 2, 494), p, font(SANS_B, 26), ROSE, "mm")
        x += tw + 76
    text(d, (104, 620), "Local SMS ledger  ·  optional Firefly push  ·  English AED", font(SANS, 28), INK_MUTED)
    # mini phone card on the right
    rr(d, (1320, 140, 1824, 628), 36, WHITE)
    text(d, (1572, 190), "This month", font(SANS, 24), INK_MUTED, "mt")
    text(d, (1572, 250), "AED 42,180.42", font(SANS_B, 42), INK, "mt")
    text(d, (1572, 310), "FAB  ·  ENBD  ·  Liv", font(SANS, 22), ROSE, "mt")
    text(d, (1388, 380), "Carrefour", font(SANS_B, 26), INK)
    text(d, (1776, 380), "− 186.50", font(SANS_B, 26), RED, "rt")
    text(d, (1388, 440), "ENOC", font(SANS_B, 26), INK)
    text(d, (1776, 440), "− 92.00", font(SANS_B, 26), RED, "rt")
    text(d, (1388, 500), "Salary · FAB", font(SANS_B, 26), INK)
    text(d, (1776, 500), "+ 18,400.00", font(SANS_B, 26), GREEN, "rt")
    return img


def feature_graphic():
    return banner().resize((1024, 500), Image.Resampling.LANCZOS)


def save(img, path: Path):
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, "PNG", optimize=True)
    print("wrote", path, img.size)


def main():
    b = banner()
    save(b, ROOT / "banner.png")
    save(feature_graphic(), FASTLANE / "featureGraphic.png")

    mapping = [
        (home(False), "home-light.png", "1.png"),
        (analytics(), "analytics-light.png", "2.png"),
        (firefly(), "firefly-light.png", "3.png"),
        (chat(), "chat-light.png", "4.png"),
        (detail(), "transaction-detail-light.png", "5.png"),
        (home(True), "home-dark.png", "6.png"),
    ]
    for img, shot, fl in mapping:
        save(img, SHOTS / shot)
        save(img, PHONE / fl)


if __name__ == "__main__":
    main()
