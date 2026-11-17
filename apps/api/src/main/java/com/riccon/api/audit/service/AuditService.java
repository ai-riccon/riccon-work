package com.riccon.api.audit.service;

import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.audit.repository.AuditRepository;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审计写入服务。
 *
 * <p>关键动作（登录、租户/账号/角色变更）与业务数据在同一事务中写入；本类不捕获持久化异常，写入失败会让
 * 业务事务回滚并向调用方抛错（fail-closed，ADR-0007 第 4 节）。
 */
@Service
public class AuditService {

    /** 审计仓储。 */
    private final AuditRepository auditRepository;

    /**
     * 创建审计服务。
     *
     * @param auditRepository 审计仓储
     */
    public AuditService(AuditRepository auditRepository) {
        this.auditRepository = Objects.requireNonNull(auditRepository, "auditRepository must not be null");
    }

    /**
     * 追加审计事件；若调用方已有事务则加入，否则开启新事务。
     *
     * @param event 审计事件
     */
    @Transactional
    public void append(AuditEvent event) {
        auditRepository.append(event);
    }
}
