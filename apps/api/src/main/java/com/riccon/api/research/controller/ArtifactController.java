package com.riccon.api.research.controller;

import com.riccon.api.file.dto.DownloadUrlResponse;
import com.riccon.api.identity.domain.RequirePermission;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.research.dto.ArtifactContent;
import com.riccon.api.research.dto.ArtifactDetailResponse;
import com.riccon.api.research.dto.ArtifactPageResponse;
import com.riccon.api.research.dto.ArtifactVersionPageResponse;
import com.riccon.api.research.dto.ArtifactVersionResponse;
import com.riccon.api.research.service.ArtifactService;
import com.riccon.api.web.AuthenticationSupport;
import com.riccon.api.web.RequestContext;
import com.riccon.platform.domain.identity.PermissionCodes;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 产物接口。
 *
 * <p>全部要求 {@code artifact.read}。下载地址只返回短期预签名 URL，绑定服务端对象 key；客户端不能传入 key，
 * 也不能直接访问对象存储（ADR-0014 第 2 节）。正文优先走同源 content 端点，避免浏览器跨域读取对象存储。
 */
@RestController
@RequestMapping("/api/v1")
public class ArtifactController {

    /** 产物服务。 */
    private final ArtifactService artifactService;

    /**
     * 创建控制器。
     *
     * @param artifactService 产物服务
     */
    public ArtifactController(ArtifactService artifactService) {
        this.artifactService = artifactService;
    }

    /**
     * 列出工作空间产物。
     *
     * @param workspaceId 工作空间 ID
     * @param page 页码
     * @param size 每页大小
     * @param authentication 当前认证
     * @return 产物分页
     */
    @GetMapping("/workspaces/{workspaceId}/artifacts")
    @RequirePermission(PermissionCodes.ARTIFACT_READ)
    public ArtifactPageResponse list(
            @PathVariable UUID workspaceId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            Authentication authentication) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return artifactService.list(actor, workspaceId, PageQuery.of(page, size));
    }

    /**
     * 读取产物与当前版本。
     *
     * @param artifactId 产物 ID
     * @param authentication 当前认证
     * @return 产物详情
     */
    @GetMapping("/artifacts/{artifactId}")
    @RequirePermission(PermissionCodes.ARTIFACT_READ)
    public ArtifactDetailResponse get(@PathVariable UUID artifactId, Authentication authentication) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return artifactService.get(actor, artifactId);
    }

    /**
     * 列出版本。
     *
     * @param artifactId 产物 ID
     * @param page 页码
     * @param size 每页大小
     * @param authentication 当前认证
     * @return 版本分页
     */
    @GetMapping("/artifacts/{artifactId}/versions")
    @RequirePermission(PermissionCodes.ARTIFACT_READ)
    public ArtifactVersionPageResponse listVersions(
            @PathVariable UUID artifactId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            Authentication authentication) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return artifactService.listVersions(actor, artifactId, PageQuery.of(page, size));
    }

    /**
     * 读取指定版本与引用清单。
     *
     * @param artifactId 产物 ID
     * @param version 版本号
     * @param authentication 当前认证
     * @return 版本
     */
    @GetMapping("/artifacts/{artifactId}/versions/{version}")
    @RequirePermission(PermissionCodes.ARTIFACT_READ)
    public ArtifactVersionResponse getVersion(
            @PathVariable UUID artifactId, @PathVariable int version, Authentication authentication) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return artifactService.getVersion(actor, artifactId, version);
    }

    /**
     * 获取产物下载地址。
     *
     * @param artifactId 产物 ID
     * @param version 版本号
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 预签名地址
     */
    @GetMapping("/artifacts/{artifactId}/versions/{version}/download-url")
    @RequirePermission(PermissionCodes.ARTIFACT_READ)
    public DownloadUrlResponse downloadUrl(
            @PathVariable UUID artifactId,
            @PathVariable int version,
            Authentication authentication,
            HttpServletRequest request) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return artifactService.downloadUrl(
                actor, artifactId, version, RequestContext.traceId(request), RequestContext.requestId(request));
    }

    /**
     * 同源读取产物正文。
     *
     * <p>正文上限 2 MiB，响应固定 {@code Cache-Control: no-store}；不返回对象存储地址或凭据。
     * 跨租户、不可见产物与不存在版本统一 404，语义与下载地址一致。
     *
     * @param artifactId 产物 ID
     * @param version 版本号
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 正文响应；Markdown 或纯文本
     */
    @GetMapping("/artifacts/{artifactId}/versions/{version}/content")
    @RequirePermission(PermissionCodes.ARTIFACT_READ)
    public ResponseEntity<byte[]> content(
            @PathVariable UUID artifactId,
            @PathVariable int version,
            Authentication authentication,
            HttpServletRequest request) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        ArtifactContent content = artifactService.content(
                actor, artifactId, version, RequestContext.traceId(request), RequestContext.requestId(request));
        return ResponseEntity.ok()
                .contentType(content.contentType())
                .contentLength(content.body().length)
                .cacheControl(CacheControl.noStore())
                // 不允许浏览器根据内容猜测类型，避免对象存储中的 HTML 等内容被当作可执行文档。
                .header("X-Content-Type-Options", "nosniff")
                .body(content.body());
    }
}
