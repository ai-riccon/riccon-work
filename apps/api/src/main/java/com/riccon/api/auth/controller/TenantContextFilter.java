package com.riccon.api.auth.controller;

import com.riccon.api.audit.domain.AdditionalAuditActions;
import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.audit.service.AuditService;
import com.riccon.api.auth.service.AuthService;
import com.riccon.api.error.ErrorCodes;
import com.riccon.api.error.ForbiddenException;
import com.riccon.api.error.UnauthenticatedException;
import com.riccon.api.identity.domain.PlatformTenant;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.web.ProblemWriter;
import com.riccon.api.web.RequestContext;
import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import com.riccon.platform.domain.identity.PermissionCodes;
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
 * 与查询参数 {@code tenantId} 都不是租户来源：与主体不一致时默认拒绝并写审计；两者同时出现且不一致时
 * 一律拒绝，任何一方都不能掩盖另一方。
 *
 * <p>唯一窄例外是 ADR-0022 §2.4 的平台成本过滤：仅 {@code platform} 租户、{@code GET
 * /api/v1/admin/costs} 且具备 {@code platform.cost.read} 时，允许过滤值与主体不一致；过滤器先校验权限，
 * controller 仍由 {@code PermissionInterceptor} 二次强制。该例外下 {@code X-Tenant-Id} 不参与过滤，
 * 过滤值只来自查询参数。其他路径/方法/主体继续按默认拒绝处理。
 *
 * <p>每个请求结束时必须清理 ThreadLocal，避免线程池复用串租户。
 */
@Component
public class TenantContextFilter extends OncePerRequestFilter {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(TenantContextFilter.class);

    /** 只用于识别伪造的租户请求头；它不是租户来源。 */
    private static final String FORGED_TENANT_HEADER = "X-Tenant-Id";

    /** 查询参数名；与契约一致，仅用于识别伪造。 */
    private static final String FORGED_TENANT_PARAMETER = "tenantId";

    /** 审计拒绝原因：租户标识与主体不一致。 */
    private static final String TENANT_MISMATCH_REASON = "tenant_mismatch";

    /** 审计拒绝原因：平台成本例外候选主体缺少 platform.cost.read。 */
    private static final String MISSING_PERMISSION_REASON = "missing_permission";

    /** 审计写入。 */
    private final AuditService auditService;

    /** 认证服务；平台成本例外在过滤器内也要校验角色，不能只依赖 controller 拦截器。 */
    private final AuthService authService;

    /** 可注入时钟。 */
    private final Clock clock;

    /** 错误响应写入器。 */
    private final ProblemWriter problemWriter;

    /**
     * 创建租户上下文过滤器。
     *
     * @param auditService 审计写入
     * @param authService 认证服务（平台成本例外权限校验）
     * @param clock 可注入时钟
     * @param problemWriter 错误响应写入器
     */
    public TenantContextFilter(
            AuditService auditService, AuthService authService, Clock clock, ProblemWriter problemWriter) {
        this.auditService = auditService;
        this.authService = authService;
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

        String headerTenant = nonBlank(request.getHeader(FORGED_TENANT_HEADER));
        String queryTenant = nonBlank(queryParameter(request, FORGED_TENANT_PARAMETER));
        // 两个来源同时存在且不一致时直接拒绝：任何一方都不能掩盖另一方（REV-TASK-0142-001 F-001）。
        if (headerTenant != null && queryTenant != null && !headerTenant.equals(queryTenant)) {
            denyMismatchedTenant(
                    request, response, principal, tenantId, queryTenant, headerTenant, TENANT_MISMATCH_REASON);
            return;
        }

        String forgedTenant = headerTenant != null ? headerTenant : queryTenant;
        if (forgedTenant != null && !forgedTenant.equals(tenantId.value())) {
            // 只有平台成本端点允许“主体租户与过滤值不一致”；命中候选后还必须具备平台成本权限。
            if (!isPlatformCostFilter(request, principal)) {
                denyMismatchedTenant(
                        request, response, principal, tenantId, forgedTenant, null, TENANT_MISMATCH_REASON);
                return;
            }
            // 若只依赖 PermissionInterceptor，安全过滤器会在角色校验前放行伪造 tenantId，形成纵深缺口。
            if (!hasPlatformCostPermission(request, response, principal, tenantId, forgedTenant)) {
                return;
            }
        }

        try (TenantContext.TenantScope ignored = TenantContext.open(tenantId)) {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * 平台成本端点的租户过滤参数例外。
     *
     * <p>ADR-0009 §1 把查询参数 {@code tenantId} 视为伪造入口；但 ADR-0022 §2.4 的
     * {@code GET /api/v1/admin/costs} 明确使用 {@code tenantId} 作为跨租户聚合过滤。这里只识别
     * 平台保留租户的该只读端点；调用方还必须通过 {@link #hasPlatformCostPermission} 校验
     * {@code platform.cost.read}，租户端点与其他方法不享受例外。
     *
     * @param request HTTP 请求
     * @param principal 已认证主体
     * @return 是否命中平台成本过滤例外（不代表已授权）
     */
    private static boolean isPlatformCostFilter(HttpServletRequest request, RicconPrincipal principal) {
        return "GET".equals(request.getMethod())
                && "/api/v1/admin/costs".equals(request.getRequestURI())
                && PlatformTenant.ID.equals(principal.tenantId());
    }

    /**
     * 校验平台成本读取权限，作为过滤器例外的 defense-in-depth。
     *
     * <p>与 {@code PermissionInterceptor} 同源调用 {@link AuthService#requirePermission}，每次请求从数据库
     * 重新加载权限，不信任会话中的旧权限。失败时由本方法直接写响应并返回 {@code false}（fail-closed）：
     * 缺少权限或权限读取异常写 403 并审计；会话失效/账号禁用写 401，与常规拦截器路径保持一致。
     * 任何异常都不会逃逸出安全过滤器链，也不会降级为放行。
     *
     * @param request HTTP 请求
     * @param response HTTP 响应
     * @param principal 已认证主体
     * @param tenantId 主体所属租户
     * @param forgedTenant 触发例外的过滤值
     * @return 是否具备 {@code platform.cost.read}
     * @throws IOException 写入失败
     */
    private boolean hasPlatformCostPermission(
            HttpServletRequest request,
            HttpServletResponse response,
            RicconPrincipal principal,
            TenantId tenantId,
            String forgedTenant)
            throws IOException {
        try {
            authService.requirePermission(principal, PermissionCodes.PLATFORM_COST_READ);
            return true;
        } catch (UnauthenticatedException exception) {
            // 会话失效或账号禁用：返回 401，避免把“未认证”误报成“缺少权限”。
            problemWriter.write(
                    response,
                    HttpStatus.UNAUTHORIZED,
                    ErrorCodes.AUTH_UNAUTHENTICATED,
                    "Authentication required",
                    "session is missing or expired",
                    RequestContext.traceId(request));
            return false;
        } catch (ForbiddenException exception) {
            // 预期内的无权限：与租户不一致一样拒绝，不泄露例外命中信息。
            denyMismatchedTenant(request, response, principal, tenantId, forgedTenant, null, MISSING_PERMISSION_REASON);
            return false;
        } catch (RuntimeException exception) {
            // 权限读取失败同样按无权限处理；异常不能逃逸出安全过滤器链，也不能降级为放行。
            LOG.error(
                    "platform cost permission check failed userId={} tenantId={}",
                    principal.userId(),
                    principal.tenantId(),
                    exception);
            denyMismatchedTenant(request, response, principal, tenantId, forgedTenant, null, MISSING_PERMISSION_REASON);
            return false;
        }
    }

    /**
     * 写拒绝审计并返回 403；审计写入失败返回 500，避免无审计的越权尝试。
     *
     * @param request HTTP 请求
     * @param response HTTP 响应
     * @param principal 会话主体
     * @param tenantId 主体所属租户
     * @param forgedTenant 客户端伪造的租户值（冲突时为查询参数值）
     * @param conflictingHeader 与查询参数冲突的请求头值，无冲突时为空
     * @param reason 审计中的拒绝原因
     * @throws IOException 写入失败
     */
    private void denyMismatchedTenant(
            HttpServletRequest request,
            HttpServletResponse response,
            RicconPrincipal principal,
            TenantId tenantId,
            String forgedTenant,
            String conflictingHeader,
            String reason)
            throws IOException {
        // 审计 details 的键受 AuditDetailsPolicy 白名单约束，冲突时把两个值都写入 allowed key
        // attemptedTenant（格式 header=<值>;query=<值>），不新增未批准的审计字段。
        String attempted = conflictingHeader == null
                ? truncate(forgedTenant, 200)
                : truncate("header=" + conflictingHeader + ";query=" + forgedTenant, 200);
        String targetId = forgedTenant.length() <= 64 ? forgedTenant : null;
        Map<String, Object> details = Map.of("attemptedTenant", attempted, "reason", reason);
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
                            details)));
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
     * 把空白值归一为“未提供”，避免空白请求头/查询参数参与冲突判断。
     *
     * @param value 原始值
     * @return 非空白值或 {@code null}
     */
    private static String nonBlank(String value) {
        return value != null && !value.isBlank() ? value : null;
    }

    /**
     * 截断审计 details 中的外部输入，避免超长值放大审计记录。
     *
     * @param value 原始值
     * @param maxLength 最大长度
     * @return 截断后的值
     */
    private static String truncate(String value, int maxLength) {
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
}
