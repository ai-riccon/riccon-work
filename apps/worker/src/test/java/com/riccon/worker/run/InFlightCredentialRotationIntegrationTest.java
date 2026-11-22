package com.riccon.worker.run;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.riccon.platform.common.crypto.CredentialEnvelope;
import com.riccon.platform.common.crypto.MasterKeyRing;
import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import com.riccon.platform.domain.model.ModelPurpose;
import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.audit.domain.AuditEvent;
import com.riccon.worker.audit.repository.AuditRepository;
import com.riccon.worker.common.UuidV7;
import com.riccon.worker.crypto.service.EnvelopeDecryptor;
import com.riccon.worker.crypto.service.MasterKeyProvider;
import com.riccon.worker.model.domain.ModelRoute;
import com.riccon.worker.model.domain.RoutedModel;
import com.riccon.worker.model.service.FailoverChatModel;
import com.riccon.worker.model.service.ModelInvocationService;
import com.riccon.worker.model.service.ModelInvocationService.InvocationHandle;
import com.riccon.worker.model.service.ModelRouter;
import com.riccon.worker.model.support.ModelFailoverFixture;
import com.riccon.worker.persistence.UuidBinary;
import com.riccon.worker.support.TestCredentials;
import com.riccon.worker.support.TestModelStub;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * M04 AC-07 / F-006：真实并发流式请求期间轮换平台 key（TASK-0157）。
 *
 * <p>背景：IT-MODEL-106 只证明轮换后旧密文仍可解密；E2E-M04-04 只覆盖管理页面影响说明。
 * 本用例补齐“真实在途流”缺口：用生产 {@link ModelRouter}/{@link FailoverChatModel} 发起两次
 * 真实 HTTP SSE 流（本地模型桩按 OpenAI 兼容协议逐帧返回），共享真实 MySQL（Testcontainers）；
 * 第一条流在首段内容输出后被上游闸门挂起时，用与 API 轮换相同的单事务语义插入新 key、条件停用旧 key
 * 并写审计，然后断言：
 *
 * <ul>
 *   <li>旧流不中断、已输出内容不重放：轮换不重建旧连接，旧流最终成功且每段内容恰好一次；
 *   <li>新请求使用新 key：旧流仍挂起时发起的第二条流在 Authorization、attempt.credential_id
 *       与轮换后解析的 key 池三处都指向新 key；
 *   <li>审计与掩码：轮换审计归属 platform、target 为新 key、details 只含 ID/掩码/状态；
 *       响应、模型请求体、数据库文本列与 Worker 日志均不含旧/新 key 明文。
 * </ul>
 *
 * <p>为什么轮换在本测试内执行：`apps/worker` 不得编译依赖 `apps/api`（ArchUnit/Enforcer），
 * Worker 测试无法调用 HTTP 轮换端点。这里用 {@link CredentialEnvelope}（API 与 Worker 共用的
 * 生产信封实现）、{@link MasterKeyProvider#wrap}（生产 DEK 包裹）、{@link MasterKeyRing#fingerprint}
 * （生产 HMAC）以及 API {@code ModelCredentialJdbc} 的 INSERT/条件停用 SQL 语义执行同一事务；
 * 轮换端点自身的 HTTP 掩码/审计/并发 409 已由 IT-MODEL-106、SEC-M04-006/008、
 * {@code M04VerifierGatewaySecurityTest.ac06_ac07} 覆盖，本用例只补齐并发交织。
 *
 * <p>可复现：每个场景重复 3 次（{@link RepeatedTest}），每次使用唯一租户/provider/model，
 * 只依赖 latch/闸门而不依赖 {@code Thread.sleep} 或真实时间。
 */
@Tag("security")
class InFlightCredentialRotationIntegrationTest extends WorkerIntegrationTestBase {

    /** 平台租户 ID；provider_credential 与审计的平台事件都归属它。 */
    private static final String PLATFORM = "platform";

    /** 平台租户标识。 */
    private static final TenantId PLATFORM_TENANT = TenantId.of(PLATFORM);

    /** 轮换审计的操作者 ID；不指向任何真实账号。 */
    private static final String ROTATION_ACTOR = "m24-key-rotation-test";

    /** 轮换审计追踪 ID。 */
    private static final String ROTATION_TRACE = "trace-m24-key-rotation";

    /** 模型调用追踪 ID。 */
    private static final String STREAM_TRACE = "trace-m24-in-flight";

    /** 模型桩默认文本的第二段；与 {@link TestModelStub} 的默认响应对应。 */
    private static final List<String> EXPECTED_TEXT = List.of("根据资料，", "这是回答 [1]。");

    /** 真实模型桩（127.0.0.1 随机端口，OpenAI 兼容 SSE）。 */
    private TestModelStub stub;

    /** 平台 key 池/路由夹具。 */
    private ModelFailoverFixture fixture;

    /** 模型网关。 */
    @Autowired
    private ModelRouter modelRouter;

    /** 调用记录服务。 */
    @Autowired
    private ModelInvocationService invocationService;

    /** 审计仓储（生产 append-only 入口）。 */
    @Autowired
    private AuditRepository auditRepository;

    /** 事务管理器；轮换必须在单事务内完成。 */
    @Autowired
    private PlatformTransactionManager transactionManager;

    /** Worker 主密钥提供者，用于按生产方式包裹新 DEK。 */
    @Autowired
    private MasterKeyProvider masterKeyProvider;

    /** 配置环境；测试主密钥/pepper 由 {@link WorkerIntegrationTestBase} 动态注入。 */
    @Autowired
    private Environment environment;

    /** 启动模型桩并恢复 M04 全局目录/路由/开关基线。 */
    @BeforeEach
    void setUp() {
        resetModelGatewayState();
        stub = new TestModelStub();
        fixture = new ModelFailoverFixture(jdbcClient);
    }

    /** 释放闸门并关闭模型桩，避免失败用例挂起桩线程。 */
    @AfterEach
    void tearDown() {
        stub.releaseContent();
        stub.close();
    }

    /**
     * 轮换发生在旧流首字之后、完成之前：旧流继续完成且不重放，新请求在旧流仍挂起时使用新 key。
     */
    @RepeatedTest(3)
    @DisplayName("AC-07/F-006：真实并发在途轮换，旧流不中断、新请求新 key、无明文")
    void rotateDuringInFlightStream_oldStreamCompletesAndNewRequestUsesNewKey() throws Exception {
        String tenantValue = uniqueTenant("m24-key");
        data.createTenant(tenantValue);
        TenantId tenant = TenantId.of(tenantValue);
        stub.holdAfterFirstContent();

        UUID modelId = fixture.createModel(stub.url(), "m24-key-model");
        UUID providerId = fixture.providerOf(modelId);
        String oldApiKey = "sk-m24-old-" + UUID.randomUUID();
        String newApiKey = "sk-m24-new-" + UUID.randomUUID();
        UUID oldCredentialId =
                fixture.addPlatformKey(providerId, oldApiKey, 10, CredentialEnvelope.maskedHint(oldApiKey));
        fixture.route(ModelPurpose.CHAT, modelId);
        String oldCiphertextBefore = credentialRow(oldCredentialId).ciphertext();

        LogCapture logs = attachWorkerLogs();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // 第一条真实流：消费到首段内容后，模型桩停在闸门上，连接保持打开。
            CountDownLatch firstStreamText = new CountDownLatch(1);
            AtomicReference<UUID> firstInvocation = new AtomicReference<>();
            Future<StreamResult> firstStream =
                    executor.submit(() -> runStream(tenant, firstStreamText, firstInvocation));
            assertThat(firstStreamText.await(20, TimeUnit.SECONDS))
                    .as("旧流首段内容未在超时内到达")
                    .isTrue();
            assertThat(stub.requestCount()).isEqualTo(1);
            assertThat(stub.lastAuthorization()).as("旧流首个请求必须使用轮换前的 key").isEqualTo("Bearer " + oldApiKey);
            assertThat(firstInvocation.get()).isNotNull();

            // 轮换：真实事务插入新 key、条件停用旧 key、写审计；旧流此刻仍被挂起。
            Rotation rotation = rotate(providerId, oldCredentialId, newApiKey);
            assertThat(firstStream.isDone()).as("轮换不应中断在途旧流").isFalse();
            assertThat(stub.requestCount()).as("轮换不应重建旧流的上游连接").isEqualTo(1);
            assertThat(credentialRow(oldCredentialId).status()).isEqualTo("DISABLED");

            // 旧流仍挂起时发起第二条真实请求：必须从池中选到新 key。
            CountDownLatch secondStreamText = new CountDownLatch(1);
            AtomicReference<UUID> secondInvocation = new AtomicReference<>();
            Future<StreamResult> secondStream =
                    executor.submit(() -> runStream(tenant, secondStreamText, secondInvocation));
            assertThat(secondStreamText.await(20, TimeUnit.SECONDS))
                    .as("轮换后的新请求首段内容未在超时内到达")
                    .isTrue();
            assertThat(stub.requestCount()).isEqualTo(2);
            assertThat(stub.lastAuthorization()).as("轮换后的新请求必须使用新 key").isEqualTo("Bearer " + newApiKey);
            assertThat(firstStream.isDone()).isFalse();
            assertThat(secondStream.isDone()).isFalse();

            // 在途 attempt 的 credential_id 分别锁定旧/新 key（轮换不清空或改写 attempt）。
            assertThat(attemptCredentialIds(tenant, firstInvocation.get())).containsExactly(oldCredentialId.toString());
            assertThat(attemptStatuses(tenant, firstInvocation.get())).containsExactly("STARTED");
            assertThat(attemptCredentialIds(tenant, secondInvocation.get()))
                    .containsExactly(rotation.newCredentialId().toString());
            assertThat(attemptStatuses(tenant, secondInvocation.get())).containsExactly("STARTED");

            // 新请求解析到的 key 池只剩新 key；旧 key 未重新启用。
            ModelRoute routeAfterRotation = TenantContext.callWithTenant(
                    tenant, () -> modelRouter.resolve(tenant, ModelPurpose.CHAT, STREAM_TRACE, null));
            assertThat(routeAfterRotation.models().get(0).credentials())
                    .extracting(credential -> credential.id())
                    .containsExactly(rotation.newCredentialId());

            // 放开闸门：两条流都应正常完成，旧流不出现重试/重放。
            stub.releaseContent();
            StreamResult first = firstStream.get(20, TimeUnit.SECONDS);
            StreamResult second = secondStream.get(20, TimeUnit.SECONDS);
            assertThat(first.error()).isNull();
            assertThat(second.error()).isNull();
            assertThat(texts(first)).containsExactlyElementsOf(EXPECTED_TEXT);
            assertThat(texts(second)).containsExactlyElementsOf(EXPECTED_TEXT);
            assertThat(stub.requestCount()).as("整个场景只应有两个上游请求").isEqualTo(2);
            assertThat(attemptStatuses(tenant, firstInvocation.get())).containsExactly("SUCCEEDED");
            assertThat(attemptStatuses(tenant, secondInvocation.get())).containsExactly("SUCCEEDED");
            assertThat(attemptCredentialIds(tenant, firstInvocation.get())).containsExactly(oldCredentialId.toString());
            assertThat(attemptCredentialIds(tenant, secondInvocation.get()))
                    .containsExactly(rotation.newCredentialId().toString());

            // 旧行密文不得因轮换改写；旧掩码保持；新行 ACTIVE 且有生产格式的 fingerprint。
            CredentialRow oldRow = credentialRow(oldCredentialId);
            assertThat(oldRow.ciphertext()).isEqualTo(oldCiphertextBefore);
            assertThat(oldRow.maskedHint()).isEqualTo(CredentialEnvelope.maskedHint(oldApiKey));
            assertThat(oldRow.version()).isEqualTo(2);
            assertThat(oldRow.disabledAt()).isNotNull();
            assertThat(oldRow.rotatedAt()).isNotNull();
            CredentialRow newRow = credentialRow(rotation.newCredentialId());
            assertThat(newRow.status()).isEqualTo("ACTIVE");
            assertThat(newRow.version()).isEqualTo(1);
            assertThat(newRow.priority()).isEqualTo(10);
            assertThat(newRow.maskedHint()).isEqualTo(CredentialEnvelope.maskedHint(newApiKey));
            assertThat(newRow.fingerprint()).isEqualTo(rotation.newFingerprint());
            assertThat(newRow.ciphertext()).startsWith("enc:v1:").doesNotContain(newApiKey);
            assertThat(jdbcClient
                            .sql("""
                                    SELECT COUNT(*) FROM provider_credential
                                     WHERE tenant_id = 'platform' AND provider_id = :providerId
                                       AND credential_type = 'API_KEY' AND status = 'ACTIVE'
                                    """)
                            .param("providerId", UuidBinary.toBytes(providerId))
                            .query(Integer.class)
                            .single())
                    .as("轮换后提供方只能有一把 ACTIVE key")
                    .isEqualTo(1);

            // Worker 生产审计（model.invocation.started）每次流一条，details 只含 modelId/purpose。
            List<String> workerAudits = jdbcClient
                    .sql("""
                            SELECT CAST(details AS CHAR) AS details FROM audit_event
                             WHERE tenant_id = :tenantId AND action = 'model.invocation.started'
                            """)
                    .param("tenantId", tenantValue)
                    .query((rs, rowNum) -> rs.getString("details"))
                    .list();
            assertThat(workerAudits).hasSize(2);
            assertThat(String.join("\n", workerAudits))
                    .contains(modelId.toString())
                    .contains("chat")
                    .doesNotContain(oldApiKey)
                    .doesNotContain(newApiKey);
            assertThat(jdbcClient
                            .sql("""
                                    SELECT COUNT(*) FROM audit_event
                                     WHERE tenant_id = :tenantId AND action = 'model.invocation.failed'
                                    """)
                            .param("tenantId", tenantValue)
                            .query(Integer.class)
                            .single())
                    .isZero();

            // 轮换审计：platform/USER/MODEL_CREDENTIAL，details 只含 ID、掩码与状态。
            AuditRow rotatedAudit = rotationAudit(rotation.newCredentialId());
            assertThat(rotatedAudit.tenantId()).isEqualTo(PLATFORM);
            assertThat(rotatedAudit.actorType()).isEqualTo("USER");
            assertThat(rotatedAudit.targetType()).isEqualTo("MODEL_CREDENTIAL");
            assertThat(rotatedAudit.outcome()).isEqualTo("SUCCESS");
            JsonNode details = objectMapper.readTree(rotatedAudit.details());
            assertThat(details.path("providerId").asText()).isEqualTo(providerId.toString());
            assertThat(details.path("credentialId").asText())
                    .isEqualTo(rotation.newCredentialId().toString());
            assertThat(details.path("previousCredentialId").asText()).isEqualTo(oldCredentialId.toString());
            assertThat(details.path("maskedHint").asText()).isEqualTo(CredentialEnvelope.maskedHint(newApiKey));
            assertThat(details.path("status").asText()).isEqualTo("ACTIVE");
            assertThat(rotatedAudit.details()).doesNotContain(oldApiKey).doesNotContain(newApiKey);

            // 无明文：响应文本、模型请求体、数据库文本列与 Worker 日志。
            assertThat(responseText(first)).doesNotContain(oldApiKey).doesNotContain(newApiKey);
            assertThat(responseText(second)).doesNotContain(oldApiKey).doesNotContain(newApiKey);
            assertThat(stub.lastRequest()).isNotNull().doesNotContain(oldApiKey).doesNotContain(newApiKey);
            assertNoPlaintextInDatabase(
                    providerId, oldCredentialId, rotation.newCredentialId(), modelId, oldApiKey, newApiKey);
            assertThat(String.join("\n", logs.messages()))
                    .as("Worker 日志不得出现 key 明文")
                    .doesNotContain(oldApiKey)
                    .doesNotContain(newApiKey);
        } finally {
            stub.releaseContent();
            executor.shutdownNow();
            logs.detach();
        }
    }

    /**
     * 发起一次真实流式调用，并在首个文本响应到达时放开 latch。
     *
     * @param tenant 请求租户
     * @param firstTextLatch 首个文本响应到达后 count down
     * @param invocationId 用于回填本次调用 ID，便于在途断言 attempt
     * @return 流式结果
     */
    private StreamResult runStream(TenantId tenant, CountDownLatch firstTextLatch, AtomicReference<UUID> invocationId) {
        ModelRoute route = TenantContext.callWithTenant(
                tenant, () -> modelRouter.resolve(tenant, ModelPurpose.CHAT, STREAM_TRACE, null));
        RoutedModel first = route.firstModel().orElseThrow();
        InvocationHandle handle = TenantContext.callWithTenant(
                tenant,
                () -> invocationService.start(
                        tenant,
                        null,
                        null,
                        null,
                        null,
                        first.target().model().id(),
                        first.target().model().modelKey(),
                        ModelPurpose.CHAT,
                        STREAM_TRACE,
                        null));
        invocationId.set(handle.id());
        FailoverChatModel model = modelRouter.createModel(route, tenant, handle, STREAM_TRACE, null, ModelPurpose.CHAT);
        List<ChatResponse> responses = new ArrayList<>();
        AtomicBoolean firstTextSeen = new AtomicBoolean();
        RuntimeException error = null;
        try {
            model.stream(List.of(new UserMessage("你好")), List.of(), null)
                    .doOnNext(response -> {
                        responses.add(response);
                        if (hasText(response) && firstTextSeen.compareAndSet(false, true)) {
                            firstTextLatch.countDown();
                        }
                    })
                    .blockLast(Duration.ofSeconds(20));
        } catch (RuntimeException ex) {
            error = ex;
        }
        return new StreamResult(List.copyOf(responses), error);
    }

    /**
     * 以单事务执行一次平台 key 轮换。
     *
     * <p>SQL 与 API {@code ModelCredentialJdbc} 的 INSERT_CREDENTIAL / DISABLE_FOR_ROTATION 一致：
     * 先插入 ACTIVE 新 key（含 fingerprint/masked_hint/继承 priority），再按旧行 version 条件停用；
     * 同一事务内写 {@code model.credential.rotated}（platform/USER/MODEL_CREDENTIAL）。
     *
     * @param providerId 供应商 ID
     * @param oldCredentialId 旧 key ID
     * @param newApiKey 新 key 明文（仅测试内存）
     * @return 轮换结果快照
     */
    private Rotation rotate(UUID providerId, UUID oldCredentialId, String newApiKey) {
        return TenantContext.callWithTenant(PLATFORM_TENANT, () -> {
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            return template.execute(status -> {
                CredentialRow previous = credentialRow(oldCredentialId);
                UUID newCredentialId = UuidBinary.newV7();
                UUID actorId = UuidBinary.newV7();
                String keyId = "dek-m24-" + newCredentialId;
                byte[] dek = TestCredentials.newDek();
                MasterKeyProvider.WrappedDek wrapped = masterKeyProvider.wrap(
                        dek, EnvelopeDecryptor.aad(PLATFORM, CredentialEnvelope.PURPOSE_MODEL_CREDENTIAL, keyId));
                String ciphertext = CredentialEnvelope.encrypt(
                        PLATFORM,
                        CredentialEnvelope.PURPOSE_MODEL_CREDENTIAL,
                        keyId,
                        dek,
                        newApiKey.getBytes(StandardCharsets.UTF_8));
                String fingerprint = fingerprintRing().fingerprint(providerId.toString(), newApiKey);
                String maskedHint = CredentialEnvelope.maskedHint(newApiKey);
                LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

                jdbcClient
                        .sql("""
                                INSERT INTO tenant_data_key
                                    (tenant_id, key_id, purpose, wrapped_dek, wrap_nonce, algorithm, master_key_id)
                                VALUES ('platform', :keyId, :purpose, :wrappedDek, :wrapNonce, 'AES-256-GCM',
                                        :masterKeyId)
                                """)
                        .param("keyId", keyId)
                        .param("purpose", CredentialEnvelope.PURPOSE_MODEL_CREDENTIAL)
                        .param("wrappedDek", wrapped.wrappedDek())
                        .param("wrapNonce", wrapped.nonce())
                        .param("masterKeyId", masterKeyProvider.masterKeyId())
                        .update();
                jdbcClient
                        .sql("""
                                INSERT INTO provider_credential (
                                    tenant_id, id, provider_id, credential_type, ciphertext, key_id, master_key_id,
                                    version, status, label, fingerprint, masked_hint, priority,
                                    consecutive_failures, rotated_at, created_at, updated_at
                                ) VALUES (
                                    'platform', :id, :providerId, 'API_KEY', :ciphertext, :keyId, :masterKeyId,
                                    1, 'ACTIVE', :label, :fingerprint, :maskedHint, :priority,
                                    0, :rotatedAt, :createdAt, :updatedAt
                                )
                                """)
                        .param("id", UuidBinary.toBytes(newCredentialId))
                        .param("providerId", UuidBinary.toBytes(providerId))
                        .param("ciphertext", ciphertext)
                        .param("keyId", keyId)
                        .param("masterKeyId", masterKeyProvider.masterKeyId())
                        .param("label", previous.label())
                        .param("fingerprint", fingerprint)
                        .param("maskedHint", maskedHint)
                        .param("priority", previous.priority())
                        .param("rotatedAt", now)
                        .param("createdAt", now)
                        .param("updatedAt", now)
                        .update();
                int updated = jdbcClient
                        .sql("""
                                UPDATE provider_credential
                                   SET status = 'DISABLED',
                                       disabled_at = :now,
                                       disabled_by = :actorId,
                                       rotated_at = :now,
                                       version = version + 1,
                                       updated_at = :now
                                 WHERE tenant_id = 'platform'
                                   AND provider_id = :providerId
                                   AND credential_type = 'API_KEY'
                                   AND id = :id
                                   AND version = :version
                                   AND status IN ('ACTIVE', 'INVALID')
                                """)
                        .param("now", now)
                        .param("actorId", UuidBinary.toBytes(actorId))
                        .param("providerId", UuidBinary.toBytes(providerId))
                        .param("id", UuidBinary.toBytes(oldCredentialId))
                        .param("version", previous.version())
                        .update();
                if (updated != 1) {
                    // 与 API 一致：版本条件失败即整个事务回滚，绝不出现新旧 key 同时 ACTIVE。
                    throw new IllegalStateException("rotation state conflict");
                }
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("providerId", providerId.toString());
                details.put("credentialId", newCredentialId.toString());
                details.put("previousCredentialId", oldCredentialId.toString());
                details.put("maskedHint", maskedHint);
                details.put("status", "ACTIVE");
                auditRepository.append(new AuditEvent(
                        UuidV7.generate(),
                        Instant.now(),
                        PLATFORM,
                        AuditActorType.USER,
                        ROTATION_ACTOR,
                        AuditActions.MODEL_CREDENTIAL_ROTATED,
                        AuditTargetType.MODEL_CREDENTIAL,
                        newCredentialId.toString(),
                        AuditOutcome.SUCCESS,
                        ROTATION_TRACE,
                        null,
                        details,
                        AuditEvent.CURRENT_SCHEMA_VERSION));
                return new Rotation(newCredentialId, fingerprint);
            });
        });
    }

    /**
     * 构造生产 HMAC fingerprint 环；pepper 来自测试动态配置。
     *
     * @return 主密钥环
     */
    private MasterKeyRing fingerprintRing() {
        return MasterKeyRing.load(
                environment.getProperty("riccon.worker.crypto.master-key"),
                null,
                environment.getProperty("riccon.worker.crypto.master-key-id"),
                environment.getProperty("riccon.worker.crypto.fingerprint-pepper"),
                false);
    }

    /**
     * 读取平台凭据行。
     *
     * @param credentialId 凭据 ID
     * @return 行快照
     */
    private CredentialRow credentialRow(UUID credentialId) {
        return jdbcClient
                .sql("""
                        SELECT version, status, ciphertext, fingerprint, masked_hint, priority, label,
                               disabled_at, rotated_at
                          FROM provider_credential
                         WHERE tenant_id = 'platform' AND id = :id
                        """)
                .param("id", UuidBinary.toBytes(credentialId))
                .query((rs, rowNum) -> new CredentialRow(
                        rs.getInt("version"),
                        rs.getString("status"),
                        rs.getString("ciphertext"),
                        rs.getString("fingerprint"),
                        rs.getString("masked_hint"),
                        rs.getInt("priority"),
                        rs.getString("label"),
                        rs.getObject("disabled_at", LocalDateTime.class),
                        rs.getObject("rotated_at", LocalDateTime.class)))
                .single();
    }

    /**
     * 读取轮换审计行。
     *
     * @param targetId 新凭据 ID
     * @return 审计行
     */
    private AuditRow rotationAudit(UUID targetId) {
        return jdbcClient
                .sql("""
                        SELECT tenant_id, actor_type, target_type, outcome, details
                          FROM audit_event
                         WHERE action = :action AND target_id = :targetId
                        """)
                .param("action", AuditActions.MODEL_CREDENTIAL_ROTATED)
                .param("targetId", targetId.toString())
                .query((rs, rowNum) -> new AuditRow(
                        rs.getString("tenant_id"),
                        rs.getString("actor_type"),
                        rs.getString("target_type"),
                        rs.getString("outcome"),
                        rs.getString("details")))
                .single();
    }

    /**
     * 读取调用 attempt 的 credential_id 序列。
     *
     * @param tenant 请求租户
     * @param invocationId 调用 ID
     * @return 按 attempt_no 排序的凭据 ID
     */
    private List<String> attemptCredentialIds(TenantId tenant, UUID invocationId) {
        return jdbcClient
                .sql("""
                        SELECT credential_id FROM model_invocation_attempt
                         WHERE tenant_id = :tenantId AND invocation_id = :invocationId
                         ORDER BY attempt_no
                        """)
                .param("tenantId", tenant.value())
                .param("invocationId", UuidBinary.toBytes(invocationId))
                .query((rs, rowNum) -> {
                    byte[] raw = rs.getBytes("credential_id");
                    return raw == null ? null : UuidBinary.fromBytes(raw).toString();
                })
                .list();
    }

    /**
     * 读取调用 attempt 状态序列。
     *
     * @param tenant 请求租户
     * @param invocationId 调用 ID
     * @return 按 attempt_no 排序的状态
     */
    private List<String> attemptStatuses(TenantId tenant, UUID invocationId) {
        return jdbcClient
                .sql("""
                        SELECT status FROM model_invocation_attempt
                         WHERE tenant_id = :tenantId AND invocation_id = :invocationId
                         ORDER BY attempt_no
                        """)
                .param("tenantId", tenant.value())
                .param("invocationId", UuidBinary.toBytes(invocationId))
                .query((rs, rowNum) -> rs.getString("status"))
                .list();
    }

    /**
     * 断言数据库文本列不含明文 key。
     *
     * <p>覆盖本测试创建的凭据行（密文/指纹/掩码/标签）、关联审计 details 与平台 DEK 行的十六进制表示；
     * 不使用全表扫描，避免把其他测试的合法数据当作本测试结论。
     *
     * @param providerId 供应商 ID
     * @param oldCredentialId 旧凭据 ID
     * @param newCredentialId 新凭据 ID
     * @param modelId 模型 ID
     * @param secrets 不得出现的明文
     */
    private void assertNoPlaintextInDatabase(
            UUID providerId, UUID oldCredentialId, UUID newCredentialId, UUID modelId, String... secrets) {
        String credentialText = String.join(
                "\n",
                jdbcClient
                        .sql("""
                                SELECT ciphertext, fingerprint, masked_hint, label
                                  FROM provider_credential
                                 WHERE tenant_id = 'platform' AND provider_id = :providerId
                                """)
                        .param("providerId", UuidBinary.toBytes(providerId))
                        .query((rs, rowNum) -> String.join(
                                "|",
                                rs.getString("ciphertext"),
                                String.valueOf(rs.getString("fingerprint")),
                                rs.getString("masked_hint"),
                                rs.getString("label")))
                        .list());
        List<String> targets = List.of(oldCredentialId.toString(), newCredentialId.toString(), modelId.toString());
        String auditText = String.join(
                "\n",
                jdbcClient
                        .sql("""
                                SELECT CAST(details AS CHAR) AS details
                                  FROM audit_event
                                 WHERE target_id IN (:targets)
                                """)
                        .param("targets", targets)
                        .query((rs, rowNum) -> String.valueOf(rs.getString("details")))
                        .list());
        String dataKeyText = String.join(
                "\n",
                jdbcClient
                        .sql("""
                                SELECT HEX(wrapped_dek) AS wrapped_dek, HEX(wrap_nonce) AS wrap_nonce
                                  FROM tenant_data_key
                                 WHERE tenant_id = 'platform'
                                   AND key_id IN (:keyIds)
                                """)
                        .param("keyIds", List.of("dek-" + oldCredentialId, "dek-m24-" + newCredentialId))
                        .query((rs, rowNum) -> rs.getString("wrapped_dek") + "|" + rs.getString("wrap_nonce"))
                        .list());
        for (String secret : secrets) {
            assertThat(credentialText).doesNotContain(secret);
            assertThat(auditText).doesNotContain(secret);
            assertThat(dataKeyText).doesNotContain(secret);
        }
    }

    /**
     * 判断响应是否携带非空文本块。
     *
     * @param response 响应
     * @return 有文本返回 true
     */
    private static boolean hasText(ChatResponse response) {
        return response.getContent() != null
                && response.getContent().stream()
                        .anyMatch(block -> block instanceof TextBlock text
                                && text.getText() != null
                                && !text.getText().isBlank());
    }

    /**
     * 提取响应中的文本块。
     *
     * @param result 流式结果
     * @return 文本列表
     */
    private static List<String> texts(StreamResult result) {
        List<String> texts = new ArrayList<>();
        for (ChatResponse response : result.responses()) {
            if (response.getContent() == null) {
                continue;
            }
            for (ContentBlock block : response.getContent()) {
                if (block instanceof TextBlock text
                        && text.getText() != null
                        && !text.getText().isBlank()) {
                    texts.add(text.getText());
                }
            }
        }
        return texts;
    }

    /**
     * 把响应文本拼为单个字符串，用于明文扫描。
     *
     * @param result 流式结果
     * @return 拼接文本
     */
    private static String responseText(StreamResult result) {
        return String.join("", texts(result));
    }

    /**
     * 挂载 Worker 命名空间的日志捕获器。
     *
     * @return 捕获器
     */
    private static LogCapture attachWorkerLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger("com.riccon.worker");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return new LogCapture(logger, appender);
    }

    /**
     * 凭据行快照。
     *
     * @param version 版本
     * @param status 状态
     * @param ciphertext 密文
     * @param fingerprint HMAC 指纹
     * @param maskedHint 掩码
     * @param priority 优先级
     * @param label 标签
     * @param disabledAt 停用时间
     * @param rotatedAt 轮换时间
     */
    private record CredentialRow(
            int version,
            String status,
            String ciphertext,
            String fingerprint,
            String maskedHint,
            int priority,
            String label,
            LocalDateTime disabledAt,
            LocalDateTime rotatedAt) {}

    /**
     * 轮换结果快照。
     *
     * @param newCredentialId 新凭据 ID
     * @param newFingerprint 新指纹
     */
    private record Rotation(UUID newCredentialId, String newFingerprint) {}

    /**
     * 审计行快照。
     *
     * @param tenantId 租户
     * @param actorType 操作者类型
     * @param targetType 目标类型
     * @param outcome 结果
     * @param details details JSON
     */
    private record AuditRow(String tenantId, String actorType, String targetType, String outcome, String details) {}

    /**
     * 一次流式运行结果。
     *
     * @param responses 响应序列
     * @param error 运行错误；成功为 null
     */
    private record StreamResult(List<ChatResponse> responses, RuntimeException error) {}

    /**
     * 日志捕获器。
     *
     * @param logger 被捕获的 logger
     * @param appender 捕获器
     */
    private record LogCapture(Logger logger, ListAppender<ILoggingEvent> appender) {

        /**
         * 返回捕获到的格式化日志。
         *
         * @return 日志文本
         */
        private List<String> messages() {
            return appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
        }

        /** 解除挂载。 */
        private void detach() {
            logger.detachAppender(appender);
        }
    }
}
