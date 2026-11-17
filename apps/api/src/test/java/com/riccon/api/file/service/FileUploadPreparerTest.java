package com.riccon.api.file.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.api.common.Sha256;
import com.riccon.api.error.FileTooLargeException;
import com.riccon.api.error.InvalidFileException;
import com.riccon.api.error.UnsupportedFileTypeException;
import com.riccon.api.file.domain.FileTypes;
import com.riccon.api.support.TestFiles;
import com.riccon.platform.domain.file.FileFormat;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/** 上传预处理：大小、文件名、MIME、magic bytes 与摘要。 */
class FileUploadPreparerTest {

    /** 预处理器。 */
    private final FileUploadPreparer preparer = new FileUploadPreparer();

    /** 合法 PDF 落盘、计算摘要并返回 canonical 元数据。 */
    @Test
    void prepare_validPdf_returnsPreparedUpload() throws IOException {
        byte[] content = TestFiles.pdf();
        MockMultipartFile file = new MockMultipartFile("file", "报告.pdf", "application/pdf", content);
        FileUploadPreparer.PreparedUpload prepared = preparer.prepare(file);
        try {
            assertThat(prepared.originalName()).isEqualTo("报告.pdf");
            assertThat(prepared.format()).isEqualTo(FileFormat.PDF);
            assertThat(prepared.mimeType()).isEqualTo("application/pdf");
            assertThat(prepared.sizeBytes()).isEqualTo(content.length);
            assertThat(prepared.sha256()).isEqualTo(Sha256.hex(content));
            assertThat(Files.readAllBytes(prepared.path())).isEqualTo(content);
        } finally {
            Files.deleteIfExists(prepared.path());
        }
    }

    /** 空文件与 null 文件返回 400 业务异常。 */
    @Test
    void prepare_emptyFile_throwsInvalidFile() {
        assertThatThrownBy(
                        () -> preparer.prepare(new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[0])))
                .isInstanceOf(InvalidFileException.class);
        assertThatThrownBy(() -> preparer.prepare(null)).isInstanceOf(InvalidFileException.class);
    }

    /** 声明大小超过 200 MB 时在读取前拒绝。 */
    @Test
    void prepare_oversizedFile_throwsFileTooLarge() {
        MockMultipartFile file = new MockMultipartFile("file", "big.pdf", "application/pdf", new byte[0]) {
            @Override
            public long getSize() {
                return FileTypes.MAX_SIZE_BYTES + 1;
            }
        };
        assertThatThrownBy(() -> preparer.prepare(file)).isInstanceOf(FileTooLargeException.class);
    }

    /** 内容与扩展名不一致时拒绝，并且不把临时文件留给调用方。 */
    @Test
    void prepare_wrongMagic_throwsUnsupported() {
        MockMultipartFile file = new MockMultipartFile("file", "fake.pdf", "application/pdf", TestFiles.png());
        assertThatThrownBy(() -> preparer.prepare(file)).isInstanceOf(UnsupportedFileTypeException.class);
    }

    /** 声明 MIME 与扩展名不一致时拒绝。 */
    @Test
    void prepare_wrongDeclaredMime_throwsUnsupported() {
        MockMultipartFile file = new MockMultipartFile("file", "a.pdf", "image/png", TestFiles.pdf());
        assertThatThrownBy(() -> preparer.prepare(file)).isInstanceOf(UnsupportedFileTypeException.class);
    }

    /** DOCX/XLSX 必须包含 OOXML 入口。 */
    @Test
    void prepare_validDocxAndXlsx_pass() throws IOException {
        FileUploadPreparer.PreparedUpload docx = preparer.prepare(new MockMultipartFile(
                "file",
                "a.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                TestFiles.docx()));
        FileUploadPreparer.PreparedUpload xlsx = preparer.prepare(new MockMultipartFile(
                "file",
                "a.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                TestFiles.xlsx()));
        try {
            assertThat(docx.format()).isEqualTo(FileFormat.DOCX);
            assertThat(xlsx.format()).isEqualTo(FileFormat.XLSX);
        } finally {
            Files.deleteIfExists(docx.path());
            Files.deleteIfExists(xlsx.path());
        }
    }
}
