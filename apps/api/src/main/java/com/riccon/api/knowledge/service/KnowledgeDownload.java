package com.riccon.api.knowledge.service;

import com.riccon.api.file.storage.StorageStream;
import java.io.IOException;

/**
 * 知识库下载源。
 *
 * <p>只包含响应头所需的展示信息与流句柄；对象 key、桶名和存储端点不得进入本对象或响应。
 *
 * @param filename 下载文件名（文档标题）
 * @param contentType 响应 MIME
 * @param contentLength 字节数
 * @param stream 对象流句柄
 */
public record KnowledgeDownload(String filename, String contentType, long contentLength, StorageStream stream)
        implements AutoCloseable {

    /** 关闭对象流。 */
    @Override
    public void close() throws IOException {
        stream.close();
    }
}
