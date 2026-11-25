package com.riccon.worker.workflow.approval.service;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.identity.RoleCodes;
import com.riccon.platform.domain.workflow.WorkflowApprovalAssigneeType;
import com.riccon.platform.domain.workflow.WorkflowErrorCode;
import com.riccon.worker.workflow.approval.domain.WorkflowApprovalRow;
import com.riccon.worker.workflow.approval.repository.WorkflowApprovalRepository;
import com.riccon.worker.workflow.engine.domain.WorkflowGraphNodeConfig;
import com.riccon.worker.workflow.node.domain.WorkflowNodeFailureException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 审批人解析与决定范围校验（ADR-0024 §5.2）。
 *
 * <p>解析顺序固定：本租户 → 账号 {@code ACTIVE} → 角色/用户范围 → 排除运行发起人。结果为空的
 * 情况不在这里失败：执行器仍创建一条无 assignee 的审批记录，把运行保持在
 * {@code WAITING_APPROVAL} 并写 {@code WF_NO_APPROVER}，绝不自动放行。角色审批不写
 * {@code workflow_approval_assignee}，决定时按 {@code user_role} 联接复查。
 *
 * <p>审批节点配置在发布阶段已校验；运行入口仍做封闭校验，配置损坏时以 {@code WF_INVALID_GRAPH}
 * 失败，不把非法配置当作“无审批人”放行。
 */
@Component
public class WorkflowApprovalAssigneeResolver {

    /** 允许的角色审批码；与数据库 CHECK、契约枚举一致。 */
    private static final Set<String> ALLOWED_ROLES =
            Set.of(RoleCodes.TENANT_ADMIN, RoleCodes.WORKFLOW_EDITOR, RoleCodes.MEMBER);

    /** 审批仓储。 */
    private final WorkflowApprovalRepository repository;

    /**
     * 创建解析器。
     *
     * @param repository 审批仓储
     */
    public WorkflowApprovalAssigneeResolver(WorkflowApprovalRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    /**
     * 按节点配置解析可用审批人。
     *
     * @param tenantId 租户
     * @param config 审批节点配置
     * @param requesterUserId 运行发起人（必须排除）
     * @return 审批人解析结果（可能为空）
     * @throws WorkflowNodeFailureException 配置缺失或非法（{@code WF_INVALID_GRAPH}）
     */
    public Resolution resolve(TenantId tenantId, WorkflowGraphNodeConfig config, UUID requesterUserId) {
        WorkflowApprovalAssigneeType type = config.assigneeType();
        if (type == null) {
            throw invalid("审批节点缺少 assigneeType");
        }
        if (type == WorkflowApprovalAssigneeType.USER) {
            return resolveUsers(tenantId, config, requesterUserId);
        }
        return resolveRole(tenantId, config, requesterUserId);
    }

    /**
     * 判断指定审批人是否有权决定。
     *
     * @param tenantId 租户
     * @param approval 审批行
     * @param approverUserId 审批人
     * @return true 表示在节点指定范围内
     */
    public boolean inScope(TenantId tenantId, WorkflowApprovalRow approval, UUID approverUserId) {
        if (approval.assigneeType() == WorkflowApprovalAssigneeType.USER) {
            return repository.isAssignedUser(tenantId, approval.id(), approverUserId);
        }
        String roleCode = approval.assigneeRoleCode();
        return roleCode != null && repository.hasRole(tenantId, approverUserId, roleCode);
    }

    /**
     * 解析指定用户审批人：过滤停用/跨租户/发起人，保留配置顺序。
     *
     * @param tenantId 租户
     * @param config 节点配置
     * @param requesterUserId 发起人
     * @return 解析结果
     */
    private Resolution resolveUsers(TenantId tenantId, WorkflowGraphNodeConfig config, UUID requesterUserId) {
        if (config.assigneeRole() != null) {
            throw invalid("用户审批不能同时配置 assigneeRole");
        }
        List<UUID> configured = config.assigneeUserIds();
        if (configured == null || configured.isEmpty()) {
            throw invalid("用户审批至少需要一个审批人");
        }
        if (configured.size() > 50) {
            throw invalid("用户审批人不能超过 50 个");
        }
        LinkedHashSet<UUID> unique = new LinkedHashSet<>(configured);
        List<UUID> active = repository.findActiveUserAssignees(tenantId, unique, requesterUserId);
        Set<UUID> available = Set.copyOf(active);
        List<UUID> ordered = new ArrayList<>(unique.size());
        for (UUID userId : unique) {
            if (available.contains(userId)) {
                ordered.add(userId);
            }
        }
        return new Resolution(WorkflowApprovalAssigneeType.USER, null, ordered);
    }

    /**
     * 解析角色审批人：排除发起人，允许结果为空。
     *
     * @param tenantId 租户
     * @param config 节点配置
     * @param requesterUserId 发起人
     * @return 解析结果
     */
    private Resolution resolveRole(TenantId tenantId, WorkflowGraphNodeConfig config, UUID requesterUserId) {
        if (config.assigneeUserIds() != null && !config.assigneeUserIds().isEmpty()) {
            throw invalid("角色审批不能同时配置 assigneeUserIds");
        }
        String roleCode = config.assigneeRole();
        if (roleCode == null || !ALLOWED_ROLES.contains(roleCode)) {
            throw invalid("角色审批人不受支持");
        }
        List<UUID> active = repository.findActiveRoleAssignees(tenantId, roleCode, requesterUserId);
        return new Resolution(WorkflowApprovalAssigneeType.ROLE, roleCode, active);
    }

    /**
     * 构造配置非法失败。
     *
     * @param message 脱敏原因
     * @return 节点失败
     */
    private static WorkflowNodeFailureException invalid(String message) {
        return new WorkflowNodeFailureException(WorkflowErrorCode.WF_INVALID_GRAPH, message);
    }

    /**
     * 审批人解析结果。
     *
     * @param type 审批人类型
     * @param roleCode 角色码；用户审批为空
     * @param userIds 可用审批人；角色审批用于审计计数，不写 assignee 表
     */
    public record Resolution(WorkflowApprovalAssigneeType type, String roleCode, List<UUID> userIds) {

        /** 复制集合并校验类型与角色码。 */
        public Resolution {
            Objects.requireNonNull(type, "type must not be null");
            userIds = userIds == null ? List.of() : List.copyOf(userIds);
            if (type == WorkflowApprovalAssigneeType.USER && roleCode != null) {
                throw new IllegalArgumentException("user resolution must not set roleCode");
            }
            if (type == WorkflowApprovalAssigneeType.ROLE && roleCode == null) {
                throw new IllegalArgumentException("role resolution requires roleCode");
            }
        }

        /**
         * 可用审批人数。
         *
         * @return 数量
         */
        public int count() {
            return userIds.size();
        }

        /**
         * 是否没有任何可用审批人。
         *
         * @return true 表示无可用审批人
         */
        public boolean empty() {
            return userIds.isEmpty();
        }
    }
}
