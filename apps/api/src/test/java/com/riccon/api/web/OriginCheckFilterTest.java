package com.riccon.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/** Origin/Referer 同源校验。 */
class OriginCheckFilterTest {

    /** 测试用错误响应写入器。 */
    private final ProblemWriter problemWriter = new ProblemWriter(new ObjectMapper());

    /** 测试用过滤器。 */
    private final OriginCheckFilter filter = new OriginCheckFilter(problemWriter);

    /** 同源写请求必须放行。 */
    @Test
    void sameOriginPassesThrough() throws Exception {
        MockHttpServletRequest request = request("POST", "http://127.0.0.1:8080", "Origin", "http://127.0.0.1:8080");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();
        FilterChain chain = (req, res) -> invoked.set(true);
        filter.doFilter(request, response, chain);
        assertThat(invoked).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    /** scheme、host 或端口不同都必须拒绝。 */
    @Test
    void differentOriginIsRejected() throws Exception {
        assertRejected(request("POST", "http://127.0.0.1:8080", "Origin", "https://127.0.0.1:8080"));
        assertRejected(request("POST", "http://127.0.0.1:8080", "Origin", "http://evil.example:8080"));
        assertRejected(request("POST", "http://127.0.0.1:8080", "Origin", "http://127.0.0.1:9090"));
    }

    /** 默认端口与 request server port 不一致时拒绝。 */
    @Test
    void defaultOriginPortIsComparedWithEffectivePort() throws Exception {
        assertRejected(request("POST", "http://127.0.0.1:8080", "Origin", "http://127.0.0.1"));
    }

    /** Origin 缺失时回退到 Referer。 */
    @Test
    void refererIsUsedWhenOriginMissing() throws Exception {
        MockHttpServletRequest request =
                request("POST", "http://127.0.0.1:8080", "Referer", "http://evil.example/page");
        assertRejected(request);
        MockHttpServletRequest allowed =
                request("POST", "http://127.0.0.1:8080", "Referer", "http://127.0.0.1:8080/page");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();
        filter.doFilter(allowed, response, (req, res) -> invoked.set(true));
        assertThat(invoked).isTrue();
    }

    /** Origin: null 与非法 URI 必须拒绝，不能按“无 Origin”放行。 */
    @Test
    void opaqueAndMalformedOriginsAreRejected() throws Exception {
        assertRejected(request("POST", "http://127.0.0.1:8080", "Origin", "null"));
        assertRejected(request("POST", "http://127.0.0.1:8080", "Origin", "::::"));
    }

    /** 安全方法不检查来源，避免影响普通导航。 */
    @Test
    void safeMethodsAreNotChecked() throws Exception {
        MockHttpServletRequest request = request("GET", "http://127.0.0.1:8080", "Origin", "http://evil.example");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> invoked.set(true));
        assertThat(invoked).isTrue();
    }

    /**
     * 构造请求。
     *
     * @param method HTTP 方法
     * @param url 请求地址
     * @param header 头名
     * @param value 头值
     * @return 请求
     */
    private MockHttpServletRequest request(String method, String url, String header, String value) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod(method);
        request.setScheme(url.substring(0, url.indexOf("://")));
        String authority = url.substring(url.indexOf("://") + 3);
        request.setServerName(authority.substring(0, authority.lastIndexOf(':')));
        request.setServerPort(Integer.parseInt(authority.substring(authority.lastIndexOf(':') + 1)));
        request.addHeader(header, value);
        return request;
    }

    /**
     * 断言请求被拒绝且未进入后续链路。
     *
     * @param request 请求
     * @throws Exception 过滤器异常
     */
    private void assertRejected(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> invoked.set(true));
        assertThat(invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/problem+json");
    }
}
