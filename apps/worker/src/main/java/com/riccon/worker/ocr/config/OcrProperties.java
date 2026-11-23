package com.riccon.worker.ocr.config;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * M05 本地 OCR 配置（ADR-0023 第 4.2/4.4/4.6 节）。
 *
 * <p>所有上限都有保守默认值：像素、单页/单文档超时、OCR 页数与并发。生产默认启用；测试 profile
 * 在 {@code application-test.yml} 显式关闭，保证无 Tesseract 的宿主/CI 跑全量不会失败。
 *
 * @param enabled 是否启用 OCR；仅在此为 true 时装配原生引擎并在不可用时启动失败
 * @param engine 引擎标识，当前支持 {@code tesseract}
 * @param language Tesseract 语言，冒号分隔多个语言时使用 {@code +}，例如 {@code chi_sim+eng}
 * @param maxConcurrency 每实例并发 OCR 线程数；不占用 chat/report 线程
 * @param pageTimeout 单页 OCR 超时
 * @param documentTimeout 单文档 OCR 总超时；超时后保留已成功页并告警
 * @param maxPages 单文档最多 OCR 页数，超出页只保留文本层
 * @param maxPixels 单页目标最大像素数，超出先等比缩放
 * @param maxDecodePixels 单页解码前硬上限，超出直接拒绝（防像素炸弹）
 * @param maxLongEdge 单页最长边像素，超出等比缩放
 * @param pdfDpi PDF 页渲染 DPI
 * @param lowConfidenceThreshold 块级低置信阈值，低于该值仍入索引但告警
 * @param pageLowConfidenceThreshold 页级低置信阈值，低于该值标记整页低置信
 * @param tessdataPath tessdata 目录；空值使用 {@code TESSDATA_PREFIX}/Tesseract 默认路径
 * @param manifestPath 镜像内 OCR 指纹 manifest 路径；不存在时信息端点显示 {@code unknown}
 */
@ConfigurationProperties(prefix = "riccon.worker.ocr")
public record OcrProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("tesseract") String engine,
        @DefaultValue("chi_sim+eng") String language,
        @DefaultValue("2") int maxConcurrency,
        @DefaultValue("30s") Duration pageTimeout,
        @DefaultValue("600s") Duration documentTimeout,
        @DefaultValue("100") int maxPages,
        @DefaultValue("50000000") long maxPixels,
        @DefaultValue("200000000") long maxDecodePixels,
        @DefaultValue("4000") int maxLongEdge,
        @DefaultValue("200") int pdfDpi,
        @DefaultValue("0.60") BigDecimal lowConfidenceThreshold,
        @DefaultValue("0.45") BigDecimal pageLowConfidenceThreshold,
        @DefaultValue("") String tessdataPath,
        @DefaultValue("/opt/riccon/ocr/manifest.json") String manifestPath) {

    /** 校验资源上限与阈值，避免把非法配置带入运行期。 */
    public OcrProperties {
        if (engine == null || engine.isBlank()) {
            throw new IllegalArgumentException("riccon.worker.ocr.engine must not be blank");
        }
        if (language == null || language.isBlank()) {
            throw new IllegalArgumentException("riccon.worker.ocr.lang must not be blank");
        }
        if (maxConcurrency < 1 || maxConcurrency > 16) {
            throw new IllegalArgumentException("riccon.worker.ocr.max-concurrency must be in [1, 16]");
        }
        if (pageTimeout == null || pageTimeout.isZero() || pageTimeout.isNegative()) {
            throw new IllegalArgumentException("riccon.worker.ocr.page-timeout must be positive");
        }
        if (documentTimeout == null || documentTimeout.isZero() || documentTimeout.isNegative()) {
            throw new IllegalArgumentException("riccon.worker.ocr.document-timeout must be positive");
        }
        if (maxPages < 1) {
            throw new IllegalArgumentException("riccon.worker.ocr.max-pages must be positive");
        }
        if (maxPixels < 1) {
            throw new IllegalArgumentException("riccon.worker.ocr.max-pixels must be positive");
        }
        if (maxDecodePixels < maxPixels) {
            throw new IllegalArgumentException(
                    "riccon.worker.ocr.max-decode-pixels must be greater than or equal to max-pixels");
        }
        if (maxLongEdge < 100) {
            throw new IllegalArgumentException("riccon.worker.ocr.max-long-edge must be at least 100");
        }
        if (pdfDpi < 72 || pdfDpi > 600) {
            throw new IllegalArgumentException("riccon.worker.ocr.pdf-dpi must be in [72, 600]");
        }
        if (lowConfidenceThreshold == null
                || lowConfidenceThreshold.signum() <= 0
                || lowConfidenceThreshold.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("riccon.worker.ocr.low-confidence-threshold must be in (0, 1]");
        }
        if (pageLowConfidenceThreshold == null
                || pageLowConfidenceThreshold.signum() <= 0
                || pageLowConfidenceThreshold.compareTo(lowConfidenceThreshold) > 0) {
            throw new IllegalArgumentException(
                    "riccon.worker.ocr.page-low-confidence-threshold must be in (0, low-confidence-threshold]");
        }
        tessdataPath = tessdataPath == null ? "" : tessdataPath;
        manifestPath = manifestPath == null ? "" : manifestPath;
    }

    /**
     * 返回语言列表（按 {@code +} 拆分）。
     *
     * @return 语言列表，顺序与配置一致
     */
    public java.util.List<String> languages() {
        return java.util.Arrays.stream(language.split("\\+"))
                .map(String::strip)
                .filter(part -> !part.isEmpty())
                .toList();
    }

    /**
     * 构造 OcrCli 使用的默认配置，避免 CLI 依赖 Spring 上下文。
     *
     * @param language 语言
     * @param tessdataPath tessdata 目录；空值使用 Tesseract 默认路径
     * @param manifestPath 指纹 manifest 路径
     * @return OCR 配置
     */
    public static OcrProperties cliDefaults(String language, String tessdataPath, String manifestPath) {
        return new OcrProperties(
                true,
                "tesseract",
                language,
                1,
                Duration.ofSeconds(30),
                Duration.ofSeconds(600),
                100,
                50_000_000L,
                200_000_000L,
                4000,
                200,
                new BigDecimal("0.60"),
                new BigDecimal("0.45"),
                tessdataPath,
                manifestPath);
    }
}
