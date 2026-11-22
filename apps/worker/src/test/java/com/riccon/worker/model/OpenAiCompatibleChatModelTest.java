package com.riccon.worker.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.worker.config.ModelGatewayProperties;
import com.riccon.worker.crypto.domain.SecretValue;
import com.riccon.worker.model.domain.ModelDescriptor;
import com.riccon.worker.model.domain.ModelException;
import com.riccon.worker.model.domain.ProviderDescriptor;
import com.riccon.worker.model.domain.ResolvedModel;
import com.riccon.worker.model.service.OpenAiCompatibleChatModel;
import com.riccon.worker.support.TestModelStub;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** OpenAI 兼容流式客户端：请求格式、增量、重试与错误映射。 */
class OpenAiCompatibleChatModelTest {

    /** 模型桩。 */
    private TestModelStub stub;

    /** 每个测试启动独立桩。 */
    @BeforeEach
    void setUp() {
        stub = new TestModelStub();
    }

    /** 关闭桩。 */
    @AfterEach
    void tearDown() {
        stub.close();
    }

    /** 文本增量、usage 与认证头正确。 */
    @Test
    void stream_textDeltas_sendsApiKeyAndReturnsUsage() {
        OpenAiCompatibleChatModel model = model(Duration.ofSeconds(5), 3);

        List<ChatResponse> responses = model.stream(List.of(new UserMessage("请总结资料")), List.of(), null)
                .collectList()
                .block(Duration.ofSeconds(10));

        assertThat(responses).isNotNull();
        String text = responses.stream()
                .filter(response -> response.getContent() != null)
                .flatMap(response -> response.getContent().stream())
                .filter(TextBlock.class::isInstance)
                .map(block -> ((TextBlock) block).getText())
                .reduce("", String::concat);
        assertThat(text).isEqualTo("根据资料，这是回答 [1]。");
        assertThat(stub.lastAuthorization()).isEqualTo("Bearer " + TestModelStubApiKey.VALUE);
        assertThat(responses)
                .anySatisfy(response -> assertThat(response.getUsage()).isNotNull());
        assertThat(stub.lastRequest()).contains("\"stream\":true").contains("\"fake-model\"");
    }

    /** 工具调用增量被转换为 ToolUseBlock 内容片段。 */
    @Test
    void stream_toolCall_returnsToolUseFragments() {
        OpenAiCompatibleChatModel model = model(Duration.ofSeconds(5), 1);
        ToolSchema schema = ToolSchema.builder()
                .name("echo")
                .description("echo")
                .parameters(Map.of("type", "object"))
                .build();

        List<ChatResponse> responses = model.stream(List.of(new UserMessage("CALL_MCP")), List.of(schema), null)
                .collectList()
                .block(Duration.ofSeconds(10));

        assertThat(responses).isNotNull();
        List<ToolUseBlock> calls = responses.stream()
                .flatMap(response -> response.getContent().stream())
                .filter(ToolUseBlock.class::isInstance)
                .map(ToolUseBlock.class::cast)
                .toList();
        assertThat(calls).isNotEmpty();
        assertThat(calls.get(0).getName()).isEqualTo("echo");
        assertThat(calls).anySatisfy(call -> assertThat(call.getContent()).contains("market-data"));
        assertThat(calls.get(0).getInput()).containsEntry("text", "market-data");
        assertThat(stub.lastRequest()).contains("\"tools\"").contains("\"echo\"");
    }

    /**
     * 内层客户端不自行重试 429：所有重试由 FailoverChatModel 拥有，
     * 保证 HTTP 调用数 = attempt 行数 = 降级审计条数（ADR-0021 §5.2）。
     */
    @Test
    void stream_rateLimited_doesNotRetryInInnerClient() {
        stub.rateLimitNext(1);
        OpenAiCompatibleChatModel model = model(Duration.ofSeconds(5), 3);

        assertThatThrownBy(() -> model.stream(List.of(new UserMessage("hi")), List.of(), null)
                        .collectList()
                        .block(Duration.ofSeconds(10)))
                .isInstanceOf(ModelException.class)
                .satisfies(error -> {
                    ModelException modelError = (ModelException) error;
                    assertThat(modelError.errorCode()).isEqualTo(ModelException.RATE_LIMITED);
                    assertThat(modelError.failureKind())
                            .isEqualTo(com.riccon.worker.model.domain.ModelFailureKind.RATE_LIMITED);
                });
        assertThat(stub.requestCount()).isEqualTo(1);
    }

    /** 内层客户端不自行重试 5xx；失败带上可重试与 5xx 物理分类供降级状态机使用。 */
    @Test
    void stream_serverError_doesNotRetryInInnerClient() {
        stub.forceStatus(500);
        OpenAiCompatibleChatModel model = model(Duration.ofSeconds(5), 2);

        assertThatThrownBy(() -> model.stream(List.of(new UserMessage("hi")), List.of(), null)
                        .collectList()
                        .block(Duration.ofSeconds(10)))
                .isInstanceOf(ModelException.class)
                .satisfies(error -> {
                    ModelException modelError = (ModelException) error;
                    assertThat(modelError.errorCode()).isEqualTo(ModelException.UNAVAILABLE);
                    assertThat(modelError.retryable()).isTrue();
                    assertThat(modelError.failureKind())
                            .isEqualTo(com.riccon.worker.model.domain.ModelFailureKind.SERVER_ERROR);
                });
        assertThat(stub.requestCount()).isEqualTo(1);
    }

    /** 401 不重试。 */
    @Test
    void stream_unauthorized_doesNotRetry() {
        stub.forceStatus(401);
        OpenAiCompatibleChatModel model = model(Duration.ofSeconds(5), 3);

        assertThatThrownBy(() -> model.stream(List.of(new UserMessage("hi")), List.of(), null)
                        .collectList()
                        .block(Duration.ofSeconds(10)))
                .isInstanceOf(ModelException.class)
                .satisfies(error ->
                        assertThat(((ModelException) error).errorCode()).isEqualTo(ModelException.UNAUTHORIZED));
        assertThat(stub.requestCount()).isEqualTo(1);
    }

    /** 流中错误载荷产生失败且错误信息不含密钥。 */
    @Test
    void stream_errorPayload_failsWithoutLeakingApiKey() {
        stub.errorAfterChunk();
        OpenAiCompatibleChatModel model = model(Duration.ofSeconds(5), 1);

        assertThatThrownBy(() -> model.stream(List.of(new UserMessage("hi")), List.of(), null)
                        .collectList()
                        .block(Duration.ofSeconds(10)))
                .isInstanceOf(ModelException.class)
                .hasMessageNotContaining(TestModelStubApiKey.VALUE);
    }

    /** 空回答在协议层正常结束，由上层判定无可见内容。 */
    @Test
    void stream_emptyAnswer_completesWithoutText() {
        stub.emptyAnswer();
        OpenAiCompatibleChatModel model = model(Duration.ofSeconds(5), 1);

        List<ChatResponse> responses = model.stream(List.of(new UserMessage("hi")), List.of(), null)
                .collectList()
                .block(Duration.ofSeconds(10));

        assertThat(responses).isNotNull();
        assertThat(responses.stream()
                        .flatMap(response -> response.getContent().stream())
                        .filter(TextBlock.class::isInstance))
                .isEmpty();
    }

    /** 非法 SSE JSON 返回不可重试协议错误。 */
    @Test
    void stream_malformedJson_fails() {
        stub.malformedResponse();
        OpenAiCompatibleChatModel model = model(Duration.ofSeconds(5), 1);

        assertThatThrownBy(() -> model.stream(List.of(new UserMessage("hi")), List.of(), null)
                        .collectList()
                        .block(Duration.ofSeconds(10)))
                .isInstanceOf(ModelException.class)
                .satisfies(error ->
                        assertThat(((ModelException) error).errorCode()).isEqualTo(ModelException.INVALID_RESPONSE));
    }

    /** 缺少 [DONE] 时按 EOF 正常结束。 */
    @Test
    void stream_missingDone_completes() {
        stub.noDone();
        OpenAiCompatibleChatModel model = model(Duration.ofSeconds(5), 1);

        List<ChatResponse> responses = model.stream(List.of(new UserMessage("hi")), List.of(), null)
                .collectList()
                .block(Duration.ofSeconds(10));

        assertThat(responses).isNotNull();
        assertThat(responses)
                .anySatisfy(response -> assertThat(response.getFinishReason()).isEqualTo("stop"));
    }

    /** 空流在 EOF 时补一个 stop，避免 Agent 挂起。 */
    @Test
    void stream_emptyStream_completesWithStop() {
        stub.emptyStream();
        OpenAiCompatibleChatModel model = model(Duration.ofSeconds(5), 1);

        List<ChatResponse> responses = model.stream(List.of(new UserMessage("hi")), List.of(), null)
                .collectList()
                .block(Duration.ofSeconds(10));

        assertThat(responses).isNotNull();
        assertThat(responses)
                .last()
                .satisfies(response -> assertThat(response.getFinishReason()).isEqualTo("stop"));
    }

    /** 连接失败返回可重试不可用错误。 */
    @Test
    void stream_connectionRefused_failsRetryable() {
        OpenAiCompatibleChatModel model = model(Duration.ofSeconds(1), 1, "http://127.0.0.1:1");

        assertThatThrownBy(() -> model.stream(List.of(new UserMessage("hi")), List.of(), null)
                        .collectList()
                        .block(Duration.ofSeconds(10)))
                .isInstanceOf(ModelException.class)
                .satisfies(error -> {
                    ModelException modelError = (ModelException) error;
                    assertThat(modelError.errorCode()).isEqualTo(ModelException.UNAVAILABLE);
                    assertThat(modelError.retryable()).isTrue();
                    assertThat(modelError.failureKind())
                            .isEqualTo(com.riccon.worker.model.domain.ModelFailureKind.TRANSPORT);
                });
    }

    /** 其他 4xx 状态映射为不可重试协议错误。 */
    @Test
    void stream_otherClientErrors_failWithoutRetry() {
        for (int status : List.of(400, 403, 404, 408)) {
            stub.reset();
            stub.forceStatus(status);
            OpenAiCompatibleChatModel model = model(Duration.ofSeconds(5), 1);
            assertThatThrownBy(() -> model.stream(List.of(new UserMessage("hi")), List.of(), null)
                            .collectList()
                            .block(Duration.ofSeconds(10)))
                    .as("status %s", status)
                    .isInstanceOf(ModelException.class);
            assertThat(stub.requestCount()).isEqualTo(1);
        }
    }

    /** 工具历史消息与生成参数被序列化到请求体。 */
    @Test
    void stream_toolHistoryAndOptions_serialized() {
        OpenAiCompatibleChatModel model = model(Duration.ofSeconds(5), 1);
        ToolUseBlock call = ToolUseBlock.builder()
                .id("call-1")
                .name("echo")
                .input(Map.of("text", "hello"))
                .build();
        Msg assistant = Msg.builder()
                .name("assistant")
                .role(MsgRole.ASSISTANT)
                .content(call)
                .build();
        Msg toolResult = Msg.builder()
                .name("tool")
                .role(MsgRole.TOOL)
                .content(ToolResultBlock.of(
                        "call-1",
                        "echo",
                        TextBlock.builder().text("echo: hello").build()))
                .build();
        GenerateOptions options = GenerateOptions.builder()
                .temperature(0.2)
                .topP(0.9)
                .maxTokens(128)
                .build();

        model.stream(List.of(new UserMessage("hi"), assistant, toolResult), List.of(), options)
                .blockLast(Duration.ofSeconds(10));

        assertThat(stub.lastRequest())
                .contains("\"tool_calls\"")
                .contains("\"role\":\"tool\"")
                .contains("\"tool_call_id\":\"call-1\"")
                .contains("\"temperature\":0.2")
                .contains("\"top_p\":0.9")
                .contains("\"max_tokens\":128");
    }

    /** 构造模型。 */
    private OpenAiCompatibleChatModel model(Duration timeout, int attempts) {
        return model(timeout, attempts, stub.url());
    }

    /** 构造指定端点模型。 */
    private OpenAiCompatibleChatModel model(Duration timeout, int attempts, String url) {
        ProviderDescriptor provider =
                new ProviderDescriptor(UUID.randomUUID(), "stub", "OPENAI_COMPATIBLE", URI.create(url));
        ModelDescriptor descriptor =
                new ModelDescriptor(UUID.randomUUID(), provider.id(), "fake-model", "Fake", 1024, 8192);
        ResolvedModel resolved = new ResolvedModel(
                descriptor, provider, URI.create(url), new SecretValue(TestModelStubApiKey.VALUE), 1024);
        ModelGatewayProperties properties = new ModelGatewayProperties(
                timeout, timeout, attempts, List.of(Duration.ZERO, Duration.ZERO), true, 1024);
        return new OpenAiCompatibleChatModel(resolved, properties, new ObjectMapper());
    }

    /** 测试密钥常量。 */
    private static final class TestModelStubApiKey {
        private static final String VALUE = "sk-model-test-secret";
    }
}
