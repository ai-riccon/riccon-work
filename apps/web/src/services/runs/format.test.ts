import { describe, expect, it } from 'vitest';
import {
  formatTokenUsage,
  invocationStatusColor,
  invocationStatusLabel,
  modelPurposeLabel,
  runKindLabel,
  runStageLabel,
  runStatusColor,
  runStatusLabel,
} from './format';

describe('运行详情展示规则', () => {
  it('映射运行与调用状态文案及颜色', () => {
    expect(runStatusLabel('RUNNING')).toBe('运行中');
    expect(runStatusLabel('RETRY_WAIT')).toBe('等待重试');
    expect(runStatusColor('SUCCEEDED')).toBe('success');
    expect(runStatusColor('RETRY_WAIT')).toBe('warning');
    expect(runStatusColor('CANCELLED')).toBe('default');

    expect(invocationStatusLabel('FAILED')).toBe('失败');
    expect(invocationStatusColor('STARTED')).toBe('processing');
    expect(invocationStatusColor('CANCELLED')).toBe('default');
  });

  it('映射类别、用途与阶段，并对空阶段给出占位', () => {
    expect(runKindLabel('CHAT')).toBe('对话回答');
    expect(runKindLabel('RESEARCH_REPORT')).toBe('调研报告');
    expect(modelPurposeLabel('REPORT')).toBe('报告生成');
    expect(runStageLabel('APPROVAL')).toBe('审批');
    expect(runStageLabel(null)).toBe('—');
  });

  it('格式化 token，缺失值使用占位而不当作 0', () => {
    expect(formatTokenUsage(128, 96)).toBe('128 / 96');
    expect(formatTokenUsage(0, 0)).toBe('0 / 0');
    expect(formatTokenUsage(null, undefined)).toBe('— / —');
    expect(formatTokenUsage(128, null)).toBe('128 / —');
  });
});
