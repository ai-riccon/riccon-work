package com.riccon.worker.workflow.node.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.model.ModelPurpose;
import com.riccon.worker.audit.service.AuditService;
import com.riccon.worker.crypto.domain.SecretValue;
import com.riccon.worker.model.config.ModelRoutingProperties;
import com.riccon.worker.model.domain.ModelCredential;
import com.riccon.worker.model.domain.ModelDescriptor;
import com.riccon.worker.model.domain.ModelException;
import com.riccon.worker.model.domain.ModelFailureKind;
import com.riccon.worker.model.domain.ModelRoute;
import com.riccon.worker.model.domain.ModelRoutingMode;
import com.riccon.worker.model.domain.ModelTarget;
import com.riccon.worker.model.domain.ProviderDescriptor;
import com.riccon.worker.model.domain.ResolvedModel;
import com.riccon.worker.model.domain.RoutedModel;
import com.riccon.worker.model.repository.ModelAttemptRepository;
import com.riccon.worker.model.service.CredentialHealthService;
import com.riccon.worker.model.service.FailoverChatModel;
import com.riccon.worker.model.service.ModelInvocationService;
import com.riccon.worker.model.service.ModelInvocationService.InvocationHandle;
import com.riccon.worker.model.service.ModelMetrics;
import com.riccon.worker.model.service.ModelRegistry;
import com.riccon.worker.model.service.ModelRouter;
import com.riccon.worker.model.service.OpenAiCompatibleChatModel;
import com.riccon.worker.workflow.engine.repository.WorkflowEngineRepository;
import com.riccon.worker.workflow.node.WorkflowNodeTestFixtures;
import com.riccon.worker.workflow.node.domain.WorkflowNodeExecution;
import com.riccon.worker.workflow.node.domain.WorkflowNodeResult;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

/**
 * MODEL 节点跨模型降级（ADR-0024 §4.10，M04 复用）。
 *
 * <p>用真实 {@link FailoverChatModel} 消费 {@link ModelNodeExecutor} 传给 M04 的路由链：主模型解析
 * 失败时按链顺序切到备用模型，证明节点没有把 REPORT 链折叠成单模型。M04 的 key 级重试/首字预算
 * 由现有 M04 套件覆盖。
 */
class ModelNodeFailoverTest {

    /** 主模型 ID。 */
    private static final UUID PRIMARY_ID = UUID.fromString("01930000-0000-7000-8000-000000000040");

    /** 备用模型 ID。 */
    private static final UUID FALLBACK_ID = UUID.fromString("01930000-0000-7000-8000-000000000041");

    /** 租户。 */
    private final TenantId tenantId = TenantId.of(WorkflowNodeTestFixtures.TENANT);

    /** 主模型路由。 */
    private RoutedModel primary;

    /** 备用模型路由。 */
    private RoutedModel fallback;

    /** 模型网关。 */
    private ModelRouter modelRouter;

    /** 模型注册表。 */
    private ModelRegistry modelRegistry;

    /** 调用记录。 */
    private ModelInvocationService invocationService;

    /** 引擎仓储。 */
    private WorkflowEngineRepository repository;

    /** 尝试记录。 */
    private ModelAttemptRepository attemptRepository;

    /** 调用句柄。 */
    private InvocationHandle handle;

    /** 每个测试重建 mock 与路由。 */
    @BeforeEach
    void setUp() {
        primary = routedModel(PRIMARY_ID, "primary");
        fallback = routedModel(FALLBACK_ID, "fallback");
        modelRouter = mock(ModelRouter.class);
        modelRegistry = mock(ModelRegistry.class);
        invocationService = mock(ModelInvocationService.class);
        repository = mock(WorkflowEngineRepository.class);
        attemptRepository = mock(ModelAttemptRepository.class);
        handle = new InvocationHandle(
                UUID.fromString("01930000-0000-7000-8000-000000000042"),
                tenantId,
                PRIMARY_ID,
                WorkflowNodeTestFixtures.CLOCK.millis());
        when(invocationService.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(handle);
        when(repository.linkModelInvocation(any(), any(), any(), any())).thenReturn(1);
    }

    /** 主模型解析失败时真实降级到 REPORT 链中的备用模型。 */
    @Test
    @DisplayName("主模型失败按 REPORT 链降级到备用模型")
    void execute_primaryUnavailable_failsOverToFallback() {
        ModelRoute chain =
                new ModelRoute(ModelPurpose.REPORT, ModelRoutingMode.ROUTED, true, List.of(primary, fallback));
        when(modelRouter.resolve(eq(tenantId), eq(ModelPurpose.REPORT), anyString(), any()))
                .thenReturn(chain);
        when(modelRegistry.resolve(
                        eq(primary.target()), eq(primary.credentials().getFirst())))
                .thenThrow(new ModelException(
                        ModelException.UNAVAILABLE, true, "primary down", ModelFailureKind.TRANSPORT));
        when(modelRegistry.resolve(
                        eq(fallback.target()), eq(fallback.credentials().getFirst())))
                .thenReturn(resolved(fallback));
        OpenAiCompatibleChatModel fallbackClient = mock(OpenAiCompatibleChatModel.class);
        when(fallbackClient.stream(anyList(), anyList(), any())).thenReturn(Flux.just(response("备用模型输出")));
        when(modelRegistry.createClient(any())).thenReturn(fallbackClient);
        when(modelRouter.createModel(any(), any(), any(), anyString(), any(), eq(ModelPurpose.REPORT)))
                .thenAnswer(invocation -> new FailoverChatModel(
                        invocation.getArgument(0),
                        routingProperties(),
                        modelRegistry,
                        attemptRepository,
                        mock(CredentialHealthService.class),
                        mock(AuditService.class),
                        mock(ModelMetrics.class),
                        WorkflowNodeTestFixtures.CLOCK,
                        tenantId,
                        handle,
                        "workflow-run-test",
                        null,
                        ModelPurpose.REPORT));
        ModelNodeExecutor executor =
                new ModelNodeExecutor(modelRouter, invocationService, repository, new WorkflowTemplateRenderer());

        WorkflowNodeResult result = executor.execute(execution());

        assertThat(result.output()).containsEntry("text", "备用模型输出");
        ArgumentCaptor<UUID> modelIds = ArgumentCaptor.forClass(UUID.class);
        verify(attemptRepository, times(2)).started(any(), any(), anyInt(), modelIds.capture(), any(), any());
        assertThat(modelIds.getAllValues()).containsExactly(PRIMARY_ID, FALLBACK_ID);
        verify(invocationService)
                .succeeded(
                        eq(handle),
                        eq(FALLBACK_ID),
                        eq("fallback-key"),
                        eq("workflow-run-test"),
                        eq(null),
                        eq(null),
                        eq(null),
                        eq(null),
                        eq(2));
    }

    /**
     * 构造路由模型。
     *
     * @param modelId 模型 ID
     * @param key 模型 key
     * @return 路由模型
     */
    private static RoutedModel routedModel(UUID modelId, String key) {
        ModelDescriptor descriptor = new ModelDescriptor(modelId, UUID.randomUUID(), key + "-key", "Model", 1024, 8192);
        ProviderDescriptor provider = new ProviderDescriptor(
                UUID.randomUUID(), "provider-" + key, "OPENAI_COMPATIBLE", URI.create("http://127.0.0.1:19090/v1"));
        ModelTarget target = new ModelTarget(descriptor, provider, URI.create("http://127.0.0.1:19090/v1"), 1024);
        ModelCredential credential =
                new ModelCredential(UUID.randomUUID(), provider.id(), "cipher", "key", "mk", 1, "mask", 1);
        return new RoutedModel(target, List.of(credential));
    }

    /**
     * 构造已解析模型。
     *
     * @param routed 路由模型
     * @return 已解析模型
     */
    private static ResolvedModel resolved(RoutedModel routed) {
        return new ResolvedModel(
                routed.target().model(),
                routed.target().provider(),
                routed.target().baseUrl(),
                new SecretValue("test-key"),
                routed.target().maxOutputTokens());
    }

    /**
     * 构造降级配置。
     *
     * @return 配置
     */
    private static ModelRoutingProperties routingProperties() {
        return new ModelRoutingProperties(
                3, List.of(Duration.ofMillis(1)), Duration.ofSeconds(5), Duration.ofSeconds(10));
    }

    /**
     * 构造模型响应。
     *
     * @param text 文本
     * @return 响应
     */
    private static ChatResponse response(String text) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(text).build()))
                .build();
    }

    /**
     * 构造执行上下文。
     *
     * @return 上下文
     */
    private static WorkflowNodeExecution execution() {
        String graph = "{\"schemaVersion\":1,\"nodes\":["
                + "{\"id\":\"m\",\"type\":\"MODEL\",\"name\":\"模型\","
                + "\"config\":{\"promptTemplate\":\"写报告\",\"outputKind\":\"TEXT\"}},"
                + "{\"id\":\"e\",\"type\":\"END\",\"name\":\"结束\",\"config\":{\"resultFrom\":\"m\"}}],"
                + "\"edges\":[{\"id\":\"e1\",\"source\":\"m\",\"target\":\"e\"}]}";
        return WorkflowNodeTestFixtures.execution(
                graph,
                "m",
                "{}",
                Map.of(),
                List.of(),
                WorkflowNodeTestFixtures.futureDeadline(),
                WorkflowNodeTestFixtures.activeGuard());
    }
}
