package com.riccon.api.workflow.template.repository;

import com.riccon.api.workflow.template.domain.WorkflowTemplateEntry;
import com.riccon.api.workflow.template.persistence.WorkflowTemplateJdbc;
import com.riccon.api.workflow.template.persistence.WorkflowTemplateRow;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * 内置模板目录仓储。
 *
 * <p>把持久化行转换为业务条目，服务层不接触 {@code JdbcClient} 或 SQL（ADR-0015，ArchUnit 分层）。
 * 只读全局目录，不提供租户隔离或写路径；用户模板在 M06b 另建入口（ADR-0024 §12）。
 */
@Repository
public class WorkflowTemplateRepository {

    /** 内置模板只读 SQL。 */
    private final WorkflowTemplateJdbc templateJdbc;

    /**
     * 创建仓储。
     *
     * @param templateJdbc 内置模板只读 SQL
     */
    public WorkflowTemplateRepository(WorkflowTemplateJdbc templateJdbc) {
        this.templateJdbc = Objects.requireNonNull(templateJdbc, "templateJdbc must not be null");
    }

    /**
     * 分页查询启用的内置模板。
     *
     * @param scenario 可选场景过滤；为空表示不过滤
     * @param name 可选名称过滤；为空表示不过滤
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 模板条目
     */
    public List<WorkflowTemplateEntry> list(String scenario, String name, int limit, int offset) {
        return templateJdbc.list(scenario, name, limit, offset).stream()
                .map(WorkflowTemplateRepository::toEntry)
                .toList();
    }

    /**
     * 统计启用的内置模板。
     *
     * @param scenario 可选场景过滤；为空表示不过滤
     * @param name 可选名称过滤；为空表示不过滤
     * @return 总数
     */
    public long count(String scenario, String name) {
        return templateJdbc.count(scenario, name);
    }

    /**
     * 按 ID 读取启用的内置模板。
     *
     * @param templateId 模板 ID
     * @return 模板条目
     */
    public Optional<WorkflowTemplateEntry> findActive(UUID templateId) {
        return templateJdbc.findActive(templateId).map(WorkflowTemplateRepository::toEntry);
    }

    /**
     * 把持久化行转换为业务条目。
     *
     * @param row 持久化行
     * @return 业务条目
     */
    private static WorkflowTemplateEntry toEntry(WorkflowTemplateRow row) {
        return new WorkflowTemplateEntry(
                row.id(),
                row.name(),
                row.scenario(),
                row.description(),
                row.visibility(),
                row.version(),
                row.updatedAt());
    }
}
