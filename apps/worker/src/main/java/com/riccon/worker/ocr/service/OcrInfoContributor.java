package com.riccon.worker.ocr.service;

import com.riccon.worker.ocr.config.OcrProperties;
import com.riccon.worker.ocr.domain.OcrEngine;
import com.riccon.worker.ocr.domain.OcrManifest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

/**
 * 向 {@code /actuator/info} 暴露 OCR 引擎/语言/权重指纹（ADR-0023 第 4.6 节）。
 *
 * <p>只暴露非密钥的部署事实：是否启用、引擎、语言、权重哈希与语言可用性；不包含 tessdata 路径、
 * 对象 key、凭据或内部地址。端点本身由 SecurityConfig 按 {@code RICCON_OPS_ALLOWED_CIDRS}
 * 限制来源（空=loopback），本类不放宽访问控制。
 */
@Component
public class OcrInfoContributor implements InfoContributor {

    /** OCR 配置。 */
    private final OcrProperties properties;

    /** 镜像指纹。 */
    private final OcrManifest manifest;

    /** OCR 引擎；关闭开关时为空。 */
    private final OcrEngine engine;

    /**
     * 创建信息贡献者。
     *
     * @param properties OCR 配置
     * @param manifest 镜像指纹
     * @param engineProvider OCR 引擎提供者
     */
    public OcrInfoContributor(
            OcrProperties properties, OcrManifest manifest, ObjectProvider<OcrEngine> engineProvider) {
        this.properties = properties;
        this.manifest = manifest;
        this.engine = engineProvider.getIfAvailable();
    }

    /**
     * 写入 {@code ocr} 详情。
     *
     * @param builder Actuator Info 构建器
     */
    @Override
    public void contribute(Info.Builder builder) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("enabled", properties.enabled() && engine != null);
        details.put("engine", properties.engine());
        details.put("lang", properties.language());
        details.put("model_hash", manifest.modelHash());
        details.put("engine_version", manifest.engineVersion());
        if (engine != null) {
            details.put("language_available", engine.languageAvailable());
        }
        builder.withDetail("ocr", details);
    }
}
