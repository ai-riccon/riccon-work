package com.riccon.api.error;

import org.springframework.http.HttpStatus;

/**
 * 产物正文暂时不可读。
 *
 * <p>对象存储失败、对象缺失或网络异常统一映射为 502，响应不包含对象 key、存储 URL 或凭据；
 * 具体原因只写服务端日志，供运维排查。
 */
public class ArtifactContentUnavailableException extends RicconException {

    /** 使用固定脱敏文案创建异常。 */
    public ArtifactContentUnavailableException() {
        super(
                HttpStatus.BAD_GATEWAY,
                ErrorCodes.ARTIFACT_CONTENT_UNAVAILABLE,
                "Artifact content unavailable",
                "artifact content is temporarily unavailable");
    }
}
