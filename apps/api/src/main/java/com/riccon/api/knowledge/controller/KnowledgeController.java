package com.riccon.api.knowledge.controller;

import com.riccon.api.auth.service.AuthService;
import com.riccon.api.error.BadRequestException;
import com.riccon.api.error.ForbiddenException;
import com.riccon.api.identity.domain.CurrentUser;
import com.riccon.api.identity.domain.PlatformTenant;
import com.riccon.api.identity.domain.RequirePermission;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.knowledge.domain.KnowledgeActor;
import com.riccon.api.knowledge.dto.KnowledgeDocumentAclResponse;
import com.riccon.api.knowledge.dto.KnowledgeDocumentAclUpdate;
import com.riccon.api.knowledge.dto.KnowledgeDocumentCreateRequest;
import com.riccon.api.knowledge.dto.KnowledgeDocumentPageResponse;
import com.riccon.api.knowledge.dto.KnowledgeDocumentResponse;
import com.riccon.api.knowledge.service.KnowledgeAclService;
import com.riccon.api.knowledge.service.KnowledgeBadRequestException;
import com.riccon.api.knowledge.service.KnowledgeDocumentLifecycleService;
import com.riccon.api.knowledge.service.KnowledgeDownload;
import com.riccon.api.knowledge.service.KnowledgeDownloadService;
import com.riccon.api.knowledge.service.KnowledgeService;
import com.riccon.api.web.AuthenticationSupport;
import com.riccon.api.web.RequestContext;
import com.riccon.platform.domain.identity.PermissionCodes;
import com.riccon.platform.domain.knowledge.KnowledgeDocumentStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 知识库文档接口。
 *
 * <p>读取要求 {@code knowledge.read}；登记要求 {@code knowledge.manage}。M05 起 ACL GET/PUT、删除与重索引
 * 的资源判定顺序是“先按租户 + ACL 谓词取文档，不可见统一 404；可见但非创建人且无管理能力才 403”
 * （ADR-0023 第 5.4 节、REV-TASK-0165-001 F-001）。知识读端点因此不设路由级 {@code knowledge.read}
 * 注释，而在 {@link #actor(Authentication)} 统一校验权限；平台管理员（无租户知识权限）可到达服务层并按
 * 跨租户统一 404 返回，避免存在性探测。{@code x-riccon-permission} 仍描述业务能力语义。
 *
 * <p>下载由服务端流式代理，每次请求重判 ACL；不返回对象 key、预签名 URL 或存储端点。
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

    /** ACL 服务。 */
    private final KnowledgeAclService aclService;

    /** 删除/重索引服务。 */
    private final KnowledgeDocumentLifecycleService lifecycleService;

    /** 下载服务。 */
    private final KnowledgeDownloadService downloadService;

    /** 认证服务，用于读取当前权限快照。 */
    private final AuthService authService;

    /**
     * 创建控制器。
     *
     * @param knowledgeService 知识库服务
     * @param aclService ACL 服务
     * @param lifecycleService 生命周期服务
     * @param downloadService 下载服务
     * @param authService 认证服务
     */
    public KnowledgeController(
            KnowledgeService knowledgeService,
            KnowledgeAclService aclService,
            KnowledgeDocumentLifecycleService lifecycleService,
            KnowledgeDownloadService downloadService,
            AuthService authService) {
        this.knowledgeService = knowledgeService;
        this.aclService = aclService;
        this.lifecycleService = lifecycleService;
        this.downloadService = downloadService;
        this.authService = authService;
    }

    /**
     * 列出当前账号可见的知识库文档。
     *
     * @param page 页码
     * @param size 每页大小
     * @param status 可选状态过滤
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 文档分页
     */
    @GetMapping
    public KnowledgeDocumentPageResponse list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) KnowledgeDocumentStatus status,
            Authentication authentication,
            HttpServletRequest request) {
        KnowledgeActor actor = actor(authentication);
        return knowledgeService.list(
                actor,
                PageQuery.of(page, size),
                status,
                RequestContext.traceId(request),
                RequestContext.requestId(request));
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
        KnowledgeActor actor = actor(authentication);
        return knowledgeService.create(actor, body, RequestContext.traceId(request), RequestContext.requestId(request));
    }

    /**
     * 读取知识库文档。
     *
     * @param documentId 文档 ID
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 文档
     */
    @GetMapping("/{documentId}")
    public KnowledgeDocumentResponse get(
            @PathVariable UUID documentId, Authentication authentication, HttpServletRequest request) {
        KnowledgeActor actor = actor(authentication);
        return knowledgeService.get(
                actor, documentId, RequestContext.traceId(request), RequestContext.requestId(request));
    }

    /**
     * 读取文档 ACL。
     *
     * @param documentId 文档 ID
     * @param authentication 当前认证
     * @return ACL
     */
    @GetMapping("/{documentId}/acl")
    public KnowledgeDocumentAclResponse getAcl(@PathVariable UUID documentId, Authentication authentication) {
        return aclService.get(actor(authentication), documentId);
    }

    /**
     * 整体替换文档 ACL。
     *
     * @param documentId 文档 ID
     * @param body 目标 ACL
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 更新后的文档
     */
    @PutMapping("/{documentId}/acl")
    public KnowledgeDocumentResponse putAcl(
            @PathVariable UUID documentId,
            @RequestBody KnowledgeDocumentAclUpdate body,
            Authentication authentication,
            HttpServletRequest request) {
        return aclService.put(
                actor(authentication),
                documentId,
                body,
                RequestContext.traceId(request),
                RequestContext.requestId(request));
    }

    /**
     * 软删除文档并投递索引清理；重复删除幂等。
     *
     * @param documentId 文档 ID
     * @param authentication 当前认证
     * @param request HTTP 请求
     */
    @DeleteMapping("/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID documentId, Authentication authentication, HttpServletRequest request) {
        lifecycleService.delete(
                actor(authentication), documentId, RequestContext.traceId(request), RequestContext.requestId(request));
    }

    /**
     * 下载知识库源文件；服务端流式代理，每请求重判 ACL。
     *
     * @param documentId 文档 ID
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 源文件字节流
     */
    @GetMapping("/{documentId}/download")
    public ResponseEntity<StreamingResponseBody> download(
            @PathVariable UUID documentId, Authentication authentication, HttpServletRequest request) {
        KnowledgeDownload download = downloadService.prepare(
                actor(authentication), documentId, RequestContext.traceId(request), RequestContext.requestId(request));
        StreamingResponseBody body = outputStream -> {
            try (download) {
                download.stream().inputStream().transferTo(outputStream);
            }
        };
        return ResponseEntity.ok()
                .contentType(contentType(download.contentType()))
                .contentLength(download.contentLength())
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(download.filename()))
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }

    /**
     * 重建知识库索引。
     *
     * @param documentId 文档 ID
     * @param force 可选 {@code true} 整本重做；缺省只补缺失页
     * @param idempotencyKey 可选幂等键
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 当前文档
     */
    @PostMapping("/{documentId}/reindex")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public KnowledgeDocumentResponse reindex(
            @PathVariable UUID documentId,
            @RequestParam(required = false) String force,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication,
            HttpServletRequest request) {
        validateIdempotencyKey(idempotencyKey);
        return lifecycleService.reindex(
                actor(authentication), documentId, parseForce(force), RequestContext.traceId(request));
    }

    /**
     * 读取当前权限快照并构造知识库操作者。
     *
     * @param authentication 当前认证
     * @return 操作者
     */
    private KnowledgeActor actor(Authentication authentication) {
        RicconPrincipal principal = AuthenticationSupport.requirePrincipal(authentication);
        CurrentUser current = authService.loadCurrentUser(principal);
        // 平台管理员没有租户知识库权限，但按 ADR-0023 5.4 持有租户文档 ID 时必须返回统一 404；
        // 因此知识读端点不设路由级 knowledge.read 门，由服务层先按租户/ACL 取文档。
        if (!current.hasPermission(PermissionCodes.KNOWLEDGE_READ) && !PlatformTenant.ID.equals(principal.tenantId())) {
            throw new ForbiddenException(PermissionCodes.KNOWLEDGE_READ);
        }
        return new KnowledgeActor(
                principal.userId(),
                principal.tenantId(),
                current.hasPermission(PermissionCodes.KNOWLEDGE_ACL_MANAGE),
                current.hasPermission(PermissionCodes.KNOWLEDGE_MANAGE));
    }

    /**
     * 解析 {@code force} 查询参数。
     *
     * @param value 原始值
     * @return 布尔值
     * @throws KnowledgeBadRequestException 值不是 {@code true}/{@code false}
     */
    private static boolean parseForce(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new KnowledgeBadRequestException("force must be true or false");
        };
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
            throw new BadRequestException("Idempotency-Key length must be between 8 and 128");
        }
    }

    /**
     * 构造安全的内容类型；异常 MIME 回退为八位字节流。
     *
     * @param mimeType 文档 MIME
     * @return 媒体类型
     */
    private static MediaType contentType(String mimeType) {
        try {
            return MediaType.parseMediaType(mimeType);
        } catch (RuntimeException exception) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    /**
     * 构造 RFC 5987 附件头，同时提供 ASCII 回退。
     *
     * @param filename 文件名
     * @return Content-Disposition
     */
    private static String contentDisposition(String filename) {
        String safe = filename == null ? "knowledge-document" : filename;
        String ascii = safe.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "'").replace("\\", "_");
        String encoded = URLEncoder.encode(safe, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }
}
