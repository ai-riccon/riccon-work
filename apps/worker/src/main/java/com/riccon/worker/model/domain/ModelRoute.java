package com.riccon.worker.model.domain;

import com.riccon.platform.domain.model.ModelPurpose;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 一次运行解析出的模型路由链。
 *
 * <p>链已经过启停、端点允许列表、协议与 key 可用性过滤；顺序即尝试顺序（ADR-0021 §5.2）。
 * {@code configured} 表示是否真的命中了 `model_routing_entry`：迁移基线或未配置链时按 M02
 * 单模型兼容处理（由 resolver 决定），一旦存在显式链就绝不回退到链外模型。
 *
 * @param purpose 用途
 * @param mode 解析时使用的路由模式
 * @param failoverEnabled 降级开关
 * @param configured 是否由有序链产生（false 表示迁移基线兼容）
 * @param models 有序模型与可用 key
 */
public record ModelRoute(
        ModelPurpose purpose,
        ModelRoutingMode mode,
        boolean failoverEnabled,
        boolean configured,
        List<RoutedModel> models) {

    /** 复制链，禁止调用方修改尝试顺序。 */
    public ModelRoute {
        Objects.requireNonNull(purpose, "purpose must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
        models = List.copyOf(models == null ? List.of() : models);
    }

    /**
     * 返回链中第一个可尝试模型。
     *
     * @return 模型；链为空时为空
     */
    public Optional<RoutedModel> firstModel() {
        return models.stream().filter(model -> !model.credentials().isEmpty()).findFirst();
    }

    /**
     * 返回链是否没有任何可用尝试。
     *
     * @return 没有可用模型或 key 时为 true
     */
    public boolean isEmpty() {
        return firstModel().isEmpty();
    }
}
