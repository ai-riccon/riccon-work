package com.riccon.worker.chat.domain;

import com.riccon.platform.domain.chat.CitationSourceType;
import com.riccon.platform.domain.chat.LocatorType;
import java.util.List;
import java.util.UUID;

/**
 * 对话 SSE 事件（chat-event.v1）。
 *
 * <p>字段与 {@code contracts/events/sse/chat-event.v1.schema.json} 一一对应；{@code data}
 * 使用强类型记录，禁止额外字段。事件不携带提示词、文件原文或工具结果原文。
 *
 * @param eventId 事件 ID
 * @param eventType 事件类型
 * @param version 版本，固定 1
 * @param occurredAt ISO-8601 时间
 * @param tenantId 租户
 * @param runId 运行 ID
 * @param messageId 回答消息 ID
 * @param traceId 追踪 ID
 * @param data 事件数据
 */
public record ChatEvent(
        UUID eventId,
        String eventType,
        int version,
        String occurredAt,
        String tenantId,
        UUID runId,
        UUID messageId,
        String traceId,
        Object data) {

    /** 校验必填字段，防止发送不完整事件。 */
    public ChatEvent {
        if (eventId == null
                || eventType == null
                || occurredAt == null
                || tenantId == null
                || runId == null
                || messageId == null
                || traceId == null
                || data == null) {
            throw new IllegalArgumentException("chat event is missing required fields");
        }
    }

    /**
     * 开始事件数据。
     *
     * @param modelId 模型 ID
     * @param modelKey 模型 key
     */
    public record Started(String modelId, String modelKey) {}

    /**
     * 增量事件数据。
     *
     * @param delta 文本增量，非空
     */
    public record Delta(String delta) {
        /** 校验增量非空。 */
        public Delta {
            if (delta == null || delta.isEmpty()) {
                throw new IllegalArgumentException("chat delta must not be empty");
            }
        }
    }

    /**
     * 引用事件数据。
     *
     * @param citations 非空引用列表
     */
    public record CitationData(List<Citation> citations) {
        /** 复制列表并校验非空。 */
        public CitationData {
            if (citations == null || citations.isEmpty()) {
                throw new IllegalArgumentException("citation event requires at least one citation");
            }
            citations = List.copyOf(citations);
        }
    }

    /**
     * 单条引用；可空字段允许序列化为 null，但不得包含额外字段。
     *
     * @param ordinal 序号
     * @param sourceType 来源类型
     * @param fileId 文件 ID
     * @param knowledgeDocumentId 知识库文档 ID
     * @param chunkId 分块 ID
     * @param locatorType 定位类型
     * @param pageNo 页码
     * @param sheetName 工作表
     * @param cellRange 单元格范围
     * @param paragraphNo 段落号
     * @param title 标题
     * @param snippet 片段
     * @param score 相关度
     */
    public record Citation(
            int ordinal,
            CitationSourceType sourceType,
            UUID fileId,
            UUID knowledgeDocumentId,
            UUID chunkId,
            LocatorType locatorType,
            Integer pageNo,
            String sheetName,
            String cellRange,
            Integer paragraphNo,
            String title,
            String snippet,
            Double score) {
        /** 校验必填字段与 snippet 上限。 */
        public Citation {
            if (ordinal < 1 || sourceType == null || locatorType == null || title == null || snippet == null) {
                throw new IllegalArgumentException("citation is missing required fields");
            }
            if (snippet.length() > 1000) {
                throw new IllegalArgumentException("citation snippet exceeds 1000 characters");
            }
        }
    }

    /**
     * 完成事件数据。
     *
     * @param finishReason stop/length/error
     * @param promptTokens 输入 token
     * @param completionTokens 输出 token
     * @param firstTokenMs 首字延迟
     */
    public record Completed(
            String finishReason, Integer promptTokens, Integer completionTokens, Integer firstTokenMs) {}

    /**
     * 失败事件数据。
     *
     * @param code 稳定错误码
     * @param message 脱敏说明
     * @param retryable 是否可重试
     */
    public record Failed(String code, String message, boolean retryable) {
        /** 校验错误码格式。 */
        public Failed {
            if (code == null || !code.matches("^RICCON-[A-Z]+-[0-9]{3}$")) {
                throw new IllegalArgumentException("failed event code is invalid");
            }
            if (message == null || message.length() > 500) {
                throw new IllegalArgumentException("failed event message is invalid");
            }
        }
    }

    /**
     * 停止事件数据。
     *
     * @param reason 停止原因
     */
    public record Stopped(String reason) {}
}
