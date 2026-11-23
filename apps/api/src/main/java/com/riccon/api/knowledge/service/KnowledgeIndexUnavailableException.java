package com.riccon.api.knowledge.service;

import com.riccon.api.error.RicconException;
import org.springframework.http.HttpStatus;

/**
 * 知识库索引队列或索引服务不可用（{@code RICCON-KB-503}）。
 *
 * <p>API 只写 outbox 事件并返回 202；队列写入失败时不得返回“部分受理”，必须整体失败并回滚。
 */
public class KnowledgeIndexUnavailableException extends RicconException {

    /**
     * 创建 503 业务异常。
     *
     * @param detail 脱敏说明
     */
    public KnowledgeIndexUnavailableException(String detail) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "RICCON-KB-503", "Knowledge index unavailable", detail);
    }
}
