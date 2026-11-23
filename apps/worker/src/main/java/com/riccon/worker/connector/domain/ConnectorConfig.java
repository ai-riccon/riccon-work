package com.riccon.worker.connector.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 连接器配置（{@code kb_connector.config} JSON）。
 *
 * <p>只包含 bucket/prefix/region/扩展名与排除 glob；不包含端点 URL、凭据或对象 key。
 *
 * @param bucket S3 桶名
 * @param prefix 对象前缀；空串表示桶根
 * @param region 签名区域；为空时使用部署默认区域
 * @param includeExtensions 允许的扩展名；为空时使用部署白名单
 * @param excludeGlobs 排除的 glob 模式
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConnectorConfig(
        String bucket, String prefix, String region, List<String> includeExtensions, List<String> excludeGlobs) {

    /** 缺省前缀。 */
    public static final String DEFAULT_PREFIX = "";

    /** 部署白名单（ADR-0023 第 6.5 节）。 */
    public static final List<String> DEFAULT_EXTENSIONS =
            List.of("pdf", "docx", "xlsx", "png", "jpg", "jpeg", "txt", "md");

    /** 规范化可选集合，避免 JSON null 泄漏到业务判断。 */
    public ConnectorConfig {
        includeExtensions = includeExtensions == null ? List.of() : List.copyOf(includeExtensions);
        excludeGlobs = excludeGlobs == null ? List.of() : List.copyOf(excludeGlobs);
        if (prefix == null) {
            prefix = DEFAULT_PREFIX;
        }
    }

    /**
     * 返回生效的扩展名白名单。
     *
     * @return 配置值；为空时返回部署白名单
     */
    public List<String> effectiveExtensions() {
        return includeExtensions.isEmpty() ? DEFAULT_EXTENSIONS : includeExtensions;
    }
}
