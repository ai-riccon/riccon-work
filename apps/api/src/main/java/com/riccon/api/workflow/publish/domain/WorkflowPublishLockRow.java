package com.riccon.api.workflow.publish.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * 发布事务内的流程定义行锁快照。
 *
 * <p>{@code SELECT ... FOR UPDATE} 返回的只有发布指针：同一流程的并发发布在拿到该行锁后串行执行，
 * 后到者能看到先到者提交的 {@code published_version_id}/{@code published_version_no}，从而判定是否
 * 为同一草稿的重复发布，不产生重复版本（ADR-0024 §3.3.2/§4.1）。草稿版本字段不在这里读取，服务层
 * 会在锁内重新读取完整快照，避免使用加锁前的旧草稿。
 *
 * @param workflowId 流程 ID
 * @param publishedVersionId 最近发布版本 ID；未发布时为空
 * @param publishedVersionNo 最近发布版本号；未发布时为空
 */
public record WorkflowPublishLockRow(UUID workflowId, UUID publishedVersionId, Integer publishedVersionNo) {

    /** 校验流程 ID。 */
    public WorkflowPublishLockRow {
        Objects.requireNonNull(workflowId, "workflowId must not be null");
    }
}
