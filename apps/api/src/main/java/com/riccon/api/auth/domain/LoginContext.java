package com.riccon.api.auth.domain;

/**
 * 一次登录尝试的请求上下文。
 *
 * <p>用于写审计与日志：不含明文密码；会话 ID 只以哈希形式进入审计（ADR-0007 白名单）。
 *
 * @param traceId 追踪 ID
 * @param requestId 请求 ID
 * @param sourceIp 客户端 IP；仅登录事件写入审计
 * @param sessionId 当前会话 ID；可为空，服务层会做 SHA-256 后写入审计
 */
public record LoginContext(String traceId, String requestId, String sourceIp, String sessionId) {}
