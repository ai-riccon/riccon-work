package com.riccon.api.admin.model.gateway.service;

import com.riccon.api.admin.model.gateway.config.ModelGatewaySwitchProperties;
import com.riccon.api.admin.model.gateway.domain.GatewaySwitchRow;
import com.riccon.api.admin.model.gateway.domain.ModelGatewayRoutingMode;
import com.riccon.api.admin.model.gateway.domain.ModelGatewaySwitchEnvOverrideException;
import com.riccon.api.admin.model.gateway.domain.ModelGatewaySwitchValidationException;
import com.riccon.api.admin.model.gateway.domain.ModelGatewaySwitchVersionConflictException;
import com.riccon.api.admin.model.gateway.dto.ModelGatewaySwitchResponse;
import com.riccon.api.admin.model.gateway.dto.ModelGatewaySwitchUpdateRequest;
import com.riccon.api.admin.model.gateway.repository.ModelGatewaySwitchRepository;
import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.audit.service.AuditService;
import com.riccon.api.identity.domain.PlatformTenant;
import com.riccon.api.identity.domain.RequestMeta;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.quota.config.QuotaEnforcementProperties;
import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 模型网关开关管理服务（ADR-0021 §3.4/§7.1）。
 *
 * <p>优先级为“环境变量 &gt; 数据库 &gt; 默认”：GET 返回生效值，任意开关被环境变量覆盖时
 * {@code source=ENVIRONMENT} 且 PUT 固定 409；未覆盖时 GET/PUT 都读写 {@code model_gateway_switch}。
 * 三个开关作为一个整组保存：{@code version} 是三行中的最大版本，保存时必须三行都按同一期望版本推进，
 * 任一行影响 0 行即视为并发冲突并让整个事务回滚。
 *
 * <p>每次变更写一条 {@code model.gateway.switch.changed} 审计（每个实际变化的开关一条，details 含
 * switch/value/previousValue/source）；审计与业务更新同事务，审计失败会让保存回滚（fail-closed）。
 */
@Service
public class ModelGatewaySwitchService {

    /** 路由模式开关键。 */
    public static final String ROUTING_MODE_KEY = "routingMode";

    /** 降级开关键。 */
    public static final String FAILOVER_ENABLED_KEY = "failoverEnabled";

    /** 配额执行开关键。 */
    public static final String QUOTA_ENFORCEMENT_ENABLED_KEY = "quotaEnforcementEnabled";

    /** 生效来源：数据库。 */
    private static final String SOURCE_DATABASE = "DATABASE";

    /** 生效来源：环境变量。 */
    private static final String SOURCE_ENVIRONMENT = "ENVIRONMENT";

    /** 对外时间统一使用的团队偏移（Asia/Shanghai，UTC+08:00）。 */
    private static final ZoneOffset TEAM_OFFSET = ZoneOffset.ofHours(8);

    /**
     * 代码默认值，必须与 V11 种子一致：ROUTED/true/false。
     *
     * <p>仅在全局表缺少某一行时兜底读取；正常部署由迁移种子保证三行存在。
     */
    private static final Map<String, String> DEFAULT_VALUES = Map.of(
            ROUTING_MODE_KEY, ModelGatewayRoutingMode.ROUTED.name(),
            FAILOVER_ENABLED_KEY, "true",
            QUOTA_ENFORCEMENT_ENABLED_KEY, "false");

    /** 开关仓储。 */
    private final ModelGatewaySwitchRepository repository;

    /** 路由/降级环境覆盖。 */
    private final ModelGatewaySwitchProperties properties;

    /** 配额执行环境覆盖；复用 B6 已建立的属性绑定。 */
    private final QuotaEnforcementProperties quotaEnforcementProperties;

    /** 审计写入。 */
    private final AuditService auditService;

    /** 可注入时钟，保证审计与修改时间可测试。 */
    private final Clock clock;

    /**
     * 创建开关服务。
     *
     * @param repository 开关仓储
     * @param properties 路由/降级环境覆盖
     * @param quotaEnforcementProperties 配额执行环境覆盖
     * @param auditService 审计写入
     * @param clock 可注入时钟
     */
    public ModelGatewaySwitchService(
            ModelGatewaySwitchRepository repository,
            ModelGatewaySwitchProperties properties,
            QuotaEnforcementProperties quotaEnforcementProperties,
            AuditService auditService,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.quotaEnforcementProperties =
                Objects.requireNonNull(quotaEnforcementProperties, "quotaEnforcementProperties must not be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 读取当前生效开关。
     *
     * <p>每次调用直接读数据库，不做跨请求缓存；环境变量覆盖只影响返回的生效值，不改变数据库版本。
     *
     * @return 生效开关；无环境覆盖时 source=DATABASE
     * @throws IllegalStateException 数据库开关值非法
     */
    @Transactional(readOnly = true)
    public ModelGatewaySwitchResponse read() {
        return snapshot(repository.findAll());
    }

    /**
     * 整组保存网关开关。
     *
     * <p>先做字段校验，再检查环境覆盖（任意覆盖时 409），最后按版本条件更新三行并写审计。所有写操作与审计
     * 在同一事务内完成：任一步失败都会回滚开关表，避免出现“已显示保存但审计缺失”的无痕变更。
     *
     * @param request 整组更新请求
     * @param actor 当前平台管理员
     * @param meta 请求元数据
     * @return 更新后的生效开关
     * @throws ModelGatewaySwitchValidationException 字段级 400 条件
     * @throws ModelGatewaySwitchEnvOverrideException 环境变量覆盖生效
     * @throws ModelGatewaySwitchVersionConflictException 版本冲突
     */
    @Transactional
    public ModelGatewaySwitchResponse update(
            ModelGatewaySwitchUpdateRequest request, RicconPrincipal actor, RequestMeta meta) {
        UpdateValues values = validate(request);

        // 环境变量存在时拒绝任何写入，避免管理端显示与运行时生效值不一致（ADR-0021 §3.4）。
        LinkedHashMap<String, Object> overrides = envOverrides();
        if (!overrides.isEmpty()) {
            throw new ModelGatewaySwitchEnvOverrideException(List.copyOf(overrides.keySet()));
        }

        List<GatewaySwitchRow> rows = repository.findAll();
        Map<String, String> currentValues = valuesByKey(rows);
        int currentVersion = groupVersion(rows);
        if (values.version() != currentVersion) {
            throw new ModelGatewaySwitchVersionConflictException(currentVersion);
        }

        // 先严格解析当前数据库值：非法值属于数据损坏，不应被覆盖成“看似成功”的保存。
        String previousRouting =
                requireRouting(currentValues.getOrDefault(ROUTING_MODE_KEY, defaultFor(ROUTING_MODE_KEY)));
        boolean previousFailover = requireBoolean(
                currentValues.getOrDefault(FAILOVER_ENABLED_KEY, defaultFor(FAILOVER_ENABLED_KEY)),
                FAILOVER_ENABLED_KEY);
        boolean previousQuota = requireBoolean(
                currentValues.getOrDefault(QUOTA_ENFORCEMENT_ENABLED_KEY, defaultFor(QUOTA_ENFORCEMENT_ENABLED_KEY)),
                QUOTA_ENFORCEMENT_ENABLED_KEY);

        Instant now = clock.instant();
        UUID updatedBy = actor.userId();
        updateOne(ROUTING_MODE_KEY, values.version(), values.routingMode(), updatedBy, now);
        updateOne(FAILOVER_ENABLED_KEY, values.version(), Boolean.toString(values.failoverEnabled()), updatedBy, now);
        updateOne(
                QUOTA_ENFORCEMENT_ENABLED_KEY,
                values.version(),
                Boolean.toString(values.quotaEnforcementEnabled()),
                updatedBy,
                now);

        // 只为实际变化的开关写审计；重复保存相同值只推进版本，不制造无意义的变更记录。
        appendAuditIfChanged(ROUTING_MODE_KEY, previousRouting, values.routingMode(), actor, meta, now);
        appendAuditIfChanged(
                FAILOVER_ENABLED_KEY,
                Boolean.toString(previousFailover),
                Boolean.toString(values.failoverEnabled()),
                actor,
                meta,
                now);
        appendAuditIfChanged(
                QUOTA_ENFORCEMENT_ENABLED_KEY,
                Boolean.toString(previousQuota),
                Boolean.toString(values.quotaEnforcementEnabled()),
                actor,
                meta,
                now);

        return snapshot(repository.findAll());
    }

    /**
     * 校验整组更新请求。
     *
     * @param request 更新请求
     * @return 通过校验的值
     * @throws ModelGatewaySwitchValidationException 字段缺失或非法
     */
    private UpdateValues validate(ModelGatewaySwitchUpdateRequest request) {
        if (request == null) {
            throw new ModelGatewaySwitchValidationException("version", "INVALID_VERSION", "request body is required");
        }
        if (request.version() == null || request.version() < 1) {
            throw new ModelGatewaySwitchValidationException("version", "INVALID_VERSION", "version must be >= 1");
        }
        if (request.routingMode() == null || request.routingMode().isBlank()) {
            throw new ModelGatewaySwitchValidationException(
                    "routingMode", "INVALID_ROUTING_MODE", "routingMode must be ROUTED or BASELINE");
        }
        String routingMode;
        try {
            routingMode = ModelGatewayRoutingMode.parse(request.routingMode()).name();
        } catch (IllegalArgumentException exception) {
            throw new ModelGatewaySwitchValidationException(
                    "routingMode", "INVALID_ROUTING_MODE", "routingMode must be ROUTED or BASELINE");
        }
        if (request.failoverEnabled() == null) {
            throw new ModelGatewaySwitchValidationException(
                    "failoverEnabled", "INVALID_FAILOVER_ENABLED", "failoverEnabled is required");
        }
        if (request.quotaEnforcementEnabled() == null) {
            throw new ModelGatewaySwitchValidationException(
                    "quotaEnforcementEnabled",
                    "INVALID_QUOTA_ENFORCEMENT_ENABLED",
                    "quotaEnforcementEnabled is required");
        }
        return new UpdateValues(
                request.version(), routingMode, request.failoverEnabled(), request.quotaEnforcementEnabled());
    }

    /**
     * 组装当前生效快照。
     *
     * <p>数据库缺少某一行时回落到与 V11 种子一致的代码默认值；环境变量只覆盖实际设置的开关。
     *
     * @param rows 数据库开关行
     * @return 生效开关响应
     */
    private ModelGatewaySwitchResponse snapshot(List<GatewaySwitchRow> rows) {
        Map<String, String> databaseValues = valuesByKey(rows);
        String databaseRouting =
                requireRouting(databaseValues.getOrDefault(ROUTING_MODE_KEY, defaultFor(ROUTING_MODE_KEY)));
        boolean databaseFailover = requireBoolean(
                databaseValues.getOrDefault(FAILOVER_ENABLED_KEY, defaultFor(FAILOVER_ENABLED_KEY)),
                FAILOVER_ENABLED_KEY);
        boolean databaseQuota = requireBoolean(
                databaseValues.getOrDefault(QUOTA_ENFORCEMENT_ENABLED_KEY, defaultFor(QUOTA_ENFORCEMENT_ENABLED_KEY)),
                QUOTA_ENFORCEMENT_ENABLED_KEY);

        LinkedHashMap<String, Object> overrides = envOverrides();
        String routingMode =
                overrides.containsKey(ROUTING_MODE_KEY) ? (String) overrides.get(ROUTING_MODE_KEY) : databaseRouting;
        boolean failoverEnabled = overrides.containsKey(FAILOVER_ENABLED_KEY)
                ? (Boolean) overrides.get(FAILOVER_ENABLED_KEY)
                : databaseFailover;
        boolean quotaEnforcementEnabled = overrides.containsKey(QUOTA_ENFORCEMENT_ENABLED_KEY)
                ? (Boolean) overrides.get(QUOTA_ENFORCEMENT_ENABLED_KEY)
                : databaseQuota;

        GatewaySwitchRow latest = rows.stream()
                .max(Comparator.comparing(GatewaySwitchRow::updatedAt))
                .orElse(null);
        Instant updatedAt = latest == null ? clock.instant() : latest.updatedAt();
        UUID updatedBy = latest == null ? null : latest.updatedBy();
        OffsetDateTime teamTime = updatedAt.atOffset(TEAM_OFFSET);
        return new ModelGatewaySwitchResponse(
                routingMode,
                failoverEnabled,
                quotaEnforcementEnabled,
                groupVersion(rows),
                overrides.isEmpty() ? SOURCE_DATABASE : SOURCE_ENVIRONMENT,
                overrides.isEmpty() ? null : Map.copyOf(overrides),
                teamTime,
                updatedBy);
    }

    /**
     * 收集环境变量覆盖。
     *
     * @return 按路由、降级、配额顺序排列的覆盖值；无覆盖时为空
     */
    private LinkedHashMap<String, Object> envOverrides() {
        LinkedHashMap<String, Object> overrides = new LinkedHashMap<>();
        String routingMode = properties.routingModeOverride();
        if (routingMode != null) {
            overrides.put(ROUTING_MODE_KEY, routingMode);
        }
        Boolean failoverEnabled = properties.failoverEnabledOverride();
        if (failoverEnabled != null) {
            overrides.put(FAILOVER_ENABLED_KEY, failoverEnabled);
        }
        Boolean quotaEnforcementEnabled =
                quotaEnforcementProperties.enabledOverride().orElse(null);
        if (quotaEnforcementEnabled != null) {
            overrides.put(QUOTA_ENFORCEMENT_ENABLED_KEY, quotaEnforcementEnabled);
        }
        return overrides;
    }

    /**
     * 按版本条件更新一行；影响 0 行时抛出并发冲突并让事务回滚。
     *
     * @param key 开关键
     * @param expectedVersion 客户端读到的整组版本
     * @param value 新值
     * @param updatedBy 平台管理员账号 ID
     * @param updatedAt 修改时间（UTC）
     * @throws ModelGatewaySwitchVersionConflictException 版本冲突
     */
    private void updateOne(String key, int expectedVersion, String value, UUID updatedBy, Instant updatedAt) {
        int affected = repository.updateValue(key, expectedVersion, value, updatedBy, updatedAt);
        if (affected != 1) {
            // 并发方可能已推进版本；重读当前最大版本用于 409 提示，不做自动重试或覆盖。
            int latestVersion = Math.max(expectedVersion + 1, groupVersion(repository.findAll()));
            throw new ModelGatewaySwitchVersionConflictException(latestVersion);
        }
    }

    /**
     * 值实际变化时写一条开关审计。
     *
     * @param key 开关键
     * @param previousValue 原值
     * @param value 新值
     * @param actor 平台管理员
     * @param meta 请求元数据
     * @param occurredAt 事件时间（UTC）
     */
    private void appendAuditIfChanged(
            String key,
            String previousValue,
            String value,
            RicconPrincipal actor,
            RequestMeta meta,
            Instant occurredAt) {
        if (Objects.equals(previousValue, value)) {
            return;
        }
        AuditEvent event = AuditEvent.create(
                occurredAt,
                PlatformTenant.ID,
                AuditActorType.USER,
                actor.userId().toString(),
                AuditActions.MODEL_GATEWAY_SWITCH_CHANGED,
                AuditTargetType.MODEL,
                key,
                AuditOutcome.SUCCESS,
                meta.traceId(),
                meta.requestId(),
                null,
                Map.of(
                        "switch", key,
                        "value", value,
                        "previousValue", previousValue,
                        "source", SOURCE_DATABASE));
        // 平台级事件固定写 platform 租户，不能沿用调用方的平台管理员账号租户维度。
        TenantContext.runWithTenant(PlatformTenant.TENANT_ID, () -> auditService.append(event));
    }

    /**
     * 把开关行转为按开关键索引的 map。
     *
     * @param rows 开关行
     * @return 开关键到数据库值的 map
     */
    private static Map<String, String> valuesByKey(List<GatewaySwitchRow> rows) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (GatewaySwitchRow row : rows) {
            values.put(row.key(), row.value());
        }
        return values;
    }

    /**
     * 计算整组版本：三行应共享同一版本，取最大值为权威整组版本。
     *
     * @param rows 开关行
     * @return 整组版本；无行时为 1
     */
    private static int groupVersion(List<GatewaySwitchRow> rows) {
        return rows.stream().mapToInt(GatewaySwitchRow::version).max().orElse(1);
    }

    /**
     * 返回开关默认值。
     *
     * @param key 开关键
     * @return 默认值
     */
    private static String defaultFor(String key) {
        return DEFAULT_VALUES.getOrDefault(key, "");
    }

    /**
     * 严格解析路由模式；数据库非法值属于配置损坏。
     *
     * @param raw 数据库值
     * @return 规范化后的枚举名
     * @throws IllegalStateException 值非法
     */
    private static String requireRouting(String raw) {
        try {
            return ModelGatewayRoutingMode.parse(raw).name();
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("model gateway switch routingMode is invalid", exception);
        }
    }

    /**
     * 严格解析布尔开关；数据库非法值属于配置损坏。
     *
     * @param raw 数据库值
     * @param key 开关键，用于错误信息
     * @return 布尔值
     * @throws IllegalStateException 值非法
     */
    private static boolean requireBoolean(String raw, String key) {
        if ("true".equalsIgnoreCase(raw)) {
            return true;
        }
        if ("false".equalsIgnoreCase(raw)) {
            return false;
        }
        throw new IllegalStateException("model gateway switch " + key + " is invalid");
    }

    /**
     * 校验后的整组更新值。
     *
     * @param version 整组版本
     * @param routingMode 规范化的路由模式
     * @param failoverEnabled 降级开关
     * @param quotaEnforcementEnabled 配额执行开关
     */
    private record UpdateValues(
            int version, String routingMode, boolean failoverEnabled, boolean quotaEnforcementEnabled) {}
}
