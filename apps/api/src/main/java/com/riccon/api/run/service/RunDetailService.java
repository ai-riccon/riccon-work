package com.riccon.api.run.service;

import com.riccon.api.chat.service.ConversationService;
import com.riccon.api.error.NotFoundException;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.research.domain.ResearchTaskSnapshot;
import com.riccon.api.research.domain.RunStageSnapshot;
import com.riccon.api.research.repository.ResearchTaskRepository;
import com.riccon.api.research.repository.RunStageRepository;
import com.riccon.api.run.domain.ModelInvocationSnapshot;
import com.riccon.api.run.domain.RunDetailSnapshot;
import com.riccon.api.run.dto.RunDetailResponse;
import com.riccon.api.run.repository.ModelInvocationRepository;
import com.riccon.api.run.repository.RunRepository;
import com.riccon.api.workspace.service.WorkspaceService;
import com.riccon.platform.common.tenant.TenantId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运行详情读取。
 *
 * <p>运行 ID 不是访问凭据：先按租户读取，再按运行类别做对象级可见性校验，任何不可见都返回 404 而不是 403，
 * 避免通过状态码探测其他租户或其他员工的对象是否存在（ADR-0009）。
 *
 * <ul>
 *   <li>CHAT：只允许会话所有者读取，与 J1 会话读取口径一致；
 *   <li>RESEARCH_REPORT：只允许能访问报告任务所在工作空间的员工读取；
 *   <li>FILE_PARSE / KNOWLEDGE_INDEX：只允许运行发起人读取；M02 没有共享文件运行详情的需求。
 * </ul>
 */
@Service
public class RunDetailService {

    /** 运行仓储。 */
    private final RunRepository runRepository;

    /** 阶段时间线仓储。 */
    private final RunStageRepository runStageRepository;

    /** 模型调用记录仓储。 */
    private final ModelInvocationRepository modelInvocationRepository;

    /** 会话可见性。 */
    private final ConversationService conversationService;

    /** 报告任务仓储。 */
    private final ResearchTaskRepository researchTaskRepository;

    /** 工作空间可见性。 */
    private final WorkspaceService workspaceService;

    /**
     * 创建运行详情服务。
     *
     * @param runRepository 运行仓储
     * @param runStageRepository 阶段时间线仓储
     * @param modelInvocationRepository 模型调用记录仓储
     * @param conversationService 会话可见性
     * @param researchTaskRepository 报告任务仓储
     * @param workspaceService 工作空间可见性
     */
    public RunDetailService(
            RunRepository runRepository,
            RunStageRepository runStageRepository,
            ModelInvocationRepository modelInvocationRepository,
            ConversationService conversationService,
            ResearchTaskRepository researchTaskRepository,
            WorkspaceService workspaceService) {
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository must not be null");
        this.runStageRepository = Objects.requireNonNull(runStageRepository, "runStageRepository must not be null");
        this.modelInvocationRepository =
                Objects.requireNonNull(modelInvocationRepository, "modelInvocationRepository must not be null");
        this.conversationService = Objects.requireNonNull(conversationService, "conversationService must not be null");
        this.researchTaskRepository =
                Objects.requireNonNull(researchTaskRepository, "researchTaskRepository must not be null");
        this.workspaceService = Objects.requireNonNull(workspaceService, "workspaceService must not be null");
    }

    /**
     * 读取运行详情。
     *
     * @param actor 当前员工
     * @param runId 运行 ID
     * @return 运行详情
     * @throws NotFoundException 运行不存在、属于其他租户或对当前员工不可见
     */
    @Transactional(readOnly = true)
    public RunDetailResponse get(RicconPrincipal actor, UUID runId) {
        TenantId tenantId = TenantId.of(actor.tenantId());
        RunDetailSnapshot run =
                runRepository.findDetail(tenantId, runId).orElseThrow(() -> new NotFoundException("run"));
        requireVisible(actor, tenantId, run);
        List<RunStageSnapshot> stages = runStageRepository.listByRun(tenantId, run.id());
        List<ModelInvocationSnapshot> invocations = modelInvocationRepository.listByRun(tenantId, run.id());
        return RunDetailResponse.from(run, stages, invocations);
    }

    /**
     * 按运行类别校验对象级可见性。
     *
     * @param actor 当前员工
     * @param tenantId 当前租户
     * @param run 运行详情
     * @throws NotFoundException 运行对当前员工不可见
     */
    private void requireVisible(RicconPrincipal actor, TenantId tenantId, RunDetailSnapshot run) {
        switch (run.kind()) {
            case CHAT -> {
                if (run.conversationId() == null) {
                    throw new NotFoundException("run");
                }
                conversationService.requireOwned(actor, run.conversationId());
            }
            case RESEARCH_REPORT -> {
                if (run.researchTaskId() == null) {
                    throw new NotFoundException("run");
                }
                ResearchTaskSnapshot task = researchTaskRepository
                        .findById(tenantId, run.researchTaskId())
                        .orElseThrow(() -> new NotFoundException("run"));
                workspaceService.requireAccessible(tenantId, task.workspaceId(), actor.userId());
            }
            case FILE_PARSE, KNOWLEDGE_INDEX -> {
                if (!run.userId().equals(actor.userId())) {
                    throw new NotFoundException("run");
                }
            }
        }
    }
}
