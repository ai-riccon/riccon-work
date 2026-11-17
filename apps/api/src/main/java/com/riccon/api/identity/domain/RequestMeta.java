package com.riccon.api.identity.domain;

/**
 * 审计所需的请求元数据。
 *
 * @param traceId 追踪 ID
 * @param requestId 请求 ID，可为空
 * @param sourceIp 客户端 IP；仅登录事件写入审计，管理事件不写
 */
public record RequestMeta(String traceId, String requestId, String sourceIp) {}
