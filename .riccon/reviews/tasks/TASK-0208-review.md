---
review_id: REV-TASK-0208-001
module_id: M25
task_id: TASK-0208
reviewer:
  agent: riccon/verifier
  session: ses_df4a559edffeWpeOZTs53oWylU
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - f3c9558a009ac04de70fdc538973c3f932c277f7
  - 8e654892ad44bada22bcfc0e1342ae9bb5f1fd5b
  - 70814871cae433dad33c5dfc81fa2109993d686c
head_commit: 8e654892ad44bada22bcfc0e1342ae9bb5f1fd5b
baseline_commit: 96885ef5ff26315942a0c5023cfd388606a1e5eb
dod:
  acceptance: true
  build: true
  tests: true
  security: true
  review: true
  evidence: true
dod_not_applicable:
  - coverage
  - e2e
  - migration
  - perf
  - a11y
  - docs
dod_note: >-
  acceptance 为 true：任务 AC“复用 B10 harness，补连接器 INJ 包装（中英文/canary/内网地址/未授权工具）；
  无生产穿透”已由 12 条连接器样本、真实解析/索引链路、假模型 tripwire、工具权限和引用遮蔽用例覆盖。
  这里只判定 TASK-0208 的任务 AC；M05-AC-14 的完整闭环（页面、日志、审计与错误响应中的连接器凭据/
  Authorization/`externalId` 脱敏，连接器配置页 a11y）不在本任务单独判定，留给 B6/B7b/B8b/B12b/V1b/V2b
  与 M25 模块 gate。build 由 EV-0990 的 Maven reactor 编译（platform/API/worker）和 EV-0991 的
  `spotless:check` 支撑；tests 由 EV-0989/EV-0990 的 49 个定向用例支撑；security 由注入边界、canary/
  内网地址不泄露、未授权工具默认 ASK 与引用遮蔽用例支撑；review 为本文件；evidence 为 EV-0989
  （builder/lead 记录）与 EV-0990/EV-0991（verifier 独立复跑）。coverage/e2e/migration/perf/a11y 由
  M25 模块 gate 与 V1b/V2b 承接；docs 无行为/契约变化，仅测试与测试注释，记 N/A。
reviewed_evidence:
  - EV-0989
  - EV-0990
  - EV-0991
evidence:
  - EV-0989
  - EV-0990
  - EV-0991
findings:
  - id: F-001
    severity: P3
    status: accepted
    owner: riccon/builder-kb-index
    due: M25 gate 前（B7b 后续重构时评估）
    title: "连接器纯文本夹具通过反射绑定包私有 ConnectorPlainTextExtractor.extract"
    summary: >-
      `ConnectorInjectionFixtures.java:219-234` 用 `Class.forName` +
      `getDeclaredMethod("extract", byte[].class)` + `setAccessible(true)` 调用生产
      `ConnectorPlainTextExtractor.extract`，因为该类是包私有且任务写范围只允许
      `apps/*/src/test/java/**/injection/**`。这不是静默失效：类名/方法签名变化会抛带中文说明的
      `IllegalStateException`，测试会红；但测试因此耦合内部类名，无法在编译期发现生产重构。docx/pdf
      已直接使用生产 `DocxDocumentParser`/`PdfDocumentParser`，txt/md 的扩展名路由另由
      `KnowledgeIndexService` 测试（INJ-001）覆盖。若生产未来提供公开入口，应改为直接调用。
    accepted_reason: >-
      反射失败是显式失败而非静默回退；测试不复制生产纯文本抽取实现，避免“测一份测试私有实现”的假绿。
      当前生产类无公开入口，且修改生产可见性超出 TASK-0208 写范围；B7b 的
      `ConnectorKnowledgeIndexIntegrationTest` 已覆盖 txt/md 的真实索引入口，风险有界。后续触碰该类的
      任务可在不改变行为的前提下提供包级/公开入口并替换反射。
    evidence: >-
      apps/worker/src/test/java/com/riccon/worker/injection/ConnectorInjectionFixtures.java:219-234；
      apps/worker/src/main/java/com/riccon/worker/index/service/ConnectorPlainTextExtractor.java:44-74；
      apps/worker/src/test/java/com/riccon/worker/injection/InjectionHarnessTest.java:212-312；
      apps/worker/src/test/java/com/riccon/worker/index/service/ConnectorKnowledgeIndexIntegrationTest.java:100-124。
  - id: F-002
    severity: P3
    status: accepted
    owner: riccon/builder-kb-citation
    due: M25 gate 前（B9b/V1b 复核）
    title: "连接器引用四态测试只覆盖 DTO 遮蔽映射，未覆盖读取时 CitationSourceState 计算"
    summary: >-
      `ConnectorCitationInjectionTest.java:46-129` 直接构造带 `sourceStatus` 的 `CitationRecord`，验证
      `CitationResponse.from` 对 `AVAILABLE`/`UPDATED` 保留快照、对 `NO_ACCESS`/`DELETED` 清空
      title/snippet/定位，并断言遮蔽响应不含注入原文/canary/内网地址。这是真实生产 DTO 映射；但
      `sourceStatus` 的计算在 `CitationSourceState.statusFor`（`CitationSourceState.java:26-37`）和
      仓储/服务读取路径，本测试没有经过该计算。若状态计算回归，本测试仍会绿。现有
      `CitationSourceStateTest` 覆盖优先级与版本比较；B9b/TASK-0207 的连接器引用状态 API 用例覆盖
      connector 文档的读取时状态。
    accepted_reason: >-
      TASK-0208 的验收目标是注入 harness，引用部分是“不可访问时不回显注入原文”的 DTO 级边界；真实
      `CitationResponse.from` 已被覆盖，状态计算已有 `CitationSourceStateTest`，连接器端到端状态属于
      B9b/TASK-0207 与 V1b/V2b。把本测试扩成 MySQL/HTTP 端到端会超出本任务写范围并重复 B9b 的工作；
      当前风险有界，保留为模块级复核项。
    evidence: >-
      apps/api/src/test/java/com/riccon/api/injection/ConnectorCitationInjectionTest.java:46-129；
      apps/api/src/main/java/com/riccon/api/chat/dto/CitationResponse.java:59-79；
      apps/api/src/main/java/com/riccon/api/chat/domain/CitationSourceState.java:26-37；
      apps/api/src/test/java/com/riccon/api/chat/domain/CitationSourceStateTest.java:21-59。
test_impact: >-
  本任务只改测试，不新增依赖、不改生产/contracts/docs/deploy。新增/修改 11 个文件，均在
  `apps/api/src/test/java/**/injection/**` 与 `apps/worker/src/test/java/**/injection/**`：
  `ConnectorCitationInjectionTest`（3）、`ConnectorInjectionFixtures`、`ConnectorInjectionSamples`、
  `InjectionHarnessTest`（25，其中 24 条参数化样本 + 1 条 KnowledgeIndexService 连接器索引链）、
  `InjCatalogTest`（6）、`InjNegativeControlTest`（4）、`InjResearchPromptTest`（5）、
  `InjToolPermissionTest`（6）、`InjectionFixtures`、`InjectionSamples`、`InjectionSurface`。
  verifier 在 commit `7081487`（B9b merge；injection 目录的树与 `8e65489` 相同）独立复跑同一命令：
  `./mvnw -T1C -pl apps/api,apps/worker -am
  -Dtest='InjectionHarnessTest,InjCatalogTest,InjNegativeControlTest,InjResearchPromptTest,InjToolPermissionTest,ConnectorCitationInjectionTest'
  -Dsurefire.failIfNoSpecifiedTests=false test`，API 3 + worker 46 = 49，0 failures/0 errors/0 skipped，
  exit 0（EV-0990）。`-Dsurefire.failIfNoSpecifiedTests=false` 只用于让 reactor 中没有匹配测试的
  platform 模块不报“No tests were executed”；日志明确显示 API 3、worker 46，且 InjectionHarnessTest
  的 24 个参数化用例都执行，因此不是零测试假绿，也不是 flake 重试或掩盖失败。
  `./mvnw -q spotless:check` exit 0（EV-0991）。未跑全量 Maven/E2E/coverage/perf/a11y（模块 gate 与
  V1b/V2b 承接）。
notes: >-
  1) 范围核对：`git diff --name-status 96885ef..f3c9558` 恰好 11 个文件，全部为测试；无生产源码、
  contracts/docs/deploy/.riccon 改动；`git diff --check 96885ef..f3c9558` exit 0；无 pom/依赖变更。
  2) 证据核对：EV-0989（commit `8e65489`，dirty=false，exit=0，output sha256
  `d3cc2bd94c4c032accbaa063e4d703d9013ea1cf2374b01f1aeff6f86ee27ce3`，20106 bytes）与日志
  “3 + 46，Skipped 0，BUILD SUCCESS”一致；该 EV 由 `riccon/lead` 记录，不是 builder，但命令、提交和
  日志完整，已由 verifier 在 EV-0990 独立复跑。EV-0990（commit `7081487`，dirty=false，exit=0，
  sha256 `a438fd2e4e7f78a1b44a168827965a79be748f84910a1ac42bf6e27abf7ec7b7`）与 EV-0991（spotless，
  exit 0）均由 verifier 记录。EV-9001 是 TASK-0172 的归档副本，与本任务无关，未使用。
  3) 样本核对：连接器 `INJ-001..012` 与 B10 编号逐条对应（`InjCatalogTest.connectorCatalog_mirrorsB10Ids`），
  覆盖 txt/md/docx/pdf、白字/小字号、docx 表格公式样式文本、中英文/中英混排、canary 变量、
  RFC 5737 内网地址、`delete_knowledge`、伪造 `SYSTEM`/`assistant` 角色、工具结果级联、伪造不可信边界
  与伪造引用行；`SEC-CONN-002` 别名在 `ConnectorInjectionSamples.ACCEPTANCE_ALIAS` 与
  `InjCatalogTest.connectorCatalog_coversAttackGoals` 双重断言。INJ-008 与 INJ-012 都覆盖伪造边界，
  其中 012 额外覆盖伪造引用；这是连接器目录对 B10 的刻意扩展，不是编号缺口。
  4) 真实链路：txt/md 经生产 `ConnectorPlainTextExtractor.extract`（反射，见 F-001）；docx/pdf 经生产
  `DocxDocumentParser`/`PdfDocumentParser`；INJ-001 经真实 `KnowledgeIndexService` +
  `S3FileContentReader.readConnector` 桩完成索引；聊天/报告分别经真实 `ChatAgentService`/
  `ResearchAgentService` 的系统提示与 `UntrustedData`；工具权限经真实 agentscope `ReActAgent` 权限
  引擎（`PermissionContextState` 仅 ALLOW `knowledge_search`，未授权 `ToolBase` 默认 ASK）。
  5) 引用遮蔽：`ConnectorCitationInjectionTest` 覆盖 DTO 四态映射；状态计算见 F-002。TM-68 的完整
  读取链路由 B9b/TASK-0207 与 V1b/V2b 复核。
  6) 任务卡 `tools_used` 为空，无法从卡片核实 builder 是否使用 Serena/CodeGraph；这与
  REV-TASK-0205-001 notes 第 4 条所述的全局记录习惯一致。本任务仅测试变更、且全量编译/49 用例
  通过、反射失效会显式报错，故不列 finding，建议 Lead 统一回填或写明降级理由。
  7) 评审期间 main 已从 `1eb7669` 前进到 `8d06bd4`（B9b 证据提交），EV-0990 在 `7081487` 上录制；
  `7081487`/`8d06bd4` 对 `apps/*/src/test/java/**/injection/**` 无改动，B10b 被测树与 `8e65489`
  一致。评审时工作区另有 TASK-0207 的未提交/并行状态，不影响本任务的 49 个定向用例结果，但再次
  提醒同一工作树同一时间只跑一个长测试。
  8) gate check：当前 `riccon gate check --module M25` 失败，原因是 M25 仍在 building（TASK-0206/0207
  review、TASK-0209/0211/0212/0213 planned）且无模块 review；其中 TASK-0208 的 write_scope 报错
  （AGENTS.md + B9b 的 3 个测试文件）来自 `scope.mjs` 对未 done 任务按 `baseline..HEAD` 取区间，不是
  B10b 实际 diff 越界。TASK-0208 自己的 `96885ef..f3c9558` 只有 11 个测试文件；标 done 并写
  `end_commit=f3c9558` 后该区间报错应消失。gate 的其它错误由 M25 收口与模块 review 解决，不归本任务。
created_at: "2026-11-24T10:33:09+08:00"
---

# REV-TASK-0208-001：M05b-B10b 连接器内容注入 harness 独立评审

## 结论

**Pass**。TASK-0208 在 B10 `INJ-001..012` harness 上补齐连接器样本目录、真实 txt/md/docx/pdf 解析与索引链、
假模型 tripwire、工具权限和引用遮蔽用例；49 个定向用例在 verifier 独立复跑中 0 failure/0 error/0 skip，
`spotless:check` 通过。任务 AC“复用 B10 harness，补连接器 INJ 包装；无生产穿透”满足。没有 P0/P1/P2；
F-001/F-002 是两项 P3 accepted（包私有类反射耦合、引用状态计算不在本测试内），不影响本任务验收。

## 验证了什么

### AC 对账（任务级）

| AC（任务卡原文）                                                                     | 结论 | 证据/说明                                                                                                                                                                                                                                                                                    |
| ------------------------------------------------------------------------------------ | ---- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 复用 B10 harness，补连接器 INJ 包装（中英文/canary/内网地址/未授权工具）；无生产穿透 | pass | `ConnectorInjectionSamples` 12 条；`InjectionHarnessTest` 24 条参数化样本（12 B10 + 12 连接器）+ 1 条索引链；`InjCatalogTest` 6、`InjToolPermissionTest` 6、`InjNegativeControlTest` 4、`InjResearchPromptTest` 5、`ConnectorCitationInjectionTest` 3；`96885ef..f3c9558` 仅 11 个测试文件。 |

M05-AC-14 的模块级完整闭环（连接器凭据/Authorization/`externalId` 脱敏、页面/审计/错误响应、连接器页 a11y）
不在本任务单独判定；本任务提供其中的“连接器内容注入不改变指令、不泄露 canary/内网地址、不触发未授权工具、
引用保留来源与定位”测试基础。

### 样本与覆盖

- `INJ-001..012` 编号连续、与 B10 一一对应；连接器表面覆盖 `CONNECTOR_TXT/MD/DOCX/DOCX_WHITE_TEXT/
DOCX_TABLE/PDF`，语言覆盖 `zh-CN`/`en`/`zh-en`。
- 攻击目标覆盖：系统提示/canary 泄露（001/002/003/007/008/012）、未授权删除工具（004/009/010）、
  内网地址外发（005/007）、伪造 `SYSTEM`/`assistant` 角色（006/010）、工具结果级联（011）、
  伪造不可信数据边界（008/012）、伪造引用行（012）。
- 内网地址使用 RFC 5737 文档保留段 `198.51.100.7`，canary 值只存在假模型内；样本只引用变量名，
  不在文档/日志/测试数据中写入合成凭据值。
- `SEC-CONN-002` 别名与 `SEC-KB-004` 在 `InjCatalogTest` 中断言；ADR-0023 §8/§11.2、THREAT-MODEL
  TM-13/B-03/TM-55/TM-68 的引用一致。

### 真实链路与断言强度

- txt/md：生产 `ConnectorPlainTextExtractor.extract`（BOM 去除、CRLF 归一、空行分段）；docx/pdf：
  生产 `DocxDocumentParser`/`PdfDocumentParser`（PDF 走文本层，不触发 OCR）。
- `InjectionHarnessTest.connectorIndex_preservesInjectionAsUntrustedChunk` 用真实 `KnowledgeIndexService`
  的 FULL 路径（mock 掉 MySQL/OpenSearch/S3 边界），断言 `readConnector`、真实抽取、切分、段落定位、
  `updateParseMetadata(NONE)`、`markIndexed` 与 `replaceDocument`；索引内容保留注入原文但不含 canary。
- 聊天链使用真实 `ChatAgentService.prepare`：系统提示静态、检索内容经 `UntrustedData.fromCitations`、
  工具面只有只读 `knowledge_search`；报告生成链使用真实 `ResearchAgentService.prepareGenerationStage`，
  引用与已批准工具结果各一个数据块。
- 假模型是 tripwire：只有注入文本进入 SYSTEM/用户指令面（或不完整数据块被当成指令）才输出 canary；
  `InjNegativeControlTest` 明确证明“未隔离会泄露、已隔离不误报”，避免断言空转。
- 回答固定为 `BENIGN_ANSWER`，事件为 `MODEL_STOP`；`InjToolPermissionTest` 用真实 agentscope 权限引擎、
  实际注册 `delete_knowledge` stub，断言 `RequireUserConfirmEvent`、`PERMISSION_ASKING`、
  `stub.executions()==0`，不是只检查工具名清单。
- 日志侧断言捕获的 root 日志非空，且不含 canary、内网地址或注入原文；四类证据（模型请求面、回答、
  工具结果、日志）都有具体断言，无空断言、无 `@Disabled`/assume/retry。

### 引用四态

- `ConnectorCitationInjectionTest` 直接调用生产 `CitationResponse.from`：`AVAILABLE`/`UPDATED` 保留
  title/snippet/paragraphNo；`NO_ACCESS`/`DELETED` 清空 title/snippet 与全部定位字段，且 `toString`
  不含注入原文、`source=KNOWLEDGE`、canary 变量/值或内网地址。
- 状态计算 `CitationSourceState.statusFor` 的优先级和版本比较由既有 `CitationSourceStateTest` 覆盖；
  连接器文档从 MySQL/HTTP 读取时的状态由 B9b/TASK-0207 覆盖（见 F-002、未验证）。

### 范围、质量与证据

- `96885ef..f3c9558`：11 个文件，全部在任务写范围；无生产源码、contracts、docs、deploy、迁移或 `.riccon`
  生产改动；`git diff --check` exit 0；无新增依赖（PDFBox/POI 均为既有生产依赖）。
- 新测试注释与命名使用中文/`被测方法_场景_期望` 风格；复杂夹具与反射边界有说明。
- EV-0989：commit `8e65489`、dirty=false、exit=0、sha256 `d3cc2bd…`，日志 3+46、Skipped 0、BUILD SUCCESS。
- EV-0990：verifier 在 `7081487` 独立复跑同一命令，3+46、0/0/0、exit 0；EV-0991：`spotless:check`
  exit 0。EV-9001 是 TASK-0172 归档副本，未使用。

## 未验证

- 未跑全量 `./mvnw -q -T1C verify`、coverage、E2E、a11y、perf、security 扫描；这些由 M25 模块 gate 与
  V1b/V2b 承接。本评审不替代 Product 预验收或用户验收。
- 未验证连接器凭据/Authorization/`externalId` 在页面、日志、审计、错误响应与导出中的脱敏；属于 B6/B7b/
  B8b/B12b/V2b。
- 未验证真实连接器同步端到端（`ConnectorSyncService` → S3 → 索引 → 检索）；B6/B7b 的集成测试覆盖该链路，
  本任务只验证内容进入模型前后的边界。
- 未验证真实 `CitationSourceState.statusFor` 在本 API 测试中的计算（见 F-002）；连接器状态端到端由 B9b/
  TASK-0207 与 V1b/V2b 复核。

## 剩余风险

1. **F-001（P3 accepted）**：连接器 txt/md 夹具通过反射绑定包私有 `ConnectorPlainTextExtractor.extract`；
   生产重命名/签名变化会显式报错，但测试与内部类名耦合，未来应改用公开入口。
2. **F-002（P3 accepted）**：`ConnectorCitationInjectionTest` 只覆盖 DTO 遮蔽映射；状态计算依赖既有
   `CitationSourceStateTest` 与 B9b/TASK-0207，V1b/V2b 需复核连接器真实读取路径。
3. M05-AC-14 的凭据/`externalId`/页面/审计/错误响应/a11y 部分仍是模块级 residual，不能由本任务单独宣称
   AC-14 已全绿。
4. 任务卡 `tools_used` 为空，无法从卡片核实 builder 是否使用 Serena/CodeGraph；属于项目级记录习惯，
   不阻断本任务，建议 Lead 统一回填。
5. 评审期间工作区有并行 B9b/TASK-0207 活动，main 从 `1eb7669` 前进到 `8d06bd4`；EV-0990 的 commit
   `7081487` 对 injection 目录无变化，未观察到假绿/假失败。

## 建议

- Lead 可将 TASK-0208 标为 done；不需要为 B10b 重开任务或修改生产代码。F-001/F-002 作为 P3 accepted
  带到 M25 gate / V1b-V2b 复核。
- 模块 gate 时由 Verifier 另跑合同 build/test/coverage/E2E 与安全/性能项；本任务级 EV 不能替代模块全量。
- 建议 Lead 在 handoff 中提醒并行 agent 遵守同一工作树长测试互斥，并逐步回填 `tools_used` 或写明降级理由。
