# TASK-0114 阻塞诊断（2026-11-21T08:38+08:00）

> 非 EV。记录 B11b 在编写 `e2e/tests/m04-quota-cost.spec.ts` 时发现的 B9b 页面缺陷；
> 修复后需重新运行合同桩 E2E、`pnpm -w a11y` 与真实栈定向 E2E。

## 复现命令

```bash
pnpm exec playwright test --config e2e/playwright.config.ts e2e/tests/m04-quota-cost.spec.ts --reporter=line
```

## 缺陷 1（P0）：成本页在开发/生产构建中崩溃

- 现象：平台/租户成本页加载数据后整页进入 Umi 错误边界
  `Something went wrong. Cannot convert a BigInt value to a number`；
  控制台 `TypeError: Cannot convert a BigInt value to a number at Math.pow`。
- 根因：`apps/web/src/services/cost/format.ts:61` 使用 BigInt 幂运算
  `const divisor = 10n ** BigInt(safeScale);`。当前 Umi/webpack Babel 目标会把
  `**` 降级为 `Math.pow(...)`，而 `Math.pow` 不接受 BigInt 操作数。
- 构建产物证据：
  `apps/web/dist/p__admin__costs__index.async.js` 中存在
  `Math.pow(BigInt(10),BigInt(n))`（生产构建同样受影响，不只是 E2E dev）。
- 影响：AC-11 成本页在有任何已定价调用时不可用；`E2E-M04-06` 与 `E2E-M04-07b`
  的成本段无法通过。
- 建议修复（B9b 写入范围）：改为避免 `**` 运算符，例如
  `const divisor = BigInt(\`1${'0'.repeat(safeScale)}\`);`。

## 缺陷 2（P1）：配额页控制台警告使 E2E console 守卫失败

- 现象：访问 `/admin/quotas` 即出现
  `console.error: Warning: Instance created by useForm is not connected to any Form element.`
- 根因：`apps/web/src/pages/admin/quotas/quota-limit-modal.tsx` 的 effect 在
  `tenant === null`（Modal 关闭且 `destroyOnHidden` 未挂载 Form）时调用
  `form.resetFields()`。
- 影响：`e2e/tests/fixtures.ts` 的 console 守卫按项目规则不允许空 allowlist 之外的
  `console.error`，因此 `E2E-M04-05` 与 `E2E-M04-07b` 的配额段失败；B11b 不应通过
  放宽守卫来掩盖。
- 建议修复（B9b 写入范围）：`tenant === null` 分支只清空 error，不调用
  `form.resetFields()`；或为 Modal 增加 `forceRender`。

## 缺陷 3（P1，合同桩 B8）：保存配额响应缺少 used

- 现象：保存限额后平台行显示 `已用 NaN / 1,500`。
- 根因：`e2e/stub/server.mjs` 的 `PUT /api/v1/admin/tenants/{tenantId}/quotas`
  用 `{daily, monthly}` 覆盖 `state.m04.tenantQuotas`，响应缺少
  `QuotaWindowState.used`。
- 影响：合同桩不能真实呈现保存后的配额页；B11b 用例内已用 `patchStubQuotaSaveUsage`
  按契约补回 seed 计数作为临时夹具，B8 修正后可移除该补丁。
- 建议修复（B8 写入范围）：PUT 时保留原 `used`，或写入后按 `quotaWindows` 统一组装。

## 已完成的测试侧验证

- 在临时本地放开 `useForm` 警告（未提交）后，
  `E2E-M04-05` 通过（21.8s），覆盖平台/租户视图、限额保存、明确不限、五态、员工 403。
- 真实栈定向 `REAL_PLAYWRIGHT_GREP='E2E-M04-05' node scripts/real-stack/run.mjs --mode=full`
  通过（13.9s，见 `real-stack-quota-targeted-pass.log`）：真实 429 形状、`quota.exceeded`
  审计、`riccon_quota_rejections_total` 增长、租户只读页限额与 +08:00 重置时间。
- 真实栈定向 `REAL_PLAYWRIGHT_GREP='E2E-M04-06'` 在成本页 UI 段失败（见
  `real-stack-cost-targeted-fail.log`）：生产构建同样出现
  `Cannot convert a BigInt value to a number`，Provider/聚合 API 断言此前已通过。
- `E2E-M04-07b` 在路由/key/配额键盘与 axe 段通过，随后在成本页因缺陷 1 崩溃。
- `E2E-M04-06` 在成本页首屏渲染时因缺陷 1 崩溃。
- 修复缺陷 1/2 后应重新取证；临时 allowlist 与 `patchStubQuotaSaveUsage` 的取舍见回执。
