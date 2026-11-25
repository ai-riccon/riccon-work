package com.riccon.api.workflow.template.service;

import com.riccon.api.error.BadRequestException;
import com.riccon.api.error.ForbiddenException;
import com.riccon.api.error.NotFoundException;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.workflow.definition.domain.WorkflowDefinitionActor;
import com.riccon.api.workflow.definition.dto.WorkflowDefinitionDetailResponse;
import com.riccon.api.workflow.definition.service.WorkflowDefinitionService;
import com.riccon.api.workflow.template.domain.WorkflowTemplateEntry;
import com.riccon.api.workflow.template.dto.WorkflowTemplatePageResponse;
import com.riccon.api.workflow.template.dto.WorkflowTemplateResponse;
import com.riccon.api.workflow.template.repository.WorkflowTemplateRepository;
import com.riccon.platform.domain.identity.PermissionCodes;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 内置模板目录服务。
 *
 * <p>实现 M06-AC-10 的模板可见与复制入口：列表只返回 {@code BUILT_IN} 且启用的模板，支持名称/场景包含
 * 过滤与强制分页；复制复用 T02 的 {@link WorkflowDefinitionService#createFromTemplate} 生成当前用户的
 * 草稿副本，模板行只读、不被修改（ADR-0024 §3.3.7/§12）。
 *
 * <p>不在 M06 范围：用户模板另存、租户内共享、模板升级提示与复制新版本（M06b/M26）。模板目录是全局表，
 * 不含租户数据；平台租户 404 由 {@code WorkflowPlatformTenantFilter} 在权限拦截前短路（ADR-0024 §6.1）。
 */
@Service
public class WorkflowTemplateService {

    /** 模板资源名，用于统一 404。 */
    private static final String RESOURCE = "workflow-template";

    /** 过滤条件最大长度，与契约 {@code scenario}/{@code name} 一致。 */
    private static final int MAX_FILTER_LENGTH = 200;

    /** 内置模板只读仓储。 */
    private final WorkflowTemplateRepository repository;

    /** 流程定义服务；复制时复用 T02 的草稿创建逻辑。 */
    private final WorkflowDefinitionService definitionService;

    /**
     * 创建模板目录服务。
     *
     * @param repository 内置模板只读仓储
     * @param definitionService 流程定义服务
     */
    public WorkflowTemplateService(WorkflowTemplateRepository repository, WorkflowDefinitionService definitionService) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.definitionService = Objects.requireNonNull(definitionService, "definitionService must not be null");
    }

    /**
     * 分页列出启用的内置模板。
     *
     * <p>过滤值为空或空白时不过滤；长度超过 200 按请求非法 400。列表按最近更新时间倒序、ID 升序返回，
     * 契约未声明排序参数，因此不接受客户端排序键。
     *
     * @param scenario 可选场景过滤
     * @param name 可选名称过滤
     * @param page 分页
     * @return 模板分页
     */
    @Transactional(readOnly = true)
    public WorkflowTemplatePageResponse list(String scenario, String name, PageQuery page) {
        String scenarioFilter = normalizeFilter(scenario, "scenario");
        String nameFilter = normalizeFilter(name, "name");
        long total = repository.count(scenarioFilter, nameFilter);
        List<WorkflowTemplateResponse> items =
                repository.list(scenarioFilter, nameFilter, page.size(), page.offset()).stream()
                        .map(WorkflowTemplateResponse::from)
                        .toList();
        return new WorkflowTemplatePageResponse(page.page(), page.size(), total, page.totalPages(total), items);
    }

    /**
     * 从内置模板创建当前用户的草稿副本。
     *
     * <p>顺序：先校验 {@code workflow.edit}（403），再确认模板存在且启用（404，契约声明），最后复用
     * {@link WorkflowDefinitionService#createFromTemplate} 写入草稿并写 {@code workflow.created} 审计；
     * 模板行不被更新，草稿记录来源模板 ID/类型/版本，供 M06b 升级提示使用（AC-10/25）。
     *
     * @param actor 当前主体
     * @param templateId 模板 ID
     * @param name 可选名称；省略时使用模板名
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @return 已创建的个人草稿详情
     */
    @Transactional
    public WorkflowDefinitionDetailResponse copy(
            WorkflowDefinitionActor actor, UUID templateId, String name, String traceId, String requestId) {
        if (!actor.canEdit()) {
            throw new ForbiddenException(PermissionCodes.WORKFLOW_EDIT);
        }
        WorkflowTemplateEntry template =
                repository.findActive(templateId).orElseThrow(() -> new NotFoundException(RESOURCE));
        // 名称与说明都为 null，由 T02 使用模板本身的名称与说明；复制不修改源模板。
        return definitionService.createFromTemplate(actor, template.id(), name, null, traceId, requestId);
    }

    /**
     * 规范化过滤值并校验长度。
     *
     * @param value 原始过滤值
     * @param field 字段名，用于错误说明
     * @return 去空白后的过滤值；空值返回 {@code null}
     */
    private static String normalizeFilter(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.length() > MAX_FILTER_LENGTH) {
            throw new BadRequestException(field + " must be at most " + MAX_FILTER_LENGTH + " characters");
        }
        return trimmed;
    }
}
