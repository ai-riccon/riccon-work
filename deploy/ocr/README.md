# deploy/ocr — 零出网 OCR 冒烟夹具

M05-O1a 提供的固定夹具，用于验证 worker 镜像内的 Tesseract 在 `--network none`
下可独立完成 OCR（M05-AC-05、ADR-0023 §4.6/§4.7）。

| 文件             | 说明                                                              |
| ---------------- | ----------------------------------------------------------------- |
| `sample.png`     | 1100x220 灰度 PNG，白底黑字 `RICCON OCR 12345`（8 KB 左右）       |
| `gen_fixture.py` | 重新生成夹具的脚本（需要 Pillow + DejaVu 字体，仅在本地手动运行） |

验证命令（镜像需先构建，见 `deploy/Dockerfile.worker`）：

```bash
docker run --rm --network none \
  -v "$PWD/deploy/ocr:/ocr:ro" \
  --entrypoint tesseract \
  riccon-worker:local \
  /ocr/sample.png stdout -l chi_sim+eng --psm 7
# 期望输出包含：RICCON OCR 12345
```

说明：

- 夹具只覆盖“原生库可加载、语言包可用、离线可出文本”，不衡量精度；
  M05-B5 的精度基线（中文 CER ≤ 10%、英文 CER ≤ 5%）使用 `IT-KB-104` 的 20 张夹具。
- 夹具语言为英文，`chi_sim` 通过 `tesseract --list-langs` 验证已随镜像分发；
  中文样本属于 B5 测试夹具写范围（`apps/worker/src/test/resources/**`）。
- O1b（TASK-0175）的 `scripts/ops/ocr-check` 复用镜像与该夹具，不在本任务写。
