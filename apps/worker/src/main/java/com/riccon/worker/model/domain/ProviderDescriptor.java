package com.riccon.worker.model.domain;

import java.net.URI;
import java.util.UUID;

/**
 * 模型供应商目录快照（全局表）。
 *
 * @param id 供应商 ID
 * @param code 供应商代码
 * @param protocol 协议；M02 固定 {@code OPENAI_COMPATIBLE}
 * @param baseUrl 固定 HTTPS 端点
 */
public record ProviderDescriptor(UUID id, String code, String protocol, URI baseUrl) {}
