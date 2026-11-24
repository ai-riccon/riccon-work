package com.riccon.api.common.service;

import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.audit.service.AuditService;
import com.riccon.api.config.WorkflowFeatureAuditWriter;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 编排开关启动观测的审计写入端口适配器。
 *
 * <p>直接委托 {@link AuditService}，保持与业务审计相同的写入与事务语义；端口化只用于让“关闭态审计
 * 失败不阻断启动”可被单元测试证明（ADR-0024 §4.8、OPS-M06-024）。
 *
 * <p>放在 service 层而不是 config：{@code ModuleBoundaryTest.mvcLayeringIsOneWay} 在
 * {@code consideringAllDependencies} 下要求只有 controller/service 层可以依赖 service 层，
 * config 只装配无业务依赖的 Bean，因此不能直接在 config 中构造委托 {@code AuditService} 的 Bean。
 */
@Service
public class WorkflowFeatureAuditWriterAdapter implements WorkflowFeatureAuditWriter {

    /** 审计写入服务。 */
    private final AuditService auditService;

    /**
     * 创建审计写入端口适配器。
     *
     * @param auditService 审计写入服务
     */
    public WorkflowFeatureAuditWriterAdapter(AuditService auditService) {
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
    }

    /**
     * 追加一条审计事件；事务与 fail-closed 语义由 {@link AuditService#append(AuditEvent)} 决定。
     *
     * @param event 已通过白名单校验的事件
     */
    @Override
    public void append(AuditEvent event) {
        auditService.append(event);
    }
}
