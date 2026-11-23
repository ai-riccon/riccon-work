package com.riccon.worker.ocr.support;

import com.riccon.worker.ocr.domain.OcrEngine;
import com.riccon.worker.ocr.domain.OcrOptions;
import com.riccon.worker.ocr.domain.OcrResult;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 测试用 OCR 引擎：按页码/调用次数返回预设结果，并记录收到的图片字节。
 *
 * <p>只在测试中使用；不加载原生库，保证默认测试 profile 无 Tesseract 时也能覆盖 SPI 与四态。
 */
public final class FakeOcrEngine implements OcrEngine {

    /** 调用次数。 */
    private final AtomicInteger calls = new AtomicInteger();

    /** 每次调用返回的结果生成器。 */
    private final Function<OcrOptions, OcrResult> responder;

    /** 收到的图片字节。 */
    private final List<byte[]> receivedImages = new CopyOnWriteArrayList<>();

    /** 语言是否可用。 */
    private final boolean languageAvailable;

    /**
     * 创建固定结果引擎。
     *
     * @param result 每次调用返回的结果
     */
    public FakeOcrEngine(OcrResult result) {
        this(options -> result, true);
    }

    /**
     * 创建按选项响应引擎。
     *
     * @param responder 结果生成器
     */
    public FakeOcrEngine(Function<OcrOptions, OcrResult> responder) {
        this(responder, true);
    }

    /**
     * 创建按选项响应引擎。
     *
     * @param responder 结果生成器
     * @param languageAvailable 语言是否可用
     */
    public FakeOcrEngine(Function<OcrOptions, OcrResult> responder, boolean languageAvailable) {
        this.responder = responder;
        this.languageAvailable = languageAvailable;
    }

    /** 返回引擎标识。 */
    @Override
    public String engineId() {
        return "fake";
    }

    /** 返回语言可用性。 */
    @Override
    public boolean languageAvailable() {
        return languageAvailable;
    }

    /**
     * 记录调用并返回预设结果。
     *
     * @param imageBytes 图片字节
     * @param options 选项
     * @return 预设结果
     */
    @Override
    public OcrResult recognize(byte[] imageBytes, OcrOptions options) {
        calls.incrementAndGet();
        receivedImages.add(imageBytes.clone());
        return responder.apply(options);
    }

    /** 返回调用次数。 */
    public int callCount() {
        return calls.get();
    }

    /** 返回收到的图片字节列表。 */
    public List<byte[]> receivedImages() {
        return List.copyOf(receivedImages);
    }
}
