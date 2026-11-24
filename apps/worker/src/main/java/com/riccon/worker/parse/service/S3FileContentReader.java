package com.riccon.worker.parse.service;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.config.ParseProperties;
import com.riccon.worker.config.StorageProperties;
import com.riccon.worker.connector.domain.ConnectorContentTypes;
import com.riccon.worker.parse.domain.FileRecord;
import com.riccon.worker.parse.domain.ParseException;
import com.riccon.worker.run.domain.WorkerErrorCodes;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * S3 对象读取器。
 *
 * <p>对象 key 必须带当前租户前缀 {@code tenants/{tenantId}/}；不匹配时按不存在处理，
 * 不区分“其他租户的对象”和“对象不存在”（ADR-0009 TEN-013）。文件大小在下载前检查，
 * 避免大文件把 Worker 内存打满。
 */
@Component
public class S3FileContentReader {

    /** S3 客户端。 */
    private final S3Client s3Client;

    /** 桶名。 */
    private final String bucket;

    /** 单文件最大字节数。 */
    private final long maxFileBytes;

    /**
     * 创建对象读取器。
     *
     * @param s3Client S3 客户端
     * @param storageProperties 存储配置
     * @param parseProperties 解析配置
     */
    public S3FileContentReader(
            S3Client s3Client, StorageProperties storageProperties, ParseProperties parseProperties) {
        this.s3Client = Objects.requireNonNull(s3Client, "s3Client must not be null");
        this.bucket = Objects.requireNonNull(storageProperties, "storageProperties must not be null")
                .bucket();
        this.maxFileBytes = Objects.requireNonNull(parseProperties, "parseProperties must not be null")
                .maxFileBytes();
    }

    /**
     * 读取文件内容。
     *
     * @param tenantId 当前租户
     * @param file 文件元数据
     * @return 文件字节
     * @throws ParseException key 越权、对象不存在、过大或存储不可用
     */
    public byte[] read(TenantId tenantId, FileRecord file) {
        requireTenantKey(tenantId, file.storageKey());
        return readKey(file.storageKey(), file.sizeBytes());
    }

    /**
     * 读取连接器文档的平台桶对象。
     *
     * <p>连接器原文由同步流程写入平台对象存储（与文件解析共用 {@link StorageProperties#bucket()}），
     * key 固定为 {@code tenants/{tenantId}/connector/{yyyy}/{documentId}}，年份取创建时间的 UTC 年。
     * 本方法不访问连接器源桶、不使用连接器凭据；读取前校验租户前缀与大小上限，与普通文件解析一致。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @param createdAt 文档创建时间（UTC），用于派生年份
     * @return 对象字节
     * @throws ParseException key 越权、对象不存在、过大或存储不可用
     */
    public byte[] readConnector(TenantId tenantId, UUID documentId, Instant createdAt) {
        String key = connectorObjectKey(tenantId, documentId, createdAt);
        // key 由服务端派生，仍执行前缀校验作为纵深防御；调用方不能传入任意 key。
        requireTenantKey(tenantId, key);
        return readKey(key, -1L);
    }

    /**
     * 派生连接器文档的平台对象 key。
     *
     * <p>与同步侧 {@link ConnectorContentTypes#objectKey} 共用同一实现，避免 B6 写入与 B7b 读取的 key
     * 布局漂移；key 不得写入日志、错误信息或索引文档。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @param createdAt 文档创建时间（UTC）
     * @return 平台对象 key
     */
    public static String connectorObjectKey(TenantId tenantId, UUID documentId, Instant createdAt) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        return ConnectorContentTypes.objectKey(tenantId.value(), documentId, createdAt);
    }

    /**
     * 只校验对象存在（图片 M02 不下载内容）。
     *
     * @param tenantId 当前租户
     * @param file 文件元数据
     * @throws ParseException key 越权、对象不存在或存储不可用
     */
    public void verifyExists(TenantId tenantId, FileRecord file) {
        requireTenantKey(tenantId, file.storageKey());
        head(file.storageKey());
    }

    /**
     * 读取指定 key 的对象，下载前检查大小并翻译存储错误。
     *
     * @param storageKey 对象 key
     * @param fallbackSize HEAD 未返回长度时使用的已知大小；未知时传负数跳过上限判断
     * @return 对象字节
     */
    private byte[] readKey(String storageKey, long fallbackSize) {
        HeadObjectResponse head = head(storageKey);
        long length = head.contentLength() == null ? fallbackSize : head.contentLength();
        if (length > maxFileBytes) {
            throw new ParseException(WorkerErrorCodes.FILE_TOO_LARGE, false, "file exceeds worker parse size limit");
        }
        try {
            return s3Client.getObjectAsBytes(GetObjectRequest.builder()
                            .bucket(bucket)
                            .key(storageKey)
                            .build())
                    .asByteArray();
        } catch (NoSuchKeyException ex) {
            throw ParseException.notFound();
        } catch (S3Exception ex) {
            throw translate(ex);
        } catch (SdkClientException ex) {
            throw ParseException.storageUnavailable("object storage is unavailable", ex);
        }
    }

    /**
     * 发起 HEAD 请求并翻译存储错误。
     *
     * @param storageKey 对象 key
     * @return HEAD 响应
     */
    private HeadObjectResponse head(String storageKey) {
        try {
            return s3Client.headObject(
                    HeadObjectRequest.builder().bucket(bucket).key(storageKey).build());
        } catch (NoSuchKeyException ex) {
            throw ParseException.notFound();
        } catch (S3Exception ex) {
            throw translate(ex);
        } catch (SdkClientException ex) {
            throw ParseException.storageUnavailable("object storage is unavailable", ex);
        }
    }

    /**
     * 校验对象 key 的租户前缀，防止篡改 key 读取其他租户对象。
     *
     * @param tenantId 当前租户
     * @param storageKey 对象 key
     */
    private static void requireTenantKey(TenantId tenantId, String storageKey) {
        if (storageKey == null || !storageKey.startsWith("tenants/" + tenantId.value() + "/")) {
            throw ParseException.notFound();
        }
    }

    /**
     * 将 S3 错误翻译为可重试/不可重试解析错误；404/403 统一为不存在，避免泄露存在性。
     *
     * @param ex S3 异常
     * @return 解析异常
     */
    private static ParseException translate(S3Exception ex) {
        int status = ex.statusCode();
        if (status == 404 || status == 403) {
            return ParseException.notFound();
        }
        if (status >= 500) {
            return ParseException.storageUnavailable("object storage is unavailable", ex);
        }
        return new ParseException(
                WorkerErrorCodes.STORAGE_UNAVAILABLE, false, "object storage request was rejected", ex);
    }
}
