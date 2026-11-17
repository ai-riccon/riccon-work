package com.riccon.api.file.controller;

import com.riccon.api.error.BadRequestException;
import com.riccon.api.file.dto.DownloadUrlResponse;
import com.riccon.api.file.dto.FilePageResponse;
import com.riccon.api.file.dto.FileResponse;
import com.riccon.api.file.service.FileService;
import com.riccon.api.identity.domain.RequirePermission;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.web.AuthenticationSupport;
import com.riccon.api.web.RequestContext;
import com.riccon.platform.domain.identity.PermissionCodes;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件接口。
 *
 * <p>上传使用 multipart/form-data；服务端先写对象存储，再在同一事务写元数据、审计与解析 outbox。
 * 下载只返回 300 秒内的预签名 URL，客户端不能指定对象 key。重试仅对 FAILED 状态生效。
 */
@RestController
@RequestMapping("/api/v1")
public class FileController {

    /** Idempotency-Key 最小长度。 */
    private static final int MIN_IDEMPOTENCY_KEY_LENGTH = 8;

    /** Idempotency-Key 最大长度。 */
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    /** 文件服务。 */
    private final FileService fileService;

    /**
     * 创建控制器。
     *
     * @param fileService 文件服务
     */
    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    /**
     * 列出工作空间文件。
     *
     * @param workspaceId 工作空间 ID
     * @param conversationId 可选会话过滤
     * @param page 页码
     * @param size 每页大小
     * @param authentication 当前认证
     * @return 文件分页
     */
    @GetMapping("/workspaces/{workspaceId}/files")
    @RequirePermission(PermissionCodes.FILE_READ)
    public FilePageResponse list(
            @PathVariable UUID workspaceId,
            @RequestParam(required = false) UUID conversationId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            Authentication authentication) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return fileService.list(actor, workspaceId, conversationId, PageQuery.of(page, size));
    }

    /**
     * 上传文件。
     *
     * @param workspaceId 工作空间 ID
     * @param file 文件
     * @param conversationId 可选会话 ID
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 已受理的文件
     */
    @PostMapping(path = "/workspaces/{workspaceId}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(PermissionCodes.FILE_UPLOAD)
    public FileResponse upload(
            @PathVariable UUID workspaceId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "conversationId", required = false) UUID conversationId,
            Authentication authentication,
            HttpServletRequest request) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return fileService.upload(
                actor,
                workspaceId,
                conversationId,
                file,
                RequestContext.traceId(request),
                RequestContext.requestId(request));
    }

    /**
     * 读取文件元数据与解析状态。
     *
     * @param fileId 文件 ID
     * @param authentication 当前认证
     * @return 文件
     */
    @GetMapping("/files/{fileId}")
    @RequirePermission(PermissionCodes.FILE_READ)
    public FileResponse get(@PathVariable UUID fileId, Authentication authentication) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return fileService.get(actor, fileId);
    }

    /**
     * 删除文件（软删除）。
     *
     * @param fileId 文件 ID
     * @param authentication 当前认证
     * @param request HTTP 请求
     */
    @DeleteMapping("/files/{fileId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission(PermissionCodes.FILE_DELETE)
    public void delete(@PathVariable UUID fileId, Authentication authentication, HttpServletRequest request) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        fileService.delete(actor, fileId, RequestContext.traceId(request), RequestContext.requestId(request));
    }

    /**
     * 获取文件下载地址。
     *
     * @param fileId 文件 ID
     * @param authentication 当前认证
     * @return 预签名地址
     */
    @GetMapping("/files/{fileId}/download-url")
    @RequirePermission(PermissionCodes.FILE_READ)
    public DownloadUrlResponse downloadUrl(@PathVariable UUID fileId, Authentication authentication) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return fileService.downloadUrl(actor, fileId);
    }

    /**
     * 重试失败的文件解析。
     *
     * @param fileId 文件 ID
     * @param idempotencyKey 可选幂等键；当前以解析状态串行化重试
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 新解析尝试下的文件
     */
    @PostMapping("/files/{fileId}/parse-retries")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequirePermission(PermissionCodes.FILE_UPLOAD)
    public FileResponse retryParse(
            @PathVariable UUID fileId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication,
            HttpServletRequest request) {
        validateIdempotencyKey(idempotencyKey);
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return fileService.retryParse(actor, fileId, RequestContext.traceId(request));
    }

    /**
     * 校验可选幂等键长度；重试本身由失败状态与文件行锁串行化。
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
