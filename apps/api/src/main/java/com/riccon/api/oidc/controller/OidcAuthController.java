package com.riccon.api.oidc.controller;

import com.riccon.api.error.BadRequestException;
import com.riccon.api.oidc.domain.OidcProviderAvailability;
import com.riccon.api.oidc.service.OidcLoginService;
import com.riccon.api.oidc.service.OidcProviderRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 企业账号登录入口查询。
 *
 * <p>匿名可调用，仅用于登录页决定是否展示“企业账号登录”。未知租户、未配置、已停用与租户暂停统一返回
 * {@code enabled=false}，不返回租户名、IdP 地址或错误差异；端点本身不写审计（ADR-0019 第 4 节）。
 * 按客户端 IP 做 60 次/分钟进程内限流，超限 429 {@code RICCON-RATE-429}；不按 tenantCode 计数。
 */
@RestController
@RequestMapping("/api/v1/auth/oidc")
public class OidcAuthController {

    /** 租户代码格式；与 OpenAPI 参数约束一致。 */
    private static final Pattern TENANT_CODE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$");

    /** 登录业务。 */
    private final OidcLoginService loginService;

    /** 端点限流。 */
    private final OidcProviderRateLimiter providerRateLimiter;

    /**
     * 创建 OIDC 认证控制器。
     *
     * @param loginService 登录业务
     * @param providerRateLimiter 端点限流
     */
    public OidcAuthController(OidcLoginService loginService, OidcProviderRateLimiter providerRateLimiter) {
        this.loginService = Objects.requireNonNull(loginService, "loginService must not be null");
        this.providerRateLimiter = Objects.requireNonNull(providerRateLimiter, "providerRateLimiter must not be null");
    }

    /**
     * 查询租户企业登录可用性。
     *
     * @param tenantCode 租户代码
     * @param request HTTP 请求
     * @return 可用性
     */
    @GetMapping("/providers")
    public OidcProviderAvailability providers(
            @RequestParam(name = "tenantCode", required = false) String tenantCode, HttpServletRequest request) {
        // 先限流再解析，避免用参数校验差异绕过计数。
        providerRateLimiter.checkAllowed(request.getRemoteAddr());
        if (tenantCode == null || !TENANT_CODE.matcher(tenantCode).matches()) {
            throw new BadRequestException("tenantCode is invalid");
        }
        return loginService.providerAvailability(tenantCode);
    }
}
