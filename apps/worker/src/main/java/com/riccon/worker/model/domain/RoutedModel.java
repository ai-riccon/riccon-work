package com.riccon.worker.model.domain;

import java.util.List;
import java.util.Objects;

/**
 * 路由链中的一个模型及其可用 key 池。
 *
 * @param target 模型端点快照
 * @param credentials 按 priority/created_at/id 排序的可用 key
 */
public record RoutedModel(ModelTarget target, List<ModelCredential> credentials) {

    /** 校验端点并复制 key 列表。 */
    public RoutedModel {
        Objects.requireNonNull(target, "target must not be null");
        credentials = List.copyOf(credentials == null ? List.of() : credentials);
    }
}
