package com.riccon.api.cost.domain;

import com.riccon.api.error.ErrorCodes;
import com.riccon.api.error.RicconException;
import org.springframework.http.HttpStatus;

/**
 * 成本查询参数非法（时间范围、维度或分页）。
 *
 * <p>使用稳定错误码 {@link ErrorCodes#COST_INVALID_REQUEST}，与配额参数错误区分；
 * 详情只描述规则，不回显用户输入或内部 SQL。
 */
public class CostInvalidRequestException extends RicconException {

    /**
     * 创建参数错误异常。
     *
     * @param detail 服务端可读原因
     */
    public CostInvalidRequestException(String detail) {
        super(HttpStatus.BAD_REQUEST, ErrorCodes.COST_INVALID_REQUEST, "Invalid request", detail);
    }
}
