package com.riccon.worker.audit.repository;

import com.riccon.worker.audit.domain.AuditEvent;
import com.riccon.worker.audit.persistence.AuditJdbc;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 审计仓储。
 *
 * <p>只暴露追加语义；没有修改/删除入口，满足应用层 append-only。
 */
@Component
public class AuditRepository {

    /** 审计持久化。 */
    private final AuditJdbc auditJdbc;

    /**
     * 创建审计仓储。
     *
     * @param auditJdbc 审计持久化
     */
    public AuditRepository(AuditJdbc auditJdbc) {
        this.auditJdbc = Objects.requireNonNull(auditJdbc, "auditJdbc must not be null");
    }

    /**
     * 追加事件。
     *
     * @param event 审计事件
     */
    public void append(AuditEvent event) {
        auditJdbc.append(event);
    }
}
