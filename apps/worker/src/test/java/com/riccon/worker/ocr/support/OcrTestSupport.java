package com.riccon.worker.ocr.support;

import com.riccon.worker.ocr.config.OcrProperties;
import com.riccon.worker.ocr.domain.OcrBlock;
import com.riccon.worker.parse.domain.BoundingBox;
import java.math.BigDecimal;
import java.time.Duration;

/**
 * OCR 测试配置与结果构造工具。
 *
 * <p>避免每个测试重复写 15 个配置字段；默认值与生产一致，但超时/上限可单独调小以覆盖边界。
 */
public final class OcrTestSupport {

    /** 工具类禁止实例化。 */
    private OcrTestSupport() {}

    /**
     * 生产默认配置。
     *
     * @return 生产默认 OCR 配置
     */
    public static OcrProperties defaults() {
        return properties(true, Duration.ofSeconds(5), Duration.ofSeconds(30), 100, 50_000_000L, 200_000_000L, 4000);
    }

    /**
     * 关闭 OCR 的配置。
     *
     * @return 关闭 OCR 的配置
     */
    public static OcrProperties disabled() {
        return new OcrProperties(
                false,
                "tesseract",
                "chi_sim+eng",
                1,
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                100,
                50_000_000L,
                200_000_000L,
                67_108_864L,
                4000,
                200,
                new BigDecimal("0.60"),
                new BigDecimal("0.45"),
                "",
                "");
    }

    /**
     * 自定义 OCR 配置。
     *
     * @param enabled 是否启用
     * @param pageTimeout 单页超时
     * @param documentTimeout 文档超时
     * @param maxPages OCR 页数上限
     * @param maxPixels 目标像素
     * @param maxDecodePixels 解码硬上限
     * @param maxLongEdge 最长边
     * @return OCR 配置
     */
    public static OcrProperties properties(
            boolean enabled,
            Duration pageTimeout,
            Duration documentTimeout,
            int maxPages,
            long maxPixels,
            long maxDecodePixels,
            int maxLongEdge) {
        return properties(
                enabled, pageTimeout, documentTimeout, maxPages, maxPixels, maxDecodePixels, 67_108_864L, maxLongEdge);
    }

    /**
     * 自定义 OCR 配置（含 64 MiB 解码字节预算）。
     *
     * @param enabled 是否启用
     * @param pageTimeout 单页超时
     * @param documentTimeout 文档超时
     * @param maxPages OCR 页数上限
     * @param maxPixels 目标像素
     * @param maxDecodePixels 解码硬上限
     * @param maxDecodeBytes 解码字节预算
     * @param maxLongEdge 最长边
     * @return OCR 配置
     */
    public static OcrProperties properties(
            boolean enabled,
            Duration pageTimeout,
            Duration documentTimeout,
            int maxPages,
            long maxPixels,
            long maxDecodePixels,
            long maxDecodeBytes,
            int maxLongEdge) {
        return new OcrProperties(
                enabled,
                "tesseract",
                "chi_sim+eng",
                1,
                pageTimeout,
                documentTimeout,
                maxPages,
                maxPixels,
                maxDecodePixels,
                maxDecodeBytes,
                maxLongEdge,
                200,
                new BigDecimal("0.60"),
                new BigDecimal("0.45"),
                "",
                "");
    }

    /**
     * 构造指向测试 manifest 的配置。
     *
     * @param manifestPath manifest 路径
     * @param enabled 是否启用
     * @return OCR 配置
     */
    public static OcrProperties withManifest(String manifestPath, boolean enabled) {
        return new OcrProperties(
                enabled,
                "tesseract",
                "chi_sim+eng",
                1,
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                100,
                50_000_000L,
                200_000_000L,
                67_108_864L,
                4000,
                200,
                new BigDecimal("0.60"),
                new BigDecimal("0.45"),
                "",
                manifestPath);
    }

    /**
     * 构造指定语言的配置，用于配置绑定与 info 测试。
     *
     * @param language 语言
     * @param manifestPath manifest 路径
     * @param enabled 是否启用
     * @return OCR 配置
     */
    public static OcrProperties withLanguage(String language, String manifestPath, boolean enabled) {
        return new OcrProperties(
                enabled,
                "tesseract",
                language,
                1,
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                100,
                50_000_000L,
                200_000_000L,
                67_108_864L,
                4000,
                200,
                new BigDecimal("0.60"),
                new BigDecimal("0.45"),
                "",
                manifestPath);
    }

    /**
     * 返回测试 manifest 的文件系统路径。
     *
     * @return 路径
     */
    public static String manifestPath() {
        try {
            return java.nio.file.Path.of(OcrTestSupport.class
                            .getResource("/ocr/manifest.json")
                            .toURI())
                    .toString();
        } catch (java.net.URISyntaxException exception) {
            throw new IllegalStateException("test manifest is missing", exception);
        }
    }

    /**
     * 构造一个 OCR 文本块。
     *
     * @param text 文本
     * @param confidence 置信度
     * @param pageNo 页码
     * @param lineNo 行序
     * @param bbox 区域
     * @return 文本块
     */
    public static OcrBlock block(String text, String confidence, int pageNo, int lineNo, BoundingBox bbox) {
        return new OcrBlock(text, new BigDecimal(confidence), bbox, lineNo, pageNo);
    }
}
