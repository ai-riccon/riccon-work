#!/usr/bin/env python3
"""生成零出网 OCR 冒烟用固定夹具（M05-O1a）。

仅在需要重新生成 `deploy/ocr/sample.png` 时手动运行；运行镜像的验证不依赖本脚本。
夹具是 1100x220 灰度 PNG，白底黑字 "RICCON OCR 12345"，用于断言镜像内 Tesseract
在 `--network none` 下能识别出非空文本。

重新生成（任意带 Pillow 与 DejaVu 字体的环境）：

    pip install pillow
    apt-get install -y fonts-dejavu-core   # Debian/Ubuntu
    python3 deploy/ocr/gen_fixture.py deploy/ocr/sample.png

注意：字体只影响生成过程；镜像内不包含该字体，也不在运行期渲染。
"""

import sys

from PIL import Image, ImageDraw, ImageFont

TEXT = "RICCON OCR 12345"
FONT_CANDIDATES = [
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf",
]


def main() -> int:
    out = sys.argv[1] if len(sys.argv) > 1 else "sample.png"
    font_path = next(
        (p for p in FONT_CANDIDATES if __import__("os").path.exists(p)), None
    )
    if font_path is None:
        raise SystemExit("未找到 DejaVuSans-Bold.ttf；请安装 fonts-dejavu-core 后重试")
    font = ImageFont.truetype(font_path, 72)
    image = Image.new("L", (1100, 220), 255)
    ImageDraw.Draw(image).text((50, 60), TEXT, fill=0, font=font)
    image.save(out, optimize=True)
    print(f"wrote {out} {image.size[0]}x{image.size[1]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
