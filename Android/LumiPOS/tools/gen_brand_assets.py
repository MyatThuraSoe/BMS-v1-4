"""
Regenerate LumiPOS brand assets from the single source of truth used by the
web frontend:  frontend/public/Lumi-logo.png

Run from the repo root:
    C:/Users/ASUS/.workbuddy-ai/binaries/python/envs/default/Scripts/python.exe \
        Android/LumiPOS/tools/gen_brand_assets.py

Outputs (all under Android/LumiPOS/src/main/res):
    drawable-nodpi/lumi_logo.png                      in-app brand mark (512px)
    drawable-{m,h,xh,xxh,xxxh}dpi/ic_launcher_foreground.png
                                                      adaptive-icon foreground layer
    mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.webp      legacy square launcher icon
    mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher_round.webp legacy round launcher icon
"""

import os
from PIL import Image, ImageDraw

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
SRC = os.path.join(REPO, "frontend", "public", "Lumi-logo.png")
RES = os.path.join(REPO, "Android", "LumiPOS", "src", "main", "res")

# adaptive-icon canvas is 108dp; the guaranteed-visible safe zone is the inner 72dp.
SAFE_ZONE = 72.0 / 108.0
FOREGROUND_SCALE = 0.68  # logo occupies ~68% of the canvas -> inside the safe zone

# density bucket -> scale factor relative to mdpi (1x)
DENSITIES = {
    "mdpi": 1,
    "hdpi": 1.5,
    "xhdpi": 2,
    "xxhdpi": 3,
    "xxxhdpi": 4,
}

LEGACY_ICON_DP = 48  # launcher icon base size in dp

BACKGROUND = (255, 255, 255, 255)  # white plate behind the mark


def square_mark(path):
    """Load the logo, trim transparent padding and return a centred square RGBA image."""
    im = Image.open(path).convert("RGBA")
    bbox = im.getchannel("A").getbbox()
    if bbox:
        im = im.crop(bbox)
    side = max(im.width, im.height)
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.paste(im, ((side - im.width) // 2, (side - im.height) // 2), im)
    return canvas


def paste_centred(canvas, mark, target_size):
    scaled = mark.resize((target_size, target_size), Image.LANCZOS)
    off = (canvas.width - target_size) // 2
    canvas.paste(scaled, (off, off), scaled)


def write_png(img, *parts):
    out = os.path.join(RES, *parts)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    img.save(out, "PNG", optimize=True)
    print("  wrote", os.path.relpath(out, REPO), img.size)


def main():
    if not os.path.exists(SRC):
        raise SystemExit("source logo not found: " + SRC)

    mark = square_mark(SRC)
    print("source:", os.path.relpath(SRC, REPO), "-> trimmed square", mark.size)

    # 1. In-app brand mark (drawer header / about screen).
    write_png(mark.resize((512, 512), Image.LANCZOS), "drawable-nodpi", "lumi_logo.png")

    # 2. Adaptive launcher icon foreground (transparent, logo inside safe zone).
    for bucket, factor in DENSITIES.items():
        canvas_px = int(round(108 * factor))
        canvas = Image.new("RGBA", (canvas_px, canvas_px), (0, 0, 0, 0))
        paste_centred(canvas, mark, int(round(canvas_px * FOREGROUND_SCALE)))
        write_png(canvas, "drawable-%s" % bucket, "ic_launcher_foreground.png")

    # 3. Legacy launcher icons (pre-adaptive fallback, still used by some launchers).
    for bucket, factor in DENSITIES.items():
        side = int(round(LEGACY_ICON_DP * factor))

        square = Image.new("RGBA", (side, side), BACKGROUND)
        paste_centred(square, mark, int(round(side * 0.84)))
        out = os.path.join(RES, "mipmap-%s" % bucket, "ic_launcher.webp")
        square.save(out, "WEBP", quality=95, method=6)
        print("  wrote", os.path.relpath(out, REPO), square.size)

        circle_mask = Image.new("L", (side * 4, side * 4), 0)
        ImageDraw.Draw(circle_mask).ellipse((0, 0, side * 4 - 1, side * 4 - 1), fill=255)
        circle_mask = circle_mask.resize((side, side), Image.LANCZOS)
        circle = Image.new("RGBA", (side, side), BACKGROUND)
        paste_centred(circle, mark, int(round(side * 0.74)))
        circle.putalpha(circle_mask)
        out = os.path.join(RES, "mipmap-%s" % bucket, "ic_launcher_round.webp")
        circle.save(out, "WEBP", quality=95, method=6)
        print("  wrote", os.path.relpath(out, REPO), circle.size)

    print("done.")


if __name__ == "__main__":
    main()
