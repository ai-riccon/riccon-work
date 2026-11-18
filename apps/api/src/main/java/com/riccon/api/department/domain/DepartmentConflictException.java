package com.riccon.api.department.domain;

import com.riccon.api.error.ErrorCodes;
import com.riccon.api.error.RicconException;
import org.springframework.http.HttpStatus;

/**
 * 部门与成员关系冲突。
 *
 * <p>ADR-0018 第 7.3 节要求部门名重复、账号已在其他/本部门、移除当前负责人等状态冲突统一返回
 * {@code 409} 且错误码为 {@link ErrorCodes#RICCON_DEPT_409}；说明只描述稳定原因，不回显成员名单、
 * 邮箱或账号是否存在。
 */
public class DepartmentConflictException extends RicconException {

    /**
     * 创建部门冲突异常。
     *
     * @param detail 服务端可读原因；不得包含姓名、邮箱、成员名单或业务内容
     */
    public DepartmentConflictException(String detail) {
        super(HttpStatus.CONFLICT, ErrorCodes.RICCON_DEPT_409, "Department conflict", detail);
    }
}
