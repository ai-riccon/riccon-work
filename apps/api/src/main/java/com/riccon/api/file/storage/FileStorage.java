package com.riccon.api.file.storage;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 文件对象存储端口。
 *
 * <p>API 只依赖 S3 协议语义；实现必须校验 key 的租户前缀，禁止调用方传入任意 key。上传失败向上抛出，
 * 由业务事务回滚并通过补偿删除对象。
 */
public interface FileStorage {

    /**
     * 写入对象。
     *
     * @param tenantId 租户代码
     * @param key 对象 key，必须带 {@code tenants/{tenantId}/} 前缀
     * @param source 本地文件
     * @param contentType canonical MIME
     */
    void putObject(String tenantId, String key, Path source, String contentType);

    /**
     * 签发短期下载地址。
     *
     * @param tenantId 租户代码
     * @param key 对象 key
     * @param ttl 有效期，不得超过 300 秒
     * @return 预签名 URL
     */
    URI presignGet(String tenantId, String key, Duration ttl);

    /**
     * 读取对象正文，用于服务端同源代理。
     *
     * <p>正文整体读入内存，调用方必须传入较小的上限（当前产物正文为 2 MiB），避免把大对象读入堆。
     * 实现必须校验 key 的租户前缀；对象大于 {@code maxBytes} 时抛出
     * {@link StorageObjectTooLargeException}，读取失败抛出 {@link StorageReadException}。
     *
     * @param tenantId 租户代码
     * @param key 对象 key，必须带 {@code tenants/{tenantId}/} 前缀
     * @param maxBytes 允许读取的最大字节数
     * @return 对象正文与存储侧 MIME 类型
     */
    StorageObject readObject(String tenantId, String key, long maxBytes);

    /**
     * 以流式句柄打开对象，用于知识库源文件下载代理。
     *
     * <p>实现必须校验 key 的租户前缀；读取失败抛出 {@link StorageReadException}。调用方负责关闭返回的
     * {@link StorageStream}，禁止把对象 key 或存储端点返回给客户端。
     *
     * @param tenantId 租户代码
     * @param key 对象 key，必须带 {@code tenants/{tenantId}/} 前缀
     * @return 流式句柄
     */
    StorageStream openObject(String tenantId, String key);

    /**
     * 尽力删除对象；用于事务回滚补偿，失败只记录日志，不覆盖原始业务异常。
     *
     * @param tenantId 租户代码
     * @param key 对象 key
     */
    void deleteQuietly(String tenantId, String key);
}
