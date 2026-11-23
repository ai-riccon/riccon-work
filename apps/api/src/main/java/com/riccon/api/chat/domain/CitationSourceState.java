package com.riccon.api.chat.domain;

/**
 * 引用源文档的读取时状态快照。
 *
 * <p>{@code accessible} 由当前账号的文档 ACL 谓词求值，{@code deleted} 来自
 * {@code knowledge_document.deleted_at}，{@code checksum} 是文档当前内容 SHA-256。状态按
 * {@link #statusFor(String)} 统一计算，调用方只负责按 {@code document_id} 批量取状态，禁止逐条查询
 * （ADR-0023 第 5.4 节）。
 *
 * @param accessible 当前账号是否仍可见该文档
 * @param deleted 文档是否已软删除
 * @param checksum 文档当前内容 checksum；文档不存在时为空
 */
public record CitationSourceState(boolean accessible, boolean deleted, String checksum) {

    /**
     * 结合引用创建时的版本快照计算可用性。
     *
     * <p>优先级固定为 {@code NO_ACCESS > DELETED > UPDATED > AVAILABLE}；{@code sourceVersion} 为空的历史引用
     * 不做 {@code UPDATED} 判定，除非 ACL 撤销或文档删除覆盖（ADR-0023 第 5.1、5.4 节）。
     *
     * @param sourceVersion 引用创建时的 checksum；FILE 引用与历史引用为空
     * @return 引用可用性
     */
    public CitationSourceStatus statusFor(String sourceVersion) {
        if (!accessible) {
            return CitationSourceStatus.NO_ACCESS;
        }
        if (deleted) {
            return CitationSourceStatus.DELETED;
        }
        if (sourceVersion != null && checksum != null && !sourceVersion.equals(checksum)) {
            return CitationSourceStatus.UPDATED;
        }
        return CitationSourceStatus.AVAILABLE;
    }

    /**
     * 文档不可见（不存在、跨租户或 ACL 拒绝）时的统一状态。
     *
     * @return 不可访问状态
     */
    public static CitationSourceState denied() {
        return new CitationSourceState(false, false, null);
    }
}
