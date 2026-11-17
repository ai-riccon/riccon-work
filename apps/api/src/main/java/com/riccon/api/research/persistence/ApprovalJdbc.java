package com.riccon.api.research.persistence;

import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import com.riccon.api.persistence.TenantJdbc;
import com.riccon.api.research.domain.ApprovalSnapshot;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.research.ApprovalStatus;
import com.riccon.platform.domain.research.RunStage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 审批 SQL。
 *
 * <p>只查询公开列，明确排除 {@code params_ciphertext} 与 {@code params_key_id}：完整参数仅由 Worker 解密重放，
 * 不进入任何 API 响应（ADR-0017 第 1、6 节）。决定更新带 {@code status = 'PENDING'} 条件，重复决定影响 0 行。
 */
@Component
public class ApprovalJdbc {

    /** 公开列；不包含密文与密钥 ID。 */
    private static final String COLUMNS = """
            id, research_task_id, run_id, stage, attempt_no, status, tool_name,
            params_preview, params_sha256, requested_by, decided_by, decided_at,
            decision_comment, expires_at, created_at
            """;

    /** 按 ID 读取并加行锁；审批决定串行化。 */
    private static final String FIND_FOR_UPDATE =
            "SELECT " + COLUMNS + " FROM approval WHERE tenant_id = :tenantId AND id = :approvalId FOR UPDATE";

    /** 按报告任务读取审批；详情时间线按创建时间升序。 */
    private static final String LIST_BY_TASK = "SELECT " + COLUMNS
            + " FROM approval WHERE tenant_id = :tenantId AND research_task_id = :taskId"
            + " ORDER BY created_at ASC, id ASC";

    /** 列表过滤条件。 */
    private static final String LIST_FILTER = " FROM approval WHERE tenant_id = :tenantId";

    /** 统计审批数。 */
    private static final String COUNT = "SELECT COUNT(*)" + LIST_FILTER;

    /** 带状态过滤的统计。 */
    private static final String COUNT_BY_STATUS = COUNT + " AND status = :status";

    /** 分页列表；最新审批在前。 */
    private static final String LIST =
            "SELECT " + COLUMNS + LIST_FILTER + " ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset";

    /** 带状态过滤的分页列表。 */
    private static final String LIST_BY_STATUS = "SELECT " + COLUMNS + LIST_FILTER
            + " AND status = :status"
            + " ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset";

    /** 条件更新为已决定；仅 PENDING 可迁移。 */
    private static final String MARK_DECIDED = """
            UPDATE approval
               SET status = :status,
                   decided_by = :decidedBy,
                   decided_at = :decidedAt,
                   decision_comment = :decisionComment,
                   updated_at = :decidedAt
             WHERE tenant_id = :tenantId
               AND id = :approvalId
               AND status = 'PENDING'
            """;

    /** 租户守卫数据访问入口。 */
    private final TenantJdbc tenantJdbc;

    /** params_preview JSON 解析。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建审批 SQL 组件。
     *
     * @param tenantJdbc 租户守卫数据访问入口
     * @param objectMapper JSON 解析器
     */
    public ApprovalJdbc(TenantJdbc tenantJdbc, ObjectMapper objectMapper) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * 按 ID 读取审批并加锁。
     *
     * @param tenantId 当前租户
     * @param approvalId 审批 ID
     * @return 审批快照
     */
    public Optional<ApprovalSnapshot> findForUpdate(TenantId tenantId, UUID approvalId) {
        return tenantJdbc.queryOne(
                tenantId, FIND_FOR_UPDATE, Map.of("approvalId", BinaryUuids.toBytes(approvalId)), this::mapRow);
    }

    /**
     * 查询指定任务的审批记录。
     *
     * @param tenantId 当前租户
     * @param taskId 报告任务 ID
     * @return 审批列表
     */
    public List<ApprovalSnapshot> listByTask(TenantId tenantId, UUID taskId) {
        return tenantJdbc.query(tenantId, LIST_BY_TASK, Map.of("taskId", BinaryUuids.toBytes(taskId)), this::mapRow);
    }

    /**
     * 统计审批数。
     *
     * @param tenantId 当前租户
     * @param status 可选状态过滤
     * @return 审批数
     */
    public long count(TenantId tenantId, ApprovalStatus status) {
        if (status == null) {
            return tenantJdbc.count(tenantId, COUNT, Map.of());
        }
        return tenantJdbc.count(tenantId, COUNT_BY_STATUS, Map.of("status", status.name()));
    }

    /**
     * 分页列出审批。
     *
     * @param tenantId 当前租户
     * @param status 可选状态过滤
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 审批列表
     */
    public List<ApprovalSnapshot> list(TenantId tenantId, ApprovalStatus status, int limit, int offset) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("limit", limit);
        params.put("offset", offset);
        String sql = LIST;
        if (status != null) {
            params.put("status", status.name());
            sql = LIST_BY_STATUS;
        }
        return tenantJdbc.query(tenantId, sql, params, this::mapRow);
    }

    /**
     * 条件更新审批状态。
     *
     * @param tenantId 当前租户
     * @param approvalId 审批 ID
     * @param status 目标状态（APPROVED/REJECTED）
     * @param decidedBy 审批人
     * @param decidedAt 审批时间
     * @param comment 审批备注
     * @return 受影响行数；不是 PENDING 时为 0
     */
    public int markDecided(
            TenantId tenantId,
            UUID approvalId,
            ApprovalStatus status,
            UUID decidedBy,
            Instant decidedAt,
            String comment) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("approvalId", BinaryUuids.toBytes(approvalId));
        params.put("status", status.name());
        params.put("decidedBy", BinaryUuids.toBytes(decidedBy));
        params.put("decidedAt", SqlTimestamps.toDatabase(decidedAt));
        params.put("decisionComment", comment);
        return tenantJdbc.update(tenantId, MARK_DECIDED, params);
    }

    /**
     * 映射审批行并解析脱敏预览。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 审批快照
     * @throws SQLException 列读取失败
     */
    private ApprovalSnapshot mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ApprovalSnapshot(
                BinaryUuids.fromBytes(rs.getBytes("id")),
                BinaryUuids.fromBytes(rs.getBytes("research_task_id")),
                BinaryUuids.fromBytes(rs.getBytes("run_id")),
                RunStage.valueOf(rs.getString("stage")),
                rs.getInt("attempt_no"),
                ApprovalStatus.valueOf(rs.getString("status")),
                rs.getString("tool_name"),
                parsePreview(rs.getString("params_preview")),
                rs.getString("params_sha256"),
                BinaryUuids.fromBytes(rs.getBytes("requested_by")),
                rs.getBytes("decided_by") == null ? null : BinaryUuids.fromBytes(rs.getBytes("decided_by")),
                SqlTimestamps.toInstant(rs.getObject("decided_at", LocalDateTime.class)),
                rs.getString("decision_comment"),
                SqlTimestamps.toInstant(rs.getObject("expires_at", LocalDateTime.class)),
                SqlTimestamps.toInstant(rs.getObject("created_at", LocalDateTime.class)));
    }

    /**
     * 解析脱敏预览；非法 JSON 按空对象展示，不让读取路径失败。
     *
     * @param json 预览 JSON
     * @return 预览键值
     */
    private Map<String, Object> parsePreview(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            return parsed == null ? Map.of() : parsed;
        } catch (JacksonException exception) {
            return Map.of();
        }
    }
}
