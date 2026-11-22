package com.riccon.platform.domain.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * 审计词汇守卫（M19 P4b）。
 *
 * <p>平台层等价下沉：{@code apps/api} 与 {@code apps/worker} 的 {@code AuditDetailsPolicyTest} 继续断言
 * 各自 details 白名单行为，这里守卫共享词汇（动作名、actor/outcome/target 枚举）的格式、唯一性与稳定性。
 * 审计动作与目标类型写入 append-only 审计表并被查询/导出读取，改名或删除仍必须先改 ADR 与迁移。
 */
class AuditVocabularyTest {

    /** 反射读取 {@link AuditActions} 的 public static final String 常量，返回按常量名排序的“名 -> 动作名”映射。 */
    private static Map<String, String> actions() throws IllegalAccessException {
        Map<String, String> actions = new TreeMap<>();
        for (Field field : AuditActions.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers) && field.getType() == String.class) {
                actions.put(field.getName(), (String) field.get(null));
            }
        }
        return actions;
    }

    /** 读取枚举常量名，便于逐项断言。 */
    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }

    /**
     * 审计动作非空且符合 {@code <domain>.<event>} 小写点分格式，禁止空格与大写片段。
     *
     * <p>段内允许下划线：ADR-0023 §5.6 的 {@code knowledge.document.admin_read} 是已批准动作名；
     * 其余动作继续保持纯小写点分。
     */
    @Test
    void auditActions_followLowercaseDotSeparatedFormat() throws IllegalAccessException {
        assertThat(actions())
                .isNotEmpty()
                .allSatisfy((name, value) -> assertThat(value)
                        .as("审计动作 %s", name)
                        .isNotBlank()
                        .matches("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$"));
    }

    /** 审计动作不得重复；常量名必须等于动作名的大写下划线形式（如 USER_CREATED -> user.created）。 */
    @Test
    void auditActions_areUniqueAndMirrorConstantNames() throws IllegalAccessException {
        Map<String, String> actions = actions();
        assertThat(actions.values()).doesNotHaveDuplicates();
        assertThat(actions)
                .allSatisfy((name, value) -> assertThat(name)
                        .as("审计动作 %s 的常量名", value)
                        .isEqualTo(value.toUpperCase(Locale.ROOT).replace('.', '_')));
    }

    /**
     * app 测试引用的动作必须稳定存在：API/Worker 的 details 白名单以这些常量作为键，
     * 缺失会让审计查询/导出出现“未知动作”。断言同时锁定动作名原文。
     */
    @Test
    void appReferencedAuditActions_matchContractVocabulary() throws IllegalAccessException {
        assertThat(actions())
                .containsEntry("AUTH_LOGIN_SUCCEEDED", "auth.login.succeeded")
                .containsEntry("AUTH_LOGIN_FAILED", "auth.login.failed")
                .containsEntry("MODEL_INVOCATION_STARTED", "model.invocation.started")
                .containsEntry("MODEL_INVOCATION_COMPLETED", "model.invocation.completed")
                .containsEntry("MODEL_INVOCATION_FAILED", "model.invocation.failed")
                .containsEntry("USER_CREATED", "user.created")
                .containsEntry("FILE_UPLOADED", "file.uploaded")
                .containsEntry("MCP_TOOL_INVOKED", "mcp.tool.invoked")
                .containsEntry("MODEL_ROUTING_UPDATED", "model.routing.updated")
                .containsEntry("QUOTA_EXCEEDED", "quota.exceeded")
                .containsEntry("AUDIT_EXPORT_APPROVED", "audit.export.approved");
    }

    /** actor 类型必须稳定为 USER/SERVICE/SYSTEM，平台事件不得用用户名或邮箱等 PII 充当 actor。 */
    @Test
    void auditActorType_isStable() {
        assertThat(names(AuditActorType.values()))
                .doesNotHaveDuplicates()
                .containsExactly("USER", "SERVICE", "SYSTEM")
                .allSatisfy(name -> assertThat(name).matches("^[A-Z][A-Z0-9_]*$"));
    }

    /** outcome 必须稳定为 SUCCESS/FAILURE/DENIED；跨租户/越权拒绝使用 DENIED，不允许新增同义状态。 */
    @Test
    void auditOutcome_isStable() {
        assertThat(names(AuditOutcome.values()))
                .doesNotHaveDuplicates()
                .containsExactly("SUCCESS", "FAILURE", "DENIED")
                .allSatisfy(name -> assertThat(name).matches("^[A-Z][A-Z0-9_]*$"));
    }

    /** target 类型必须覆盖 ADR-0007/ADR-0018 列出的全部目标，且枚举名保持大写 + 下划线。 */
    @Test
    void auditTargetType_isStable() {
        assertThat(names(AuditTargetType.values()))
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrder(
                        "SESSION",
                        "WORKSPACE",
                        "DEPARTMENT",
                        "FILE",
                        "CONVERSATION",
                        "KNOWLEDGE_DOCUMENT",
                        "RESEARCH_TASK",
                        "RUN",
                        "ARTIFACT",
                        "MODEL",
                        "MODEL_ROUTING",
                        "MODEL_CREDENTIAL",
                        "QUOTA",
                        "MCP_TOOL",
                        "USER",
                        "ROLE",
                        "TENANT",
                        "AUDIT",
                        "AUDIT_EXPORT",
                        "KNOWLEDGE_CONNECTOR")
                .allSatisfy(name -> assertThat(name).matches("^[A-Z][A-Z0-9_]*$"));
    }
}
