from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[2]
background_path = ROOT / "assets" / "novvin-promo" / "novvin-social-background.png"
logo_path = ROOT / "Conectate-web" / "public" / "novvin-logo.png"
output_path = ROOT / "assets" / "novvin-promo" / "novvin-social-poster.png"


def font(size: int, bold: bool = False):
    name = "segoeuib.ttf" if bold else "segoeui.ttf"
    return ImageFont.truetype(Path(r"C:\Windows\Fonts") / name, size)


canvas = Image.open(background_path).convert("RGBA").resize((1080, 1920), Image.Resampling.LANCZOS)
draw = ImageDraw.Draw(canvas)

# A compact, high-contrast card keeps the real copy legible on any feed.
card = (88, 270, 992, 1675)
draw.rounded_rectangle(card, radius=46, fill=(4, 18, 56, 208), outline=(77, 226, 224, 145), width=3)

logo = Image.open(logo_path).convert("RGBA").resize((250, 250), Image.Resampling.LANCZOS)
canvas.alpha_composite(logo, (415, 315))

title = "Novvin"
subtitle = "Conversaciones que conectan"
title_box = draw.textbbox((0, 0), title, font=font(86, True))
draw.text(((1080 - (title_box[2] - title_box[0])) / 2, 565), title, font=font(86, True), fill=(235, 255, 255, 255))
sub_box = draw.textbbox((0, 0), subtitle, font=font(38, True))
draw.text(((1080 - (sub_box[2] - sub_box[0])) / 2, 675), subtitle, font=font(38, True), fill=(77, 226, 224, 255))

features = [
    "Chats, grupos y comunidades",
    "Llamadas y salas de voz",
    "Videos, perfiles y retos",
]
for index, feature in enumerate(features):
    y = 860 + index * 135
    draw.ellipse((168, y + 8, 208, y + 48), fill=(77, 226, 224, 255))
    draw.text((245, y), feature, font=font(40, True), fill=(245, 250, 255, 255))

draw.rounded_rectangle((150, 1325, 930, 1460), radius=32, fill=(77, 226, 224, 255))
cta = "Disponible gratis en Google Play"
cta_box = draw.textbbox((0, 0), cta, font=font(34, True))
draw.text(((1080 - (cta_box[2] - cta_box[0])) / 2, 1370), cta, font=font(34, True), fill=(3, 27, 62, 255))

url = "play.google.com/store/apps/details?id=conectate.com"
url_box = draw.textbbox((0, 0), url, font=font(24))
draw.text(((1080 - (url_box[2] - url_box[0])) / 2, 1530), url, font=font(24), fill=(214, 238, 255, 255))

canvas.convert("RGB").save(output_path, quality=95)
print(output_path)
