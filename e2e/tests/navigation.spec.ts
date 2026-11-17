import { expect, test } from './fixtures';
import { login } from './session';

/**
 * 路由与跳转冒烟：未登录访问根路径必须先到登录页；登录后可访问管理端占位页面。
 *
 * 不再依赖员工端顶部的“管理端”链接，员工端只承载 J1 工作空间。
 */
test('根路径跳转登录，登录后可访问管理端', async ({ page }) => {
  await page.goto('/');
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByRole('heading', { name: '登录 Riccon' })).toBeVisible();

  await login(page);
  await expect(page).toHaveURL(/\/chat$/);

  await page.goto('/admin/overview');
  await expect(page.getByRole('main')).toContainText('管理总览');
  await page.getByRole('link', { name: /系统设置/ }).click();
  await expect(page).toHaveURL(/\/admin\/settings$/);
  await expect(page.getByRole('main')).toContainText('系统设置');
});
