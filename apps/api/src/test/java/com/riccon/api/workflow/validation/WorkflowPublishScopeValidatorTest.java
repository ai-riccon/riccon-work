package com.riccon.api.workflow.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.workflow.WorkflowRunScope;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 发布/校验共用范围校验器单元测试（ADR-0024 §7.4，F-002）。
 *
 * <p>覆盖：省略范围、ONLY_SELF/TENANT/DEPARTMENT 的角色与部门规则、非 DEPARTMENT 携带部门、
 * DEPARTMENT 缺部门或部门非本人负责；全部失败统一 {@code RICCON-WF-400}。纯 JUnit，不启动 Spring。
 */
class WorkflowPublishScopeValidatorTest {

    /** 测试租户。 */
    private static final TenantId TENANT = TenantId.of("tenant-scope");

    /** 测试账号。 */
    private static final UUID USER = UUID.fromString("01990000-0000-7000-8000-000000009001");

    /** 本人负责的启用部门。 */
    private static final UUID MANAGED_DEPARTMENT = UUID.fromString("01990000-0000-7000-8000-000000009002");

    /** 其他部门。 */
    private static final UUID OTHER_DEPARTMENT = UUID.fromString("01990000-0000-7000-8000-000000009003");

    /** 部门查询桩。 */
    private final FakeSupport support = new FakeSupport();

    /** 被测校验器。 */
    private final WorkflowPublishScopeValidator validator = new WorkflowPublishScopeValidator(support);

    /** 省略范围且无部门：只校图，不查询部门。 */
    @Test
    @DisplayName("resolve_withoutScope_returnsNull")
    void resolve_withoutScope_returnsNull() {
        assertThat(validator.resolve(TENANT, USER, false, null, null)).isNull();

        assertThat(support.departmentQueries).isZero();
    }

    /** 省略范围却带部门：参数组合非法。 */
    @Test
    @DisplayName("resolve_withoutScopeWithDepartment_throws")
    void resolve_withoutScopeWithDepartment_throws() {
        assertScopeError(() -> validator.resolve(TENANT, USER, false, null, MANAGED_DEPARTMENT));
    }

    /** ONLY_SELF：编辑者允许，携带部门非法。 */
    @Test
    @DisplayName("resolve_onlySelf_returnsNull")
    void resolve_onlySelf_returnsNull() {
        assertThat(validator.resolve(TENANT, USER, false, WorkflowRunScope.ONLY_SELF, null))
                .isNull();
    }

    /** ONLY_SELF 携带部门：参数组合非法。 */
    @Test
    @DisplayName("resolve_onlySelfWithDepartment_throws")
    void resolve_onlySelfWithDepartment_throws() {
        assertScopeError(() -> validator.resolve(TENANT, USER, false, WorkflowRunScope.ONLY_SELF, MANAGED_DEPARTMENT));
    }

    /** TENANT：仅租户管理员允许。 */
    @Test
    @DisplayName("resolve_tenantByAdmin_returnsNull")
    void resolve_tenantByAdmin_returnsNull() {
        assertThat(validator.resolve(TENANT, USER, true, WorkflowRunScope.TENANT, null))
                .isNull();
    }

    /** TENANT：编辑者拒绝。 */
    @Test
    @DisplayName("resolve_tenantByNonAdmin_throws")
    void resolve_tenantByNonAdmin_throws() {
        assertScopeError(() -> validator.resolve(TENANT, USER, false, WorkflowRunScope.TENANT, null));
    }

    /** TENANT 携带部门：参数组合非法。 */
    @Test
    @DisplayName("resolve_tenantWithDepartment_throws")
    void resolve_tenantWithDepartment_throws() {
        assertScopeError(() -> validator.resolve(TENANT, USER, true, WorkflowRunScope.TENANT, MANAGED_DEPARTMENT));
    }

    /** DEPARTMENT：租户管理员不能选择。 */
    @Test
    @DisplayName("resolve_departmentByAdmin_throws")
    void resolve_departmentByAdmin_throws() {
        assertScopeError(() -> validator.resolve(TENANT, USER, true, WorkflowRunScope.DEPARTMENT, MANAGED_DEPARTMENT));
    }

    /** DEPARTMENT：缺少部门 ID 拒绝。 */
    @Test
    @DisplayName("resolve_departmentMissingDepartment_throws")
    void resolve_departmentMissingDepartment_throws() {
        assertScopeError(() -> validator.resolve(TENANT, USER, false, WorkflowRunScope.DEPARTMENT, null));
    }

    /** DEPARTMENT：部门不是本人负责的启用部门拒绝。 */
    @Test
    @DisplayName("resolve_departmentNotManaged_throws")
    void resolve_departmentNotManaged_throws() {
        assertScopeError(() -> validator.resolve(TENANT, USER, false, WorkflowRunScope.DEPARTMENT, OTHER_DEPARTMENT));
    }

    /** DEPARTMENT：账号无负责部门时拒绝。 */
    @Test
    @DisplayName("resolve_departmentWithoutManagedDepartment_throws")
    void resolve_departmentWithoutManagedDepartment_throws() {
        support.managedDepartment = null;

        assertScopeError(() -> validator.resolve(TENANT, USER, false, WorkflowRunScope.DEPARTMENT, MANAGED_DEPARTMENT));
    }

    /** DEPARTMENT：本人负责的启用部门通过并返回部门 ID。 */
    @Test
    @DisplayName("resolve_departmentManaged_returnsDepartment")
    void resolve_departmentManaged_returnsDepartment() {
        assertThat(validator.resolve(TENANT, USER, false, WorkflowRunScope.DEPARTMENT, MANAGED_DEPARTMENT))
                .isEqualTo(MANAGED_DEPARTMENT);
        assertThat(support.departmentQueries).isEqualTo(1);
    }

    /**
     * 断言范围失败统一为 {@code RICCON-WF-400}。
     *
     * @param call 待执行调用
     */
    private static void assertScopeError(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(WorkflowScopeValidationException.class)
                .satisfies(exception -> assertThat(((WorkflowScopeValidationException) exception).code())
                        .isEqualTo("RICCON-WF-400"));
    }

    /**
     * 只读部门查询桩。
     *
     * <p>默认账号负责 {@link #MANAGED_DEPARTMENT}；测试可置空模拟无负责部门。
     */
    private static final class FakeSupport implements WorkflowValidationSupport {

        /** 账号负责的启用部门；为空表示没有。 */
        private UUID managedDepartment = MANAGED_DEPARTMENT;

        /** 部门查询次数，用于确认省略范围时不查库。 */
        private int departmentQueries;

        @Override
        public Set<UUID> findAvailableReportModels(Set<UUID> modelIds) {
            return Set.of();
        }

        @Override
        public Map<UUID, WorkflowToolAvailability> toolAvailabilities(TenantId tenantId, Set<UUID> toolIds) {
            return Map.of();
        }

        @Override
        public int countActiveApprovers(TenantId tenantId, WorkflowApproverQuery query) {
            return 0;
        }

        @Override
        public Optional<UUID> findActiveManagedDepartmentId(TenantId tenantId, UUID userId) {
            departmentQueries++;
            return Optional.ofNullable(managedDepartment);
        }
    }
}
