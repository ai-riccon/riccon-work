package com.riccon.platform.domain.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * 权限码词汇守卫（M19 P4b）。
 *
 * <p>平台层等价下沉：{@code apps/api} 的 {@code RoleDefinitionsTest} 继续断言角色到权限的映射，
 * 这里守卫共享权限码本身（格式、唯一性、常量名一致性、平台/租户前缀边界）。权限码同时出现在迁移字典、
 * OpenAPI {@code x-riccon-permission} 与审计 details 白名单中，改名仍必须先改契约与迁移。
 */
class PermissionCodesTest {

    /** 反射读取 {@link PermissionCodes} 的 public static final String 常量，返回按常量名排序的“名 -> 码值”映射。 */
    private static Map<String, String> permissionCodes() throws IllegalAccessException {
        Map<String, String> codes = new TreeMap<>();
        for (Field field : PermissionCodes.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers) && field.getType() == String.class) {
                codes.put(field.getName(), (String) field.get(null));
            }
        }
        return codes;
    }

    /**
     * 权限码非空且符合 {@code <domain>.<action>} 小写点分格式：至少两段、每段小写字母开头，
     * 允许 {@code tenant.user.manage} 这类多段资源。
     */
    @Test
    void permissionCodes_followLowercaseDotSeparatedFormat() throws IllegalAccessException {
        assertThat(permissionCodes())
                .isNotEmpty()
                .allSatisfy((name, value) -> assertThat(value)
                        .as("权限码 %s", name)
                        .isNotBlank()
                        .matches("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)+$"));
    }

    /** 权限码不得重复；常量名必须等于码值的大写下划线形式（如 TENANT_USER_MANAGE -> tenant.user.manage）。 */
    @Test
    void permissionCodes_areUniqueAndMirrorConstantNames() throws IllegalAccessException {
        Map<String, String> codes = permissionCodes();
        assertThat(codes.values()).doesNotHaveDuplicates();
        assertThat(codes)
                .allSatisfy((name, value) -> assertThat(name)
                        .as("权限码 %s 的常量名", value)
                        .isEqualTo(value.toUpperCase(Locale.ROOT).replace('.', '_')));
    }

    /** 核心权限码必须稳定存在且互不重复，覆盖工作空间、会话、租户管理、部门与审计导出审批边界。 */
    @Test
    void corePermissionCodes_matchContractVocabulary() {
        List<String> coreCodes = List.of(
                PermissionCodes.WORKSPACE_READ,
                PermissionCodes.CONVERSATION_WRITE,
                PermissionCodes.TENANT_USER_MANAGE,
                PermissionCodes.DEPARTMENT_READ,
                PermissionCodes.PLATFORM_DEPARTMENT_MANAGE,
                PermissionCodes.AUDIT_READ,
                PermissionCodes.AUDIT_EXPORT_APPROVE,
                PermissionCodes.PLATFORM_AUDIT_EXPORT_APPROVE);
        assertThat(coreCodes)
                .doesNotHaveDuplicates()
                .containsExactly(
                        "workspace.read",
                        "conversation.write",
                        "tenant.user.manage",
                        "department.read",
                        "platform.department.manage",
                        "audit.read",
                        "audit.export.approve",
                        "platform.audit.export.approve");
    }

    /** 平台管理权限一律 {@code platform.} 前缀，租户业务权限不带该前缀；两组权限码值不重叠。 */
    @Test
    void platformScopedCodes_areDisjointFromTenantCodes() throws IllegalAccessException {
        Map<String, String> codes = permissionCodes();
        for (Map.Entry<String, String> entry : codes.entrySet()) {
            boolean platformNamed = entry.getKey().startsWith("PLATFORM_");
            boolean platformScoped = entry.getValue().startsWith("platform.");
            assertThat(platformScoped)
                    .as("权限码 %s 的 platform. 前缀与常量名不一致", entry.getKey())
                    .isEqualTo(platformNamed);
        }
        assertThat(codes)
                .containsEntry("PLATFORM_DEPARTMENT_MANAGE", "platform.department.manage")
                .containsEntry("DEPARTMENT_READ", "department.read");
        assertThat(codes.values())
                .filteredOn(value -> value.startsWith("platform."))
                .doesNotContain(PermissionCodes.DEPARTMENT_READ, PermissionCodes.WORKSPACE_READ);
    }

    /**
     * M06 新增的 6 个 workflow 权限码必须与契约一致，且全部是租户业务权限。
     *
     * <p>平台管理员不持有任何 workflow 权限，`/workflows/**` 等路径对 {@code tenantId=platform} 统一 404
     * （ADR-0024 §6.1）；因此这里同时锁定集合大小与前缀，防止把 workflow 码误加 {@code platform.} 前缀或
     * 漏掉编辑/发布/审批中的任意一项。
     */
    @Test
    void workflowPermissionCodes_matchM06Contract() throws IllegalAccessException {
        Map<String, String> workflowCodes = new TreeMap<>();
        permissionCodes().forEach((name, value) -> {
            if (name.startsWith("WORKFLOW_")) {
                workflowCodes.put(name, value);
            }
        });

        assertThat(workflowCodes)
                .containsOnly(
                        Map.entry("WORKFLOW_READ", "workflow.read"),
                        Map.entry("WORKFLOW_EDIT", "workflow.edit"),
                        Map.entry("WORKFLOW_PUBLISH", "workflow.publish"),
                        Map.entry("WORKFLOW_RUN", "workflow.run"),
                        Map.entry("WORKFLOW_APPROVE", "workflow.approve"),
                        Map.entry("WORKFLOW_MANAGE", "workflow.manage"));
        assertThat(workflowCodes.values())
                .allSatisfy(code -> assertThat(code)
                        .startsWith("workflow.")
                        // 平台管理员不持 workflow 权限，前缀错配会让网关按平台租户放行。
                        .doesNotStartWith("platform."));
    }
}
