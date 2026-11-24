package com.riccon.api.workflow.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.web.ProblemWriter;
import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

/**
 * 平台租户工作流端点 404 守卫（ADR-0024 §6.1 / F-005）。
 *
 * <p>覆盖：四个工作流前缀与嵌套路径对 {@code tenantId=platform} 统一 404；非 platform 主体、未认证
 * 请求与非工作流路径继续放行，由后续权限链/安全链决定 401/403。守卫必须在 MVC 权限拦截器之前短路，
 * 否则平台管理员会先收到 403 并暴露端点存在。
 */
class WorkflowPlatformTenantFilterTest {

    /** 测试用错误响应写入器。 */
    private final ProblemWriter problemWriter = new ProblemWriter(new ObjectMapper());

    /** 被测过滤器。 */
    private final WorkflowPlatformTenantFilter filter = new WorkflowPlatformTenantFilter(problemWriter);

    /** 每个用例后清理安全上下文，避免线程复用串主体。 */
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** platform 主体访问四个前缀（含嵌套路径）一律 404 RICCON-TENANT-404。 */
    @Test
    void platformTenantGets404OnEveryWorkflowPrefix() throws Exception {
        authenticate("platform");
        for (String path : List.of(
                "/api/v1/workflows",
                "/api/v1/workflows/runtime-limits",
                "/api/v1/workflows/01990000-0000-7000-8000-000000000101",
                "/api/v1/workflow-runs",
                "/api/v1/workflow-runs/01990000-0000-7000-8000-000000000102",
                "/api/v1/workflow-approvals",
                "/api/v1/workflow-approvals/01990000-0000-7000-8000-000000000103/decision",
                "/api/v1/workflow-templates",
                "/api/v1/workflow-templates/01990000-0000-7000-8000-000000000104/copy")) {
            assertRejected404(path);
        }
    }

    /** 非 platform 主体继续进入后续链路（权限由 RBAC 判定）。 */
    @Test
    void tenantPrincipalPassesThrough() throws Exception {
        authenticate("acme");
        assertPassesThrough("/api/v1/workflows");
        assertPassesThrough("/api/v1/workflow-runs/01990000-0000-7000-8000-000000000102");
    }

    /** 未认证请求不由本过滤器处理，交给安全链返回 401。 */
    @Test
    void unauthenticatedRequestsPassThroughFor401() throws Exception {
        assertPassesThrough("/api/v1/workflows");
    }

    /** 前缀必须按路径边界匹配；相似但不同的路径不误伤。 */
    @Test
    void prefixBoundaryDoesNotMatchSiblingPaths() throws Exception {
        authenticate("platform");
        assertPassesThrough("/api/v1/workflows-other");
        assertPassesThrough("/api/v1/workflow-runs-archive");
        assertPassesThrough("/api/v1/files");
    }

    /** 带 context path 的部署同样识别工作流路径。 */
    @Test
    void contextPathIsStrippedBeforeMatching() throws Exception {
        authenticate("platform");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ctx/api/v1/workflows");
        request.setContextPath("/ctx");
        assertRejected404(request);
    }

    /**
     * 断言请求被 404 拒绝且未进入后续链。
     *
     * @param path 请求路径
     * @throws Exception 过滤器异常
     */
    private void assertRejected404(String path) throws Exception {
        assertRejected404(new MockHttpServletRequest("GET", path));
    }

    /**
     * 断言请求被 404 拒绝且未进入后续链。
     *
     * @param request 请求
     * @throws Exception 过滤器异常
     */
    private void assertRejected404(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();
        FilterChain chain = (req, res) -> invoked.set(true);
        filter.doFilter(request, response, chain);
        assertThat(invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString()).contains("RICCON-TENANT-404");
    }

    /**
     * 断言请求继续进入后续链。
     *
     * @param path 请求路径
     * @throws Exception 过滤器异常
     */
    private void assertPassesThrough(String path) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();
        FilterChain chain = (req, res) -> invoked.set(true);
        filter.doFilter(new MockHttpServletRequest("GET", path), response, chain);
        assertThat(invoked).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    /**
     * 写入已认证主体。
     *
     * @param tenantId 租户代码
     */
    private static void authenticate(String tenantId) {
        RicconPrincipal principal = new RicconPrincipal(
                UUID.fromString("01990000-0000-7000-8000-000000000201"), tenantId, "tester", "测试用户");
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }
}
