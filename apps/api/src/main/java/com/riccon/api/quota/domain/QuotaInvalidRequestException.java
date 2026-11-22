package com.riccon.api.quota.domain;

import com.riccon.api.error.ErrorCodes;
import com.riccon.api.error.RicconException;
import org.springframework.http.HttpStatus;

/**
 * 配额请求非法：负数、缺键、未知键或非整数，返回 {@code RICCON-QUOTA-400}。
 *
 * <p>与通用 {@code RICCON-REQUEST-400} 区分，便于前端和运维按配额域定位问题（ADR-0022 第 3.3 节）。
 */
public class QuotaInvalidRequestException extends RicconException {

    /**
     * 创建配额请求异常。
     *
     * @param detail 服务端可读原因；不得包含请求原文或敏感数据
     */
    public QuotaInvalidRequestException(String detail) {
        super(HttpStatus.BAD_REQUEST, ErrorCodes.QUOTA_INVALID_REQUEST, "Invalid quota request", detail);
    }
}
