package com.riccon.api.knowledge.controller;

import com.riccon.api.error.BadRequestException;
import com.riccon.api.identity.domain.RequirePermission;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.knowledge.dto.KnowledgeDocumentCreateRequest;
import com.riccon.api.knowledge.dto.KnowledgeDocumentPageResponse;
import com.riccon.api.knowledge.dto.KnowledgeDocumentResponse;
import com.riccon.api.knowledge.service.KnowledgeService;
import com.riccon.api.web.AuthenticationSupport;
import com.riccon.api.web.RequestContext;
import com.riccon.platform.domain.identity.PermissionCodes;
import com.riccon.platform.domain.knowledge.KnowledgeDocumentStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库文档接口。
 *
 * <p>读取要求 {@code knowledge.read}，登记要求 {@code knowledge.manage}；M02 没有文档级 ACL，同租户员工
 * 可检索全部未删除文档。登记只写元数据并投递索引事件，不阻塞等待 Worker。
 */
@RestController
@RequestMapping("/api/v1/knowledge/documents")
public class KnowledgeController {

    /** Idempotency-Key 最小长度。 */
    private static final int MIN_IDEMPOTENCY_KEY_LENGTH = 8;

    /** Idempotency-Key 最大长度。 */
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    /** 知识库服务。 */
    private final KnowledgeService knowledgeService;

    /**
     * 创建控制器。
     *
     * @param knowledgeService 知识库服务
     */
    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    /**
     * 列出知识库文档。
     *
     * @param page 页码
     * @param size 每页大小
     * @param status 可选状态过滤
     * @param authentication 当前认证
     * @return 文档分页
     */
    @GetMapping
    @RequirePermission(PermissionCodes.KNOWLEDGE_READ)
    public KnowledgeDocumentPageResponse list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) KnowledgeDocumentStatus status,
            Authentication authentication) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return knowledgeService.list(actor, PageQuery.of(page, size), status);
    }

    /**
     * 将已上传文件登记为知识库文档。
     *
     * @param body 登记请求
     * @param idempotencyKey 可选幂等键
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 已受理文档
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequirePermission(PermissionCodes.KNOWLEDGE_MANAGE)
    public KnowledgeDocumentResponse create(
            @RequestBody KnowledgeDocumentCreateRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication,
            HttpServletRequest request) {
        validateIdempotencyKey(idempotencyKey);
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return knowledgeService.create(actor, body, RequestContext.traceId(request));
    }

    /**
     * 读取知识库文档。
     *
     * @param documentId 文档 ID
     * @param authentication 当前认证
     * @return 文档
     */
    @GetMapping("/{documentId}")
    @RequirePermission(PermissionCodes.KNOWLEDGE_READ)
    public KnowledgeDocumentResponse get(@PathVariable UUID documentId, Authentication authentication) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return knowledgeService.get(actor, documentId);
    }

    /**
     * 校验可选幂等键长度；同一来源文件重复登记由文件行锁收敛。
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
            throw new BadRequestException("Idempotency-Key must be between 8 and 128 characters");
        }
    }
}
