/**
 * M06 编排 Studio 的共享领域词汇。
 *
 * <p>本包只放 API 与 Worker 共同使用的封闭枚举与常量，不放 Spring 注解、仓储或业务逻辑；数据库 CHECK
 * 约束与 OpenAPI 枚举必须与这里保持一致（ADR-0024 第 3/4 节）。节点、算子、占位符与错误码都是封闭白名单，
 * 任何新增值都必须先更新契约、迁移与测试，禁止在业务代码里写字符串字面量。
 */
package com.riccon.platform.domain.workflow;
