package com.riccon.api.knowledge.service;

import com.riccon.api.config.KnowledgeAclProperties;
import com.riccon.api.knowledge.repository.KnowledgeRepository;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.knowledge.KnowledgeVisibility;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 文档 ACL 输入校验与规范化。
 *
 * <p>创建与整体替换共用同一规则：{@code TENANT} 不得带部门/名单；{@code DEPT} 必须单个启用部门；
 * {@code USERS} 名单最多 {@code RICCON_KB_ACL_MAX_USERS}（默认 200）人且必须是本租户启用账号；创建人始终隐式
 * 可见并从名单中剔除。任何非法组合返回 {@code RICCON-KB-400}，不静默截断。
 */
@Component
public class KnowledgeAclPolicy {

    /** 契约硬上限：单文档显式授权最多 200 人（OpenAPI {@code maxItems: 200}）。 */
    private static final int CONTRACT_MAX_USERS = 200;

    /** 知识库文档仓储。 */
    private final KnowledgeRepository knowledgeRepository;

    /** ACL 开关与上限。 */
    private final KnowledgeAclProperties properties;

    /**
     * 创建 ACL 校验器。
     *
     * @param knowledgeRepository 知识库文档仓储
     * @param properties ACL 配置
     */
    public KnowledgeAclPolicy(KnowledgeRepository knowledgeRepository, KnowledgeAclProperties properties) {
        this.knowledgeRepository = Objects.requireNonNull(knowledgeRepository, "knowledgeRepository must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    /**
     * 校验并规范化 ACL。
     *
     * @param tenantId 当前租户
     * @param visibilityValue 可见范围字符串；创建时为空表示 {@code TENANT}
     * @param departmentIdValue 部门 ID 字符串
     * @param userIdsValue 用户 ID 字符串列表
     * @param creatorId 创建人；隐式可见，不写入名单
     * @return 规范化 ACL
     */
    public ResolvedAcl resolve(
            TenantId tenantId,
            String visibilityValue,
            String departmentIdValue,
            List<String> userIdsValue,
            UUID creatorId) {
        KnowledgeVisibility visibility = parseVisibility(visibilityValue);
        UUID departmentId = parseOptionalUuid(departmentIdValue, "departmentId");
        List<UUID> userIds = parseUserIds(userIdsValue);
        return switch (visibility) {
            case TENANT -> {
                if (departmentId != null) {
                    throw new KnowledgeBadRequestException("departmentId is only allowed when visibility=DEPT");
                }
                if (userIdsValue != null) {
                    throw new KnowledgeBadRequestException("userIds is only allowed when visibility=USERS");
                }
                yield new ResolvedAcl(visibility, null, List.of());
            }
            case DEPT -> {
                if (departmentId == null) {
                    throw new KnowledgeBadRequestException("departmentId is required when visibility=DEPT");
                }
                if (userIdsValue != null) {
                    throw new KnowledgeBadRequestException("userIds is only allowed when visibility=USERS");
                }
                knowledgeRepository
                        .findActiveDepartment(tenantId, departmentId)
                        .orElseThrow(
                                () -> new KnowledgeBadRequestException("departmentId must be an active department"));
                yield new ResolvedAcl(visibility, departmentId, List.of());
            }
            case USERS -> {
                if (departmentId != null) {
                    throw new KnowledgeBadRequestException("departmentId is only allowed when visibility=DEPT");
                }
                if (userIds.size() > Math.min(properties.maxUsers(), CONTRACT_MAX_USERS)) {
                    throw new KnowledgeBadRequestException(
                            "userIds must not exceed " + Math.min(properties.maxUsers(), CONTRACT_MAX_USERS) + " users");
                }
                Set<UUID> activeUsers = knowledgeRepository.findActiveUserIds(tenantId);
                List<UUID> filtered = new ArrayList<>(userIds.size());
                for (UUID userId : userIds) {
                    if (!activeUsers.contains(userId)) {
                        throw new KnowledgeBadRequestException("userIds must belong to active users of this tenant");
                    }
                    if (!userId.equals(creatorId)) {
                        filtered.add(userId);
                    }
                }
                yield new ResolvedAcl(visibility, null, List.copyOf(filtered));
            }
        };
    }

    /**
     * 解析可见范围；空值按创建默认 {@code TENANT}。
     *
     * @param value 字符串
     * @return 枚举
     */
    private static KnowledgeVisibility parseVisibility(String value) {
        if (value == null || value.isBlank()) {
            return KnowledgeVisibility.TENANT;
        }
        try {
            return KnowledgeVisibility.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new KnowledgeBadRequestException("visibility must be TENANT, DEPT or USERS");
        }
    }

    /**
     * 解析可空 UUID。
     *
     * @param value 字符串
     * @param field 字段名
     * @return UUID；空白返回 {@code null}
     */
    private static UUID parseOptionalUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new KnowledgeBadRequestException(field + " must be a valid UUID");
        }
    }

    /**
     * 解析并校验用户名单：元素必须是 UUID 且不得重复。
     *
     * @param values 字符串列表
     * @return UUID 列表
     */
    private static List<UUID> parseUserIds(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<UUID> unique = new HashSet<>();
        List<UUID> result = new ArrayList<>(values.size());
        for (String value : values) {
            UUID userId;
            try {
                userId = UUID.fromString(value == null ? "" : value.trim());
            } catch (IllegalArgumentException exception) {
                throw new KnowledgeBadRequestException("userIds must contain valid UUIDs");
            }
            if (!unique.add(userId)) {
                throw new KnowledgeBadRequestException("userIds must not contain duplicates");
            }
            result.add(userId);
        }
        return List.copyOf(result);
    }

    /**
     * 规范化后的 ACL。
     *
     * @param visibility 可见范围
     * @param departmentId 部门；仅 {@code DEPT} 非空
     * @param userIds 显式名单；不含创建人
     */
    public record ResolvedAcl(KnowledgeVisibility visibility, UUID departmentId, List<UUID> userIds) {}
}
