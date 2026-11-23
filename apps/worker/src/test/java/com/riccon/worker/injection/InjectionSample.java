package com.riccon.worker.injection;

import java.util.List;

/**
 * 一条可执行的提示注入样本。
 *
 * <p>样本编号是稳定契约：`INJ-001..012` 与 ADR-0023 §8、`docs/security/THREAT-MODEL.md`
 * TM-13 / B-03 引用同一批编号。每个样本必须能回答四个问题：注入从哪里来、期望是否进入模型上下文、
 * 模型应保持哪些系统预设、失败时如何定位。
 *
 * @param id 稳定用例编号
 * @param surface 注入进入点
 * @param locale 语种（zh-CN、en、zh-en）
 * @param sheetName Excel 样本的工作表名；OCR 样本为 null
 * @param payload 注入原文；测试断言以它和 {@link InjectionSamples#DETECTORS} 判定边界
 * @param probe 可读断言点，失败消息中包含该字段
 * @param threatAlias 威胁模型别名；无别名时为空字符串
 * @param expectIndexed 期望该文本进入检索/模型上下文；隐藏表为 false
 * @param expectToolCall 期望假模型发起一次只读知识检索；仅 INJ-011 为 true
 * @param expectForgedMarkers 期望样本包含伪造边界标记，必须被转义
 */
record InjectionSample(
        String id,
        InjectionSurface surface,
        String locale,
        String sheetName,
        String payload,
        String probe,
        String threatAlias,
        boolean expectIndexed,
        boolean expectToolCall,
        boolean expectForgedMarkers) {

    /** 返回失败定位用的完整样本名。 */
    String display() {
        return id + "[" + surface + "/" + locale + "]";
    }

    /** 返回样本的检测词；空列表时使用目录级默认检测词。 */
    List<String> detectors() {
        return InjectionSamples.DETECTORS;
    }
}
