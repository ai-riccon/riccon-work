package com.riccon.worker.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.worker.config.ModelGatewayProperties;
import com.riccon.worker.crypto.domain.SecretValue;
import com.riccon.worker.model.domain.ModelDescriptor;
import com.riccon.worker.model.domain.ProviderDescriptor;
import com.riccon.worker.model.domain.ResolvedModel;
import com.riccon.worker.model.service.OpenAiCompatibleChatModel;
import com.riccon.worker.support.TestModelStub;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 模型流式读取不能阻塞订阅线程（F-002 根因回归测试）。
 *
 * <p>{@code Flux.create} 的生成器是阻塞的 HTTP 读循环；如果在订阅线程上直接执行，
 * {@code Flux.toIterable().iterator()} 会阻塞到整个模型流结束才返回第一个元素，表现为
 * “增量被缓冲到流结束”。该用例用时间分片桩验证：获取迭代器与读取首个增量都必须远早于
 * 模型结束。
 */
class OpenAiCompatibleChatModelStreamingTest {

    /** 分片：首个 content 帧之前的延迟。 */
    private static final int FIRST_FRAME_DELAY_MS = 40;

    /** 分片：相邻 content 帧间隔。 */
    private static final int FRAME_INTERVAL_MS = 120;

    /** 分片数量：6 片 ≈ 640ms 总时长，足以区分首帧与结束。 */
    private static final int SLICES = 6;

    /** 模型桩。 */
    private TestModelStub stub;

    /** 被测模型。 */
    private OpenAiCompatibleChatModel model;

    /** 启动模型桩与被测模型。 */
    @BeforeEach
    void setUp() {
        stub = new TestModelStub();
        stub.timeSliced(FIRST_FRAME_DELAY_MS, FRAME_INTERVAL_MS, SLICES);
        model = new OpenAiCompatibleChatModel(resolvedModel(stub.url()), properties(), new ObjectMapper());
    }

    /** 关闭模型桩。 */
    @AfterEach
    void tearDown() {
        stub.close();
    }

    /** 阻塞式迭代器按到达顺序逐条拿到增量，且首个增量远早于模型结束。 */
    @Test
    void blockingIterableReceivesIncrementsBeforeStreamEnds() {
        Msg question = new UserMessage("你好");
        // 预热：首次订阅包含 Reactor/HttpClient 的类加载与线程启动（实测约 300ms），
        // 与流式缓冲无关，不计入断言。
        consumeAll(question);
        long startedAt = System.nanoTime();
        Iterator<ChatResponse> iterator =
                model.stream(List.of(question), List.of(), null).toIterable().iterator();
        long iteratorReadyMs = elapsedMs(startedAt);
        assertThat(iteratorReadyMs)
                .as("获取迭代器不得等整个模型流（实测 %dms）", iteratorReadyMs)
                .isLessThan(300L);

        List<Long> textArrivals = new ArrayList<>();
        long finishedMs = drain(iterator, startedAt, textArrivals);

        assertThat(textArrivals).as("应有 %d 个文本增量", SLICES).hasSize(SLICES);
        long firstMs = textArrivals.get(0);
        assertThat(firstMs).as("首个增量 %dms 必须远早于整段流 %dms", firstMs, finishedMs).isLessThan(400L);
        // 增量按 120ms 间隔到达：如果被缓冲到结束，所有时间戳会挤在末尾。
        assertThat(textArrivals.get(textArrivals.size() - 1) - firstMs)
                .as("增量应分散到达，而不是集中在末尾")
                .isGreaterThanOrEqualTo((long) (SLICES - 2) * FRAME_INTERVAL_MS);
        assertThat(finishedMs).as("整段流应覆盖分片间隔").isGreaterThan((long) (SLICES - 1) * FRAME_INTERVAL_MS);
    }

    /**
     * 读取并丢弃一次完整流，用于预热。
     *
     * @param question 用户消息
     */
    private void consumeAll(Msg question) {
        Iterator<ChatResponse> iterator =
                model.stream(List.of(question), List.of(), null).toIterable().iterator();
        drain(iterator, System.nanoTime(), new ArrayList<>());
    }

    /**
     * 读完迭代器并记录文本增量到达时间。
     *
     * @param iterator 响应迭代器
     * @param startedAt 开始纳秒
     * @param textArrivals 文本增量到达时间收集器
     * @return 读取结束时的毫秒数
     */
    private static long drain(Iterator<ChatResponse> iterator, long startedAt, List<Long> textArrivals) {
        while (iterator.hasNext()) {
            ChatResponse response = iterator.next();
            if (response.getContent() != null && !response.getContent().isEmpty()) {
                textArrivals.add(elapsedMs(startedAt));
            }
        }
        return elapsedMs(startedAt);
    }

    /**
     * 取消订阅后立即终止模型流，且不把取消当错误上报。
     *
     * <p>Worker 取消运行时会释放 Agent 与事件流；若阻塞读循环不感知取消，线程会一直占住到
     * 上游自然结束（或空闲超时）。该用例只取首个增量即取消，断言在远小于整段流的时长内结束。
     */
    @Test
    void cancellingAfterFirstIncrementStopsStreamPromptly() {
        Msg question = new UserMessage("你好");
        // 预热：首次订阅的类加载与线程启动开销不计入断言。
        consumeAll(question);
        long startedAt = System.nanoTime();
        ChatResponse first = model.stream(List.of(question), List.of(), null).blockFirst(Duration.ofSeconds(10));
        long cancelledMs = elapsedMs(startedAt);

        assertThat(first).as("取消前应拿到首个响应").isNotNull();
        assertThat(cancelledMs)
                .as("取消后应立即结束，不能等整段流 %dms 的分片", cancelledMs)
                .isLessThan((long) (SLICES - 2) * FRAME_INTERVAL_MS);
    }

    /**
     * 计算自开始以来的毫秒数。
     *
     * @param startedAt 开始纳秒
     * @return 毫秒
     */
    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    /**
     * 构造模型解析结果。
     *
     * @param baseUrl 模型桩地址
     * @return 解析结果
     */
    private static ResolvedModel resolvedModel(String baseUrl) {
        return new ResolvedModel(
                new ModelDescriptor(UUID.randomUUID(), UUID.randomUUID(), "fake-model", "Fake", 512, 8192),
                new ProviderDescriptor(UUID.randomUUID(), "stub", "OPENAI_COMPATIBLE", URI.create(baseUrl)),
                URI.create(baseUrl),
                new SecretValue("test-key"),
                512);
    }

    /** 网关配置：允许本地明文 HTTP，单次尝试。 */
    private static ModelGatewayProperties properties() {
        return new ModelGatewayProperties(
                Duration.ofSeconds(5), Duration.ofSeconds(20), 1, List.of(Duration.ofMillis(10)), true, 512);
    }
}
