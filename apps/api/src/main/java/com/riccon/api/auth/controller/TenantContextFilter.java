package com.riccon.api.auth.controller;

import com.riccon.api.audit.domain.AdditionalAuditActions;
import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.audit.service.AuditService;
import com.riccon.api.error.ErrorCodes;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.web.ProblemWriter;
import com.riccon.api.web.RequestContext;
import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 从已认证主体建立 {@link TenantContext}。
 *
 * <p>ADR-0009 规定租户只能从会话主体派生，禁止读取 Cookie、请求头或请求体。请求头 {@code X-Tenant-Id}
 * 与查询参数 {@code tenantId} 若与主体不一致，立即拒绝并写审计；一致时也忽略，不改变派生来源。
 * 每个请求结束时必须清理 ThreadLocal，避免线程池复用串租户。
 */
@Component
public class TenantContextFilter extends OncePerRequestFilter {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(TenantContextFilter.class);

    /** 只用于识别伪造的租户请求头；它不是租户来源。 */
    private static final String FORGED_TENANT_HEADER = "X-Tenant-Id";

    /** 查询参数名；与契约一致，仅用于识别伪造。 */
    private static final String FORGED_TENANT_PARAMETER = "tenantId";

    /** 审计写入。 */
    private final AuditService auditService;

    /** 可注入时钟。 */
    private final Clock clock;

    /** 错误响应写入器。 */
    private final ProblemWriter problemWriter;

    /**
     * 创建租户上下文过滤器。
     *
     * @param auditService 审计写入
     * @param clock 可注入时钟
     * @param problemWriter 错误响应写入器
     */
    public TenantContextFilter(AuditService auditService, Clock clock, ProblemWriter problemWriter) {
        this.auditService = auditService;
        this.clock = clock;
        this.problemWriter = problemWriter;
    }

    /**
     * 建立租户作用域，并在 finally 中清理。
     *
     * @param request HTTP 请求
     * @param response HTTP 响应
     * @param filterChain 后续过滤器链
     * @throws ServletException 过滤器失败
     * @throws IOException 读写失败
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // 防御性清理：即使上一个请求异常退出，也不能让线程复用继承旧租户。
        TenantContext.clear();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof RicconPrincipal principal)) {
            try {
                filterChain.doFilter(request, response);
            } finally {
                TenantContext.clear();
            }
            return;
        }

        TenantId tenantId;
        try {
            tenantId = TenantId.of(principal.tenantId());
        } catch (IllegalArgumentException exception) {
            problemWriter.write(
                    response,
                    HttpStatus.UNAUTHORIZED,
                    ErrorCodes.AUTH_UNAUTHENTICATED,
                    "Authentication required",
                    "session tenant is invalid",
                    RequestContext.traceId(request));
            return;
        }

        String forgedTenant =
                firstPresent(request.getHeader(FORGED_TENANT_HEADER), queryParameter(request, FORGED_TENANT_PARAMETER));
        if (forgedTenant != null && !forgedTenant.equals(tenantId.value())) {
            denyMismatchedTenant(request, response, principal, tenantId, forgedTenant);
            return;
        }

        try (TenantContext.TenantScope ignored = TenantContext.open(tenantId)) {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * 写拒绝审计并返回 403；审计写入失败返回 500，避免无审计的越权尝试。
     *
     * @param request HTTP 请求
     * @param response HTTP 响应
     * @param principal 会话主体
     * @param tenantId 主体所属租户
     * @param forgedTenant 客户端伪造的租户值
     * @throws IOException 写入失败
     */
    private void denyMismatchedTenant(
            HttpServletRequest request,
            HttpServletResponse response,
            RicconPrincipal principal,
            TenantId tenantId,
            String forgedTenant)
            throws IOException {
        String attempted = forgedTenant.length() > 200 ? forgedTenant.substring(0, 200) : forgedTenant;
        String targetId = forgedTenant.length() <= 64 ? forgedTenant : null;
        try {
            TenantContext.runWithTenant(
                    tenantId,
                    () -> auditService.append(AuditEvent.create(
                            clock.instant(),
                            tenantId.value(),
                            AuditActorType.USER,
                            principal.userId().toString(),
                            AdditionalAuditActions.TENANT_ACCESS_DENIED,
                            AuditTargetType.TENANT,
                            targetId,
                            AuditOutcome.DENIED,
                            RequestContext.traceId(request),
                            RequestContext.requestId(request),
                            request.getRemoteAddr(),
                            Map.of("attemptedTenant", attempted, "reason", "tenant_mismatch"))));
        } catch (RuntimeException exception) {
            LOG.error(
                    "tenant access denied audit failed traceId={} tenantId={}",
                    RequestContext.traceId(request),
                    tenantId.value(),
                    exception);
            problemWriter.write(
                    response,
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    ErrorCodes.INTERNAL,
                    "Internal server error",
                    "An unexpected error occurred.",
                    RequestContext.traceId(request));
            return;
        }
        problemWriter.write(
                response,
                HttpStatus.FORBIDDEN,
                ErrorCodes.AUTH_FORBIDDEN,
                "Forbidden",
                "tenant does not match authenticated principal",
                RequestContext.traceId(request));
    }

    /**
     * 只从查询字符串读取参数，避免 Servlet 容器为检测伪造值而解析 multipart 请求体。
     *
     * <p>{@code request.getParameter} 会触发 multipart 解析；超过上传阈值时会在安全过滤器链内抛错，
     * 让本应返回 413 的请求变成通用错误。这里手工解析 query string，不触碰请求体，也不改变
     * “租户只从会话主体派生”的边界。
     *
     * @param request HTTP 请求
     * @param name 参数名
     * @return 解码后的第一个值；不存在时为空
     */
    private String queryParameter(HttpServletRequest request, String name) {
        String query = request.getQueryString();
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int separator = pair.indexOf('=');
            String rawName = separator >= 0 ? pair.substring(0, separator) : pair;
            if (!name.equals(decode(rawName))) {
                continue;
            }
            return separator >= 0 ? decode(pair.substring(separator + 1)) : "";
        }
        return null;
    }

    /**
     * 解码 URL 组件；非法编码视为未提供该参数，不阻断请求。
     *
     * @param value 原始值
     * @return 解码值，非法编码时返回空串
     */
    private String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return "";
        }
    }

    /**
     * 返回第一个非空值。
     *
     * @param first 请求头值
     * @param second 查询参数值
     * @return 非空值或 {@code null}
     */
    private String firstPresent(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : null;
    }
}
