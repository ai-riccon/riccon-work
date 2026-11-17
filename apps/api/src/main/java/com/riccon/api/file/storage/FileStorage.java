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
     * 尽力删除对象；用于事务回滚补偿，失败只记录日志，不覆盖原始业务异常。
     *
     * @param tenantId 租户代码
     * @param key 对象 key
     */
    void deleteQuietly(String tenantId, String key);
}
