package com.riccon.api.error;

import org.springframework.http.HttpStatus;

/**
 * 上传文件缺少必要元数据或内容为空。
 *
 * <p>对外返回 400，错误码固定 {@code RICCON-FILE-400}；说明只描述请求问题，不包含文件名之外的任何内容。
 */
public class InvalidFileException extends RicconException {

    /**
     * 创建文件请求错误。
     *
     * @param detail 服务端可读原因
     */
    public InvalidFileException(String detail) {
        super(HttpStatus.BAD_REQUEST, ErrorCodes.FILE_INVALID, "Invalid file", detail);
    }
}
