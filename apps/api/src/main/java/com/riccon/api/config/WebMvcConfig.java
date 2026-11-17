package com.riccon.api.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 拦截器注册。
 *
 * <p>权限拦截器只作用于 {@code /api/v1/**}；健康检查与静态资源不经过业务权限判断。拦截器通过
 * {@link HandlerInterceptor} 接口注入，避免配置类依赖 controller 具体类型。
 */
@Configuration(proxyBeanMethods = false)
public class WebMvcConfig implements WebMvcConfigurer {

    /** 方法级权限拦截器。 */
    private final HandlerInterceptor permissionInterceptor;

    /**
     * 创建 MVC 配置。
     *
     * @param permissionInterceptor 方法级权限拦截器
     */
    public WebMvcConfig(@Qualifier("permissionInterceptor") HandlerInterceptor permissionInterceptor) {
        this.permissionInterceptor = permissionInterceptor;
    }

    /**
     * 注册权限拦截器。
     *
     * @param registry 拦截器注册表
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(permissionInterceptor).addPathPatterns("/api/v1/**");
    }
}
