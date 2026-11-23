package com.riccon.worker.connector.service;

import com.riccon.worker.config.StorageProperties;
import com.riccon.worker.connector.domain.ConnectorErrorCodes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * 连接器原文写入平台对象存储。
 *
 * <p>源端 S3 客户端只用于 List/Get/Head；连接器下载内容必须写入平台对象存储（与
 * {@code S3FileContentReader} 相同的 {@link StorageProperties#bucket()}），不能写回源桶或使用源凭据。
 * key 由同步服务派生为 {@code tenants/{tenantId}/connector/{yyyy}/{documentId}}。
 *
 * <p>写前再次检查 100 MB 硬上限；超限或写入失败抛出 {@link ObjectWriteException}，由同步服务把当前项标记
 * FAILED，不创建文档。
 */
@Component
public class ConnectorObjectWriter {

    /** 单对象硬上限（100 MB）。 */
    public static final long MAX_OBJECT_BYTES = 100L * 1024 * 1024;

    /** 平台对象存储客户端（与文件解析/索引共用同一 bean）。 */
    private final S3Client platformS3Client;

    /** 平台存储配置。 */
    private final StorageProperties storageProperties;

    /**
     * 创建写入器。
     *
     * @param platformS3Client 平台 S3 客户端
     * @param storageProperties 平台存储配置
     */
    public ConnectorObjectWriter(S3Client platformS3Client, StorageProperties storageProperties) {
        this.platformS3Client = Objects.requireNonNull(platformS3Client, "platformS3Client must not be null");
        this.storageProperties = Objects.requireNonNull(storageProperties, "storageProperties must not be null");
    }

    /**
     * 把下载文件写入平台桶。
     *
     * @param key 平台对象 key
     * @param path 临时文件
     * @param mimeType MIME
     * @throws ObjectWriteException 超限或写入失败
     */
    public void write(String key, Path path, String mimeType) {
        try {
            long size = Files.size(path);
            if (size > MAX_OBJECT_BYTES) {
                throw new ObjectWriteException(ConnectorErrorCodes.OBJECT_TOO_LARGE);
            }
            platformS3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(storageProperties.bucket())
                            .key(key)
                            .contentType(mimeType)
                            .build(),
                    RequestBody.fromFile(path));
        } catch (S3Exception exception) {
            throw new ObjectWriteException(
                    exception.statusCode() == 401 || exception.statusCode() == 403
                            ? ConnectorErrorCodes.AUTH
                            : ConnectorErrorCodes.OBJECT_FAILED);
        } catch (SdkClientException exception) {
            throw new ObjectWriteException(ConnectorErrorCodes.OBJECT_FAILED);
        } catch (IOException exception) {
            throw new ObjectWriteException(ConnectorErrorCodes.OBJECT_FAILED);
        }
    }

    /**
     * 平台桶写入失败；携带稳定错误码供条目记录。
     */
    public static class ObjectWriteException extends RuntimeException {

        /** 稳定错误码。 */
        private final String errorCode;

        /**
         * 创建异常。
         *
         * @param errorCode 稳定错误码
         */
        public ObjectWriteException(String errorCode) {
            super(errorCode);
            this.errorCode = errorCode;
        }

        /** 返回错误码。 */
        public String errorCode() {
            return errorCode;
        }
    }

    /**
     * 返回平台桶名，仅供测试/排障断言，不写入日志。
     *
     * @return 平台桶名
     */
    String platformBucket() {
        return storageProperties.bucket();
    }
}
