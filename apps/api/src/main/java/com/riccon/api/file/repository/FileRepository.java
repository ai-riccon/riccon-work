package com.riccon.api.file.repository;

import com.riccon.api.file.domain.FileParseRecord;
import com.riccon.api.file.domain.FileRecord;
import com.riccon.api.file.persistence.FileJdbc;
import com.riccon.platform.common.tenant.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * 文件仓储。
 *
 * <p>封装文件、解析尝试与会话归属 SQL；服务层不直接接触 JDBC。所有读取默认排除软删除文件。
 */
@Repository
public class FileRepository {

    /** 文件 SQL。 */
    private final FileJdbc fileJdbc;

    /**
     * 创建仓储。
     *
     * @param fileJdbc 文件 SQL
     */
    public FileRepository(FileJdbc fileJdbc) {
        this.fileJdbc = Objects.requireNonNull(fileJdbc, "fileJdbc must not be null");
    }

    /**
     * 插入文件元数据。
     *
     * @param tenantId 当前租户
     * @param file 文件快照
     * @param uploadedBy 上传人
     */
    public void insertFile(TenantId tenantId, FileRecord file, UUID uploadedBy) {
        fileJdbc.insertFile(tenantId, file, uploadedBy);
    }

    /**
     * 插入解析尝试。
     *
     * @param tenantId 当前租户
     * @param fileId 文件 ID
     * @param parse 解析尝试
     * @param createdAt 创建时间
     */
    public void insertParse(TenantId tenantId, UUID fileId, FileParseRecord parse, Instant createdAt) {
        fileJdbc.insertParse(tenantId, fileId, parse, createdAt);
    }

    /**
     * 查询可见文件。
     *
     * @param tenantId 当前租户
     * @param fileId 文件 ID
     * @param userId 账号 ID
     * @return 文件快照
     */
    public Optional<FileRecord> findAccessible(TenantId tenantId, UUID fileId, UUID userId) {
        return fileJdbc.findAccessible(tenantId, fileId, userId);
    }

    /**
     * 锁定并查询可见文件。
     *
     * @param tenantId 当前租户
     * @param fileId 文件 ID
     * @param userId 账号 ID
     * @return 文件快照
     */
    public Optional<FileRecord> lockAccessible(TenantId tenantId, UUID fileId, UUID userId) {
        return fileJdbc.lockAccessible(tenantId, fileId, userId);
    }

    /**
     * 统计可见文件。
     *
     * @param tenantId 当前租户
     * @param userId 账号 ID
     * @param workspaceId 工作空间 ID
     * @param conversationId 可选会话过滤
     * @return 总数
     */
    public long countAccessible(TenantId tenantId, UUID userId, UUID workspaceId, UUID conversationId) {
        return fileJdbc.countAccessible(tenantId, userId, workspaceId, conversationId);
    }

    /**
     * 分页查询可见文件。
     *
     * @param tenantId 当前租户
     * @param userId 账号 ID
     * @param workspaceId 工作空间 ID
     * @param conversationId 可选会话过滤
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 文件列表
     */
    public List<FileRecord> listAccessible(
            TenantId tenantId, UUID userId, UUID workspaceId, UUID conversationId, int limit, int offset) {
        return fileJdbc.listAccessible(tenantId, userId, workspaceId, conversationId, limit, offset);
    }

    /**
     * 查询最近一次解析尝试。
     *
     * @param tenantId 当前租户
     * @param fileId 文件 ID
     * @return 解析尝试
     */
    public Optional<FileParseRecord> findLatestParse(TenantId tenantId, UUID fileId) {
        return fileJdbc.findLatestParse(tenantId, fileId);
    }

    /**
     * 软删除文件。
     *
     * @param tenantId 当前租户
     * @param fileId 文件 ID
     * @param deletedAt 删除时间
     * @return 受影响行数
     */
    public int softDelete(TenantId tenantId, UUID fileId, Instant deletedAt) {
        return fileJdbc.softDelete(tenantId, fileId, deletedAt);
    }

    /**
     * 锁定会话。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @param conversationId 会话 ID
     * @return 会话 ID
     */
    public Optional<UUID> lockConversation(TenantId tenantId, UUID workspaceId, UUID conversationId) {
        return fileJdbc.lockConversation(tenantId, workspaceId, conversationId);
    }

    /**
     * 统计会话内文件数。
     *
     * @param tenantId 当前租户
     * @param conversationId 会话 ID
     * @return 文件数
     */
    public long countConversationFiles(TenantId tenantId, UUID conversationId) {
        return fileJdbc.countConversationFiles(tenantId, conversationId);
    }
}
