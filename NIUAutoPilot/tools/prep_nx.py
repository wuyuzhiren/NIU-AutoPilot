# -*- coding: utf-8 -*-
"""预处理紫色小牛NX车辆素材：去白底 + 缩放 + 微调透明边缘"""
import os
from PIL import Image

SRC = r"E:\小牛自用\NIUAutoPilot\app\src\main\res\drawable-nodpi"
OUT = r"E:\小牛自用\NIUAutoPilot\app\src\main\res\drawable-nodpi"
TARGET_W = 900  # 手机端合理尺寸

def remove_white_bg(img, tol=235, feather=1):
    """白底转透明，边缘轻度羽化防白边"""
    img = img.convert("RGBA")
    px = img.load()
    w, h = img.size
    # 先按白色阈值处理
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if r > tol and g > tol and b > tol:
                px[x, y] = (r, g, b, 0)
    # 边缘羽化：对透明像素的1环邻域做半透明过渡（简单处理）
    if feather > 0:
        img2 = img.copy()
        px2 = img2.load()
        for y in range(1, h - 1):
            for x in range(1, w - 1):
                r, g, b, a = px[x, y]
                if a == 0:
                    continue
                # 如果右侧或下侧是透明，则本像素透明度减半（消除锯齿白边）
                r2, g2, b2, a2 = px[x + 1, y]
                r3, g3, b3, a3 = px[x, y + 1]
                if a2 == 0 or a3 == 0:
                    px2[x, y] = (r, g, b, max(0, a - 60))
        img = img2
    return img

for name in ["nx_body.png", "nx_bucket.png"]:
    p = os.path.join(SRC, name)
    im = Image.open(p)
    print(name, "orig:", im.size)
    # 等比缩放
    w, h = im.size
    if w > TARGET_W:
        nh = int(h * TARGET_W / w)
        im = im.resize((TARGET_W, nh), Image.LANCZOS)
    im = remove_white_bg(im)
    # 计算非透明边界并裁剪空白
    bbox = im.getbbox()
    if bbox:
        im = im.crop(bbox)
    out_name = name.replace(".png", "_alpha.png")
    im.save(os.path.join(OUT, out_name), "PNG")
    print(" ->", out_name, im.size)

print("DONE")
