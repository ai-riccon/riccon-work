package com.riccon.api.file.domain;

import com.riccon.api.error.InvalidFileException;
import com.riccon.api.error.UnsupportedFileTypeException;
import com.riccon.platform.domain.file.FileFormat;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * 上传文件格式、MIME 与 magic bytes 校验。
 *
 * <p>安全边界：扩展名是主判据，但内容必须匹配；DOCX/XLSX 同为 ZIP 容器，必须检查 OOXML 入口文件，
 * 防止把任意 ZIP 伪装成文档。服务端最终保存的是嗅探后的 canonical MIME，而不是客户端声明值。
 */
public final class FileTypes {

    /** 单文件上限：200 MB（合同与 OpenAPI）。 */
    public static final long MAX_SIZE_BYTES = 200L * 1024 * 1024;

    /** 原始文件名列长度。 */
    private static final int MAX_NAME_LENGTH = 255;

    /** PDF canonical MIME。 */
    private static final String MIME_PDF = "application/pdf";

    /** DOCX canonical MIME。 */
    private static final String MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    /** XLSX canonical MIME。 */
    private static final String MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** PNG canonical MIME。 */
    private static final String MIME_PNG = "image/png";

    /** JPEG canonical MIME。 */
    private static final String MIME_JPEG = "image/jpeg";

    /** 扩展名到格式映射。 */
    private static final Map<String, FileFormat> EXTENSIONS = Map.of(
            ".pdf", FileFormat.PDF,
            ".docx", FileFormat.DOCX,
            ".xlsx", FileFormat.XLSX,
            ".png", FileFormat.PNG,
            ".jpg", FileFormat.JPEG,
            ".jpeg", FileFormat.JPEG);

    /** 每种格式允许的声明 MIME；空值与 octet-stream 视为未声明。 */
    private static final Map<FileFormat, Set<String>> MIME_TYPES = Map.of(
            FileFormat.PDF, Set.of(MIME_PDF),
            FileFormat.DOCX, Set.of(MIME_DOCX),
            FileFormat.XLSX, Set.of(MIME_XLSX),
            FileFormat.PNG, Set.of(MIME_PNG),
            FileFormat.JPEG, Set.of(MIME_JPEG, "image/jpg"));

    /** ZIP 文件头。 */
    private static final byte[] ZIP_MAGIC = {0x50, 0x4B, 0x03, 0x04};

    /** PDF 文件头。 */
    private static final byte[] PDF_MAGIC = {0x25, 0x50, 0x44, 0x46, 0x2D};

    /** PNG 文件头。 */
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    /** JPEG 文件头。 */
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    /** 工具类禁止实例化。 */
    private FileTypes() {}

    /**
     * 校验并清理原始文件名。
     *
     * <p>只保留 basename，存储 key 不包含文件名；超长、空白或含 NUL 的名称拒绝。
     *
     * @param originalFilename 客户端文件名
     * @return 清理后的文件名
     * @throws InvalidFileException 文件名为空、超长或含控制字符
     */
    public static String sanitizeOriginalName(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new InvalidFileException("file name is required");
        }
        int slash = Math.max(originalFilename.lastIndexOf('/'), originalFilename.lastIndexOf('\\'));
        String baseName = slash >= 0 ? originalFilename.substring(slash + 1) : originalFilename;
        String trimmed = baseName.strip();
        if (trimmed.isEmpty()) {
            throw new InvalidFileException("file name is required");
        }
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new InvalidFileException("file name must not exceed 255 characters");
        }
        if (trimmed.indexOf('\0') >= 0) {
            throw new InvalidFileException("file name contains illegal characters");
        }
        return trimmed;
    }

    /**
     * 根据扩展名确定业务格式。
     *
     * @param fileName 已清理文件名
     * @return 格式
     * @throws UnsupportedFileTypeException 扩展名缺失或不受支持
     */
    public static FileFormat formatForName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            throw new UnsupportedFileTypeException();
        }
        String extension = fileName.substring(dot).toLowerCase(Locale.ROOT);
        FileFormat format = EXTENSIONS.get(extension);
        if (format == null) {
            throw new UnsupportedFileTypeException();
        }
        return format;
    }

    /**
     * 返回格式的 canonical MIME。
     *
     * @param format 格式
     * @return MIME
     */
    public static String mimeType(FileFormat format) {
        return switch (format) {
            case PDF -> MIME_PDF;
            case DOCX -> MIME_DOCX;
            case XLSX -> MIME_XLSX;
            case PNG -> MIME_PNG;
            case JPEG -> MIME_JPEG;
        };
    }

    /**
     * 校验客户端声明的 Content-Type 与扩展名一致。
     *
     * <p>浏览器可能给空值或 {@code application/octet-stream}；这两种情况放行，最终以 magic bytes 为准。
     *
     * @param format 扩展名推导格式
     * @param declaredContentType 客户端声明 Content-Type
     * @throws UnsupportedFileTypeException 明确声明了不匹配的 MIME
     */
    public static void requireDeclaredType(FileFormat format, String declaredContentType) {
        if (declaredContentType == null || declaredContentType.isBlank()) {
            return;
        }
        String normalized = declaredContentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || "application/octet-stream".equals(normalized)) {
            return;
        }
        if (!MIME_TYPES.get(format).contains(normalized)) {
            throw new UnsupportedFileTypeException();
        }
    }

    /**
     * 校验文件内容与扩展名匹配。
     *
     * @param path 已落盘的临时文件
     * @param format 扩展名推导格式
     * @throws UnsupportedFileTypeException 内容不匹配或无法读取
     */
    public static void requireContentMatches(Path path, FileFormat format) {
        byte[] header = readHeader(path);
        boolean matches = switch (format) {
            case PDF -> startsWith(header, PDF_MAGIC);
            case PNG -> startsWith(header, PNG_MAGIC);
            case JPEG -> startsWith(header, JPEG_MAGIC);
            case DOCX -> startsWith(header, ZIP_MAGIC) && zipContains(path, "word/document.xml");
            case XLSX -> startsWith(header, ZIP_MAGIC) && zipContains(path, "xl/workbook.xml");
        };
        if (!matches) {
            throw new UnsupportedFileTypeException();
        }
    }

    /**
     * 读取文件头 8 字节。
     *
     * @param path 文件路径
     * @return 实际读取的字节
     */
    private static byte[] readHeader(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            return input.readNBytes(8);
        } catch (IOException exception) {
            throw new UnsupportedFileTypeException();
        }
    }

    /**
     * 判断字节数组是否以指定前缀开头。
     *
     * @param content 内容
     * @param prefix 前缀
     * @return 匹配时返回 {@code true}
     */
    private static boolean startsWith(byte[] content, byte[] prefix) {
        if (content.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (content[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 检查 ZIP 容器中是否存在指定入口。
     *
     * @param path ZIP 文件
     * @param requiredEntry 必需入口
     * @return 存在时返回 {@code true}
     */
    private static boolean zipContains(Path path, String requiredEntry) {
        try (ZipFile zipFile = new ZipFile(path.toFile())) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (requiredEntry.equals(entry.getName())) {
                    return true;
                }
            }
            return false;
        } catch (ZipException exception) {
            return false;
        } catch (IOException exception) {
            return false;
        }
    }
}
