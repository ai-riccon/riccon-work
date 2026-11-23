package com.riccon.api.knowledge.connector.controller;

import com.riccon.api.error.BadRequestException;
import com.riccon.api.identity.domain.RequirePermission;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.knowledge.connector.dto.ConnectorItemPageResponse;
import com.riccon.api.knowledge.connector.dto.ConnectorPageResponse;
import com.riccon.api.knowledge.connector.dto.ConnectorResponse;
import com.riccon.api.knowledge.connector.dto.ConnectorSyncAcceptedResponse;
import com.riccon.api.knowledge.connector.dto.ConnectorTestResponse;
import com.riccon.api.knowledge.connector.service.ConnectorRequestValidator;
import com.riccon.api.knowledge.connector.service.ConnectorService;
import com.riccon.api.knowledge.connector.service.ConnectorSyncService;
import com.riccon.api.knowledge.connector.service.ConnectorTestService;
import com.riccon.api.web.AuthenticationSupport;
import com.riccon.api.web.RequestContext;
import com.riccon.platform.domain.identity.PermissionCodes;
import com.riccon.platform.domain.knowledge.ConnectorItemStatus;
import com.riccon.platform.domain.knowledge.ConnectorStatus;
import com.riccon.platform.domain.knowledge.ConnectorType;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * 知识库连接器接口（契约 {@code 1.5.0-m05b}）。
 *
 * <p>读端点要求 {@code knowledge.connector.read}，管理端点要求 {@code knowledge.connector.manage}（当前仅
 * {@code TENANT_ADMIN}）。跨租户或不存在统一 404，同租户非管理员由权限拦截器返回 403，避免存在性探测。
 *
 * <p>请求体以 {@link JsonNode} 交给服务层校验，确保未知字段、缺省 {@code defaultVisibility} 等契约错误
 * 返回 400 {@code RICCON-KB-400}，而不是被全局 Jackson 配置静默忽略。
 */
@RestController
@RequestMapping("/api/v1/knowledge/connectors")
public class ConnectorController {

    /** Idempotency-Key 最小长度。 */
    private static final int MIN_IDEMPOTENCY_KEY_LENGTH = 8;

    /** Idempotency-Key 最大长度。 */
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    /** CRUD/凭据服务。 */
    private final ConnectorService connectorService;

    /** 连接测试服务。 */
    private final ConnectorTestService testService;

    /** 同步受理服务。 */
    private final ConnectorSyncService syncService;

    /** 请求校验。 */
    private final ConnectorRequestValidator validator;

    /**
     * 创建控制器。
     *
     * @param connectorService CRUD/凭据服务
     * @param testService 连接测试服务
     * @param syncService 同步受理服务
     * @param validator 请求校验
     */
    public ConnectorController(
            ConnectorService connectorService,
            ConnectorTestService testService,
            ConnectorSyncService syncService,
            ConnectorRequestValidator validator) {
        this.connectorService = connectorService;
        this.testService = testService;
        this.syncService = syncService;
        this.validator = validator;
    }

    /**
     * 列出连接器。
     *
     * @param page 页码
     * @param size 每页大小
     * @param status 可选状态
     * @param type 可选类型
     * @param authentication 当前认证
     * @return 连接器分页
     */
    @GetMapping
    @RequirePermission(PermissionCodes.KNOWLEDGE_CONNECTOR_READ)
    public ConnectorPageResponse list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            Authentication authentication) {
        PageQuery pageQuery = ConnectorRequestValidator.page(page, size);
        ConnectorStatus statusFilter = ConnectorRequestValidator.optionalEnum(status, "status", ConnectorStatus.class);
        ConnectorType typeFilter = ConnectorRequestValidator.optionalEnum(type, "type", ConnectorType.class);
        return connectorService.list(principal(authentication), pageQuery, statusFilter, typeFilter);
    }

    /**
     * 创建连接器。
     *
     * @param body 请求体
     * @param idempotencyKey 可选幂等键
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 已创建连接器
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(PermissionCodes.KNOWLEDGE_CONNECTOR_MANAGE)
    public ConnectorResponse create(
            @RequestBody JsonNode body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication,
            HttpServletRequest request) {
        validateIdempotencyKey(idempotencyKey);
        return connectorService.create(
                principal(authentication),
                validator.parseCreate(body),
                RequestContext.traceId(request),
                RequestContext.requestId(request));
    }

    /**
     * 部分更新连接器。
     *
     * @param connectorId 连接器 ID
     * @param body 请求体
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 更新后的连接器
     */
    @PatchMapping("/{connectorId}")
    @RequirePermission(PermissionCodes.KNOWLEDGE_CONNECTOR_MANAGE)
    public ConnectorResponse update(
            @PathVariable UUID connectorId,
            @RequestBody JsonNode body,
            Authentication authentication,
            HttpServletRequest request) {
        return connectorService.update(
                principal(authentication),
                connectorId,
                validator.parseUpdate(body),
                RequestContext.traceId(request),
                RequestContext.requestId(request));
    }

    /**
     * 软删除连接器。
     *
     * @param connectorId 连接器 ID
     * @param documents 已导入文档处置
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 204
     */
    @DeleteMapping("/{connectorId}")
    @RequirePermission(PermissionCodes.KNOWLEDGE_CONNECTOR_MANAGE)
    public ResponseEntity<Void> delete(
            @PathVariable UUID connectorId,
            @RequestParam(required = false) String documents,
            Authentication authentication,
            HttpServletRequest request) {
        connectorService.delete(
                principal(authentication),
                connectorId,
                documents,
                RequestContext.traceId(request),
                RequestContext.requestId(request));
        return ResponseEntity.noContent().build();
    }

    /**
     * 写入或轮换凭据。
     *
     * @param connectorId 连接器 ID
     * @param body 请求体
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 仅掩码的连接器
     */
    @PutMapping("/{connectorId}/credential")
    @RequirePermission(PermissionCodes.KNOWLEDGE_CONNECTOR_MANAGE)
    public ConnectorResponse putCredential(
            @PathVariable UUID connectorId,
            @RequestBody JsonNode body,
            Authentication authentication,
            HttpServletRequest request) {
        return connectorService.putCredential(
                principal(authentication),
                connectorId,
                validator.parseCredentialBody(body),
                RequestContext.traceId(request),
                RequestContext.requestId(request));
    }

    /**
     * 连接测试。
     *
     * @param connectorId 连接器 ID
     * @param authentication 当前认证
     * @return 测试结果
     */
    @PostMapping("/{connectorId}/test")
    @RequirePermission(PermissionCodes.KNOWLEDGE_CONNECTOR_MANAGE)
    public ConnectorTestResponse test(@PathVariable UUID connectorId, Authentication authentication) {
        ConnectorTestService.TestResult result = testService.test(principal(authentication), connectorId);
        return ConnectorTestResponse.from(result.outcome(), result.testedAt());
    }

    /**
     * 立即同步。
     *
     * @param connectorId 连接器 ID
     * @param idempotencyKey 可选幂等键
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 202 受理
     */
    @PostMapping("/{connectorId}/sync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequirePermission(PermissionCodes.KNOWLEDGE_CONNECTOR_MANAGE)
    public ConnectorSyncAcceptedResponse sync(
            @PathVariable UUID connectorId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication,
            HttpServletRequest request) {
        validateIdempotencyKey(idempotencyKey);
        return syncService.requestSync(
                principal(authentication),
                connectorId,
                RequestContext.traceId(request),
                RequestContext.requestId(request));
    }

    /**
     * 分页查询同步条目。
     *
     * @param connectorId 连接器 ID
     * @param page 页码
     * @param size 每页大小
     * @param status 可选条目状态
     * @param authentication 当前认证
     * @return 条目分页
     */
    @GetMapping("/{connectorId}/items")
    @RequirePermission(PermissionCodes.KNOWLEDGE_CONNECTOR_READ)
    public ConnectorItemPageResponse items(
            @PathVariable UUID connectorId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String status,
            Authentication authentication) {
        PageQuery pageQuery = ConnectorRequestValidator.page(page, size);
        ConnectorItemStatus statusFilter =
                ConnectorRequestValidator.optionalEnum(status, "status", ConnectorItemStatus.class);
        return connectorService.listItems(principal(authentication), connectorId, pageQuery, statusFilter);
    }

    /**
     * 读取当前主体。
     *
     * @param authentication 当前认证
     * @return 主体
     */
    private static RicconPrincipal principal(Authentication authentication) {
        return AuthenticationSupport.requirePrincipal(authentication);
    }

    /**
     * 校验可选幂等键长度；连接器写入以数据库唯一键/行锁收敛重复请求。
     *
     * @param idempotencyKey 请求头
     * @throws BadRequestException 长度不在 8–128
     */
    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null) {
            return;
        }
        if (idempotencyKey.length() < MIN_IDEMPOTENCY_KEY_LENGTH
                || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new BadRequestException("Idempotency-Key length must be between 8 and 128");
        }
    }
}
