package com.riccon.worker.research.domain;

import com.riccon.platform.domain.research.StageStatus;
import java.util.UUID;

/**
 * 运行阶段记录快照。
 *
 * @param id 阶段记录 ID
 * @param stage 阶段名
 * @param attemptNo 尝试号
 * @param status 状态
 * @param detailsJson 白名单摘要 JSON
 */
public record RunStageRecord(UUID id, String stage, int attemptNo, StageStatus status, String detailsJson) {}
