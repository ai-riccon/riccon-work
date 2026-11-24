import { describe, expect, it } from 'vitest';
import {
  hasWorkflowPermission,
  runScopeLabel,
  WORKFLOW_PERMISSIONS,
  workflowDisplayStatus,
  workflowVersionSummary,
} from './format';

/** 列表/编辑器共享展示规则测试。 */
describe('编排展示规则', () => {
  it('按权限码判断能力', () => {
    expect(hasWorkflowPermission(['workflow.edit'], WORKFLOW_PERMISSIONS.edit)).toBe(true);
    expect(hasWorkflowPermission(['workflow.read'], WORKFLOW_PERMISSIONS.edit)).toBe(false);
    expect(hasWorkflowPermission(undefined, WORKFLOW_PERMISSIONS.read)).toBe(false);
  });

  it('流程状态按停用/草稿/未发布修改/已发布派生', () => {
    expect(
      workflowDisplayStatus({
        status: 'DISABLED',
        publishedVersionNo: 3,
        hasUnpublishedChanges: true,
      }).label,
    ).toBe('已停用');
    expect(
      workflowDisplayStatus({
        status: 'ACTIVE',
        publishedVersionNo: null,
        hasUnpublishedChanges: true,
      }),
    ).toEqual({ label: '草稿', color: 'blue' });
    expect(
      workflowDisplayStatus({
        status: 'ACTIVE',
        publishedVersionNo: 2,
        hasUnpublishedChanges: true,
      }).label,
    ).toBe('有未发布修改');
    expect(
      workflowDisplayStatus({
        status: 'ACTIVE',
        publishedVersionNo: 2,
        hasUnpublishedChanges: false,
      }).label,
    ).toBe('已发布');
  });

  it('版本摘要区分未发布与已发布', () => {
    expect(workflowVersionSummary({ draftVersion: 1, publishedVersionNo: null })).toBe(
      '草稿 v1 · 尚未发布',
    );
    expect(workflowVersionSummary({ draftVersion: 3, publishedVersionNo: 2 })).toBe(
      '草稿 v3 · 发布 v2',
    );
  });

  it('运行范围显示中文', () => {
    expect(runScopeLabel('ONLY_SELF')).toBe('仅自己');
    expect(runScopeLabel('DEPARTMENT')).toBe('本部门');
    expect(runScopeLabel('TENANT')).toBe('本租户');
  });
});
