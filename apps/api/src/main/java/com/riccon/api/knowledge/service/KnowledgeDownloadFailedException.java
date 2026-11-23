package com.riccon.api.knowledge.service;

import com.riccon.api.error.RicconException;
import org.springframework.http.HttpStatus;

/**
 * 源文件读取失败（{@code RICCON-KB-502}）。
 *
 * <p>对象存储不可用或流读取失败时返回；响应不包含对象 key、桶名、预签名 URL 或存储端点。
 * 契约当前只声明 502 通用响应、未给出稳定码，本码由 M05-B3 收敛并在 M05 gate 前登记（REV-TASK-0165-001 F-003）。
 */
public class KnowledgeDownloadFailedException extends RicconException {

    /**
     * 创建 502 业务异常。
     *
     * @param detail 脱敏说明
     */
    public KnowledgeDownloadFailedException(String detail) {
        super(HttpStatus.BAD_GATEWAY, "RICCON-KB-502", "Knowledge download failed", detail);
    }
}
