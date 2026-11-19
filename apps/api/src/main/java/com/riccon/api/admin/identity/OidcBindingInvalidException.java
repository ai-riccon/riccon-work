package com.riccon.api.admin.identity;

import com.riccon.api.error.ErrorCodes;
import com.riccon.api.error.RicconException;
import org.springframework.http.HttpStatus;

/**
 * OIDC 账号开通/绑定请求非法。
 *
 * <p>用于 issuer 与租户配置不一致、OIDC/LOCAL 分支字段矛盾、subject 非法以及平台保留租户拒绝绑定
 * （ADR-0019 第 3/7 节）。对外错误码固定 {@code RICCON-OIDC-400}；消息只说明规则，不回显 subject。
 */
public class OidcBindingInvalidException extends RicconException {

    /**
     * 创建请求非法异常。
     *
     * @param detail 服务端可读原因，禁止包含 subject 原文或密钥
     */
    public OidcBindingInvalidException(String detail) {
        super(HttpStatus.BAD_REQUEST, ErrorCodes.OIDC_INVALID_REQUEST, "Invalid OIDC request", detail);
    }
}
