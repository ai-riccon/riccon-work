package com.riccon.api.config;

import com.riccon.api.audit.domain.AuditEvent;

/**
 * 编排开关启动观测的审计写入端口。
 *
 * <p>生产实现直接委托 {@code AuditService}（与业务审计同一 fail-closed 语义）；独立端口让启动观测的
 * “审计失败不得阻断 API 启动”（OPS-M06-024）可被单元测试证明，不需要构造真实数据库故障。
 */
@FunctionalInterface
public interface WorkflowFeatureAuditWriter {

    /**
     * 追加一条审计事件。
     *
     * @param event 已通过白名单校验的事件
     */
    void append(AuditEvent event);
}
