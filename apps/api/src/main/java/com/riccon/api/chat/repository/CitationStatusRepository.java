package com.riccon.api.chat.repository;

import com.riccon.api.chat.domain.CitationSourceState;
import com.riccon.api.chat.persistence.CitationStatusJdbc;
import com.riccon.api.config.KnowledgeAclProperties;
import com.riccon.api.knowledge.persistence.KnowledgeAccessJdbc;
import com.riccon.platform.common.tenant.TenantId;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * 引用源文档状态仓储。
 *
 * <p>聊天消息引用（J1）与产物引用（J2）共用：按当前账号解析文档 ACL 上下文后批量读取状态。这里刻意不使用
 * {@code knowledge.acl.manage} 的管理性读取旁路——引用展示是员工读取路径，旁路会要求逐文档写
 * {@code knowledge.document.admin_read} 审计；ACL 不可见时统一按 {@code NO_ACCESS} 遮蔽，宁可少展示也不泄露
 * （ADR-0023 第 5.4 节，TM-68）。
 */
@Repository
public class CitationStatusRepository {

    /** 引用状态批量查询。 */
    private final CitationStatusJdbc statusJdbc;

    /** 知识库可见性上下文解析。 */
    private final KnowledgeAccessJdbc accessJdbc;

    /** 文档级 ACL 开关。 */
    private final KnowledgeAclProperties aclProperties;

    /**
     * 创建引用状态仓储。
     *
     * @param statusJdbc 引用状态批量查询
     * @param accessJdbc 知识库可见性上下文解析
     * @param aclProperties 文档级 ACL 开关
     */
    public CitationStatusRepository(
            CitationStatusJdbc statusJdbc, KnowledgeAccessJdbc accessJdbc, KnowledgeAclProperties aclProperties) {
        this.statusJdbc = Objects.requireNonNull(statusJdbc, "statusJdbc must not be null");
        this.accessJdbc = Objects.requireNonNull(accessJdbc, "accessJdbc must not be null");
        this.aclProperties = Objects.requireNonNull(aclProperties, "aclProperties must not be null");
    }

    /**
     * 批量读取引用文档状态。
     *
     * @param tenantId 当前租户
     * @param viewerId 当前账号
     * @param documentIds 引用涉及的知识库文档 ID
     * @return 文档 ID 到状态的映射；ACL 开启时不可见或跨租户文档不返回
     */
    public Map<UUID, CitationSourceState> findStates(TenantId tenantId, UUID viewerId, Collection<UUID> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return Map.of();
        }
        // 引用展示按账号自身 ACL 判定，不传入 unrestricted；ACL 关闭时谓词退化为租户级。
        Map<UUID, CitationSourceState> states = statusJdbc.findStates(
                tenantId,
                accessJdbc.resolve(tenantId, viewerId, false, aclProperties.enforcementEnabled()),
                documentIds);
        if (aclProperties.enforcementEnabled()) {
            return states;
        }
        // ACL 关闭时保持 M03 语义：找不到文档行（历史假引用/已硬删除）不做可见性遮蔽，
        // 只有显式 deleted_at 仍按 DELETED 处理。ACL 开启后缺失行统一按 NO_ACCESS，避免跨租户探测。
        Map<UUID, CitationSourceState> legacy = new LinkedHashMap<>(states);
        for (UUID documentId : documentIds) {
            legacy.putIfAbsent(documentId, new CitationSourceState(true, false, null));
        }
        return legacy;
    }
}
