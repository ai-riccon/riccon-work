#!/usr/bin/env bash
# M05-O1a worker OCR 镜像验收（ADR-0023 §4.6/§4.7、M05-AC-05）。
#
# 覆盖：
#   1. docker build 自包含成功（jar 在镜像内编译，语言包构建期校验）；
#   2. 镜像内 tesseract/leptonica 版本、chi_sim/eng 语言包、指纹 manifest；
#   3. 非 root（uid 10001）与 `--network none` 零出网 OCR；
#   4. 镜像内无代理/密钥类环境变量；
#   5. SBOM（CycloneDX）+ 许可检查：OCR 组件为 Apache-2.0/BSD，无 AGPL/SSPL、无公有云 OCR SDK。
#
# 依赖：docker、trivy、python3（合同 tools.required）。
# 用法：bash deploy/ocr/verify.sh（默认镜像 tag riccon-worker-ocr:o1a，可用 IMG 覆盖）。
set -euo pipefail

cd "$(dirname "$0")/../.."
img="${IMG:-riccon-worker-ocr:o1a}"
out_dir="$(mktemp -d)"
trap 'rm -rf "$out_dir"' EXIT

echo "== docker build"
docker build -f deploy/Dockerfile.worker -t "$img" .

echo "== tesseract --version"
docker run --rm --network none --entrypoint tesseract "$img" --version

echo "== tesseract --list-langs"
langs="$(docker run --rm --network none --entrypoint tesseract "$img" --list-langs)"
echo "$langs"
grep -qx 'chi_sim' <<< "$langs"
grep -qx 'eng' <<< "$langs"

echo "== OCR 指纹 manifest"
manifest="$(docker run --rm --network none --entrypoint cat "$img" /opt/riccon/ocr/manifest.json)"
echo "$manifest"
grep -q '"engine":"tesseract"' <<< "$manifest"

echo "== 非 root"
test "$(docker run --rm --network none --entrypoint id "$img" -u)" = "10001"

echo "== 零出网 OCR（--network none）"
ocr_out="$(docker run --rm --network none -v "$PWD/deploy/ocr:/ocr:ro" \
  --entrypoint tesseract "$img" /ocr/sample.png stdout -l chi_sim+eng --psm 7)"
echo "$ocr_out"
grep -q 'RICCON OCR 12345' <<< "$ocr_out"

echo "== 镜像环境无代理/密钥"
if docker run --rm --network none --entrypoint env "$img" \
  | grep -qiE '^(https?|all|no)_proxy=|password=|secret=|token='; then
  echo "镜像环境变量疑似包含代理或密钥" >&2
  exit 1
fi

echo "== SBOM（CycloneDX）"
trivy image --format cyclonedx --scanners license --quiet -o "$out_dir/sbom.cdx.json" "$img"
ls -l "$out_dir/sbom.cdx.json"

echo "== 许可检查（OCR 组件 + 禁止项）"
trivy image --scanners license --format json --quiet -o "$out_dir/license.json" "$img"
python3 - "$out_dir/license.json" "$out_dir/sbom.cdx.json" <<'PY'
import json
import sys

license_path, sbom_path = sys.argv[1], sys.argv[2]
license_report = json.load(open(license_path, encoding="utf-8"))
packages = []
for result in license_report.get("Results", []):
    for pkg in result.get("Packages") or []:
        packages.append(
            (
                pkg.get("Name") or "",
                pkg.get("Version") or "",
                ",".join(pkg.get("Licenses") or []),
            )
        )

ocr = [p for p in packages if any(k in p[0].lower() for k in ("tesseract", "lept"))]
print("OCR 组件：")
for name, version, lic in ocr:
    print(f"  - {name} {version} [{lic or '未声明'}]")

required = {"tesseract-ocr", "libtesseract5", "liblept5"}
missing = required - {p[0] for p in ocr}
if missing:
    raise SystemExit(f"缺少 OCR 组件：{sorted(missing)}")
for name, version, lic in ocr:
    if name in {"tesseract-ocr", "libtesseract5"} and "Apache-2.0" not in lic:
        raise SystemExit(f"{name} 许可应为 Apache-2.0，实际 {lic!r}")

banned = [p for p in packages if any(x in p[2].upper() for x in ("AGPL", "SSPL"))]
if banned:
    raise SystemExit(f"发现禁止的 AGPL/SSPL 组件：{banned}")

cloud_kw = ("azure-ai", "google-cloud-vision", "aliyun-ocr", "tencentcloud-ocr", "textract", "ocr.aliyuncs")
cloud = [p for p in packages if any(k in p[0].lower() for k in cloud_kw)]
if cloud:
    raise SystemExit(f"发现公有云 OCR 依赖：{cloud}")

sbom = json.load(open(sbom_path, encoding="utf-8"))
components = sbom.get("components", [])
print(f"CycloneDX 组件数：{len(components)}")
print("许可检查通过：OCR 组件齐全、无 AGPL/SSPL、无公有云 OCR。")
PY

echo "o1a-ok: 镜像可离线 OCR，语言包与许可/SBOM 校验通过"
