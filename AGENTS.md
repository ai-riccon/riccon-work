# riccon-work — Agent 说明

本文件是 Riccon 智能体团队在该项目中的协作合同，也是技术栈、命令、工具和约束的唯一事实源。保持受管区块有效；区块外的说明可以自由编辑。

## Project Contract

<!-- riccon:contract:start -->

```yaml
contract_version: 1
project:
  name: riccon-work
  type: web-app
  risk_tier: T4
  status: greenfield
  timezone: Asia/Shanghai
product:
  goal: "面向企业私有化部署的多租户 AI 协同办公系统：员工在云工作空间中与 Agent 协作完成办公任务，平台统一管理模型、知识库、MCP 与权限。"
  users:
    - "企业员工（普通使用者）"
    - "部门/团队负责人（共享空间与 Agent 管理者）"
    - "平台管理员（租户、模型、知识库、MCP、审计）"
    - "企业 IT 运维（私有化部署与运维）"
  mvc: "J1 通用问答与文件处理：员工上传 PDF/Word/Excel/图片，在云工作空间获得流式回答与知识库引用。J2 市场调研报告：输入主题 → 知识库检索带引用 + 1 个 MCP 工具取数 → 人工审批 → 生成报告并保存到工作空间。"
  non_goals:
    - "在线文档协同编辑"
    - "IM、日历、会议"
    - "OA 审批流"
    - "完整云桌面（RDP/VDI）"
    - "移动端 App（v1 Web 优先）"
    - "信创适配（v1 不承诺）"
    - "agentscope Team/Workflow preview 的可视化编排（M06 自研 DAG Studio 属于 v1）"
  success_metrics:
    - "12 周内 M02（MVC）可演示"
    - "24 周内完成 M02–M06（含 M10/M03b）私有化 v1"
    - "MVC 旅程 J1/J2 E2E 通过，跨租户越权用例零漏检"
    - "假模型基准下首字 p95 不超过 3 秒"
    - "审计覆盖模型、工具、MCP 与权限变更"
  module_roadmap:
    - id: M01
      title: "工程基线与 agentscope 冒烟"
      goal: "monorepo 骨架、CI、合同命令可运行、agentscope 2.0.5 五项冒烟（状态后端、沙箱、MCP、HITL、流式）、威胁模型初稿"
      status: done
    - id: M02
      title: "MVC：云工作空间与办公协同闭环"
      goal: "登录、租户、基础 RBAC、云工作空间、通用问答与文件处理、市场调研报告闭环（知识库引用、1 个 MCP 工具、HITL、产物）、网关最小闭环、审计、E2E"
      status: done
    - id: M03
      title: "权限纵深与组织模型"
      goal: "组织/部门、部门数据范围、部门维度越权测试套件"
      status: building
    - id: M10
      title: "身份与审计纵深（M03b）"
      goal: "OIDC/SSO、审计查询与导出审批、身份与导出越权用例"
      status: planned
    - id: M04
      title: "模型网关纵深"
      goal: "路由、降级、多 key、配额执行、成本看板"
      status: planned
    - id: M05
      title: "知识库纵深"
      goal: "Excel/图片/OCR、文档级 ACL、1 个外部连接器"
      status: planned
    - id: M06
      title: "编排 Studio"
      goal: "自研 DAG 可视化、模板、运行历史、审批节点"
      status: planned
    - id: M07
      title: "MCP 网关纵深"
      goal: "注册审批、凭据托管、代理、工具搜索、审计"
      status: planned
    - id: M08
      title: "本地 Connector"
      goal: "桌面端文件与浏览器桥接（Phase 2）"
      status: planned
    - id: M09
      title: "交付与运维"
      goal: "Helm、备份、升级、回滚演练，贯穿各模块"
      status: planned
stack:
  languages:
    - "Java 21"
    - "TypeScript 5"
  frameworks:
    - "Spring Boot 4.1"
    - "Spring Security 7"
    - "Spring AI 2.0"
    - "agentscope-java 2.0.5"
    - "React 19"
    - "Ant Design 6"
    - "Ant Design Pro 6"
    - "Ant Design X 2"
    - "Umi Max 4"
  runtime: "JDK 21 + Node 26 + Docker"
  database: "MySQL 8.4.13"
  package_manager: "Maven 3.10 + pnpm 10"
  versions:
    java: "21.0.13"
    spring-boot: "4.1.2"
    spring-ai: "2.0.2"
    agentscope-java: "2.0.5"
    mysql: "8.4.13"
    node: "26.13.0"
    pnpm: "10"
    antd: "6.7.1"
    antd-pro: "6.0.3"
    pro-components: "3.1.15-9"
    antd-x: "2.10.0"
    opensearch: "3.x"
    spring-security: "7.1.2"
  subprojects:
    - path: apps/api
      kind: "同步 HTTP 服务"
      languages:
        - "Java 21"
      frameworks:
        - "Spring Boot 4.1"
      package_manager: "Maven"
    - path: apps/worker
      kind: "异步 Agent 运行与 MCP 代理"
      languages:
        - "Java 21"
      frameworks:
        - "Spring Boot 4.1"
        - "agentscope-java 2.0.5"
      package_manager: "Maven"
    - path: apps/web
      kind: "员工端与管理端 Web"
      languages:
        - "TypeScript 5"
      frameworks:
        - "React 19"
        - "Ant Design 6"
        - "Ant Design Pro 6"
        - "Ant Design X 2"
        - "Umi Max 4"
      package_manager: "pnpm"
repo_map:
  root:
    - pom.xml
    - mvnw
    - .mvn
    - pnpm-workspace.yaml
    - package.json
  source:
    - apps
    - platform
  tests:
    - apps
    - e2e
  docs: docs
  infra: deploy
  contracts: contracts
commands:
  install: "./mvnw -q -T1C -DskipTests dependency:go-offline && pnpm install --frozen-lockfile"
  build: "./mvnw -q -T1C -DskipTests package && pnpm -w build"
  format: "./mvnw -q spotless:apply && pnpm -w format"
  lint: "./mvnw -q spotless:check && pnpm -w lint"
  typecheck: "pnpm -w typecheck"
  test: "./mvnw -q -T1C verify && pnpm -w test"
  test_e2e: "docker compose -f deploy/compose/docker-compose.test.yml up -d --wait && pnpm -w e2e"
  coverage: "./mvnw -q -T1C verify jacoco:report && pnpm -w test -- --coverage"
  run: "docker compose -f deploy/compose/docker-compose.test.yml up -d --wait && pnpm -w dev"
  security: "trivy fs --scanners vuln,secret,misconfig --skip-dirs node_modules --skip-dirs target --skip-dirs .git --exit-code 1 ."
  perf: "k6 run e2e/perf/mvc-smoke.js"
  a11y: "pnpm -w a11y"
  deploy_preview: "docker compose -f deploy/compose/docker-compose.preview.yml up -d --build"
  deploy_prod: "helm upgrade --install riccon deploy/helm/riccon -n riccon --create-namespace"
  sandbox: "docker compose -f deploy/compose/docker-compose.test.yml up -d --wait"
sandbox:
  required: true
  runtime: "Docker Compose（MySQL 8.4、OpenSearch、RustFS）加 agentscope 容器沙箱"
  network: "构建/测试容器默认无外网；Compose 测试栈仅私有 Docker 网络；Agent 沙箱默认禁外网，仅放行模型与授权 MCP 端点"
  mounts:
    - "临时 workspace 卷（每会话隔离）"
  secrets: "本地 .env 与 CI secrets；密钥加密入库，禁止写入镜像、仓库和日志"
  limits:
    cpu: 2
    memory: 4Gi
    pids: 512
    timeout_minutes: 30
  evidence: "gate 前运行 commands.sandbox 并产生 passing EV"
tools:
  required:
    - id: riccon
      purpose: "状态、任务、证据与门禁内核（node ~/.config/opencode/riccon/scripts/riccon.mjs）"
      fallback: "无替代，缺失即 blocker"
    - id: git
      purpose: "版本与 diff 证据"
      fallback: "无替代"
    - id: jdk21
      purpose: "后端编译与运行时"
      fallback: "无替代"
    - id: maven
      purpose: "后端构建与测试"
      fallback: "使用仓库内 mvnw（M01 生成）"
    - id: node26
      purpose: "前端构建与 E2E"
      fallback: "需降级到 Node 24 LTS 时走决策包"
    - id: pnpm10
      purpose: "前端包管理与 workspace"
      fallback: "缺失即前端任务 blocker，安装后锁定 10.x"
    - id: docker
      purpose: "依赖服务、测试栈与 Agent 沙箱"
      fallback: "无替代，缺失即 blocker"
    - id: codegraph
      purpose: "架构检索与影响面分析"
      fallback: "改用 grep/read，并在任务证据记录降级"
    - id: trivy
      purpose: "依赖与密钥扫描"
      fallback: "缺失时 security gate 失败，不静默跳过"
    - id: k6
      purpose: "性能预算验证"
      fallback: "缺失时性能 DoD 无法通过"
    - id: playwright-cli
      purpose: "E2E 与 axe 可访问性"
      fallback: "缺失即 E2E blocker"
  optional:
    - id: gh
      purpose: "PR/issue 工作流"
      fallback: "使用本地 git"
    - id: semgrep
      purpose: "Java/TypeScript SAST 补充"
      fallback: "仅 Trivy，M03 再评估"
    - id: gitleaks
      purpose: "提交前密钥扫描"
      fallback: "由 Trivy secret 扫描覆盖"
    - id: browser-skill
      purpose: "人工 UX 抽查与截图"
      fallback: "使用 Playwright MCP"
  mcp:
    - antd
    - context7
    - playwright
    - codegraph
  fallback: "MCP 不可用时改用官方文档或 CLI，并在任务证据记录降级；required 缺失即 blocker；Trivy/k6 缺失时 gate 失败。"
environments:
  local: "macOS + Docker Desktop，JDK 21，Node 26"
  preview: "单机 Docker Compose 私有化预览"
  sandbox: "Compose 测试栈加每会话 Agent 容器沙箱"
  production: "客户 K8s + Helm（M09）"
integrations:
  auth: "内置账号 + OIDC Authorization Code/PKCE（Spring Security 7）"
  payments: "无（私有化许可，v1 不计费）"
  email: "无（v1 不发邮件）"
  storage: "S3 兼容（测试与小规模用 RustFS；客户可用自有 S3/OSS；生产由 ops 在 M09 选型）"
  external_apis:
    - "OpenCode Go OpenAI 兼容端点 https://opencode.ai/zen/go/v1（仅开发与演示）"
    - "客户自建 OpenAI 兼容模型网关（生产）"
    - "租户授权的 MCP servers"
data:
  pii: true
  retention: "审计日志至少 180 天；对话与产物默认 180 天，按租户策略覆盖；删除请求 30 天内完成"
  migrations: "Flyway；仅 apps/api 执行迁移，worker 关闭 Flyway 并校验版本；只向前兼容；破坏性变更需 ADR 与回滚脚本"
constraints:
  style: "Java 21 + Spring Boot 4.1 模块化单体，ArchUnit 强制边界；前端 TypeScript strict + ESLint；注释中文"
  security: "T4；租户行级隔离与越权测试；密钥加密托管与轮换；MCP 工具白名单；提示注入防护；全链路审计；外部模型端点仅处理合成数据，PII 必须走客户内网网关，路由失败即失败"
  performance: "假模型基准首字 p95 不超过 3 秒；API p95 不超过 500ms；50 并发 Agent run；脚本 e2e/perf/mvc-smoke.js，开关 RICCON_FAKE_MODEL=1"
  accessibility: "J1/J2 关键旅程 WCAG 2.1 AA 基线；axe 无 serious 或 critical；入口 commands.a11y"
  coverage:
    line: 80
    branch: 70
    exclude:
      - "生成代码"
      - "deploy/"
      - "e2e/"
      - "apps/web 纯展示组件"
    reports:
      - "apps/*/target/site/jacoco"
      - "apps/web/coverage"
    note: "M01 骨架期以实际可测代码为准；排除项必须在评审中逐条列出"
  licensing: "产品私有；依赖限 Apache-2.0、MIT、BSD、EPL；AGPL 仅限内部使用且不得进入一体机分发；agentscope-java 为 Apache-2.0；MySQL Connector/J 为 GPLv2+FOSS exception，按 DEC-0003 暂用，发布前必须取得商业许可或替换驱动（release blocker）"
  compliance:
    - "等保二级基线设计（不承诺测评通过）"
    - "生产数据不出客户内网"
    - "审计留存不少于 180 天且 append-only，禁止在线删除，导出需审批"
    - "信创适配 v1 不支持"
coding_standard:
  path: docs/CODING-STANDARDS.md
  owner: riccon/architect
  comments: zh-CN
quality:
  evidence_binding: strict
definition_of_done:
  module:
    - acceptance
    - build
    - tests
    - coverage
    - e2e
    - review
    - docs
    - evidence
  release:
    - build
    - tests
    - e2e
    - security
    - rollback
    - approval
decision_owners:
  product: user
  ux: user
  architecture: user
  production: user
  spending: user
  destructive: user
budget:
  tokens: none
  wall_clock: "8h/module, 3d/mvc；超预估 2 倍暂停询问"
  cost: not-tracked
```

<!-- riccon:contract:end -->

## 工具与技能路由

工具意图写在合同里；这里只写「谁在什么阶段用哪个」，避免 agent 随机挑选。

### MCP

| MCP          | 使用者                       | 用途                                                          | 约束                                |
| ------------ | ---------------------------- | ------------------------------------------------------------- | ----------------------------------- |
| `antd`       | builder（前端）、verifier    | antd 6 / Pro 6 / X 的组件 API、demo、changelog                | 组件 API 以 MCP 为准，不凭记忆写    |
| `context7`   | 全员                         | Spring Boot 4.1、Spring AI 2.0、agentscope-java、Umi/Pro 文档 | 先查当前版本文档，再看代码          |
| `playwright` | verifier                     | E2E、截图、可访问性快照                                       | 只在 sandbox/preview 环境操作       |
| `codegraph`  | architect、builder、verifier | 符号、调用链、影响面                                          | 需在仓库根执行一次 `codegraph init` |

### Skills

| Skill                            | 使用者          | 阶段     | 说明                             |
| -------------------------------- | --------------- | -------- | -------------------------------- |
| `riccon-core`                    | 全员            | 全程     | 角色、状态、门禁、回执           |
| `riccon-contract`                | lead、architect | 合同变更 | 改合同前必须加载                 |
| `riccon-verify`                  | verifier        | 验证     | DoD、假绿识别、E2E、安全         |
| `riccon-docs`                    | 全员            | 文档     | 最小文档规则                     |
| `x-components`                   | builder（前端） | M02 起   | Bubble、Sender、Conversations 等 |
| `x-chat-provider` / `use-x-chat` | builder（前端） | M02 起   | 流式消息与自定义 Provider        |
| `x-markdown` / `x-request`       | builder（前端） | M02 起   | 富文本流式渲染与请求封装         |
| `x-card`                         | builder（前端） | M06 起   | Agent 动态富交互 UI，按需加载    |
| `browser`                        | verifier、lead  | 审阅     | 人工 UX 抽查，不作为门禁证据     |

### 明确不用

- `pro-upgrade`：绿地项目直接使用最新版，不需要升级路径。
- `report`：只在反馈 OpenCode 自身问题时使用。
- `grill-me` / `grilling`：仅用于项目前期设计访谈，不进入交付循环。
- `opencode`：仅当需要修改团队配置或排查 OpenCode 自身行为时加载。

## 协作约定

- 开始工作前读 `.riccon/state.json`、最新 handoff 和本文件中的合同。
- 不假设合同里没有的目录、命令或工具。
- 每个任务都有写入范围和验收标准；不要静默扩大任何一项。
- 证据必须在提交之后录制：先 commit，再 `riccon evidence run`；dirty 工作区录制的证据 strict gate 会失败。
- task 的 `write_scope` 按文件路径 glob 匹配；目录必须写 `dir/**`，不要写 `dir`。
- 证据是命令、退出码和输出路径，不是声明。strict 绑定下，review 的 `dod_evidence` 必须把 acceptance/build/tests/e2e/coverage/docs 指向真实、通过、已评审的证据；命令必须与合同 `commands` 对应项匹配，空命令和 `not-implemented`/占位命令直接失败；未加引号管道默认失败，`||` 只允许 `exit 非零`，`; true`、`exit 0`、后台 `&` 等掩盖失败也失败。
- 不要把密钥写进仓库、状态、日志或提示词。
- 不要编辑 `.riccon/` 中其他角色负责的决策或报告。
- 代码变更同步更新文档，或记录 no-impact 理由。
- 产品范围、不可逆技术选择、生产、花钱和破坏性操作由用户拥有；其余授权给团队。
- 文档、注释、决策包、评审包和 handoff 默认中文；代码注释必须中文；代码标识符、命令和字段名保持英文。
- 代码注释至少方法级；复杂业务流程必须有行级或段落注释；不写重复、无信息量的注释。
- Architect 在 Builder 开始前创建 `docs/CODING-STANDARDS.md`，按技术栈和行业最佳实践定义编码规范；Builder 必须遵循，Verifier 检查。
- “覆盖率”默认指代码行/分支覆盖率，不是 AC 覆盖；门槛见合同 `constraints.coverage`，报告必须可由 `commands.coverage` 复现。
- 现有历史代码的注释迁移单独成任务，不和行为变更混合。
- 任务过大（`write_scope` 超过 8 项或 acceptance 超过 6 项）时先拆分；不要用一个大任务让 Builder 长时间只读。
- 模块结束用 `riccon module cost` 记录墙钟；本项目不计量 token 与美元成本，`confidence` 用 `estimated`，并在 source/note 注明 wall-clock-only；全部指标为 0 时只能用 `unmeasured`。
- Product 的 brief/AC/non-goals 先经 Lead 完整性/合同一致性评审，再由用户批准范围；随后 Architect 评可行性、Verifier 评可验证性；结论写入 `docs/PRODUCT.md` 评审状态表。
- Architect 的 ARCHITECTURE/ADR 需经 Lead、用户（不可逆/高成本项）、Verifier、Ops 评审；未批准的 ADR 保持 `proposed`；结论写入 `docs/ARCHITECTURE.md` 评审状态表。
- Builder 只把任务移到 `review`，不自行 `done`；高风险任务（认证、权限、迁移、并发、安全、AI 工具调用）在 done 前必须有独立 Verifier 评审。
- 任务级/架构级独立评审写到 `.riccon/reviews/tasks/<task>-review.md`，由 `task-review.schema.json` 校验；模块评审写到 `.riccon/reviews/<module>-review.md`。任务评审不参与模块 gate，高风险任务 closeout 必须引用。
- 模块 gate 通过后，先由 Product 在 `docs/ACCEPTANCE.md` 做预验收；预验收 `pass` 后才提交用户最终验收（accepted/rejected/conditional）；用户未 accepted 不进入下一个用户可见模块。
- 所有时间默认北京时间（`Asia/Shanghai`，UTC+08:00）；API/日志/事件/UI 使用带偏移的 ISO 8601；数据库存储按合同/ADR（当前 MySQL `DATETIME(6)` UTC，见 ADR-0008/0012）。
- 验证分层：L1 迭代跑定向测试/编译；L2 任务收尾跑受影响模块一次全量并录 EV；L3 模块 gate 由 verifier 跑合同 test/coverage/E2E/a11y/security。`verify` 已含 worker，不得同一任务重复跑 worker 全量；共享层/契约/迁移变更必须全量一次；最终证据在干净提交上录制，探索性失败不录，负向安全证据保留；并行 agent 同一时间只跑一个全量套件。
- 合同变更：加载 `riccon-contract` → 修改受管区块 → `riccon contract sync` → `riccon contract validate --strict`；决策不可覆盖，只追加。
