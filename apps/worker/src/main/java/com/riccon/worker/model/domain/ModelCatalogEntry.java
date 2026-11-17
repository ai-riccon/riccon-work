package com.riccon.worker.model.domain;

/**
 * 模型目录条目。
 *
 * @param model 模型
 * @param provider 供应商
 */
public record ModelCatalogEntry(ModelDescriptor model, ProviderDescriptor provider) {}
