package com.riccon.worker.workflow.queue.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * M06 运行队列、租约、调度池与心跳配置（ADR-0024 §4.3/§10.1）。
 *
 * <p>属性前缀 {@code riccon.workflow}，环境变量为 {@code RICCON_WORKFLOW_*}；与 API 侧的
 * {@code WorkflowProperties} 使用同一组部署键，避免两侧默认值漂移。Worker 实际消费
 * claim/lease/心跳/调度池/并发/停止领取相关字段；其余字段（节点、模型超时等）由引擎或 API 使用，
 * 这里不重复校验。
 *
 * <p>跨字段约束（batch=1、调度/心跳池 ≥ 并发、interval ≤ lease/3、连接池 ≥ 并发 + 4、
 * 轮询与退避为正）统一由 {@link WorkflowQueueStartupValidator} 在启动期 fail-closed 执行，
 * 因此本记录不做会与 validator 报错信息重复的校验。
 *
 * @param workerConcurrency 运行队列槽位（默认 10）
 * @param claimBatch 每槽位领取批量；v1 固定 1，配置 &gt;1 启动失败
 * @param claimPollIntervalMs 空闲槽位 claim 轮询周期（默认 1000ms）
 * @param claimIdleBackoffMaxMs 空队列指数退避上限（默认 5000ms）
 * @param schedulerPoolSize M06 独立调度池大小（默认 10，不是 Spring 共享调度池）
 * @param heartbeatConcurrency 心跳池线程数（默认 10）
 * @param heartbeatIntervalSeconds 续期间隔秒数（默认 30，必须 ≤ lease/3）
 * @param leaseSeconds 执行租约秒数（默认 90）
 * @param claimEnabled Worker 侧停止领取开关（默认 true）
 * @param shutdownGraceSeconds 停止领取后的在途等待上限（默认 120s）
 * @param instanceId 实例标识；空值时使用主机名 + PID
 */
@ConfigurationProperties(prefix = "riccon.workflow")
public record WorkflowQueueProperties(
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
        @DefaultValue("") String instanceId) {}
