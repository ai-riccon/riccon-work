package com.riccon.worker.model.domain;

import java.util.UUID;

/**
 * 模型目录快照（全局表）。
 *
 * @param id 模型 ID
 * @param providerId 供应商 ID
 * @param modelKey 上游模型标识
 * @param displayName 展示名
 * @param maxOutputTokens 最大输出 token
 * @param contextWindow 上下文窗口
 */
public record ModelDescriptor(
        UUID id, UUID providerId, String modelKey, String displayName, int maxOutputTokens, int contextWindow) {}
