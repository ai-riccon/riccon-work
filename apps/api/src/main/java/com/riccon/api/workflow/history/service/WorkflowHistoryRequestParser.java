package com.riccon.api.workflow.history.service;

import com.riccon.api.workflow.history.domain.WorkflowHistoryFilter;
import com.riccon.api.workflow.run.service.WorkflowRunBadRequestException;
import com.riccon.platform.domain.workflow.WorkflowRunStatus;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 运行历史查询参数解析。
 *
 * <p>查询参数错误统一返回 400 {@code RICCON-WF-400}，说明只含字段名与格式要求，不回显原始输入；
 * 时间必须带偏移（如 {@code 2026-11-25T10:00:00+08:00}），与合同 date-time 和项目展示口径一致。
 */
@Component
public class WorkflowHistoryRequestParser {

    /**
     * 解析过滤条件。
     *
     * @param workflowId 流程 ID 原文；可空
     * @param versionNo 版本号；可空
     * @param status 运行状态；可空
     * @param requesterUserId 发起人 ID 原文；可空
     * @param from 创建时间起点原文；可空
     * @param to 创建时间终点原文；可空
     * @return 过滤条件
     * @throws WorkflowRunBadRequestException ID/时间格式非法，或 from 晚于 to
     */
    public WorkflowHistoryFilter parseFilter(
            String workflowId,
            Integer versionNo,
            WorkflowRunStatus status,
            String requesterUserId,
            String from,
            String to) {
        UUID workflow = parseUuid(workflowId, "workflowId");
        UUID requester = parseUuid(requesterUserId, "requesterUserId");
        OffsetDateTime fromTime = parseTime(from, "from");
        OffsetDateTime toTime = parseTime(to, "to");
        if (fromTime != null && toTime != null && fromTime.isAfter(toTime)) {
            throw new WorkflowRunBadRequestException("from must not be after to");
        }
        if (versionNo != null && versionNo < 1) {
            throw new WorkflowRunBadRequestException("versionNo must be >= 1");
        }
        return new WorkflowHistoryFilter(
                workflow,
                versionNo,
                status,
                requester,
                fromTime == null ? null : fromTime.toInstant(),
                toTime == null ? null : toTime.toInstant());
    }

    /**
     * 解析路径中的流程 ID。
     *
     * @param value 路径参数
     * @return 流程 ID
     * @throws WorkflowRunBadRequestException 非 UUID
     */
    public UUID parseWorkflowId(String value) {
        UUID workflowId = parseUuid(value, "workflowId");
        if (workflowId == null) {
            throw new WorkflowRunBadRequestException("workflowId is required");
        }
        return workflowId;
    }

    /**
     * 解析路径中的运行 ID。
     *
     * @param value 路径参数
     * @return 运行 ID
     * @throws WorkflowRunBadRequestException 非 UUID
     */
    public UUID parseRunId(String value) {
        UUID runId = parseUuid(value, "workflowRunId");
        if (runId == null) {
            throw new WorkflowRunBadRequestException("workflowRunId is required");
        }
        return runId;
    }

    /**
     * 解析可选 UUID。
     *
     * @param value 原文；可空
     * @param field 字段名
     * @return UUID 或 {@code null}
     * @throws WorkflowRunBadRequestException 格式非法
     */
    private static UUID parseUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new WorkflowRunBadRequestException(field + " must be a UUID");
        }
    }

    /**
     * 解析可空时间。
     *
     * @param value 原文；可空
     * @param field 字段名
     * @return 带偏移时间或 {@code null}
     * @throws WorkflowRunBadRequestException 缺少偏移或格式非法
     */
    private static OffsetDateTime parseTime(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.trim());
        } catch (DateTimeParseException exception) {
            throw new WorkflowRunBadRequestException(field + " must be an ISO-8601 date-time with offset");
        }
    }
}
