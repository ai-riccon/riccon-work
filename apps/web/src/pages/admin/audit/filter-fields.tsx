import { DatePicker, Form, Input, Select } from 'antd';
import type { AuditTargetType, AuditOutcome } from '@/services/audit/types';
import type { DayjsLike } from '@/services/audit/format';
import { targetTypeLabel } from '@/services/audit/format';

/** 最大可查跨度（天）；与 ADR-0020 和 OpenAPI `maxRangeDays` 一致。 */
export const AUDIT_MAX_RANGE_DAYS = 180;

/** 事件结果选项。 */
export const OUTCOME_OPTIONS: Array<{ value: AuditOutcome; label: string }> = [
  { value: 'SUCCESS', label: '成功' },
  { value: 'FAILURE', label: '失败' },
  { value: 'DENIED', label: '拒绝' },
];

/** 对象类型选项；顺序与 OpenAPI 枚举一致。 */
export const TARGET_TYPE_OPTIONS: Array<{ value: AuditTargetType; label: string }> = (
  [
    'SESSION',
    'WORKSPACE',
    'DEPARTMENT',
    'FILE',
    'CONVERSATION',
    'KNOWLEDGE_DOCUMENT',
    'RESEARCH_TASK',
    'RUN',
    'ARTIFACT',
    'MODEL',
    'MODEL_ROUTING',
    'MODEL_CREDENTIAL',
    'QUOTA',
    'MCP_TOOL',
    'USER',
    'ROLE',
    'TENANT',
    'AUDIT',
    'AUDIT_EXPORT',
  ] as AuditTargetType[]
).map((value) => ({ value, label: targetTypeLabel(value) }));

/** 审计过滤字段参数。 */
export interface AuditFilterFieldsProps {
  /** 策略返回的动作名列表，用于下拉。 */
  actions: string[];
  /** 加载中禁用输入，避免重复提交。 */
  disabled?: boolean;
}

/**
 * 审计过滤表单字段。
 *
 * 组件只渲染字段，不拥有 Form 实例；审计页和导出申请弹窗共用同一套字段，
 * 保证查询条件与导出条件口径一致。时间由 DatePicker 选择，提交时统一转换为
 * 北京时间 `+08:00` 偏移。
 *
 * @param props 动作列表与禁用状态
 * @returns 过滤字段集合
 */
export function AuditFilterFields({ actions, disabled = false }: AuditFilterFieldsProps) {
  return (
    <>
      <Form.Item label="开始时间" name="occurredFrom">
        <DatePicker
          aria-label="开始时间"
          allowClear
          disabled={disabled}
          format="YYYY-MM-DD HH:mm:ss"
          placeholder="选择开始时间"
          showTime={{ format: 'HH:mm:ss' }}
          style={{ width: '100%' }}
        />
      </Form.Item>

      <Form.Item
        dependencies={['occurredFrom']}
        label="结束时间"
        name="occurredTo"
        rules={[
          ({ getFieldValue }) => ({
            validator: async (_rule, value: DayjsLike | null | undefined) => {
              const message = validateAuditRange(
                getFieldValue('occurredFrom') as DayjsLike | null | undefined,
                value,
              );
              if (message) {
                throw new Error(message);
              }
            },
          }),
        ]}
      >
        <DatePicker
          aria-label="结束时间"
          allowClear
          disabled={disabled}
          format="YYYY-MM-DD HH:mm:ss"
          placeholder="选择结束时间"
          showTime={{ format: 'HH:mm:ss' }}
          style={{ width: '100%' }}
        />
      </Form.Item>

      <Form.Item label="动作" name="action">
        <Select
          allowClear
          aria-label="动作"
          disabled={disabled}
          optionFilterProp="value"
          options={actions.map((action) => ({ value: action, label: action }))}
          placeholder="全部动作"
          showSearch
        />
      </Form.Item>

      <Form.Item
        label="操作者 ID"
        name="actorId"
        rules={[
          {
            pattern: /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
            message: '操作者 ID 必须是 UUID。',
          },
        ]}
      >
        <Input
          allowClear
          aria-label="操作者 ID"
          disabled={disabled}
          placeholder="例如 0198f0c2-…"
        />
      </Form.Item>

      <Form.Item label="对象类型" name="targetType">
        <Select
          allowClear
          aria-label="对象类型"
          disabled={disabled}
          options={TARGET_TYPE_OPTIONS}
          placeholder="全部对象"
          showSearch
          optionFilterProp="label"
        />
      </Form.Item>

      <Form.Item
        label="对象 ID"
        name="targetId"
        rules={[{ max: 64, message: '对象 ID 不能超过 64 个字符。' }]}
      >
        <Input
          allowClear
          aria-label="对象 ID"
          disabled={disabled}
          placeholder="文件、会话或导出请求 ID"
        />
      </Form.Item>

      <Form.Item label="结果" name="outcome">
        <Select
          allowClear
          aria-label="结果"
          disabled={disabled}
          options={OUTCOME_OPTIONS}
          placeholder="全部结果"
        />
      </Form.Item>
    </>
  );
}

/**
 * 判断结束时间是否早于开始时间。
 *
 * 表单校验和页面提交前的兜底共用该函数；返回值用于生成中文错误文案。
 *
 * @param from 开始时间
 * @param to 结束时间
 * @returns 错误文案；合法时返回 null
 */
export function validateAuditRange(
  from: DayjsLike | null | undefined,
  to: DayjsLike | null | undefined,
): string | null {
  if (!from || !to) {
    return null;
  }
  const fromMs = from.toDate().getTime();
  const toMs = to.toDate().getTime();
  if (Number.isNaN(fromMs) || Number.isNaN(toMs)) {
    return '时间格式不正确。';
  }
  if (toMs < fromMs) {
    return '结束时间不能早于开始时间。';
  }
  if (toMs - fromMs > AUDIT_MAX_RANGE_DAYS * 24 * 60 * 60 * 1000) {
    return `可查范围不能超过 ${AUDIT_MAX_RANGE_DAYS} 天，请缩小时间范围或改用导出。`;
  }
  return null;
}
