// @vitest-environment jsdom
import { render, screen } from '@testing-library/react';
import { Form } from 'antd';
import { describe, expect, it } from 'vitest';
import { withProviders } from '@/test/render';
import {
  AuditFilterFields,
  OUTCOME_OPTIONS,
  TARGET_TYPE_OPTIONS,
  validateAuditRange,
} from './filter-fields';

/** 把毫秒时间戳包装成 DayjsLike。 */
function day(ms: number) {
  return { toDate: () => new Date(ms) };
}

describe('审计过滤字段', () => {
  it('每个控件都有可访问名称，动作下拉来自策略', () => {
    render(
      withProviders(
        <Form layout="vertical">
          <AuditFilterFields actions={['file.uploaded', 'audit.queried']} />
        </Form>,
      ),
    );

    expect(screen.getByLabelText('开始时间')).toBeInTheDocument();
    expect(screen.getByLabelText('结束时间')).toBeInTheDocument();
    expect(screen.getByLabelText('动作')).toBeInTheDocument();
    expect(screen.getByLabelText('操作者 ID')).toBeInTheDocument();
    expect(screen.getByLabelText('对象类型')).toBeInTheDocument();
    expect(screen.getByLabelText('对象 ID')).toBeInTheDocument();
    expect(screen.getByLabelText('结果')).toBeInTheDocument();
  });

  it('对象类型与结果选项覆盖契约枚举的中文文案', () => {
    expect(TARGET_TYPE_OPTIONS.map((item) => item.value)).toEqual(
      expect.arrayContaining([
        'AUDIT',
        'AUDIT_EXPORT',
        'MCP_TOOL',
        'FILE',
        'MODEL_ROUTING',
        'MODEL_CREDENTIAL',
        'QUOTA',
      ]),
    );
    expect(TARGET_TYPE_OPTIONS.find((item) => item.value === 'AUDIT_EXPORT')?.label).toBe(
      '审计导出',
    );
    expect(TARGET_TYPE_OPTIONS.find((item) => item.value === 'MODEL_ROUTING')?.label).toBe(
      '模型路由',
    );
    expect(TARGET_TYPE_OPTIONS.find((item) => item.value === 'MODEL_CREDENTIAL')?.label).toBe(
      '模型 key',
    );
    expect(TARGET_TYPE_OPTIONS.find((item) => item.value === 'QUOTA')?.label).toBe('配额');
    expect(OUTCOME_OPTIONS.map((item) => item.label)).toEqual(['成功', '失败', '拒绝']);
  });

  it('时间范围校验拒绝倒序与超过 180 天', () => {
    const start = Date.UTC(2026, 10, 1);
    const end = Date.UTC(2026, 10, 2);
    expect(validateAuditRange(day(start), day(end))).toBeNull();
    expect(validateAuditRange(day(end), day(start))).toContain('结束时间不能早于开始时间');
    expect(validateAuditRange(day(start), day(start + 181 * 24 * 60 * 60 * 1000))).toContain(
      '不能超过 180 天',
    );
    expect(validateAuditRange(undefined, day(end))).toBeNull();
  });
});
