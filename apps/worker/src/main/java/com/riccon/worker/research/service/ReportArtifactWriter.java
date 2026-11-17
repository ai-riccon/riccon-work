package com.riccon.worker.research.service;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.research.domain.ReportDocument;
import com.riccon.worker.research.domain.ResearchTaskRecord;
import com.riccon.worker.research.service.ArtifactStorage.StoredArtifact;
import com.riccon.worker.research.service.ReportArtifactService.SavedArtifact;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 报告产物写入编排。
 *
 * <p>先把 Markdown 正文写入对象存储，再在事务内保存产物、版本、引用与审计。对象 key 由任务 ID 与正文
 * 摘要生成，重复写同一内容只会覆盖同一对象，不会产生孤儿版本；数据库事务失败时对象可能残留，由 M09
 * 按租户前缀清理，不影响引用可解释性。
 */
@Component
public class ReportArtifactWriter {

    /** 对象存储。 */
    private final ArtifactStorage storage;

    /** 产物落库。 */
    private final ReportArtifactService service;

    /**
     * 创建写入编排。
     *
     * @param storage 对象存储
     * @param service 产物落库
     */
    public ReportArtifactWriter(ArtifactStorage storage, ReportArtifactService service) {
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
        this.service = Objects.requireNonNull(service, "service must not be null");
    }

    /**
     * 写入报告产物。
     *
     * @param tenantId 租户
     * @param task 报告任务
     * @param runId 运行 ID
     * @param createdBy 生成人
     * @param document 报告文档
     * @param traceId 追踪 ID
     * @return 保存结果
     */
    public SavedArtifact write(
            TenantId tenantId,
            ResearchTaskRecord task,
            UUID runId,
            UUID createdBy,
            ReportDocument document,
            String traceId) {
        StoredArtifact stored = storage.put(tenantId, task.id(), document.body());
        return service.persist(tenantId, task, runId, createdBy, document, stored, traceId);
    }
}
