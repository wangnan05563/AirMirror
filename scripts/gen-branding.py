"""Generate AirMirror brand assets: logo, Android launcher icons, social preview.

Design is intentionally geometric (two screens + a mirror arrow) so it reads at
16 px and does not depend on a specific typeface. Colors come from ui/Theme.kt.
Requires Pillow (dev-machine only; not an app dependency).
"""
import os

from PIL import Image, ImageDraw, ImageFont

BG = (11, 31, 58, 255)          # Theme.BACKGROUND #0B1F3A
SURFACE = (18, 49, 79, 255)     # Theme.SURFACE #12314F
ACCENT = (23, 195, 178, 255)    # Theme.ACCENT #17C3B2
WHITE = (255, 255, 255, 255)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BRAND = os.path.join(ROOT, "branding")
RES = os.path.join(ROOT, "app", "src", "main", "res")
# 用系统的 WINDIR 推导字体目录，避免把某个机器的盘符写死在仓里
FONT_DIR = os.path.join(os.environ.get("WINDIR", ""), "Fonts")
FONT_BOLD = os.path.join(FONT_DIR, "msyhbd.ttc")
FONT_REGULAR = os.path.join(FONT_DIR, "msyh.ttc")


def logo(size: int) -> Image.Image:
    image = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    stroke = max(2, int(size * 0.035))
    draw.rounded_rectangle([0, 0, size - 1, size - 1], radius=int(size * 0.22), fill=BG)
    # left screen (source, accent) / right screen (target, white)
    draw.rounded_rectangle(
        [size * 0.16, size * 0.30, size * 0.46, size * 0.60],
        radius=int(size * 0.03), outline=ACCENT, width=stroke,
    )
    draw.rounded_rectangle(
        [size * 0.54, size * 0.40, size * 0.84, size * 0.70],
        radius=int(size * 0.03), outline=WHITE, width=stroke,
    )
    # mirror arrow between the two screens
    mid_y = size * 0.50
    draw.line([size * 0.47, mid_y, size * 0.53, mid_y], fill=ACCENT, width=stroke)
    draw.line([size * 0.505, mid_y - stroke * 1.6, size * 0.53, mid_y], fill=ACCENT, width=stroke)
    draw.line([size * 0.505, mid_y + stroke * 1.6, size * 0.53, mid_y], fill=ACCENT, width=stroke)
    return image


SVG = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="512" height="512">
  <rect width="512" height="512" rx="112" fill="#0B1F3A"/>
  <rect x="82" y="154" width="154" height="154" rx="15" fill="none" stroke="#17C3B2" stroke-width="18"/>
  <rect x="276" y="205" width="154" height="154" rx="15" fill="none" stroke="#FFFFFF" stroke-width="18"/>
  <line x1="241" y1="256" x2="271" y2="256" stroke="#17C3B2" stroke-width="18"/>
  <polyline points="259,239 273,256 259,273" fill="none" stroke="#17C3B2" stroke-width="18"
            stroke-linecap="round" stroke-linejoin="round"/>
</svg>
"""


def social_preview() -> Image.Image:
    width, height = 1280, 640
    image = Image.new("RGB", (width, height), BG[:3])
    draw = ImageDraw.Draw(image)
    # vertical gradient: BACKGROUND -> SURFACE
    for y in range(height):
        t = y / (height - 1)
        color = tuple(int(BG[i] + (SURFACE[i] - BG[i]) * t) for i in range(3))
        draw.line([(0, y), (width, y)], fill=color)
    icon = logo(384)
    image.paste(icon, (88, 128), icon)
    draw.text((540, 168), "AirMirror", font=ImageFont.truetype(FONT_BOLD, 104), fill=WHITE)
    draw.text((544, 304), "局域网投屏接收端", font=ImageFont.truetype(FONT_BOLD, 52), fill=ACCENT)
    draw.text(
        (546, 400),
        "Android 4.4+ · 免费 · 开源 · 无需账号",
        font=ImageFont.truetype(FONT_REGULAR, 34),
        fill=(230, 240, 255),
    )
    draw.line([(546, 486), (1180, 486)], fill=ACCENT, width=3)
    return image


def main():
    os.makedirs(BRAND, exist_ok=True)
    with open(os.path.join(BRAND, "logo.svg"), "w", encoding="utf-8") as f:
        f.write(SVG)
    for size in (512, 192, 48):
        logo(size).save(os.path.join(BRAND, "logo-%d.png" % size))
    logo(256).save(
        os.path.join(BRAND, "logo.ico"),
        sizes=[(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)],
    )
    social_preview().save(os.path.join(BRAND, "social-preview-1280x640.png"))

    # Android launcher icons (legacy PNG set; adaptive icons are not used to keep minSdk 19 simple)
    for density, px in {
        "mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192,
    }.items():
        folder = os.path.join(RES, "mipmap-%s" % density)
        os.makedirs(folder, exist_ok=True)
        logo(px).save(os.path.join(folder, "ic_launcher.png"))
    print("branding written:", BRAND)


if __name__ == "__main__":
    main()
