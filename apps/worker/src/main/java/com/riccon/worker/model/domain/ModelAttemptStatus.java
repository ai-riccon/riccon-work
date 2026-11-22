package com.riccon.worker.model.domain;

/**
 * 单次上游尝试状态（与 `model_invocation_attempt.status` 的 CHECK 一致）。
 */
public enum ModelAttemptStatus {

    /** 已开始，尚未结束。 */
    STARTED,

    /** 成功。 */
    SUCCEEDED,

    /** 失败（含首字后中断）。 */
    FAILED,

    /** 用户取消或客户端断开。 */
    CANCELLED
}
