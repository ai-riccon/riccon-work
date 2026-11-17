package com.riccon.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** traceId 透传与校验。 */
class TraceIdFilterTest {

    /** 测试用过滤器。 */
    private final TraceIdFilter filter = new TraceIdFilter();

    /** 合法外部 traceId 必须原样回写。 */
    @Test
    void validTraceIdIsPropagated() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Trace-Id", "trace-abc_123");
        request.addHeader("X-Request-Id", "request-001");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> invoked.set(true));
        assertThat(invoked).isTrue();
        assertThat(response.getHeader("X-Trace-Id")).isEqualTo("trace-abc_123");
        assertThat(request.getAttribute(RequestContext.TRACE_ID_ATTRIBUTE)).isEqualTo("trace-abc_123");
        assertThat(request.getAttribute(RequestContext.REQUEST_ID_ATTRIBUTE)).isEqualTo("request-001");
    }

    /** 非法 traceId 必须替换，防止日志注入。 */
    @Test
    void invalidTraceIdIsReplaced() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Trace-Id", "bad trace with spaces");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {};
        filter.doFilter(request, response, chain);
        assertThat(response.getHeader("X-Trace-Id")).isNotEqualTo("bad trace with spaces");
        assertThat(response.getHeader("X-Trace-Id")).matches("^[a-f0-9-]{36}$");
    }

    /** 超长请求 ID 必须忽略。 */
    @Test
    void invalidRequestIdIsIgnored() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "x".repeat(65));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {});
        assertThat(request.getAttribute(RequestContext.REQUEST_ID_ATTRIBUTE)).isNull();
    }
}
