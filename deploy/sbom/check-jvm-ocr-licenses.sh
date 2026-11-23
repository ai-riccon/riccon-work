#!/usr/bin/env bash
# M05-B5 JVM OCR 依赖许可断言（ADR-0023 §4.6、AGENTS.md constraints.licensing）。
#
# 断言三件事：
#   1. apps/worker 实际依赖树中的 OCR 相关坐标/版本与 NOTICE 登记的一致；
#   2. NOTICE 确实登记了每个组件的版本与许可证（含 JNA 双许可选择）；
#   3. 依赖树中不出现 AGPL/SSPL、公有云 OCR SDK，以及 pom 明确排除的 Tess4J 附加依赖。
#
# 只用仓库已有工具（mvnw、grep），不新增依赖、不联网（Maven 以 offline 模式运行）。
# 用法：bash deploy/sbom/check-jvm-ocr-licenses.sh
set -euo pipefail

cd "$(dirname "$0")/../.."

notice="NOTICE"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
tree="$tmp_dir/worker-tree.txt"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

echo "== 依赖树（apps/worker，offline）"
./mvnw -o -q -pl apps/worker dependency:tree -DoutputFile="$tree" >/dev/null
test -s "$tree" || fail "dependency:tree 未生成输出"

echo "== 实际引入的 OCR 相关依赖"
require_tree() {
  local coord="$1"
  grep -qF -- "$coord" "$tree" || fail "依赖树缺少 $coord"
  echo "  ok $coord"
}
require_tree "net.sourceforge.tess4j:tess4j:jar:5.20.0"
require_tree "net.sourceforge.lept4j:lept4j:jar:1.24.0"
require_tree "net.java.dev.jna:jna:jar:5.19.1"
require_tree "com.github.jai-imageio:jai-imageio-core:jar:1.4.0"
require_tree "commons-io:commons-io:jar:2.21.0"

echo "== 被排除的 Tess4J 附加依赖不得出现"
for banned in \
  "org.apache.pdfbox:pdfbox-tools" \
  "org.apache.pdfbox:jbig2-imageio" \
  "org.jboss:jboss-vfs"; do
  if grep -qF -- "$banned" "$tree"; then
    fail "依赖树出现应排除的 $banned（检查 apps/worker/pom.xml 的 tess4j exclusions）"
  fi
  echo "  ok 未出现 $banned"
done

echo "== 禁止许可与公有云 OCR"
if grep -Eiq 'agpl|sspl|azure-ai|google-cloud-vision|aliyun-ocr|tencentcloud-ocr|textract' "$tree"; then
  grep -Ein 'agpl|sspl|azure-ai|google-cloud-vision|aliyun-ocr|tencentcloud-ocr|textract' "$tree" >&2 || true
  fail "依赖树出现禁止许可或公有云 OCR 依赖"
fi
echo "  ok 无 AGPL/SSPL、无公有云 OCR"

echo "== NOTICE 登记项"
require_notice() {
  local text="$1"
  grep -qF -- "$text" "$notice" || fail "NOTICE 缺少：$text"
  echo "  ok $text"
}
require_notice "Tess4J 5.20.0"
require_notice "net.sourceforge.tess4j:tess4j:5.20.0"
require_notice "Lept4J 1.24.0"
require_notice "net.sourceforge.lept4j:lept4j:1.24.0"
require_notice "Java Native Access (JNA) 5.19.1"
require_notice "net.java.dev.jna:jna:5.19.1"
require_notice "LGPL-2.1-or-later 或 Apache License 2.0"
require_notice "本项目按 Apache-2.0 使用"
require_notice "jai-imageio-core 1.4.0"
require_notice "com.github.jai-imageio:jai-imageio-core:1.4.0"
require_notice "BSD 3-Clause"
require_notice "Apache Commons IO 2.21.0"
require_notice "commons-io:commons-io:2.21.0"
require_notice "不含 AGPL/SSPL 组件"

echo "== 产物核对（若已构建 fat jar）"
jar="$(ls apps/worker/target/riccon-worker-*-exec.jar 2>/dev/null | head -n 1 || true)"
if [ -n "$jar" ]; then
  echo "  扫描 $jar"
  # 一次读取清单，避免 grep -q 提前关闭管道在 pipefail 下误判。
  listing="$(unzip -l "$jar")"
  for banned in "pdfbox-tools" "jbig2-imageio" "jboss-vfs"; do
    if grep -qF -- "BOOT-INF/lib/$banned" <<< "$listing"; then
      fail "产物 BOOT-INF/lib 出现应排除的 $banned"
    fi
    echo "  ok 产物无 $banned"
  done
  for keep in "tess4j-5.20.0.jar" "lept4j-1.24.0.jar" "jna-5.19.1.jar" \
    "jai-imageio-core-1.4.0.jar" "commons-io-2.21.0.jar"; do
    grep -qF -- "BOOT-INF/lib/$keep" <<< "$listing" \
      || fail "产物 BOOT-INF/lib 缺少 $keep"
    echo "  ok 产物含 $keep"
  done
else
  echo "  skip 未找到 fat jar（先 ./mvnw -pl apps/worker -am -DskipTests package）"
fi

echo "jvm-ocr-license-ok"
