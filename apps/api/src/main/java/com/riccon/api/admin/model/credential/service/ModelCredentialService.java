package com.riccon.api.admin.model.credential.service;

import com.riccon.api.admin.model.credential.domain.CredentialReasonCodes;
import com.riccon.api.admin.model.credential.domain.ModelCredentialConflictException;
import com.riccon.api.admin.model.credential.domain.ModelCredentialInvalidRequestException;
import com.riccon.api.admin.model.credential.domain.ModelCredentialRow;
import com.riccon.api.admin.model.credential.domain.PlatformDataKeyRecord;
import com.riccon.api.admin.model.credential.dto.ModelCredentialCreateRequest;
import com.riccon.api.admin.model.credential.dto.ModelCredentialPageResponse;
import com.riccon.api.admin.model.credential.dto.ModelCredentialResponse;
import com.riccon.api.admin.model.credential.dto.ModelCredentialRotateRequest;
import com.riccon.api.admin.model.credential.repository.ModelCredentialRepository;
import com.riccon.api.admin.model.provider.domain.ProviderCatalogRow;
import com.riccon.api.admin.model.provider.repository.ModelProviderRepository;
import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.audit.service.AuditService;
import com.riccon.api.common.UuidV7;
import com.riccon.api.crypto.CredentialCipher;
import com.riccon.api.crypto.MasterKeyProvider;
import com.riccon.api.error.NotFoundException;
import com.riccon.api.identity.domain.PlatformTenant;
import com.riccon.api.identity.domain.RequestMeta;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.platform.common.crypto.CredentialEnvelope;
import com.riccon.platform.common.crypto.MasterKeyRing;
import com.riccon.platform.common.model.ManagedEndpointRegistry;
import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 平台托管模型 key 池服务。
 *
 * <p>实现 ADR-0021 §6 的 key 生命周期：新增、轮换、停用、恢复；所有写入都在单事务内完成，审计与业务
 * 同事务（失败即失败）；查询与更新固定 {@code tenant_id='platform'}，租户级遗留凭据不可见也不可操作。
 *
 * <p><strong>在途影响</strong>：停用/轮换只改状态与时间列，不修改旧行的 ciphertext/key_id，也不取消已经建立的
 * HTTP 连接。请求开始后已解密的 key 仍可用于该次尝试；新请求在状态提交后立即看不到该 key。管理员确认框
 * 的文案由前端承担（B9a），服务端保证不破坏在途已读取的密文材料。
 *
 * <p><strong>明文边界</strong>：apiKey 只存在于本方法栈帧与加密调用参数中；不写日志、不写审计、不进入响应，
 * 也不缓存到字段。异常消息固定且不含明文或密文。
 */
@Service
public class ModelCredentialService {

    /** DEK 用途，与 Worker 解密侧一致。 */
    private static final String PURPOSE = CredentialEnvelope.PURPOSE_MODEL_CREDENTIAL;

    /** DEK 算法。 */
    private static final String DEK_ALGORITHM = "AES-256-GCM";

    /** 数据密钥长度。 */
    private static final int DEK_BYTES = 32;

    /** apiKey 最小长度（ADR-0021 §3.2）。 */
    private static final int MIN_API_KEY_LENGTH = 16;

    /** apiKey 最大长度（OpenAPI）。 */
    private static final int MAX_API_KEY_LENGTH = 512;

    /** label 最大长度。 */
    private static final int MAX_LABEL_LENGTH = 64;

    /** 默认优先级。 */
    private static final int DEFAULT_PRIORITY = 100;

    /** 最小优先级。 */
    private static final int MIN_PRIORITY = 1;

    /** 最大优先级。 */
    private static final int MAX_PRIORITY = 1000;

    /** 团队默认时区偏移（Asia/Shanghai）；数据库仍存 UTC。 */
    private static final ZoneOffset TEAM_OFFSET = ZoneOffset.ofHours(8);

    /** 已知占位值；精确匹配（忽略大小写），避免管理员误把示例值当真实 key 写入。 */
    private static final Set<String> PLACEHOLDER_KEYS = Set.of(
            "changeme",
            "change-me",
            "placeholder",
            "your-api-key",
            "your_api_key",
            "example",
            "example-key",
            "dummy",
            "sk-xxx",
            "sk-your-api-key",
            "replace-me");

    /** 进程级安全随机源；DEK 生成使用。 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 凭据仓储。 */
    private final ModelCredentialRepository credentialRepository;

    /** 供应商目录仓储，用于 404 与允许列表校验。 */
    private final ModelProviderRepository providerRepository;

    /** 部署端点允许列表。 */
    private final ManagedEndpointRegistry endpointRegistry;

    /** 主密钥提供者。 */
    private final MasterKeyProvider masterKeyProvider;

    /** 凭据信封加密器。 */
    private final CredentialCipher credentialCipher;

    /** 审计写入。 */
    private final AuditService auditService;

    /** 可注入时钟。 */
    private final Clock clock;

    /**
     * 创建 key 池服务。
     *
     * @param credentialRepository 凭据仓储
     * @param providerRepository 供应商目录仓储
     * @param endpointRegistry 部署端点允许列表
     * @param masterKeyProvider 主密钥提供者
     * @param credentialCipher 凭据信封加密器
     * @param auditService 审计写入
     * @param clock 可注入时钟
     */
    public ModelCredentialService(
            ModelCredentialRepository credentialRepository,
            ModelProviderRepository providerRepository,
            ManagedEndpointRegistry endpointRegistry,
            MasterKeyProvider masterKeyProvider,
            CredentialCipher credentialCipher,
            AuditService auditService,
            Clock clock) {
        this.credentialRepository =
                Objects.requireNonNull(credentialRepository, "credentialRepository must not be null");
        this.providerRepository = Objects.requireNonNull(providerRepository, "providerRepository must not be null");
        this.endpointRegistry = Objects.requireNonNull(endpointRegistry, "endpointRegistry must not be null");
        this.masterKeyProvider = Objects.requireNonNull(masterKeyProvider, "masterKeyProvider must not be null");
        this.credentialCipher = Objects.requireNonNull(credentialCipher, "credentialCipher must not be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 分页列出 provider 的平台 key（掩码）。
     *
     * @param providerId 供应商 ID
     * @param page 分页参数
     * @return 掩码凭据分页
     * @throws NotFoundException provider 不存在
     */
    @Transactional(readOnly = true)
    public ModelCredentialPageResponse listCredentials(UUID providerId, PageQuery page) {
        requireProviderExists(providerId);
        long total = credentialRepository.count(providerId);
        List<ModelCredentialResponse> items =
                credentialRepository.findPage(providerId, page.size(), page.offset()).stream()
                        .map(row -> toResponse(row, null))
                        .toList();
        return new ModelCredentialPageResponse(
                page.page(), page.size(), total, page.totalPages(total), List.copyOf(items));
    }

    /**
     * 新增平台 key。
     *
     * <p>provider 必须存在于目录且通过部署允许列表；明文只进入请求体，服务端立即计算 HMAC fingerprint、
     * 生成/复用平台 DEK 并信封加密。fingerprint 重复返回 409。最后一把 key 不受限制（管理员可下线 provider），
     * 运行时由 Worker 决定切链或 503（ADR-0021 §6.3）。
     *
     * @param providerId 供应商 ID
     * @param request 新增请求
     * @param actor 平台管理员
     * @param meta 请求元数据
     * @return 新增的掩码凭据
     * @throws NotFoundException provider 不存在
     * @throws ModelCredentialInvalidRequestException 字段非法或 provider 不可用
     * @throws ModelCredentialConflictException fingerprint 重复
     */
    @Transactional
    public ModelCredentialResponse create(
            UUID providerId, ModelCredentialCreateRequest request, RicconPrincipal actor, RequestMeta meta) {
        Objects.requireNonNull(request, "request must not be null");
        requireManageableProvider(providerId);
        String apiKey = requireApiKey(request.apiKey());
        String label = requireLabel(request.label());
        int priority = request.priority() == null ? DEFAULT_PRIORITY : requirePriority(request.priority());
        String fingerprint = masterKeyProvider.fingerprint(providerId.toString(), apiKey);
        if (credentialRepository.fingerprintExists(providerId, fingerprint)) {
            throw duplicateCredential();
        }
        String maskedHint = masterKeyProvider.maskedHint(apiKey);
        UUID credentialId = UuidV7.generate();
        Instant now = clock.instant();
        Sealed sealed = encrypt(apiKey);
        try {
            credentialRepository.insert(
                    credentialId,
                    providerId,
                    sealed.ciphertext(),
                    sealed.keyId(),
                    sealed.masterKeyId(),
                    fingerprint,
                    maskedHint,
                    label,
                    priority,
                    now,
                    null);
        } catch (DuplicateKeyException exception) {
            // 唯一键是最终防线；并发重复创建只有一个成功。
            throw duplicateCredential();
        }
        appendAudit(
                actor,
                AuditActions.MODEL_CREDENTIAL_CREATED,
                credentialId,
                meta,
                Map.of(
                        "providerId",
                        providerId.toString(),
                        "credentialId",
                        credentialId.toString(),
                        "maskedHint",
                        maskedHint,
                        "status",
                        "ACTIVE"));
        return toResponse(requireCredential(providerId, credentialId), null);
    }

    /**
     * 轮换平台 key：单事务插入新 key 并停用旧 key。
     *
     * <p>新 key 继承旧 key 的 priority 与（未提供时）label；旧 key 只改状态与 rotated_at/updated_at，
     * ciphertext/key_id 不变，因此在途已解密的连接不会被破坏。并发轮换通过旧行 version 条件更新串行化，
     * 只有一个成功，另一个返回 409；新 key 与旧 key 明文相同时 fingerprint 唯一键返回 409。
     *
     * @param providerId 供应商 ID
     * @param credentialId 旧凭据 ID
     * @param request 轮换请求
     * @param actor 平台管理员
     * @param meta 请求元数据
     * @return 新 key 的掩码视图
     * @throws NotFoundException provider/credential 不存在
     * @throws ModelCredentialInvalidRequestException 字段非法或 provider 不可用
     * @throws ModelCredentialConflictException 状态冲突或 fingerprint 重复
     */
    @Transactional
    public ModelCredentialResponse rotate(
            UUID providerId,
            UUID credentialId,
            ModelCredentialRotateRequest request,
            RicconPrincipal actor,
            RequestMeta meta) {
        Objects.requireNonNull(request, "request must not be null");
        requireManageableProvider(providerId);
        ModelCredentialRow previous = requireCredential(providerId, credentialId);
        if ("DISABLED".equals(previous.status())) {
            throw stateConflict("credential is disabled; enable it before rotation");
        }
        String apiKey = requireApiKey(request.apiKey());
        String label = request.label() == null ? previous.label() : requireLabel(request.label());
        String fingerprint = masterKeyProvider.fingerprint(providerId.toString(), apiKey);
        if (credentialRepository.fingerprintExists(providerId, fingerprint)) {
            throw duplicateCredential();
        }
        String maskedHint = masterKeyProvider.maskedHint(apiKey);
        UUID newCredentialId = UuidV7.generate();
        Instant now = clock.instant();
        Sealed sealed = encrypt(apiKey);
        try {
            credentialRepository.insert(
                    newCredentialId,
                    providerId,
                    sealed.ciphertext(),
                    sealed.keyId(),
                    sealed.masterKeyId(),
                    fingerprint,
                    maskedHint,
                    label,
                    previous.priority(),
                    now,
                    now);
        } catch (DuplicateKeyException exception) {
            throw duplicateCredential();
        }
        int updated = credentialRepository.disableForRotation(
                providerId, credentialId, previous.version(), actor.userId(), now);
        if (updated == 0) {
            // 新 key 随事务回滚；旧 key 已被并发操作改变，返回稳定 409。
            throw stateConflict("credential state changed concurrently");
        }
        appendAudit(
                actor,
                AuditActions.MODEL_CREDENTIAL_ROTATED,
                newCredentialId,
                meta,
                Map.of(
                        "providerId", providerId.toString(),
                        "credentialId", newCredentialId.toString(),
                        "previousCredentialId", credentialId.toString(),
                        "maskedHint", maskedHint,
                        "status", "ACTIVE"));
        return toResponse(requireCredential(providerId, newCredentialId), null);
    }

    /**
     * 停用平台 key（ACTIVE/INVALID → DISABLED）。
     *
     * <p>幂等：已是 DISABLED 时返回 {@code changed=false} 且不写审计。停用不删除行、不改密文，也不取消
     * 在途连接；新请求立即不再选择该 key。允许停用最后一把 ACTIVE key。
     *
     * @param providerId 供应商 ID
     * @param credentialId 凭据 ID
     * @param actor 平台管理员
     * @param meta 请求元数据
     * @return 停用后的掩码视图
     * @throws NotFoundException provider/credential 不存在
     */
    @Transactional
    public ModelCredentialResponse disable(
            UUID providerId, UUID credentialId, RicconPrincipal actor, RequestMeta meta) {
        requireProviderExists(providerId);
        ModelCredentialRow current = requireCredential(providerId, credentialId);
        if ("DISABLED".equals(current.status())) {
            return toResponse(current, false);
        }
        int updated = credentialRepository.disable(providerId, credentialId, actor.userId(), clock.instant());
        ModelCredentialRow after = requireCredential(providerId, credentialId);
        if (updated == 0) {
            // 并发停用/状态变化：以数据库当前状态为准，不重复写审计。
            return toResponse(after, false);
        }
        appendAudit(
                actor,
                AuditActions.MODEL_CREDENTIAL_DISABLED,
                credentialId,
                meta,
                Map.of(
                        "providerId", providerId.toString(),
                        "credentialId", credentialId.toString(),
                        "maskedHint", after.maskedHint(),
                        "status", after.status(),
                        "reason", "ADMIN_DISABLED"));
        return toResponse(after, true);
    }

    /**
     * 恢复平台 key（DISABLED/INVALID → ACTIVE）。
     *
     * <p>清空 consecutive_failures、cooldown_until、invalidated_at 与停用标记；从 INVALID 恢复写
     * {@code model.credential.recovered}，从 DISABLED 恢复写 {@code model.credential.enabled}。已是 ACTIVE 时
     * 返回 {@code changed=false} 且不写审计。
     *
     * @param providerId 供应商 ID
     * @param credentialId 凭据 ID
     * @param actor 平台管理员
     * @param meta 请求元数据
     * @return 恢复后的掩码视图
     * @throws NotFoundException provider/credential 不存在
     */
    @Transactional
    public ModelCredentialResponse enable(UUID providerId, UUID credentialId, RicconPrincipal actor, RequestMeta meta) {
        requireProviderExists(providerId);
        ModelCredentialRow current = requireCredential(providerId, credentialId);
        if ("ACTIVE".equals(current.status())) {
            return toResponse(current, false);
        }
        int updated = credentialRepository.enable(providerId, credentialId, clock.instant());
        ModelCredentialRow after = requireCredential(providerId, credentialId);
        if (updated == 0) {
            return toResponse(after, false);
        }
        // 来自 INVALID 是 401/403 误判或密钥续期后的恢复，用 recovered 与自动失效事件配对；
        // 来自 DISABLED 是管理员显式停用后的恢复，用 enabled。
        boolean recovered = "INVALID".equals(current.status());
        String action = recovered ? AuditActions.MODEL_CREDENTIAL_RECOVERED : AuditActions.MODEL_CREDENTIAL_ENABLED;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("providerId", providerId.toString());
        details.put("credentialId", credentialId.toString());
        details.put("maskedHint", after.maskedHint());
        if (recovered) {
            details.put("reason", "ADMIN_RECOVERED");
        } else {
            details.put("status", after.status());
            details.put("reason", "ADMIN_ENABLED");
        }
        appendAudit(actor, action, credentialId, meta, details);
        return toResponse(after, true);
    }

    /**
     * 校验 provider 存在；读列表与停用/恢复只需要存在性。
     *
     * <p>端点被移出部署允许列表后，管理员仍需查看并停用历史 key；因此这些操作不因 {@code allowed=false}
     * 而失败，避免留下无法止血的凭据。
     *
     * @param providerId 供应商 ID
     * @return 目录行
     * @throws NotFoundException provider 不存在
     */
    private ProviderCatalogRow requireProviderExists(UUID providerId) {
        return providerRepository.findById(providerId).orElseThrow(() -> new NotFoundException("model provider"));
    }

    /**
     * 校验 provider 存在、协议支持、未停用且在部署允许列表内（新增/轮换写入新密钥前置条件）。
     *
     * @param providerId 供应商 ID
     * @return 目录行
     * @throws NotFoundException provider 不存在
     * @throws ModelCredentialInvalidRequestException provider 不可用于平台 key
     */
    private ProviderCatalogRow requireManageableProvider(UUID providerId) {
        ProviderCatalogRow provider = requireProviderExists(providerId);
        boolean usable = "ACTIVE".equals(provider.status())
                && "OPENAI_COMPATIBLE".equals(provider.protocol())
                && endpointRegistry.isAllowed(provider.code(), provider.baseUrl());
        if (!usable) {
            throw new ModelCredentialInvalidRequestException(
                    "providerId",
                    CredentialReasonCodes.PROVIDER_NOT_ALLOWED,
                    "provider is not allowed for platform credentials");
        }
        return provider;
    }

    /**
     * 按 provider + ID 读取凭据，未命中统一 404（不泄露其他 provider/租户是否存在）。
     *
     * @param providerId 供应商 ID
     * @param credentialId 凭据 ID
     * @return 凭据行
     * @throws NotFoundException 凭据不存在或不属于该 provider
     */
    private ModelCredentialRow requireCredential(UUID providerId, UUID credentialId) {
        return credentialRepository
                .findById(providerId, credentialId)
                .orElseThrow(() -> new NotFoundException("model credential"));
    }

    /**
     * 校验并返回 apiKey；拒绝空值、首尾空白、长度越界与已知占位值。
     *
     * @param apiKey 明文 key
     * @return 原样 key（不做大小写或内容变换）
     * @throws ModelCredentialInvalidRequestException 不合法
     */
    private static String requireApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw invalid("apiKey", CredentialReasonCodes.INVALID_API_KEY, "apiKey is required");
        }
        if (!apiKey.equals(apiKey.strip())) {
            // 首尾空白通常来自复制粘贴；静默 trim 可能把错误 key 写进库，直接拒绝更安全。
            throw invalid(
                    "apiKey",
                    CredentialReasonCodes.INVALID_API_KEY,
                    "apiKey must not contain leading or trailing whitespace");
        }
        if (apiKey.length() < MIN_API_KEY_LENGTH || apiKey.length() > MAX_API_KEY_LENGTH) {
            throw invalid(
                    "apiKey",
                    CredentialReasonCodes.INVALID_API_KEY,
                    "apiKey must be between " + MIN_API_KEY_LENGTH + " and " + MAX_API_KEY_LENGTH + " characters");
        }
        if (PLACEHOLDER_KEYS.contains(apiKey.toLowerCase(Locale.ROOT))) {
            throw invalid(
                    "apiKey", CredentialReasonCodes.INVALID_API_KEY, "apiKey must not be a known placeholder value");
        }
        return apiKey;
    }

    /**
     * 校验并返回标签。
     *
     * @param label 原始标签
     * @return 去首尾空白的标签
     * @throws ModelCredentialInvalidRequestException 为空或超长
     */
    private static String requireLabel(String label) {
        if (label == null || label.isBlank()) {
            throw invalid("label", CredentialReasonCodes.INVALID_LABEL, "label is required");
        }
        String normalized = label.strip();
        if (normalized.length() > MAX_LABEL_LENGTH) {
            throw invalid(
                    "label",
                    CredentialReasonCodes.INVALID_LABEL,
                    "label must be at most " + MAX_LABEL_LENGTH + " characters");
        }
        return normalized;
    }

    /**
     * 校验优先级范围。
     *
     * @param priority 优先级
     * @return 原值
     * @throws ModelCredentialInvalidRequestException 越界
     */
    private static int requirePriority(int priority) {
        if (priority < MIN_PRIORITY || priority > MAX_PRIORITY) {
            throw invalid(
                    "priority",
                    CredentialReasonCodes.INVALID_PRIORITY,
                    "priority must be between " + MIN_PRIORITY + " and " + MAX_PRIORITY);
        }
        return priority;
    }

    /**
     * 用平台 DEK 加密明文；DEK 使用后立即清零。
     *
     * <p>返回的 keyId/masterKeyId 必须与密文前缀成对写入，否则 Worker 解密时 AAD 的 keyId 不一致会失败。
     *
     * @param apiKey 明文 key
     * @return 密文与所引用的 DEK 标识
     */
    private Sealed encrypt(String apiKey) {
        ActiveKey key = activeDataKey();
        try {
            String ciphertext = credentialCipher.encrypt(
                    PlatformTenant.ID, PURPOSE, key.record().keyId(), key.dek(), apiKey);
            return new Sealed(ciphertext, key.record().keyId(), key.record().masterKeyId());
        } finally {
            Arrays.fill(key.dek(), (byte) 0);
        }
    }

    /**
     * 读取或懒生成平台模型凭据 DEK。
     *
     * <p>返回的明文 DEK 由调用方清零；包裹 AAD 固定为 {@code platform|model-credential|keyId|1}，与 Worker
     * 解密侧一致。首次创建时若无活跃 DEK，使用 32 字节安全随机数并写入 {@code tenant_data_key}；并发首次创建
     * 可能生成多个 DEK，但每行凭据都引用自己的 keyId，不影响解密。
     *
     * @return 记录与明文 DEK
     */
    private ActiveKey activeDataKey() {
        Optional<PlatformDataKeyRecord> existing = credentialRepository.findActiveDataKey();
        if (existing.isPresent()) {
            PlatformDataKeyRecord record = existing.get();
            if (!PURPOSE.equals(record.purpose())
                    || !DEK_ALGORITHM.equals(record.algorithm())
                    || record.wrappedDek() == null
                    || record.wrapNonce() == null) {
                throw new IllegalStateException("platform model credential data key is not usable");
            }
            byte[] aad = CredentialEnvelope.aad(PlatformTenant.ID, PURPOSE, record.keyId());
            byte[] dek = masterKeyProvider.unwrap(record.wrapNonce(), record.wrappedDek(), aad);
            return new ActiveKey(record, dek);
        }
        byte[] dek = new byte[DEK_BYTES];
        RANDOM.nextBytes(dek);
        String keyId = "mc-" + UUID.randomUUID().toString().replace("-", "");
        try {
            byte[] aad = CredentialEnvelope.aad(PlatformTenant.ID, PURPOSE, keyId);
            MasterKeyRing.WrappedDek wrapped = masterKeyProvider.wrap(dek, aad);
            PlatformDataKeyRecord record = new PlatformDataKeyRecord(
                    keyId,
                    PURPOSE,
                    wrapped.wrappedDek(),
                    wrapped.nonce(),
                    DEK_ALGORITHM,
                    masterKeyProvider.masterKeyId());
            credentialRepository.insertDataKey(record);
            return new ActiveKey(record, dek);
        } catch (RuntimeException exception) {
            Arrays.fill(dek, (byte) 0);
            throw exception;
        }
    }

    /**
     * 写凭据生命周期审计；事件归属 platform 租户，actor 为当前平台管理员，details 只含 ID/掩码/状态/原因。
     *
     * @param actor 平台管理员
     * @param action 审计动作
     * @param credentialId 目标凭据 ID
     * @param meta 请求元数据
     * @param details 白名单字段
     */
    private void appendAudit(
            RicconPrincipal actor, String action, UUID credentialId, RequestMeta meta, Map<String, Object> details) {
        AuditEvent event = AuditEvent.create(
                clock.instant(),
                PlatformTenant.ID,
                AuditActorType.USER,
                actor.userId().toString(),
                action,
                AuditTargetType.MODEL_CREDENTIAL,
                credentialId.toString(),
                AuditOutcome.SUCCESS,
                meta.traceId(),
                meta.requestId(),
                null,
                details);
        // 审计写入与业务同事务；使用 platform 租户上下文，避免按调用方租户落库。
        TenantContext.runWithTenant(PlatformTenant.TENANT_ID, () -> auditService.append(event));
    }

    /**
     * 映射响应；时间统一转换为 Asia/Shanghai（UTC+08:00）偏移。
     *
     * @param row 凭据行
     * @param changed 状态是否变化；列表/新增/轮换传 {@code null}
     * @return 掩码响应
     */
    private static ModelCredentialResponse toResponse(ModelCredentialRow row, Boolean changed) {
        return new ModelCredentialResponse(
                row.id().toString(),
                row.providerId().toString(),
                row.label(),
                row.maskedHint(),
                row.status(),
                row.priority(),
                row.version(),
                row.consecutiveFailures(),
                offset(row.cooldownUntil()),
                changed,
                offset(row.createdAt()),
                offset(row.updatedAt()),
                offset(row.rotatedAt()),
                offset(row.lastUsedAt()));
    }

    /**
     * 将 UTC 时间转换为团队时区偏移。
     *
     * @param instant UTC 时间
     * @return 带 +08:00 偏移的时间或 {@code null}
     */
    private static OffsetDateTime offset(Instant instant) {
        return instant == null ? null : instant.atOffset(TEAM_OFFSET);
    }

    /**
     * 构造 fingerprint 重复冲突。
     *
     * @return 409 异常
     */
    private static ModelCredentialConflictException duplicateCredential() {
        return new ModelCredentialConflictException(
                CredentialReasonCodes.DUPLICATE_CREDENTIAL, "an identical api key already exists for this provider");
    }

    /**
     * 构造状态冲突。
     *
     * @param detail 脱敏说明
     * @return 409 异常
     */
    private static ModelCredentialConflictException stateConflict(String detail) {
        return new ModelCredentialConflictException(CredentialReasonCodes.CREDENTIAL_STATE_CONFLICT, detail);
    }

    /**
     * 构造字段级 400。
     *
     * @param field 字段
     * @param reason 原因码
     * @param detail 脱敏说明
     * @return 400 异常
     */
    private static ModelCredentialInvalidRequestException invalid(String field, String reason, String detail) {
        return new ModelCredentialInvalidRequestException(field, reason, detail);
    }

    /**
     * 活跃 DEK 与明文；只在一次加密调用内存在。
     *
     * @param record DEK 记录
     * @param dek 明文 DEK，调用方使用后清零
     */
    private record ActiveKey(PlatformDataKeyRecord record, byte[] dek) {}

    /**
     * 加密结果。
     *
     * @param ciphertext 信封密文
     * @param keyId 密文引用的 DEK 标识
     * @param masterKeyId 包裹该 DEK 的主密钥标识
     */
    private record Sealed(String ciphertext, String keyId, String masterKeyId) {}
}
