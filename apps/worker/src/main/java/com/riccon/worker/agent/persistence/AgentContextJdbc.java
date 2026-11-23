package com.riccon.worker.agent.persistence;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.chat.CitationSourceType;
import com.riccon.platform.domain.chat.LocatorType;
import com.riccon.platform.domain.knowledge.KnowledgeVisibility;
import com.riccon.worker.agent.domain.RetrievedChunk;
import com.riccon.worker.persistence.TenantJdbc;
import com.riccon.worker.persistence.UuidBinary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 对话与报告 Agent 的检索访问（MySQL 权威分块）。
 *
 * <p>M02 不要求向量检索质量；使用带租户条件的关键词候选集，在应用层做确定性打分。
 * 查询只返回 TIED 到 {@code INDEXED} 且未删除的知识库文档；文件分块按最新成功解析尝试读取。
 * 所有 SQL 都带 {@code tenant_id}，由 {@link TenantJdbc} 强制。
 */
@Component
public class AgentContextJdbc {

    /** 最多使用的查询词数量。 */
    private static final int MAX_TERMS = 6;

    /** 单个查询词最小长度。 */
    private static final int MIN_TERM_LENGTH = 2;

    /** 引用片段最大字符数。 */
    private static final int SNIPPET_LENGTH = 500;

    /** 租户数据访问层。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建检索访问。
     *
     * @param tenantJdbc 租户数据访问层
     */
    public AgentContextJdbc(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 关键词检索知识库分块（系统兼容入口）。
     *
     * <p>该入口没有操作者上下文，按 fail-closed 只返回 {@code TENANT} 文档；需要按文档 ACL 检索时必须使用
     * {@link #search(TenantId, UUID, boolean, String, int, int)}。生产调用方（chat/report/knowledge_search）
     * 需要把 run 的用户 ID 传入新重载后才会返回 DEPT/USERS 文档。
     *
     * @param tenantId 租户
     * @param query 用户查询
     * @param candidateLimit 候选上限
     * @param limit 返回上限
     * @return 按分数降序的命中
     */
    public List<RetrievedChunk> search(TenantId tenantId, String query, int candidateLimit, int limit) {
        return search(tenantId, null, false, query, candidateLimit, limit);
    }

    /**
     * 关键词检索知识库分块，并按文档 ACL 过滤。
     *
     * <p>谓词与 API 列表/详情/下载共用同一语义（ADR-0023 第 5.4 节）：{@code TENANT} 全员、同启用部门的
     * {@code DEPT}、创建人或 {@code knowledge_document_acl} 显式授权。{@code unrestricted=true} 只用于
     * {@code TENANT_ADMIN} 管理性读取；工作线程必须由调用方解析后传入，不能在本类猜测角色。
     *
     * @param tenantId 租户
     * @param actorUserId 当前 run 的用户；为空表示系统兼容入口
     * @param unrestricted 是否管理性读取
     * @param query 用户查询
     * @param candidateLimit 候选上限
     * @param limit 返回上限
     * @return 按分数降序的命中
     */
    public List<RetrievedChunk> search(
            TenantId tenantId, UUID actorUserId, boolean unrestricted, String query, int candidateLimit, int limit) {
        List<String> terms = terms(query);
        if (terms.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("""
                SELECT c.id, c.document_id, d.title, c.content, c.locator_type,
                       c.page_no, c.sheet_name, c.cell_range, c.paragraph_no
                  FROM knowledge_chunk c
                  JOIN knowledge_document d
                    ON d.tenant_id = c.tenant_id
                   AND d.id = c.document_id
                 WHERE c.tenant_id = :tenantId
                   AND d.status = 'INDEXED'
                   AND d.deleted_at IS NULL
                   AND (
                """);
        Map<String, Object> params = new HashMap<>();
        for (int index = 0; index < terms.size(); index++) {
            if (index > 0) {
                sql.append(" OR ");
            }
            String parameter = "term" + index;
            sql.append("c.content LIKE :")
                    .append(parameter)
                    .append(" OR d.title LIKE :")
                    .append(parameter);
            params.put(parameter, "%" + terms.get(index) + "%");
        }
        sql.append(")");
        appendAclPredicate(sql, params, tenantId, actorUserId, unrestricted);
        sql.append(" ORDER BY c.document_id, c.ordinal LIMIT :candidateLimit");
        params.put("candidateLimit", candidateLimit);

        List<RetrievedChunk> candidates = tenantJdbc.query(
                tenantId,
                sql.toString(),
                params,
                (rs, rowNum) -> new RetrievedChunk(
                        0,
                        CitationSourceType.KNOWLEDGE,
                        null,
                        UuidBinary.fromBytes(rs.getBytes("document_id")),
                        UuidBinary.fromBytes(rs.getBytes("id")),
                        LocatorType.valueOf(rs.getString("locator_type")),
                        (Integer) rs.getObject("page_no"),
                        rs.getString("sheet_name"),
                        rs.getString("cell_range"),
                        (Integer) rs.getObject("paragraph_no"),
                        rs.getString("title"),
                        rs.getString("content"),
                        0.0));
        List<RetrievedChunk> scored = new ArrayList<>();
        for (RetrievedChunk candidate : candidates) {
            double score = score(candidate, terms);
            if (score > 0) {
                scored.add(new RetrievedChunk(
                        candidate.ordinal(),
                        candidate.sourceType(),
                        candidate.fileId(),
                        candidate.knowledgeDocumentId(),
                        candidate.chunkId(),
                        candidate.locatorType(),
                        candidate.pageNo(),
                        candidate.sheetName(),
                        candidate.cellRange(),
                        candidate.paragraphNo(),
                        candidate.title(),
                        candidate.content(),
                        Math.min(1.0, score)));
            }
        }
        scored.sort((left, right) -> Double.compare(right.score(), left.score()));
        List<RetrievedChunk> result = new ArrayList<>();
        int ordinal = 1;
        for (RetrievedChunk item : scored.subList(0, Math.min(limit, scored.size()))) {
            result.add(new RetrievedChunk(
                    ordinal++,
                    item.sourceType(),
                    item.fileId(),
                    item.knowledgeDocumentId(),
                    item.chunkId(),
                    item.locatorType(),
                    item.pageNo(),
                    item.sheetName(),
                    item.cellRange(),
                    item.paragraphNo(),
                    item.title(),
                    item.content(),
                    item.score()));
        }
        return List.copyOf(result);
    }

    /**
     * 读取指定文件的最新成功解析分块。
     *
     * @param tenantId 租户
     * @param fileId 文件 ID
     * @param limit 最大分块数
     * @return 分块；文件不存在、已删除或未成功解析时为空
     */
    public List<RetrievedChunk> fileChunks(TenantId tenantId, UUID fileId, int limit) {
        List<FileChunkRow> rows = tenantJdbc.query(
                tenantId,
                """
                SELECT f.original_name AS title, c.id, c.content, c.locator_type,
                       c.page_no, c.sheet_name, c.cell_range, c.paragraph_no
                  FROM file f
                  JOIN file_parse p
                    ON p.tenant_id = f.tenant_id
                   AND p.file_id = f.id
                   AND p.status = 'SUCCEEDED'
                  JOIN file_chunk c
                    ON c.tenant_id = p.tenant_id
                   AND c.parse_id = p.id
                 WHERE f.tenant_id = :tenantId
                   AND f.id = :fileId
                   AND f.status = 'AVAILABLE'
                   AND f.deleted_at IS NULL
                   AND p.attempt = (
                       SELECT MAX(p2.attempt)
                         FROM file_parse p2
                        WHERE p2.tenant_id = f.tenant_id
                          AND p2.file_id = f.id
                          AND p2.status = 'SUCCEEDED')
                 ORDER BY c.ordinal
                 LIMIT :limit
                """,
                Map.of("fileId", UuidBinary.toBytes(fileId), "limit", limit),
                (rs, rowNum) -> new FileChunkRow(
                        rs.getString("title"),
                        UuidBinary.fromBytes(rs.getBytes("id")),
                        rs.getString("content"),
                        LocatorType.valueOf(rs.getString("locator_type")),
                        (Integer) rs.getObject("page_no"),
                        rs.getString("sheet_name"),
                        rs.getString("cell_range"),
                        (Integer) rs.getObject("paragraph_no")));
        List<RetrievedChunk> chunks = new ArrayList<>();
        int ordinal = 1;
        for (FileChunkRow row : rows) {
            chunks.add(new RetrievedChunk(
                    ordinal++,
                    CitationSourceType.FILE,
                    fileId,
                    null,
                    row.chunkId(),
                    row.locatorType(),
                    row.pageNo(),
                    row.sheetName(),
                    row.cellRange(),
                    row.paragraphNo(),
                    row.title(),
                    row.content(),
                    1.0));
        }
        return List.copyOf(chunks);
    }

    /**
     * 校验文件属于租户且可用。
     *
     * @param tenantId 租户
     * @param fileId 文件 ID
     * @return 是否存在
     */
    public boolean fileAvailable(TenantId tenantId, UUID fileId) {
        return tenantJdbc
                .queryOne(
                        tenantId,
                        """
                        SELECT 1 AS present
                          FROM file
                         WHERE tenant_id = :tenantId
                           AND id = :fileId
                           AND status = 'AVAILABLE'
                           AND deleted_at IS NULL
                        """,
                        Map.of("fileId", UuidBinary.toBytes(fileId)),
                        (rs, rowNum) -> rs.getInt("present"))
                .isPresent();
    }

    /**
     * 读取最新成功解析的单个文件分块，供引用片段生成。
     *
     * @param tenantId 租户
     * @param fileIds 文件 ID 列表
     * @param perFileLimit 每文件上限
     * @return 全部文件分块，序号在调用方重新编排
     */
    public List<RetrievedChunk> fileChunks(TenantId tenantId, List<UUID> fileIds, int perFileLimit) {
        List<RetrievedChunk> all = new ArrayList<>();
        for (UUID fileId : fileIds) {
            all.addAll(fileChunks(tenantId, fileId, perFileLimit));
        }
        List<RetrievedChunk> renumbered = new ArrayList<>(all.size());
        int ordinal = 1;
        for (RetrievedChunk chunk : all) {
            renumbered.add(new RetrievedChunk(
                    ordinal++,
                    chunk.sourceType(),
                    chunk.fileId(),
                    chunk.knowledgeDocumentId(),
                    chunk.chunkId(),
                    chunk.locatorType(),
                    chunk.pageNo(),
                    chunk.sheetName(),
                    chunk.cellRange(),
                    chunk.paragraphNo(),
                    chunk.title(),
                    chunk.content(),
                    chunk.score()));
        }
        return List.copyOf(renumbered);
    }

    /**
     * 追加文档 ACL 谓词。
     *
     * <p>没有 actor 时只允许 {@code TENANT} 文档，避免旧调用方在 M05 后静默读到收窄文档；有 actor 时解析其
     * 主部门与部门状态，再追加与 API 相同的四种可见分支。管理性读取不追加谓词，审计由调用方负责。
     *
     * @param sql SQL 构造器
     * @param params 参数映射
     * @param tenantId 租户
     * @param actorUserId 当前用户；可空
     * @param unrestricted 是否管理性读取
     */
    private void appendAclPredicate(
            StringBuilder sql, Map<String, Object> params, TenantId tenantId, UUID actorUserId, boolean unrestricted) {
        if (unrestricted) {
            return;
        }
        if (actorUserId == null) {
            sql.append(" AND d.visibility = '")
                    .append(KnowledgeVisibility.TENANT.name())
                    .append("'");
            return;
        }
        Optional<DepartmentStatus> department = tenantJdbc.queryOne(
                tenantId,
                """
                SELECT u.department_id AS department_id, d.status AS department_status
                  FROM app_user u
                  LEFT JOIN department d
                    ON d.tenant_id = u.tenant_id
                   AND d.id = u.department_id
                 WHERE u.tenant_id = :tenantId
                   AND u.id = :actorUserId
                   AND u.status = 'ACTIVE'
                """,
                Map.of("actorUserId", UuidBinary.toBytes(actorUserId)),
                AgentContextJdbc::mapDepartmentStatus);
        sql.append(" AND (d.visibility = '")
                .append(KnowledgeVisibility.TENANT.name())
                .append("'");
        department
                .filter(status -> status.departmentId() != null && status.active())
                .ifPresent(status -> {
                    sql.append(" OR (d.visibility = '")
                            .append(KnowledgeVisibility.DEPT.name())
                            .append("' AND d.department_id = :aclDepartmentId)");
                    params.put("aclDepartmentId", UuidBinary.toBytes(status.departmentId()));
                });
        sql.append(" OR d.created_by = :aclActorUserId")
                .append(" OR EXISTS (SELECT 1 FROM knowledge_document_acl acl")
                .append(" WHERE acl.tenant_id = d.tenant_id AND acl.document_id = d.id")
                .append(" AND acl.user_id = :aclActorUserId))");
        params.put("aclActorUserId", UuidBinary.toBytes(actorUserId));
    }

    /**
     * 映射账号部门与部门状态。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 部门状态
     * @throws SQLException 列读取失败
     */
    private static DepartmentStatus mapDepartmentStatus(ResultSet rs, int rowNum) throws SQLException {
        byte[] departmentId = rs.getBytes("department_id");
        return new DepartmentStatus(
                departmentId == null ? null : UuidBinary.fromBytes(departmentId),
                "ACTIVE".equals(rs.getString("department_status")));
    }

    /**
     * 把查询拆成去重后的关键词。
     *
     * @param query 查询
     * @return 关键词
     */
    private static List<String> terms(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String term : query.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}_]+")) {
            if (term.length() < MIN_TERM_LENGTH) {
                continue;
            }
            unique.add(term);
            // 中文没有空格分词；补充二元组，避免“市场问题”无法命中“市场增长”。
            if (term.length() > 2
                    && term.codePoints()
                            .anyMatch(codePoint ->
                                    Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN)) {
                for (int index = 0; index + 2 <= term.length(); index++) {
                    unique.add(term.substring(index, index + 2));
                    if (unique.size() == MAX_TERMS) {
                        break;
                    }
                }
            }
            if (unique.size() == MAX_TERMS) {
                break;
            }
        }
        return List.copyOf(unique);
    }

    /**
     * 计算确定性相关度。
     *
     * @param chunk 分块
     * @param terms 关键词
     * @return 0–1 分数
     */
    private static double score(RetrievedChunk chunk, List<String> terms) {
        String content = chunk.content() == null ? "" : chunk.content().toLowerCase(java.util.Locale.ROOT);
        String title = chunk.title() == null ? "" : chunk.title().toLowerCase(java.util.Locale.ROOT);
        int hits = 0;
        for (String term : terms) {
            if (content.contains(term)) {
                hits += 2;
            }
            if (title.contains(term)) {
                hits += 1;
            }
        }
        if (hits == 0) {
            return 0;
        }
        return (double) hits / (terms.size() * 3.0);
    }

    /**
     * 文件分块行。
     *
     * @param title 文件名
     * @param chunkId 分块 ID
     * @param content 原文
     * @param locatorType 定位类型
     * @param pageNo 页码
     * @param sheetName 工作表
     * @param cellRange 单元格范围
     * @param paragraphNo 段落号
     */
    private record FileChunkRow(
            String title,
            UUID chunkId,
            String content,
            LocatorType locatorType,
            Integer pageNo,
            String sheetName,
            String cellRange,
            Integer paragraphNo) {}

    /**
     * 账号部门解析结果。
     *
     * @param departmentId 主部门；未分配为空
     * @param active 部门是否启用
     */
    private record DepartmentStatus(UUID departmentId, boolean active) {}
}
