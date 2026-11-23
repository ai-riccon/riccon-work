package com.riccon.api.knowledge.service;

import com.riccon.api.error.RicconException;
import org.springframework.http.HttpStatus;

/**
 * 知识库状态冲突（{@code RICCON-KB-409}）。
 *
 * <p>用于文档已软删除或正在索引时再次触发重索引；重复删除按幂等返回 204，不使用本错误。
 */
public class KnowledgeConflictException extends RicconException {

    /**
     * 创建 409 业务异常。
     *
     * @param detail 脱敏说明
     */
    public KnowledgeConflictException(String detail) {
        super(HttpStatus.CONFLICT, "RICCON-KB-409", "Knowledge document conflict", detail);
    }
}
