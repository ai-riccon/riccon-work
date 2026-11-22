package com.riccon.api.admin.model.provider.controller;

import com.riccon.api.admin.model.provider.dto.ModelProviderPageResponse;
import com.riccon.api.admin.model.provider.dto.ModelProviderUpdateRequest;
import com.riccon.api.admin.model.provider.service.ModelProviderService;
import com.riccon.api.identity.domain.RequirePermission;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.model.catalog.web.ModelProblems;
import com.riccon.platform.common.model.ManagedEndpointPolicy;
import com.riccon.platform.common.model.ManagedEndpointPolicyException;
import com.riccon.platform.domain.identity.PermissionCodes;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台模型供应商/端点目录接口。
 *
 * <p>只读端点返回目录、部署标签、允许状态与平台 key 数量；写接口只允许修改展示名。没有端点创建 API：
 * {@code baseUrl}/{@code environmentLabel}/{@code protocol} 出现即 400，{@code baseUrl} 先按 §2.2 校验
 * URL，结构合法但属于部署管理字段时返回 {@code ENDPOINT_IMMUTABLE_DEPLOYMENT_MANAGED}；两种情况都不发起出网
 * （ADR-0021 §2.4/§7.1）。
 */
@RestController
@RequestMapping("/api/v1/admin/model-providers")
public class ModelProviderAdminController {

    /** 供应商目录服务。 */
    private final ModelProviderService providerService;

    /** 端点 URL 策略。 */
    private final ManagedEndpointPolicy endpointPolicy;

    /**
     * 创建供应商目录控制器。
     *
     * @param providerService 供应商目录服务
     * @param endpointPolicy 端点 URL 策略
     */
    public ModelProviderAdminController(ModelProviderService providerService, ManagedEndpointPolicy endpointPolicy) {
        this.providerService = providerService;
        this.endpointPolicy = endpointPolicy;
    }

    /**
     * 分页列出供应商目录。
     *
     * @param page 页码
     * @param size 每页大小
     * @return 供应商分页
     */
    @GetMapping
    @RequirePermission(PermissionCodes.PLATFORM_MODEL_READ)
    public ModelProviderPageResponse listProviders(
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return providerService.listProviders(PageQuery.of(page, size));
    }

    /**
     * 修改供应商展示名；部署管理字段一律拒绝。
     *
     * @param providerId 供应商 ID
     * @param body 更新请求
     * @param request HTTP 请求，用于 traceId
     * @return 更新后的供应商，或字段级 400
     */
    @PutMapping("/{providerId}")
    @RequirePermission(PermissionCodes.PLATFORM_MODEL_MANAGE)
    public ResponseEntity<Object> updateProvider(
            @PathVariable UUID providerId,
            @RequestBody(required = false) ModelProviderUpdateRequest body,
            HttpServletRequest request) {
        if (body == null) {
            return ModelProblems.invalidField("name", "INVALID_NAME", "request body is required", request);
        }
        if (body.baseUrl() != null) {
            try {
                endpointPolicy.validateUrl(body.baseUrl(), "baseUrl");
            } catch (ManagedEndpointPolicyException exception) {
                return ModelProblems.invalidField(
                        exception.field(),
                        exception.reason().name(),
                        "baseUrl is invalid: " + exception.reason().name(),
                        request);
            }
            return ModelProblems.immutableDeploymentField("baseUrl", request);
        }
        if (body.environmentLabel() != null) {
            return ModelProblems.immutableDeploymentField("environmentLabel", request);
        }
        if (body.protocol() != null) {
            return ModelProblems.immutableDeploymentField("protocol", request);
        }
        if (body.name() == null || body.name().isBlank() || body.name().strip().length() > 200) {
            return ModelProblems.invalidField("name", "INVALID_NAME", "name must be 1..200 characters", request);
        }
        String name = body.name().strip();
        return ResponseEntity.ok(providerService.renameProvider(providerId, name));
    }
}
