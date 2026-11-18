package com.riccon.api.file.repository;

import com.riccon.api.file.domain.FileParseRecord;
import com.riccon.api.file.domain.FileRecord;
import com.riccon.api.file.persistence.FileJdbc;
import com.riccon.api.workspace.persistence.WorkspaceJdbc;
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
 * 文件可见性由工作空间唯一谓词决定：一般入口由服务层调用 {@code WorkspaceService.requireAccessible}；
 * 知识库登记需要在文件行锁内同时判定空间，因此本仓储组合 {@link WorkspaceJdbc#findAccessible}，
 * 不复制任何可见性 SQL。
 */
@Repository
public class FileRepository {

    /** 文件 SQL。 */
    private final FileJdbc fileJdbc;

    /** 工作空间可见性唯一实现点，用于锁定文件的组合判定。 */
    private final WorkspaceJdbc workspaceJdbc;

    /**
     * 创建仓储。
     *
     * @param fileJdbc 文件 SQL
     * @param workspaceJdbc 工作空间可见性 SQL
     */
    public FileRepository(FileJdbc fileJdbc, WorkspaceJdbc workspaceJdbc) {
        this.fileJdbc = Objects.requireNonNull(fileJdbc, "fileJdbc must not be null");
        this.workspaceJdbc = Objects.requireNonNull(workspaceJdbc, "workspaceJdbc must not be null");
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
     * 按租户读取可用文件。
     *
     * @param tenantId 当前租户
     * @param fileId 文件 ID
     * @return 文件快照
     */
    public Optional<FileRecord> findById(TenantId tenantId, UUID fileId) {
        return fileJdbc.findById(tenantId, fileId);
    }

    /**
     * 锁定并读取可用文件，同时校验当前员工可访问文件所属工作空间。
     *
     * <p>知识库登记使用：文件行锁与空间判定在同一调用内完成，空间判定委托给
     * {@link WorkspaceJdbc#findAccessible}，保持单一可见性谓词。
     *
     * @param tenantId 当前租户
     * @param fileId 文件 ID
     * @param userId 账号 ID
     * @return 文件快照；不存在或不可见时为空
     */
    public Optional<FileRecord> lockAccessible(TenantId tenantId, UUID fileId, UUID userId) {
        return fileJdbc.findByIdForUpdate(tenantId, fileId)
                .filter(file -> workspaceJdbc
                        .findAccessible(tenantId, file.workspaceId(), userId)
                        .isPresent());
    }

    /**
     * 锁定并读取可用文件。
     *
     * @param tenantId 当前租户
     * @param fileId 文件 ID
     * @return 文件快照
     */
    public Optional<FileRecord> findByIdForUpdate(TenantId tenantId, UUID fileId) {
        return fileJdbc.findByIdForUpdate(tenantId, fileId);
    }

    /**
     * 统计工作空间可用文件。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @param conversationId 可选会话过滤
     * @return 总数
     */
    public long countByWorkspace(TenantId tenantId, UUID workspaceId, UUID conversationId) {
        return fileJdbc.countByWorkspace(tenantId, workspaceId, conversationId);
    }

    /**
     * 分页查询工作空间可用文件。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @param conversationId 可选会话过滤
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 文件列表
     */
    public List<FileRecord> listByWorkspace(
            TenantId tenantId, UUID workspaceId, UUID conversationId, int limit, int offset) {
        return fileJdbc.listByWorkspace(tenantId, workspaceId, conversationId, limit, offset);
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
