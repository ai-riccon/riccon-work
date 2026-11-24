---
review_id: REV-TASK-0241-001
module_id: M06
task_id: TASK-0241
reviewer:
  agent: riccon/verifier
  session: ses_df010bebaffeJK2xpvlGl8m9JI
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: fail
reviewed_commits:
  - c1f8f19096cb02a1cdbc138c1b05ea7acb957484
  - d324e11f06fa189569175cdc5e82ddb16a1f3426
  - 98c88e8b0c0eb6572f2454deb3db667ed22f79c2
head_commit: 98c88e8b0c0eb6572f2454deb3db667ed22f79c2
code_commit: d324e11f06fa189569175cdc5e82ddb16a1f3426
evidence_head: 98c88e8b0c0eb6572f2454deb3db667ed22f79c2
baseline_commit: 219e196
contract_commit: ca651583e2075a8e6fc11d2fa9093d9e8ea75765
related_reviews:
  - REV-TASK-0222-001
  - REV-TASK-0223-001
review_scope: >-
  M06 T04b 任务级独立验收：F-001 幂等契约收敛（create/duplicate/publish 可选键、publish 资源级幂等、
  run/retry/approval 键级幂等不变）、F-002 VALIDATE 可选 runScope/departmentId 且复用 PUBLISH 范围校验、
  F-003 WorkflowContractM06Test 同步、F-005 detail/get/saveDraft 的 canRun 与 T05 准入/RUNNABLE 同口径，
  以及写范围、T03/T04/T05 定向回归、EV-1041 与独立复跑。不重审 T05 运行执行器、T10 UI、
  T16a 全量 E2E/axe/perf/security/coverage 或 M06 模块 gate；不替代 Product 预验收或用户最终验收。
dod:
  acceptance: true
  build: true
  tests: true
  review: true
  evidence: false
  lint: true
dod_not_applicable:
  - coverage
  - e2e
  - security
  - performance
  - accessibility
dod_note: >-
  acceptance/build/tests/review/lint=true 指 T04b 功能范围：EV-1041（commit d324e11，dirty=false）的
  88 个 API 用例 + 12 个 ArchUnit 用例全部 0 failures/0 errors/0 skipped；本评审独立复跑 56 个 API
  用例 + 12 个 ArchUnit 用例也全部通过；本任务文件 spotless 定向检查 exit 0，HEAD a35d1f5 全仓
  spotless:check 亦 exit 0。evidence=false 是本次 fail 的唯一阻碍：TASK-0241.evidence 仍引用
  dirty=true 的 EV-1034（exit 1）与 EV-1035（exit 0），strict 绑定下 M06 gate 的
  evidence_attestation 必失败，见 F-006。coverage/e2e/security/performance/accessibility 属模块 gate
  与 T16a，本任务未运行。
findings:
  - id: F-001
    severity: P2
    status: fixed
    title: "F-001 幂等收敛：create/duplicate/publish 可选键，publish 资源级幂等"
    summary: >-
      c1f8f19 将三个端点改为可选 Idempotency-Key：缺失直接通过，提供时只校验 8..128；create/duplicate
      不承诺键级重放；publish 在 WorkflowPublishService 按 graph_sha256+runScope+departmentId
      命中当前发布版本时返回既有版本，不写第二条版本或审计。run/retry/approval 代码与契约仍要求
      键级幂等，本任务未改。新增测试覆盖缺失键、带键创建/复制不重放、无键重复发布只产生一版一审计。
    evidence: >-
      WorkflowDefinitionController.java:119,122,209,212,299-308；WorkflowPublishController.java:88,91,126-136；
      WorkflowPublishService.java:143-153；WorkflowDefinitionApiTest.java:395-451；
      WorkflowPublishApiTest.java:283-304；WorkflowContractM06Test.java:97-139；
      contracts/openapi.yaml:2566-2570,2675-2682,2747-2755。
  - id: F-002
    severity: P2
    status: fixed
    title: "F-002 VALIDATE 可选 runScope/departmentId 并复用 PUBLISH 范围校验"
    summary: >-
      validate 端点新增可选 query；非法枚举/UUID 由控制器返回 400 RICCON-WF-400。WorkflowDefinitionService.validateDraft
      调用与 publish 相同的 WorkflowPublishScopeValidator：DEPARTMENT 必须带本人负责的启用部门；非
      DEPARTMENT（含省略范围）携带 departmentId 返回 400；租户管理员仅可 ONLY_SELF/TENANT。
      方法 @Transactional(readOnly=true)，只读取部门/审批信息，不改 draftVersion、不写版本或审计。
    evidence: >-
      WorkflowDefinitionController.java:182-191,263-290；WorkflowDefinitionService.java:341-350；
      WorkflowPublishScopeValidator.java:48-84；WorkflowValidationJdbc.java:92-103,222-227；
      WorkflowValidationApiTest.java:139-215；WorkflowPublishApiTest.java:306-390；
      WorkflowPublishScopeValidatorTest.java:43-137；contracts/openapi.yaml:2710-2731。
  - id: F-003
    severity: P2
    status: fixed
    title: "F-003 WorkflowContractM06Test 与 1.6.0-m06 同步"
    summary: >-
      契约测试不再断言 create/duplicate/publish 的 Idempotency-Key，改为断言 run/retry/approval 三个
      操作仍声明 required=true；同时断言 publish 描述含 graph_sha256/资源级幂等、create 描述不承诺
      键级重放，以及 validate 的 runScope/departmentId 为可选且 runScope 引用封闭枚举。
    evidence: >-
      WorkflowContractM06Test.java:97-154,311-333；contracts/openapi.yaml:2566-2570,2675-2682,
      2710-2731,2747-2755,2920-2930,3055-3070,3140-3155；EV-1041 独立复跑 6/6。
  - id: F-005
    severity: P2
    status: fixed
    title: "F-005 detail/get/saveDraft 的 canRun 与 T05 准入/RUNNABLE 同口径，owner 不再是例外"
    summary: >-
      d324e11 让 get/saveDraft 对所有者与普通主体一样按发布版本 runScope 计算 departmentId/scopeVisible，
      detail 的 canRun 改为 latest!=null && ACTIVE && actor.canRun() && (admin || scopeVisible)。
      这与 T05 requireRunnable/withinVersionScope 的“租户管理员放行、其余按版本范围”一致，也与
      RUNNABLE 列表的 published+ACTIVE+范围谓词一致；所有者移出 DEPARTMENT 后详情可见但 canRun=false。
      新增集成用例覆盖 owner 移出部门、仍在部门编辑者、无部门成员与 RUNNABLE 对照；租户管理员
      准入由 T05 WorkflowRunScopeApiTest 覆盖。
    evidence: >-
      WorkflowDefinitionService.java:158-172,317-323,490-500,528-551；WorkflowPublishApiTest.java:447-512；
      WorkflowRunScopeApiTest.java:148-170；WorkflowRunService.java:423-457；WorkflowDefinitionJdbc.java:264-298。
  - id: F-006
    severity: P1
    status: open
    owner: riccon/builder-b2
    due: "T04b done 前（M06 gate 前）"
    title: "TASK-0241 evidence 仍引用 dirty EV-1034/EV-1035，strict 绑定下 M06 gate 必失败"
    summary: >-
      EV-1034 为 dirty=true、exit_code=1；EV-1035 为 dirty=true、exit_code=0；TASK-0241.evidence
      同时引用两者与 clean 的 EV-1041。项目合同 quality.evidence_binding=strict，gate.mjs:386-409
      汇总本模块全部任务 evidence，任一 dirty 记录即 evidence_attestation=fail（EV-1034、EV-1035
      均命中）。EV-1041 自身 clean、hash 一致，但不能抵消另外两条 dirty 记录。修复方式：从
      task.evidence 移除 EV-1034/EV-1035（日志可保留为历史，不参与取证），或对相应命令重录
      clean 证据；然后重新复审。
    evidence: >-
      .riccon/tasks/TASK-0241.json:22-26；.riccon/evidence/TASK-0241/EV-1034.json:8-9；
      .riccon/evidence/TASK-0241/EV-1035.json:8-9；scripts/lib/gate.mjs:386-409；
      AGENTS.md quality.evidence_binding=strict。
  - id: F-007
    severity: P3
    status: accepted
    owner: riccon/builder-b2
    due: "M06 gate 前"
    title: "F-005 的 saveDraft 返回路径与租户管理员 canRun 分支缺少独立断言"
    summary: >-
      d324e11 同时改了 saveDraft 的 canRun 计算，但新增用例只覆盖 GET 详情、RUNNABLE 与 owner
      移出部门；未在 owner 移出后调用保存再断言 canRun，也未直接断言 tenantAdmin 在 ONLY_SELF/
      DEPARTMENT 发布版本上的 canRun=true。代码与 get 共用 detail(...)/withinScope，T05
      WorkflowRunScopeApiTest 已覆盖管理员运行准入。
    accepted_reason: >-
      同一 detail()/withinScope 逻辑被 get/saveDraft 复用，saveDraft 仅多一次 latestVersion/
      activeDepartmentId 读取；管理员分支由 T05 运行准入用例独立覆盖。属于测试完整性改进，
      不改变当前可运行性判断，不影响本任务验收结论。
  - id: F-008
    severity: P3
    status: accepted
    owner: riccon/lead
    due: "M06 gate 前"
    title: "任务卡未把 F-005 写入 acceptance，baseline 由 219e196 改为 53a3c84"
    summary: >-
      c1f8f19 创建的任务卡为 baseline=219e196、end=c1f8f19；98c88e8 改为 baseline=53a3c84、
      end=d324e11。53a3c84 是 c1f8f19 的后代，因此 baseline..end 只覆盖 F-005 收口提交，不覆盖
      T04b 的 F-001/F-002 主体；任务卡 acceptance 三条仍未列入 F-005（REV-TASK-0223-001 F-002
      已提示 Lead 补入或另立任务）。实际两个 T04b 提交的全部文件路径均在 write_scope 内，功能无缺口。
    accepted_reason: >-
      仅影响任务卡追溯与 gate 的 baseline..end 视图，不改变代码/测试/证据有效性；模块 review 应以
      c1f8f19 + d324e11 的逐提交路径复核 T04b，并把 F-005 作为 T04b 关闭项记录；Lead 在 M06 gate 前
      补任务卡 acceptance 或模块 review 说明即可。
evidence:
  - EV-1034
  - EV-1035
  - EV-1041
reviewed_evidence:
  - EV-1041
created_at: "2026-11-25T07:53:14+08:00"
---

# REV-TASK-0241-001：M06 T04b 幂等契约收敛与 validate runScope 独立评审

## 结论

**不通过（fail，功能通过、证据门禁未通过）**。F-001/F-002/F-003/F-005 的代码、契约和测试均通过
独立复核：EV-1041 在 `d324e11` 上 88 个 API 用例 + 12 个 ArchUnit 用例全绿，本评审独立复跑
56 个 API 用例 + 12 个 ArchUnit 用例也全绿；T04b 文件 spotless 通过，改动路径均在 write_scope 内。
但 TASK-0241.evidence 仍引用两条 `dirty=true` 的历史证据（EV-1034 还 `exit_code=1`），在合同
`quality.evidence_binding: strict` 下，模块 gate 的 `evidence_attestation` 必然失败。**功能可验收，
证据清单修复前不能 done**；见 F-006。

## 评审对象与范围

- 任务卡：`.riccon/tasks/TASK-0241.json`，owner `riccon/builder-b2`，status `review`，evidence
  `EV-1034/EV-1035/EV-1041`，记录 baseline `53a3c84`、end_commit `d324e11`。
- 代码提交：`c1f8f19`（17 文件，+899/-92，F-001/F-002/F-003 与 validate 范围）与
  `d324e11`（2 文件，+74/-4，F-005 canRun）；证据/任务卡提交 `98c88e8`。契约基准 `ca65158`
  （`1.6.0-m06`，仅 contracts/ADR，无 schema/V16）。
- 任务卡 baseline 的语义存在漂移：原始 c1f8f19 卡片为 `219e196`，98c88e8 改为 `53a3c84`（它是
  c1f8f19 的后代，只覆盖 F-005 收口）。本评审按 `c1f8f19` + `d324e11` 的逐提交文件路径判断 T04b
  范围，见 F-008。
- 评审期间并行任务把 HEAD 从 `98c88e8` 推到 `4e302e7`（T10b 前端）与 `a35d1f5`（T05 运行包
  spotless 格式化）；`a35d1f5` 不含任何 T04b 文件，`git diff d324e11..HEAD -- apps/api` 仅为
  `workflow/run/**` 格式化，T04b 代码判断仍以 `d324e11` 为准。

## F-001：幂等契约收敛

- **create/duplicate**：`@RequestHeader(value = "Idempotency-Key", required = false)` +
  `validateIdempotencyKey` 的 null 直接返回，缺失不拒绝；提供时只校验 8..128
  （`WorkflowDefinitionController.java:119,122,209,212,299-308`）。同一键连续创建/复制产生不同 ID，
  证明不承诺键级重放；每次成功仍写一条 `workflow.created` 审计
  （`WorkflowDefinitionApiTest.java:395-451`）。
- **publish**：控制器同样改为可选键（`WorkflowPublishController.java:88,91,126-136`）；服务在
  `scopeValidator.resolve` 之后比较当前发布版本的 `graphSha256 + runScope + departmentId`，命中即
  返回既有版本，不写第二条版本/发布审计（`WorkflowPublishService.java:143-153`）。新用例在完全
  不带键的情况下连续发布两次，断言同版本、版本数 1、发布审计 1
  （`WorkflowPublishApiTest.java:283-304`）；原有带键/并发/失败原子性用例仍保留并通过。
- **run/retry/approval 不变**：`git diff-tree` 显示 T04b 两个提交未触碰
  `workflow/run/**`、审批或重试代码；`WorkflowContractM06Test` 仍断言这三个操作声明
  `Idempotency-Key` 且 `required=true`（契约 2920-2930、3055-3070、3140-3155）。键级重放行为属于
  T05/T08/T09，本任务只确认未被 T04b 改变。
- 契约 `1.6.0-m06` 已从 create/duplicate/publish 删除该参数，并写明 publish 资源级幂等
  （`contracts/openapi.yaml:2566-2570,2675-2682,2747-2755`）。**F-001 关闭。**

## F-002：VALIDATE 可选范围

- 控制器新增 `@RequestParam(required=false)` 的 `runScope/departmentId`，非法枚举/非法 UUID 由
  `parseRunScope`/`parseDepartmentId` 转 400 `RICCON-WF-400`
  （`WorkflowDefinitionController.java:182-191,263-290`）。
- 服务调用与 publish 相同的 `WorkflowPublishScopeValidator.resolve`：`DEPARTMENT` 必须带 departmentId，
  且必须是本人当前负责的启用部门；非 `DEPARTMENT`（包括省略范围）携带 departmentId 返回 400；
  `TENANT` 仅租户管理员；省略范围不查询部门、只校图/审批可达
  （`WorkflowPublishScopeValidator.java:48-84`；`WorkflowValidationJdbc.java:92-103,222-227`）。
  这满足“带范围复用 PUBLISH 范围校验、省略时不含范围”。
- 只读性：`validateDraft` 为 `@Transactional(readOnly = true)`，路径中没有草稿更新、版本写入或
  audit 调用（`WorkflowDefinitionService.java:341-350`）。集成测试在多次合法/非法范围校验后断言
  `draftVersion` 不变、版本数 0、发布/保存审计计数不变
  （`WorkflowValidationApiTest.java:139-215`；`WorkflowPublishApiTest.java:306-390`）。
- 单元测试覆盖省略范围、ONLY_SELF/TENANT/DEPARTMENT、缺部门、非 DEPARTMENT 带部门、非本人部门、
  管理员 DEPARTMENT 拒绝、本人部门通过（`WorkflowPublishScopeValidatorTest.java:43-137`）。**F-002 关闭。**

## F-003：契约测试同步

`WorkflowContractM06Test` 现在断言 23 个 operation、117 条路径、CSRF、权限与 operationId 不变；
create/duplicate/publish **不声明** Idempotency-Key；run/retry/approval 声明且 `required=true`；
publish 描述包含 `graph_sha256`/“资源级幂等”，create 描述包含“不承诺 Idempotency-Key 键级重放”；
validate 的 `runScope`/`departmentId` 可选，`runScope` 引用 `WorkflowRunScope` 封闭枚举
（`WorkflowContractM06Test.java:97-154,311-333`）。独立运行 6/6 通过。**F-003 关闭。**

## F-005：canRun 与 T05/RUNNABLE 同口径

- `WorkflowDefinitionService.detail` 的 `canRun` 为
  `latest != null && status == ACTIVE && actor.canRun() && (admin || scopeVisible)`，owner 不再
  直接短路（`WorkflowDefinitionService.java:539-542`）。`get` 对所有非管理员主体计算
  `activeDepartmentId` 和 `withinScope`（`:158-172`）；`saveDraft` 在保存后用相同方式重算
  （`:317-323`）；`withinScope` 仍为 TENANT 全租户、ONLY_SELF 当前 owner、DEPARTMENT 命中当前部门
  （`:490-500`）。
- T05 `requireRunnable` 为 `tenantAdmin || withinVersionScope`，`withinVersionScope` 使用所选版本的
  runScope/departmentId（`WorkflowRunService.java:423-457`）；RUNNABLE 列表 SQL 为
  published + ACTIVE + 范围谓词（`WorkflowDefinitionJdbc.java:264-298`）。三者对“管理员放行、其余
  按版本范围”一致。
- 集成用例发布 DEPARTMENT 版本后，owner/同部门编辑者 canRun=true 且出现在 RUNNABLE；把 owner 移出
  部门后，owner 详情仍可见、可编辑但 canRun=false 且不在 RUNNABLE，同部门编辑者保持 true，无部门
  成员 404（`WorkflowPublishApiTest.java:447-512`）；T05 侧对应 owner 移出后运行 404、管理员仍可运行
  （`WorkflowRunScopeApiTest.java:148-170`）。**F-005 功能关闭**；saveDraft 返回路径与管理员 canRun
  的独立断言缺口记 P3 F-007。

## 范围与回归

- **写范围**：`c1f8f19` 只改 `workflow/{publish,definition,validation}` 生产/测试、`WorkflowContractM06Test`
  与任务卡；`d324e11` 只改 `WorkflowDefinitionService` 与 `WorkflowPublishApiTest`。全部落在
  TASK-0241 的 7 个 write_scope 内（任务卡自身的 `.riccon/tasks/TASK-0241.json` 是任务状态记录）。
- **禁止面**：`git diff-tree --no-commit-id --name-only -r c1f8f19 d324e11` 无
  `db/migration`、`contracts/**`、`docs/PRODUCT.md`、`AGENTS.md`、research/J2 或 worker 文件；
  `git diff 219e196..HEAD --` 上述路径为空。V15/schema 未变。
- **架构**：`ModuleBoundaryTest` 独立复跑 12/12 通过；`WorkflowPublishScopeValidator` 通过
  `WorkflowValidationSupport` 端口读取部门，未引入 service → service 依赖。
- **回归**：EV-1041 覆盖 T03（Definition/Validation/Validator/Graph）、T04（Publish/Lifecycle/
  AuditFailClosed）、T05（RunScope）与契约/平台过滤共 88 个 API 用例 + 12 ArchUnit；独立复跑
  Contract/ScopeValidator/PublishApi/VersionLifecycle/Definition/Validation/RunScope 共 56 个 API
  用例 + 12 ArchUnit，全部 0/0/0。未重跑 T05 全量运行/执行器套件（T04b 未改 run 代码）。
- **格式**：T04b 文件定向 `spotless:check -DspotlessFiles=...` exit 0；评审开始时全仓
  `spotless:check` 仅因 T05 `workflow/run/**` 失败（外部项，REV-TASK-0223-001 F-001），随后
  `a35d1f5` 已格式化，当前 HEAD 全仓 `spotless:check` exit 0。`git diff --check` 对两个 T04b 提交
  均 exit 0；变更测试无 `@Disabled`/`assumeTrue`/skip。

## 证据复核

| 证据    | 命令摘要                 | commit    | dirty | exit | 输出/哈希                          | 结论                                                     |
| ------- | ------------------------ | --------- | ----- | ---- | ---------------------------------- | -------------------------------------------------------- |
| EV-1034 | 12 类 API + ArchUnit     | `c1f8f19` | true  | 1    | 143827 B / `045d05…`               | 失败且脏；历史记录，不能作为 done 证据（F-006）          |
| EV-1035 | 11 类 API（无 ArchUnit） | `c1f8f19` | true  | 0    | 138610 B / `a82948…`               | 通过但脏；同样不能作为 strict 证据（F-006）              |
| EV-1041 | 12 类 API + ArchUnit     | `d324e11` | false | 0    | 143869 B / `680dc30a…`（校验一致） | clean pass；88 API + 12 ArchUnit，0 failures/errors/skip |

- EV-1041 的 `commit` 与 `d324e11` 一致，`dirty=false`、`exit_code=0`；输出文件 SHA-256
  `680dc30acd47ee1c1018cefbc34d12d9d122ffbd05866ab2e6bfb17133349915` 与记录一致。日志中的
  `GlobalExceptionHandler unexpected error` 三条来自审计 fail-closed 负向用例（transfer/disable/
  publish），命令整体 exit 0，不是失败。
- **计数更正**：EV-1041 的 surefire 报告实测为 88 个 API 用例（6 Contract + 12 ScopeValidator +
  14 Validator + 10 Publish + 5 Lifecycle + 3 AuditFailClosed + 8 Definition + 11 Validation +
  4 GraphDomain + 6 GraphJson + 5 PlatformFilter + 4 RunScope），不是派单口径的 70；差异是计数
  口径，不是测试失败。
- 独立复跑（评审时 HEAD `98c88e8`/后续 `a35d1f5`，T04b API 代码与 `d324e11` 相同）：
  `./mvnw -q -pl apps/api -am test -Dtest='WorkflowContractM06Test,WorkflowPublishScopeValidatorTest,WorkflowPublishApiTest,WorkflowVersionLifecycleApiTest,WorkflowDefinitionApiTest,WorkflowValidationApiTest,WorkflowRunScopeApiTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
  → exit 0，56 tests / 0/0/0；
  `./mvnw -q -pl platform/platform-architecture-tests -am test -Dtest='ModuleBoundaryTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
  → exit 0，12 tests / 0/0/0。
- 本评审未录入新 EV（按任务要求只写评审文件）；以上独立复跑是评审时命令结果，不是 builder 证据。

## Findings 汇总

- **F-001/F-002/F-003/F-005（P2，fixed）**：T04b 的功能验收已关闭，详见上文。
- **F-006（P1，open）**：任务证据仍引用 dirty EV-1034/EV-1035，strict gate 必失败。**这是本次
  verdict=fail 的唯一 P1 阻断**，不是代码缺陷。
- **F-007（P3，accepted）**：saveDraft 返回路径与租户管理员 canRun 缺独立断言，见 front matter。
- **F-008（P3，accepted）**：任务卡 F-005 acceptance 与 baseline 追溯问题，见 front matter。

## 未验证

- 合同全量 `build`/`test`/`coverage`/`E2E`/`a11y`/`security`/`perf`/`migration rollback` 未运行；
  本任务只做受影响定向测试与 ArchUnit。模块 gate 仍需最终提交上的全量命令与 EV。
- T05 全量运行/执行器、T08/T09 审批历史、T10 前端浏览器旅程、T16a 跨部门/跨租户/axe/perf/
  J1/J2 全量 E2E 未运行；T10 编辑页是否在所有路径都传 runScope 只做了代码浏览，未做浏览器验证。
- EV-1034/EV-1035 取证时的 dirty 工作树内容无法从日志重放；本评审只复核记录字段与 output hash。
- 未运行 `riccon gate check --module M06`（按任务要求）；gate 结论基于合同 strict 配置与
  `gate.mjs:386-409` 的确定性规则。

## 剩余风险

1. **F-006 是当前唯一阻断**：修复 evidence 清单后需重新提交任务卡并复审；否则 M06 gate 不会通过。
2. **F-007**：F-005 的 saveDraft/管理员 canRun 断言可在 T16a 或后续 T04b 补测；当前逻辑风险低。
3. **F-008**：任务卡 baseline 改为 53a3c84 后，`baseline..end` 不再覆盖 c1f8f19；模块 review 必须
   按两个 T04b 提交分别审计，不能只看任务卡区间。当前 HEAD 已继续前移，模块 gate 前 Lead 应收敛
   各任务 end_commit/证据。
4. **并行任务影响**：评审期间 T05 spotless 已由 `a35d1f5` 修复；其他 M06 任务仍有 in_progress/
   planned 状态和 dirty 证据（例如 TASK-0242 的 EV-1042/EV-1043），模块 gate 前需统一清理。
5. **T10 契约消费**：T10 必须在 `POST /validate` 传当前 runScope（DEPARTMENT 时带 departmentId），
   才能把 `valid=true` 当作可发布信号；本任务服务端已就绪，UI 端到端未验证。

## 是否可 done

**功能层面满足 T04b 的 F-001/F-002/F-003/F-005，但本任务当前不可 done**：F-006 是 P1，且
`quality.evidence_binding: strict` 下的 gate 规则明确会因 EV-1034/EV-1035 的 `dirty=true` 失败。
builder 只需把这两条 dirty 记录从 `TASK-0241.evidence` 移除（或重录 clean 证据），重新提交任务卡后
复审；无需改代码。修复并复审 pass 后，T04b 可 done；M06 模块 gate 还需其他任务与全量命令收敛。
