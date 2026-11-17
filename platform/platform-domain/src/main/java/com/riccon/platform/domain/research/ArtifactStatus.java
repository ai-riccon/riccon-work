package com.riccon.platform.domain.research;

/**
 * 产物状态。
 *
 * <p>报告只在生成成功后落库；重试沿用同一任务与产物，不产生重复产物（M02-AC-11）。
 */
public enum ArtifactStatus {
    /** 生成中（M02 只在成功后可见）。 */
    DRAFT,
    /** 已保存，可打开与下载。 */
    SAVED,
    /** 已软删除，历史引用仍可解释来源。 */
    DELETED
}
