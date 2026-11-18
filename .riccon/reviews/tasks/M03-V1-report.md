---
review_id: REV-M03-V1-002
module_id: M03
task_id: TASK-0048
verifier: riccon/verifier
verdict: pass
reviewed_commits:
  - e530b6103d7806b07db996edaa1fc122e0ccf3e7
  - 0b87c273b850a1cc58e3f317b5750b73f4722278
created_at: 2026-11-18T23:06:00+08:00
evidence:
  - EV-0338
  - EV-0339
  - EV-0340
  - EV-0341
findings:
  - id: F-001
    severity: P1
    status: fixed
    summary: "部门管理页 axe serious 违规已修复；独立 pnpm -w a11y 8/8 通过"
  - id: F-002
    severity: P1
    status: fixed
    summary: "聊天页可切换到部门空间，会话/文件按空间加载并可继续；E2E 独立走通"
  - id: F-003
    severity: P2
    status: fixed
    summary: "负责人 CUSTOM 空间经部门空间列表合并，刷新后仍可见可管理"
  - id: F-004
    severity: P2
    status: fixed
    summary: "平台管理员管理端新增租户管理导航；E2E 通过导航进入租户列表"
  - id: F-005
    severity: P3
    status: fixed
    summary: "DEPT/TEN 完整性清单写入 surefire 输出并反射校验方法存在"
  - id: F-006
    severity: P3
    status: accepted
    summary: "J2 产物区域存在两个“引用清单”标题；真实栈测试已定位到 #artifact-citations-title，产品语义留待后续收敛"
---

# M03-V1 复验报告（B5 修复后）

## 结论

**M03-V1：pass。** 上一轮两个 P1（F-001 axe、F-002 部门空间打开旅程）与两个 P2（F-003 负责人 CUSTOM 列表、F-004 导航入口）已独立复验为 fixed；未发现新的 P0/P1。

**可以进入 M03-V2（模块全量 gate）**。M03-V2 仍需在最终提交上跑 `./mvnw verify`、`pnpm -w test/e2e/a11y`、coverage、security 和真实栈 J1/J2；本报告不替代模块 review。

## 独立验证结果

| 验证                     | 结果                                                                       | 证据                         |
| ------------------------ | -------------------------------------------------------------------------- | ---------------------------- |
| `pnpm -w e2e --grep M03` | **4 passed**（E2E-M03-01/02/07 功能 + M03-07 axe）                         | EV-0338，exit 0，dirty=false |
| `pnpm -w a11y`           | **8 passed**，无 serious/critical                                          | EV-0340，exit 0，dirty=false |
| DEPT/TEN API 回归        | **39 tests**（DEPT 11、TEN 28），**0 skipped**                             | EV-0339，exit 0，dirty=false |
| 完整性守卫               | `TEN-001..027`、`DEPT-001..010` 全部在 surefire XML；无 skip/failure/error | EV-0339 报告                 |

本轮未重复 full verify、coverage、security、真实栈（按指令只做定向复验；M03-V2 执行）。

## F-001..F-006 对账

| ID                 | 上轮    | 本轮         | 独立证据                                                                                                                                                                                                       |
| ------------------ | ------- | ------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| F-001 axe          | P1 open | **fixed**    | EV-0340：8/8 a11y；部门页启用 Tag 使用 `#237804` 文本、停用按钮 `#a8071a`、`autoInsertSpace={false}`，aria-label 含可见文本；M03-07 axe 无 serious/critical                                                    |
| F-002 部门空间旅程 | P1 open | **fixed**    | EV-0338：`E2E-M03-02` 切换“销售部项目空间”→“销售部部门会话”→加载“销售部客户清单.pdf”（解析成功）→带文件继续提问成功；切回个人空间后旧会话/文件不再显示。stub 只在 `e2e/` 扩展团队空间 conversations/files 响应 |
| F-003 CUSTOM 列表  | P2 open | **fixed**    | EV-0338：负责人创建 CUSTOM 后**不把自己加为显式成员**，刷新仍看到“重点客户空间”和“管理成员”；共享抽屉合并 `/departments/{id}/workspaces`                                                                       |
| F-004 导航入口     | P2 open | **fixed**    | EV-0338：`E2E-M03-01` 从 `/admin/overview` 点击“租户管理”导航进入 `/admin/tenants`                                                                                                                             |
| F-005 完整性守卫   | P3 open | **fixed**    | EV-0339：两个套件输出 `SUITE-MANIFEST`，并反射断言每个编号对应方法存在；旧 M02 `TenantIsolationTest` 仍未加 `@Tag`，见剩余风险                                                                                 |
| F-006 重复标题     | P3 open | **accepted** | `e2e/real/tests/j2.spec.ts` 定位 `#artifact-citations-title`；产品侧两个“引用清单”标题保留为 P3                                                                                                                |

## AC 对账

| AC            | 结论          | 说明                                                                 |
| ------------- | ------------- | -------------------------------------------------------------------- |
| M03-AC-01     | pass          | E2E-M03-01：平台管理员创建部门、加/移成员、任命负责人                |
| M03-AC-02     | pass          | E2E-M03-02：负责人创建/改可见性/CUSTOM 成员；普通成员无管理入口      |
| M03-AC-03     | pass（UI 段） | E2E-M03-02：切换部门空间后只显示该空间会话；API DEPT-001 交叉验证    |
| M03-AC-04     | pass（UI 段） | E2E-M03-02：部门空间会话/文件加载并可继续；API DEPT-002/003 交叉验证 |
| M03-AC-05     | pass          | DEPT-004：同部门成员访问个人空间 404                                 |
| M03-AC-06     | pass          | E2E-M03-02：知识库固定文案 + 源文件 404 不可点击；DEPT-010           |
| M03-AC-07     | pass          | DEPT-006/007/008：停用、成员移出、负责人移交下一次请求生效           |
| M03-AC-19     | pass          | DEPT-001..010，零 skip                                               |
| M03-AC-20     | pass          | TEN-001..027，零 skip；完整性守卫通过                                |
| M03-AC-21     | pass          | 375px/键盘通过；axe 8/8 无 serious/critical                          |
| M03-AC-08..18 | 不适用        | M10 范围                                                             |

## 修改范围

- `e2e/stub/server.mjs`：为部门空间提供 `/workspaces/{id}/conversations`、`/workspaces/{id}/files` 的 workspace 过滤与真实响应；播种部门会话、消息与已解析文件。
- `e2e/tests/m03.spec.ts`：新增切换工作空间 → 部门空间会话/文件加载 → 继续提问 → 切回清空；F-003 不把负责人加为显式成员仍能在刷新后管理；F-004 走 admin 导航。
- 未修改生产源码、迁移、contracts 或产品文档。

## 剩余风险 / 未覆盖

- **F-005 遗留**：M02 `TenantIsolationTest` 未加 `@Tag("tenant-isolation")`；本轮完整性清单覆盖 verifier 套件，旧用例仍随 full verify 执行，但 tag 过滤不会选中它们。建议 M03-V2 或后续测试整理时补 tag。
- **F-006（P3，已接受）**：J2 产物正文与引用区各有一个“引用清单”标题；测试已精确定位，产品语义后续收敛。
- **未验证**：full `./mvnw verify`、coverage、security、真实栈 J1/J2 本轮未重跑；由 M03-V2 负责。完整 stub E2E（23/23）与 API DEPT/TEN 已回归。
- **真实栈**：本轮只扩了 `e2e/` stub，不进入生产构建；未修改 `scripts/real-stack/**`。
