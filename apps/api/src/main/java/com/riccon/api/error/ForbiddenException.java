package com.riccon.api.error;

import org.springframework.http.HttpStatus;

/** 已认证但缺少所需权限，或跨租户访问被拒绝。 */
public class ForbiddenException extends RicconException {

    /**
     * 创建权限拒绝异常。
     *
     * @param permissionCode 需要的权限码；可为空（例如租户伪造拒绝）
     */
    public ForbiddenException(String permissionCode) {
        super(HttpStatus.FORBIDDEN, ErrorCodes.AUTH_FORBIDDEN, "Forbidden", "permission denied: " + permissionCode);
    }
}
