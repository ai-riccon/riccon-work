# ADR-0011：B-06 依赖安全处置与例外

Status: accepted
Scope: M02 gate；例外仅限 worker 以 MCP client 方式使用 agentscope 的场景
Date: 2026-11-17
Accepted: 2026-11-17 by user via DEC-0005 (accept); B-06 closed for M02, exception expiry 2026-12-31 or M03 gate, whichever is earlier.
关联: TASK-0008、M01 review F-002、`docs/security/THREAT-MODEL.md` B-06/T-01-2/第 9 节、ADR-0004、ADR-0005

## Context

M01 gate 的 `commands.security`（Trivy）失败（`.riccon/evidence/TASK-0007/EV-0077`）：

- `pnpm-lock.yaml`：47 项（1 CRITICAL、16 HIGH、25 MEDIUM、5 LOW）；
- `apps/worker/pom.xml` 与根 `pom.xml`：各 3 项（1 HIGH、2 MEDIUM）；
- secret 与 misconfig 均为 0。

T4 项目不能以“以后再修”关闭 B-06。本 ADR 记录两件事：哪些依赖被真正修复、哪些只能作为**有补偿控制和时间盒的例外**。例外由 `riccon/architect` 提出，风险接受仍由 Lead/用户按合同 `decision_owners` 确认；本 ADR 状态保持 `proposed` 直到获得书面接受。

### 分类方法

| 类别      | 判定                                                                  | 证据命令                                               |
| --------- | --------------------------------------------------------------------- | ------------------------------------------------------ |
| runtime   | 进入应用产物或后端运行时类路径                                        | `pnpm -C apps/web why <pkg>`、`./mvnw dependency:tree` |
| dev/build | 只参与开发服务器、构建、代码生成、测试，不进入 `apps/web/dist` 与 jar | 同上 + 产物内容检查                                    |

关键事实：

1. 前端 critical/high **全部**来自 `@umijs/max@4.7.34` 的构建链（`@umijs/plugins`、`@umijs/preset-umi`、bundler 等），不是业务运行时依赖。`pnpm -C apps/web why` 的链路见“证据”节。
2. `apps/web` 的源码会 `import { Link, Outlet, useLocation } from '@umijs/max'`，但 Umi 在构建时把用到的运行时代码内联进静态产物；`dist/` 中不存在 axios、immer、katex、decode-uri-component 的代码（见证据）。因此 Umi 对“部署物”而言是构建依赖，对“源码”而言是编译期库。
3. `katex` 是唯一经 runtime 库进入依赖图的受影响包（`@ant-design/x → mermaid → katex`）；已升级修复，且当前页面未使用 Mermaid，产物中也没有 katex 代码。
4. Java 侧 `mcp-core:0.17.2` 由 `io.agentscope:agentscope-core:2.0.5` 传递引入，是 worker 的真实运行时依赖。

## Decision

### 1. 依赖范围重分类（前端）

`@umijs/max` 从 `apps/web/package.json` 的 `dependencies` 移到 `devDependencies`。

理由与约束：

- 它是构建框架与 CLI；`apps/web/dist` 为静态产物，部署时不带 `node_modules`；
- 生产/预览构建必须在完整安装（含 devDependencies）后执行；**禁止**在 `pnpm install --prod` 后构建。合同 `commands.install` 与 `commands.build` 目前就是完整安装，满足该约束；
- 这不是“隐藏风险”：本 ADR 同时记录构建链残余漏洞（第 4 节），并要求发布前用 `--include-dev-deps` 复核；
- 该重分类使 `commands.security` 的 pnpm 目标只反映**会进入产物**的依赖，与威胁模型 T-01-2 的“产物供应链”语义一致。

### 2. 前端可修复项（pnpm overrides）

在根 `package.json` 的 `pnpm.overrides` 中做最小版本覆盖，全部经过 `pnpm install` → `pnpm -w build` → `pnpm -w test` → `pnpm -w e2e` 验证：

| 包               | 原版本  | 覆盖后  | 清除的漏洞（示例）                                                      | 备注                                                        |
| ---------------- | ------- | ------- | ----------------------------------------------------------------------- | ----------------------------------------------------------- |
| `immer`          | 8.0.4   | 9.0.6   | CVE-2021-23436（CRITICAL）、CVE-2021-3757（HIGH）                       | 9.0.6 是官方 fixed 版本；未追 10.x，避免 dva-immer 兼容风险 |
| `axios`          | 0.27.2  | 0.34.0  | 24 项 0.27.2 相关 CVE（SSRF、原型污染、代理凭据泄露等）                 | 0.34.0 已清空 Trivy 列表；不跨到 1.x，降低 Umi 插件断裂面   |
| `vite`           | 7.3.6   | 7.3.7   | CVE-2026-112942/112943/112944/112946/112945（launch-editor 命令注入等） | 同 minor patch                                              |
| `react-router`   | 6.3.0   | 6.30.4  | CVE-2025-68470（HIGH）、CVE-2026-40181（MEDIUM）                        | 同 major；CVE-2026-53666/53669 仅 7.18.0 修复，列入残余     |
| `node-fetch`     | 1.7.3   | 2.6.7   | CVE-2022-0235（HIGH）                                                   | 2.6.7 为 CJS，3.x 为 ESM-only 会破坏 isomorphic-fetch       |
| `path-to-regexp` | 1.7.0   | 1.9.0   | CVE-2024-45296（HIGH，ReDoS）                                           | 同 major patch                                              |
| `path-to-regexp` | 8.2.0   | 8.4.0   | CVE-2026-4926（HIGH）、CVE-2026-4923（MEDIUM）                          | 同 major patch                                              |
| `@babel/core`    | 7.23.6  | 7.29.13 | CVE-2026-49356（LOW）                                                   | 与 lockfile 中已存在的 7.29.13 对齐                         |
| `@babel/runtime` | 7.23.6  | 7.29.14 | CVE-2025-27789（MEDIUM）                                                | 与 lockfile 中已存在的 7.29.14 对齐                         |
| `katex`          | 0.16.47 | 0.18.2  | CVE-2026-103923（LOW，XSS）                                             | runtime 链路（`@ant-design/x → mermaid`）                   |

未覆盖 `decode-uri-component` 到 0.5.1：该版本为 ESM-only，`query-string@6.14.1` 为 CJS，覆盖会导致运行期 `ERR_REQUIRE_ESM`；列入残余。

### 3. Java 侧

| 包                                       | 处理                                         | 结果                                                         |
| ---------------------------------------- | -------------------------------------------- | ------------------------------------------------------------ |
| `io.opentelemetry:*`                     | 导入 `opentelemetry-bom:1.67.0`              | 修复 CVE-2026-118259（MEDIUM），`./mvnw -q -T1C verify` 通过 |
| `io.modelcontextprotocol.sdk:mcp:0.17.2` | **不覆盖**，保留 agentscope 2.0.5 的传递版本 | 见下方例外                                                   |

MCP 升级已实测失败（不可强行升级）：

- 覆盖到 `1.1.5`：`McpStdioSmokeTest` 报 `NoSuchMethodError: McpJsonMapper.getDefault()`；
- 覆盖到 `0.18.5`：同样报错；`javap` 确认 0.18.0 起该方法被移除，`1.0.0/1.0.1/1.0.2` 均无；
- agentscope 升级不在本任务范围（合同 `stack.versions.agentscope-java: 2.0.5`），需 Lead 决策与完整回归。

### 4. 例外清单（仅这些 CVE 允许进 `.trivyignore`）

| CVE            | 严重度 | 包                                            | 链路                                            | 为何不能修                                                       |
| -------------- | ------ | --------------------------------------------- | ----------------------------------------------- | ---------------------------------------------------------------- |
| CVE-2026-35568 | HIGH   | `io.modelcontextprotocol.sdk:mcp-core:0.17.2` | `agentscope-core:2.0.5 → mcp:0.17.2 → mcp-core` | 修复版本 1.0.0；与 agentscope 2.0.5 二进制不兼容（见上）         |
| CVE-2026-34237 | MEDIUM | `io.modelcontextprotocol.sdk:mcp-core:0.17.2` | 同上                                            | 修复版本 1.0.1/1.1.1/0.18.3；0.18.3 也会触发 `getDefault()` 缺失 |

**补偿控制**（缺一不可，M02 gate 由 verifier 核对）：

1. Riccon 只做 MCP **client**：stdio 子进程或租户白名单内的 HTTP 端点；worker 不启动 SDK 内置 HTTP/SSE server，也没有入站 MCP 监听端口（`IT-SEC-002`、`MCP-001`）。
2. MCP 工具来自**静态白名单**（M02 仅 1 个只读工具），工具调用全量 HITL，参数最小化（TM-14/TM-15、`IT-HITL-001`）。
3. Worker 不对外暴露，Agent 沙箱 `network=none`；出网只允许模型端点与授权 MCP 端点（TB-03/TB-05/TB-06）。
4. DNS rebinding 与通配 CORS 的受影响面是 SDK server；上述四点确保该代码路径在 Riccon 不可达。
5. 审计覆盖 `mcp.tool.invoked`，可核对工具、参数/结果 hash、耗时（`MCP-007`）。

**到期与复核**：`2026-12-31` 或 M03 gate，取早者。任一条件发生必须重开本 ADR 并删除 `.trivyignore` 条目：agentscope 升级到使用 MCP SDK ≥ 1.0.1 的版本；M02 之后引入入站 MCP server 或管理员自助注册（M07）；Trivy 报告 fixed 版本变化。

**B-06 关闭条件**：`commands.security` 退出码 0，且 `.trivyignore` 只包含本 ADR 列出的 CVE、每条有 `exp` 与注释；例外获得 Lead/用户书面风险接受后，B-06 才可关闭。技术处置完成不等于风险接受完成。

### 5. 构建链残余（不在 `commands.security` 范围）

重分类并修复后，用 `trivy fs --scanners vuln --include-dev-deps ... .` 仍可看到以下 dev/build 项；它们**不进入** `dist/` 或后端 jar，但属于开发机/CI 供应链风险：

| 包                     | 版本    | 严重度 | CVE                  | 不升级原因                                                 |
| ---------------------- | ------- | ------ | -------------------- | ---------------------------------------------------------- |
| `decode-uri-component` | 0.2.2   | HIGH   | CVE-2026-118664      | 修复 0.5.1 为 ESM-only，CJS 依赖链（query-string 6.x）断裂 |
| `@hono/node-server`    | 1.19.19 | MEDIUM | CVE-2026-118663      | 修复 2.1.3 为 major，@utoo/pack 兼容性未知                 |
| `esbuild`              | 0.18.20 | MEDIUM | GHSA-67mh-4wv8-2f99  | 修复 0.25.0 为 major，tsx@3.12/esbuild-kit 依赖 0.18       |
| `glob`                 | 10.5.0  | MEDIUM | CVE-2026-118773      | 修复 14.0.2 为 major API 变更                              |
| `minimatch`            | 9.0.9   | MEDIUM | CVE-2026-118764      | 修复 11.0.1 为 major API 变更                              |
| `react-router`         | 6.30.4  | MEDIUM | CVE-2026-53666/53669 | 仅 7.18.0 修复；Umi 4 使用 v6，跨 major 断裂               |
| `elliptic`             | 6.6.1   | LOW    | CVE-2025-14505       | 上游无修复版本                                             |

处置约定：

- 这些项**不得**进入 `.trivyignore` 以外的“静默通过”路径；它们本来就被 Trivy 的生产依赖扫描排除，因此不需要 ignore 条目；
- 发布前（B-11/M09）运行一次 `--include-dev-deps` 扫描并人工评估；M03 gate 前逐项决定升级或记录新的例外；
- 新增任何 `dependencies` 项时，必须同时确认它是否会进入 `dist/`；进入产物的依赖必须出现在生产扫描结果中。

## Consequences

- 正面：B-06 的 Trivy 目标从 50 项降为 0 项可见项；前端 critical/high 全部移除或确认为构建链；Java 仅剩 2 项不可修复且不可达的服务器端 CVE，有明确退出条件。
- 负面：`@umijs/max` 成为 devDependency 后，任何“先 `pnpm install --prod` 再构建”的流水线会失败，必须在运行手册中写清楚；构建链漏洞不再被默认 gate 覆盖，需要发布前的补充扫描。
- 约束：例外到期会自动恢复报错；升级 agentscope 时必须重新跑 MCP 冒烟并删除 ignore 条目；不得用包名通配扩大 ignore 范围。

## Alternatives

- **保持 `@umijs/max` 在 dependencies，用 `.trivyignore` 覆盖 40+ 个前端 CVE**：拒绝。allowlist 膨胀会掩盖未来真正进入运行时的漏洞，且 axios 一项就有 20 个 CVE。
- **强行升级 glob/minimatch/@hono/esbuild/decode-uri**：拒绝。major/ESM 变更会破坏 Umi 4 构建链，违反“可回滚、可验证”的约束。
- **升级 agentscope 以换取 MCP SDK 1.x**：本任务不做。需要合同版本变更、API 回归和五项冒烟，属于 Lead 决策；本 ADR 把它列为 B-06 的关闭路径。
- **移除 MCP 能力**：拒绝。J2 旅程要求 1 个 MCP 工具，属于 MVC 范围。

## Evidence

- 扫描前：`.riccon/evidence/TASK-0007/EV-0077`（exit 1；pnpm 47、pom 3）。
- 分类：
  - `pnpm -C apps/web why immer|axios|vite|react-router|katex|decode-uri-component|node-fetch|glob|minimatch|@hono/node-server|esbuild|elliptic`
  - `./mvnw -pl apps/worker dependency:tree -Dincludes=io.modelcontextprotocol.sdk,io.opentelemetry`
- 修复验证：`pnpm -w build`（EV-0086）、`pnpm -w test`（EV-0087）、`pnpm -w e2e`（EV-0088）、`./mvnw -q -T1C verify`（EV-0089）均通过。
- MCP 不兼容实测：`1.1.5` 与 `0.18.5` 覆盖均报 `NoSuchMethodError: McpJsonMapper.getDefault()`；`javap` 核验 1.0.0/1.0.1/1.0.2 无该方法。
- 产物检查：`grep -rl axios|immer|katex|decode-uri apps/web/dist` 无匹配（仅 XProvider 上下文键 `mermaid` 字符串，非 mermaid/katex 代码）。
- 扫描后：`trivy fs --scanners vuln,secret,misconfig --skip-dirs node_modules --skip-dirs target --skip-dirs .git --exit-code 1 .` → exit 0（EV-0090）；仅 2 条 CVE 被 `.trivyignore` 抑制（`--show-suppressed` 可复核）。
- 构建链残余：`trivy fs --scanners vuln --include-dev-deps ...` → 1 HIGH / 7 MEDIUM / 1 LOW（均在上表）。
