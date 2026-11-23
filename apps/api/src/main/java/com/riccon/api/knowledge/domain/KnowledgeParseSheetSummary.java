package com.riccon.api.knowledge.domain;

import java.util.Objects;

/**
 * 单个工作表的解析摘要，对应 OpenAPI {@code KnowledgeParseSheetSummary}。
 *
 * @param name 工作表名
 * @param cellRange 整表实际有值范围，空表可为空
 * @param empty 是否空表
 * @param indexed 是否已索引
 * @param indexedRange 已索引范围；与整表范围不同表示存在未索引部分
 */
public record KnowledgeParseSheetSummary(
        String name, String cellRange, boolean empty, boolean indexed, String indexedRange) {

    /** 校验工作表名。 */
    public KnowledgeParseSheetSummary {
        Objects.requireNonNull(name, "name must not be null");
    }
}
