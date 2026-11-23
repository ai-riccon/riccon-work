package com.riccon.api.knowledge.service;

import com.riccon.api.error.RicconException;
import org.springframework.http.HttpStatus;

/**
 * 知识库请求不合法（{@code RICCON-KB-400}）。
 *
 * <p>用于可见范围与部门/名单组合非法、部门不存在或未启用、名单超过上限或用户不属于本租户等业务校验。
 */
public class KnowledgeBadRequestException extends RicconException {

    /**
     * 创建 400 业务异常。
     *
     * @param detail 脱敏说明，不含标题、正文或名单
     */
    public KnowledgeBadRequestException(String detail) {
        super(HttpStatus.BAD_REQUEST, "RICCON-KB-400", "Invalid knowledge request", detail);
    }
}
