package com.riccon.api.quota.domain;

import com.riccon.api.error.ErrorCodes;
import com.riccon.api.error.RicconException;
import org.springframework.http.HttpStatus;

/**
 * 配额存储不可用；fail-closed 拒绝新 run，返回 {@code RICCON-QUOTA-503}。
 *
 * <p>配额表读取/写入失败（DB 超时、表缺失、死锁或锁等待）都映射到本异常。选择拒绝而不是放行，是因为
 * 放行会让成本失控且事后无法归因；在途 run 不受影响（ADR-0022 第 1.7 节）。
 */
public class QuotaUnavailableException extends RicconException {

    /** 使用固定中文提示创建异常，不暴露 SQL、主机或连接信息。 */
    public QuotaUnavailableException() {
        super(
                HttpStatus.SERVICE_UNAVAILABLE,
                ErrorCodes.QUOTA_UNAVAILABLE,
                "Quota service unavailable",
                "用量服务暂不可用，请稍后重试。");
    }
}
