# TASK-0114 第二轮阻塞诊断（2026-11-21T09:22+08:00）

> 非 EV。TASK-0130（`0e95989`）修复已验证：成本页不再出现 BigInt `Math.pow` 崩溃，
> 配额 Modal 不再出现 `useForm` 警告，`E2E-M04-05` 与真实栈配额/成本 API+UI 均通过。
> 仍有 2 个 B9b 页面级问题导致合同桩 E2E 与 `@a11y` 不能全绿；按“不放宽断言”原则登记阻塞。

## 当前结果

| 命令                                                                                           | 结果                                                    |
| ---------------------------------------------------------------------------------------------- | ------------------------------------------------------- |
| `pnpm exec playwright test --config e2e/playwright.config.ts e2e/tests/m04-quota-cost.spec.ts` | 1 passed / 2 failed（日志 `stub-e2e-blocker.log`）      |
| `pnpm -w a11y`                                                                                 | 14 passed / 1 failed（日志 `a11y-command-blocker.log`） |
| `REAL_PLAYWRIGHT_GREP='M04-B11b' node scripts/real-stack/run.mjs --mode=full`                  | 2 passed / 1 failed（日志 `real-stack-b11b-final.log`） |
| 真实栈 `E2E-M04-05`                                                                            | 通过                                                    |
| 真实栈 `E2E-M04-06`                                                                            | 通过（日志 `real-stack-cost-targeted-pass.log`）        |
| 真实栈四页 @a11y                                                                               | 失败节点同下                                            |

## 缺陷 4（P1）：租户成本表使用 antd 6 已废弃 `expandable.expandIcon`

- 失败用例：合同桩 `E2E-M04-06`（功能断言全部通过，console 守卫失败）。
- 节点：`console.error: Warning: [antd: Table] 'expandable.expandIcon' is deprecated. Please use 'components.ExpandIcon' instead.`
- 位置：`apps/web/src/pages/admin/costs/index.tsx` 的租户分支
  `expandable={{ expandIcon: ({ expanded, onExpand, record }) => ... }}`。
- 影响：`e2e/tests/fixtures.ts` 的 console 守卫 allowlist 为空；项目规则禁止为页面自身
  可修复的告警临时放行。
- 建议修复（B9b）：迁移到 `components={{ ExpandIcon }}`，保留现有
  `aria-label`（查看/收起明细）、`type="link"` 与键盘行为。

## 缺陷 5（P1，AC-14）：平台成本表横向滚动区不可键盘访问

- 失败用例：合同桩 `E2E-M04-07b`（375px，成本页 axe）与真实栈四页 @a11y。
- 节点：`scrollable-region-focusable (serious)`，target `.ant-table-content`，
  `style="overflow: auto hidden"`；`focusable-content` 与 `focusable-element` 均不满足。
- 原因：平台成本表 `scroll={{ x: 900 }}` 在 375px 产生横向滚动，但平台视图表格行内
  没有任何可聚焦元素（租户视图有“查看明细”按钮，因此不触发）。
- 影响：AC-14 “仅用键盘操作 / axe 无 serious 或 critical” 不通过。
- 建议修复（B9b）：让滚动容器或其中内容可聚焦，例如给平台成本表加
  `onRow={() => ({ tabIndex: 0 })}`（行可聚焦后 axe 的 `focusable-content` 满足），
  或等价地给滚动容器增加 `tabIndex=0`；修复后用 375px axe 与键盘 Tab 复核。

## P2：成本页整页刷新默认维度不一致

- 现象：登录后首次 `page.goto('/admin/costs')` 或 `page.reload()` 时，`groupBy` 初值可能为
  `DAY`（平台/租户都出现），而不是 AC-11 期望的平台 `TENANT` / 租户 `USER`。
- 根因：`groupBy` 的 `useState` 初始化依赖 `auth.session.permissions`；会话恢复前权限为空，
  初值为 `DAY`，后续 effect 只处理 USER↔TENANT 的互斥，不把 DAY 纠正为角色默认维度。
- 现状：B11b 用例已显式选择 AC-11 维度（并注明 P2），不作为 gate 阻塞；但用户直接刷新
  会看到“按日”，建议 B9b 在 `mode` 确定后补齐默认维度。

## 已验证的 TASK-0130 修复

- `apps/web/src/services/cost/format.ts` 的 `BigInt(\`1${'0'.repeat(n)}\`)`在
dev 与`apps/web/dist`（08:55 重建）中均无 `Math.pow(BigInt`；成本页正常渲染。
- `quota-limit-modal.tsx` 关闭态不再调用 `form.resetFields()`；`E2E-M04-05` 与
  `pnpm -w a11y` 的配额段无 `useForm` 警告。

## 测试侧已完成的修正（非放宽）

- 成本页显式选择平台 `TENANT` / 租户 `USER` 维度后再断言（不依赖会话恢复时序）。
- 平台 MODEL 桶金额按 seed 校正（主模型 acme 0.0042、备用模型 beta 0.008）。
- 未定价/未分桶分两次 UI 重载断言：未定价单列 → 转未分桶后金额合计不变。
- 明细面板按 `假模型-未定价示例` 过滤，避免命中已折叠的历史面板。
- B8 `PUT` 配额响应缺 `used` 的 `patchStubQuotaSaveUsage` 保留，待 B8 修复后移除（P2）。
