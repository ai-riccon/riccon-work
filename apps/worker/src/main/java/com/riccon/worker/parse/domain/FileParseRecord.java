package com.riccon.worker.parse.domain;

import com.riccon.platform.domain.file.ParseStatus;
import java.util.UUID;

/**
 * 文件解析尝试快照。
 *
 * @param id 解析尝试 ID
 * @param fileId 文件 ID
 * @param attempt 尝试号
 * @param status 当前状态
 */
public record FileParseRecord(UUID id, UUID fileId, int attempt, ParseStatus status) {}
