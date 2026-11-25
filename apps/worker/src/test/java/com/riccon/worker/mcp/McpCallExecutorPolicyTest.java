package com.riccon.worker.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.riccon.platform.domain.mcp.McpToolRisk;
import com.riccon.platform.domain.mcp.McpTransport;
import com.riccon.worker.config.McpProperties;
import com.riccon.worker.mcp.domain.McpServerDescriptor;
import com.riccon.worker.mcp.domain.McpToolDescriptor;
import com.riccon.worker.mcp.domain.McpToolHandle;
import com.riccon.worker.mcp.repository.McpRepository;
import com.riccon.worker.mcp.service.McpCallExecutor;
import com.riccon.worker.mcp.service.McpClientFactory;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * MCP-001/MCP-008：共享执行器双策略，J2 READ_ONLY 继续拒绝 WRITE。
 *
 * <p>策略由调用方显式传入，执行器不猜测调用方；未知/缺失风险等级 fail-closed。
 * M06 的 APPROVAL_BOUND_WRITE 只放宽风险等级，不放开“必须有审批 ID”这一底线。
 */
class McpCallExecutorPolicyTest {

    /** 允许的 server code。 */
    private static final String SERVER_CODE = "riccon-builtin";

    /** 调用方审批 ID。 */
    private static final UUID APPROVAL_ID = UUID.randomUUID();

    /** 测试执行器。 */
    private final McpCallExecutor executor =
            new McpCallExecutor(mock(McpRepository.class), mock(McpClientFactory.class), properties(65536));

    /** J2：READ 工具 + 审批通过现存策略。 */
    @Test
    @DisplayName("READ_ONLY 允许 READ 工具")
    void readOnly_allowsReadTool() {
        assertThat(executor.validatePolicy(
                        handle(McpToolRisk.READ, true, true), McpCallExecutor.Policy.READ_ONLY, APPROVAL_ID))
                .isNull();
    }

    /** J2：WRITE 工具即使有审批也继续拒绝（M02 行为不放宽）。 */
    @Test
    @DisplayName("READ_ONLY 拒绝 WRITE 工具")
    void readOnly_rejectsWriteTool() {
        assertThat(executor.validatePolicy(
                        handle(McpToolRisk.WRITE, true, true), McpCallExecutor.Policy.READ_ONLY, APPROVAL_ID))
                .isEqualTo("mcp tool risk level is not allowed");
    }

    /** J2：缺少审批 ID 拒绝。 */
    @Test
    @DisplayName("READ_ONLY 缺少审批拒绝")
    void readOnly_rejectsMissingApproval() {
        assertThat(executor.validatePolicy(
                        handle(McpToolRisk.READ, true, true), McpCallExecutor.Policy.READ_ONLY, null))
                .isEqualTo("mcp tool call is missing approval");
    }

    /** J2：工具不再要求审批时拒绝，避免目录被改成免审批。 */
    @Test
    @DisplayName("READ_ONLY 要求审批标记")
    void readOnly_rejectsToolWithoutApprovalRequirement() {
        assertThat(executor.validatePolicy(
                        handle(McpToolRisk.READ, false, true), McpCallExecutor.Policy.READ_ONLY, APPROVAL_ID))
                .isEqualTo("mcp tool must require human approval");
    }

    /** M06：WRITE 工具在审批绑定策略下允许。 */
    @Test
    @DisplayName("APPROVAL_BOUND_WRITE 允许 WRITE 工具")
    void approvalBoundWrite_allowsWriteTool() {
        assertThat(executor.validatePolicy(
                        handle(McpToolRisk.WRITE, true, true),
                        McpCallExecutor.Policy.APPROVAL_BOUND_WRITE,
                        APPROVAL_ID))
                .isNull();
    }

    /** M06：审批 ID 仍是硬要求。 */
    @Test
    @DisplayName("APPROVAL_BOUND_WRITE 缺少审批拒绝")
    void approvalBoundWrite_rejectsMissingApproval() {
        assertThat(executor.validatePolicy(
                        handle(McpToolRisk.WRITE, true, true), McpCallExecutor.Policy.APPROVAL_BOUND_WRITE, null))
                .isEqualTo("mcp tool call is missing approval");
    }

    /** 两个策略共享平台白名单检查。 */
    @Test
    @DisplayName("平台白名单对两种策略都生效")
    void bothPolicies_rejectServerOutsideAllowlist() {
        McpToolHandle handle = handle("tenant-other", McpToolRisk.WRITE, true, true);
        assertThat(executor.validatePolicy(handle, McpCallExecutor.Policy.READ_ONLY, APPROVAL_ID))
                .isEqualTo("mcp server is not in the platform allowlist");
        assertThat(executor.validatePolicy(handle, McpCallExecutor.Policy.APPROVAL_BOUND_WRITE, APPROVAL_ID))
                .isEqualTo("mcp server is not in the platform allowlist");
    }

    /** 停用工具对两种策略都失败关闭。 */
    @Test
    @DisplayName("停用工具对两种策略都拒绝")
    void bothPolicies_rejectInactiveTool() {
        McpToolHandle handle = handle(SERVER_CODE, McpToolRisk.READ, true, false);
        assertThat(executor.validatePolicy(handle, McpCallExecutor.Policy.READ_ONLY, APPROVAL_ID))
                .isEqualTo("mcp server or tool is disabled");
        assertThat(executor.validatePolicy(handle, McpCallExecutor.Policy.APPROVAL_BOUND_WRITE, APPROVAL_ID))
                .isEqualTo("mcp server or tool is disabled");
    }

    /** 结果超过单次预算：抛 ResultLimitException，错误码保持 J2 RICCON-MCP-422。 */
    @Test
    @DisplayName("MCP-008 结果超限失败关闭")
    void call_resultOverBudget_throwsResultLimit() {
        McpClientFactory factory = mock(McpClientFactory.class);
        McpClientWrapper wrapper = mock(McpClientWrapper.class);
        when(factory.create(any())).thenReturn(wrapper);
        McpSchema.CallToolResult response =
                new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("x".repeat(2048))), false);
        when(wrapper.callTool(anyString(), anyMap())).thenReturn(Mono.just(response));
        McpCallExecutor limited = new McpCallExecutor(mock(McpRepository.class), factory, properties(1024));

        assertThatThrownBy(() -> limited.call(handle(McpToolRisk.READ, true, true), Map.of("text", "hello")))
                .isInstanceOf(McpCallExecutor.ResultLimitException.class)
                .satisfies(exception -> assertThat(((McpCallExecutor.ResultLimitException) exception).errorCode())
                        .isEqualTo("RICCON-MCP-422"));
    }

    /**
     * 构造 MCP 配置。
     *
     * @param maxResultBytes 结果上限
     * @return 配置
     */
    private static McpProperties properties(int maxResultBytes) {
        return new McpProperties(
                List.of(SERVER_CODE), true, true, Duration.ofSeconds(5), Duration.ofSeconds(5), maxResultBytes, 500);
    }

    /**
     * 构造 READ 工具句柄。
     *
     * @param risk 风险
     * @param requiresApproval 是否要求审批
     * @param active 是否启用
     * @return 工具句柄
     */
    private static McpToolHandle handle(McpToolRisk risk, boolean requiresApproval, boolean active) {
        return handle(SERVER_CODE, risk, requiresApproval, active);
    }

    /**
     * 构造工具句柄。
     *
     * @param serverCode server code
     * @param risk 风险
     * @param requiresApproval 是否要求审批
     * @param active 是否启用
     * @return 工具句柄
     */
    private static McpToolHandle handle(String serverCode, McpToolRisk risk, boolean requiresApproval, boolean active) {
        UUID serverId = UUID.randomUUID();
        McpToolDescriptor tool = new McpToolDescriptor(
                UUID.randomUUID(), serverId, "echo", "echo", Map.of("type", "object"), risk, requiresApproval, active);
        McpServerDescriptor server = new McpServerDescriptor(
                serverId, serverCode, "test", McpTransport.STDIO, null, "java -version", null, active);
        return new McpToolHandle(tool, server);
    }
}
