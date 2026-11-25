package com.riccon.worker.workflow.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Worker 编排运行限制配置注册（ADR-0024 §10.1）。
 *
 * <p>只注册 {@link WorkflowRuntimeProperties}；队列/租约/心跳属性与三个线程池由 T06a 的
 * {@code WorkflowQueueConfiguration} 提供。本类<b>不</b>声明 {@code taskScheduler}、
 * {@code workflowRunSlotExecutor} 或 {@code workflowHeartbeatExecutor} 同名 Bean，避免覆盖
 * T06a 已固定下来的线程池隔离语义（F-001/OPS-M06-002）。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkflowRuntimeProperties.class)
public class WorkflowRuntimeConfiguration {}
