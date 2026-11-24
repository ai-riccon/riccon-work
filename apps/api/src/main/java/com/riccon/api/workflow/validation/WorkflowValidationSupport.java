package com.riccon.api.workflow.validation;

import com.riccon.platform.common.tenant.TenantId;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 图校验与发布范围校验所需的只读目录/授权查询端口。
 *
 * <p>校验器位于 {@code workflow.validation} 根包，只依赖本端口；实现放在
 * {@code workflow.validation.repository}，通过 {@code ..persistence..} 访问全局目录与租户表，满足
 * ArchUnit 的 controller → service → repository → persistence 单向规则。查询全部只读，不写审计。
 */
public interface WorkflowValidationSupport {

    /**
     * 返回指定模型集合中可在 REPORT 路由链使用的模型。
     *
     * <p>要求模型与 provider 均为 {@code ACTIVE}、协议为 {@code OPENAI_COMPATIBLE}、位于
     * {@code purpose=REPORT} 的路由链，并通过部署端点允许列表（ADR-0024 §4.10）。
     *
     * @param modelIds 待检查模型 ID；不得为空集合
     * @return 可用模型 ID
     */
    Set<UUID> findAvailableReportModels(Set<UUID> modelIds);

    /**
     * 查询工具对租户的可用状态。
     *
     * @param tenantId 当前租户
     * @param toolIds 待检查工具 ID；不得为空集合
     * @return 工具 ID 到状态的映射；缺失按未授权处理
     */
    Map<UUID, WorkflowToolAvailability> toolAvailabilities(TenantId tenantId, Set<UUID> toolIds);

    /**
     * 统计排除指定账号后可用的审批人数量。
     *
     * @param tenantId 当前租户
     * @param query 审批人解析条件
     * @return 可用审批人数；无可用审批人时返回 0
     */
    int countActiveApprovers(TenantId tenantId, WorkflowApproverQuery query);

    /**
     * 查询账号当前负责的启用部门。
     *
     * <p>用于 PUBLISH 与 VALIDATE 共用的 DEPARTMENT 范围校验：账号必须是启用状态，部门必须是其主部门、
     * 启用状态，且负责人是本人（ADR-0024 §6.2、§7.4）。
     *
     * @param tenantId 当前租户
     * @param userId 当前账号
     * @return 部门 ID；账号停用、无部门、部门停用或负责人不是本人时为空
     */
    Optional<UUID> findActiveManagedDepartmentId(TenantId tenantId, UUID userId);
}
