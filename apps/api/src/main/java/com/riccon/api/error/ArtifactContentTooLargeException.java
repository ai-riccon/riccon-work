package com.riccon.api.error;

import org.springframework.http.HttpStatus;

/**
 * 产物正文超过同源代理上限（2 MiB）。
 *
 * <p>不区分是数据库元数据已超限还是对象实际大小超限，避免客户端依赖服务端存储细节。
 */
public class ArtifactContentTooLargeException extends RicconException {

    /** 使用固定大小上限文案创建异常。 */
    public ArtifactContentTooLargeException() {
        super(
                HttpStatus.PAYLOAD_TOO_LARGE,
                ErrorCodes.ARTIFACT_CONTENT_TOO_LARGE,
                "Payload too large",
                "artifact content must not exceed 2 MiB");
    }
}
