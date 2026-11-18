package com.riccon.api.run.service;

import com.riccon.api.chat.service.ConversationService;
import com.riccon.api.error.NotFoundException;
import com.riccon.api.file.domain.FileRecord;
import com.riccon.api.file.repository.FileRepository;
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
 * <p>运行 ID 不是访问凭据：先按租户读取，再按运行对象所属工作空间做访问判定，任何不可见都返回 404 而不是 403，
 * 避免通过状态码探测其他租户或其他员工的对象是否存在（ADR-0009）。
 *
 * <ul>
 *   <li>CHAT：按会话所属工作空间判定，共享空间成员可读取会话运行（ADR-0018 第 3.2 节）；
 *   <li>RESEARCH_REPORT：按报告任务所属工作空间判定；
 *   <li>FILE_PARSE：按来源文件所属工作空间判定，个人空间仍只允许所有者；
 *   <li>KNOWLEDGE_INDEX：知识库在 M03 为租户级共享，但运行详情仍只允许发起人读取，避免暴露他人模型调用明细。
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

    /** 文件仓储，用于解析运行的空间归属。 */
    private final FileRepository fileRepository;

    /** 工作空间可见性。 */
    private final WorkspaceService workspaceService;

    /**
     * 创建运行详情服务。
     *
     * @param runRepository 运行仓储
     * @param runStageRepository 阶段时间线仓储
     * @param modelInvocationRepository 模型调用记录仓储
     * @param conversationService 会话访问判定
     * @param researchTaskRepository 报告任务仓储
     * @param fileRepository 文件仓储
     * @param workspaceService 工作空间可见性
     */
    public RunDetailService(
            RunRepository runRepository,
            RunStageRepository runStageRepository,
            ModelInvocationRepository modelInvocationRepository,
            ConversationService conversationService,
            ResearchTaskRepository researchTaskRepository,
            FileRepository fileRepository,
            WorkspaceService workspaceService) {
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository must not be null");
        this.runStageRepository = Objects.requireNonNull(runStageRepository, "runStageRepository must not be null");
        this.modelInvocationRepository =
                Objects.requireNonNull(modelInvocationRepository, "modelInvocationRepository must not be null");
        this.conversationService = Objects.requireNonNull(conversationService, "conversationService must not be null");
        this.researchTaskRepository =
                Objects.requireNonNull(researchTaskRepository, "researchTaskRepository must not be null");
        this.fileRepository = Objects.requireNonNull(fileRepository, "fileRepository must not be null");
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
     * 按运行对象所属空间校验可见性。
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
                // 会话服务会先读取会话所属空间再调用 requireAccessible，不再按会话创建人限制。
                conversationService.requireAccessible(actor, run.conversationId());
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
            case FILE_PARSE -> {
                if (run.fileId() == null) {
                    throw new NotFoundException("run");
                }
                FileRecord file =
                        fileRepository.findById(tenantId, run.fileId()).orElseThrow(() -> new NotFoundException("run"));
                workspaceService.requireAccessible(tenantId, file.workspaceId(), actor.userId());
            }
            case KNOWLEDGE_INDEX -> {
                // 知识库在 M03 是租户级共享，但运行详情包含模型调用明细，仍只对发起人开放。
                if (!run.userId().equals(actor.userId())) {
                    throw new NotFoundException("run");
                }
            }
        }
    }
}
