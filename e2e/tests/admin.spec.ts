import { expect, test } from '@playwright/test';
import { expectNoSeriousA11yViolations } from './a11y';

/**
 * 管理端 /admin 冒烟：验证 ProLayout 外壳、菜单与占位页面可渲染。
 *
 * /admin 会重定向到 /admin/overview，因此直接断言总览页标题与菜单项。
 */
test.describe('管理端布局', () => {
  test('渲染 ProLayout 与占位菜单且无严重可访问性问题 @a11y', async ({ page }) => {
    await page.goto('/admin');

    await expect(page).toHaveURL(/\/admin\/overview$/);
    await expect(page.getByRole('main')).toContainText('管理总览');
    await expect(page.getByText('系统设置')).toBeVisible();
    await expect(page.getByText('骨架阶段提示')).toBeVisible();

    await expectNoSeriousA11yViolations(page);
  });
});
