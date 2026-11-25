package com.riccon.worker.workflow.tool.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.platform.domain.workflow.WorkflowErrorCode;
import com.riccon.worker.mcp.domain.McpException;
import com.riccon.worker.mcp.service.McpCallExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * IT-WF-104：MCP 错误到工作流稳定错误码的映射。
 *
 * <p>只有明确授权拒绝与结果超限可以给出确定性错误码；端点、协议、超时与未知异常都可能已经产生
 * 外部副作用，统一映射为 {@code WF_SIDE_EFFECT_UNKNOWN}，不得声明可安全重试。
 */
class WorkflowToolNodeExecutorTest {

    /** 结果超限映射为工具结果上限。 */
    @Test
    @DisplayName("结果超限映射 WF_TOOL_RESULT_LIMIT")
    void errorCodeOf_resultLimit() {
        assertThat(WorkflowToolNodeExecutor.errorCodeOf(
                        new McpCallExecutor.ResultLimitException("mcp tool result exceeds size budget")))
                .isEqualTo(WorkflowErrorCode.WF_TOOL_RESULT_LIMIT);
    }

    /** 明确授权拒绝映射为未授权。 */
    @Test
    @DisplayName("授权拒绝映射 WF_TOOL_NOT_AUTHORIZED")
    void errorCodeOf_denied() {
        assertThat(WorkflowToolNodeExecutor.errorCodeOf(
                        new McpException(McpException.DENIED, false, "mcp tool is not authorized")))
                .isEqualTo(WorkflowErrorCode.WF_TOOL_NOT_AUTHORIZED);
    }

    /** 端点/协议/超时映射为未知副作用。 */
    @Test
    @DisplayName("其他失败映射 WF_SIDE_EFFECT_UNKNOWN")
    void errorCodeOf_otherFailures() {
        assertThat(WorkflowToolNodeExecutor.errorCodeOf(
                        new McpException(McpException.UNAVAILABLE, true, "mcp tool call failed")))
                .isEqualTo(WorkflowErrorCode.WF_SIDE_EFFECT_UNKNOWN);
        assertThat(WorkflowToolNodeExecutor.errorCodeOf(
                        new McpException(McpException.INVALID, false, "mcp tool returned an error result")))
                .isEqualTo(WorkflowErrorCode.WF_SIDE_EFFECT_UNKNOWN);
        assertThat(WorkflowToolNodeExecutor.errorCodeOf(
                        new McpException(McpException.ALREADY_INVOKED, false, "approval has already been executed")))
                .isEqualTo(WorkflowErrorCode.WF_SIDE_EFFECT_UNKNOWN);
    }
}
