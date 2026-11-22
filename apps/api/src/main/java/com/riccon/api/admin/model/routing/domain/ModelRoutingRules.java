package com.riccon.api.admin.model.routing.domain;

import com.riccon.api.admin.model.catalog.domain.AdminModelRow;
import com.riccon.platform.common.model.ManagedEndpointRegistry;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 模型路由保存规则。
 *
 * <p>ADR-0021 §4.2 是唯一事实源：{@code modelIds} 必须 1..20 个、去重、每个模型存在，且 provider 通过
 * 部署端点校验；<b>允许包含 DISABLED 模型</b>（保留位置，运行时跳过）。未知模型按字段级 400 处理，
 * 不使用 404，因为请求体引用的对象不参与资源存在性语义。
 */
public final class ModelRoutingRules {

    /** 单链最多模型数；与 OpenAPI {@code ModelRoutingUpdateRequest.modelIds.maxItems} 一致。 */
    public static final int MAX_CHAIN_LENGTH = 20;

    /** 唯一协议；M04 只支持 OpenAI 兼容协议。 */
    private static final String OPENAI_COMPATIBLE = "OPENAI_COMPATIBLE";

    /** 启用状态。 */
    private static final String ACTIVE = "ACTIVE";

    /** 工具类禁止实例化。 */
    private ModelRoutingRules() {}

    /**
     * 校验并返回请求版本。
     *
     * @param version 请求体中的版本
     * @return 合法版本
     * @throws ModelRoutingValidationException 版本为空或小于 1
     */
    public static int requireVersion(Integer version) {
        if (version == null || version < 1) {
            throw new ModelRoutingValidationException(
                    "version", "INVALID_VERSION", "version must be an integer greater than or equal to 1");
        }
        return version;
    }

    /**
     * 校验并解析有序模型 ID 列表。
     *
     * @param rawModelIds 请求体中的字符串 ID
     * @return 保序、去重后的 UUID 列表
     * @throws ModelRoutingValidationException 空链、超长、非法 UUID 或重复
     */
    public static List<UUID> parseModelIds(List<String> rawModelIds) {
        if (rawModelIds == null || rawModelIds.isEmpty()) {
            throw new ModelRoutingValidationException(
                    "modelIds", "CHAIN_EMPTY", "modelIds must contain between 1 and 20 models");
        }
        if (rawModelIds.size() > MAX_CHAIN_LENGTH) {
            throw new ModelRoutingValidationException(
                    "modelIds", "CHAIN_TOO_LONG", "modelIds must contain at most 20 models");
        }
        Set<UUID> unique = new LinkedHashSet<>();
        for (String raw : rawModelIds) {
            if (raw == null || raw.isBlank()) {
                throw new ModelRoutingValidationException(
                        "modelIds", "INVALID_MODEL_ID", "modelIds must contain valid UUIDs");
            }
            UUID modelId;
            try {
                modelId = UUID.fromString(raw.strip());
            } catch (IllegalArgumentException exception) {
                throw new ModelRoutingValidationException(
                        "modelIds", "INVALID_MODEL_ID", "modelIds must contain valid UUIDs");
            }
            if (!unique.add(modelId)) {
                throw new ModelRoutingValidationException(
                        "modelIds", "DUPLICATE_MODEL", "modelIds must not contain duplicate models");
            }
        }
        return List.copyOf(unique);
    }

    /**
     * 校验模型是否可以被加入路由链。
     *
     * <p>模型自身 DISABLED 是允许的（保留位置）；但 provider 必须 ACTIVE、协议为 {@code OPENAI_COMPATIBLE}
     * 且通过部署允许列表，否则该条目永远不可路由。错误信息不回显 baseUrl 或供应商内部地址。
     *
     * @param model 目录行
     * @param endpointRegistry 部署端点允许列表
     * @throws ModelRoutingValidationException provider 停用、协议不支持或端点不在允许列表
     */
    public static void requireRoutable(AdminModelRow model, ManagedEndpointRegistry endpointRegistry) {
        if (!ACTIVE.equals(model.providerStatus()) || !OPENAI_COMPATIBLE.equals(model.providerProtocol())) {
            throw new ModelRoutingValidationException(
                    "modelIds", "MODEL_NOT_ROUTABLE", "modelIds contains a model whose provider is not routable");
        }
        if (!endpointRegistry.isAllowed(model.providerCode(), model.providerBaseUrl())) {
            throw new ModelRoutingValidationException(
                    "modelIds",
                    "ENDPOINT_NOT_ALLOWED",
                    "modelIds contains a model whose endpoint is not allowed by deployment configuration");
        }
    }
}
