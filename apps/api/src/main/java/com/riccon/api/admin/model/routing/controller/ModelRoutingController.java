package com.riccon.api.admin.model.routing.controller;

import com.riccon.api.admin.model.routing.domain.ModelRoutingValidationException;
import com.riccon.api.admin.model.routing.domain.ModelRoutingVersionConflictException;
import com.riccon.api.admin.model.routing.dto.ModelRoutingListResponse;
import com.riccon.api.admin.model.routing.dto.ModelRoutingResponse;
import com.riccon.api.admin.model.routing.dto.ModelRoutingUpdateRequest;
import com.riccon.api.admin.model.routing.service.ModelRoutingService;
import com.riccon.api.admin.model.routing.web.RoutingProblems;
import com.riccon.api.identity.domain.RequestMeta;
import com.riccon.api.identity.domain.RequirePermission;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.model.catalog.web.ModelProblems;
import com.riccon.api.web.AuthenticationSupport;
import com.riccon.api.web.RequestContext;
import com.riccon.platform.domain.identity.PermissionCodes;
import com.riccon.platform.domain.model.ModelPurpose;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台模型路由配置接口。
 *
 * <p>{@code GET /admin/model-routing} 返回 CHAT/REPORT 两条链；{@code PUT /admin/model-routing/{purpose}}
 * 整链保存并使用 {@code version} 乐观锁。租户管理员与员工没有 {@code platform.model.*} 权限，统一 403；
 * 路由是全局平台资源，路径不接受 tenantId（ADR-0021 §4.3/§7.1）。
 */
@RestController
@RequestMapping("/api/v1/admin/model-routing")
public class ModelRoutingController {

    /** 平台模型路由服务。 */
    private final ModelRoutingService routingService;

    /**
     * 创建路由控制器。
     *
     * @param routingService 平台模型路由服务
     */
    public ModelRoutingController(ModelRoutingService routingService) {
        this.routingService = routingService;
    }

    /**
     * 读取两条路由链。
     *
     * @return CHAT、REPORT 两条链
     */
    @GetMapping
    @RequirePermission(PermissionCodes.PLATFORM_MODEL_READ)
    public ModelRoutingListResponse listChains() {
        return routingService.listChains();
    }

    /**
     * 整链保存指定用途的路由链。
     *
     * @param purpose 用途路径参数
     * @param body 更新请求
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 保存后的链；字段级 400 或版本冲突 409
     */
    @PutMapping("/{purpose}")
    @RequirePermission(PermissionCodes.PLATFORM_MODEL_MANAGE)
    public ResponseEntity<Object> updateChain(
            @PathVariable String purpose,
            @RequestBody(required = false) ModelRoutingUpdateRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        ModelPurpose parsedPurpose;
        try {
            parsedPurpose = ModelPurpose.valueOf(purpose.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return ModelProblems.invalidField("purpose", "INVALID_PURPOSE", "purpose must be CHAT or REPORT", request);
        }
        if (body == null) {
            return ModelProblems.invalidField(
                    "version", "INVALID_VERSION", "version and modelIds are required", request);
        }
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        try {
            ModelRoutingResponse saved =
                    routingService.saveChain(parsedPurpose, body.version(), body.modelIds(), actor, meta(request));
            return ResponseEntity.ok(saved);
        } catch (ModelRoutingValidationException exception) {
            return ModelProblems.invalidField(exception.field(), exception.reason(), exception.getMessage(), request);
        } catch (ModelRoutingVersionConflictException exception) {
            return RoutingProblems.versionConflict(exception.currentVersion(), request);
        }
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
