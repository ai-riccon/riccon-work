import { describe, expect, it } from 'vitest';
import { duplicateWorkflowName, MAX_WORKFLOW_NAME } from './limits';

/**
 * 客户端上限与副本命名测试。
 *
 * 关闭 REV-TASK-0230-001 F-002 的“另存副本名 200 上限”：原名允许 200 字符，
 * 直接追加“ 副本”会超过契约上限并导致必然 400。
 */

describe('duplicateWorkflowName', () => {
  it('短名称直接追加副本后缀', () => {
    expect(duplicateWorkflowName('市场调研', '忽略的详情名')).toBe('市场调研 副本');
  });

  it('空白名称回退详情名，再回退固定名称', () => {
    expect(duplicateWorkflowName('   ', '详情流程')).toBe('详情流程 副本');
    expect(duplicateWorkflowName('', null)).toBe('流程 副本');
  });

  it('200 字符名称截断后仍不超过契约上限并保留后缀', () => {
    const result = duplicateWorkflowName('a'.repeat(MAX_WORKFLOW_NAME), '详情');
    expect(result).toHaveLength(MAX_WORKFLOW_NAME);
    expect(result.endsWith(' 副本')).toBe(true);
    expect(result.startsWith('a'.repeat(3))).toBe(true);
  });

  it('按字符截断 emoji，不产生半个代理对', () => {
    const result = duplicateWorkflowName('😀'.repeat(MAX_WORKFLOW_NAME), null);
    expect([...result]).toHaveLength(MAX_WORKFLOW_NAME);
    expect(result.endsWith(' 副本')).toBe(true);
    expect(result.includes('\uFFFD')).toBe(false);
  });
});
