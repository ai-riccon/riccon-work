---
review_id: REV-M01-001
module_id: M01
verifier: riccon/verifier
reviewer:
  agent: riccon/verifier
  session: ses_e171991cdffeJcrAMAd4tR4azl
  model: deepseek-v4.1-flash
  independent: true
verdict: pass
dod:
  acceptance: true
  build: true
  tests: true
  coverage: true
  e2e: true
  review: true
  docs: true
  evidence: true
dod_evidence:
  acceptance:
    - EV-0069
    - EV-0070
    - EV-0073
    - EV-0079
    - EV-0081
    - EV-0083
    - EV-0084
  build:
    - EV-0070
  tests:
    - EV-0073
  coverage:
    - EV-0074
  e2e:
    - EV-0075
  docs:
    - EV-0081
    - EV-0067
reviewed_evidence:
  - EV-0001
  - EV-0002
  - EV-0003
  - EV-0004
  - EV-0005
  - EV-0006
  - EV-0007
  - EV-0008
  - EV-0009
  - EV-0010
  - EV-0011
  - EV-0012
  - EV-0013
  - EV-0014
  - EV-0015
  - EV-0016
  - EV-0017
  - EV-0018
  - EV-0022
  - EV-0040
  - EV-0041
  - EV-0042
  - EV-0044
  - EV-0045
  - EV-0046
  - EV-0047
  - EV-0048
  - EV-0049
  - EV-0050
  - EV-0051
  - EV-0052
  - EV-0053
  - EV-0054
  - EV-0055
  - EV-0056
  - EV-0057
  - EV-0058
  - EV-0059
  - EV-0065
  - EV-0066
  - EV-0067
  - EV-0068
  - EV-0069
  - EV-0070
  - EV-0071
  - EV-0072
  - EV-0073
  - EV-0074
  - EV-0075
  - EV-0076
  - EV-0077
  - EV-0078
  - EV-0079
  - EV-0080
  - EV-0081
  - EV-0082
  - EV-0083
  - EV-0084
findings:
  - id: F-001
    severity: P2
    title: "前端覆盖率 7.69%/7.81%，低于合同 80/70；按 M01 骨架口径有条件接受，M02 gate 前必须补齐"
    status: open
    evidence: "EV-0074"
  - id: F-002
    severity: P2
    title: "commands.security 失败：pnpm-lock 1 critical + 16 high，pom 1 high；CI 未运行该命令"
    status: open
    evidence: "EV-0077"
  - id: F-003
    severity: P3
    title: "前端覆盖率报告把生成代码 src/.umi-production 计入分母，与合同 coverage.exclude 不一致"
    status: open
    evidence: "EV-0074"
  - id: F-004
    severity: P2
    title: "开发环境三页均出现 Umi overlay console TypeError；admin/login 同时有 antd 6 Alert message 废弃警告，E2E 未采集 console"
    status: open
    evidence: "EV-0075；.riccon/evidence/TASK-0007/console-check.json"
  - id: F-005
    severity: P2
    title: "ProLayout 3.1.15-9 beta 的折叠按钮因 axe serious 被关闭，需 M02 升级或自绘侧边栏"
    status: open
    evidence: "EV-0078"
  - id: F-006
    severity: P2
    title: "TASK-0001 的 11 条工具安装 EV 无 commit attestation（git init 前录制），strict binding 只告警"
    status: open
    evidence: "EV-0001..EV-0011"
  - id: F-007
    severity: P2
    title: "MySQL Connector/J GPLv2+FOSS exception 仍按 DEC-0003=C 暂用，B-01 发布阻断项未关闭"
    status: open
    evidence: "DEC-0003；ADR-0005；EV-0081"
  - id: F-008
    severity: P3
    title: "docs/RUNBOOK.md 仍有 M01 前口径：称 apps/web 未建立、commands.run 指向不存在的 dev compose、架构规则数量写成 6"
    status: open
    evidence: "docs/RUNBOOK.md:14,72,85"
  - id: F-009
    severity: P3
    title: "E2E 仅覆盖 /chat 与 /admin/overview 的 Chromium 桌面路径；登录页 axe、375px、键盘路径留待 M02-AC-16"
    status: open
    evidence: "EV-0075；EV-0078"
created_at: 2026-11-17T10:01:18Z
---

# M01 独立评审：工程基线与 agentscope 冒烟

- **Verdict**：`pass`（仅对 M01 基线；P2 已知风险必须进入 M02 gate）。
- **范围**：M01 三条模块验收、合同命令、agentscope 2.0.5 五项冒烟、T4 威胁模型初稿、证据审计。
- **结论**：M01 作为“准备阶段”可交付；构建、测试、E2E、a11y、沙箱全部真实执行并通过。**安全扫描（Trivy）未通过**、前端覆盖率显著低于合同门槛，均按 P2 条件接受并列为 M02 gate 前置。没有 P0/P1。

## 1. 验收标准

| 模块验收                                           | 结果     | 证据                                                                                                                  |
| -------------------------------------------------- | -------- | --------------------------------------------------------------------------------------------------------------------- |
| 合同 install/build/test/sandbox 命令可从根目录运行 | **pass** | EV-0069（install）、EV-0070（build）、EV-0073（test）、EV-0079（sandbox）；另 lint/typecheck/coverage/e2e/a11y 均通过 |
| agentscope 2.0.5 五项冒烟有通过证据                | **pass** | EV-0084（5 个测试类、7 个测试方法、0 skipped）；EV-0073 全量回归同样通过                                              |
| M02 前的 T4 威胁模型初稿完成                       | **pass** | EV-0081：TB-01..TB-09、ABUSE-01..03、B-01/B-06、OIDC=M03、ADR-0001..0010 均存在且可检索                               |

模块 DoD：`acceptance/build/tests/coverage/e2e/review/docs/evidence` 全部为 true；覆盖率的条件接受见 F-001。

## 2. 合同命令执行

所有命令均在仓库根目录、HEAD=`1fe71c9`、非 `.riccon` 工作区干净时执行；命令字符串与合同 `commands.*` 完全一致。

| 合同项    | 命令                                                                                                                     | EV      | 退出码 | 观察结果                                                                                                      |
| --------- | ------------------------------------------------------------------------------------------------------------------------ | ------- | ------ | ------------------------------------------------------------------------------------------------------------- |
| install   | `./mvnw -q -T1C -DskipTests dependency:go-offline && pnpm -w install --frozen-lockfile`                                  | EV-0069 | 0      | Maven 依赖就绪；pnpm lockfile 未变，忽略 core-js/es5-ext 构建脚本（预期）                                     |
| build     | `./mvnw -q -T1C -DskipTests package && pnpm -w build`                                                                    | EV-0070 | 0      | API/Worker/Platform jar 与 web `dist` 生成                                                                    |
| lint      | `./mvnw -q spotless:check && pnpm -w lint`                                                                               | EV-0071 | 0      | Spotless、ESLint `--max-warnings=0`、Prettier 全部通过                                                        |
| typecheck | `pnpm -w typecheck`                                                                                                      | EV-0072 | 0      | Umi setup + `tsc --noEmit` 通过                                                                               |
| test      | `./mvnw -q -T1C verify && pnpm -w test`                                                                                  | EV-0073 | 0      | 后端 28 个测试（api 2、worker 9、arch 7、common 10），前端 5 个；0 skipped                                    |
| coverage  | `./mvnw -q -T1C verify jacoco:report && pnpm -w test -- --coverage`                                                      | EV-0074 | 0      | 后端 JaCoCo 80/70 通过；前端 line 7.69% / branch 7.81%，见 F-001                                              |
| test_e2e  | `docker compose -f deploy/compose/docker-compose.test.yml up -d --wait && pnpm -w e2e`                                   | EV-0075 | 0      | Compose 三个服务 healthy；Playwright Chromium 3 passed                                                        |
| security  | `trivy fs --scanners vuln,secret,misconfig --skip-dirs node_modules --skip-dirs target --skip-dirs .git --exit-code 1 .` | EV-0077 | **1**  | pnpm-lock 47（1 CRITICAL/16 HIGH/25 MEDIUM/5 LOW）；pom 3（1 HIGH/2 MEDIUM）；0 secret、0 misconfig；见 F-002 |
| a11y      | `pnpm -w a11y`                                                                                                           | EV-0078 | 0      | `/chat`、`/admin/overview` 两页 axe 无 serious/critical，2 passed                                             |
| sandbox   | `docker compose -f deploy/compose/docker-compose.test.yml up -d --wait`                                                  | EV-0079 | 0      | MySQL 8.4.13、OpenSearch 3.9.0、RustFS 1.0.6 均 healthy，全部绑定 `127.0.0.1`                                 |

E2E 后与 sandbox 后分别执行了 `docker compose ... down -v`，EV-0076/EV-0080 证明测试栈和卷被清理。

## 3. 证据审计

- 活动证据共 **58** 条（TASK-0001..TASK-0007）：全部输出文件存在、SHA-256 与字节数匹配、`manual=false`；除下述已知项外 `dirty=false`，`commit` 均为 HEAD 祖先。
- **EV-0077**（security）与 **EV-0082**（我首次 ArchUnit 命令参数选错，Surefire 无匹配测试）为非零退出；两者都未绑定 DoD。EV-0082 已由 EV-0083 修正并通过，属验证者命令选择问题，不是产品失败。
- **EV-0001..EV-0011**（TASK-0001 工具安装）`commit=null`：录制于 `git init` 之前，strict binding 只产生 `evidence_attestation` 告警。按已知限制处理（F-006），未用于任何 DoD 绑定。
- 已归档的 superseded 证据（EV-0019..0021、EV-0023..0039、EV-0043、EV-0060..0064）**不在任何任务引用中**；任务引用全部指向活动 EV。
- TASK-0003/0004/0005/0006 的 baseline/end commit 均为 HEAD 祖先；没有跨工作区或截断证据。

## 4. 质量核对

### 4.1 模块边界与 ArchUnit

- `platform/platform-architecture-tests/.../ModuleBoundaryTest.java` 独立执行 **7/7 通过、0 skipped**（EV-0083）。
- `agentscopeIsOnlyAllowedInWorker` 明确禁止 `com.riccon..` 中 worker 之外的类依赖 `io.agentscope..`；`projectClassesAreLoaded` 断言行集非空，规则不是空集假绿。
- `apps/api` 与 `platform/platform-common` 的 `maven-enforcer-plugin bannedDependencies` 也禁止 `io.agentscope:*`，形成双保险。
- `platform-common` 另有禁止 Spring Web/`jakarta.servlet` 的 ArchUnit 规则，符合“无框架共享内核”。
- 安全过滤器链：API 与 Worker 均只放行 `/actuator/health`，其余 `denyAll()`；`ApiApplicationTests`/`WorkerApplicationTests` 用 MockMvc 断言健康检查 200、未知路径 4xx。

### 4.2 前端、E2E 与可访问性

- `/chat` 与 `/admin` 均真实渲染：E2E 验证标题、会话列表、输入框、ProLayout 菜单、`/admin` → `/admin/overview` 重定向；导航用例验证根路径重定向与菜单切换。
- axe 使用 `wcag2a/wcag2aa/wcag21a/wcag21aa` 标签，拦截 serious/critical；两页均 0 违规。
- 我另做了独立浏览器检查（Chromium 1280×800，`console-check.json` + 截图）：三页 HTTP 200、无 pageerror、无失败请求、无 4xx/5xx。但三页均有一条 console `TypeError`（Umi 4.7.34 dev error overlay 处理 antd 警告时触发 `strip-ansi` 互操作错误）；`/admin`、`/login` 同时出现 `[antd: Alert] message is deprecated` 警告（代码仍用 antd 6 已废弃的 `message` 属性），见 F-004。
- `/login` 未纳入 axe 测试；375px 与纯键盘路径未测，M02-AC-16 再补（F-009）。

### 4.3 agentscope 2.0.5 五项冒烟

EV-0084 独立重跑五个测试类，均非 skip、非 mock 假绿：

1. **状态后端**：`InMemoryAgentStateStore` 保存/加载/删除 + `AgentState` JSON 往返，断言上下文、迭代计数与 ask 权限规则。
2. **沙箱**：真实 Docker 容器执行命令，断言 stdout/退出码 7，`close()` 后 `docker inspect` 失败证明容器已删除；`network("none")`。
3. **MCP**：真实 `McpClientBuilder` 启动本地 stdio 子进程，按 MCP 2024-11-05 JSON-RPC 完成 initialize、tools/list、tools/call，断言 `echo: hello-mcp`；不触网。
4. **HITL**：`PermissionEngine` 三态 + `ReActAgent.streamEvents` 在 ask 规则下挂起，断言工具未执行；回填 `ConfirmResult` 后工具只执行一次、模型调用两次、正常结束。
5. **流式**：假模型两段文本，断言精确事件序列（start → delta ×2 → end）与 delta 拼接结果。

### 4.4 威胁模型与 ADR

- 9 个信任边界 TB-01..TB-09 齐全；3 个滥用场景 ABUSE-01（跨租户读取）、ABUSE-02（提示注入提权）、ABUSE-03（MCP 工具外泄）齐全。
- 发布阻断项包含 B-01（MySQL 许可）与 B-06（Trivy critical/high）；明确 M02 gate 需关闭 B-02..B-06、B-09、B-10、B-12、B-14。
- OIDC 时点已统一为 **M03**：PRODUCT、ARCHITECTURE、THREAT-MODEL、ADR-0006 一致；M02 只做内置账号。
- ADR-0001..ADR-0010 均存在；ADR-0006 密钥信封加密、ADR-0007 审计事件、ADR-0008 outbox/可观测、ADR-0010 Flyway 单一迁移者均有可执行约束。
- 威胁模型 §3.1 自曝“CI 未运行 commands.security，M02 补齐”，与 F-002 一致。

### 4.5 覆盖率

- 后端 JaCoCo（`verify` 内 `jacoco:check`）：api 行 86.67%（13/15）、worker 行 86.67%（13/15）、platform-common 行 100%（38/38）、branch 91.67%；仅排除启动类，符合 CODING-STANDARDS。
- 前端 `pnpm -w test -- --coverage`：statements 7.64%、branches 7.81%、functions 3.22%、lines 7.69%（12/156）。排除 `.umi-production` 生成代码后为行 26.67%、branch 50%；唯一有断言的业务逻辑 `config/routes.ts` 为行 92.3%、branch 83.3%。
- 按合同 `coverage.note` 的 M01 骨架口径，逐条排除项为：生成代码 `src/.umi/**`、`src/.umi-production/**`；纯展示组件 `src/app.tsx`、`src/layouts/**`、`src/pages/**`（E2E 已覆盖渲染与 axe）。排除后唯一可测逻辑 routes.ts 达标，故 DoD coverage 可为 true；但配置尚未真正排除生成代码/展示组件，M02 必须补齐组件测试并落配置（F-001/F-003）。

### 4.6 安全扫描

`commands.security` 未通过。`pnpm-lock.yaml` 47 项（1 CRITICAL：immer 8.0.4 via `@umijs/max → @umijs/plugins → dva-immer`；16 HIGH 含 mcp-core 0.17.2、axios 0.27.2、vite 7.3.6、node-fetch 1.7.3 等）；`pom.xml` 3 项（mcp-core CVE-2026-35568 HIGH、CVE-2026-34237 MEDIUM、opentelemetry-api MEDIUM）。无 secret、无 misconfig。

判为 **P2 条件接受** 的理由：M01 没有可部署业务面，没有真实模型/MCP/用户数据；这些均为框架/工具链传递依赖；威胁模型 §8 明确 B-06 在 **M02 gate** 关闭。M02 若引入 HTTP MCP、真实模型或对外服务而 B-06 未关闭，应升级为 P1。

## 5. Findings

| ID    | 级别 | 状态 | 发现                                                                                                                                                                      | 位置 / 复现                                                                                                            | 证据                                           |
| ----- | ---- | ---- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------- |
| F-001 | P2   | open | 前端覆盖率 7.69%/7.81%，低于 80/70；M01 骨架期有条件接受，M02 gate 前必须补齐。                                                                                           | `apps/web/vitest.config.ts:15-20`；`pnpm -w test -- --coverage`                                                        | EV-0074                                        |
| F-002 | P2   | open | Trivy 退出 1：pnpm-lock 1 CRITICAL/16 HIGH，pom 1 HIGH；CI 未运行 commands.security。                                                                                     | `pnpm-lock.yaml`、`pom.xml`、`.github/workflows/ci.yml`；见 §4.6                                                       | EV-0077                                        |
| F-003 | P3   | open | 覆盖率把生成的 `src/.umi-production/**` 计入分母，与合同“生成代码”排除项不一致。                                                                                          | `apps/web/vitest.config.ts:19-20`；报告含 `.umi-production` 111/156 行                                                 | EV-0074                                        |
| F-004 | P2   | open | 开发环境三页 console 均出现 Umi overlay `TypeError`；`/admin`、`/login` 同时有 antd 6 `Alert` `message` 废弃警告；E2E 未断言 console。                                    | `apps/web/src/pages/admin/overview/index.tsx:16-22`、`apps/web/src/pages/login/index.tsx:22-28`；`e2e/tests/*.spec.ts` | EV-0075；`console-check.json` + `screenshots/` |
| F-005 | P2   | open | ProLayout 3.1.15-9 beta 折叠按钮因 axe serious 被关闭（`collapsedButtonRender={false}`），beta 升级/自绘侧边栏待定。                                                      | `apps/web/package.json`、`apps/web/src/layouts/admin/index.tsx:26-29`                                                  | EV-0078                                        |
| F-006 | P2   | open | TASK-0001 的 11 条工具安装 EV 无 commit attestation（git init 前录制），strict binding 只告警。                                                                           | `.riccon/evidence/TASK-0001/EV-0001..0011.json` 的 `commit=null`                                                       | EV-0001..EV-0011                               |
| F-007 | P2   | open | MySQL Connector/J GPLv2+FOSS exception 按 DEC-0003=C 暂用；B-01 发布阻断项未关闭。                                                                                        | `docs/adr/ADR-0005...`、`.riccon/decisions/DEC-0003.md`                                                                | DEC-0003；EV-0081                              |
| F-008 | P3   | open | RUNBOOK 仍是 M01 前口径：line 14 称 apps/web 未建立；line 72 称 commands.run 指向 docker-compose.dev.yml（实际 test.yml）；line 85 称 6 条架构规则（实际 7 个测试方法）。 | `docs/RUNBOOK.md`                                                                                                      | EV-0081                                        |
| F-009 | P3   | open | E2E 仅覆盖 `/chat`、`/admin/overview` 的 Chromium 桌面路径；`/login` axe、375px、键盘路径未测，M02-AC-16 再补。                                                           | `e2e/tests/*.spec.ts`                                                                                                  | EV-0075；EV-0078                               |

P0/P1：**无**。P2 均为 M02 gate 前置；P3 为文档/测试范围问题。

## 6. 残余风险与已知限制

1. **安全扫描未通过**（F-002）：M01 不对外服务，故不阻断 M01；B-06 是 M02 gate 与发布阻断项。若 M02 提前引入 HTTP MCP、真实模型或外部端点，先关闭再继续。
2. **前端覆盖率**（F-001）：E2E 证明“能渲染/可访问”，不证明组件逻辑；M02 的 J1/J2 页面必须补 jsdom 组件测试并把排除项写进配置，否则 M02 gate 不通过。
3. **Pro 6 beta**（F-005）：折叠按钮被关闭是 a11y workaround，不是修复；M02 应升级或自绘侧边栏，并保留 axe 无 serious/critical。
4. **TASK-0001 无 commit**（F-006）：11 条安装证据不可重放为“已提交源码”的证明；不影响 M01 结论，但不能用于 release 证据。
5. **MySQL 许可**（F-007）：DEC-0003=C 是用户明确的风险接受；B-01 必须在对外分发前重新决策（商业许可或替换驱动）。
6. **E2E 环境**：Playwright 跑的是 Umi dev server，不是 `pnpm -w build` 产物；M02 应至少加一次生产构建静态服务的 E2E。
7. **API 默认配置**：`apps/api/src/main/resources/application.yml` 的 DB 测试占位凭据与 `useSSL=false` 仅用于本地/测试，生产必须由环境变量/密钥管理注入；威胁模型已覆盖该边界，但部署前需检查。
8. **未执行**：`commands.perf`（`e2e/perf/mvc-smoke.js` 尚不存在）、`commands.run`、`commands.format`、`commands.deploy_preview`、`commands.deploy_prod`、`riccon release check`。这些不在 M01 module DoD 中；M02/M09 再补。
9. **未验证**：M02-AC-01..16 全部未测（M01 没有业务功能）；Firefox/WebKit 未测；375px 与纯键盘路径未测。

## 7. 对 M02 的前置条件

1. **关闭 B-06**：升级/替换 mcp-core、axios、vite、immer 等 critical/high 依赖，或由用户书面接受剩余风险；把 `commands.security` 加进 CI。未关闭前不得声称 M02 安全基线通过。
2. **前端覆盖率**：为 J1/J2 的页面与交互补组件测试；把 `src/.umi-production/**`（以及 `src/.umi/**`）加入 coverage exclude；按合同 80/70 设阈值并在 CI 执行。
3. **Pro beta**：升级 `@ant-design/pro-components` 或自绘侧边栏，恢复折叠能力并通过 axe；不要保留“关掉控件”作为长期方案。
4. **E2E 纵深**：加 console/pageerror/network 失败断言；覆盖 `/login`、375px、纯键盘与生产构建产物。
5. **证据卫生**：TASK-0001 的工具版本在提交后重录一次，消除 commit=null；后续任务保持“先 commit 再 evidence run”。
6. **文档**：更新 RUNBOOK 到 M01 完成后的真实命令与目录；同步 M02 的 DDL/迁移与运行步骤。
7. **安全边界**：M02 第一张业务表/第一个认证端点落地时，按 ADR-0003/0009 实现租户传播与越权用例；威胁模型 §10 的 TEN/INJ/MCP/AUDIT 测试 ID 必须真实出现在 surefire/E2E 报告中。

## 8. 证据链接

- 安装/构建/质量：`EV-0069` install、`EV-0070` build、`EV-0071` lint、`EV-0072` typecheck、`EV-0073` test、`EV-0074` coverage。
- E2E/可访问性/沙箱：`EV-0075` test_e2e、`EV-0076` compose down、`EV-0078` a11y、`EV-0079` sandbox、`EV-0080` compose down。
- 安全：`EV-0077`（失败，见 F-002）。
- 独立补充核对：`EV-0081` 文档/威胁模型；`EV-0083` ArchUnit；`EV-0084` agentscope 五项冒烟；`EV-0082` 为失败的命令选择尝试（已由 EV-0083 取代）。
- 浏览器产物：`.riccon/evidence/TASK-0007/console-check.json`、`.riccon/evidence/TASK-0007/screenshots/{chat,admin,login}.png`。
- 原始日志目录：`.riccon/evidence/TASK-*/EV-*.log`；证据审计以 `riccon evidence list --json` 的 SHA-256/字节数/commit 为准。
