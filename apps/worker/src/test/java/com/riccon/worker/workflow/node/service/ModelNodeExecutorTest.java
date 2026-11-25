package com.riccon.worker.workflow.node.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.riccon.platform.domain.model.ModelPurpose;
import com.riccon.platform.domain.workflow.WorkflowErrorCode;
import com.riccon.worker.model.domain.ModelCredential;
import com.riccon.worker.model.domain.ModelDescriptor;
import com.riccon.worker.model.domain.ModelRoute;
import com.riccon.worker.model.domain.ModelRoutingMode;
import com.riccon.worker.model.domain.ModelTarget;
import com.riccon.worker.model.domain.ProviderDescriptor;
import com.riccon.worker.model.domain.RoutedModel;
import com.riccon.worker.model.service.FailoverChatModel;
import com.riccon.worker.model.service.ModelInvocationService;
import com.riccon.worker.model.service.ModelInvocationService.InvocationHandle;
import com.riccon.worker.model.service.ModelRouter;
import com.riccon.worker.workflow.engine.repository.WorkflowEngineRepository;
import com.riccon.worker.workflow.node.WorkflowNodeTestFixtures;
import com.riccon.worker.workflow.node.domain.WorkflowNodeExecution;
import com.riccon.worker.workflow.node.domain.WorkflowNodeFailureException;
import com.riccon.worker.workflow.node.domain.WorkflowNodeResult;
import com.riccon.worker.workflow.node.domain.WorkflowNodeTimeoutException;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

/**
 * MODEL 节点执行器（ADR-0024 §4.4/§4.10）。
 *
 * <p>验证固定 REPORT 路由、可选 modelId 必须在链内、分类标签命中、调用记录关联、
 * 失败/超时映射；模型网关的降级与成本口径由 M04 套件覆盖。
 */
class ModelNodeExecutorTest {

    /** 模型 ID（链首）。 */
    private static final UUID MODEL_ID = UUID.fromString("01930000-0000-7000-8000-000000000030");

    /** 备用模型 ID。 */
    private static final UUID FALLBACK_MODEL_ID = UUID.fromString("01930000-0000-7000-8000-000000000032");

    /** 租户。 */
    private com.riccon.platform.common.tenant.TenantId tenantId;

    /** 模型网关。 */
    private ModelRouter modelRouter;

    /** 调用记录。 */
    private ModelInvocationService invocationService;

    /** 引擎仓储。 */
    private WorkflowEngineRepository repository;

    /** 降级模型。 */
    private FailoverChatModel chatModel;

    /** 路由。 */
    private ModelRoute route;

    /** 调用句柄。 */
    private InvocationHandle handle;

    /** 执行器。 */
    private ModelNodeExecutor executor;

    /** 每个测试重建 mock。 */
    @BeforeEach
    void setUp() {
        tenantId = com.riccon.platform.common.tenant.TenantId.of(WorkflowNodeTestFixtures.TENANT);
        modelRouter = mock(ModelRouter.class);
        invocationService = mock(ModelInvocationService.class);
        repository = mock(WorkflowEngineRepository.class);
        chatModel = mock(FailoverChatModel.class);
        route = route(true);
        handle = new InvocationHandle(
                UUID.fromString("01930000-0000-7000-8000-000000000031"),
                tenantId,
                MODEL_ID,
                WorkflowNodeTestFixtures.CLOCK.millis());
        when(modelRouter.resolve(eq(tenantId), eq(ModelPurpose.REPORT), anyString(), any()))
                .thenReturn(route);
        when(modelRouter.createModel(any(), any(), any(), anyString(), any(), eq(ModelPurpose.REPORT)))
                .thenReturn(chatModel);
        when(invocationService.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(handle);
        when(repository.linkModelInvocation(any(), any(), any(), any())).thenReturn(1);
        executor = new ModelNodeExecutor(modelRouter, invocationService, repository, new WorkflowTemplateRenderer());
    }

    /** 文本输出成功并记录调用。 */
    @Test
    @DisplayName("文本模型使用 REPORT 路由并记录调用")
    void execute_text_succeeds() {
        when(chatModel.stream(anyList(), anyList(), any())).thenReturn(Flux.just(response("写好的报告")));

        WorkflowNodeResult result = executor.execute(execution("{\"promptTemplate\":\"写报告\",\"outputKind\":\"TEXT\"}"));

        assertThat(result.output()).containsEntry("text", "写好的报告");
        assertThat(result.outputSummary()).isEqualTo("写好的报告");
        assertThat(result.result()).isNull();
        verify(modelRouter).resolve(tenantId, ModelPurpose.REPORT, "workflow-run-test", null);
        verify(repository)
                .linkModelInvocation(
                        eq(tenantId),
                        eq(handle.id()),
                        eq(UUID.fromString("01930000-0000-7000-8000-000000000010")),
                        eq(UUID.fromString("01930000-0000-7000-8000-000000000011")));
        verify(invocationService)
                .succeeded(
                        eq(handle),
                        eq(MODEL_ID),
                        eq("model-key"),
                        eq("workflow-run-test"),
                        eq(null),
                        eq(null),
                        eq(null),
                        eq(null),
                        eq(1));
    }

    /** 未配置 modelId：传给 M04 的必须是完整 REPORT 链，保留跨模型降级。 */
    @Test
    @DisplayName("未指定模型时传完整 REPORT 链")
    void execute_withoutModelId_passesFullReportChain() {
        ModelRoute chain = routeWithModels(2);
        when(modelRouter.resolve(eq(tenantId), eq(ModelPurpose.REPORT), anyString(), any()))
                .thenReturn(chain);
        when(chatModel.stream(anyList(), anyList(), any())).thenReturn(Flux.just(response("文本")));

        executor.execute(execution("{\"promptTemplate\":\"写报告\",\"outputKind\":\"TEXT\"}"));

        ArgumentCaptor<ModelRoute> captor = ArgumentCaptor.forClass(ModelRoute.class);
        verify(modelRouter).createModel(captor.capture(), any(), any(), anyString(), any(), eq(ModelPurpose.REPORT));
        assertThat(captor.getValue().models())
                .extracting(model -> model.target().model().id())
                .containsExactly(MODEL_ID, FALLBACK_MODEL_ID);
        assertThat(captor.getValue().failoverEnabled()).isTrue();
    }

    /** 指定 modelId：传给 M04 的是该模型在链中的后缀，仍可向后续备用模型降级。 */
    @Test
    @DisplayName("指定模型时传链后缀")
    void execute_withModelId_passesSuffixChain() {
        ModelRoute chain = routeWithModels(2);
        when(modelRouter.resolve(eq(tenantId), eq(ModelPurpose.REPORT), anyString(), any()))
                .thenReturn(chain);
        when(chatModel.stream(anyList(), anyList(), any())).thenReturn(Flux.just(response("文本")));

        executor.execute(execution("{\"promptTemplate\":\"写报告\",\"outputKind\":\"TEXT\","
                + "\"modelId\":\"01930000-0000-7000-8000-000000000032\"}"));

        ArgumentCaptor<ModelRoute> captor = ArgumentCaptor.forClass(ModelRoute.class);
        verify(modelRouter).createModel(captor.capture(), any(), any(), anyString(), any(), eq(ModelPurpose.REPORT));
        assertThat(captor.getValue().models())
                .extracting(model -> model.target().model().id())
                .containsExactly(FALLBACK_MODEL_ID);
    }

    /** 分类标签命中。 */
    @Test
    @DisplayName("分类输出命中标签")
    void execute_classification_matchesLabel() {
        when(chatModel.stream(anyList(), anyList(), any())).thenReturn(Flux.just(response("正面")));

        WorkflowNodeResult result = executor.execute(
                execution("{\"promptTemplate\":\"分类\",\"outputKind\":\"CLASSIFICATION\",\"labels\":[\"正面\",\"负面\"]}"));

        assertThat(result.output()).containsEntry("classificationLabel", "正面");
        assertThat(result.outputSummary()).isEqualTo("分类：正面");
    }

    /** 分类标签未命中失败并记录失败调用。 */
    @Test
    @DisplayName("分类输出未命中标签失败")
    void execute_classificationMismatch_fails() {
        when(chatModel.stream(anyList(), anyList(), any())).thenReturn(Flux.just(response("中性")));

        assertThatThrownBy(() -> executor.execute(execution(
                        "{\"promptTemplate\":\"分类\",\"outputKind\":\"CLASSIFICATION\",\"labels\":[\"正面\",\"负面\"]}")))
                .isInstanceOf(WorkflowNodeFailureException.class)
                .satisfies(error -> assertThat(((WorkflowNodeFailureException) error).errorCode())
                        .isEqualTo(WorkflowErrorCode.WF_MODEL_OUTPUT_INVALID));
        verify(invocationService)
                .failed(
                        eq(handle),
                        eq("workflow-run-test"),
                        eq(null),
                        eq("WF_MODEL_OUTPUT_INVALID"),
                        eq(true),
                        eq(null),
                        eq(false),
                        eq(1));
    }

    /** 节点 modelId 必须在 REPORT 链内。 */
    @Test
    @DisplayName("节点模型不在链内失败")
    void execute_modelNotInRoute_fails() {
        String config = "{\"promptTemplate\":\"写报告\",\"outputKind\":\"TEXT\","
                + "\"modelId\":\"01930000-0000-7000-8000-000000000099\"}";

        assertThatThrownBy(() -> executor.execute(execution(config)))
                .isInstanceOf(WorkflowNodeFailureException.class)
                .satisfies(error -> assertThat(((WorkflowNodeFailureException) error).errorCode())
                        .isEqualTo(WorkflowErrorCode.WF_MODEL_UNAVAILABLE));
        verify(invocationService, never()).start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /** 空路由链失败。 */
    @Test
    @DisplayName("空 REPORT 路由链失败")
    void execute_emptyRoute_fails() {
        when(modelRouter.resolve(eq(tenantId), eq(ModelPurpose.REPORT), anyString(), any()))
                .thenReturn(route(false));

        assertThatThrownBy(() -> executor.execute(execution("{\"promptTemplate\":\"写报告\"}")))
                .isInstanceOf(WorkflowNodeFailureException.class)
                .satisfies(error -> assertThat(((WorkflowNodeFailureException) error).errorCode())
                        .isEqualTo(WorkflowErrorCode.WF_MODEL_UNAVAILABLE));
    }

    /** 模型超时映射 WF_NODE_TIMEOUT 并记录失败。 */
    @Test
    @DisplayName("模型超时映射 WF_NODE_TIMEOUT")
    void execute_timeout_mapsNodeTimeout() {
        when(chatModel.stream(anyList(), anyList(), any())).thenReturn(Flux.error(new TimeoutException("timeout")));

        assertThatThrownBy(() -> executor.execute(execution("{\"promptTemplate\":\"写报告\"}")))
                .isInstanceOf(WorkflowNodeTimeoutException.class);
        verify(invocationService)
                .failed(
                        eq(handle),
                        eq("workflow-run-test"),
                        eq(null),
                        eq("WF_NODE_TIMEOUT"),
                        eq(true),
                        eq(null),
                        eq(false),
                        eq(1));
    }

    /** 模型调用失败映射 WF_MODEL_UNAVAILABLE。 */
    @Test
    @DisplayName("模型调用失败映射 WF_MODEL_UNAVAILABLE")
    void execute_modelError_mapsUnavailable() {
        when(chatModel.stream(anyList(), anyList(), any())).thenReturn(Flux.error(new IllegalStateException("boom")));

        assertThatThrownBy(() -> executor.execute(execution("{\"promptTemplate\":\"写报告\"}")))
                .isInstanceOf(WorkflowNodeFailureException.class)
                .satisfies(error -> assertThat(((WorkflowNodeFailureException) error).errorCode())
                        .isEqualTo(WorkflowErrorCode.WF_MODEL_UNAVAILABLE));
        verify(invocationService)
                .failed(
                        eq(handle),
                        eq("workflow-run-test"),
                        eq(null),
                        eq("WF_MODEL_UNAVAILABLE"),
                        eq(true),
                        eq(null),
                        eq(false),
                        eq(1));
    }

    /**
     * 构造包含两个模型的路由链。
     *
     * @param count 模型数量（1 或 2）
     * @return 路由
     */
    private static ModelRoute routeWithModels(int count) {
        List<RoutedModel> models = new java.util.ArrayList<>();
        UUID[] ids = {MODEL_ID, FALLBACK_MODEL_ID};
        for (int index = 0; index < count; index++) {
            models.add(routedModel(ids[index], "model-" + index));
        }
        return new ModelRoute(ModelPurpose.REPORT, ModelRoutingMode.ROUTED, true, models);
    }

    /**
     * 构造单个路由模型。
     *
     * @param modelId 模型 ID
     * @param key 模型 key
     * @return 路由模型
     */
    private static RoutedModel routedModel(UUID modelId, String key) {
        ModelDescriptor descriptor = new ModelDescriptor(modelId, UUID.randomUUID(), key, "Model", 1024, 8192);
        ProviderDescriptor provider = new ProviderDescriptor(
                UUID.randomUUID(), "provider-" + key, "OPENAI_COMPATIBLE", URI.create("http://127.0.0.1:19090/v1"));
        ModelTarget target = new ModelTarget(descriptor, provider, URI.create("http://127.0.0.1:19090/v1"), 1024);
        ModelCredential credential =
                new ModelCredential(UUID.randomUUID(), provider.id(), "cipher", "key", "mk", 1, "mask", 1);
        return new RoutedModel(target, List.of(credential));
    }

    /**
     * 构造模型路由。
     *
     * @param withModel 是否包含模型
     * @return 路由
     */
    private static ModelRoute route(boolean withModel) {
        if (!withModel) {
            return new ModelRoute(ModelPurpose.REPORT, ModelRoutingMode.ROUTED, true, List.of());
        }
        ModelDescriptor descriptor = new ModelDescriptor(MODEL_ID, UUID.randomUUID(), "model-key", "Model", 1024, 8192);
        ProviderDescriptor provider = new ProviderDescriptor(
                UUID.randomUUID(), "provider", "OPENAI_COMPATIBLE", URI.create("http://127.0.0.1:19090/v1"));
        ModelTarget target = new ModelTarget(descriptor, provider, URI.create("http://127.0.0.1:19090/v1"), 1024);
        ModelCredential credential =
                new ModelCredential(UUID.randomUUID(), provider.id(), "cipher", "key", "mk", 1, "mask", 1);
        return new ModelRoute(
                ModelPurpose.REPORT,
                ModelRoutingMode.ROUTED,
                true,
                List.of(new RoutedModel(target, List.of(credential))));
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

    /** 分类节点缺少 labels 失败。 */
    @Test
    @DisplayName("分类节点缺少 labels 失败")
    void execute_classificationWithoutLabels_fails() {
        assertThatThrownBy(() ->
                        executor.execute(execution("{\"promptTemplate\":\"分类\",\"outputKind\":\"CLASSIFICATION\"}")))
                .isInstanceOf(WorkflowNodeFailureException.class)
                .satisfies(error -> assertThat(((WorkflowNodeFailureException) error).errorCode())
                        .isEqualTo(WorkflowErrorCode.WF_INVALID_GRAPH));
        verify(invocationService, never()).start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /** 模型节点缺少提示词失败。 */
    @Test
    @DisplayName("模型节点缺少提示词失败")
    void execute_missingPrompt_fails() {
        assertThatThrownBy(() -> executor.execute(execution("{\"outputKind\":\"TEXT\"}")))
                .isInstanceOf(WorkflowNodeFailureException.class)
                .satisfies(error -> assertThat(((WorkflowNodeFailureException) error).errorCode())
                        .isEqualTo(WorkflowErrorCode.WF_INVALID_GRAPH));
    }

    /** 调用记录关联失败 fail-closed。 */
    @Test
    @DisplayName("调用记录关联失败 fail-closed")
    void execute_linkFailure_failsClosed() {
        when(repository.linkModelInvocation(any(), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> executor.execute(execution("{\"promptTemplate\":\"写报告\"}")))
                .isInstanceOf(WorkflowNodeFailureException.class)
                .satisfies(error -> assertThat(((WorkflowNodeFailureException) error).errorCode())
                        .isEqualTo(WorkflowErrorCode.WF_INTERNAL));
        verify(invocationService)
                .failed(
                        eq(handle),
                        eq("workflow-run-test"),
                        eq(null),
                        eq("WF_INTERNAL"),
                        eq(false),
                        eq(null),
                        eq(false),
                        eq(1));
    }

    /** 取消守卫在调用前中断。 */
    @Test
    @DisplayName("取消守卫中断模型节点")
    void execute_cancelledGuard_aborts() {
        WorkflowNodeExecution execution =
                executionWithGuard("{\"promptTemplate\":\"写报告\"}", WorkflowNodeTestFixtures.guard(false, true));

        assertThatThrownBy(() -> executor.execute(execution))
                .isInstanceOf(com.riccon.worker.workflow.node.domain.WorkflowNodeAbortedException.class);
        verify(invocationService, never()).start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * 构造执行上下文。
     *
     * @param config 节点配置
     * @return 上下文
     */
    private static WorkflowNodeExecution execution(String config) {
        return executionWithGuard(config, WorkflowNodeTestFixtures.activeGuard());
    }

    /**
     * 构造带守卫的执行上下文。
     *
     * @param config 节点配置
     * @param guard 守卫
     * @return 上下文
     */
    private static WorkflowNodeExecution executionWithGuard(
            String config, com.riccon.worker.workflow.node.domain.WorkflowNodeGuard guard) {
        String graph = "{\"schemaVersion\":1,\"nodes\":["
                + "{\"id\":\"m\",\"type\":\"MODEL\",\"name\":\"模型\",\"config\":" + config + "},"
                + "{\"id\":\"e\",\"type\":\"END\",\"name\":\"结束\",\"config\":{\"resultFrom\":\"m\"}}],"
                + "\"edges\":[{\"id\":\"e1\",\"source\":\"m\",\"target\":\"e\"}]}";
        return WorkflowNodeTestFixtures.execution(
                graph, "m", "{}", Map.of(), List.of(), WorkflowNodeTestFixtures.futureDeadline(), guard);
    }
}
