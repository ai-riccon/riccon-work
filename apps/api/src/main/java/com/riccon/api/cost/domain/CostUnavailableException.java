package com.riccon.api.cost.domain;

import com.riccon.api.error.ErrorCodes;
import com.riccon.api.error.RicconException;
import org.springframework.http.HttpStatus;

/**
 * 成本存储/查询不可用：数据库超时或查询失败。
 *
 * <p>按 ADR-0022 §2.5 fail-closed：超时返回 503 {@code RICCON-COST-503}，不返回部分结果；
 * 不降级为全表或无界查询。
 */
public class CostUnavailableException extends RicconException {

    /** 创建成本查询不可用异常。 */
    public CostUnavailableException() {
        super(
                HttpStatus.SERVICE_UNAVAILABLE,
                ErrorCodes.COST_UNAVAILABLE,
                "Service Unavailable",
                "cost report is temporarily unavailable");
    }
}
