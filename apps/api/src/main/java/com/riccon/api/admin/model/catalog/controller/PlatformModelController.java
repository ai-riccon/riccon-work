package com.riccon.api.admin.model.catalog.controller;

import com.riccon.api.admin.model.catalog.dto.AdminModelPageResponse;
import com.riccon.api.admin.model.catalog.dto.AdminModelResponse;
import com.riccon.api.admin.model.catalog.dto.ModelStatusUpdateRequest;
import com.riccon.api.admin.model.catalog.service.PlatformModelService;
import com.riccon.api.identity.domain.RequestMeta;
import com.riccon.api.identity.domain.RequirePermission;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.model.catalog.web.ModelProblems;
import com.riccon.api.web.AuthenticationSupport;
import com.riccon.api.web.RequestContext;
import com.riccon.platform.domain.identity.PermissionCodes;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台模型目录接口。
 *
 * <p>{@code GET /admin/models} 只读目录（含 provider、价格与定价状态）；{@code PATCH /admin/models/{modelId}}
 * 只改启停状态并写 {@code model.updated} 审计。价格没有写接口，创建模型也不在 M04 范围（ADR-0021 §7.1）。
 */
@RestController
@RequestMapping("/api/v1/admin/models")
public class PlatformModelController {

    /** 允许的模型状态。 */
    private static final Set<String> ALLOWED_STATUSES = Set.of("ACTIVE", "DISABLED");

    /** 平台模型服务。 */
    private final PlatformModelService modelService;

    /**
     * 创建平台模型目录控制器。
     *
     * @param modelService 平台模型服务
     */
    public PlatformModelController(PlatformModelService modelService) {
        this.modelService = modelService;
    }

    /**
     * 分页列出平台模型目录。
     *
     * @param page 页码
     * @param size 每页大小
     * @return 模型分页
     */
    @GetMapping
    @RequirePermission(PermissionCodes.PLATFORM_MODEL_READ)
    public AdminModelPageResponse listModels(
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return modelService.listModels(PageQuery.of(page, size));
    }

    /**
     * 启用或停用模型；只接受 ACTIVE/DISABLED。
     *
     * @param modelId 模型 ID
     * @param body 状态请求
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 更新后的模型，或字段级 400
     */
    @PatchMapping("/{modelId}")
    @RequirePermission(PermissionCodes.PLATFORM_MODEL_MANAGE)
    public ResponseEntity<Object> updateStatus(
            @PathVariable UUID modelId,
            @RequestBody(required = false) ModelStatusUpdateRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        if (body == null || body.status() == null) {
            return ModelProblems.invalidField("status", "INVALID_STATUS", "status is required", request);
        }
        String status = body.status().strip().toUpperCase(Locale.ROOT);
        if (!ALLOWED_STATUSES.contains(status)) {
            return ModelProblems.invalidField("status", "INVALID_STATUS", "status must be ACTIVE or DISABLED", request);
        }
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        AdminModelResponse updated = modelService.updateStatus(modelId, status, actor, meta(request));
        return ResponseEntity.ok(updated);
    }

    /**
     * 组装审计请求元数据；管理事件不记录来源 IP。
     *
     * @param request HTTP 请求
     * @return 元数据
     */
    private RequestMeta meta(HttpServletRequest request) {
        return new RequestMeta(RequestContext.traceId(request), RequestContext.requestId(request), null);
    }
}
