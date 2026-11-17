package com.riccon.api.file.domain;

import com.riccon.platform.domain.file.ParseStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 单次文件解析尝试。
 *
 * <p>同一文件重试会产生新的 attempt；历史失败记录保留，不覆盖。错误码与脱敏原因由 Worker 回写。
 *
 * @param id 解析尝试 ID
 * @param attempt 尝试号，从 1 开始
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
public record FileParseRecord(
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

    /** 校验必填字段。 */
    public FileParseRecord {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1");
        }
    }
}
