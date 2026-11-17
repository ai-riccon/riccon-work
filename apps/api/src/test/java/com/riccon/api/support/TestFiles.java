package com.riccon.api.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 测试文件内容生成器。
 *
 * <p>只生成满足 API 扩展名、MIME 与 magic bytes 校验所需的最小结构；API 集成测试不执行 Worker 解析，
 * 因此不引入 PDFBox/POI 生成完整文档。
 */
public final class TestFiles {

    /** 工具类禁止实例化。 */
    private TestFiles() {}

    /**
     * 最小 PDF 内容。
     *
     * @return 以 {@code %PDF-} 开头的字节
     */
    public static byte[] pdf() {
        return "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * 最小 PNG 内容。
     *
     * @return PNG 签名与占位数据
     */
    public static byte[] png() {
        byte[] signature = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        byte[] body = "png-body".getBytes(StandardCharsets.US_ASCII);
        byte[] content = new byte[signature.length + body.length];
        System.arraycopy(signature, 0, content, 0, signature.length);
        System.arraycopy(body, 0, content, signature.length, body.length);
        return content;
    }

    /**
     * 最小 JPEG 内容。
     *
     * @return JPEG 文件头与占位数据
     */
    public static byte[] jpeg() {
        byte[] signature = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
        byte[] body = "jpeg-body".getBytes(StandardCharsets.US_ASCII);
        byte[] content = new byte[signature.length + body.length];
        System.arraycopy(signature, 0, content, 0, signature.length);
        System.arraycopy(body, 0, content, signature.length, body.length);
        return content;
    }

    /**
     * 最小 DOCX 容器；只需包含 OOXML 入口。
     *
     * @return ZIP 字节
     */
    public static byte[] docx() {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("[Content_Types].xml", "<Types/>");
        entries.put("word/document.xml", "<document/>");
        return zip(entries);
    }

    /**
     * 最小 XLSX 容器；只需包含 OOXML 入口。
     *
     * @return ZIP 字节
     */
    public static byte[] xlsx() {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("[Content_Types].xml", "<Types/>");
        entries.put("xl/workbook.xml", "<workbook/>");
        return zip(entries);
    }

    /**
     * 构造通用 ZIP。
     *
     * @param entries 入口名到文本内容
     * @return ZIP 字节
     */
    public static byte[] zip(Map<String, String> entries) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(output)) {
                for (Map.Entry<String, String> entry : entries.entrySet()) {
                    zip.putNextEntry(new ZipEntry(entry.getKey()));
                    zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("failed to build test zip", exception);
        }
    }
}
