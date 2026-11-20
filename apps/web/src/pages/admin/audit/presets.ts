import { formatBeijingIso } from '@/services/audit/format';

/** 降级事件动作名；与平台 domain `AuditActions.MODEL_FAILOVER_TRIGGERED` 一致。 */
export const FAILOVER_PRESET_ACTION = 'model.failover.triggered';

/** 降级事件预设的中文名称。 */
export const FAILOVER_PRESET_LABEL = '降级事件（最近 7 天）';

/** 前端预设生成的时间窗口；仅用于查询条件，不改变服务端默认窗口。 */
export interface FailoverPreset {
  /** 固定为降级事件动作。 */
  action: string;
  /** 最近 7 天起点，北京时间带偏移。 */
  occurredFrom: string;
  /** 当前时间，北京时间带偏移。 */
  occurredTo: string;
}

/**
 * 生成“降级事件最近 7 天”查询窗口。
 *
 * 产品口径（PRODUCT §M04）：降级事件默认展示最近 7 天，可继续筛选至 180 天。
 * 这里只在前端生成查询条件；服务端默认窗口与保留策略不改。
 *
 * @param now 当前时间，测试可注入固定时钟
 * @returns 带 `+08:00` 偏移的动作与时间范围
 */
export function buildFailoverPreset(now: Date): FailoverPreset {
  const from = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000);
  return {
    action: FAILOVER_PRESET_ACTION,
    occurredFrom: formatBeijingIso(from),
    occurredTo: formatBeijingIso(now),
  };
}
