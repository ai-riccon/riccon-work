package com.riccon.worker.model.service;

import com.riccon.worker.model.domain.FailureReason;
import com.riccon.worker.model.domain.ModelException;
import com.riccon.worker.model.domain.ModelFailureKind;

/**
 * 把底层错误映射为降级触发原因（ADR-0021 §5.1）。
 *
 * <p>物理分类优先；旧构造器创建的错误按稳定错误码回退，保证测试桩与历史调用点行为一致。
 */
final class ModelFailureClassifier {

    /** 工具类禁止实例化。 */
    private ModelFailureClassifier() {}

    /**
     * 分类一次首字前失败。
     *
     * @param error 模型错误
     * @return 触发原因
     */
    static FailureReason classify(ModelException error) {
        ModelFailureKind kind = error.failureKind();
        if (kind == ModelFailureKind.UNKNOWN) {
            kind = fromErrorCode(error.errorCode());
        }
        return switch (kind) {
            case TRANSPORT, SERVER_ERROR -> FailureReason.UNAVAILABLE;
            case TIMEOUT -> FailureReason.TIMEOUT;
            case RATE_LIMITED -> FailureReason.RATE_LIMITED;
            case UNAUTHORIZED -> FailureReason.UNAUTHORIZED;
            case FORBIDDEN -> FailureReason.FORBIDDEN;
            case INVALID_RESPONSE -> FailureReason.INVALID_RESPONSE;
            case CONFIGURATION -> FailureReason.CONFIGURATION;
            case UNKNOWN -> FailureReason.INVALID_RESPONSE;
        };
    }

    /**
     * 按错误码回退分类。
     *
     * @param errorCode 稳定错误码
     * @return 物理分类
     */
    private static ModelFailureKind fromErrorCode(String errorCode) {
        return switch (errorCode) {
            case ModelException.UNAUTHORIZED -> ModelFailureKind.UNAUTHORIZED;
            case ModelException.FORBIDDEN -> ModelFailureKind.FORBIDDEN;
            case ModelException.RATE_LIMITED -> ModelFailureKind.RATE_LIMITED;
            case ModelException.TIMEOUT -> ModelFailureKind.TIMEOUT;
            case ModelException.UNAVAILABLE -> ModelFailureKind.TRANSPORT;
            case ModelException.CONFIGURATION -> ModelFailureKind.CONFIGURATION;
            default -> ModelFailureKind.INVALID_RESPONSE;
        };
    }

    /**
     * 判断是否允许同一 key 显式退避重试一次。
     *
     * @param error 模型错误
     * @return 5xx 或 429 返回 true
     */
    static boolean retrySameKeyAllowed(ModelException error) {
        ModelFailureKind kind = error.failureKind();
        if (kind == ModelFailureKind.UNKNOWN) {
            kind = fromErrorCode(error.errorCode());
        }
        return kind == ModelFailureKind.SERVER_ERROR || kind == ModelFailureKind.RATE_LIMITED;
    }
}
