package com.riccon.worker.ocr.service;

import com.riccon.worker.ocr.config.OcrProperties;
import com.riccon.worker.ocr.domain.OcrBlock;
import com.riccon.worker.ocr.domain.OcrEngine;
import com.riccon.worker.ocr.domain.OcrException;
import com.riccon.worker.ocr.domain.OcrManifest;
import com.riccon.worker.ocr.domain.OcrOptions;
import com.riccon.worker.ocr.domain.OcrResult;
import com.riccon.worker.parse.domain.BoundingBox;
import com.riccon.worker.parse.domain.ParseErrorCodes;
import com.sun.jna.Pointer;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.imageio.ImageIO;
import net.sourceforge.tess4j.ITessAPI;
import net.sourceforge.tess4j.TessAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Tesseract OCR 引擎（Tess4J/JNA，ADR-0023 第 4.1/4.2 节）。
 *
 * <p>启动自检：仅在 {@code riccon.worker.ocr.enabled=true} 时装配本 Bean；构造期触发 JNA 加载
 * {@code libtesseract}，失败直接让 Worker 启动失败（生产 fail-fast）。语言包缺失不阻断启动，
 * 记录固定 WARN 键 {@code ocr.engine}/{@code ocr.lang}/{@code ocr.model_hash}，并在识别调用时
 * 返回 {@code KB_OCR_LANG_UNAVAILABLE}。
 *
 * <p>实现只使用 Tess4J 低层 {@link TessAPI}：直接以 BGR 像素缓冲调用
 * {@code TessBaseAPISetImage/Recognize} 并按行读取 ResultIterator，不经过
 * {@code Tesseract.getWords} 的 Leptonica/Pix 路径。这样 JVM 只需加载 {@code libtesseract}
 * 与 tessdata，镜像无需额外安装 {@code libleptonica-dev} 符号链接，也避免 Tesseract 类型
 * 泄漏到 SPI。
 *
 * <p>线程模型：每个 OCR 线程持有独立 {@code TessBaseAPI} 句柄（ThreadLocal），因为 Tesseract
 * 句柄不支持并发调用；并发上限由 {@link OcrService} 的固定线程池控制。
 */
@Component
@ConditionalOnProperty(prefix = "riccon.worker.ocr", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TesseractOcrEngine implements OcrEngine, AutoCloseable {

    /** 日志记录器。 */
    private static final Logger LOG = LoggerFactory.getLogger(TesseractOcrEngine.class);

    /** 引擎标识。 */
    public static final String ENGINE_ID = "tesseract";

    /** 每像素字节数（BGR）。 */
    private static final int BYTES_PER_PIXEL = 3;

    /** 行级迭代粒度。 */
    private static final int LEVEL_TEXT_LINE = ITessAPI.TessPageIteratorLevel.RIL_TEXTLINE;

    /** OCR 配置。 */
    private final OcrProperties properties;

    /** 镜像指纹，只用于日志与信息端点。 */
    private final OcrManifest manifest;

    /** Tesseract 版本。 */
    private final String engineVersion;

    /** 配置语言是否已完成初始化探测。 */
    private final boolean languageAvailable;

    /** 每线程独立 Tesseract 句柄。 */
    private final ThreadLocal<ITessAPI.TessBaseAPI> clients;

    /** 已创建的句柄，供关闭时释放 native 资源。 */
    private final Queue<ITessAPI.TessBaseAPI> handles = new ConcurrentLinkedQueue<>();

    /**
     * 创建并自检 Tesseract 引擎。
     *
     * @param properties OCR 配置
     * @param manifest 镜像指纹
     * @throws IllegalStateException 原生库不可用（生产启用时拒绝启动）
     */
    public TesseractOcrEngine(OcrProperties properties, OcrManifest manifest) {
        this.properties = properties;
        this.manifest = manifest;
        if (!ENGINE_ID.equalsIgnoreCase(properties.engine())) {
            throw new IllegalStateException("不支持的 OCR 引擎：" + properties.engine() + "（当前仅支持 tesseract）");
        }
        this.engineVersion = loadNativeVersion();
        this.languageAvailable = probeLanguage();
        this.clients = ThreadLocal.withInitial(this::createClient);
        if (languageAvailable) {
            LOG.info(
                    "ocr.engine.ready ocr.engine={} ocr.version={} ocr.lang={} ocr.model_hash={}",
                    ENGINE_ID,
                    engineVersion,
                    properties.language(),
                    manifest.modelHash());
        } else {
            LOG.warn(
                    "ocr.language.unavailable ocr.engine={} ocr.lang={} ocr.model_hash={}",
                    ENGINE_ID,
                    properties.language(),
                    manifest.modelHash());
        }
    }

    /** 返回引擎标识。 */
    @Override
    public String engineId() {
        return ENGINE_ID;
    }

    /** 返回配置语言是否可用。 */
    @Override
    public boolean languageAvailable() {
        return languageAvailable;
    }

    /**
     * 返回 Tesseract 原生库版本，供启动日志与排障使用。
     *
     * @return 版本字符串
     */
    public String engineVersion() {
        return engineVersion;
    }

    /**
     * 识别一张规范化后的图片。
     *
     * @param imageBytes PNG/JPEG 字节
     * @param options 页码与 DPI
     * @return 识别结果
     * @throws OcrException 语言包缺失、图片损坏或引擎失败
     */
    @Override
    public OcrResult recognize(byte[] imageBytes, OcrOptions options) {
        if (!languageAvailable) {
            throw new OcrException(ParseErrorCodes.OCR_LANG_UNAVAILABLE, false, "OCR 语言包不可用：" + properties.language());
        }
        BufferedImage image = decode(imageBytes);
        ITessAPI.TessBaseAPI handle = clients.get();
        ByteBuffer buffer = toBgrBuffer(image);
        try {
            TessAPI api = TessAPI.INSTANCE;
            api.TessBaseAPISetImage(
                    handle,
                    buffer,
                    image.getWidth(),
                    image.getHeight(),
                    BYTES_PER_PIXEL,
                    image.getWidth() * BYTES_PER_PIXEL);
            int dpi = options.dpi() > 0 ? options.dpi() : properties.pdfDpi();
            if (dpi > 0) {
                api.TessBaseAPISetSourceResolution(handle, dpi);
            }
            api.TessBaseAPISetPageSegMode(handle, ITessAPI.TessPageSegMode.PSM_AUTO);
            int result = api.TessBaseAPIRecognize(handle, null);
            if (result != 0) {
                throw new OcrException(ParseErrorCodes.OCR_FAILED, true, "OCR 引擎识别失败");
            }
            List<OcrBlock> blocks = readLines(handle, options.pageNo());
            return new OcrResult(blocks, image.getWidth(), image.getHeight());
        } catch (OcrException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // 原生崩溃会直接终止进程，由 outbox 重启恢复；这里只处理可捕获的库错误。
            throw new OcrException(ParseErrorCodes.OCR_FAILED, true, "OCR 引擎识别失败", exception);
        } finally {
            // 及时释放上一页图像，避免长文档在 native 侧累积。
            TessAPI.INSTANCE.TessBaseAPIClear(handle);
        }
    }

    /**
     * 从 ResultIterator 读取行文本、置信度与 bbox。
     *
     * @param handle Tesseract 句柄
     * @param pageNo 页码
     * @return 行级文本块
     */
    private static List<OcrBlock> readLines(ITessAPI.TessBaseAPI handle, int pageNo) {
        TessAPI api = TessAPI.INSTANCE;
        ITessAPI.TessResultIterator iterator = api.TessBaseAPIGetIterator(handle);
        if (iterator == null) {
            return List.of();
        }
        List<OcrBlock> blocks = new ArrayList<>();
        try {
            ITessAPI.TessPageIterator pageIterator = api.TessResultIteratorGetPageIterator(iterator);
            if (pageIterator == null) {
                return List.of();
            }
            api.TessPageIteratorBegin(pageIterator);
            do {
                Pointer textPointer = api.TessResultIteratorGetUTF8Text(iterator, LEVEL_TEXT_LINE);
                if (textPointer == null) {
                    continue;
                }
                String text;
                try {
                    text = textPointer.getString(0, "UTF-8");
                } finally {
                    api.TessDeleteText(textPointer);
                }
                if (text == null || text.isBlank()) {
                    continue;
                }
                IntBuffer left = IntBuffer.allocate(1);
                IntBuffer top = IntBuffer.allocate(1);
                IntBuffer right = IntBuffer.allocate(1);
                IntBuffer bottom = IntBuffer.allocate(1);
                api.TessPageIteratorBoundingBox(pageIterator, LEVEL_TEXT_LINE, left, top, right, bottom);
                int x = Math.max(0, left.get());
                int y = Math.max(0, top.get());
                int width = right.get() - x;
                int height = bottom.get() - y;
                if (width <= 0 || height <= 0) {
                    continue;
                }
                float rawConfidence = api.TessResultIteratorConfidence(iterator, LEVEL_TEXT_LINE);
                blocks.add(new OcrBlock(
                        text.strip(),
                        normalizeConfidence(rawConfidence),
                        new BoundingBox(x, y, width, height),
                        blocks.size() + 1,
                        pageNo));
            } while (api.TessPageIteratorNext(pageIterator, LEVEL_TEXT_LINE) == ITessAPI.TRUE);
        } finally {
            api.TessResultIteratorDelete(iterator);
        }
        return List.copyOf(blocks);
    }

    /**
     * 将 Tesseract 0–100 置信度归一化到 0–1；负值（非文本层级）按 0 处理。
     *
     * @param rawConfidence 原始置信度
     * @return 0–1 的置信度，保留 3 位小数
     */
    static BigDecimal normalizeConfidence(float rawConfidence) {
        double value = rawConfidence <= 0 ? 0 : Math.min(rawConfidence, 100) / 100.0;
        return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP);
    }

    /**
     * 加载 libtesseract 并返回版本；失败即启动失败。
     *
     * @return Tesseract 版本
     */
    private static String loadNativeVersion() {
        try {
            String version = TessAPI.INSTANCE.TessVersion();
            return version == null || version.isBlank() ? OcrManifest.UNKNOWN : version;
        } catch (LinkageError exception) {
            throw new IllegalStateException("OCR 引擎原生库不可用：无法加载 libtesseract", exception);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("OCR 引擎原生库不可用：无法初始化 libtesseract", exception);
        }
    }

    /**
     * 探测配置语言是否可初始化；缺失只告警，不阻断启动。
     *
     * @return 可用返回 true
     */
    private boolean probeLanguage() {
        ITessAPI.TessBaseAPI handle = null;
        try {
            handle = TessAPI.INSTANCE.TessBaseAPICreate();
            int result = TessAPI.INSTANCE.TessBaseAPIInit3(handle, datapath(), properties.language());
            return result == 0;
        } catch (LinkageError | RuntimeException exception) {
            LOG.warn(
                    "ocr.language.probe_failed ocr.engine={} ocr.lang={} reason={}",
                    ENGINE_ID,
                    properties.language(),
                    exception.getClass().getSimpleName());
            return false;
        } finally {
            if (handle != null) {
                TessAPI.INSTANCE.TessBaseAPIDelete(handle);
            }
        }
    }

    /**
     * 创建线程私有 Tesseract 句柄并初始化语言。
     *
     * @return 已初始化的句柄
     */
    private ITessAPI.TessBaseAPI createClient() {
        ITessAPI.TessBaseAPI handle = TessAPI.INSTANCE.TessBaseAPICreate();
        int result;
        try {
            result = TessAPI.INSTANCE.TessBaseAPIInit3(handle, datapath(), properties.language());
        } catch (RuntimeException | LinkageError exception) {
            TessAPI.INSTANCE.TessBaseAPIDelete(handle);
            throw new OcrException(ParseErrorCodes.OCR_FAILED, true, "OCR 引擎初始化失败", exception);
        }
        if (result != 0) {
            TessAPI.INSTANCE.TessBaseAPIDelete(handle);
            throw new OcrException(ParseErrorCodes.OCR_LANG_UNAVAILABLE, false, "OCR 语言包不可用：" + properties.language());
        }
        TessAPI.INSTANCE.TessBaseAPISetPageSegMode(handle, ITessAPI.TessPageSegMode.PSM_AUTO);
        handles.add(handle);
        return handle;
    }

    /**
     * 返回配置的 tessdata 目录；空值返回 null 以使用 Tesseract 默认路径。
     *
     * @return 目录或 null
     */
    private String datapath() {
        String path = properties.tessdataPath();
        return path == null || path.isBlank() ? null : path;
    }

    /**
     * 把 RGB 图片转换为 Tesseract 需要的 BGR 直接缓冲。
     *
     * <p>先绘制到 {@code TYPE_3BYTE_BGR}，由 JDK 完成颜色转换；随后复制到 direct buffer，
     * 避免 JNA 在 native 调用期间持有堆数组引用。
     *
     * @param image 图片
     * @return BGR 直接缓冲
     */
    private static ByteBuffer toBgrBuffer(BufferedImage image) {
        BufferedImage bgr = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D graphics = bgr.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        byte[] data = ((DataBufferByte) bgr.getRaster().getDataBuffer()).getData();
        ByteBuffer buffer = ByteBuffer.allocateDirect(data.length);
        buffer.put(data);
        buffer.flip();
        return buffer;
    }

    /**
     * 解码图片字节。
     *
     * @param imageBytes 图片字节
     * @return 图片
     * @throws OcrException 图片损坏
     */
    private static BufferedImage decode(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new OcrException(ParseErrorCodes.PARSE_CORRUPT, false, "图片内容为空或不可读");
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                throw new OcrException(ParseErrorCodes.PARSE_CORRUPT, false, "图片内容不可读或格式不支持");
            }
            return image;
        } catch (IOException exception) {
            throw new OcrException(ParseErrorCodes.PARSE_CORRUPT, false, "图片内容不可读或已损坏", exception);
        }
    }

    /**
     * 释放所有线程的 Tesseract 句柄；进程退出或 Spring 上下文关闭时调用。
     *
     * <p>不释放会导致 Leptonica 在进程退出时打印 ObjectCache LEAK 警告；幂等，可重复调用。
     */
    @jakarta.annotation.PreDestroy
    @Override
    public void close() {
        ITessAPI.TessBaseAPI handle;
        while ((handle = handles.poll()) != null) {
            TessAPI.INSTANCE.TessBaseAPIDelete(handle);
        }
        clients.remove();
    }
}
