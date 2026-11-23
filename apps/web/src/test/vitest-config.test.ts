import { describe, expect, it } from 'vitest';
import config from '../../vitest.config';

/**
 * 合同覆盖率命令的并发上限回归。
 *
 * 根因（TASK-0196）：默认 worker 数在覆盖率插桩、jsdom 与 antd 重页面叠加时造成 CPU 争抢，
 * 少数用例超过 15s 被误判失败。固定 4 个 worker 后 `pnpm -w test -- --coverage` 可连续通过；
 * 本测试锁定该配置，防止后续无意中移除。
 */
describe('web 测试并发上限', () => {
  it('vitest maxWorkers 固定为 4，避免 coverage 合同命令 CPU 争抢超时', () => {
    expect((config as { test?: { maxWorkers?: number } }).test?.maxWorkers).toBe(4);
  });
});
