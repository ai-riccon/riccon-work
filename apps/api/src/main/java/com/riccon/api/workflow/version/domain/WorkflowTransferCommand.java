package com.riccon.api.workflow.version.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * 所有权移交命令。
 *
 * <p>只有租户管理员可以移交；目标必须是本租户启用且具备 {@code workflow.edit} 的成员，服务层负责
 * 账号状态、权限与并发校验（ADR-0024 §6.2，AC-15）。
 *
 * @param ownerUserId 目标所有者账号 ID
 */
public record WorkflowTransferCommand(UUID ownerUserId) {

    /** 校验目标账号。 */
    public WorkflowTransferCommand {
        Objects.requireNonNull(ownerUserId, "ownerUserId must not be null");
    }
}
