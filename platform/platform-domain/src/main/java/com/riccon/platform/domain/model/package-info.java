/**
 * 模型网关领域词汇：调用用途与调用状态。
 *
 * <p>M02 只有一个部署级 OpenAI 兼容端点；调用记录按租户审计。M04 引入路由、降级、多 key 与配额时，
 * 复用同一调用状态与审计动作，不改变事件/字段语义。
 */
package com.riccon.platform.domain.model;
