package com.riccon.api.file.service;

import com.riccon.api.error.FileTooLargeException;
import com.riccon.api.error.InvalidFileException;
import com.riccon.api.file.domain.FileTypes;
import com.riccon.platform.domain.file.FileFormat;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * 上传文件预处理。
 *
 * <p>先校验声明大小与文件名，再把内容流式落盘并计算 SHA-256，最后用 magic bytes/OOXML 入口校验真实格式。
 * 临时文件由调用方在事务结束后删除；任何校验失败都会先清理临时文件再抛出业务异常。
 */
@Component
public class FileUploadPreparer {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(FileUploadPreparer.class);

    /** 流式复制缓冲区大小。 */
    private static final int BUFFER_SIZE = 8192;

    /**
     * 预处理上传文件。
     *
     * @param file multipart 文件
     * @return 已落盘、已校验的上传内容
     * @throws InvalidFileException 文件为空或文件名不合法
     * @throws FileTooLargeException 超过 200 MB
     * @throws com.riccon.api.error.UnsupportedFileTypeException 扩展名、MIME 或内容不受支持
     */
    public PreparedUpload prepare(MultipartFile file) {
        if (file == null) {
            throw new InvalidFileException("file is required");
        }
        if (file.getSize() <= 0) {
            throw new InvalidFileException("file must not be empty");
        }
        if (file.getSize() > FileTypes.MAX_SIZE_BYTES) {
            throw new FileTooLargeException();
        }
        String originalName = FileTypes.sanitizeOriginalName(file.getOriginalFilename());
        FileFormat format = FileTypes.formatForName(originalName);
        FileTypes.requireDeclaredType(format, file.getContentType());

        Path temporary = null;
        try {
            temporary = Files.createTempFile("riccon-upload-", ".tmp");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = copyAndHash(file, temporary, digest);
            if (total <= 0) {
                throw new InvalidFileException("file must not be empty");
            }
            if (total > FileTypes.MAX_SIZE_BYTES) {
                throw new FileTooLargeException();
            }
            FileTypes.requireContentMatches(temporary, format);
            return new PreparedUpload(
                    temporary,
                    originalName,
                    format,
                    FileTypes.mimeType(format),
                    total,
                    HexFormat.of().formatHex(digest.digest()));
        } catch (IOException exception) {
            deleteQuietly(temporary);
            throw new IllegalStateException("failed to read uploaded file", exception);
        } catch (NoSuchAlgorithmException exception) {
            deleteQuietly(temporary);
            throw new IllegalStateException("SHA-256 is not available", exception);
        } catch (RuntimeException exception) {
            deleteQuietly(temporary);
            throw exception;
        }
    }

    /**
     * 流式复制并更新摘要；同时按实际字节数执行上限兜底。
     *
     * @param file 上传文件
     * @param target 临时文件
     * @param digest SHA-256 摘要器
     * @return 实际字节数
     * @throws IOException 读写失败
     */
    private long copyAndHash(MultipartFile file, Path target, MessageDigest digest) throws IOException {
        long total = 0;
        try (InputStream input = file.getInputStream();
                OutputStream output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > FileTypes.MAX_SIZE_BYTES) {
                    throw new FileTooLargeException();
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        return total;
    }

    /**
     * 静默删除临时文件；失败不影响主异常。
     *
     * @param path 临时文件
     */
    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            // 清理失败只留下临时文件，不能覆盖原始校验异常；记录路径便于运维清理。
            LOG.warn("file.upload.temp_cleanup_failed path={}", path, exception);
        }
    }

    /**
     * 预处理结果。
     *
     * @param path 临时文件路径
     * @param originalName 清理后的原始文件名
     * @param format 业务格式
     * @param mimeType 服务端 canonical MIME
     * @param sizeBytes 实际字节数
     * @param sha256 内容 SHA-256
     */
    public record PreparedUpload(
            Path path, String originalName, FileFormat format, String mimeType, long sizeBytes, String sha256) {

        /** 校验字段完整。 */
        public PreparedUpload {
            Objects.requireNonNull(path, "path must not be null");
            Objects.requireNonNull(originalName, "originalName must not be null");
            Objects.requireNonNull(format, "format must not be null");
            Objects.requireNonNull(mimeType, "mimeType must not be null");
            Objects.requireNonNull(sha256, "sha256 must not be null");
        }
    }
}
