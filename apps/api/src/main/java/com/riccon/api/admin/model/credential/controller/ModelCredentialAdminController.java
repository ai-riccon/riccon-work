package com.riccon.api.admin.model.credential.controller;

import com.riccon.api.admin.model.credential.domain.CredentialReasonCodes;
import com.riccon.api.admin.model.credential.domain.ModelCredentialConflictException;
import com.riccon.api.admin.model.credential.domain.ModelCredentialInvalidRequestException;
import com.riccon.api.admin.model.credential.dto.ModelCredentialCreateRequest;
import com.riccon.api.admin.model.credential.dto.ModelCredentialPageResponse;
import com.riccon.api.admin.model.credential.dto.ModelCredentialResponse;
import com.riccon.api.admin.model.credential.dto.ModelCredentialRotateRequest;
import com.riccon.api.admin.model.credential.service.ModelCredentialService;
import com.riccon.api.error.ErrorCodes;
import com.riccon.api.identity.domain.RequestMeta;
import com.riccon.api.identity.domain.RequirePermission;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.model.catalog.web.ModelProblems;
import com.riccon.api.web.ApiProblems;
import com.riccon.api.web.AuthenticationSupport;
import com.riccon.api.web.RequestContext;
import com.riccon.platform.domain.identity.PermissionCodes;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台托管模型 key 池接口（ADR-0021 §7.1/§6.3）。
 *
 * <p>列表只返回掩码与状态；新增/轮换的 {@code apiKey} 为 writeOnly 明文，服务端立即信封加密，响应永不回显。
 * 停用/恢复是幂等状态机操作，{@code changed=false} 表示当前状态已满足、未重复写审计。
 *
 * <p><strong>在途影响</strong>（与前端确认框文案一致）：停用/轮换后新请求不再使用该 key；正在进行的回答
 * 不会被取消，已建立的连接可能继续使用已解密的 key，也可能因上游断开而失败，客户端可重试且不会重复内容。
 * 服务端不删除旧行、不改旧密文，避免破坏在途请求已读取的材料。
 */
@RestController
@RequestMapping("/api/v1/admin/model-providers/{providerId}/credentials")
public class ModelCredentialAdminController {

    /** key 池服务。 */
    private final ModelCredentialService credentialService;

    /**
     * 创建 key 池控制器。
     *
     * @param credentialService key 池服务
     */
    public ModelCredentialAdminController(ModelCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    /**
     * 分页列出 provider 的平台 key（掩码）。
     *
     * @param providerId 供应商 ID
     * @param page 页码
     * @param size 每页大小
     * @return 掩码凭据分页
     */
    @GetMapping
    @RequirePermission(PermissionCodes.PLATFORM_MODEL_READ)
    public ModelCredentialPageResponse listCredentials(
            @PathVariable UUID providerId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return credentialService.listCredentials(providerId, PageQuery.of(page, size));
    }

    /**
     * 新增平台 key。
     *
     * @param providerId 供应商 ID
     * @param body 新增请求；apiKey 只进不出
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 201 与掩码凭据，或字段级 400
     */
    @PostMapping
    @RequirePermission(PermissionCodes.PLATFORM_MODEL_MANAGE)
    public ResponseEntity<Object> createCredential(
            @PathVariable UUID providerId,
            @RequestBody(required = false) ModelCredentialCreateRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        if (body == null) {
            return ModelProblems.invalidField(
                    "apiKey", CredentialReasonCodes.INVALID_API_KEY, "request body is required", request);
        }
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        ModelCredentialResponse created = credentialService.create(providerId, body, actor, meta(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * 轮换平台 key：新增新 key 并停用旧 key；旧 key 的在途连接不被取消。
     *
     * @param providerId 供应商 ID
     * @param credentialId 旧凭据 ID
     * @param body 轮换请求；apiKey 只进不出
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 200 与新 key 的掩码视图，或字段级 400/409
     */
    @PostMapping("/{credentialId}/rotate")
    @RequirePermission(PermissionCodes.PLATFORM_MODEL_MANAGE)
    public ResponseEntity<Object> rotateCredential(
            @PathVariable UUID providerId,
            @PathVariable UUID credentialId,
            @RequestBody(required = false) ModelCredentialRotateRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        if (body == null) {
            return ModelProblems.invalidField(
                    "apiKey", CredentialReasonCodes.INVALID_API_KEY, "request body is required", request);
        }
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        ModelCredentialResponse rotated =
                credentialService.rotate(providerId, credentialId, body, actor, meta(request));
        return ResponseEntity.ok(rotated);
    }

    /**
     * 停用平台 key；幂等，已是停用态返回 changed=false。
     *
     * @param providerId 供应商 ID
     * @param credentialId 凭据 ID
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 200 与停用后的掩码视图
     */
    @PostMapping("/{credentialId}/disable")
    @RequirePermission(PermissionCodes.PLATFORM_MODEL_MANAGE)
    public ResponseEntity<Object> disableCredential(
            @PathVariable UUID providerId,
            @PathVariable UUID credentialId,
            Authentication authentication,
            HttpServletRequest request) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return ResponseEntity.ok(credentialService.disable(providerId, credentialId, actor, meta(request)));
    }

    /**
     * 恢复平台 key；DISABLED 写 enabled、INVALID 写 recovered。
     *
     * @param providerId 供应商 ID
     * @param credentialId 凭据 ID
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 200 与恢复后的掩码视图
     */
    @PostMapping("/{credentialId}/enable")
    @RequirePermission(PermissionCodes.PLATFORM_MODEL_MANAGE)
    public ResponseEntity<Object> enableCredential(
            @PathVariable UUID providerId,
            @PathVariable UUID credentialId,
            Authentication authentication,
            HttpServletRequest request) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return ResponseEntity.ok(credentialService.enable(providerId, credentialId, actor, meta(request)));
    }

    /**
     * 字段级 400：只写 contract 允许的 field/reason，不回显 apiKey 或密文。
     *
     * @param exception 字段级异常
     * @param request HTTP 请求
     * @return ProblemDetail 响应
     */
    @ExceptionHandler(ModelCredentialInvalidRequestException.class)
    public ResponseEntity<Object> handleInvalidRequest(
            ModelCredentialInvalidRequestException exception, HttpServletRequest request) {
        return ModelProblems.invalidField(exception.field(), exception.reason(), exception.getMessage(), request);
    }

    /**
     * 409：fingerprint 重复或并发状态冲突；响应只含稳定 reason。
     *
     * @param exception 冲突异常
     * @param request HTTP 请求
     * @return ProblemDetail 响应
     */
    @ExceptionHandler(ModelCredentialConflictException.class)
    public ResponseEntity<ProblemDetail> handleConflict(
            ModelCredentialConflictException exception, HttpServletRequest request) {
        ProblemDetail problem = ApiProblems.create(
                HttpStatus.CONFLICT,
                ErrorCodes.MODEL_CONFLICT,
                "Conflict",
                exception.getMessage(),
                RequestContext.traceId(request));
        problem.setProperty("reason", exception.reason());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    /**
     * 组装审计请求元数据；管理事件不记录来源 IP。
     *
     * @param request HTTP 请求
     * @return 元数据
     */
    private static RequestMeta meta(HttpServletRequest request) {
        return new RequestMeta(RequestContext.traceId(request), RequestContext.requestId(request), null);
    }
}
