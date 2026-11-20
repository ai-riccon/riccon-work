package com.riccon.worker.model.domain;

import java.net.URI;
import java.util.Objects;

/**
 * 可路由的模型端点快照（不含凭据明文）。
 *
 * <p>路由解析、尝试选择与 SSE `chat.started` 只持有本快照；API key 在每次尝试时按需解密，
 * 明文不进入 Agent 会话、日志或审计（ADR-0021 §6.5）。
 *
 * @param model 模型目录快照
 * @param provider 供应商目录快照
 * @param baseUrl 已校验的上游基础 URL
 * @param maxOutputTokens 本次调用的输出上限
 */
public record ModelTarget(ModelDescriptor model, ProviderDescriptor provider, URI baseUrl, int maxOutputTokens) {

    /** 校验必填字段，避免半成品目标进入路由链。 */
    public ModelTarget {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        if (maxOutputTokens < 1) {
            throw new IllegalArgumentException("maxOutputTokens must be positive");
        }
    }
}
