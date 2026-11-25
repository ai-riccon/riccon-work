package com.riccon.worker.workflow.engine.domain;

import com.riccon.platform.domain.workflow.WorkflowNodeStatus;
import com.riccon.platform.domain.workflow.WorkflowNodeType;
import java.util.UUID;

/**
 * 节点尝试行快照（{@code workflow_node_run} 只读投影）。
 *
 * <p>引擎在运行开始时一次读取全部尝试，按 {@code node_key, attempt} 归并；成功尝试的输出用于
 * 崩溃恢复/重试时渲染下游模板，不需要重新执行上游节点（ADR-0024 §4.6）。
 *
 * @param id 节点运行 ID
 * @param nodeKey 节点 key
 * @param nodeType 节点类型
 * @param nodeName 节点名快照
 * @param attempt 尝试号
 * @param status 状态
 * @param inputSummary 输入摘要
 * @param outputSummary 输出摘要
 * @param outputJson 输出 JSON 原文
 * @param citationsJson 引用 JSON 原文
 * @param errorCode 稳定错误码
 * @param errorMessage 脱敏失败原因
 */
public record WorkflowNodeAttemptRow(
        UUID id,
        String nodeKey,
        WorkflowNodeType nodeType,
        String nodeName,
        int attempt,
        WorkflowNodeStatus status,
        String inputSummary,
        String outputSummary,
        String outputJson,
        String citationsJson,
        String errorCode,
        String errorMessage) {}
