package com.riccon.api.file.storage;

import java.io.IOException;
import java.io.InputStream;

/**
 * 对象存储流式读取句柄。
 *
 * <p>调用方必须在 {@code try-with-resources} 中使用并关闭，避免连接泄漏。只暴露正文流与存储侧元数据，
 * 不暴露对象 key、桶名或存储端点；ACL 与下载响应头由业务层负责。
 */
public interface StorageStream extends AutoCloseable {

    /**
     * 返回对象正文流。
     *
     * @return 输入流
     */
    InputStream inputStream();

    /**
     * 返回存储侧 MIME 类型；可为空。
     *
     * @return MIME
     */
    String contentType();

    /**
     * 返回对象字节数；未知时为空。
     *
     * @return 字节数
     */
    Long contentLength();

    /** 关闭底层连接。 */
    @Override
    void close() throws IOException;
}
