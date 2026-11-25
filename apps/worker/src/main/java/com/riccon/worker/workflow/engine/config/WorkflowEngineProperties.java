package com.riccon.worker.workflow.engine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 串行引擎执行期配置（ADR-0024 §4.4/§4.6/§10.1）。
 *
 * <p>属性前缀 {@code riccon.workflow}，与 API 的 {@code WorkflowProperties}、T06a 的
 * {@code WorkflowQueueProperties}、T15 的 {@code WorkflowRuntimeProperties} 使用同一组部署键。
 * 本记录只绑定引擎实际消费的执行字段（节点/步数/超时/结果上限），不重复声明 claim/租约/指标字段；
 * 同一前缀存在多个 {@code @ConfigurationProperties} 是 Spring Boot 支持的按字段绑定方式。
 *
 * <p>默认值与 ADR §10.1 一致。上限类字段在引擎侧再做一次范围校验，防止部署把节点/步数放宽到
 * 契约之外；T15 的启动校验仍负责包大小与连接池等跨字段约束。
 *
 * @param maxNodes 单流程节点上限（1–50）
 * @param maxSteps 单运行节点尝试步数上限（1–200）
 * @param modelTimeoutSeconds 模型节点超时（秒）
 * @param retrievalTimeoutSeconds 检索节点超时（秒）
 * @param runTimeoutMinutes 运行执行超时（分钟，审批等待不计）
 * @param resultMaxBytes 单次运行节点输出 + 最终结果上限（字节）
 */
@ConfigurationProperties(prefix = "riccon.workflow")
public record WorkflowEngineProperties(
        @DefaultValue("50") int maxNodes,
        @DefaultValue("200") int maxSteps,
        @DefaultValue("300") int modelTimeoutSeconds,
        @DefaultValue("120") int retrievalTimeoutSeconds,
        @DefaultValue("30") int runTimeoutMinutes,
        @DefaultValue("5242880") long resultMaxBytes) {

    /** 校验执行期上限；越界启动失败，不静默截断。 */
    public WorkflowEngineProperties {
        if (maxNodes < 1 || maxNodes > 50) {
            throw new IllegalArgumentException("riccon.workflow.max-nodes must be between 1 and 50");
        }
        if (maxSteps < 1 || maxSteps > 200) {
            throw new IllegalArgumentException("riccon.workflow.max-steps must be between 1 and 200");
        }
        if (modelTimeoutSeconds < 1 || modelTimeoutSeconds > 3600) {
            throw new IllegalArgumentException("riccon.workflow.model-timeout-seconds must be between 1 and 3600");
        }
        if (retrievalTimeoutSeconds < 1 || retrievalTimeoutSeconds > 3600) {
            throw new IllegalArgumentException(
                    "riccon.workflow.retrieval-timeout-seconds must be between 1 and 3600");
        }
        if (runTimeoutMinutes < 1 || runTimeoutMinutes > 1440) {
            throw new IllegalArgumentException("riccon.workflow.run-timeout-minutes must be between 1 and 1440");
        }
        if (resultMaxBytes < 1) {
            throw new IllegalArgumentException("riccon.workflow.result-max-bytes must be positive");
        }
    }
}
