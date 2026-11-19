import { expect, type Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
import path from 'node:path';

/** 真实栈 seed 输出；由 `scripts/real-stack/run.mjs` 在启动 Web 前写入。 */
export interface RealSeed {
  apiBase: string;
  tenants: { alpha: string; beta: string };
  users: {
    platformAdmin: string;
    alphaAdmin: string;
    alphaAdmin2: string;
    alphaMember: string;
    betaMember: string;
  };
  userIds: {
    alphaAdmin: string;
    alphaAdmin2: string;
    alphaMember: string;
    betaMember: string;
  };
  passwords: {
    alphaAdmin: string;
    alphaAdmin2: string;
    alphaMember: string;
    betaMember: string;
  };
  workspaces: { alpha: { id: string; name: string }; beta: { id: string; name: string } };
  catalog: { mcpToolId: string };
}

/**
 * 读取真实栈 seed。
 */
export function readSeed(): RealSeed {
  const file =
    process.env.REAL_SEED_OUT ??
    path.resolve(__dirname, '../../../.riccon/evidence/TASK-0024/real-stack-seed.json');
  return JSON.parse(readFileSync(file, 'utf8'));
}

/**
 * 通过真实登录表单进入工作空间；不预置 Cookie。
 *
 * @param page Playwright 页面
 * @param username 员工账号
 * @param password 密码
 */
export async function login(
  page: Page,
  username = 'alpha-member',
  password = 'alpha-member-password-12',
): Promise<RealSeed> {
  const seed = readSeed();
  await page.goto('/login');
  await page.getByPlaceholder('例如 acme').fill(seed.tenants.alpha);
  await page.getByPlaceholder('请输入账号').fill(username);
  await page.getByPlaceholder('请输入密码').fill(password);
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect(page).toHaveURL(/\/chat$/);
  await expect(page.getByRole('heading', { name: 'Riccon 工作空间' })).toBeVisible();
  await expect(page.getByText('Alpha 演示企业')).toBeVisible();
  return seed;
}

/**
 * 等待 J1 回答正文出现；返回从调用到正文可见的毫秒数。
 *
 * @param page Playwright 页面
 */
export async function waitForJ1Answer(page: Page): Promise<number> {
  const start = Date.now();
  await expect(page.getByText('市场规模与增长', { exact: false }).first()).toBeVisible({
    timeout: 60_000,
  });
  return Date.now() - start;
}
