package com.riccon.worker.connector.domain;

/**
 * 源端对象列表项。
 *
 * @param key 对象 key（完整值只在 Worker 内使用）
 * @param etag ETag/version
 * @param sizeBytes 列表报告的大小
 */
public record ConnectorObject(String key, String etag, long sizeBytes) {}
