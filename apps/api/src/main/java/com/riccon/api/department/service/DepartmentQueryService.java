package com.riccon.api.department.service;

import com.riccon.api.auth.service.AuthService;
import com.riccon.api.department.domain.DepartmentRecord;
import com.riccon.api.department.dto.DepartmentMemberPageResponse;
import com.riccon.api.department.dto.DepartmentMemberResponse;
import com.riccon.api.department.dto.DepartmentPageResponse;
import com.riccon.api.department.dto.DepartmentResponse;
import com.riccon.api.department.repository.DepartmentRepository;
import com.riccon.api.error.NotFoundException;
import com.riccon.api.identity.domain.CurrentUser;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.organization.domain.DepartmentSnapshot;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.identity.RoleCodes;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 租户端部门只读服务。
 *
 * <p>普通成员只读取本人所属部门与成员；租户管理员读取本租户全部部门与任意部门成员（只读，用于空间管理），
 * 不提供任何部门写入能力（DEC-0015、ADR-0018 第 1.4/2.3 节）。访问其他部门统一 404，不泄露部门是否存在。
 */
@Service
public class DepartmentQueryService {

    /** 部门读取仓储。 */
    private final DepartmentRepository departmentRepository;

    /** 认证服务，用于读取当前请求的角色快照。 */
    private final AuthService authService;

    /**
     * 创建租户端部门只读服务。
     *
     * @param departmentRepository 部门读取仓储
     * @param authService 认证服务
     */
    public DepartmentQueryService(DepartmentRepository departmentRepository, AuthService authService) {
        this.departmentRepository = Objects.requireNonNull(departmentRepository, "departmentRepository");
        this.authService = Objects.requireNonNull(authService, "authService");
    }

    /**
     * 读取本人部门；租户管理员读取本租户全部部门。
     *
     * @param actor 当前账号
     * @param page 分页参数
     * @return 部门分页
     */
    @Transactional(readOnly = true)
    public DepartmentPageResponse listMine(RicconPrincipal actor, PageQuery page) {
        TenantId tenantId = TenantId.of(actor.tenantId());
        CurrentUser currentUser = authService.loadCurrentUser(actor);
        if (currentUser.roles().contains(RoleCodes.TENANT_ADMIN)) {
            long total = departmentRepository.countDepartments(tenantId);
            List<DepartmentResponse> items =
                    departmentRepository.listDepartments(tenantId, page.size(), page.offset()).stream()
                            .map(DepartmentResponse::from)
                            .toList();
            return new DepartmentPageResponse(page.page(), page.size(), total, page.totalPages(total), items);
        }
        // 普通成员只有 0 或 1 个部门；翻到后续页时返回空列表，保持分页字段一致。
        Optional<DepartmentRecord> own = departmentRepository.findDepartmentRecordOfUser(tenantId, actor.userId());
        List<DepartmentResponse> items =
                own.isEmpty() || page.page() > 1 ? List.of() : List.of(DepartmentResponse.from(own.get()));
        long total = own.isEmpty() ? 0L : 1L;
        return new DepartmentPageResponse(page.page(), page.size(), total, page.totalPages(total), items);
    }

    /**
     * 列出部门成员；普通成员只能访问本人所属部门，租户管理员可访问本租户任意部门。
     *
     * @param actor 当前账号
     * @param departmentId 部门 ID
     * @param page 分页参数
     * @return 成员分页
     * @throws NotFoundException 部门不存在、属于其他租户或其他部门
     */
    @Transactional(readOnly = true)
    public DepartmentMemberPageResponse listMembers(RicconPrincipal actor, UUID departmentId, PageQuery page) {
        TenantId tenantId = TenantId.of(actor.tenantId());
        DepartmentSnapshot department = departmentRepository
                .findDepartment(tenantId, departmentId)
                .orElseThrow(() -> new NotFoundException("department"));
        CurrentUser currentUser = authService.loadCurrentUser(actor);
        if (!currentUser.roles().contains(RoleCodes.TENANT_ADMIN)) {
            boolean ownDepartment = departmentRepository
                    .findDepartmentOfUser(tenantId, actor.userId())
                    .map(own -> own.id().equals(department.id()))
                    .orElse(false);
            if (!ownDepartment) {
                throw new NotFoundException("department");
            }
        }
        long total = departmentRepository.countMembers(tenantId, departmentId);
        List<DepartmentMemberResponse> items =
                departmentRepository.listMembers(tenantId, departmentId, page.size(), page.offset()).stream()
                        .map(DepartmentMemberResponse::from)
                        .toList();
        return new DepartmentMemberPageResponse(page.page(), page.size(), total, page.totalPages(total), items);
    }
}
