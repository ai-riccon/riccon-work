package com.riccon.api.error;

import org.springframework.http.HttpStatus;

/**
 * 单文件超过 200 MB。
 *
 * <p>同时用于 multipart 容器阈值与应用层大小校验，保证两条路径都返回 413 与稳定错误码。
 */
public class FileTooLargeException extends RicconException {

    /** 使用合同上限文案创建异常。 */
    public FileTooLargeException() {
        super(
                HttpStatus.PAYLOAD_TOO_LARGE,
                ErrorCodes.FILE_TOO_LARGE,
                "Payload too large",
                "file must not exceed 200 MB");
    }
}
