package com.riccon.worker.model.domain;

import com.riccon.worker.crypto.domain.SecretValue;
import java.net.URI;

/**
 * 一次运行可使用的模型端点与凭据。
 *
 * <p>凭据用 {@link SecretValue} 包装，避免被日志或异常打印；端点必须通过协议与允许列表校验后才可构造。
 *
 * @param model 模型目录快照
 * @param provider 供应商目录快照
 * @param baseUrl 已校验的上游基础 URL
 * @param apiKey 明文 API key 包装
 * @param maxOutputTokens 本次调用的输出上限
 */
public record ResolvedModel(
        ModelDescriptor model, ProviderDescriptor provider, URI baseUrl, SecretValue apiKey, int maxOutputTokens) {}
