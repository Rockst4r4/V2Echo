import os
from PIL import Image, ImageDraw

os.makedirs("assets", exist_ok=True)
out_ico = "assets/icon.ico"
out_png = "assets/icon.png"

custom_candidates = [
    "../branding/logo.png",
    "../branding/logo.jpg",
    "../branding/logo.jpeg",
]

custom_src = next((p for p in custom_candidates if os.path.exists(p)), None)

if custom_src:
    print(f"Using custom logo: {custom_src}")
    img = Image.open(custom_src).convert("RGBA")
    side = min(img.size)
    left = (img.width - side) // 2
    top = (img.height - side) // 2
    img = img.crop((left, top, left + side, top + side)).resize((512, 512), Image.LANCZOS)
else:
    # بازسازی دقیق لوگوی سپر فیروزه‌ای + رعدوبرق سبز روی پس‌زمینه تیره
    img = Image.new("RGBA", (512, 512), (17, 25, 31, 255))
    draw = ImageDraw.Draw(img)
    cyan = (34, 228, 255, 255)
    green = (124, 255, 79, 255)

    # سپر فیروزه‌ای
    draw.line([(265, 95), (165, 118), (118, 145), (118, 240), (150, 325), (242, 405)], fill=cyan, width=18, joint="curve")
    draw.line([(295, 108), (350, 128), (392, 152), (392, 212), (310, 212)], fill=cyan, width=18, joint="curve")
    draw.line([(304, 260), (390, 260), (370, 336), (262, 406)], fill=cyan, width=18, joint="curve")

    # رعدوبرق سبز مرکزی
    bolt = [(296, 86), (186, 264), (248, 264), (210, 412), (324, 228), (262, 228)]
    draw.polygon(bolt, fill=(17, 25, 31, 255), outline=green)
    for i in range(len(bolt)):
        draw.line([bolt[i], bolt[(i + 1) % len(bolt)]], fill=green, width=16)

img.save(out_png)
img.save(out_ico, sizes=[(256, 256), (128, 128), (64, 64), (48, 48), (32, 32), (16, 16)])
print("Windows icon generated:", out_ico)
