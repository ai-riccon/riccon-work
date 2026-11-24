package com.riccon.api.workflow.validation.repository;

import com.riccon.api.workflow.validation.WorkflowApproverQuery;
import com.riccon.api.workflow.validation.WorkflowToolAvailability;
import com.riccon.api.workflow.validation.WorkflowValidationSupport;
import com.riccon.api.workflow.validation.persistence.WorkflowValidationJdbc;
import com.riccon.api.workflow.validation.persistence.WorkflowValidationJdbc.ReportModelRow;
import com.riccon.api.workflow.validation.persistence.WorkflowValidationJdbc.ToolRow;
import com.riccon.platform.common.model.ManagedEndpointRegistry;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.workflow.WorkflowApprovalAssigneeType;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * 图校验只读查询仓储。
 *
 * <p>把全局模型/工具目录与租户授权/账号查询组合成校验端口，并在仓储层应用部署端点允许列表；校验器只依赖
 * {@link WorkflowValidationSupport}，不直接接触 JDBC（ArchUnit 分层）。所有查询只读，不返回凭据或正文。
 */
@Repository
public class WorkflowValidationRepository implements WorkflowValidationSupport {

    /** 启用状态值。 */
    private static final String ACTIVE = "ACTIVE";

    /** 唯一支持的 provider 协议。 */
    private static final String OPENAI_COMPATIBLE = "OPENAI_COMPATIBLE";

    /** 校验 SQL。 */
    private final WorkflowValidationJdbc jdbc;

    /** 部署模型端点允许列表。 */
    private final ManagedEndpointRegistry endpointRegistry;

    /**
     * 创建仓储。
     *
     * @param jdbc 校验 SQL
     * @param endpointRegistry 部署模型端点允许列表
     */
    public WorkflowValidationRepository(WorkflowValidationJdbc jdbc, ManagedEndpointRegistry endpointRegistry) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.endpointRegistry = Objects.requireNonNull(endpointRegistry, "endpointRegistry must not be null");
    }

    /**
     * 返回指定模型集合中可在 REPORT 路由链使用的模型。
     *
     * @param modelIds 待检查模型 ID
     * @return 可用模型 ID
     */
    @Override
    public Set<UUID> findAvailableReportModels(Set<UUID> modelIds) {
        if (modelIds.isEmpty()) {
            return Set.of();
        }
        Set<UUID> available = new HashSet<>();
        for (ReportModelRow row : jdbc.findReportModels(modelIds)) {
            if (ACTIVE.equals(row.modelStatus())
                    && ACTIVE.equals(row.providerStatus())
                    && OPENAI_COMPATIBLE.equals(row.providerProtocol())
                    && row.inReportChain()
                    && endpointRegistry.isAllowed(row.providerCode(), row.providerBaseUrl())) {
                available.add(row.modelId());
            }
        }
        return Set.copyOf(available);
    }

    /**
     * 查询工具对租户的可用状态。
     *
     * @param tenantId 当前租户
     * @param toolIds 待检查工具 ID
     * @return 工具 ID 到状态的映射
     */
    @Override
    public Map<UUID, WorkflowToolAvailability> toolAvailabilities(TenantId tenantId, Set<UUID> toolIds) {
        if (toolIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ToolRow> rows = new LinkedHashMap<>();
        for (ToolRow row : jdbc.findTools(toolIds)) {
            rows.put(row.toolId(), row);
        }
        Map<UUID, Boolean> grants = jdbc.findToolGrants(tenantId, toolIds);
        Map<UUID, WorkflowToolAvailability> states = new LinkedHashMap<>();
        for (UUID toolId : toolIds) {
            ToolRow row = rows.get(toolId);
            if (row == null) {
                // 不区分“不存在”与“未授权”，避免枚举平台目录。
                states.put(toolId, WorkflowToolAvailability.notAuthorized());
            } else if (!ACTIVE.equals(row.toolStatus()) || !ACTIVE.equals(row.serverStatus())) {
                states.put(toolId, WorkflowToolAvailability.toolDisabled());
            } else if (!Boolean.TRUE.equals(grants.get(toolId))) {
                states.put(toolId, WorkflowToolAvailability.notAuthorized());
            } else {
                states.put(toolId, WorkflowToolAvailability.available(row.riskLevel(), row.requiresApproval()));
            }
        }
        return Map.copyOf(states);
    }

    /**
     * 统计排除指定账号后可用的审批人数量。
     *
     * @param tenantId 当前租户
     * @param query 审批人解析条件
     * @return 可用审批人数
     */
    @Override
    public int countActiveApprovers(TenantId tenantId, WorkflowApproverQuery query) {
        if (query.assigneeType() == WorkflowApprovalAssigneeType.USER) {
            if (query.assigneeUserIds().isEmpty()) {
                return 0;
            }
            return jdbc.countActiveUsers(tenantId, query.assigneeUserIds(), query.excludedUserId());
        }
        if (query.assigneeRole() == null || query.assigneeRole().isBlank()) {
            return 0;
        }
        return jdbc.countActiveRoleUsers(tenantId, query.assigneeRole(), query.excludedUserId());
    }
}
