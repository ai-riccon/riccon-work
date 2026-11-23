package com.riccon.worker.ocr.support;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * 生成带 EXIF Orientation 的 JPEG 夹具。
 *
 * <p>JDK ImageIO 写出的 JPEG 只有 APP0 JFIF；这里在 APP0 之后插入最小 APP1 EXIF（TIFF LE +
 * IFD0 一个 Orientation 条目），用于覆盖 90°/180°/270° 校正，不引入二进制夹具或第三方库。
 */
public final class JpegExifFactory {

    /** 夹具宽度：四个象限 30×15。 */
    private static final int WIDTH = 60;

    /** 夹具高度。 */
    private static final int HEIGHT = 30;

    /** 工具类禁止实例化。 */
    private JpegExifFactory() {}

    /**
     * 生成带指定 Orientation 的 60×30 JPEG。
     *
     * <p>象限颜色：左上红、右上蓝、左下绿、右下黄；旋转后可断言颜色位置。
     *
     * @param orientation EXIF orientation（1/3/6/8）
     * @return JPEG 字节
     */
    public static byte[] jpegWithOrientation(int orientation) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(255, 0, 0));
            graphics.fillRect(0, 0, WIDTH / 2, HEIGHT / 2);
            graphics.setColor(new Color(0, 0, 255));
            graphics.fillRect(WIDTH / 2, 0, WIDTH / 2, HEIGHT / 2);
            graphics.setColor(new Color(0, 255, 0));
            graphics.fillRect(0, HEIGHT / 2, WIDTH / 2, HEIGHT / 2);
            graphics.setColor(new Color(255, 255, 0));
            graphics.fillRect(WIDTH / 2, HEIGHT / 2, WIDTH / 2, HEIGHT / 2);
        } finally {
            graphics.dispose();
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "jpg", output);
            return spliceExif(output.toByteArray(), buildExifApp1(orientation));
        } catch (IOException exception) {
            throw new IllegalStateException("failed to create exif jpeg", exception);
        }
    }

    /**
     * 在 APP0 之后插入 APP1 段。
     *
     * @param jpeg 原始 JPEG
     * @param app1 APP1 段
     * @return 带 EXIF 的 JPEG
     */
    private static byte[] spliceExif(byte[] jpeg, byte[] app1) {
        int insertAt = 2;
        if (jpeg.length >= 6 && (jpeg[2] & 0xFF) == 0xFF && (jpeg[3] & 0xFF) == 0xE0) {
            int app0Length = ((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF);
            insertAt = 4 + app0Length;
        }
        byte[] result = new byte[jpeg.length + app1.length];
        System.arraycopy(jpeg, 0, result, 0, insertAt);
        System.arraycopy(app1, 0, result, insertAt, app1.length);
        System.arraycopy(jpeg, insertAt, result, insertAt + app1.length, jpeg.length - insertAt);
        return result;
    }

    /**
     * 构建最小 APP1 EXIF 段（小端 TIFF，IFD0 仅 Orientation）。
     *
     * @param orientation orientation 值
     * @return APP1 段（含 marker 与长度）
     */
    private static byte[] buildExifApp1(int orientation) {
        byte[] tiff = new byte[] {
            'I', 'I', 42, 0, 8, 0, 0, 0, 1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0, (byte) orientation, 0, 0, 0, 0, 0, 0, 0
        };
        int payloadLength = 6 + tiff.length;
        int segmentLength = payloadLength + 2;
        byte[] segment = new byte[4 + payloadLength];
        segment[0] = (byte) 0xFF;
        segment[1] = (byte) 0xE1;
        segment[2] = (byte) (segmentLength >> 8);
        segment[3] = (byte) segmentLength;
        segment[4] = 'E';
        segment[5] = 'x';
        segment[6] = 'i';
        segment[7] = 'f';
        segment[8] = 0;
        segment[9] = 0;
        System.arraycopy(tiff, 0, segment, 10, tiff.length);
        return segment;
    }
}
