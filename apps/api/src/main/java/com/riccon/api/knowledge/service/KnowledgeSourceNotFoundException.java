package com.riccon.api.knowledge.service;

import com.riccon.api.error.RicconException;
import org.springframework.http.HttpStatus;

/**
 * ACL 已通过但文档没有可下载的源文件（{@code RICCON-KB-404}）。
 *
 * <p>与 {@code RICCON-TENANT-404} 严格区分：不可见/跨租户仍返回统一 404，本错误只在文档已可见之后出现，
 * 不用于掩盖对象存在性（ADR-0023 第 5.3、5.4 节）。
 */
public class KnowledgeSourceNotFoundException extends RicconException {

    /** 创建源文件不存在异常。 */
    public KnowledgeSourceNotFoundException() {
        super(
                HttpStatus.NOT_FOUND,
                "RICCON-KB-404",
                "Knowledge source not found",
                "knowledge document has no source file");
    }
}
