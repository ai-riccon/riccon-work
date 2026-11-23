# Worker OCR 镜像 SBOM 与许可检查

M05-O1a 要求随镜像分发的 Tesseract / Leptonica / tessdata 有 SBOM 与许可记录
（ADR-0023 §4.6、AGENTS.md `constraints.licensing`）；M05-B5 追加 worker jar 内的
JVM 绑定（Tess4J/Lept4J/JNA/jai-imageio-core）与 commons-io 的许可核对。本目录只放
说明与命令，不提交生成的 SBOM（构建时间、包元数据会随基础镜像更新而变化）。

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

## JVM OCR 依赖（M05-B5）

`trivy image` / `trivy fs` 只读基础镜像的包数据库与磁盘上的 POM 文件，看不到
worker 可执行 jar 内 `BOOT-INF/lib` 的 Maven 依赖（实测对 fat jar 与解压后的 jar
目录都返回 0 个包）。因此 JVM 侧 OCR 绑定用仓库已有工具断言，不引入新依赖：

```bash
bash deploy/sbom/check-jvm-ocr-licenses.sh
```

脚本断言 `apps/worker` 依赖树、`apps/worker/pom.xml` 排除项与仓库根 `NOTICE`
三者一致：

| 坐标                                      | 版本   | 许可                                                   | 来源                         |
| ----------------------------------------- | ------ | ------------------------------------------------------ | ---------------------------- |
| `net.sourceforge.tess4j:tess4j`           | 5.20.0 | Apache-2.0                                             | 直接依赖（`tess4j.version`） |
| `net.sourceforge.lept4j:lept4j`           | 1.24.0 | Apache-2.0                                             | Tess4J 传递                  |
| `net.java.dev.jna:jna`                    | 5.19.1 | LGPL-2.1-or-later 或 Apache-2.0（本项目按 Apache-2.0） | Tess4J 传递                  |
| `com.github.jai-imageio:jai-imageio-core` | 1.4.0  | BSD 3-Clause（含核设施免责声明）                       | Tess4J 传递                  |
| `commons-io:commons-io`                   | 2.21.0 | Apache-2.0                                             | poi-ooxml 传递（非 Tess4J）  |

Tess4J 的 `pdfbox` / `pdfbox-tools` / `jbig2-imageio` / `jboss-vfs` /
`slf4j-api` 已在 `apps/worker/pom.xml` 排除；脚本断言这些坐标不出现在依赖树中，
并在已构建 fat jar 时额外断言产物 `BOOT-INF/lib` 内没有前三个 jar。

人工复核可以用 `unzip` 直接核对产物与许可原文（`trivy fs --scanners license` 实测对
fat jar 与解压后的 jar 目录都不可靠——返回 0 个包或只有计数——因此不纳入门禁）：

```bash
# 产物内 OCR 相关 jar 清单（应为 5 个，且无 pdfbox-tools/jbig2-imageio/jboss-vfs）
unzip -l apps/worker/target/riccon-worker-*-exec.jar \
  | grep -E 'BOOT-INF/lib/(tess4j|lept4j|jna|jai-imageio|commons-io)'
# 许可原文示例（JNA）
unzip -p apps/worker/target/riccon-worker-*-exec.jar BOOT-INF/lib/jna-5.19.1.jar > /tmp/jna.jar
unzip -p /tmp/jna.jar META-INF/LICENSE
```

许可原文位置：

- JNA：jar 内 `META-INF/LICENSE`、`META-INF/AL2.0`、`META-INF/LGPL2.1`；
- jai-imageio-core：jar 内 `META-INF/LICENSE`、`META-INF/NOTICE`；
- commons-io：jar 内 `META-INF/LICENSE.txt`、`META-INF/NOTICE.txt`；
- Tess4J / Lept4J：jar 未内嵌 LICENSE，以上游仓库 Apache-2.0 全文为准。

新增或升级 OCR JVM 依赖时，必须同步更新 `apps/worker/pom.xml`、仓库根 `NOTICE`
与本表，再跑脚本。

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
