package com.riccon.api.research.dto;

import com.riccon.api.error.ErrorCodes;

/**
 * 报告任务失败文案映射。
 *
 * <p>API 读取任务详情前会把当前运行状态投影回任务行，Worker 在 outbox 重试终止时写入的运行失败说明是
 * 内部摘要（例如 {@code research tool stage failed}），会覆盖 Worker 已写入任务的员工可见文案。
 * 全链不可用（{@link ErrorCodes#MODEL_UNAVAILABLE}）属于 AC-05 的稳定故障，读取时统一返回固定中文文案；
 * 其他错误码保留 Worker 已脱敏的说明，不改变既有语义。
 *
 * <p>固定文案与 Worker {@code ModelException.CHAIN_UNAVAILABLE_MESSAGE} 一致（ADR-0021 §5.3）。API 与
 * Worker 是并列部署物，不共享实现，只通过稳定错误码对齐。
 */
final class ResearchFailureText {

    /** AC-05 全链不可用统一文案。 */
    static final String MODEL_UNAVAILABLE_MESSAGE = "模型服务暂不可用，请稍后重试。";

    /** 工具类禁止实例化。 */
    private ResearchFailureText() {}

    /**
     * 解析员工可见的失败说明。
     *
     * @param failureCode 稳定错误码；可为空
     * @param storedMessage 任务行中已落库的脱敏说明；可为空
     * @return 全链不可用时为固定中文文案，否则原样返回已落库说明
     */
    static String message(String failureCode, String storedMessage) {
        return ErrorCodes.MODEL_UNAVAILABLE.equals(failureCode) ? MODEL_UNAVAILABLE_MESSAGE : storedMessage;
    }
}
