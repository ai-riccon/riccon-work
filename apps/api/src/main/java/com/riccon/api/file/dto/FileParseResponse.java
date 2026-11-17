package com.riccon.api.file.dto;

import com.riccon.api.file.domain.FileParseRecord;
import com.riccon.platform.domain.file.ParseStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * 文件解析尝试响应，对应 OpenAPI {@code FileParse}。
 *
 * @param id 解析尝试 ID
 * @param attempt 尝试号
 * @param status 解析状态
 * @param parserVersion 解析器版本
 * @param pageCount 页数
 * @param sheetCount 工作表数
 * @param chunkCount 分块数
 * @param errorCode 失败错误码
 * @param errorMessage 脱敏失败原因
 * @param startedAt 开始时间
 * @param finishedAt 结束时间
 */
public record FileParseResponse(
        UUID id,
        int attempt,
        ParseStatus status,
        String parserVersion,
        Integer pageCount,
        Integer sheetCount,
        Integer chunkCount,
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt) {

    /**
     * 映射解析尝试。
     *
     * @param parse 解析尝试；为空时返回 {@code null}
     * @return 响应或 {@code null}
     */
    public static FileParseResponse from(FileParseRecord parse) {
        if (parse == null) {
            return null;
        }
        return new FileParseResponse(
                parse.id(),
                parse.attempt(),
                parse.status(),
                parse.parserVersion(),
                parse.pageCount(),
                parse.sheetCount(),
                parse.chunkCount(),
                parse.errorCode(),
                parse.errorMessage(),
                parse.startedAt(),
                parse.finishedAt());
    }
}
