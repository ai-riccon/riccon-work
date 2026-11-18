package com.riccon.api.file.storage;

/**
 * 对象存储读取失败。
 *
 * <p>包装 S3 客户端异常与 IO 异常，避免业务层依赖具体 SDK；异常消息不包含存储凭据或完整 URL。
 */
public class StorageReadException extends RuntimeException {

    /**
     * 创建读取失败异常。
     *
     * @param message 服务端可读原因
     * @param cause 底层异常
     */
    public StorageReadException(String message, Throwable cause) {
        super(message, cause);
    }
}
