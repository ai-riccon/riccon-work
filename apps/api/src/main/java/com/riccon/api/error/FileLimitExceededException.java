package com.riccon.api.error;

import org.springframework.http.HttpStatus;

/**
 * 单会话文件数量超过合同上限（5 个）。
 *
 * <p>文件层做最终兜底；会话层仍应在附件选择阶段提前提示，避免用户体验到服务端 409。
 */
public class FileLimitExceededException extends RicconException {

    /** 使用固定错误码与通用说明创建异常。 */
    public FileLimitExceededException() {
        super(
                HttpStatus.CONFLICT,
                ErrorCodes.FILE_LIMIT_EXCEEDED,
                "File limit exceeded",
                "a conversation can contain at most 5 files");
    }
}
