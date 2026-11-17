package com.riccon.platform.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import com.riccon.platform.domain.chat.CitationSourceType;
import com.riccon.platform.domain.chat.LocatorType;
import com.riccon.platform.domain.chat.MessageRole;
import com.riccon.platform.domain.chat.MessageStatus;
import com.riccon.platform.domain.contract.EventTypes;
import com.riccon.platform.domain.file.FileFormat;
import com.riccon.platform.domain.file.FileStatus;
import com.riccon.platform.domain.file.ParseStatus;
import com.riccon.platform.domain.identity.AuthSource;
import com.riccon.platform.domain.identity.PermissionCodes;
import com.riccon.platform.domain.identity.RoleCodes;
import com.riccon.platform.domain.identity.UserStatus;
import com.riccon.platform.domain.knowledge.KnowledgeDocumentStatus;
import com.riccon.platform.domain.mcp.McpInvocationStatus;
import com.riccon.platform.domain.mcp.McpToolRisk;
import com.riccon.platform.domain.mcp.McpTransport;
import com.riccon.platform.domain.model.InvocationStatus;
import com.riccon.platform.domain.model.ModelPurpose;
import com.riccon.platform.domain.research.ApprovalDecision;
import com.riccon.platform.domain.research.ApprovalStatus;
import com.riccon.platform.domain.research.ArtifactStatus;
import com.riccon.platform.domain.research.ResearchTaskStatus;
import com.riccon.platform.domain.research.RunKind;
import com.riccon.platform.domain.research.RunStage;
import com.riccon.platform.domain.research.RunStatus;
import com.riccon.platform.domain.research.StageStatus;
import com.riccon.platform.domain.workspace.WorkspaceKind;
import com.riccon.platform.domain.workspace.WorkspaceStatus;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 领域词汇守卫。
 *
 * <p>本测试不是业务功能测试，而是契约测试：确保共享枚举被加载、关键状态值稳定、常量无重复。
 * 这些值同时出现在 Flyway CHECK 约束、OpenAPI 与事件 schema 中，改动必须先改契约与 ADR。
 */
class DomainVocabularyTest {

    /** 所有共享枚举；加载它们同时验证枚举值未被意外清空。 */
    private static final List<Class<? extends Enum<?>>> STATUS_ENUMS = List.of(
            UserStatus.class,
            AuthSource.class,
            WorkspaceKind.class,
            WorkspaceStatus.class,
            FileFormat.class,
            FileStatus.class,
            ParseStatus.class,
            MessageRole.class,
            MessageStatus.class,
            CitationSourceType.class,
            LocatorType.class,
            KnowledgeDocumentStatus.class,
            ResearchTaskStatus.class,
            RunStatus.class,
            RunKind.class,
            RunStage.class,
            StageStatus.class,
            ApprovalStatus.class,
            ApprovalDecision.class,
            ArtifactStatus.class,
            ModelPurpose.class,
            InvocationStatus.class,
            McpTransport.class,
            McpToolRisk.class,
            McpInvocationStatus.class,
            AuditActorType.class,
            AuditOutcome.class,
            AuditTargetType.class);

    /** 共享枚举至少有一个值，防止空枚举导致契约与迁移失去约束。 */
    @Test
    void statusEnumsAreNotEmpty() {
        assertThat(STATUS_ENUMS)
                .allSatisfy(enumType -> assertThat(enumType.getEnumConstants())
                        .as(enumType.getSimpleName())
                        .isNotEmpty());
    }

    /** 关键状态值与迁移 CHECK、OpenAPI 枚举保持一致。 */
    @Test
    void criticalStatusValuesAreStable() {
        assertThat(names(UserStatus.values())).containsExactly("ACTIVE", "DISABLED");
        assertThat(names(RunStatus.values()))
                .containsExactly(
                        "PENDING", "RUNNING", "WAITING_APPROVAL", "RETRY_WAIT", "SUCCEEDED", "FAILED", "CANCELLED");
        assertThat(names(RunStage.values())).containsExactly("RETRIEVAL", "TOOL", "APPROVAL", "GENERATION");
        assertThat(names(ApprovalStatus.values()))
                .containsExactly("PENDING", "APPROVED", "REJECTED", "EXPIRED", "CANCELLED");
        assertThat(names(MessageStatus.values()))
                .containsExactly("PENDING", "STREAMING", "COMPLETED", "STOPPED", "FAILED");
    }

    /** 权限码、角色码、审计动作与事件名不得重复，避免 RBAC 或消费者歧义。 */
    @Test
    void contractConstantsAreUnique() throws IllegalAccessException {
        assertThat(stringConstants(PermissionCodes.class)).doesNotHaveDuplicates();
        assertThat(stringConstants(RoleCodes.class)).doesNotHaveDuplicates();
        assertThat(stringConstants(AuditActions.class)).doesNotHaveDuplicates();
        assertThat(stringConstants(EventTypes.class)).doesNotHaveDuplicates();
    }

    /** 事件名必须符合 {@code riccon.<domain>.<event>.v<n>} 约定。 */
    @Test
    void eventNamesFollowVersionedPattern() throws IllegalAccessException {
        assertThat(stringConstants(EventTypes.class))
                .allSatisfy(
                        eventType -> assertThat(eventType).matches("^riccon\\.[a-z0-9]+(\\.[a-z0-9]+)+\\.v[0-9]+$"));
    }

    /** 提取枚举名，便于逐项断言。 */
    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }

    /** 读取接口中的 public static final String 常量。 */
    private static List<String> stringConstants(Class<?> type) throws IllegalAccessException {
        List<String> values = new java.util.ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                values.add((String) field.get(null));
            }
        }
        return values;
    }
}
