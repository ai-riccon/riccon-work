# Worker OCR 镜像 SBOM 与许可检查

M05-O1a 要求随镜像分发的 Tesseract / Leptonica / tessdata 有 SBOM 与许可记录
（ADR-0023 §4.6、AGENTS.md `constraints.licensing`）。本目录只放说明与命令，
不提交生成的 SBOM（构建时间、包元数据会随基础镜像更新而变化）。

## 前置

- 已构建 worker 镜像（见 `deploy/Dockerfile.worker`）：
  `docker build -f deploy/Dockerfile.worker -t riccon-worker:local .`
- `trivy`（合同 `tools.required`）或 `syft` 任一可用。

## 生成 SBOM（CycloneDX）

```bash
trivy image --format cyclonedx --output /tmp/riccon-worker.cdx.json riccon-worker:local
```

## 许可检查

```bash
# 只看许可，不拉漏洞库；输出到 stdout 便于证据记录
trivy image --scanners license --format table riccon-worker:local
```

## 期望结果

- 组件中包含 `tesseract-ocr`、`libtesseract5`、`liblept5`、
  `tesseract-ocr-eng`、`tesseract-ocr-osd`，版本与 `deploy/Dockerfile.worker`
  中的固定版本一致。
- 许可为 Apache-2.0 / BSD-2-Clause；不得出现 AGPL/SSPL/GPL（MySQL 驱动例外
  见 DEC-0003，不涉及本镜像）或公有云 OCR SDK。
- `chi_sim.traineddata` / `eng.traineddata` 属于数据文件，不一定出现在包级 SBOM；
  其来源、commit 与 SHA256 记录在仓库根 `NOTICE` 与镜像内
  `/opt/riccon/ocr/manifest.json`。

## 已知误报

- `trivy fs` 扫描模型/权重目录可能把 `.traineddata` 当二进制误报；镜像级扫描
  基于包数据库，不受影响。权重哈希以 `NOTICE` 与 manifest 为准。
