package com.riccon.worker.ocr.service;

import com.riccon.worker.ocr.config.OcrProperties;
import com.riccon.worker.ocr.domain.OcrEngine;
import com.riccon.worker.ocr.domain.OcrException;
import com.riccon.worker.ocr.domain.OcrOptions;
import com.riccon.worker.ocr.domain.OcrPageResult;
import com.riccon.worker.ocr.domain.OcrResult;
import com.riccon.worker.parse.domain.ParseErrorCodes;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * OCR 服务：资源预处理、线程边界、单页超时与低置信旋转重试。
 *
 * <p>线程边界（ADR-0023 第 4.4 节、M05-AC-20）：OCR 只在固定大小守护线程池中执行，
 * 不占用 chat/report 的 outbox 线程或 HTTP 线程；解析器等待 {@link Future} 的超时，
 * 超时后取消并继续下一页（原生调用不可中断时由进程重启兜底）。
 *
 * <p>四态映射：
 *
 * <ul>
 *   <li>成功：有文本块且无低置信标记；
 *   <li>低置信：返回成功 + {@code lowConfidence}/{@code pageLowConfidence}，解析器仍入索引并告警；
 *   <li>部分失败：PDF 解析器聚合成功页与 {@code FAILED_PAGES}，失败页不产生分块；
 *   <li>全部失败：无任何成功页时解析器抛 {@link com.riccon.worker.parse.domain.ParseException}。
 * </ul>
 *
 * <p>关闭开关时 {@link #enabled()} 为 false，解析器输出 {@code KB_OCR_DISABLED}，不产生伪引用。
 */
@Component
public class OcrService implements AutoCloseable {

    /** 日志记录器。 */
    private static final Logger LOG = LoggerFactory.getLogger(OcrService.class);

    /** OCR 引擎；关闭开关时为空。 */
    private final OcrEngine engine;

    /** OCR 配置。 */
    private final OcrProperties properties;

    /** 图片预处理器。 */
    private final OcrImagePreprocessor preprocessor;

    /** OCR 专用线程池。 */
    private final ExecutorService executor;

    /**
     * 创建 OCR 服务。
     *
     * @param engineProvider OCR 引擎提供者；关闭开关时为空
     * @param properties OCR 配置
     * @param preprocessor 图片预处理器
     * @throws IllegalStateException 启用 OCR 但没有可用引擎
     */
    @Autowired
    public OcrService(
            ObjectProvider<OcrEngine> engineProvider, OcrProperties properties, OcrImagePreprocessor preprocessor) {
        this(engineProvider.getIfAvailable(), properties, preprocessor);
    }

    /**
     * 创建 OCR 服务（测试与 CLI 直接注入引擎）。
     *
     * @param engine OCR 引擎；关闭开关时为空
     * @param properties OCR 配置
     * @param preprocessor 图片预处理器
     * @throws IllegalStateException 启用 OCR 但没有可用引擎
     */
    public OcrService(OcrEngine engine, OcrProperties properties, OcrImagePreprocessor preprocessor) {
        this.engine = engine;
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.preprocessor = Objects.requireNonNull(preprocessor, "preprocessor must not be null");
        if (properties.enabled() && engine == null) {
            throw new IllegalStateException("riccon.worker.ocr.enabled=true 但 OCR 引擎未装配；拒绝启动");
        }
        this.executor = Executors.newFixedThreadPool(properties.maxConcurrency(), runnable -> {
            Thread thread = new Thread(runnable, "riccon-ocr");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 返回 OCR 是否可用。
     *
     * @return 开关打开且引擎已装配返回 true
     */
    public boolean enabled() {
        return properties.enabled() && engine != null;
    }

    /**
     * 返回引擎标识，供解析器写入 {@code parse_engine} 与日志。
     *
     * @return 引擎标识
     */
    public String engineId() {
        return engine != null ? engine.engineId() : properties.engine();
    }

    /**
     * 返回配置语言是否可用。
     *
     * @return 可用返回 true；引擎为空时返回 false
     */
    public boolean languageAvailable() {
        return engine != null && engine.languageAvailable();
    }

    /**
     * 识别单张图片（PNG/JPEG），低置信时按 ADR 旋转 90° 重试一次。
     *
     * @param imageBytes 原始图片字节
     * @param pageNo 页码（单图固定 1）
     * @return 页级结果
     */
    public OcrPageResult recognizeImage(byte[] imageBytes, int pageNo) {
        if (!enabled()) {
            return OcrPageResult.failure(pageNo, ParseErrorCodes.OCR_DISABLED, "OCR 未启用");
        }
        OcrImagePreprocessor.PreparedImage prepared;
        try {
            prepared = preprocessor.prepare(imageBytes);
        } catch (OcrException exception) {
            return OcrPageResult.failure(pageNo, exception.errorCode(), exception.getMessage());
        }
        OcrPageResult result = recognizePrepared(prepared, pageNo, properties.pageTimeout());
        if (result.success() && result.pageLowConfidence() && result.averageConfidence() != null) {
            // 只在图片路径重试；PDF 页有文本层与逐页预算，不做整页旋转。
            OcrImagePreprocessor.PreparedImage rotated;
            try {
                rotated = preprocessor.rotate90Clockwise(prepared);
            } catch (OcrException exception) {
                return result;
            }
            OcrPageResult retry = recognizePrepared(rotated, pageNo, properties.pageTimeout());
            if (retry.success() && higherConfidence(retry, result)) {
                LOG.info(
                        "ocr.image.rotate_retry pageNo={} firstConfidence={} retryConfidence={}",
                        pageNo,
                        result.averageConfidence(),
                        retry.averageConfidence());
                return retry;
            }
        }
        return result;
    }

    /**
     * 识别已渲染的 PDF 页。
     *
     * @param image 渲染图片
     * @param pageNo 页码
     * @param timeout 本次调用预算（不超过剩余文档预算）
     * @return 页级结果
     */
    public OcrPageResult recognizeRendered(BufferedImage image, int pageNo, Duration timeout) {
        if (!enabled()) {
            return OcrPageResult.failure(pageNo, ParseErrorCodes.OCR_DISABLED, "OCR 未启用");
        }
        OcrImagePreprocessor.PreparedImage prepared;
        try {
            prepared = preprocessor.prepareRendered(image);
        } catch (OcrException exception) {
            return OcrPageResult.failure(pageNo, exception.errorCode(), exception.getMessage());
        }
        return recognizePrepared(prepared, pageNo, timeout);
    }

    /**
     * 在 OCR 线程池中执行识别并应用单页超时。
     *
     * @param prepared 规范化图片
     * @param pageNo 页码
     * @param timeout 超时预算
     * @return 页级结果
     */
    private OcrPageResult recognizePrepared(OcrImagePreprocessor.PreparedImage prepared, int pageNo, Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return OcrPageResult.failure(pageNo, ParseErrorCodes.OCR_TIMEOUT, "OCR 识别超时");
        }
        Future<OcrResult> future = executor.submit(
                () -> engine.recognize(prepared.pngBytes(), new OcrOptions(pageNo, properties.pdfDpi())));
        try {
            OcrResult result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return toPageResult(pageNo, result);
        } catch (TimeoutException exception) {
            future.cancel(true);
            LOG.warn("ocr.page.timeout pageNo={} timeoutMs={}", pageNo, timeout.toMillis());
            return OcrPageResult.failure(pageNo, ParseErrorCodes.OCR_TIMEOUT, "OCR 识别超时，请稍后重试");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof OcrException ocrException) {
                return OcrPageResult.failure(pageNo, ocrException.errorCode(), ocrException.getMessage());
            }
            LOG.warn(
                    "ocr.page.failed pageNo={} reason={}",
                    pageNo,
                    cause == null ? "unknown" : cause.getClass().getSimpleName());
            return OcrPageResult.failure(pageNo, ParseErrorCodes.OCR_FAILED, "OCR 识别失败");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return OcrPageResult.failure(pageNo, ParseErrorCodes.OCR_FAILED, "OCR 识别被中断");
        }
    }

    /**
     * 将引擎结果映射为页级结果并计算低置信标记。
     *
     * @param pageNo 页码
     * @param result 引擎结果
     * @return 页级结果
     */
    private OcrPageResult toPageResult(int pageNo, OcrResult result) {
        if (result.blocks().isEmpty()) {
            return OcrPageResult.failure(pageNo, ParseErrorCodes.NO_TEXT, "OCR 未识别到可索引文本");
        }
        BigDecimal average = result.averageConfidence();
        boolean lowConfidence = result.blocks().stream()
                .anyMatch(block -> block.confidence().compareTo(properties.lowConfidenceThreshold()) < 0);
        boolean pageLowConfidence = average != null && average.compareTo(properties.pageLowConfidenceThreshold()) < 0;
        return OcrPageResult.success(pageNo, result.blocks(), average, lowConfidence, pageLowConfidence);
    }

    /**
     * 判断旋转重试是否优于首次结果。
     *
     * @param retry 重试结果
     * @param first 首次结果
     * @return 重试置信度更高返回 true
     */
    private static boolean higherConfidence(OcrPageResult retry, OcrPageResult first) {
        if (retry.averageConfidence() == null) {
            return false;
        }
        return first.averageConfidence() == null || retry.averageConfidence().compareTo(first.averageConfidence()) > 0;
    }

    /**
     * 返回当前块级低置信阈值，供解析器生成警告说明。
     *
     * @return 阈值
     */
    public BigDecimal lowConfidenceThreshold() {
        return properties.lowConfidenceThreshold();
    }

    /**
     * 返回失败页错误码是否可重试。
     *
     * @param errorCode 稳定错误码
     * @return 可重试返回 true
     */
    public static boolean isRetryable(String errorCode) {
        return ParseErrorCodes.OCR_TIMEOUT.equals(errorCode) || ParseErrorCodes.OCR_FAILED.equals(errorCode);
    }

    /** 关闭 OCR 线程池。 */
    @Override
    public void close() {
        executor.shutdownNow();
    }
}
