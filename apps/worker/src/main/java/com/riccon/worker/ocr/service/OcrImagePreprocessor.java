package com.riccon.worker.ocr.service;

import com.riccon.worker.ocr.config.OcrProperties;
import com.riccon.worker.ocr.domain.OcrException;
import com.riccon.worker.parse.domain.ParseErrorCodes;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.NodeList;

/**
 * OCR 前置处理：解码、EXIF 方向校正、像素/边长上限与缩放、转 PNG。
 *
 * <p>资源边界（ADR-0023 第 4.4 节、SEC-KB-005）：
 *
 * <ul>
 *   <li>解码前读取头部尺寸，超过 {@code max-decode-pixels} 直接拒绝，避免像素炸弹把堆打满；
 *   <li>超过 {@code max-pixels} 或 {@code max-long-edge} 时用子采样与等比缩放降低内存与 OCR 时间；
 *   <li>单页解码结果最多约 64 MB（4000×4000×4B），与 ADR 的单页内存上限一致。
 * </ul>
 *
 * <p>EXIF 只处理 90°/180°/270° 三个必需方向；其他方向按 0° 处理并记录 WARN，不伪造旋转。
 */
@Component
public final class OcrImagePreprocessor {

    /** 日志记录器。 */
    private static final Logger LOG = LoggerFactory.getLogger(OcrImagePreprocessor.class);

    /** EXIF APP1 marker（0xE1）。 */
    private static final int EXIF_MARKER = 0xE1;

    /** Orientation 标签。 */
    private static final int EXIF_ORIENTATION_TAG = 0x0112;

    /** 解码前硬上限（像素）。 */
    private final long maxDecodePixels;

    /** 目标最大像素。 */
    private final long maxPixels;

    /** 目标最长边。 */
    private final int maxLongEdge;

    /**
     * 创建预处理器。
     *
     * @param properties OCR 配置
     */
    public OcrImagePreprocessor(OcrProperties properties) {
        this.maxDecodePixels = properties.maxDecodePixels();
        this.maxPixels = properties.maxPixels();
        this.maxLongEdge = properties.maxLongEdge();
    }

    /**
     * 预处理上传图片：读取头部尺寸、解码、EXIF 方向校正、限幅并转 PNG。
     *
     * @param rawBytes 原始图片字节
     * @return 规范化 PNG 与尺寸
     * @throws OcrException 图片损坏或超出解码硬上限
     */
    public PreparedImage prepare(byte[] rawBytes) {
        if (rawBytes == null || rawBytes.length == 0) {
            throw new OcrException(ParseErrorCodes.PARSE_CORRUPT, false, "图片内容为空或不可读");
        }
        try (MemoryCacheImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(rawBytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new OcrException(ParseErrorCodes.PARSE_CORRUPT, false, "图片内容不可读或格式不支持");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                requireHeaderSize(width, height);
                int orientation = readExifOrientation(reader.getImageMetadata(0));
                BufferedImage decoded = decode(reader, width, height);
                return normalize(decoded, orientation);
            } finally {
                reader.dispose();
            }
        } catch (OcrException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new OcrException(ParseErrorCodes.PARSE_CORRUPT, false, "图片内容不可读或已损坏", exception);
        }
    }

    /**
     * 预处理 PDF 渲染页：不读 EXIF，只做像素/边长限制与 PNG 转换。
     *
     * @param image PDFBox 渲染结果
     * @return 规范化 PNG 与尺寸
     */
    public PreparedImage prepareRendered(BufferedImage image) {
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
            throw new OcrException(ParseErrorCodes.PARSE_CORRUPT, false, "PDF 页面渲染失败");
        }
        requireHeaderSize(image.getWidth(), image.getHeight());
        return normalize(image, 1);
    }

    /**
     * 顺时针旋转 90°，用于低置信图片的一次重试。
     *
     * @param prepared 已规范化图片
     * @return 旋转后的 PNG 与尺寸
     */
    public PreparedImage rotate90Clockwise(PreparedImage prepared) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(prepared.pngBytes()));
            if (source == null) {
                throw new OcrException(ParseErrorCodes.PARSE_CORRUPT, false, "图片内容不可读");
            }
            BufferedImage rotated =
                    new BufferedImage(source.getHeight(), source.getWidth(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = rotated.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, rotated.getWidth(), rotated.getHeight());
                graphics.drawImage(source, new AffineTransform(0, 1, -1, 0, source.getHeight(), 0), null);
            } finally {
                graphics.dispose();
            }
            return toPng(rotated);
        } catch (IOException | RuntimeException exception) {
            throw new OcrException(ParseErrorCodes.PARSE_CORRUPT, false, "图片旋转失败", exception);
        }
    }

    /**
     * 校验头部尺寸是否在解码硬上限内。
     *
     * @param width 宽度
     * @param height 高度
     */
    private void requireHeaderSize(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new OcrException(ParseErrorCodes.PARSE_CORRUPT, false, "图片尺寸非法");
        }
        long pixels = (long) width * height;
        if (pixels > maxDecodePixels) {
            // 不解码直接拒绝：恶意头部可声明超大尺寸，解码后才检查会先耗尽内存。
            throw new OcrException(
                    ParseErrorCodes.PARSE_RESOURCE_LIMIT,
                    false,
                    "图片像素超出 OCR 解码上限（" + pixels + " > " + maxDecodePixels + "）");
        }
    }

    /**
     * 按最大像素计算子采样并解码。
     *
     * @param reader 图片读取器
     * @param width 原始宽度
     * @param height 原始高度
     * @return 解码结果
     * @throws IOException 解码失败
     */
    private BufferedImage decode(ImageReader reader, int width, int height) throws IOException {
        int step = 1;
        while (scaledPixels(width, height, step) > maxPixels) {
            step++;
        }
        ImageReadParam param = reader.getDefaultReadParam();
        if (step > 1) {
            param.setSourceSubsampling(step, step, 0, 0);
        }
        BufferedImage image = reader.read(0, param);
        if (image == null) {
            throw new OcrException(ParseErrorCodes.PARSE_CORRUPT, false, "图片解码结果为空");
        }
        return image;
    }

    /**
     * 计算子采样后的像素数。
     *
     * @param width 原始宽度
     * @param height 原始高度
     * @param step 子采样步长
     * @return 像素数
     */
    private static long scaledPixels(int width, int height, int step) {
        long sampledWidth = (width + step - 1L) / step;
        long sampledHeight = (height + step - 1L) / step;
        return sampledWidth * sampledHeight;
    }

    /**
     * 应用 EXIF 方向、限幅并输出 PNG。
     *
     * @param image 解码图片
     * @param orientation EXIF orientation，1 表示无旋转
     * @return PNG 与尺寸
     */
    private PreparedImage normalize(BufferedImage image, int orientation) {
        BufferedImage oriented = applyOrientation(image, orientation);
        BufferedImage scaled = scaleDown(oriented);
        return toPng(scaled);
    }

    /**
     * 应用 EXIF 方向；不支持的方向按 0° 处理并记录 WARN。
     *
     * @param source 原图
     * @param orientation EXIF orientation
     * @return 校正后的图
     */
    private static BufferedImage applyOrientation(BufferedImage source, int orientation) {
        return switch (orientation) {
            case 1 -> source;
            case 3 -> rotate(source, 180);
            case 6 -> rotate(source, 90);
            case 8 -> rotate(source, 270);
            default -> {
                LOG.warn("ocr.image.exif_orientation_unsupported orientation={}", orientation);
                yield source;
            }
        };
    }

    /**
     * 按顺时针角度旋转图片。
     *
     * @param source 原图
     * @param degrees 90/180/270
     * @return 旋转后的 RGB 图
     */
    private static BufferedImage rotate(BufferedImage source, int degrees) {
        int width = source.getWidth();
        int height = source.getHeight();
        boolean swap = degrees == 90 || degrees == 270;
        int targetWidth = swap ? height : width;
        int targetHeight = swap ? width : height;
        BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, targetWidth, targetHeight);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            AffineTransform transform = switch (degrees) {
                case 90 -> new AffineTransform(0, 1, -1, 0, height, 0);
                case 180 -> new AffineTransform(-1, 0, 0, -1, width, height);
                case 270 -> new AffineTransform(0, -1, 1, 0, 0, width);
                default -> new AffineTransform();
            };
            graphics.drawImage(source, transform, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    /**
     * 等比缩放到目标像素与最长边以内。
     *
     * @param source 原图
     * @return 缩放图；无需缩放时返回原图
     */
    private BufferedImage scaleDown(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        double pixels = (double) width * height;
        double scale =
                Math.min(1.0, Math.min((double) maxLongEdge / Math.max(width, height), Math.sqrt(maxPixels / pixels)));
        if (scale >= 0.999) {
            return source;
        }
        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));
        BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, targetWidth, targetHeight);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }

    /**
     * 将图片转换为 PNG 字节，同时统一为 RGB（Tesseract 不需要 alpha）。
     *
     * @param source 图片
     * @return PNG 与尺寸
     */
    private static PreparedImage toPng(BufferedImage source) {
        BufferedImage rgb = source;
        if (source.getType() != BufferedImage.TYPE_INT_RGB) {
            rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = rgb.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, source.getWidth(), source.getHeight());
                graphics.drawImage(source, 0, 0, null);
            } finally {
                graphics.dispose();
            }
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(rgb, "png", output)) {
                throw new OcrException(ParseErrorCodes.PARSE_CORRUPT, false, "图片编码失败：未找到 PNG 写入器");
            }
            return new PreparedImage(output.toByteArray(), rgb.getWidth(), rgb.getHeight());
        } catch (IOException exception) {
            throw new OcrException(ParseErrorCodes.PARSE_CORRUPT, false, "图片编码失败", exception);
        }
    }

    /**
     * 从 JPEG 元数据读取 EXIF Orientation。
     *
     * @param metadata JPEG 元数据
     * @return orientation；缺失或不可解析时返回 1
     */
    static int readExifOrientation(IIOMetadata metadata) {
        if (metadata == null) {
            return 1;
        }
        try {
            if (!"javax_imageio_jpeg_image_1.0".equals(metadata.getNativeMetadataFormatName())) {
                return 1;
            }
            IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree("javax_imageio_jpeg_image_1.0");
            NodeList markers = root.getElementsByTagName("unknown");
            for (int index = 0; index < markers.getLength(); index++) {
                IIOMetadataNode marker = (IIOMetadataNode) markers.item(index);
                if (!Integer.toString(EXIF_MARKER).equals(marker.getAttribute("MarkerTag"))) {
                    continue;
                }
                Object userObject = marker.getUserObject();
                if (userObject instanceof byte[] data) {
                    int orientation = parseExifOrientation(data);
                    if (orientation >= 1 && orientation <= 8) {
                        return orientation;
                    }
                }
            }
        } catch (RuntimeException exception) {
            LOG.warn("ocr.image.exif_unreadable reason={}", exception.getClass().getSimpleName());
        }
        return 1;
    }

    /**
     * 解析 EXIF APP1 中的 Orientation 标签（TIFF IFD0）。
     *
     * @param data APP1 内容（可能含 {@code Exif\0\0} 前缀）
     * @return orientation；无法解析时返回 1
     */
    static int parseExifOrientation(byte[] data) {
        int offset = 0;
        if (data.length >= 6
                && data[0] == 'E'
                && data[1] == 'x'
                && data[2] == 'i'
                && data[3] == 'f'
                && data[4] == 0
                && data[5] == 0) {
            offset = 6;
        }
        if (data.length < offset + 8) {
            return 1;
        }
        boolean littleEndian = data[offset] == 'I' && data[offset + 1] == 'I';
        boolean bigEndian = data[offset] == 'M' && data[offset + 1] == 'M';
        if (!littleEndian && !bigEndian) {
            return 1;
        }
        int ifdOffset = readInt(data, offset + 4, littleEndian);
        int ifd = offset + ifdOffset;
        if (ifd < offset || ifd + 2 > data.length) {
            return 1;
        }
        int entryCount = readShort(data, ifd, littleEndian);
        int entry = ifd + 2;
        for (int index = 0; index < entryCount; index++, entry += 12) {
            if (entry + 12 > data.length) {
                return 1;
            }
            int tag = readShort(data, entry, littleEndian);
            if (tag != EXIF_ORIENTATION_TAG) {
                continue;
            }
            int type = readShort(data, entry + 2, littleEndian);
            int count = readInt(data, entry + 4, littleEndian);
            if (type == 3 && count == 1) {
                return readShort(data, entry + 8, littleEndian);
            }
            return 1;
        }
        return 1;
    }

    /**
     * 读取 16 位无符号整数。
     *
     * @param data 字节
     * @param offset 起始位置
     * @param littleEndian 是否小端
     * @return 无符号值
     */
    private static int readShort(byte[] data, int offset, boolean littleEndian) {
        if (offset + 2 > data.length) {
            return 0;
        }
        int first = data[offset] & 0xFF;
        int second = data[offset + 1] & 0xFF;
        return littleEndian ? (second << 8) | first : (first << 8) | second;
    }

    /**
     * 读取 32 位整数；EXIF 偏移量按无符号解释，但 Java int 足够覆盖文件长度范围。
     *
     * @param data 字节
     * @param offset 起始位置
     * @param littleEndian 是否小端
     * @return 值
     */
    private static int readInt(byte[] data, int offset, boolean littleEndian) {
        if (offset + 4 > data.length) {
            return 0;
        }
        int first = data[offset] & 0xFF;
        int second = data[offset + 1] & 0xFF;
        int third = data[offset + 2] & 0xFF;
        int fourth = data[offset + 3] & 0xFF;
        return littleEndian
                ? (fourth << 24) | (third << 16) | (second << 8) | first
                : (first << 24) | (second << 16) | (third << 8) | fourth;
    }

    /**
     * 预处理后的图片。
     *
     * @param pngBytes 规范化 PNG 字节
     * @param width 宽度
     * @param height 高度
     */
    public record PreparedImage(byte[] pngBytes, int width, int height) {

        /** 防御性复制字节。 */
        public PreparedImage {
            pngBytes = pngBytes.clone();
        }

        /** 返回 PNG 字节副本，避免调用方修改内部数组。 */
        @Override
        public byte[] pngBytes() {
            return pngBytes.clone();
        }
    }
}
