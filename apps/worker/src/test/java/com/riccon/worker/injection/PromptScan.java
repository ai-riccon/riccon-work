package com.riccon.worker.injection;

import com.riccon.worker.agent.service.UntrustedData;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 模型请求边界扫描：把系统指令、用户指令、不可信数据块与工具结果分开。
 *
 * <p>判定规则来自 ADR-0023 §8 TM-55 与 {@code AgentPrompts} 的约定：只有 SYSTEM 消息、以及
 * 用户消息中位于 {@code <<<UNTRUSTED_DATA>>>}/{@code <<<END_UNTRUSTED_DATA>>>} 之外的部分才是指令；
 * 工具（TOOL）消息与标记内部文本一律是不可信数据。检测词出现在“指令面”即视为注入逃逸。
 */
final class PromptScan {

    /** 系统消息原文。 */
    private final List<String> systemTexts;

    /** 数据块之外的指令文本。 */
    private final List<String> instructionTexts;

    /** 数据块内部与工具结果文本。 */
    private final List<String> untrustedTexts;

    /** 工具结果原文。 */
    private final List<String> toolResultTexts;

    /** 数据块开始标记数量。 */
    private final int startMarkers;

    /** 数据块结束标记数量。 */
    private final int endMarkers;

    /** 指令面命中的检测词。 */
    private final List<String> instructionHits;

    /** 不可信面命中的检测词。 */
    private final List<String> untrustedHits;

    /**
     * 创建扫描结果。
     *
     * @param systemTexts 系统消息
     * @param instructionTexts 指令文本
     * @param untrustedTexts 不可信数据
     * @param toolResultTexts 工具结果
     * @param startMarkers 开始标记数
     * @param endMarkers 结束标记数
     * @param instructionHits 指令面命中
     * @param untrustedHits 不可信面命中
     */
    private PromptScan(
            List<String> systemTexts,
            List<String> instructionTexts,
            List<String> untrustedTexts,
            List<String> toolResultTexts,
            int startMarkers,
            int endMarkers,
            List<String> instructionHits,
            List<String> untrustedHits) {
        this.systemTexts = List.copyOf(systemTexts);
        this.instructionTexts = List.copyOf(instructionTexts);
        this.untrustedTexts = List.copyOf(untrustedTexts);
        this.toolResultTexts = List.copyOf(toolResultTexts);
        this.startMarkers = startMarkers;
        this.endMarkers = endMarkers;
        this.instructionHits = List.copyOf(instructionHits);
        this.untrustedHits = List.copyOf(untrustedHits);
    }

    /**
     * 扫描一次模型请求。
     *
     * @param messages 模型消息
     * @param detectors 注入检测词（大小写不敏感）
     * @param canaryValue 受保护 canary 值；出现在指令面即逃逸
     * @return 扫描结果
     */
    static PromptScan of(List<Msg> messages, List<String> detectors, String canaryValue) {
        List<String> systemTexts = new ArrayList<>();
        List<String> instructionTexts = new ArrayList<>();
        List<String> untrustedTexts = new ArrayList<>();
        List<String> toolResults = new ArrayList<>();
        int[] markers = new int[2];
        for (Msg message : messages) {
            String text = message.getTextContent();
            if (text == null || text.isEmpty()) {
                continue;
            }
            if (message.getRole() == MsgRole.SYSTEM) {
                systemTexts.add(text);
                instructionTexts.add(text);
            } else if (message.getRole() == MsgRole.TOOL) {
                toolResults.add(text);
                untrustedTexts.add(text);
            } else {
                scanMarkedText(text, instructionTexts, untrustedTexts, markers);
            }
        }
        Set<String> instructionHits = new LinkedHashSet<>(hits(instructionTexts, detectors));
        if (contains(instructionTexts, canaryValue)) {
            instructionHits.add("canary-value");
        }
        List<String> untrustedHits = hits(untrustedTexts, detectors);
        return new PromptScan(
                systemTexts,
                instructionTexts,
                untrustedTexts,
                toolResults,
                markers[0],
                markers[1],
                List.copyOf(instructionHits),
                untrustedHits);
    }

    /**
     * 按不可信标记切分一条消息；伪造标记不会改变状态机之外的语义。
     *
     * @param text 消息文本
     * @param instructionTexts 指令输出
     * @param untrustedTexts 不可信输出
     * @param markers 标记计数（[start, end]）
     */
    private static void scanMarkedText(
            String text, List<String> instructionTexts, List<String> untrustedTexts, int[] markers) {
        int cursor = 0;
        while (cursor < text.length()) {
            int start = text.indexOf(UntrustedData.START, cursor);
            if (start < 0) {
                instructionTexts.add(text.substring(cursor));
                return;
            }
            if (start > cursor) {
                instructionTexts.add(text.substring(cursor, start));
            }
            markers[0]++;
            int end = text.indexOf(UntrustedData.END, start + UntrustedData.START.length());
            if (end < 0) {
                // 没有结束标记时剩余文本仍属于数据面，避免把不完整数据块当指令执行。
                untrustedTexts.add(text.substring(start + UntrustedData.START.length()));
                return;
            }
            markers[1]++;
            untrustedTexts.add(text.substring(start + UntrustedData.START.length(), end));
            cursor = end + UntrustedData.END.length();
        }
    }

    /**
     * 收集命中的检测词。
     *
     * @param texts 文本列表
     * @param detectors 检测词
     * @return 命中词
     */
    private static List<String> hits(List<String> texts, List<String> detectors) {
        List<String> hits = new ArrayList<>();
        String joined = String.join("\n", texts).toLowerCase(Locale.ROOT);
        for (String detector : detectors) {
            if (joined.contains(detector.toLowerCase(Locale.ROOT))) {
                hits.add(detector);
            }
        }
        return hits;
    }

    /**
     * 判断文本是否包含指定值。
     *
     * @param texts 文本列表
     * @param value 值
     * @return 包含返回 true
     */
    private static boolean contains(List<String> texts, String value) {
        return texts.stream().anyMatch(text -> text.contains(value));
    }

    /** 是否存在指令面逃逸；有逃逸时假模型会输出 canary，测试据此失败。 */
    boolean breach() {
        return !instructionHits.isEmpty();
    }

    /** 返回可读逃逸原因。 */
    String breachReason() {
        return "instruction-surface hits=" + instructionHits;
    }

    /** 返回系统消息原文。 */
    List<String> systemTexts() {
        return systemTexts;
    }

    /** 返回指令面文本。 */
    List<String> instructionTexts() {
        return instructionTexts;
    }

    /** 返回不可信数据文本。 */
    List<String> untrustedTexts() {
        return untrustedTexts;
    }

    /** 返回工具结果文本。 */
    List<String> toolResultTexts() {
        return toolResultTexts;
    }

    /** 返回数据块开始标记数。 */
    int startMarkers() {
        return startMarkers;
    }

    /** 返回数据块结束标记数。 */
    int endMarkers() {
        return endMarkers;
    }

    /** 返回指令面命中的检测词。 */
    List<String> instructionHits() {
        return instructionHits;
    }

    /** 返回不可信面命中的检测词。 */
    List<String> untrustedHits() {
        return untrustedHits;
    }
}
