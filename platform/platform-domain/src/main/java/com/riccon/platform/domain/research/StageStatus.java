package com.riccon.platform.domain.research;

/**
 * 单个阶段的一次尝试状态。
 *
 * <p>重试会新建一条同阶段、递增尝试号的记录；已完成阶段不重复写入，保证时间线可核对且不产生重复产物。
 */
public enum StageStatus {
    /** 正在执行。 */
    RUNNING,
    /** 成功。 */
    SUCCEEDED,
    /** 失败；错误码区分可重试与不可重试。 */
    FAILED,
    /** 因前序失败或审批拒绝而跳过。 */
    SKIPPED
}
