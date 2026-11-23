package com.riccon.worker.ocr.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.worker.ocr.config.OcrProperties;
import com.riccon.worker.ocr.domain.OcrBlock;
import com.riccon.worker.ocr.domain.OcrException;
import com.riccon.worker.ocr.domain.OcrPageResult;
import com.riccon.worker.ocr.domain.OcrResult;
import com.riccon.worker.ocr.support.FakeOcrEngine;
import com.riccon.worker.ocr.support.OcrTestSupport;
import com.riccon.worker.parse.domain.BoundingBox;
import com.riccon.worker.parse.domain.ParseErrorCodes;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** OCR 服务测试：关闭开关、四态、单页超时与低置信旋转重试。 */
class OcrServiceTest {

    /** 关闭开关时不加载引擎并返回 KB_OCR_DISABLED。 */
    @Test
    @DisplayName("recognizeImage OCR 关闭返回 KB_OCR_DISABLED")
    void recognizeImage_disabled_returnsDisabled() {
        OcrProperties properties = OcrTestSupport.disabled();
        OcrService service = new OcrService(
                (com.riccon.worker.ocr.domain.OcrEngine) null, properties, new OcrImagePreprocessor(properties));
        try {
            OcrPageResult result = service.recognizeImage(new byte[0], 1);

            assertThat(service.enabled()).isFalse();
            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ParseErrorCodes.OCR_DISABLED);
        } finally {
            service.close();
        }
    }

    /** 高置信结果不带低置信标记。 */
    @Test
    @DisplayName("recognizeRendered 高置信成功")
    void recognizeRendered_highConfidence_success() {
        OcrBlock block = OcrTestSupport.block("hello", "0.950", 1, 1, new BoundingBox(0, 0, 20, 10));
        FakeOcrEngine engine = new FakeOcrEngine(new OcrResult(List.of(block), 100, 100));
        OcrService service = service(engine, OcrTestSupport.defaults());
        try {
            OcrPageResult result = service.recognizeRendered(image(100, 100), 1, java.time.Duration.ofSeconds(1));

            assertThat(result.success()).isTrue();
            assertThat(result.lowConfidence()).isFalse();
            assertThat(result.pageLowConfidence()).isFalse();
            assertThat(result.averageConfidence()).isEqualByComparingTo("0.950");
        } finally {
            service.close();
        }
    }

    /** 块级低置信但页均正常时只标记 lowConfidence。 */
    @Test
    @DisplayName("recognizeRendered 块级低置信仍成功")
    void recognizeRendered_lowBlockConfidence_marksLowConfidence() {
        OcrBlock low = OcrTestSupport.block("low", "0.500", 1, 1, new BoundingBox(0, 0, 20, 10));
        OcrBlock high = OcrTestSupport.block("high", "0.900", 1, 2, new BoundingBox(0, 10, 20, 10));
        FakeOcrEngine engine = new FakeOcrEngine(new OcrResult(List.of(low, high), 100, 100));
        OcrService service = service(engine, OcrTestSupport.defaults());
        try {
            OcrPageResult result = service.recognizeRendered(image(100, 100), 1, java.time.Duration.ofSeconds(1));

            assertThat(result.success()).isTrue();
            assertThat(result.lowConfidence()).isTrue();
            assertThat(result.pageLowConfidence()).isFalse();
        } finally {
            service.close();
        }
    }

    /** 页均低于 0.45 时标记 pageLowConfidence，但仍有文本可检索。 */
    @Test
    @DisplayName("recognizeRendered 页级低置信仍成功")
    void recognizeRendered_pageLowConfidence_marksPageLow() {
        OcrBlock low = OcrTestSupport.block("low", "0.400", 1, 1, new BoundingBox(0, 0, 20, 10));
        FakeOcrEngine engine = new FakeOcrEngine(new OcrResult(List.of(low), 100, 100));
        OcrService service = service(engine, OcrTestSupport.defaults());
        try {
            OcrPageResult result = service.recognizeRendered(image(100, 100), 1, java.time.Duration.ofSeconds(1));

            assertThat(result.success()).isTrue();
            assertThat(result.pageLowConfidence()).isTrue();
        } finally {
            service.close();
        }
    }

    /** 引擎无文本返回 KB_NO_TEXT。 */
    @Test
    @DisplayName("recognizeRendered 无文本返回 KB_NO_TEXT")
    void recognizeRendered_noText_returnsNoText() {
        FakeOcrEngine engine = new FakeOcrEngine(new OcrResult(List.of(), 100, 100));
        OcrService service = service(engine, OcrTestSupport.defaults());
        try {
            OcrPageResult result = service.recognizeRendered(image(100, 100), 1, java.time.Duration.ofSeconds(1));

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ParseErrorCodes.NO_TEXT);
        } finally {
            service.close();
        }
    }

    /** 引擎异常映射为 KB_OCR_FAILED。 */
    @Test
    @DisplayName("recognizeRendered 引擎异常返回 KB_OCR_FAILED")
    void recognizeRendered_engineFailure_returnsOcrFailed() {
        FakeOcrEngine engine = new FakeOcrEngine(options -> {
            throw new OcrException(ParseErrorCodes.OCR_FAILED, true, "engine failed");
        });
        OcrService service = service(engine, OcrTestSupport.defaults());
        try {
            OcrPageResult result = service.recognizeRendered(image(100, 100), 1, java.time.Duration.ofSeconds(1));

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ParseErrorCodes.OCR_FAILED);
        } finally {
            service.close();
        }
    }

    /** 单页超时返回 KB_OCR_TIMEOUT 并取消任务。 */
    @Test
    @DisplayName("recognizeRendered 单页超时返回 KB_OCR_TIMEOUT")
    void recognizeRendered_pageTimeout_returnsTimeout() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeOcrEngine engine = new FakeOcrEngine(options -> {
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return new OcrResult(List.of(), 100, 100);
        });
        OcrProperties properties = OcrTestSupport.properties(
                true,
                java.time.Duration.ofMillis(50),
                java.time.Duration.ofSeconds(5),
                100,
                50_000_000L,
                200_000_000L,
                4000);
        OcrService service = service(engine, properties);
        try {
            OcrPageResult result = service.recognizeRendered(image(100, 100), 1, java.time.Duration.ofMillis(50));

            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ParseErrorCodes.OCR_TIMEOUT);
        } finally {
            release.countDown();
            service.close();
        }
    }

    /** OCR 只在专用线程池执行，不占用调用方（heavy/chat）线程。 */
    @Test
    @DisplayName("recognizeRendered 在 riccon-ocr 专用线程执行")
    void recognizeRendered_runsOnOcrExecutorThread() {
        AtomicReference<String> threadName = new AtomicReference<>();
        FakeOcrEngine engine = new FakeOcrEngine(options -> {
            threadName.set(Thread.currentThread().getName());
            return new OcrResult(
                    List.of(new OcrBlock(
                            "text", new java.math.BigDecimal("0.900"), new BoundingBox(0, 0, 20, 10), 1, 1)),
                    100,
                    100);
        });
        OcrService service = service(engine, OcrTestSupport.defaults());
        try {
            service.recognizeRendered(image(100, 100), 1, java.time.Duration.ofSeconds(1));

            assertThat(threadName.get()).startsWith("riccon-ocr");
            assertThat(threadName.get()).isNotEqualTo(Thread.currentThread().getName());
        } finally {
            service.close();
        }
    }

    /** 低置信图片旋转 90° 重试一次，重试更高时采用重试结果。 */
    @Test
    @DisplayName("recognizeImage 低置信旋转重试一次")
    void recognizeImage_lowConfidence_retriesOnceWithRotation() throws Exception {
        OcrBlock low = OcrTestSupport.block("low", "0.300", 1, 1, new BoundingBox(0, 0, 20, 10));
        OcrBlock high = OcrTestSupport.block("high", "0.950", 1, 1, new BoundingBox(0, 0, 20, 10));
        FakeOcrEngine engine = new FakeOcrEngine(options -> engineResult(engineResultCounter++, low, high));
        OcrService service = service(engine, OcrTestSupport.defaults());
        try {
            OcrPageResult result = service.recognizeImage(png(60, 30), 1);

            assertThat(engine.callCount()).isEqualTo(2);
            assertThat(result.success()).isTrue();
            assertThat(result.averageConfidence()).isEqualByComparingTo("0.950");
        } finally {
            service.close();
        }
    }

    /** 旋转重试仍低置信时只重试一次并保留首次结果。 */
    @Test
    @DisplayName("recognizeImage 旋转重试最多一次")
    void recognizeImage_retryStillLow_keepsFirst() throws Exception {
        OcrBlock low = OcrTestSupport.block("low", "0.300", 1, 1, new BoundingBox(0, 0, 20, 10));
        FakeOcrEngine engine = new FakeOcrEngine(new OcrResult(List.of(low), 60, 30));
        OcrService service = service(engine, OcrTestSupport.defaults());
        try {
            OcrPageResult result = service.recognizeImage(png(60, 30), 1);

            assertThat(engine.callCount()).isEqualTo(2);
            assertThat(result.averageConfidence()).isEqualByComparingTo("0.300");
        } finally {
            service.close();
        }
    }

    /** 旋转重试的调用计数器。 */
    private int engineResultCounter;

    /**
     * 按顺序返回低/高置信结果。
     *
     * @param call 调用序号
     * @param low 低置信结果
     * @param high 高置信结果
     * @return 结果
     */
    private static OcrResult engineResult(int call, OcrBlock low, OcrBlock high) {
        return new OcrResult(List.of(call == 0 ? low : high), 60, 30);
    }

    /**
     * 构造服务。
     *
     * @param engine 引擎
     * @param properties 配置
     * @return 服务
     */
    private static OcrService service(com.riccon.worker.ocr.domain.OcrEngine engine, OcrProperties properties) {
        return new OcrService(engine, properties, new OcrImagePreprocessor(properties));
    }

    /**
     * 生成纯色图片。
     *
     * @param width 宽
     * @param height 高
     * @return 图片
     */
    private static BufferedImage image(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    /**
     * 生成 PNG 字节。
     *
     * @param width 宽
     * @param height 高
     * @return PNG 字节
     */
    private static byte[] png(int width, int height) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image(width, height), "png", output);
        return output.toByteArray();
    }
}
