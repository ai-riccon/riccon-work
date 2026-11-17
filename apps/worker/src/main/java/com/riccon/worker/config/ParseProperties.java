package com.riccon.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 文件解析与切分配置。
 *
 * <p>大文件先按大小拒绝，避免一次性读入内存导致 Worker OOM；切分参数同时用于文件分块与
 * 知识库分块，保证同一原文的重建结果稳定。
 *
 * @param maxFileBytes 单个文件允许解析的最大字节数
 * @param chunkSize 单块最大字符数
 * @param chunkOverlap 相邻块重叠字符数
 */
@ConfigurationProperties(prefix = "riccon.worker.parse")
public record ParseProperties(
        @DefaultValue("52428800") long maxFileBytes,
        @DefaultValue("800") int chunkSize,
        @DefaultValue("80") int chunkOverlap) {

    /** 校验切分参数，重叠必须小于块大小，否则切分无法前进。 */
    public ParseProperties {
        if (maxFileBytes < 1) {
            throw new IllegalArgumentException("riccon.worker.parse.max-file-bytes must be positive");
        }
        if (chunkSize < 100) {
            throw new IllegalArgumentException("riccon.worker.parse.chunk-size must be at least 100");
        }
        if (chunkOverlap < 0 || chunkOverlap >= chunkSize) {
            throw new IllegalArgumentException("riccon.worker.parse.chunk-overlap must be in [0, chunk-size)");
        }
    }
}
