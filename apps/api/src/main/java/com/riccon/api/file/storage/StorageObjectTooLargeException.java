package com.riccon.api.file.storage;

/**
 * 对象正文超过调用方允许读取的上限。
 *
 * <p>只暴露大小信息，不包含对象 key 或存储地址；业务层负责映射为对外稳定的 413 错误码。
 */
public class StorageObjectTooLargeException extends RuntimeException {

    /**
     * 创建超限异常。
     *
     * @param actualBytes 对象实际大小（读取前已知时）或已读取字节数
     * @param maxBytes 允许的最大字节数
     */
    public StorageObjectTooLargeException(long actualBytes, long maxBytes) {
        super("stored object exceeds limit: actual=" + actualBytes + ", max=" + maxBytes);
    }
}
