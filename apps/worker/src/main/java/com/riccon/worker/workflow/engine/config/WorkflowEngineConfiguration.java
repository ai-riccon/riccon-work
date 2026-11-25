package com.riccon.worker.workflow.engine.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 串行引擎配置注册。
 *
 * <p>只注册 {@link WorkflowEngineProperties}；执行器与仓储通过组件扫描装配。配置类集中在
 * {@code config} 包，满足 ArchUnit 对 {@code @Configuration} 位置的要求。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkflowEngineProperties.class)
public class WorkflowEngineConfiguration {}
