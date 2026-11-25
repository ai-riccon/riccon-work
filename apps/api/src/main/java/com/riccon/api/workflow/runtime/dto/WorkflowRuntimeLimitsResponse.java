package com.riccon.api.workflow.runtime.dto;

import com.riccon.api.workflow.runtime.domain.WorkflowRetentionPolicy;
import java.time.OffsetDateTime;

/**
 * 编排运行限制只读响应（契约 {@code WorkflowRuntimeLimits}，ADR-0024 §10.1）。
 *
 * <p>所有值来自部署配置与当前租户保留策略；租户只能读取，不能修改。字段顺序与契约一致，
 * 序列化后可直接对照 {@code contracts/openapi.yaml} 的 {@code WorkflowRuntimeLimits}。
 *
 * @param featureEnabled 编排总开关；关闭时新运行/重试/审批决定 503，取消仍可用
 * @param disabledReason 关闭原因；开启时为空
 * @param maxNodes 单流程节点上限
 * @param maxSteps 单运行节点尝试步数上限
 * @param nodeTimeoutSeconds 节点超时（秒）
 * @param runTimeoutSeconds 运行执行超时（秒，审批等待不计）
 * @param tenantConcurrency 租户运行准入并发（含等待审批）
 * @param workflowConcurrency 单流程运行准入并发
 * @param resultMaxBytes 单次运行输出 + 结果上限（字节）
 * @param retentionDays 当前租户生效的保留天数
 * @param retentionPolicy 保留天数来源
 * @param generatedAt 生成时间（带偏移的 ISO 8601）
 */
public record WorkflowRuntimeLimitsResponse(
        boolean featureEnabled,
        String disabledReason,
        int maxNodes,
        int maxSteps,
        NodeTimeoutSeconds nodeTimeoutSeconds,
        int runTimeoutSeconds,
        int tenantConcurrency,
        int workflowConcurrency,
        long resultMaxBytes,
        int retentionDays,
        WorkflowRetentionPolicy retentionPolicy,
        OffsetDateTime generatedAt) {

    /**
     * 节点超时分组（秒）。
     *
     * @param model 模型节点超时
     * @param retrieval 检索节点超时
     * @param mcp MCP 工具节点超时
     */
    public record NodeTimeoutSeconds(int model, int retrieval, int mcp) {}
}
