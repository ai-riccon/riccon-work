package com.riccon.api.knowledge.service;

import com.riccon.api.knowledge.repository.KnowledgeRetentionRepository;
import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 知识库保留到期任务。
 *
 * <p>每日 03:00（{@code Asia/Shanghai}）按启用租户逐一执行，单租户独立事务与失败隔离；默认 180 天，
 * 租户 {@code tenant.retention_days} 覆盖，策略变更不回溯已有文档（ADR-0023 第 10 节）。
 * 只软删除，物理清理归 M09。
 */
@Component
public class KnowledgeRetentionJob {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeRetentionJob.class);

    /** 保留目录仓储。 */
    private final KnowledgeRetentionRepository retentionRepository;

    /** 单租户清理。 */
    private final KnowledgeRetentionProcessor processor;

    /**
     * 创建保留任务。
     *
     * @param retentionRepository 保留目录仓储
     * @param processor 单租户清理
     */
    public KnowledgeRetentionJob(
            KnowledgeRetentionRepository retentionRepository, KnowledgeRetentionProcessor processor) {
        this.retentionRepository = Objects.requireNonNull(retentionRepository, "retentionRepository must not be null");
        this.processor = Objects.requireNonNull(processor, "processor must not be null");
    }

    /** 每日 03:00 北京时间清理到期文档；单租户失败只记录并继续其他租户。 */
    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Shanghai")
    public void expireDaily() {
        List<String> tenants = retentionRepository.findActiveTenants();
        for (String tenant : tenants) {
            try {
                TenantContext.callWithTenant(TenantId.of(tenant), () -> processor.expireTenant(TenantId.of(tenant)));
            } catch (RuntimeException exception) {
                // 批量任务按租户隔离失败：审计/队列故障会在本租户回滚，下一次调度重试，不静默标记成功。
                LOG.error("knowledge.retention.failed tenantId={} retryable=true", tenant, exception);
            }
        }
    }
}
