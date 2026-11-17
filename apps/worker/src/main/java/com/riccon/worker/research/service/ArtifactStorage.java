package com.riccon.worker.research.service;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.config.StorageProperties;
import com.riccon.worker.mcp.domain.Digests;
import com.riccon.worker.research.domain.ArtifactException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * 报告正文的对象存储写入。
 *
 * <p>对象 key 由服务端生成并带租户前缀（ADR-0012）：{@code tenants/{tenantId}/artifact/{yyyy}/{objectId}}。
 * {@code objectId} 由任务 ID 与正文 SHA-256 组成，同一内容重复写入会覆盖同一对象，避免重试产生重复对象；
 * 不同内容生成不同 key，对应新的产物版本。正文不写入数据库，也不写日志。
 */
@Component
public class ArtifactStorage {

    /** 日志记录器。 */
    private static final Logger LOG = LoggerFactory.getLogger(ArtifactStorage.class);

    /** 产物正文 MIME。 */
    public static final String CONTENT_TYPE = "text/markdown";

    /** 对象类别。 */
    private static final String CATEGORY = "artifact";

    /** S3 客户端。 */
    private final S3Client s3Client;

    /** 存储配置。 */
    private final StorageProperties properties;

    /** UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建产物存储。
     *
     * @param s3Client S3 客户端
     * @param properties 存储配置
     * @param clock UTC 时钟
     */
    public ArtifactStorage(S3Client s3Client, StorageProperties properties, Clock clock) {
        this.s3Client = Objects.requireNonNull(s3Client, "s3Client must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 写入报告正文并返回可校验元数据。
     *
     * @param tenantId 租户
     * @param researchTaskId 报告任务 ID
     * @param body Markdown 正文
     * @return 写入结果
     */
    public StoredArtifact put(TenantId tenantId, UUID researchTaskId, String body) {
        if (body == null || body.isBlank()) {
            throw new ArtifactException(ArtifactException.INVALID, false, "artifact body must not be blank");
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String sha256 = Digests.sha256Hex(body);
        String key = objectKey(tenantId, researchTaskId, sha256);
        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(properties.bucket())
                            .key(key)
                            .contentType(CONTENT_TYPE)
                            .contentLength((long) bytes.length)
                            .build(),
                    RequestBody.fromBytes(bytes));
            return new StoredArtifact(key, bytes.length, sha256);
        } catch (RuntimeException ex) {
            // 不记录正文或 key 之外的请求细节；对象存储故障通常是暂时性的，交给 outbox 有界重试。
            LOG.error(
                    "artifact.storage.put_failed tenantId={} taskId={} errorType={}",
                    tenantId.value(),
                    researchTaskId,
                    ex.getClass().getSimpleName());
            throw new ArtifactException(
                    ArtifactException.STORAGE_UNAVAILABLE, true, "artifact storage is unavailable", ex);
        }
    }

    /**
     * 生成带租户前缀的对象 key。
     *
     * @param tenantId 租户
     * @param researchTaskId 报告任务 ID
     * @param sha256 正文摘要
     * @return 对象 key
     */
    String objectKey(TenantId tenantId, UUID researchTaskId, String sha256) {
        int year = clock.instant().atZone(ZoneOffset.UTC).getYear();
        String key = "tenants/" + tenantId.value() + "/" + CATEGORY + "/" + year + "/" + researchTaskId + "-" + sha256;
        if (key.length() > 512) {
            throw new ArtifactException(ArtifactException.INVALID, false, "artifact storage key exceeds budget");
        }
        return key;
    }

    /**
     * 已写入的对象元数据。
     *
     * @param storageKey 对象 key
     * @param sizeBytes 正文字节数
     * @param sha256 正文 SHA-256
     */
    public record StoredArtifact(String storageKey, long sizeBytes, String sha256) {}
}
