package com.riccon.platform.domain.workflow;

/**
 * 运行结果保存状态。
 *
 * <p>{@link #NONE}：尚未结束或没有最终结果；{@link #SAVED}：已写入 {@code result_json}；
 * {@link #OVER_LIMIT}：结果超过部署上限，运行以 {@code WF_RESULT_LIMIT} 失败且不静默截断
 * （ADR-0024 第 3.4 节，AC-17）。
 */
public enum WorkflowResultStatus {
    /** 未产生最终结果。 */
    NONE,
    /** 结果已保存。 */
    SAVED,
    /** 结果超限未保存。 */
    OVER_LIMIT
}
