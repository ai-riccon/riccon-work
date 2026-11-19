package com.riccon.api.admin.identity;

import com.riccon.api.error.ErrorCodes;
import com.riccon.api.error.RicconException;
import org.springframework.http.HttpStatus;

/**
 * OIDC 身份绑定冲突。
 *
 * <p>覆盖三种状态冲突（ADR-0019 第 3/7 节）：同一身份已绑定其他账号、账号已绑定其他身份、唯一键并发冲突。
 * 对外错误码固定 {@code RICCON-OIDC-409}；消息不包含 issuer/subject 原文。
 */
public class OidcBindingConflictException extends RicconException {

    /**
     * 创建绑定冲突异常。
     *
     * @param detail 服务端可读原因，禁止包含 subject 原文
     */
    public OidcBindingConflictException(String detail) {
        super(HttpStatus.CONFLICT, ErrorCodes.OIDC_BINDING_CONFLICT, "OIDC identity conflict", detail);
    }
}
