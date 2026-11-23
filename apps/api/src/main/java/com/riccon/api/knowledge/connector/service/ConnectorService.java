package com.riccon.api.knowledge.connector.service;

import com.riccon.api.audit.service.AuditService;
import com.riccon.api.auth.repository.TenantDirectoryRepository;
import com.riccon.api.common.UuidV7;
import com.riccon.api.error.ForbiddenException;
import com.riccon.api.error.NotFoundException;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.knowledge.connector.config.ConnectorProperties;
import com.riccon.api.knowledge.connector.domain.ConnectorConfig;
import com.riccon.api.knowledge.connector.domain.ConnectorDocumentRow;
import com.riccon.api.knowledge.connector.domain.ConnectorRecord;
import com.riccon.api.knowledge.connector.dto.ConnectorItemPageResponse;
import com.riccon.api.knowledge.connector.dto.ConnectorItemResponse;
import com.riccon.api.knowledge.connector.dto.ConnectorPageResponse;
import com.riccon.api.knowledge.connector.dto.ConnectorResponse;
import com.riccon.api.knowledge.connector.repository.ConnectorRepository;
import com.riccon.api.knowledge.connector.service.ConnectorRequestValidator.CreateCommand;
import com.riccon.api.knowledge.connector.service.ConnectorRequestValidator.CredentialInput;
import com.riccon.api.knowledge.connector.service.ConnectorRequestValidator.UpdateCommand;
import com.riccon.api.knowledge.service.KnowledgeBadRequestException;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.identity.PermissionCodes;
import com.riccon.platform.domain.knowledge.ConnectorItemStatus;
import com.riccon.platform.domain.knowledge.ConnectorStatus;
import com.riccon.platform.domain.knowledge.ConnectorType;
import com.riccon.platform.domain.knowledge.KnowledgeVisibility;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 连接器管理服务：CRUD、凭据写入/轮换与删除处置。
 *
 * <p>创建必须显式给出 {@code defaultVisibility}；凭据只写不读并信封加密；删除只做软删除，凭据密文保留
 * 30 天由 M09 清理（ADR-0023 第 6.2/6.4 节）。所有审计与业务同一事务，审计失败即业务失败（TM-64）。
 */
@Service
public class ConnectorService {

    /** 连接器仓储。 */
    private final ConnectorRepository connectorRepository;

    /** 连接器配置（功能开关、周期、端点允许列表）。 */
    private final ConnectorProperties properties;

    /** 凭据加密。 */
    private final ConnectorCredentials credentials;

    /** 审计写入。 */
    private final AuditService auditService;

    /** 租户目录，用于停用校验。 */
    private final TenantDirectoryRepository tenantDirectoryRepository;

    /** 连接器删除传播事件。 */
    private final ConnectorIndexEvents indexEvents;

    /** 可注入时钟。 */
    private final Clock clock;

    /**
     * 创建服务。
     *
     * @param connectorRepository 连接器仓储
     * @param properties 连接器配置
     * @param credentials 凭据加密
     * @param auditService 审计写入
     * @param tenantDirectoryRepository 租户目录
     * @param indexEvents 删除传播事件
     * @param clock 可注入时钟
     */
    public ConnectorService(
            ConnectorRepository connectorRepository,
            ConnectorProperties properties,
            ConnectorCredentials credentials,
            AuditService auditService,
            TenantDirectoryRepository tenantDirectoryRepository,
            ConnectorIndexEvents indexEvents,
            Clock clock) {
        this.connectorRepository = Objects.requireNonNull(connectorRepository, "connectorRepository must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.credentials = Objects.requireNonNull(credentials, "credentials must not be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
        this.tenantDirectoryRepository =
                Objects.requireNonNull(tenantDirectoryRepository, "tenantDirectoryRepository must not be null");
        this.indexEvents = Objects.requireNonNull(indexEvents, "indexEvents must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 分页列出当前租户连接器。
     *
     * @param actor 当前主体
     * @param page 分页
     * @param status 可选状态
     * @param type 可选类型
     * @return 连接器分页
     */
    @Transactional(readOnly = true)
    public ConnectorPageResponse list(
            RicconPrincipal actor, PageQuery page, ConnectorStatus status, ConnectorType type) {
        TenantId tenantId = TenantId.of(actor.tenantId());
        requireActiveTenant(tenantId, PermissionCodes.KNOWLEDGE_CONNECTOR_READ);
        long total = connectorRepository.countConnectors(tenantId, status, type);
        List<ConnectorResponse> items =
                connectorRepository.listConnectors(tenantId, status, type, page.size(), page.offset()).stream()
                        .map(ConnectorResponse::from)
                        .toList();
        return new ConnectorPageResponse(page.page(), page.size(), total, page.totalPages(total), items);
    }

    /**
     * 分页列出连接器条目。
     *
     * @param actor 当前主体
     * @param connectorId 连接器 ID
     * @param page 分页
     * @param status 可选条目状态
     * @return 条目分页
     */
    @Transactional(readOnly = true)
    public ConnectorItemPageResponse listItems(
            RicconPrincipal actor, UUID connectorId, PageQuery page, ConnectorItemStatus status) {
        TenantId tenantId = TenantId.of(actor.tenantId());
        requireActiveTenant(tenantId, PermissionCodes.KNOWLEDGE_CONNECTOR_READ);
        connectorRepository.findActiveById(tenantId, connectorId).orElseThrow(() -> new NotFoundException("connector"));
        long total = connectorRepository.countItems(tenantId, connectorId, status);
        List<ConnectorItemResponse> items =
                connectorRepository.listItems(tenantId, connectorId, status, page.size(), page.offset()).stream()
                        .map(ConnectorItemResponse::from)
                        .toList();
        return new ConnectorItemPageResponse(page.page(), page.size(), total, page.totalPages(total), items);
    }

    /**
     * 创建连接器。
     *
     * @param actor 当前主体
     * @param command 创建命令
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @return 已创建连接器
     */
    @Transactional
    public ConnectorResponse create(RicconPrincipal actor, CreateCommand command, String traceId, String requestId) {
        requireEnabled();
        TenantId tenantId = TenantId.of(actor.tenantId());
        requireActiveTenant(tenantId, PermissionCodes.KNOWLEDGE_CONNECTOR_MANAGE);
        requireAllowedEndpoint(command.endpointId());
        requireActiveDepartment(tenantId, command.defaultVisibility(), command.defaultDepartmentId());
        Instant now = clock.instant();
        UUID connectorId = UuidV7.generate(now);
        ConnectorCredentials.Sealed sealed =
                command.credential() == null ? null : encryptCredential(tenantId, command.credential());
        ConnectorRecord connector = new ConnectorRecord(
                connectorId,
                command.name(),
                command.type(),
                ConnectorStatus.PAUSED,
                command.endpointId(),
                command.config(),
                command.defaultVisibility(),
                command.defaultDepartmentId(),
                sealed == null ? null : sealed.ciphertext(),
                sealed == null ? null : sealed.keyId(),
                sealed == null ? null : sealed.maskedHint(),
                command.scheduleEnabled(),
                command.scheduleEnabled() ? now.plus(syncInterval()) : null,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                actor.userId(),
                now,
                now,
                null);
        try {
            connectorRepository.insertConnector(tenantId, connector);
        } catch (DuplicateKeyException exception) {
            throw new ConnectorConflictException("connector name already exists");
        }
        auditService.append(
                ConnectorAuditEvents.created(now, actor.tenantId(), actor.userId(), connector, traceId, requestId));
        return ConnectorResponse.from(connector);
    }

    /**
     * 部分更新连接器。
     *
     * @param actor 当前主体
     * @param connectorId 连接器 ID
     * @param command 更新命令
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @return 更新后的连接器
     */
    @Transactional
    public ConnectorResponse update(
            RicconPrincipal actor, UUID connectorId, UpdateCommand command, String traceId, String requestId) {
        requireEnabled();
        TenantId tenantId = TenantId.of(actor.tenantId());
        requireActiveTenant(tenantId, PermissionCodes.KNOWLEDGE_CONNECTOR_MANAGE);
        ConnectorRecord current = connectorRepository
                .lockActive(tenantId, connectorId)
                .orElseThrow(() -> new NotFoundException("connector"));
        String name = command.name() == null ? current.name() : command.name();
        String endpointId = command.endpointId() == null ? current.endpointId() : command.endpointId();
        requireAllowedEndpoint(endpointId);
        ConnectorConfig config = command.config() == null ? current.config() : command.config();
        KnowledgeVisibility visibility =
                command.defaultVisibility() == null ? current.defaultVisibility() : command.defaultVisibility();
        UUID departmentId = resolveDepartment(current, command, visibility);
        requireActiveDepartment(tenantId, visibility, departmentId);
        boolean scheduleEnabled =
                command.scheduleEnabled() == null ? current.scheduleEnabled() : command.scheduleEnabled();
        ConnectorStatus status = command.status() == null ? current.status() : command.status();
        Instant now = clock.instant();
        Instant nextSyncAt = nextSyncAt(current, scheduleEnabled, status);
        int updated;
        try {
            updated = connectorRepository.updateConnector(
                    tenantId,
                    connectorId,
                    name,
                    endpointId,
                    config,
                    visibility,
                    departmentId,
                    scheduleEnabled,
                    nextSyncAt,
                    status,
                    now);
        } catch (DuplicateKeyException exception) {
            throw new ConnectorConflictException("connector name already exists");
        }
        if (updated == 0) {
            throw new NotFoundException("connector");
        }
        ConnectorRecord after = connectorRepository
                .findActiveById(tenantId, connectorId)
                .orElseThrow(() -> new NotFoundException("connector"));
        auditService.append(ConnectorAuditEvents.updated(
                now, actor.tenantId(), actor.userId(), after, current.status(), traceId, requestId));
        if (current.status() != status) {
            auditService.append(ConnectorAuditEvents.statusChanged(
                    now, actor.tenantId(), actor.userId(), after, current.status(), traceId, requestId));
        }
        return ConnectorResponse.from(after);
    }

    /**
     * 写入或轮换凭据；响应只返回掩码。
     *
     * @param actor 当前主体
     * @param connectorId 连接器 ID
     * @param credential 凭据
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @return 更新后的连接器
     */
    @Transactional
    public ConnectorResponse putCredential(
            RicconPrincipal actor, UUID connectorId, CredentialInput credential, String traceId, String requestId) {
        requireEnabled();
        TenantId tenantId = TenantId.of(actor.tenantId());
        requireActiveTenant(tenantId, PermissionCodes.KNOWLEDGE_CONNECTOR_MANAGE);
        connectorRepository.lockActive(tenantId, connectorId).orElseThrow(() -> new NotFoundException("connector"));
        Instant now = clock.instant();
        ConnectorCredentials.Sealed sealed = encryptCredential(tenantId, credential);
        if (connectorRepository.updateCredential(
                        tenantId, connectorId, sealed.ciphertext(), sealed.keyId(), sealed.maskedHint(), now)
                == 0) {
            throw new NotFoundException("connector");
        }
        ConnectorRecord after = connectorRepository
                .findActiveById(tenantId, connectorId)
                .orElseThrow(() -> new NotFoundException("connector"));
        auditService.append(ConnectorAuditEvents.credentialChanged(
                now, actor.tenantId(), actor.userId(), after, sealed.keyId(), sealed.maskedHint(), traceId, requestId));
        return ConnectorResponse.from(after);
    }

    /**
     * 软删除连接器并按 {@code documents} 处置已导入文档。
     *
     * @param actor 当前主体
     * @param connectorId 连接器 ID
     * @param documents {@code KEEP} 或 {@code SOFT_DELETE}
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     */
    @Transactional
    public void delete(RicconPrincipal actor, UUID connectorId, String documents, String traceId, String requestId) {
        String disposition = documents == null || documents.isBlank() ? "KEEP" : documents.strip();
        if (!"KEEP".equals(disposition) && !"SOFT_DELETE".equals(disposition)) {
            throw new KnowledgeBadRequestException("documents must be KEEP or SOFT_DELETE");
        }
        TenantId tenantId = TenantId.of(actor.tenantId());
        requireActiveTenant(tenantId, PermissionCodes.KNOWLEDGE_CONNECTOR_MANAGE);
        ConnectorRecord connector = connectorRepository
                .lockIncludingDeleted(tenantId, connectorId)
                .orElseThrow(() -> new NotFoundException("connector"));
        if (connector.deletedAt() != null) {
            // 幂等：重复删除返回 204，不重复写审计或事件。
            return;
        }
        Instant now = clock.instant();
        if (connectorRepository.softDeleteConnector(tenantId, connectorId, now) == 0) {
            return;
        }
        if ("SOFT_DELETE".equals(disposition)) {
            softDeleteDocuments(tenantId, connectorId, actor.userId(), traceId, requestId, now);
        }
        auditService.append(ConnectorAuditEvents.deleted(
                now, actor.tenantId(), actor.userId(), connector, "DOCUMENTS_" + disposition, traceId, requestId));
    }

    /**
     * 软删除连接器导入的文档并投递索引清理。
     *
     * @param tenantId 租户
     * @param connectorId 连接器 ID
     * @param actorId 操作者
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @param now 删除时间
     */
    private void softDeleteDocuments(
            TenantId tenantId, UUID connectorId, UUID actorId, String traceId, String requestId, Instant now) {
        for (ConnectorDocumentRow document : connectorRepository.findActiveDocuments(tenantId, connectorId)) {
            if (connectorRepository.softDeleteDocument(tenantId, document.documentId(), now) == 0) {
                continue;
            }
            auditService.append(ConnectorAuditEvents.documentDeleted(
                    now,
                    tenantId.value(),
                    actorId,
                    document.documentId(),
                    document.visibility(),
                    "CONNECTOR",
                    traceId,
                    requestId));
            indexEvents.enqueueDelete(
                    tenantId, document.documentId(), document.aclVersion(), actorId, "CONNECTOR", traceId, now);
        }
    }

    /**
     * 解析更新后的部门；显式提供优先，否则沿用原部门。
     *
     * @param current 当前连接器
     * @param command 更新命令
     * @param visibility 目标可见范围
     * @return 部门 ID
     */
    private static UUID resolveDepartment(
            ConnectorRecord current, UpdateCommand command, KnowledgeVisibility visibility) {
        if (command.defaultVisibility() != null
                && visibility != KnowledgeVisibility.DEPT
                && !command.departmentProvided()) {
            // 从 DEPT 切回 TENANT 时未显式带 defaultDepartmentId：按契约清空部门。
            return null;
        }
        UUID departmentId =
                command.departmentProvided() ? command.defaultDepartmentId() : current.defaultDepartmentId();
        if (visibility == KnowledgeVisibility.DEPT) {
            if (departmentId == null) {
                throw new KnowledgeBadRequestException(
                        "defaultDepartmentId is required when defaultVisibility is DEPT");
            }
            return departmentId;
        }
        if (departmentId != null) {
            throw new KnowledgeBadRequestException(
                    "defaultDepartmentId is only allowed when defaultVisibility is DEPT");
        }
        return null;
    }

    /**
     * 计算下一次定时同步时间；关闭调度时为 null。
     *
     * @param current 当前连接器
     * @param scheduleEnabled 目标开关
     * @param status 目标状态
     * @return 下一次同步时间
     */
    private Instant nextSyncAt(ConnectorRecord current, boolean scheduleEnabled, ConnectorStatus status) {
        if (!scheduleEnabled) {
            return null;
        }
        if (current.nextSyncAt() != null && (current.status() == status || status != ConnectorStatus.ACTIVE)) {
            return current.nextSyncAt();
        }
        return clock.instant().plus(syncInterval());
    }

    /**
     * 校验默认部门为启用状态。
     *
     * @param tenantId 租户
     * @param visibility 可见范围
     * @param departmentId 部门
     */
    private void requireActiveDepartment(TenantId tenantId, KnowledgeVisibility visibility, UUID departmentId) {
        if (visibility != KnowledgeVisibility.DEPT) {
            return;
        }
        if (departmentId == null
                || connectorRepository
                        .findActiveDepartment(tenantId, departmentId)
                        .isEmpty()) {
            throw new KnowledgeBadRequestException("defaultDepartmentId must be an active department");
        }
    }

    /**
     * 校验端点是否在部署允许列表。
     *
     * @param endpointId 端点 ID
     */
    private void requireAllowedEndpoint(String endpointId) {
        if (properties.registry() == null
                || properties.registry().findById(endpointId).isEmpty()) {
            throw new KnowledgeBadRequestException("endpointId is not in the deployment allowlist");
        }
    }

    /**
     * 加密凭据。
     *
     * @param tenantId 租户
     * @param credential 凭据
     * @return 加密结果
     */
    private ConnectorCredentials.Sealed encryptCredential(TenantId tenantId, CredentialInput credential) {
        try {
            return credentials.encrypt(tenantId, credential.accessKeyId(), credential.secretAccessKey());
        } catch (RuntimeException exception) {
            // 加密失败通常表示主密钥不可用；不降级明文，直接 503 让部署方修复。
            throw new ConnectorUnavailableException("connector credential encryption is unavailable");
        }
    }

    /**
     * 校验功能开关。
     */
    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new ConnectorUnavailableException("connector feature is disabled");
        }
    }

    /**
     * 校验租户状态；停用租户拒绝管理性读写。
     *
     * @param tenantId 租户
     * @param permission 权限码
     */
    private void requireActiveTenant(TenantId tenantId, String permission) {
        boolean active = tenantDirectoryRepository
                .findById(tenantId.value())
                .map(tenant -> tenant.isActive())
                .orElse(false);
        if (!active) {
            throw new ForbiddenException(permission);
        }
    }

    /**
     * 返回同步周期。
     *
     * @return 周期
     */
    private Duration syncInterval() {
        return Duration.ofMinutes(properties.syncIntervalMinutes());
    }
}
