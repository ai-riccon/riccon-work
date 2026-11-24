package com.riccon.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 编排 Studio 部署配置（ADR-0024 第 10.1 节，租户只读）。
 *
 * <p>属性前缀 {@code riccon.workflow}，对应环境变量 {@code RICCON_WORKFLOW_*}。这些值只来自部署环境，
 * 租户管理员只能通过 {@code GET /workflows/runtime-limits} 只读查看，不能修改。T01 先建立完整键位与
 * 启动观测；worker 侧的 claim/lease/心跳启动校验由 T06a、API 侧运行限制与指标由 T15 消费同一组键。
 *
 * <p>默认值与 ADR-0024 §10.1 一致：节点 50、步数 200、模型 300s、检索 120s、MCP 60s、运行 30min、
 * 租户并发 10、单流程 5、结果 5 MiB、worker 并发/调度池/心跳 10、租约 90s、续期 30s、轮询 1000ms、
 * 空队列退避上限 5000ms、停止领取宽限 120s、保留 180 天。校验（batch=1、池 ≥ 并发、interval ≤ lease/3、
 * 连接池 ≥ 并发 + 4）由 worker 启动时 fail-closed 执行，不在 API 侧重复判定。
 *
 * @param enabled 编排总开关；关闭时新运行/重试/审批决定/复制模板 503，取消仍可用
 * @param disabledReason 关闭原因（可选，≤200 字符）；未配置时 API 回退固定文案
 * @param instanceId 实例标识；空值时启动观测使用主机名 + 进程随机后缀
 * @param maxNodes 单流程节点上限
 * @param maxSteps 单运行节点尝试步数上限
 * @param modelTimeoutSeconds 模型节点超时
 * @param retrievalTimeoutSeconds 检索节点超时
 * @param mcpTimeoutSeconds MCP 工具节点超时
 * @param runTimeoutMinutes 运行执行超时（审批等待不计）
 * @param tenantConcurrency 租户运行准入并发（含等待审批）
 * @param flowConcurrency 单流程运行准入并发
 * @param resultMaxBytes 单次运行输出 + 结果上限（默认 5 MiB）
 * @param workerConcurrency worker 运行队列槽位
 * @param claimBatch 每槽位领取批量；v1 固定 1，>1 由 worker 启动失败
 * @param claimPollIntervalMs 空闲槽位 claim 轮询周期
 * @param claimIdleBackoffMaxMs 空队列指数退避上限
 * @param schedulerPoolSize M06 独立调度池大小（不是 Spring 共享池）
 * @param heartbeatConcurrency 心跳池线程数
 * @param heartbeatIntervalSeconds 续期间隔；必须 ≤ 租约/3
 * @param leaseSeconds 执行租约
 * @param claimEnabled worker 停止领取开关（优雅停机/紧急停止）
 * @param shutdownGraceSeconds 停止领取后的在途等待上限
 * @param retentionDays 运行保留天数默认值；租户策略优先
 */
@ConfigurationProperties(prefix = "riccon.workflow")
public record WorkflowProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("") String disabledReason,
        @DefaultValue("") String instanceId,
        @DefaultValue("50") int maxNodes,
        @DefaultValue("200") int maxSteps,
        @DefaultValue("300") int modelTimeoutSeconds,
        @DefaultValue("120") int retrievalTimeoutSeconds,
        @DefaultValue("60") int mcpTimeoutSeconds,
        @DefaultValue("30") int runTimeoutMinutes,
        @DefaultValue("10") int tenantConcurrency,
        @DefaultValue("5") int flowConcurrency,
        @DefaultValue("5242880") long resultMaxBytes,
        @DefaultValue("10") int workerConcurrency,
        @DefaultValue("1") int claimBatch,
        @DefaultValue("1000") long claimPollIntervalMs,
        @DefaultValue("5000") long claimIdleBackoffMaxMs,
        @DefaultValue("10") int schedulerPoolSize,
        @DefaultValue("10") int heartbeatConcurrency,
        @DefaultValue("30") int heartbeatIntervalSeconds,
        @DefaultValue("90") int leaseSeconds,
        @DefaultValue("true") boolean claimEnabled,
        @DefaultValue("120") int shutdownGraceSeconds,
        @DefaultValue("180") int retentionDays) {}
