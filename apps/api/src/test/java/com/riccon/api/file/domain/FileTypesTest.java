package com.riccon.api.file.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.api.error.InvalidFileException;
import com.riccon.api.error.UnsupportedFileTypeException;
import com.riccon.api.support.TestFiles;
import com.riccon.platform.domain.file.FileFormat;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 文件格式、MIME、magic bytes 与文件名清理。 */
class FileTypesTest {

    /** 临时目录。 */
    @TempDir
    Path tempDir;

    /** 扩展名映射覆盖五种格式与大小写。 */
    @Test
    void formatForName_supportedExtensions_returnsFormat() {
        assertThat(FileTypes.formatForName("a.pdf")).isEqualTo(FileFormat.PDF);
        assertThat(FileTypes.formatForName("a.DOCX")).isEqualTo(FileFormat.DOCX);
        assertThat(FileTypes.formatForName("a.xlsx")).isEqualTo(FileFormat.XLSX);
        assertThat(FileTypes.formatForName("a.png")).isEqualTo(FileFormat.PNG);
        assertThat(FileTypes.formatForName("a.jpg")).isEqualTo(FileFormat.JPEG);
        assertThat(FileTypes.formatForName("a.jpeg")).isEqualTo(FileFormat.JPEG);
    }

    /** 缺失或未知扩展名返回 415 业务异常。 */
    @Test
    void formatForName_unknownExtension_throwsUnsupported() {
        assertThatThrownBy(() -> FileTypes.formatForName("a.txt")).isInstanceOf(UnsupportedFileTypeException.class);
        assertThatThrownBy(() -> FileTypes.formatForName("a")).isInstanceOf(UnsupportedFileTypeException.class);
        assertThatThrownBy(() -> FileTypes.formatForName("a.")).isInstanceOf(UnsupportedFileTypeException.class);
    }

    /** 文件名只保留 basename 并拒绝空值、超长与 NUL。 */
    @Test
    void sanitizeOriginalName_stripsPathAndRejectsInvalid() {
        assertThat(FileTypes.sanitizeOriginalName("/tmp/evil.pdf")).isEqualTo("evil.pdf");
        assertThat(FileTypes.sanitizeOriginalName("C:\\tmp\\evil.pdf")).isEqualTo("evil.pdf");
        assertThat(FileTypes.sanitizeOriginalName("  report.pdf  ")).isEqualTo("report.pdf");
        assertThatThrownBy(() -> FileTypes.sanitizeOriginalName(" ")).isInstanceOf(InvalidFileException.class);
        assertThatThrownBy(() -> FileTypes.sanitizeOriginalName(null)).isInstanceOf(InvalidFileException.class);
        assertThatThrownBy(() -> FileTypes.sanitizeOriginalName("x".repeat(256)))
                .isInstanceOf(InvalidFileException.class);
        assertThatThrownBy(() -> FileTypes.sanitizeOriginalName("bad\0name.pdf"))
                .isInstanceOf(InvalidFileException.class);
    }

    /** canonical MIME 与声明 MIME 校验。 */
    @Test
    void requireDeclaredType_onlyRejectsExplicitMismatch() {
        assertThatCode(() -> FileTypes.requireDeclaredType(FileFormat.PDF, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> FileTypes.requireDeclaredType(FileFormat.PDF, "application/octet-stream"))
                .doesNotThrowAnyException();
        assertThatCode(() -> FileTypes.requireDeclaredType(FileFormat.PDF, "application/pdf; charset=binary"))
                .doesNotThrowAnyException();
        assertThatCode(() -> FileTypes.requireDeclaredType(FileFormat.JPEG, "image/jpg"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> FileTypes.requireDeclaredType(FileFormat.PDF, "image/png"))
                .isInstanceOf(UnsupportedFileTypeException.class);
    }

    /** 五种格式的 magic bytes 与 OOXML 入口校验。 */
    @Test
    void requireContentMatches_supportedContent_passes() throws IOException {
        assertThatCode(() -> FileTypes.requireContentMatches(write("a.pdf", TestFiles.pdf()), FileFormat.PDF))
                .doesNotThrowAnyException();
        assertThatCode(() -> FileTypes.requireContentMatches(write("a.docx", TestFiles.docx()), FileFormat.DOCX))
                .doesNotThrowAnyException();
        assertThatCode(() -> FileTypes.requireContentMatches(write("a.xlsx", TestFiles.xlsx()), FileFormat.XLSX))
                .doesNotThrowAnyException();
        assertThatCode(() -> FileTypes.requireContentMatches(write("a.png", TestFiles.png()), FileFormat.PNG))
                .doesNotThrowAnyException();
        assertThatCode(() -> FileTypes.requireContentMatches(write("a.jpeg", TestFiles.jpeg()), FileFormat.JPEG))
                .doesNotThrowAnyException();
    }

    /** 内容与扩展名不一致、ZIP 缺少 OOXML 入口或文件不可读时拒绝。 */
    @Test
    void requireContentMatches_invalidContent_throwsUnsupported() throws IOException {
        Path png = write("png", TestFiles.png());
        assertThatThrownBy(() -> FileTypes.requireContentMatches(png, FileFormat.PDF))
                .isInstanceOf(UnsupportedFileTypeException.class);
        Path genericZip = write("zip", TestFiles.zip(Map.of("[Content_Types].xml", "<Types/>")));
        assertThatThrownBy(() -> FileTypes.requireContentMatches(genericZip, FileFormat.DOCX))
                .isInstanceOf(UnsupportedFileTypeException.class);
        assertThatThrownBy(() -> FileTypes.requireContentMatches(genericZip, FileFormat.XLSX))
                .isInstanceOf(UnsupportedFileTypeException.class);
        assertThatThrownBy(() -> FileTypes.requireContentMatches(tempDir.resolve("missing"), FileFormat.PDF))
                .isInstanceOf(UnsupportedFileTypeException.class);
    }

    /** 上限常量与合同一致。 */
    @Test
    void maxSizeBytes_matchesContract() {
        assertThat(FileTypes.MAX_SIZE_BYTES).isEqualTo(200L * 1024 * 1024);
    }

    /**
     * 写入临时文件。
     *
     * @param name 文件名
     * @param content 内容
     * @return 路径
     * @throws IOException 写入失败
     */
    private Path write(String name, byte[] content) throws IOException {
        Path path = tempDir.resolve(name);
        Files.write(path, content);
        return path;
    }
}
