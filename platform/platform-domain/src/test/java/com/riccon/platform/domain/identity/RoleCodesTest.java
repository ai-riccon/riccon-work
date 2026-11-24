package com.riccon.platform.domain.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * 角色码词汇守卫（M19 P4b）。
 *
 * <p>本类是 app 断言的 platform 层等价下沉：{@code apps/api} 的 {@code RoleDefinitionsTest} 继续断言
 * 角色到权限的映射与租户开通行为，这里只守卫共享角色码自身的不变量（非空、唯一、命名与平台/租户边界）。
 * app 侧断言不移动、不删除；角色码写入租户角色行与迁移数据，改名仍必须先改 ADR 与迁移。
 */
class RoleCodesTest {

    /** 反射读取 {@link RoleCodes} 的 public static final String 常量，返回按常量名排序的“名 -> 码值”映射。 */
    private static Map<String, String> roleCodes() throws IllegalAccessException {
        Map<String, String> codes = new TreeMap<>();
        for (Field field : RoleCodes.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers) && field.getType() == String.class) {
                codes.put(field.getName(), (String) field.get(null));
            }
        }
        return codes;
    }

    /** 每个角色码非空且符合大写 + 下划线约定；常量名必须等于码值，避免令牌/数据库值与常量名漂移。 */
    @Test
    void roleCodes_followUpperSnakeCaseAndMirrorConstantNames() throws IllegalAccessException {
        assertThat(roleCodes()).isNotEmpty().allSatisfy((name, value) -> {
            assertThat(value).as("角色码 %s", name).isNotBlank().matches("^[A-Z][A-Z0-9_]*$");
            assertThat(value).as("角色码 %s 的常量名与码值", name).isEqualTo(name);
        });
    }

    /** 角色码不得重复，避免角色行与令牌声明出现二义。 */
    @Test
    void roleCodes_areUnique() throws IllegalAccessException {
        assertThat(roleCodes().values()).doesNotHaveDuplicates();
    }

    /** 内置角色码集合必须稳定为 PLATFORM_ADMIN/TENANT_ADMIN/MEMBER/WORKFLOW_EDITOR，不允许未登记的角色码进入共享词汇。 */
    @Test
    void builtInRoleCodes_matchContractVocabulary() throws IllegalAccessException {
        assertThat(roleCodes())
                .containsOnly(
                        Map.entry("PLATFORM_ADMIN", "PLATFORM_ADMIN"),
                        Map.entry("TENANT_ADMIN", "TENANT_ADMIN"),
                        Map.entry("MEMBER", "MEMBER"),
                        // M06 流程编辑者：唯一新增系统角色，由租户管理员通过既有角色管理授予（ADR-0024 §6.1）。
                        Map.entry("WORKFLOW_EDITOR", "WORKFLOW_EDITOR"));
    }

    /**
     * 平台管理员语义边界：平台角色码只有一个且带 {@code PLATFORM_} 前缀，租户系统角色都不带；
     * 平台管理权限一律 {@code platform.} 前缀，且与租户业务内容权限码不重叠。
     *
     * <p>与 {@code apps/api} 的 {@code RoleDefinitionsTest#platformAdminPermissionsDoNotOverlapTenantContent}
     * 等价：该测试断言角色到权限的映射，本测试断言映射所依赖的共享词汇边界，两边同时保留。
     */
    @Test
    void platformAdminVocabulary_isSeparatedFromTenantScope() {
        assertThat(RoleCodes.PLATFORM_ADMIN).isEqualTo("PLATFORM_ADMIN");
        assertThat(List.of(RoleCodes.TENANT_ADMIN, RoleCodes.MEMBER))
                .allSatisfy(roleCode -> assertThat(roleCode).doesNotStartWith("PLATFORM_"));
        assertThat(PermissionCodes.PLATFORM_TENANT_MANAGE).isEqualTo("platform.tenant.manage");
        assertThat(PermissionCodes.PLATFORM_DEPARTMENT_MANAGE).isEqualTo("platform.department.manage");
        assertThat(List.of(
                        PermissionCodes.WORKSPACE_READ,
                        PermissionCodes.CONVERSATION_READ,
                        PermissionCodes.DEPARTMENT_READ))
                .allSatisfy(permissionCode -> assertThat(permissionCode).doesNotStartWith("platform."))
                .doesNotContain(PermissionCodes.PLATFORM_TENANT_MANAGE, PermissionCodes.PLATFORM_DEPARTMENT_MANAGE);
    }
}
