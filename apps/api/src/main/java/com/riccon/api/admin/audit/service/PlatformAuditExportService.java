package com.riccon.api.admin.audit.service;

import com.riccon.api.admin.audit.repository.PlatformAuditExportRepository;
import com.riccon.api.audit.service.AuditConflictException;
import com.riccon.api.audit.service.AuditExportPageResponse;
import com.riccon.api.audit.service.AuditExportService;
import com.riccon.api.audit.service.AuditExportView;
import com.riccon.api.error.NotFoundException;
import com.riccon.api.identity.domain.RequestMeta;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.web.Validation;
import com.riccon.platform.common.tenant.TenantId;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 平台管理员审计导出审批（ADR-0020 第 2/3 节）。
 *
 * <p>只服务单管理员租户：目标租户启用状态的 {@code TENANT_ADMIN} 必须 ≤1，否则列表与审批均返回
 * {@code RICCON-AUDIT-409}，必须由同租户另一管理员审批。平台管理员只接触请求元数据，不读取审计内容、
 * 不提供下载；审批审计写到 {@code platform} 并带 {@code details.targetTenantId}。
 */
@Service
public class PlatformAuditExportService {

    /** 租户代码格式，与 OpenAPI TenantId 一致。 */
    private static final Pattern TENANT_CODE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$");

    /** 导出服务：复用租户作用域的列表与审批状态机。 */
    private final AuditExportService auditExportService;

    /** 平台元数据仓储。 */
    private final PlatformAuditExportRepository platformRepository;

    /**
     * 创建平台审计导出审批服务。
     *
     * @param auditExportService 导出服务
     * @param platformRepository 平台元数据仓储
     */
    public PlatformAuditExportService(
            AuditExportService auditExportService, PlatformAuditExportRepository platformRepository) {
        this.auditExportService = Objects.requireNonNull(auditExportService, "auditExportService");
        this.platformRepository = Objects.requireNonNull(platformRepository, "platformRepository");
    }

    /**
     * 列出目标租户导出请求元数据。
     *
     * @param tenantCode 目标租户代码
     * @param status 状态过滤，可为空
     * @param page 页码，可为空
     * @param size 每页大小，可为空
     * @return 导出请求分页
     */
    public AuditExportPageResponse list(String tenantCode, String status, Integer page, Integer size) {
        TenantId targetTenant = requireSingleAdminTenant(tenantCode);
        return auditExportService.list(targetTenant, status, page, size);
    }

    /**
     * 审批目标租户导出请求（仅元数据）。
     *
     * @param tenantCode 目标租户代码
     * @param exportId 导出请求 ID
     * @param decision 决定原文
     * @param comment 审批意见，可为空
     * @param actor 平台管理员
     * @param meta 请求元数据
     * @return 审批后的导出请求
     */
    public AuditExportView decide(
            String tenantCode,
            UUID exportId,
            String decision,
            String comment,
            RicconPrincipal actor,
            RequestMeta meta) {
        TenantId targetTenant = requireSingleAdminTenant(tenantCode);
        return auditExportService.decidePlatform(targetTenant, exportId, decision, comment, actor, meta);
    }

    /**
     * 校验目标租户存在且为单管理员租户。
     *
     * @param tenantCode 目标租户代码
     * @return 目标租户标识
     * @throws NotFoundException 租户不存在
     * @throws AuditConflictException 目标租户有多个启用的租户管理员
     */
    private TenantId requireSingleAdminTenant(String tenantCode) {
        String code = Validation.requirePattern(tenantCode, "tenantId", TENANT_CODE, 64);
        if (!platformRepository.tenantExists(code)) {
            throw new NotFoundException("tenant");
        }
        long admins = platformRepository.countActiveTenantAdmins(code);
        if (admins >= 2) {
            throw new AuditConflictException(
                    "tenant has multiple active tenant admins; the export must be approved inside the tenant");
        }
        return TenantId.of(code);
    }
}
