package com.riccon.api.admin.model.gateway.controller;

import com.riccon.api.admin.model.gateway.domain.ModelGatewaySwitchEnvOverrideException;
import com.riccon.api.admin.model.gateway.domain.ModelGatewaySwitchValidationException;
import com.riccon.api.admin.model.gateway.domain.ModelGatewaySwitchVersionConflictException;
import com.riccon.api.admin.model.gateway.dto.ModelGatewaySwitchResponse;
import com.riccon.api.admin.model.gateway.dto.ModelGatewaySwitchUpdateRequest;
import com.riccon.api.admin.model.gateway.service.ModelGatewaySwitchService;
import com.riccon.api.admin.model.gateway.web.GatewaySwitchProblems;
import com.riccon.api.identity.domain.RequestMeta;
import com.riccon.api.identity.domain.RequirePermission;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.web.AuthenticationSupport;
import com.riccon.api.web.RequestContext;
import com.riccon.platform.domain.identity.PermissionCodes;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台模型网关开关接口（ADR-0021 §7.1）。
 *
 * <p>{@code GET /admin/model-gateway/switches} 返回生效开关与环境覆盖；{@code PUT} 整组保存并使用
 * {@code version} 乐观锁。开关是全局平台资源，路径不接受 tenantId；租户管理员与员工没有
 * {@code platform.model.*} 权限，统一 403，响应不包含开关内容。
 */
@RestController
@RequestMapping("/api/v1/admin/model-gateway")
public class ModelGatewaySwitchController {

    /** 网关开关服务。 */
    private final ModelGatewaySwitchService switchService;

    /**
     * 创建开关控制器。
     *
     * @param switchService 网关开关服务
     */
    public ModelGatewaySwitchController(ModelGatewaySwitchService switchService) {
        this.switchService = switchService;
    }

    /**
     * 读取当前生效开关。
     *
     * @return 生效开关、版本与环境覆盖
     */
    @GetMapping("/switches")
    @RequirePermission(PermissionCodes.PLATFORM_MODEL_READ)
    public ModelGatewaySwitchResponse getSwitches() {
        return switchService.read();
    }

    /**
     * 整组保存网关开关。
     *
     * @param body 整组更新请求
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 更新后的生效开关；字段级 400、环境覆盖/版本冲突 409
     */
    @PutMapping("/switches")
    @RequirePermission(PermissionCodes.PLATFORM_MODEL_MANAGE)
    public ResponseEntity<Object> updateSwitches(
            @RequestBody(required = false) ModelGatewaySwitchUpdateRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        if (body == null) {
            return GatewaySwitchProblems.invalidField(
                    "version", "INVALID_VERSION", "request body is required", request);
        }
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        try {
            return ResponseEntity.ok(switchService.update(body, actor, meta(request)));
        } catch (ModelGatewaySwitchValidationException exception) {
            return GatewaySwitchProblems.invalidField(
                    exception.field(), exception.reason(), exception.getMessage(), request);
        } catch (ModelGatewaySwitchEnvOverrideException exception) {
            return GatewaySwitchProblems.envOverride(request);
        } catch (ModelGatewaySwitchVersionConflictException exception) {
            return GatewaySwitchProblems.versionConflict(exception.currentVersion(), request);
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
