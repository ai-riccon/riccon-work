---
review_id: REV-TASK-0148
module_id: M19
task_id: TASK-0148
reviewer:
  agent: riccon/verifier
  session: ses_e00a1e7a3ffeHE4veIxjD0Qmtx
  model: opencode-go/deepseek-v4.1-flash#max
  independent: true
verdict: pass
reviewed_commits:
  - f5922239c5c91c7086807ac026053e1276823733
  - 43b2e5b650e93772048896a715b3d211b07e6c6f
  - fe6f978ab2333709514d98233b644c358696b94d
head_commit: fe6f978ab2333709514d98233b644c358696b94d
dod:
  acceptance: true
  build: true
  tests: true
  review: true
  evidence: true
dod_note: "任务级评审；模块 coverage/E2E/security/performance/migration 属 M19 模块 gate，不在本任务 DoD。"
findings:
  - id: F-001
    severity: P3
    title: "P4b 实际是新增 platform 词汇守卫，不是从 app 迁出的等价断言；角色→权限映射仍在 app"
    summary: "3 个新测试类（14 用例）只断言共享词汇：码值格式、常量名与码值一致、唯一性、核心码集合、平台/租户前缀边界、审计 actor/outcome/target 枚举集合。它们没有复制 apps/api RoleDefinitionsTest 的角色→权限映射，也没有复制 API/Worker AuditDetailsPolicyTest 的 details 白名单行为；映射与策略断言仍原样保留在 app。由于 platform 不能反向依赖 app（任务 AC #2 明确禁止），纯词汇测试是可行边界；app 断言零改动、全量 1115/0/0/0，未造成覆盖损失，但 P4 的“下沉”实际是增量平台覆盖，不减少 app 用例或墙钟。"
    status: accepted
    owner: riccon/builder-m19
    due: M19 gate 前
    accepted_reason: "app 断言未删除/弱化（43b2e5b 仅新增 3 个 platform 测试与 baseline）；apps/api 已依赖 platform-domain，再让 platform-domain 依赖 app 会形成 Maven reactor 环，且任务 AC #2 明确禁止新增反向依赖，映射与 details 策略只能留在 app；新测试为共享词汇提供了具体、可证伪的守卫，EV-0710 的 18/0/0/0 与 EV-0709 的 1115/0/0/0 证明无覆盖损失。"
    evidence: "platform/platform-domain/src/test/java/com/riccon/platform/domain/identity/RoleCodesTest.java:12-18,34-78; apps/api/src/test/java/com/riccon/api/identity/domain/RoleDefinitionsTest.java:13-78; EV-0710"
  - id: F-002
    severity: P3
    title: "baseline 时间值来自未单独录 EV 的全量运行，计时基线不可从现有证据复核"
    summary: "baseline 的 generated_from 记录 2026-11-22 02:05–02:14 的 external 全量，generated_at 为 02:15:13；EV-0709 是 02:16:48–02:26:55 的另一次全量（其日志累计 suite 时间 639.187s，baseline 为 657.788s），说明 baseline 不是由 EV-0709 的报告生成，且该 02:05–02:14 运行没有独立 EV/日志。类集合、每类 tests 与 module、0 failure/error/skip 均与 EV-0709 报告逐类一致；用当前报告执行 --update-baseline 到临时文件也重建出相同的 196 类/1115 用例与相同的 class/module/tests 集合，因此基线内容正确，仅时间来源不可复核。"
    status: accepted
    owner: riccon/builder-m19
    due: M19 gate 前
    accepted_reason: "时间值只用于分片负载估算，不参与用例集合、--check、合并校验或 gate 结论；用例数、类集合、分片互斥完整性已由 EV-0710 独立核对，基线内容可由当前全绿报告重建。"
    evidence: "scripts/test-shards-baseline.json:1-10,769-792; .riccon/evidence/TASK-0148/EV-0709.json; EV-0710"
reviewed_evidence:
  - EV-0709
  - EV-0710
evidence:
  - EV-0709
  - EV-0710
created_at: "2026-11-22T02:37:02+08:00"
---

# REV-TASK-0148：3 个 platform 词汇测试与分片基线重建的独立评审

## 结论

**pass**。`43b2e5b` 只在 `platform/platform-domain` 新增 3 个测试类（14 用例）并重建 `scripts/test-shards-baseline.json`：RoleCodesTest 4、PermissionCodesTest 4、AuditVocabularyTest 6；app 测试、生产源码、迁移、pom、CI 均零改动。3 个新测试的断言具体、可证伪，覆盖共享词汇的格式、唯一性、常量名一致性、核心码值与平台/租户边界；app 侧原有的角色→权限映射与 AuditDetailsPolicy 行为断言仍保留。baseline 从旧 193 类/1101 用例变为新 196 类/1115 用例，只增加 3 类/14 用例，无删除、无计数下降、0 failure/0 error/0 skip；`--check`、4 片模拟与合并校验均通过。独立复跑 `platform-domain` 为 4 类/18 用例/0/0/0（EV-0710）。

本评审是独立技术验收，不代表 Product 预验收或用户产品验收；任务状态不由此文件推进，由 Lead 决定。

## Diff 与写入边界

- `git diff --name-status f592223..43b2e5b` 只有 4 个文件：3 个新增 platform-domain 测试 + `scripts/test-shards-baseline.json`。
- `apps/api/src/test/**`、`apps/worker/src/test/**`、生产源码、Flyway 迁移、`pom.xml`、CI 均未改动；新测试只 import platform-domain 自身类，没有反向依赖 app。
- 测试类命名与包路径符合 platform 约定；无 `@Disabled`、`assumeTrue/assumeFalse`、`Assumptions` 或 skip 逻辑。
- 新测试均无 `@Tag`，按 `docs/CODING-STANDARDS.md` §10.4 的“untagged=unit”约定归入 unit；`test`/`coverage` 全量命令仍会执行。

## 3 类等价性审查

| 新测试（用例数）         | 关键断言                                                                                                                                                         | app 侧对应/保留情况                                                                                   | 判断                                                                                                                                                                          |
| ------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| RoleCodesTest（4）       | 常量名=码值、`^[A-Z][A-Z0-9_]*$`、非空、唯一、`containsOnly` 3 个内置码、平台角色唯一带 `PLATFORM_` 前缀、两个平台权限码与 3 个租户内容码不重叠                  | `RoleDefinitionsTest` 继续断言 tenantSystemRoles、角色→权限映射、平台管理员不持有租户内容权限；未改动 | 共享词汇守卫具体可证伪；映射不在 platform 测试中，但映射依赖 app 类，platform 反向依赖被 AC #2 禁止且会与 apps/api→platform-domain 的既有依赖形成 Maven 环，可接受为 P4b 边界 |
| PermissionCodesTest（4） | `<domain>.<action>` 小写点分格式、唯一、常量名=码值大写下划线形式、8 个核心码 `containsExactly`、`PLATFORM_*` 名称与 `platform.` 前缀一致、平台/租户集合不重叠   | `RoleDefinitionsTest` 继续断言角色到权限的映射（tenant、platform、部门、审计导出边界）；未改动        | 覆盖共享权限码本身的不变量；映射仍在 app，等价性判断同上                                                                                                                      |
| AuditVocabularyTest（6） | 动作名小写点分格式、唯一、常量名=动作名大写下划线形式、11 个 app 引用动作的精确码值、actor/outcome/target 枚举精确集合、target 19 项 `containsExactlyInAnyOrder` | API/Worker 两个 `AuditDetailsPolicyTest` 继续断言各自 details 白名单、拒绝路径与事件必填字段；未改动  | 共享词汇守卫具体可证伪；details 策略行为仍由 app 测试覆盖，3 个词汇类不替代行为测试                                                                                           |

断言强度：

- 没有 `isNotNull()`/`isNotEmpty()` 单独充当结论的弱断言；每个类都有精确值、精确集合、正则或唯一性断言。
- 反射 helper 读取所有 `static final String` 字段；`isNotEmpty()` + `containsOnly`/`containsEntry`/`containsExactly` 组合防止“漏读常量导致空断言通过”。
- `RoleCodesTest` 的 `platformAdminVocabulary_isSeparatedFromTenantScope` 只抽样 3 个租户权限码；`PermissionCodesTest` 的 `platformScopedCodes_areDisjointFromTenantCodes` 对所有权限码做了名称/前缀一致性检查，二者合起来覆盖平台/租户边界。
- 新测试与 `DomainVocabularyTest.contractConstantsAreUnique` 在“唯一性”上有少量重复，但新增了格式、名称一致、核心值、枚举集合等覆盖，不构成弱化。

### 关于“RoleCodesTest 不覆盖角色→权限映射”的结论

**可接受，记为 P3 F-001，不判 fail。** 理由：

1. app `RoleDefinitionsTest` 的角色→权限映射、部门边界、审计导出审批边界断言完整保留，diff 中没有任何 app 测试改动。
2. 映射测试依赖 `apps/api` 的 `RoleDefinitions`/`ErrorCodes`，把该测试移到 platform 需要 production 改动或 platform→app 反向依赖，二者都被本任务 AC 明确禁止。
3. 新测试覆盖的是映射所依赖的共享词汇（角色码、权限码值、平台/租户前缀），属于可在 platform 层独立验证的纯逻辑部分；这是在不违反写入边界下能做到的最大等价下沉。
4. 代价是 P4b 没有减少 app 用例数或 app 墙钟；P4 的提速主要由 P4a tag 分层与 P2/P3 承担。此项作为剩余风险记录，不阻塞任务。

## Baseline 与分片

### 基线内容

| 指标                   | 旧 baseline | 新 baseline | 变化                                                       |
| ---------------------- | ----------: | ----------: | ---------------------------------------------------------- |
| suite_count            |         193 |         196 | +3（仅新增 3 个测试类）                                    |
| test_count             |        1101 |        1115 | +14                                                        |
| failure/error/skip     |       0/0/0 |       0/0/0 | 不变                                                       |
| platform-domain 类数   |           1 |           4 | +3                                                         |
| platform-domain 用例数 |           4 |          18 | +14（RoleCodes 4 / PermissionCodes 4 / AuditVocabulary 6） |

- 对比 `git show f592223:scripts/test-shards-baseline.json` 与当前文件：193 个公共类中 **0** 个 tests 变化、**0** 个 module 变化、**0** 个删除；新增类恰为 `com.riccon.platform.domain.identity.RoleCodesTest`、`com.riccon.platform.domain.identity.PermissionCodesTest`、`com.riccon.platform.domain.audit.AuditVocabularyTest`。
- baseline 自身校验：`suite_count=196` 与 classes 长度一致；`test_count=1115` 与逐类汇总一致；`time_total_seconds=657.788` 与逐类汇总一致；无重复类、按类名升序；`failure_count/error_count/skip_count` 均为 0。

### 分片与源码一致性

- `node scripts/test-shards.mjs --check`：**通过**，输出“196 类 / 1115 用例，4 片互斥完整，源码测试类均已登记”。
- 4 片汇总：shard 0 = 48 类/245 用例，shard 1 = 48/254，shard 2 = 49/317，shard 3 = 51/299；合计 196/1115，与基线一致。
- 新 3 类各恰好分配到一个 shard（本会话 `--out-dir` 检查）：PermissionCodesTest → shard 0，AuditVocabularyTest → shard 2，RoleCodesTest → shard 3；原有 DomainVocabularyTest → shard 1。4 个 platform-domain 类正好一片一个。
- 用现有 surefire 报告模拟 4 片后执行 `node scripts/test-merge-shards.mjs`：**pass**，每个基线类恰好一次、无额外类、总用例 1115、`failures=0 errors=0 skipped=0`。
- 辅助检查：以当前全绿报告执行 `node scripts/test-shards.mjs --update-baseline --baseline "$TMPDIR/..."`（不覆盖仓库基线）重建出 196 类/1115 用例/0/0/0，且 class/module/tests 集合与提交 baseline 完全一致。该辅助命令未单独录 EV；核心 check/merge 已包含在 EV-0710 中。

## 独立复跑与证据

| 检查                                              | 结果                                                                                                                                              | 证据    |
| ------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------- | ------- |
| `./mvnw -q -pl platform/platform-domain -am test` | platform-domain 4 类/18 用例，0 failure/0 error/0 skip                                                                                            | EV-0710 |
| `node scripts/test-shards.mjs --check`            | 通过：196 类/1115 用例，4 片互斥，源码类已登记                                                                                                    | EV-0710 |
| `--simulate` + `test-merge-shards.mjs`            | 4 片 196 类/1115 用例，0/0/0，结论 pass                                                                                                           | EV-0710 |
| `./mvnw -q spotless:check`                        | exit 0                                                                                                                                            | EV-0710 |
| `git diff --check`                                | exit 0                                                                                                                                            | EV-0710 |
| Builder 全量 EV-0709                              | commit 43b2e5b，dirty=false，exit 0，单行命令，1,437,534 bytes，sha256 匹配；日志汇总 1115 用例/0 failure/0 error/0 skip，platform-domain 18 用例 | EV-0709 |

EV-0710 记录的当前 verifier 命令为单行：`SIM=$(mktemp -d) && ./mvnw -q -pl platform/platform-domain -am test && node scripts/test-shards.mjs --check && node scripts/test-shards.mjs --simulate "$SIM/shards" && node scripts/test-merge-shards.mjs --shards-root "$SIM/shards" --out-dir "$SIM/merged" && ./mvnw -q spotless:check && git diff --check && echo verifier_task_0148_ok`；`commit=fe6f978`、`dirty=false`、`exit_code=0`。

EV-0709 日志末尾有一次 `[ERROR] Surefire is going to kill self fork JVM. The exit has elapsed 30 seconds after System.exit(0).`。该行在 TASK-0146 的 EV-0700/EV-0704/EV-0706 中同样出现，是既有 surefire fork 退出等待；surefire 汇总仍为 0/0/0、Maven exit 0，本次新测试与该现象无关，不构成本任务失败。

## AC 对账

| AC 原文要点                                                                                                                 | 结论                           | 依据                                                                                   |
| --------------------------------------------------------------------------------------------------------------------------- | ------------------------------ | -------------------------------------------------------------------------------------- |
| 至少 3 个纯逻辑类/断言组下沉到 platform-common/platform-domain，断言与 app 侧等价；不移除/弱化 app 断言（除纯平台常数重复） | pass，附 P3 F-001 解释         | 3 个新类 14 用例；app 测试零改动；映射/策略仍在 app 且未弱化；platform 反向依赖被禁止  |
| 不改生产代码/迁移/pom/CI；不新增 app 反向依赖；命名/包与 platform 一致                                                      | pass                           | diff 仅 4 文件；新测试只 import platform-domain；包名为 `com.riccon.platform.domain.*` |
| 从干净全量 surefire 报告重建 baseline；`--check` 与合并校验通过；后端用例 ≥1101 且零 skip                                   | pass，附 P3 F-002 计时来源说明 | 196 类/1115 用例/0 skip；旧→新只 +3 类/+14 用例；check 与 merge 均通过                 |
| 跑 platform 测试 + 一次全量后端（或 CI 同款 shard 合并）证明新测试执行；证据单行；由 Verifier 评审等价断言                  | pass                           | EV-0709 builder 全量 1115/0/0/0；EV-0710 verifier 平台 18/0/0/0 + shard merge；本评审  |

## 模块 gate 现状

`node ~/.config/opencode/riccon/scripts/riccon.mjs gate check --module M19` 当前为 **FAIL**，与 TASK-0148 技术验收无关：

- TASK-0119 仍为 `review`，且 gate 以其旧基线把 TASK-0144/0145/0146/0147/0148 的测试文件归因为 TASK-0119 越界（TASK-0146 评审已记录该归因问题）；
- TASK-0148 本身仍为 `review`（等待 Lead closeout）；
- M19 尚无模块评审；
- 存在开放决策 DEC-0029（非本任务写入）。

这些是模块级收尾条件；本任务的任务级评审通过，不代表模块 gate 通过。

## 未验证

- **全量后端未由 Verifier 独立复跑**：本任务按任务级验证节奏执行 platform 定向测试、`--check` 与 4 片模拟/合并；EV-0709 是 builder 的全量证据，已核对 commit/dirty/exit/hash/日志汇总。模块 gate 仍需在新上下文跑全量合同命令。
- **baseline 原始 02:05–02:14 运行日志缺失**：baseline 的计时值无法从现有证据逐项复核（F-002）；类集合/计数已通过 EV-0709、`--check` 和临时重建核对。
- **CI 4-shard 矩阵未实际运行**：本次用现有报告模拟分片并合并（DEC-0028 仍要求单独申请 CI 取证）。
- **默认 Testcontainers 模式未运行**：本任务使用 external 热栈；一次性容器属模块 gate。
- **无 UI/E2E/coverage 变更**：本任务只新增测试与 baseline，不改生产代码；模块 gate 覆盖 coverage/E2E。

## 剩余风险

1. P4b 是增量平台词汇覆盖，不减少 app 用例或墙钟；若后续要求 P4 直接降低全量时间，需要另拆任务（当前 P4a/P2/P3 承担提速）。
2. baseline 时间值来自一次未单独录 EV 的全量运行，后续分片负载可能与本机报告有轻微偏差；不影响用例集合与互斥完整性。
3. 反射型词汇测试对常量重命名/新增敏感：新增角色/权限/动作时会因 `containsOnly`/`containsExactly`/`containsEntry` 失败，这是有意的契约守卫，但需要开发者同步更新测试与迁移。
4. 新测试与 `DomainVocabularyTest.contractConstantsAreUnique` 在唯一性上有重复；后续若重构词汇测试，可考虑合并，避免维护两份反射 helper。
5. `AuditVocabularyTest.appReferencedAuditActions_matchContractVocabulary` 只锁定 11 个动作，注释中“app 测试引用的动作”范围略宽于实际断言；app 测试仍通过编译引用覆盖其余常量，暂不构成覆盖缺口。

## 独立性声明

本次评审由 `riccon/verifier` 在独立会话 `ses_e00a1e7a3ffeHE4veIxjD0Qmtx` 执行；未采信 builder 总结作为结论，先读任务/模块 AC、diff、3 个新测试、app 等价来源、baseline 与 `test-shards.mjs`/`test-merge-shards.mjs` 实现，再在 `fe6f978` 上独立复跑 platform 定向测试、`--check`、模拟分片合并、spotless 与 diff 检查。未修改生产源码、迁移、构建配置或测试源码；只写评审与 `.riccon` 证据/状态。
