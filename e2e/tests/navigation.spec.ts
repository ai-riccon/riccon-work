import { expect, test } from './fixtures';

/**
 * 路由跳转冒烟：覆盖根路径重定向与管理端嵌套路由。
 *
 * 该用例不带 @a11y 标签，用于区分 `pnpm -w a11y` 与完整 `pnpm -w e2e` 的执行范围。
 */
test('根路径重定向到 /chat，管理端菜单可切换页面', async ({ page }) => {
  await page.goto('/');
  await expect(page).toHaveURL(/\/chat$/);

  await page.getByRole('link', { name: '管理端' }).click();
  await expect(page).toHaveURL(/\/admin\/overview$/);

  await page.getByRole('link', { name: /系统设置/ }).click();
  await expect(page).toHaveURL(/\/admin\/settings$/);
  await expect(page.getByRole('main')).toContainText('系统设置');
});
