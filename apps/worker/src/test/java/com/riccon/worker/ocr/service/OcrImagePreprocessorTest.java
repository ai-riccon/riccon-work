package com.riccon.worker.ocr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.worker.ocr.config.OcrProperties;
import com.riccon.worker.ocr.domain.OcrException;
import com.riccon.worker.ocr.support.JpegExifFactory;
import com.riccon.worker.ocr.support.OcrTestSupport;
import com.riccon.worker.parse.domain.ParseErrorCodes;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** OCR 图片预处理器测试：EXIF 方向、像素上限、缩放与损坏输入。 */
class OcrImagePreprocessorTest {

    /** 生产默认配置。 */
    private final OcrImagePreprocessor preprocessor = new OcrImagePreprocessor(OcrTestSupport.defaults());

    /** 普通 PNG 保持原尺寸。 */
    @Test
    @DisplayName("prepare 小图保持尺寸")
    void prepare_smallPng_keepsDimensions() throws IOException {
        byte[] png = png(60, 30);

        OcrImagePreprocessor.PreparedImage prepared = preprocessor.prepare(png);

        assertThat(prepared.width()).isEqualTo(60);
        assertThat(prepared.height()).isEqualTo(30);
    }

    /** EXIF 6（90° CW）交换宽高并把左上象限转到右上。 */
    @Test
    @DisplayName("prepare EXIF 6 旋转 90° 顺时针")
    void prepare_exif6_rotatesClockwise() throws IOException {
        OcrImagePreprocessor.PreparedImage prepared = preprocessor.prepare(JpegExifFactory.jpegWithOrientation(6));

        assertThat(prepared.width()).isEqualTo(30);
        assertThat(prepared.height()).isEqualTo(60);
        BufferedImage image = decode(prepared);
        assertThat(dominant(image.getRGB(24, 10))).isEqualTo("red");
    }

    /** EXIF 3（180°）保持宽高并把左上象限转到右下。 */
    @Test
    @DisplayName("prepare EXIF 3 旋转 180°")
    void prepare_exif3_rotates180() throws IOException {
        OcrImagePreprocessor.PreparedImage prepared = preprocessor.prepare(JpegExifFactory.jpegWithOrientation(3));

        assertThat(prepared.width()).isEqualTo(60);
        assertThat(prepared.height()).isEqualTo(30);
        BufferedImage image = decode(prepared);
        assertThat(dominant(image.getRGB(54, 24))).isEqualTo("red");
    }

    /** EXIF 8（270° CW / 90° CCW）交换宽高并把左上象限转到左下。 */
    @Test
    @DisplayName("prepare EXIF 8 旋转 270°")
    void prepare_exif8_rotates270() throws IOException {
        OcrImagePreprocessor.PreparedImage prepared = preprocessor.prepare(JpegExifFactory.jpegWithOrientation(8));

        assertThat(prepared.width()).isEqualTo(30);
        assertThat(prepared.height()).isEqualTo(60);
        BufferedImage image = decode(prepared);
        assertThat(dominant(image.getRGB(5, 54))).isEqualTo("red");
    }

    /** 超过解码硬上限的头部尺寸在解码前被拒绝，避免像素炸弹。 */
    @Test
    @DisplayName("prepare 像素炸弹按资源上限拒绝")
    void prepare_pixelBomb_rejectedBeforeDecode() {
        byte[] bomb = pngHeader(100_000, 100_000);

        assertThatThrownBy(() -> preprocessor.prepare(bomb))
                .isInstanceOf(OcrException.class)
                .extracting(exception -> ((OcrException) exception).errorCode())
                .isEqualTo(ParseErrorCodes.PARSE_RESOURCE_LIMIT);
    }

    /** 损坏字节返回稳定损坏错误码。 */
    @Test
    @DisplayName("prepare 损坏图片返回 KB_PARSE_CORRUPT")
    void prepare_malformed_rejected() {
        assertThatThrownBy(() -> preprocessor.prepare(new byte[] {1, 2, 3, 4}))
                .isInstanceOf(OcrException.class)
                .extracting(exception -> ((OcrException) exception).errorCode())
                .isEqualTo(ParseErrorCodes.PARSE_CORRUPT);
    }

    /** 超过最长边时等比缩放。 */
    @Test
    @DisplayName("prepare 超过最长边等比缩放")
    void prepare_longEdge_scalesDown() throws IOException {
        OcrProperties properties = OcrTestSupport.properties(
                true, Duration.ofSeconds(5), Duration.ofSeconds(30), 100, 50_000_000L, 200_000_000L, 400);
        OcrImagePreprocessor small = new OcrImagePreprocessor(properties);

        OcrImagePreprocessor.PreparedImage prepared = small.prepare(png(800, 400));

        assertThat(prepared.width()).isEqualTo(400);
        assertThat(prepared.height()).isEqualTo(200);
    }

    /** 超过目标像素时用子采样解码，避免先解全尺寸再缩放。 */
    @Test
    @DisplayName("prepare 超过目标像素时子采样")
    void prepare_overTargetPixels_subsamples() throws IOException {
        OcrProperties properties = OcrTestSupport.properties(
                true, Duration.ofSeconds(5), Duration.ofSeconds(30), 100, 10_000L, 200_000_000L, 4000);
        OcrImagePreprocessor small = new OcrImagePreprocessor(properties);

        OcrImagePreprocessor.PreparedImage prepared = small.prepare(png(200, 100));

        assertThat(prepared.width() * prepared.height()).isLessThanOrEqualTo(10_000);
        assertThat(prepared.width()).isEqualTo(100);
        assertThat(prepared.height()).isEqualTo(50);
    }

    /** 渲染页同样应用缩放。 */
    @Test
    @DisplayName("prepareRendered 应用最长边限制")
    void prepareRendered_scalesDown() {
        OcrProperties properties = OcrTestSupport.properties(
                true, Duration.ofSeconds(5), Duration.ofSeconds(30), 100, 50_000_000L, 200_000_000L, 400);
        OcrImagePreprocessor small = new OcrImagePreprocessor(properties);
        BufferedImage image = new BufferedImage(800, 400, BufferedImage.TYPE_INT_RGB);

        OcrImagePreprocessor.PreparedImage prepared = small.prepareRendered(image);

        assertThat(prepared.width()).isEqualTo(400);
        assertThat(prepared.height()).isEqualTo(200);
    }

    /** 旋转 90° 后宽高交换。 */
    @Test
    @DisplayName("rotate90Clockwise 交换宽高")
    void rotate90Clockwise_swapsDimensions() throws IOException {
        OcrImagePreprocessor.PreparedImage prepared = preprocessor.prepare(png(60, 30));

        OcrImagePreprocessor.PreparedImage rotated = preprocessor.rotate90Clockwise(prepared);

        assertThat(rotated.width()).isEqualTo(30);
        assertThat(rotated.height()).isEqualTo(60);
    }

    /**
     * 生成纯色 PNG。
     *
     * @param width 宽
     * @param height 高
     * @return PNG 字节
     */
    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    /**
     * 只写 PNG 签名与 IHDR，不写像素数据；用于测试“解码前按头部尺寸拒绝”。
     *
     * @param width 声明宽度
     * @param height 声明高度
     * @return PNG 字节
     */
    private static byte[] pngHeader(int width, int height) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        ByteBuffer ihdr = ByteBuffer.allocate(13);
        ihdr.putInt(width);
        ihdr.putInt(height);
        ihdr.put((byte) 8);
        ihdr.put((byte) 2);
        ihdr.put((byte) 0);
        ihdr.put((byte) 0);
        ihdr.put((byte) 0);
        byte[] data = ihdr.array();
        output.writeBytes(intBytes(data.length));
        byte[] typeAndData = new byte[4 + data.length];
        System.arraycopy("IHDR".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, typeAndData, 0, 4);
        System.arraycopy(data, 0, typeAndData, 4, data.length);
        output.writeBytes(typeAndData);
        CRC32 crc = new CRC32();
        crc.update(typeAndData);
        output.writeBytes(intBytes((int) crc.getValue()));
        return output.toByteArray();
    }

    /**
     * 大端 4 字节。
     *
     * @param value 值
     * @return 字节
     */
    private static byte[] intBytes(int value) {
        return ByteBuffer.allocate(4).putInt(value).array();
    }

    /**
     * 解码 PNG。
     *
     * @param prepared 预处理结果
     * @return 图片
     */
    private static BufferedImage decode(OcrImagePreprocessor.PreparedImage prepared) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(prepared.pngBytes()));
        assertThat(image).isNotNull();
        return image;
    }

    /**
     * 判断颜色主通道（JPEG 压缩后允许偏差）。
     *
     * @param rgb RGB 值
     * @return red/green/blue/unknown
     */
    private static String dominant(int rgb) {
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        if (red > 150 && green < 100 && blue < 100) {
            return "red";
        }
        if (green > 150 && red < 100 && blue < 100) {
            return "green";
        }
        if (blue > 150 && red < 100 && green < 100) {
            return "blue";
        }
        return "unknown";
    }
}
