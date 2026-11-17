package com.riccon.api.identity.domain;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明接口所需权限码。
 *
 * <p>由 {@code PermissionInterceptor} 在请求进入 controller 前执行：每次从数据库重新加载角色与权限，
 * 避免会话中携带过期权限。权限码必须引用 {@code PermissionCodes} 常量，禁止写字面量。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface RequirePermission {

    /**
     * 需要的权限码。
     *
     * @return 权限码
     */
    String value();
}
