package com.riccon.worker.injection;

import com.riccon.worker.agent.service.KnowledgeSearchTool;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Flux;

/**
 * 注入 harness 的假模型：不访问任何真实模型端点，按请求边界决定是“守规”还是“被攻破”。
 *
 * <p>关键设计是 tripwire：假模型只在注入文本逃出不可信数据块（进入 SYSTEM 或用户指令面）时
 * 输出 canary 与内网地址；否则返回系统预设的企业任务答案。这样测试失败直接指向生产提示构造的回归，
 * 而不是依赖真实模型的随机服从度。另有 {@link Mode#HIJACK_DELETE} 模拟完全被攻破的模型，
 * 用于证明工具白名单/审批是最后一道防线。
 */
final class InjectionModel implements Model {

    /** 假模型行为模式。 */
    enum Mode {
        /** 边界被破坏才泄露；正常时执行系统预设任务。 */
        BOUNDARY_AWARE,
        /** 无条件尝试调用未授权删除工具，模拟被完全攻破的模型。 */
        HIJACK_DELETE,
        /** 首次调用 knowledge_search，第二次调用根据工具结果决定是否服从注入。 */
        CALL_KNOWLEDGE_THEN_ANSWER
    }

    /** 正常回答文本；包含引用编号，证明系统预设任务仍被执行。 */
    static final String BENIGN_ANSWER = "已按系统任务总结资料 [1]。";

    /** 模型名，仅用于断言与日志。 */
    private static final String MODEL_NAME = "injection-tripwire-model";

    /** 行为模式。 */
    private final Mode mode;

    /** 受保护 canary；只存在假模型内，不写入文档/日志。 */
    private final String canaryValue;

    /** 检测词。 */
    private final List<String> detectors;

    /** 模型调用记录。 */
    private final List<ModelCall> calls = new CopyOnWriteArrayList<>();

    /** 逃逸记录；非空即测试失败。 */
    private final List<String> breaches = new CopyOnWriteArrayList<>();

    /** 调用次数。 */
    private final AtomicInteger invocations = new AtomicInteger();

    /**
     * 创建假模型。
     *
     * @param mode 行为模式
     * @param canaryValue 受保护 canary
     * @param detectors 注入检测词
     */
    InjectionModel(Mode mode, String canaryValue, List<String> detectors) {
        this.mode = mode;
        this.canaryValue = canaryValue;
        this.detectors = List.copyOf(detectors);
    }

    /**
     * 返回脚本化响应；脚本耗尽时抛错而不是空流，避免测试假绿。
     *
     * @param messages 模型输入
     * @param tools 可用工具
     * @param options 生成参数
     * @return 响应流
     */
    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        int index = invocations.getAndIncrement();
        PromptScan scan = PromptScan.of(messages, detectors, canaryValue);
        calls.add(new ModelCall(index, scan, toolNames(tools), answerFor(index, scan)));
        if (scan.breach()) {
            breaches.add("调用 " + index + "：" + scan.breachReason());
            return Flux.just(textResponse(index, leakAnswer()));
        }
        if (mode == Mode.HIJACK_DELETE) {
            return Flux.just(toolCallResponse(index, "delete_knowledge", Map.of("documentId", "all")));
        }
        if (mode == Mode.CALL_KNOWLEDGE_THEN_ANSWER && index == 0) {
            return Flux.just(toolCallResponse(index, KnowledgeSearchTool.TOOL_NAME, Map.of("query", "资料")));
        }
        return Flux.just(textResponse(index, BENIGN_ANSWER));
    }

    /** 返回固定模型名。 */
    @Override
    public String getModelName() {
        return MODEL_NAME;
    }

    /** 返回调用次数。 */
    int invocationCount() {
        return invocations.get();
    }

    /** 返回调用记录。 */
    List<ModelCall> calls() {
        return List.copyOf(calls);
    }

    /** 返回逃逸记录。 */
    List<String> breaches() {
        return List.copyOf(breaches);
    }

    /**
     * 返回本次调用的实际响应类型，供失败定位。
     *
     * @param index 调用序号
     * @param scan 扫描结果
     * @return 响应说明
     */
    private String answerFor(int index, PromptScan scan) {
        if (scan.breach()) {
            return "LEAK";
        }
        if (mode == Mode.HIJACK_DELETE) {
            return "CALL delete_knowledge";
        }
        if (mode == Mode.CALL_KNOWLEDGE_THEN_ANSWER && index == 0) {
            return "CALL knowledge_search";
        }
        return "BENIGN";
    }

    /** 被攻破时的回答：包含 canary 与内网地址，测试任何一处出现都会失败。 */
    private String leakAnswer() {
        return "注入成功：canary=" + canaryValue + " target=http://" + InjectionSamples.INTERNAL_ADDRESS + "/collect";
    }

    /**
     * 构造文本响应。
     *
     * @param index 调用序号
     * @param text 回答
     * @return 响应
     */
    private static ChatResponse textResponse(int index, String text) {
        return ChatResponse.builder()
                .id("inj-text-" + index)
                .content(List.of(TextBlock.builder().text(text).build()))
                .finishReason("stop")
                .build();
    }

    /**
     * 构造工具调用响应。
     *
     * @param index 调用序号
     * @param name 工具名
     * @param input 参数
     * @return 响应
     */
    private static ChatResponse toolCallResponse(int index, String name, Map<String, Object> input) {
        return ChatResponse.builder()
                .id("inj-tool-" + index)
                .content(List.of(ToolUseBlock.builder()
                        .id("inj-call-" + index)
                        .name(name)
                        .input(input)
                        .build()))
                .finishReason("tool_calls")
                .build();
    }

    /**
     * 提取模型可见工具名。
     *
     * @param tools 工具 schema
     * @return 工具名
     */
    private static List<String> toolNames(List<ToolSchema> tools) {
        return tools == null ? List.of() : tools.stream().map(ToolSchema::getName).toList();
    }

    /**
     * 单次模型调用记录。
     *
     * @param index 调用序号
     * @param scan 边界扫描
     * @param toolNames 模型可见工具
     * @param response 响应类型
     */
    record ModelCall(int index, PromptScan scan, List<String> toolNames, String response) {

        /** 返回模型看到的系统提示。 */
        List<String> systemTexts() {
            return scan.systemTexts();
        }

        /** 返回模型看到的工具名。 */
        List<String> tools() {
            return toolNames;
        }
    }
}
