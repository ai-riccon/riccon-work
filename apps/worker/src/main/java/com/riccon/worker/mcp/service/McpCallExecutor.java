package com.riccon.worker.mcp.service;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.mcp.McpToolRisk;
import com.riccon.worker.config.McpProperties;
import com.riccon.worker.mcp.domain.ArgumentDigest;
import com.riccon.worker.mcp.domain.Digests;
import com.riccon.worker.mcp.domain.McpCallResult;
import com.riccon.worker.mcp.domain.McpException;
import com.riccon.worker.mcp.domain.McpToolHandle;
import com.riccon.worker.mcp.repository.McpRepository;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 共享 MCP 工具执行器（ADR-0024 §4.7 / F-006）。
 *
 * <p>J2 与 M06 工作流共用同一份“授权、端点、超时、结果上限、协议校验”实现，调用方通过显式
 * {@link Policy} 声明策略，执行器不猜测调用方：
 *
 * <ul>
 *   <li>{@link Policy#READ_ONLY}：J2（M02 research）沿用现状，只允许 {@code risk_level=READ}、
 *       {@code requires_approval=TRUE} 且携带审批 ID 的工具；{@code WRITE} 一律拒绝；
 *   <li>{@link Policy#APPROVAL_BOUND_WRITE}：M06 工作流，READ 按目录，WRITE 允许，但调用方必须先完成
 *       {@code workflow_approval} 状态、{@code plan_sha256} 与参数密文校验；执行器仍要求审批 ID，
 *       不把“已批准”当成可省略的凭据。
 * </ul>
 *
 * <p>持久化与审计不在此类：J2 写 {@code mcp_invocation}，工作流写 {@code workflow_tool_invocation}，
 * 两边都写 {@code mcp.tool.invoked}。执行器只返回脱敏结果摘要与文本；请求/响应原文不落日志。
 *
 * <p>未知/非法风险等级 fail-closed（数据库枚举当前只有 READ/WRITE，缺失即目录损坏）。
 */
@Component
public class McpCallExecutor {

    /** 日志记录器。 */
    private static final Logger LOG = LoggerFactory.getLogger(McpCallExecutor.class);

    /** MCP 调用策略。 */
    public enum Policy {
        /** J2：只读工具 + 现有审批；WRITE 继续拒绝。 */
        READ_ONLY,
        /** M06：审批绑定的写工具；READ/WRITE 都要求审批 ID 且由调用方先完成 plan/参数校验。 */
        APPROVAL_BOUND_WRITE
    }

    /** MCP 授权与目录仓储。 */
    private final McpRepository mcpRepository;

    /** 客户端工厂。 */
    private final McpClientFactory clientFactory;

    /** MCP 配置（白名单、结果上限）。 */
    private final McpProperties properties;

    /**
     * 创建共享执行器。
     *
     * @param mcpRepository MCP 仓储
     * @param clientFactory 客户端工厂
     * @param properties MCP 配置
     */
    public McpCallExecutor(McpRepository mcpRepository, McpClientFactory clientFactory, McpProperties properties) {
        this.mcpRepository = Objects.requireNonNull(mcpRepository, "mcpRepository must not be null");
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    /**
     * 读取已授权工具及其 server。
     *
     * @param tenantId 租户
     * @param toolId 工具 ID
     * @return 工具；未授权、停用或不存在时为空
     */
    public Optional<McpToolHandle> findAuthorizedTool(TenantId tenantId, UUID toolId) {
        return mcpRepository.findAuthorizedTool(tenantId, toolId);
    }

    /**
     * 按策略校验工具是否允许本次调用。
     *
     * <p>两个策略共享 server/tool 状态与平台白名单检查；差异只有风险等级、审批必需性与审批 ID。
     * 返回原因只用于服务端日志/稳定错误映射，不包含端点、凭据或工具参数。
     *
     * @param handle 工具与 server
     * @param policy 调用策略
     * @param approvalId 审批 ID；两个策略下都不允许为空
     * @return 失败原因；通过时为 null
     */
    public String validatePolicy(McpToolHandle handle, Policy policy, UUID approvalId) {
        Objects.requireNonNull(handle, "handle must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        if (!handle.server().active() || !handle.tool().active()) {
            return "mcp server or tool is disabled";
        }
        if (!properties.allowedServerCodes().contains(handle.server().code())) {
            return "mcp server is not in the platform allowlist";
        }
        McpToolRisk risk = handle.tool().riskLevel();
        if (risk == null) {
            // 目录损坏/未知风险等级不得按 READ 处理，失败关闭。
            return "mcp tool risk level is unknown";
        }
        switch (policy) {
            case READ_ONLY -> {
                if (risk != McpToolRisk.READ) {
                    return "mcp tool risk level is not allowed";
                }
                if (!handle.tool().requiresApproval()) {
                    return "mcp tool must require human approval";
                }
                if (approvalId == null) {
                    return "mcp tool call is missing approval";
                }
            }
            case APPROVAL_BOUND_WRITE -> {
                // M06：READ/WRITE 都要求 workflow_approval 绑定；WRITE 的 plan/参数校验在调用方完成，
                // 此处只守住“必须有审批 + 工具状态/白名单”这条共享底线。
                if (approvalId == null) {
                    return "mcp tool call is missing approval";
                }
            }
        }
        return null;
    }

    /**
     * 使用 MCP 配置的请求超时执行工具调用。
     *
     * @param handle 工具与 server
     * @param arguments 入参
     * @return 脱敏结果
     * @throws McpException 端点、协议、结果大小或工具错误
     */
    public McpCallResult call(McpToolHandle handle, Map<String, Object> arguments) {
        return call(handle, arguments, properties.requestTimeout());
    }

    /**
     * 执行一次工具调用。
     *
     * @param handle 工具与 server
     * @param arguments 入参
     * @param timeout 单次调用等待上限；必须为正
     * @return 脱敏结果
     * @throws McpException 端点、协议、结果大小或工具错误
     */
    public McpCallResult call(McpToolHandle handle, Map<String, Object> arguments, Duration timeout) {
        Objects.requireNonNull(handle, "handle must not be null");
        Objects.requireNonNull(arguments, "arguments must not be null");
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new McpException(McpException.INVALID, false, "mcp tool call timeout must be positive");
        }
        McpClientWrapper client = clientFactory.create(handle.server());
        try {
            McpSchema.CallToolResult response = client.callTool(
                            handle.tool().name(), ArgumentDigest.canonical(arguments))
                    .block(timeout);
            if (response == null) {
                throw new McpException(McpException.INVALID, false, "mcp tool returned no response");
            }
            if (Boolean.TRUE.equals(response.isError())) {
                throw new McpException(McpException.INVALID, false, "mcp tool returned an error result");
            }
            String text = textOf(response);
            long sizeBytes = text.getBytes(StandardCharsets.UTF_8).length;
            if (sizeBytes > properties.maxResultBytes()) {
                throw new ResultLimitException("mcp tool result exceeds size budget");
            }
            return new McpCallResult(null, text, Digests.sha256Hex(text), sizeBytes, 0);
        } catch (McpException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new McpException(McpException.UNAVAILABLE, true, "mcp tool call failed", ex);
        } finally {
            close(client);
        }
    }

    /**
     * 拼接 MCP text content。
     *
     * @param response 工具响应
     * @return 文本
     */
    private static String textOf(McpSchema.CallToolResult response) {
        StringBuilder builder = new StringBuilder();
        for (McpSchema.Content content : response.content()) {
            if (content instanceof McpSchema.TextContent text) {
                builder.append(text.text());
            }
        }
        return builder.toString();
    }

    /**
     * 关闭客户端；关闭失败只影响资源回收，不回滚已完成的工具调用。
     *
     * @param client 客户端
     */
    private static void close(McpClientWrapper client) {
        try {
            client.close();
        } catch (RuntimeException ex) {
            LOG.warn("mcp.client.close_failed", ex);
        }
    }

    /**
     * 工具结果超过单次大小预算。
     *
     * <p>继承 {@link McpException} 以保持 J2 的错误码不变（{@code RICCON-MCP-422}）；M06 通过类型识别
     * 并映射为 {@code WF_TOOL_RESULT_LIMIT}。消息固定，不含结果原文或大小细节。
     */
    public static final class ResultLimitException extends McpException {

        /**
         * 创建结果超限异常。
         *
         * @param message 脱敏说明
         */
        public ResultLimitException(String message) {
            super(McpException.INVALID, false, message);
        }
    }
}
