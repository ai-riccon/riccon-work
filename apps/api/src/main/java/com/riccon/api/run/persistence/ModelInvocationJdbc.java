package com.riccon.api.run.persistence;

import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import com.riccon.api.persistence.TenantJdbc;
import com.riccon.api.run.domain.ModelInvocationSnapshot;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.model.InvocationStatus;
import com.riccon.platform.domain.model.ModelPurpose;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 模型调用记录 SQL。
 *
 * <p>运行详情按 {@code run_id} 读取调用摘要；记录只含状态、耗时与 token，不含提示词或回答原文。
 * 查询经 {@link TenantJdbc} 强制租户条件，跨租户运行不会出现在结果中。
 */
@Component
public class ModelInvocationJdbc {

    /** 按运行列出调用记录；同一次运行通常只有一次对话或报告生成调用。 */
    private static final String LIST_BY_RUN = """
            SELECT id, model_id, model_key_snapshot, purpose, status, first_token_ms, duration_ms,
                   prompt_tokens, completion_tokens, error_code, started_at, finished_at
              FROM model_invocation
             WHERE tenant_id = :tenantId
               AND run_id = :runId
             ORDER BY started_at ASC, id ASC
            """;

    /** 租户守卫数据访问入口。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建调用记录 SQL 组件。
     *
     * @param tenantJdbc 租户守卫数据访问入口
     */
    public ModelInvocationJdbc(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 读取运行的模型调用记录。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @return 调用记录列表；没有时为空列表
     */
    public List<ModelInvocationSnapshot> listByRun(TenantId tenantId, UUID runId) {
        return tenantJdbc.query(
                tenantId, LIST_BY_RUN, Map.of("runId", BinaryUuids.toBytes(runId)), ModelInvocationJdbc::mapRow);
    }

    /**
     * 映射调用记录行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 调用记录
     * @throws SQLException 列读取失败
     */
    private static ModelInvocationSnapshot mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ModelInvocationSnapshot(
                BinaryUuids.fromBytes(rs.getBytes("id")),
                BinaryUuids.fromBytes(rs.getBytes("model_id")),
                rs.getString("model_key_snapshot"),
                ModelPurpose.valueOf(rs.getString("purpose")),
                InvocationStatus.valueOf(rs.getString("status")),
                rs.getObject("first_token_ms", Integer.class),
                rs.getObject("duration_ms", Long.class),
                rs.getObject("prompt_tokens", Integer.class),
                rs.getObject("completion_tokens", Integer.class),
                rs.getString("error_code"),
                SqlTimestamps.toInstant(rs.getObject("started_at", LocalDateTime.class)),
                SqlTimestamps.toInstant(rs.getObject("finished_at", LocalDateTime.class)));
    }
}
