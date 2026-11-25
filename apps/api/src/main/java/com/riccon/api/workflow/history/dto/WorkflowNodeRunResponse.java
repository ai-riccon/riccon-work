package com.riccon.api.workflow.history.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.riccon.platform.domain.workflow.WorkflowErrorCode;
import com.riccon.platform.domain.workflow.WorkflowNodeStatus;
import com.riccon.platform.domain.workflow.WorkflowNodeType;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 节点运行时间线条目（契约 {@code WorkflowNodeRun}）。
 *
 * <p>同一节点的每次重试都是一条独立记录（{@code attempt} 递增），供详情展示完整时间线；{@code inputSummary}/
 * {@code outputSummary} 由服务端生成，不含完整提示词或工具参数。{@code approvalId}/{@code invocationId}
 * 是逻辑引用，客户端据此跳转审批或工具记录（ADR-0024 §3.3.4、AC-13）。
 *
 * @param id 节点运行 ID
 * @param nodeId 图内节点 key
 * @param nodeType 节点类型
 * @param name 节点名
 * @param status 节点状态
 * @param attempt 尝试号
 * @param startedAt 开始时间
 * @param finishedAt 完成时间
 * @param durationMs 耗时
 * @param inputSummary 输入摘要
 * @param outputSummary 输出摘要
 * @param errorCode 稳定错误码
 * @param errorMessage 脱敏原因
 * @param approvalId 审批 ID
 * @param invocationId 工具调用 ID
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowNodeRunResponse(
        UUID id,
        String nodeId,
        WorkflowNodeType nodeType,
        String name,
        WorkflowNodeStatus status,
        int attempt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        Long durationMs,
        String inputSummary,
        String outputSummary,
        WorkflowErrorCode errorCode,
        String errorMessage,
        UUID approvalId,
        UUID invocationId) {}
