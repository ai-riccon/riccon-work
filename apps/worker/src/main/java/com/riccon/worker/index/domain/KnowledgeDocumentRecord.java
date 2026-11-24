package com.riccon.worker.index.domain;

import com.riccon.platform.domain.knowledge.KnowledgeDocumentStatus;
import com.riccon.platform.domain.knowledge.KnowledgeVisibility;
import com.riccon.platform.domain.knowledge.ParseEngine;
import java.time.Instant;
import java.util.UUID;

/**
 * 知识库文档快照。
 *
 * <p>索引文档冗余 ACL 与解析元数据，只用于诊断和未来过滤；授权权威始终是 MySQL
 * （ADR-0023 第 5.5 节），索引字段不得作为访问依据。{@code sourceType} 保留数据库原文
 * （{@code UPLOAD}/{@code SEED}，M05b 增加 {@code CONNECTOR}），避免在 worker 重复定义枚举。
 *
 * @param id 文档 ID
 * @param title 文档标题
 * @param fileId 来源文件 ID；演示语料与连接器文档为空
 * @param createdAt 文档创建时间（UTC），连接器对象 key 的年份来源
 * @param status 当前索引状态
 * @param visibility 可见范围
 * @param departmentId 可见部门；仅 {@code DEPT} 非空
 * @param aclVersion ACL 变更计数，用于识别索引文档是否落后
 * @param sourceType 来源类型原文
 * @param connectorId 连接器 ID；M05b 连接器文档非空
 * @param parseEngine 实际解析引擎；未知/未解析为空
 */
public record KnowledgeDocumentRecord(
        UUID id,
        String title,
        UUID fileId,
        Instant createdAt,
        KnowledgeDocumentStatus status,
        KnowledgeVisibility visibility,
        UUID departmentId,
        int aclVersion,
        String sourceType,
        UUID connectorId,
        ParseEngine parseEngine) {

    /** 校验必填字段与 ACL 版本下界。 */
    public KnowledgeDocumentRecord {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        if (title == null) {
            throw new IllegalArgumentException("title must not be null");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (visibility == null) {
            throw new IllegalArgumentException("visibility must not be null");
        }
        if (aclVersion < 1) {
            throw new IllegalArgumentException("aclVersion must be >= 1");
        }
        if (sourceType == null) {
            throw new IllegalArgumentException("sourceType must not be null");
        }
        if (visibility == KnowledgeVisibility.DEPT && departmentId == null) {
            throw new IllegalArgumentException("departmentId is required for DEPT visibility");
        }
    }

    /**
     * 以解析结果中的引擎生成索引文档快照，供 FULL 模式在同一次写入中携带 parse_engine。
     *
     * @param engine 本次解析使用的引擎
     * @return 替换 parseEngine 后的新快照
     */
    public KnowledgeDocumentRecord withParseEngine(ParseEngine engine) {
        return new KnowledgeDocumentRecord(
                id,
                title,
                fileId,
                createdAt,
                status,
                visibility,
                departmentId,
                aclVersion,
                sourceType,
                connectorId,
                engine);
    }

    /**
     * 兼容只关心文档 ID/标题的旧索引调用（M02–M04 测试与 SEED 语料）。
     *
     * @param id 文档 ID
     * @param title 标题
     * @return 默认 TENANT/UPLOAD、ACL 版本 1 的快照
     */
    public static KnowledgeDocumentRecord minimal(UUID id, String title) {
        return new KnowledgeDocumentRecord(
                id,
                title,
                null,
                // 兼容入口不参与连接器对象读取；索引文档不使用创建时间，固定纪元避免非确定性值。
                Instant.EPOCH,
                KnowledgeDocumentStatus.INDEXED,
                KnowledgeVisibility.TENANT,
                null,
                1,
                "UPLOAD",
                null,
                null);
    }
}
