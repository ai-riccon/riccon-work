package com.riccon.api.workspace.service;

import com.riccon.api.common.UuidV7;
import com.riccon.api.error.NotFoundException;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.web.Validation;
import com.riccon.api.workspace.domain.WorkspaceRecord;
import com.riccon.api.workspace.dto.WorkspaceCreateRequest;
import com.riccon.api.workspace.dto.WorkspacePageResponse;
import com.riccon.api.workspace.dto.WorkspaceResponse;
import com.riccon.api.workspace.repository.WorkspaceRepository;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.workspace.WorkspaceKind;
import com.riccon.platform.domain.workspace.WorkspaceStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 工作空间业务规则。
 *
 * <p>个人空间在首次列出时自动创建且每人唯一；团队空间由拥有 {@code workspace.write} 的租户管理员创建，
 * M02 对本租户全部员工可见。越权与跨租户统一返回 404，不区分“不存在”和“不可见”（ADR-0009）。
 */
@Service
public class WorkspaceService {

    /** 个人空间名称后缀。 */
    private static final String PERSONAL_SUFFIX = " 的个人空间";

    /** 名称最大长度，与 V2 列定义一致。 */
    private static final int MAX_NAME_LENGTH = 200;

    /** 说明最大长度。 */
    private static final int MAX_DESCRIPTION_LENGTH = 1000;

    /** 工作空间仓储。 */
    private final WorkspaceRepository workspaceRepository;

    /** 可注入时钟。 */
    private final Clock clock;

    /**
     * 创建服务。
     *
     * @param workspaceRepository 工作空间仓储
     * @param clock 可注入时钟
     */
    public WorkspaceService(WorkspaceRepository workspaceRepository, Clock clock) {
        this.workspaceRepository = Objects.requireNonNull(workspaceRepository, "workspaceRepository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 列出当前员工可见的工作空间，并在首次访问时创建个人空间。
     *
     * @param actor 当前员工
     * @param page 分页参数
     * @param kind 可选类型过滤
     * @return 工作空间分页
     */
    @Transactional
    public WorkspacePageResponse list(RicconPrincipal actor, PageQuery page, WorkspaceKind kind) {
        TenantId tenantId = TenantId.of(actor.tenantId());
        ensurePersonalWorkspace(tenantId, actor);
        long total = workspaceRepository.countAccessible(tenantId, actor.userId(), kind);
        List<WorkspaceResponse> items =
                workspaceRepository.listAccessible(tenantId, actor.userId(), kind, page.size(), page.offset()).stream()
                        .map(WorkspaceResponse::from)
                        .toList();
        return new WorkspacePageResponse(page.page(), page.size(), total, page.totalPages(total), items);
    }

    /**
     * 创建团队工作空间。
     *
     * @param actor 当前员工
     * @param request 创建请求
     * @return 新工作空间
     */
    @Transactional
    public WorkspaceResponse createTeam(RicconPrincipal actor, WorkspaceCreateRequest request) {
        Validation.requireBody(request);
        String name = Validation.requireText(request.name(), "name", 1, MAX_NAME_LENGTH);
        String description = normalizeDescription(request.description());
        Instant now = clock.instant();
        WorkspaceRecord workspace = new WorkspaceRecord(
                UuidV7.generate(now),
                WorkspaceKind.TEAM,
                name,
                description,
                actor.userId(),
                WorkspaceStatus.ACTIVE,
                now,
                now);
        workspaceRepository.insert(TenantId.of(actor.tenantId()), null, workspace, actor.userId());
        return WorkspaceResponse.from(workspace);
    }

    /**
     * 读取对当前员工可见的工作空间。
     *
     * @param actor 当前员工
     * @param workspaceId 工作空间 ID
     * @return 工作空间
     * @throws NotFoundException 不存在、属于其他租户或不可见
     */
    @Transactional(readOnly = true)
    public WorkspaceResponse get(RicconPrincipal actor, UUID workspaceId) {
        return WorkspaceResponse.from(requireAccessible(TenantId.of(actor.tenantId()), workspaceId, actor.userId()));
    }

    /**
     * 校验工作空间对指定员工可见。
     *
     * <p>供文件服务复用；个人空间只允许所有者，团队空间对本租户员工开放，归档空间仍可按 ID 访问。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @param userId 账号 ID
     * @return 工作空间快照
     * @throws NotFoundException 不存在或不可见
     */
    @Transactional(readOnly = true)
    public WorkspaceRecord requireAccessible(TenantId tenantId, UUID workspaceId, UUID userId) {
        return workspaceRepository
                .findAccessible(tenantId, workspaceId, userId)
                .orElseThrow(() -> new NotFoundException("workspace"));
    }

    /**
     * 确保个人空间存在；并发首次访问时依赖唯一键收敛到同一条记录。
     *
     * @param tenantId 当前租户
     * @param actor 当前员工
     * @return 个人空间
     */
    private WorkspaceRecord ensurePersonalWorkspace(TenantId tenantId, RicconPrincipal actor) {
        return workspaceRepository.findPersonal(tenantId, actor.userId()).orElseGet(() -> {
            Instant now = clock.instant();
            WorkspaceRecord workspace = new WorkspaceRecord(
                    UuidV7.generate(now),
                    WorkspaceKind.PERSONAL,
                    personalName(actor),
                    null,
                    actor.userId(),
                    WorkspaceStatus.ACTIVE,
                    now,
                    now);
            try {
                workspaceRepository.insert(tenantId, actor.userId(), workspace, actor.userId());
                return workspace;
            } catch (DuplicateKeyException exception) {
                // 两个并发首次请求只有一个插入成功；失败方读取已提交记录，保持每人一个个人空间。
                return workspaceRepository
                        .findPersonal(tenantId, actor.userId())
                        .orElseThrow(
                                () -> new IllegalStateException("personal workspace insert conflicted", exception));
            }
        });
    }

    /**
     * 生成个人空间名称；展示名可能已接近列上限，需截断后再加后缀。
     *
     * @param actor 当前员工
     * @return 不超过 200 字符的名称
     */
    private static String personalName(RicconPrincipal actor) {
        String base = actor.displayName() == null || actor.displayName().isBlank()
                ? actor.username()
                : actor.displayName().strip();
        int maxBaseLength = MAX_NAME_LENGTH - PERSONAL_SUFFIX.length();
        String truncated = base.length() <= maxBaseLength ? base : base.substring(0, maxBaseLength);
        return truncated + PERSONAL_SUFFIX;
    }

    /**
     * 标准化可选说明；空白值统一为 {@code null}，避免列表展示空串。
     *
     * @param description 原始说明
     * @return 去空白后的说明或 {@code null}
     * @throws com.riccon.api.error.BadRequestException 超过 1000 字符
     */
    private static String normalizeDescription(String description) {
        if (description == null) {
            return null;
        }
        String trimmed = description.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        return Validation.requireText(trimmed, "description", 1, MAX_DESCRIPTION_LENGTH);
    }
}
