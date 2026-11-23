package com.riccon.worker.ocr.config;

import com.riccon.worker.ocr.domain.OcrManifest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * M05 OCR 配置装配。
 *
 * <p>只注册配置绑定与镜像指纹；引擎、服务与信息端点使用 {@code @Component} 自动装配，保持配置类
 * 不依赖业务服务（ArchUnit MVC 分层）。测试 profile 通过
 * {@code riccon.worker.ocr.enabled=false} 关闭引擎，无 Tesseract 的宿主不会加载原生库。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OcrProperties.class)
public class OcrConfiguration {

    /**
     * 读取镜像内 OCR 指纹；缺失或损坏时降级为 {@code unknown}，不阻断启动。
     *
     * @param properties OCR 配置
     * @return 指纹
     */
    @Bean
    OcrManifest ocrManifest(OcrProperties properties) {
        return OcrManifest.load(properties.manifestPath());
    }
}
