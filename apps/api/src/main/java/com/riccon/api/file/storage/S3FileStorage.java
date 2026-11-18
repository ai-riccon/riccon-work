package com.riccon.api.file.storage;

import com.riccon.api.config.StorageProperties;
import com.riccon.api.file.domain.ObjectKeys;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

/**
 * S3 兼容对象存储实现。
 *
 * <p>每次操作前校验 key 的租户前缀，防止越权读写其他租户对象。上传使用文件流，不把 200 MB 原文读入堆；
 * 下载地址 TTL 由配置校验限制在 300 秒内。
 */
@Component
public class S3FileStorage implements FileStorage {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(S3FileStorage.class);

    /** S3 客户端。 */
    private final S3Client s3Client;

    /** 预签名器。 */
    private final S3Presigner presigner;

    /** 桶与对象存储配置。 */
    private final StorageProperties properties;

    /**
     * 创建存储适配器。
     *
     * @param s3Client S3 客户端
     * @param presigner 预签名器
     * @param properties 存储配置
     */
    public S3FileStorage(S3Client s3Client, S3Presigner presigner, StorageProperties properties) {
        this.s3Client = Objects.requireNonNull(s3Client, "s3Client must not be null");
        this.presigner = Objects.requireNonNull(presigner, "presigner must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    /** {@inheritDoc} */
    @Override
    public void putObject(String tenantId, String key, Path source, String contentType) {
        ObjectKeys.requireTenantPrefix(tenantId, key);
        try {
            long contentLength = Files.size(source);
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(properties.bucket())
                            .key(key)
                            .contentType(contentType)
                            .contentLength(contentLength)
                            .build(),
                    RequestBody.fromFile(source));
        } catch (IOException exception) {
            throw new IllegalStateException("failed to read upload buffer", exception);
        }
    }

    /** {@inheritDoc} */
    @Override
    public StorageObject readObject(String tenantId, String key, long maxBytes) {
        ObjectKeys.requireTenantPrefix(tenantId, key);
        // 正文一次读入内存，上限必须是可容纳的 int 范围；产物正文当前为 2 MiB。
        if (maxBytes <= 0 || maxBytes >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("read limit must be within (0, Integer.MAX_VALUE)");
        }
        GetObjectRequest request =
                GetObjectRequest.builder().bucket(properties.bucket()).key(key).build();
        try (ResponseInputStream<GetObjectResponse> object = s3Client.getObject(request)) {
            GetObjectResponse metadata = object.response();
            Long contentLength = metadata.contentLength();
            if (contentLength != null && contentLength > maxBytes) {
                throw new StorageObjectTooLargeException(contentLength, maxBytes);
            }
            // 多读 1 字节用于识别“元数据未声明但实际超限”的对象，避免截断后返回不完整正文。
            byte[] content = object.readNBytes((int) maxBytes + 1);
            if (content.length > maxBytes) {
                throw new StorageObjectTooLargeException(content.length, maxBytes);
            }
            return new StorageObject(content, metadata.contentType());
        } catch (IOException exception) {
            throw new StorageReadException("failed to read stored object", exception);
        } catch (S3Exception exception) {
            throw new StorageReadException("failed to fetch stored object", exception);
        }
    }

    /** {@inheritDoc} */
    @Override
    public URI presignGet(String tenantId, String key, Duration ttl) {
        ObjectKeys.requireTenantPrefix(tenantId, key);
        if (ttl == null || ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofSeconds(300)) > 0) {
            throw new IllegalArgumentException("download url ttl must be within 300 seconds");
        }
        GetObjectRequest getObjectRequest =
                GetObjectRequest.builder().bucket(properties.bucket()).key(key).build();
        PresignedGetObjectRequest presigned = presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(getObjectRequest)
                .build());
        return URI.create(presigned.url().toString());
    }

    /** {@inheritDoc} */
    @Override
    public void deleteQuietly(String tenantId, String key) {
        try {
            ObjectKeys.requireTenantPrefix(tenantId, key);
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .build());
        } catch (RuntimeException exception) {
            // 补偿删除失败不覆盖业务异常；对象由 M09 清理任务按前缀兜底。
            LOG.warn("storage.compensation.delete_failed tenantId={} key={}", tenantId, key, exception);
        }
    }
}
