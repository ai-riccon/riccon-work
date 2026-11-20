package com.riccon.api.research.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 报告任务失败文案映射单元测试。
 */
class ResearchFailureTextTest {

    /** 全链不可用一律返回 AC-05 固定文案，不受落库说明影响。 */
    @Test
    @DisplayName("RICCON-MODEL-503 统一为 AC-05 文案")
    void modelUnavailable_returnsFixedMessage() {
        assertThat(ResearchFailureText.message("RICCON-MODEL-503", null)).isEqualTo("模型服务暂不可用，请稍后重试。");
        assertThat(ResearchFailureText.message("RICCON-MODEL-503", "")).isEqualTo("模型服务暂不可用，请稍后重试。");
        assertThat(ResearchFailureText.message("RICCON-MODEL-503", "research tool stage failed"))
                .isEqualTo("模型服务暂不可用，请稍后重试。");
    }

    /** 其他错误码保留已落库的脱敏说明。 */
    @Test
    @DisplayName("其他错误码原样返回")
    void otherCodes_keepStoredMessage() {
        assertThat(ResearchFailureText.message("RICCON-MCP-504", "mcp tool timed out"))
                .isEqualTo("mcp tool timed out");
        assertThat(ResearchFailureText.message(null, "审批被拒绝，任务已终止。")).isEqualTo("审批被拒绝，任务已终止。");
        assertThat(ResearchFailureText.message("RICCON-QUOTA-429", null)).isNull();
    }
}
