package com.riccon.api.admin.model.credential.domain;

import com.riccon.api.error.ErrorCodes;
import com.riccon.api.error.RicconException;
import org.springframework.http.HttpStatus;

/**
 * 模型凭据冲突（409）。
 *
 * <p>用于 fingerprint 重复与并发轮换/状态冲突；响应只暴露稳定原因码与脱敏说明，
 * 不含 apiKey、fingerprint 原文、密文或 provider 地址（ADR-0021 §6.7/§7.3）。
 */
public class ModelCredentialConflictException extends RicconException {

    /** 稳定原因码。 */
    private final String reason;

    /**
     * 创建 409 冲突。
     *
     * @param reason 稳定原因码
     * @param detail 脱敏说明
     */
    public ModelCredentialConflictException(String reason, String detail) {
        super(HttpStatus.CONFLICT, ErrorCodes.MODEL_CONFLICT, "Conflict", detail);
        this.reason = reason;
    }

    /**
     * 返回稳定原因码。
     *
     * @return 原因码
     */
    public String reason() {
        return reason;
    }
}
