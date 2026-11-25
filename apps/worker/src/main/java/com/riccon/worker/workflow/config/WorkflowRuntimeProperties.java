package com.riccon.worker.workflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Worker 侧编排运行限制配置（ADR-0024 §10.1）。
 *
 * <p>属性前缀 {@code riccon.workflow}，与 API 的 {@code WorkflowProperties} 及 T06a 的
 * {@code WorkflowQueueProperties} 使用同一组部署键；本记录只承载 Worker 启动校验与指标需要的
 * 运行限制字段，claim/租约/调度池字段继续由 {@code WorkflowQueueProperties} 提供，避免两个记录
 * 重复声明同一键。
 *
 * <p>跨字段约束（上限为正、包大小覆盖结果上限、开关组合）由 {@link WorkflowRuntimeConfigValidator}
 * 在启动期 fail-closed / WARN 执行。
 *
 * @param enabled 编排总开关（与 API 同键；Worker 读取用于漂移指标）
 * @param disabledReason 关闭原因（可选，≤200 字符；仅随开关进入指标/日志，不落业务数据）
 * @param maxNodes 单流程节点上限
 * @param maxSteps 单运行节点尝试步数上限
 * @param modelTimeoutSeconds 模型节点超时
 * @param retrievalTimeoutSeconds 检索节点超时
 * @param mcpTimeoutSeconds MCP 工具节点超时
 * @param runTimeoutMinutes 运行执行超时（审批等待不计）
 * @param tenantConcurrency 租户运行准入并发（含等待审批；API 执行）
 * @param flowConcurrency 单流程运行准入并发（API 执行）
 * @param resultMaxBytes 单次运行输出 + 结果上限
 * @param retentionDays 保留天数默认值（租户策略优先；M06 只读展示）
 */
@ConfigurationProperties(prefix = "riccon.workflow")
public record WorkflowRuntimeProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("") String disabledReason,
        @DefaultValue("50") int maxNodes,
        @DefaultValue("200") int maxSteps,
        @DefaultValue("300") int modelTimeoutSeconds,
        @DefaultValue("120") int retrievalTimeoutSeconds,
        @DefaultValue("60") int mcpTimeoutSeconds,
        @DefaultValue("30") int runTimeoutMinutes,
        @DefaultValue("10") int tenantConcurrency,
        @DefaultValue("5") int flowConcurrency,
        @DefaultValue("5242880") long resultMaxBytes,
        @DefaultValue("180") int retentionDays) {}
