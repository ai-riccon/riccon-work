package com.riccon.api.knowledge.domain;

import com.riccon.platform.domain.knowledge.KnowledgeDocumentStatus;
import com.riccon.platform.domain.knowledge.KnowledgeVisibility;
import com.riccon.platform.domain.knowledge.ParseEngine;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 知识库文档行快照。
 *
 * <p>MySQL 是权威元数据；索引状态由 Worker 推进。M05 起携带文档级 ACL、解析摘要与保留到期信息，
 * 可见性判定集中在 {@code KnowledgeAccessJdbc}；本记录只表达持久化行，不参与授权。
 *
 * @param id 文档 ID
 * @param title 标题
 * @param sourceType 来源类型
 * @param fileId 来源文件，可为空
 * @param checksum 内容 SHA-256
 * @param mimeType 来源 MIME
 * @param sizeBytes 来源大小
 * @param status 索引状态
 * @param errorCode 失败错误码
 * @param errorMessage 脱敏失败原因
 * @param indexedAt 索引完成时间
 * @param createdBy 添加人
 * @param createdAt 创建时间
 * @param visibility 可见范围
 * @param departmentId 可见部门；仅 {@code DEPT} 非空
 * @param aclVersion ACL 变更计数，用于索引刷新幂等键
 * @param parseEngine 解析引擎
 * @param warnings 解析警告白名单
 * @param parseSummary 结构摘要
 * @param retentionExpiresAt 内容保留到期时间
 * @param sourceFileAccessLimited 文档可见集合是否宽于源文件空间
 * @param aclUserCount {@code USERS} 显式授权人数，不含创建人
 * @param deletedAt 软删除时间；未删除为空
 */
public record KnowledgeDocumentRecord(
        UUID id,
        String title,
        KnowledgeSourceType sourceType,
        UUID fileId,
        String checksum,
        String mimeType,
        long sizeBytes,
        KnowledgeDocumentStatus status,
        String errorCode,
        String errorMessage,
        Instant indexedAt,
        UUID createdBy,
        Instant createdAt,
        KnowledgeVisibility visibility,
        UUID departmentId,
        int aclVersion,
        ParseEngine parseEngine,
        List<String> warnings,
        KnowledgeParseSummary parseSummary,
        Instant retentionExpiresAt,
        boolean sourceFileAccessLimited,
        long aclUserCount,
        Instant deletedAt) {

    /** 部署默认保留天数（合同 data.retention）。 */
    public static final int DEFAULT_RETENTION_DAYS = 180;

    /** 校验必填字段。 */
    public KnowledgeDocumentRecord {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(sourceType, "sourceType must not be null");
        Objects.requireNonNull(checksum, "checksum must not be null");
        Objects.requireNonNull(mimeType, "mimeType must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(visibility, "visibility must not be null");
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must be >= 0");
        }
        if (aclVersion < 1) {
            throw new IllegalArgumentException("aclVersion must be >= 1");
        }
        if (aclUserCount < 0) {
            throw new IllegalArgumentException("aclUserCount must be >= 0");
        }
        if (status != KnowledgeDocumentStatus.DELETED && deletedAt != null) {
            throw new IllegalArgumentException("deletedAt requires DELETED status");
        }
    }

    /**
     * 计算保留策略来源。
     *
     * <p>历史行没有到期值时按当前租户策略解释；创建时写入的到期值与“创建时间 + 当前策略天数”一致时
     * 归因于部署默认或租户策略，否则视为显式设置。
     *
     * @param tenantRetentionDays 当前租户保留天数
     * @return 保留策略来源
     */
    public KnowledgeRetentionPolicy retentionPolicy(int tenantRetentionDays) {
        if (retentionExpiresAt == null) {
            // 历史 NULL 行按 ADR-0023 第 10 节固定为创建后 180 天，不随当前租户策略变化。
            return KnowledgeRetentionPolicy.DEPLOYMENT_DEFAULT;
        }
        KnowledgeRetentionPolicy base = tenantRetentionDays == DEFAULT_RETENTION_DAYS
                ? KnowledgeRetentionPolicy.DEPLOYMENT_DEFAULT
                : KnowledgeRetentionPolicy.TENANT_POLICY;
        Instant policyExpiry = createdAt.plus(Duration.ofDays(tenantRetentionDays));
        return retentionExpiresAt.equals(policyExpiry) ? base : KnowledgeRetentionPolicy.EXPLICIT;
    }

    /**
     * 计算对外展示的生效保留天数。
     *
     * @param tenantRetentionDays 当前租户保留天数
     * @return 1–3650 的保留天数
     */
    public int retentionDays(int tenantRetentionDays) {
        if (retentionExpiresAt == null) {
            // 历史 NULL 行固定 180 天；当前租户策略只影响新建文档。
            return DEFAULT_RETENTION_DAYS;
        }
        long seconds = Duration.between(createdAt, retentionExpiresAt).getSeconds();
        long days = Math.max(
                1L,
                (seconds + ChronoUnit.DAYS.getDuration().getSeconds() - 1)
                        / ChronoUnit.DAYS.getDuration().getSeconds());
        return (int) Math.min(3650L, days);
    }
}
