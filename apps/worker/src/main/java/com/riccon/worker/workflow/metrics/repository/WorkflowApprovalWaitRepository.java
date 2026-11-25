package com.riccon.worker.workflow.metrics.repository;

import com.riccon.worker.workflow.metrics.domain.WorkflowApprovalWaitSnapshot;
import com.riccon.worker.workflow.metrics.persistence.WorkflowApprovalWaitJdbc;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 待审批等待指标仓储（ADR-0024 §10.2）。
 *
 * <p>对上只暴露聚合快照，屏蔽 SQL 细节；实现只做聚合查询，不读取审批正文，也不打开租户上下文。
 * 指标读取失败不得影响运行队列或审批主路径；缓存与降级由调用方负责。
 */
@Component
public class WorkflowApprovalWaitRepository {

    /** 聚合查询实现。 */
    private final WorkflowApprovalWaitJdbc jdbc;

    /**
     * 创建仓储。
     *
     * @param jdbc 聚合查询实现
     */
    public WorkflowApprovalWaitRepository(WorkflowApprovalWaitJdbc jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    }

    /**
     * 读取当前待审批数量与最老等待时间。
     *
     * @return 聚合快照
     */
    public WorkflowApprovalWaitSnapshot waitStats() {
        return jdbc.waitStats();
    }
}
