package com.riccon.worker.model.domain;

import java.util.Objects;

/**
 * 路由链中的一个目录条目（不含凭据）。
 *
 * <p>位置从 1 开始；链读取时已过滤启停与协议，仍由 resolver 再做端点允许列表校验。
 *
 * @param position 位置，从 1 开始
 * @param model 模型目录快照
 * @param provider 供应商目录快照
 */
public record RoutingChainEntry(int position, ModelDescriptor model, ProviderDescriptor provider) {

    /** 校验位置与目录快照。 */
    public RoutingChainEntry {
        if (position < 1) {
            throw new IllegalArgumentException("position must be >= 1");
        }
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(provider, "provider must not be null");
    }
}
