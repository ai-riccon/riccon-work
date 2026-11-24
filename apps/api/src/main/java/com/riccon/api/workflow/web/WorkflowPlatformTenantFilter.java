package com.riccon.api.workflow.web;

import com.riccon.api.error.ErrorCodes;
import com.riccon.api.identity.domain.PlatformTenant;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.web.ProblemWriter;
import com.riccon.api.web.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 平台租户工作流端点守卫。
 *
 * <p>ADR-0024 第 6.1 节（F-005）：平台管理员不持有任何 {@code workflow.*} 权限，
 * {@code /api/v1/workflows/**}、{@code /workflow-runs/**}、{@code /workflow-approvals/**} 与
 * {@code /workflow-templates/**} 对 {@code tenantId=platform} 统一返回 404
 * {@code RICCON-TENANT-404}。
 *
 * <p>顺序要求：本过滤器位于安全过滤器链内、认证之后、MVC 的 {@code PermissionInterceptor} 之前。
 * 如果只在 controller 内做 404，方法级权限拦截器会先以 403 拒绝，泄露“端点存在”；因此必须在这里
 * 短路。未认证请求不在这里处理，继续由安全链返回 401；非 platform 主体继续走正常权限链。
 *
 * <p>本过滤器只做租户边界判定，不读取请求体、不解析业务参数，也不写审计：平台管理员访问不存在的
 * 租户资源属于 404 语义，不构成越权尝试（越权审计由 T05/T16 的端点用例覆盖）。
 */
@Component("workflowPlatformTenantFilter")
public class WorkflowPlatformTenantFilter extends OncePerRequestFilter {

    /** 工作流端点前缀；使用前缀边界匹配，避免误伤 `/workflows-other` 之类路径。 */
    private static final List<String> PATH_PREFIXES = List.of(
            "/api/v1/workflows", "/api/v1/workflow-runs", "/api/v1/workflow-approvals", "/api/v1/workflow-templates");

    /** 错误响应写入器。 */
    private final ProblemWriter problemWriter;

    /**
     * 创建平台租户工作流守卫。
     *
     * @param problemWriter 错误响应写入器
     */
    public WorkflowPlatformTenantFilter(ProblemWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    /**
     * 对 platform 主体的工作流请求写 404 并短路，其余请求放行。
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
        if (!isWorkflowPath(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof RicconPrincipal principal)) {
            // 未认证交给安全链统一 401，避免把“没有会话”误报为 404。
            filterChain.doFilter(request, response);
            return;
        }
        if (!PlatformTenant.ID.equals(principal.tenantId())) {
            filterChain.doFilter(request, response);
            return;
        }
        problemWriter.write(
                response,
                HttpStatus.NOT_FOUND,
                ErrorCodes.TENANT_NOT_FOUND,
                "Not Found",
                "workflow endpoints are not available to the platform tenant",
                RequestContext.traceId(request));
    }

    /**
     * 判断请求路径是否属于工作流端点。
     *
     * <p>去掉 context path 后按“前缀 + 路径边界”匹配；查询参数与请求方法不影响判定。
     *
     * @param request HTTP 请求
     * @return 命中返回 true
     */
    private static boolean isWorkflowPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return false;
        }
        String contextPath = request.getContextPath();
        String path = contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)
                ? uri.substring(contextPath.length())
                : uri;
        for (String prefix : PATH_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }
}
