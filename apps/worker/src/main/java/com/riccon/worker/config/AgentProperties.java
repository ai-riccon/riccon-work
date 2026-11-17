package com.riccon.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Agent 运行时配置。
 *
 * <p>控制 ReAct 迭代上限与检索上下文预算；检索内容与文件分块都是不可信数据，
 * 必须按字符数截断后再进入模型上下文。
 *
 * @param maxIterations ReAct 最大迭代次数
 * @param knowledgeTopK 知识库检索返回分块数
 * @param fileChunkLimit 单个文件最多载入的分块数
 * @param maxChunkChars 单个分块进入模型上下文的字符上限
 * @param retrievalCandidateLimit 关键词检索的候选上限
 */
@ConfigurationProperties(prefix = "riccon.worker.agent")
public record AgentProperties(
        @DefaultValue("4") int maxIterations,
        @DefaultValue("5") int knowledgeTopK,
        @DefaultValue("5") int fileChunkLimit,
        @DefaultValue("2000") int maxChunkChars,
        @DefaultValue("60") int retrievalCandidateLimit) {

    /** 校验预算边界，避免一次运行读入无界上下文。 */
    public AgentProperties {
        if (maxIterations < 1 || maxIterations > 12) {
            throw new IllegalArgumentException("riccon.worker.agent.max-iterations must be between 1 and 12");
        }
        if (knowledgeTopK < 1 || knowledgeTopK > 20) {
            throw new IllegalArgumentException("riccon.worker.agent.knowledge-top-k must be between 1 and 20");
        }
        if (fileChunkLimit < 1 || fileChunkLimit > 20) {
            throw new IllegalArgumentException("riccon.worker.agent.file-chunk-limit must be between 1 and 20");
        }
        if (maxChunkChars < 200 || maxChunkChars > 8000) {
            throw new IllegalArgumentException("riccon.worker.agent.max-chunk-chars must be between 200 and 8000");
        }
        if (retrievalCandidateLimit < knowledgeTopK || retrievalCandidateLimit > 200) {
            throw new IllegalArgumentException(
                    "riccon.worker.agent.retrieval-candidate-limit must be between knowledge-top-k and 200");
        }
    }
}
