package com.riccon.api.error;

import org.springframework.http.HttpStatus;

/**
 * 文件扩展名、Content-Type 与内容嗅探结果不一致或不受支持。
 *
 * <p>对外只说明允许的格式，不回显 magic bytes 或客户端提供的任意字符串，避免把不可信输入带入日志与响应。
 */
public class UnsupportedFileTypeException extends RicconException {

    /** 使用统一支持格式说明创建异常。 */
    public UnsupportedFileTypeException() {
        super(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                ErrorCodes.FILE_UNSUPPORTED,
                "Unsupported media type",
                "only PDF, DOCX, XLSX, PNG and JPEG are supported");
    }
}
