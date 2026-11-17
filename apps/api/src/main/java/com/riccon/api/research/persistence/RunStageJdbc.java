package com.riccon.api.research.persistence;

import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import com.riccon.api.persistence.TenantJdbc;
import com.riccon.api.research.domain.RunStageSnapshot;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.research.RunStage;
import com.riccon.platform.domain.research.StageStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 运行阶段时间线 SQL。
 *
 * <p>每次尝试一条记录；重试会生成同阶段、递增尝试号的新行，API 按开始时间与阶段排序还原时间线。
 * {@code details} 是 JSON 白名单摘要，读取时只解析为通用 Map，键级过滤由响应 DTO 完成。
 */
@Component
public class RunStageJdbc {

    /** 时间线查询；不返回任何原文列。 */
    private static final String LIST_BY_RUN = """
            SELECT stage, attempt_no, status, error_code, started_at, finished_at, details
              FROM agent_run_stage
             WHERE tenant_id = :tenantId
               AND run_id = :runId
             ORDER BY started_at ASC, stage ASC, attempt_no ASC
            """;

    /** 租户守卫数据访问入口。 */
    private final TenantJdbc tenantJdbc;

    /** details JSON 解析。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建阶段 SQL 组件。
     *
     * @param tenantJdbc 租户守卫数据访问入口
     * @param objectMapper JSON 解析器
     */
    public RunStageJdbc(TenantJdbc tenantJdbc, ObjectMapper objectMapper) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * 读取运行的阶段时间线。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @return 阶段列表
     */
    public List<RunStageSnapshot> listByRun(TenantId tenantId, UUID runId) {
        return tenantJdbc.query(tenantId, LIST_BY_RUN, Map.of("runId", BinaryUuids.toBytes(runId)), this::mapRow);
    }

    /**
     * 映射阶段行并解析 details。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 阶段快照
     * @throws SQLException 列读取失败
     */
    private RunStageSnapshot mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new RunStageSnapshot(
                RunStage.valueOf(rs.getString("stage")),
                rs.getInt("attempt_no"),
                StageStatus.valueOf(rs.getString("status")),
                rs.getString("error_code"),
                SqlTimestamps.toInstant(rs.getObject("started_at", LocalDateTime.class)),
                SqlTimestamps.toInstant(rs.getObject("finished_at", LocalDateTime.class)),
                parseDetails(rs.getString("details")));
    }

    /**
     * 解析 details JSON；历史行或异常内容按空摘要处理，避免单个坏行让整个时间线 500。
     *
     * @param json JSON 文本
     * @return 键值映射
     */
    private Map<String, Object> parseDetails(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            return parsed == null ? Map.of() : parsed;
        } catch (JacksonException exception) {
            // details 只影响展示；返回空摘要并在响应 DTO 中隐藏，不让读取路径失败。
            return Map.of();
        }
    }
}
