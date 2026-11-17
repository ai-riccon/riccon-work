package com.riccon.api.audit.repository;

import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.audit.persistence.AuditJdbc;
import java.util.Objects;
import org.springframework.stereotype.Repository;

/**
 * 审计仓储。
 *
 * <p>仓储只暴露追加语义；没有修改或删除入口，满足 ADR-0007 的应用层 append-only 要求。
 */
@Repository
public class AuditRepository {

    /** 审计持久化组件。 */
    private final AuditJdbc auditJdbc;

    /**
     * 创建审计仓储。
     *
     * @param auditJdbc 审计持久化组件
     */
    public AuditRepository(AuditJdbc auditJdbc) {
        this.auditJdbc = Objects.requireNonNull(auditJdbc, "auditJdbc must not be null");
    }

    /**
     * 追加审计事件。
     *
     * @param event 审计事件
     */
    public void append(AuditEvent event) {
        auditJdbc.append(event);
    }
}
