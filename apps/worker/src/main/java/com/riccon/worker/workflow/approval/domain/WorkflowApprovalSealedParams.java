package com.riccon.worker.workflow.approval.domain;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * 工具参数加密结果。
 *
 * <p>包含规范化参数 SHA-256、脱敏预览、密文与 key id；四者必须成对写入审批行。明文只在方法
 * 栈内短暂存在，不进入日志、异常消息或返回值（ADR-0017/ADR-0024 §4.7）。
 *
 * @param paramsSha256 规范化参数 SHA-256
 * @param preview 脱敏参数摘要
 * @param ciphertext 完整信封载荷
 * @param keyId 加密使用的 key id
 */
public record WorkflowApprovalSealedParams(
        String paramsSha256, Map<String, Object> preview, byte[] ciphertext, String keyId) {

    /** 校验必填字段并复制密文。 */
    public WorkflowApprovalSealedParams {
        Objects.requireNonNull(paramsSha256, "paramsSha256 must not be null");
        Objects.requireNonNull(preview, "preview must not be null");
        Objects.requireNonNull(ciphertext, "ciphertext must not be null");
        Objects.requireNonNull(keyId, "keyId must not be null");
        preview = Map.copyOf(preview);
        ciphertext = ciphertext.clone();
    }

    /**
     * 返回密文副本。
     *
     * @return 密文副本
     */
    @Override
    public byte[] ciphertext() {
        return Arrays.copyOf(ciphertext, ciphertext.length);
    }
}
