package com.riccon.worker.mcp.service;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.mcp.McpInvocationStatus;
import com.riccon.platform.domain.mcp.McpToolRisk;
import com.riccon.worker.audit.service.AuditService;
import com.riccon.worker.common.UuidV7;
import com.riccon.worker.config.McpProperties;
import com.riccon.worker.mcp.domain.ArgumentDigest;
import com.riccon.worker.mcp.domain.Digests;
import com.riccon.worker.mcp.domain.McpCallResult;
import com.riccon.worker.mcp.domain.McpException;
import com.riccon.worker.mcp.domain.McpInvocationRecord;
import com.riccon.worker.mcp.domain.McpToolHandle;
import com.riccon.worker.mcp.repository.McpRepository;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * MCP 工具调用网关。
 *
 * <p>每次调用必须同时满足：租户已授权、工具 READ、server 在平台白名单、工具要求审批且存在审批 ID。
 * 调用前写 {@code mcp_invocation} STARTED，结束后写终态与 {@code mcp.tool.invoked} 审计；
 * 请求/响应原文不落库、不写日志，只保存 SHA-256、大小与脱敏摘要。
 */
@Component
public class McpGateway {

    /** 日志记录器。 */
    private static final Logger LOG = LoggerFactory.getLogger(McpGateway.class);

    /** 调用记录仓储。 */
    private final McpRepository mcpRepository;

    /** 客户端工厂。 */
    private final McpClientFactory clientFactory;

    /** 审计服务。 */
    private final AuditService auditService;

    /** MCP 配置。 */
    private final McpProperties properties;

    /** JSON 序列化器。 */
    private final ObjectMapper objectMapper;

    /** UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建 MCP 网关。
     *
     * @param mcpRepository MCP 仓储
     * @param clientFactory 客户端工厂
     * @param auditService 审计服务
     * @param properties MCP 配置
     * @param objectMapper JSON 序列化器
     * @param clock UTC 时钟
     */
    public McpGateway(
            McpRepository mcpRepository,
            McpClientFactory clientFactory,
            AuditService auditService,
            McpProperties properties,
            ObjectMapper objectMapper,
            Clock clock) {
        this.mcpRepository = Objects.requireNonNull(mcpRepository, "mcpRepository must not be null");
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory must not be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 调用一次已审批的 MCP 工具。
     *
     * @param command 调用命令
     * @return 脱敏结果摘要与供模型使用的文本
     * @throws McpException 授权、审批、端点或协议失败
     */
    public McpCallResult invoke(McpInvocationCommand command) {
        Optional<McpToolHandle> maybeHandle = mcpRepository.findAuthorizedTool(command.tenantId(), command.toolId());
        if (maybeHandle.isEmpty()) {
            auditDeniedWithoutInvocation(command, "mcp tool is not authorized");
            throw new McpException(McpException.DENIED, false, "mcp tool is not authorized");
        }
        McpToolHandle handle = maybeHandle.get();
        String policyFailure = validatePolicy(handle, command.approvalId());
        if (policyFailure != null) {
            deny(command, handle, policyFailure);
        }

        String paramsJson = canonicalJson(command.arguments());
        String paramsSha256 = Digests.sha256Hex(paramsJson);
        String paramsPreview = serializePreview(command.arguments());
        UUID invocationId = UuidV7.generate();
        long startedAt = clock.millis();
        mcpRepository.invocationStarted(
                command.tenantId(),
                new McpInvocationRecord(
                        invocationId,
                        command.runId(),
                        command.researchTaskId(),
                        command.approvalId(),
                        handle.tool().id(),
                        handle.tool().name(),
                        paramsSha256,
                        paramsPreview));

        try {
            McpCallResult result = call(command, handle);
            long duration = Math.max(0, clock.millis() - startedAt);
            String resultPreview = "sha256=" + result.resultSha256() + ",bytes=" + result.sizeBytes();
            mcpRepository.invocationSucceeded(
                    command.tenantId(),
                    invocationId,
                    result.resultSha256(),
                    result.sizeBytes(),
                    resultPreview,
                    duration);
            auditService.mcpInvoked(
                    command.tenantId(),
                    command.traceId(),
                    command.requestId(),
                    handle.tool().id(),
                    handle.tool().name(),
                    paramsSha256,
                    result.resultSha256(),
                    duration,
                    command.approvalId(),
                    AuditOutcome.SUCCESS);
            LOG.info(
                    "mcp.tool.completed tenantId={} runId={} toolId={} durationMs={} resultBytes={}",
                    command.tenantId().value(),
                    command.runId(),
                    handle.tool().id(),
                    duration,
                    result.sizeBytes());
            return new McpCallResult(result.text(), result.resultSha256(), result.sizeBytes(), duration);
        } catch (McpException ex) {
            finishFailure(command, handle, invocationId, ex.errorCode(), startedAt);
            throw ex;
        } catch (RuntimeException ex) {
            McpException wrapped =
                    new McpException(McpException.UNAVAILABLE, true, "mcp tool call failed unexpectedly", ex);
            finishFailure(command, handle, invocationId, wrapped.errorCode(), startedAt);
            throw wrapped;
        }
    }

    /**
     * 校验工具策略，返回失败原因或 null。
     *
     * @param handle 工具与 server
     * @param approvalId 审批 ID
     * @return 失败原因；通过时为 null
     */
    private String validatePolicy(McpToolHandle handle, UUID approvalId) {
        if (!handle.server().active() || !handle.tool().active()) {
            return "mcp server or tool is disabled";
        }
        if (!properties.allowedServerCodes().contains(handle.server().code())) {
            return "mcp server is not in the platform allowlist";
        }
        if (handle.tool().riskLevel() != McpToolRisk.READ) {
            return "mcp tool risk level is not allowed";
        }
        if (!handle.tool().requiresApproval()) {
            return "mcp tool must require human approval";
        }
        if (approvalId == null) {
            return "mcp tool call is missing approval";
        }
        return null;
    }

    /**
     * 记录拒绝并抛出异常。
     *
     * @param command 调用命令
     * @param handle 工具
     * @param reason 脱敏原因
     * @throws McpException 固定拒绝错误
     */
    private void deny(McpInvocationCommand command, McpToolHandle handle, String reason) {
        String paramsJson = canonicalJson(command.arguments());
        String paramsSha256 = Digests.sha256Hex(paramsJson);
        UUID invocationId = UuidV7.generate();
        long startedAt = clock.millis();
        mcpRepository.invocationStarted(
                command.tenantId(),
                new McpInvocationRecord(
                        invocationId,
                        command.runId(),
                        command.researchTaskId(),
                        command.approvalId(),
                        handle.tool().id(),
                        handle.tool().name(),
                        paramsSha256,
                        serializePreview(command.arguments())));
        long duration = Math.max(0, clock.millis() - startedAt);
        mcpRepository.invocationTerminated(
                command.tenantId(), invocationId, McpInvocationStatus.DENIED, McpException.DENIED, duration);
        auditService.mcpInvoked(
                command.tenantId(),
                command.traceId(),
                command.requestId(),
                handle.tool().id(),
                handle.tool().name(),
                paramsSha256,
                null,
                duration,
                command.approvalId(),
                AuditOutcome.DENIED);
        LOG.warn(
                "mcp.tool.denied tenantId={} runId={} toolId={} reason={}",
                command.tenantId().value(),
                command.runId(),
                handle.tool().id(),
                reason);
        throw new McpException(McpException.DENIED, false, reason);
    }

    /**
     * 无工具目录时写拒绝审计；无法写 mcp_invocation（工具外键不存在）。
     *
     * @param command 调用命令
     * @param reason 脱敏原因
     */
    private void auditDeniedWithoutInvocation(McpInvocationCommand command, String reason) {
        auditService.mcpInvoked(
                command.tenantId(),
                command.traceId(),
                command.requestId(),
                command.toolId(),
                "unknown",
                Digests.sha256Hex(canonicalJson(command.arguments())),
                null,
                0,
                command.approvalId(),
                AuditOutcome.DENIED);
        LOG.warn(
                "mcp.tool.denied tenantId={} runId={} toolId={} reason={}",
                command.tenantId().value(),
                command.runId(),
                command.toolId(),
                reason);
    }

    /**
     * 执行客户端初始化、调用与文本提取。
     *
     * @param command 调用命令
     * @param handle 工具
     * @return 调用结果
     */
    private McpCallResult call(McpInvocationCommand command, McpToolHandle handle) {
        McpClientWrapper client = clientFactory.create(handle.server());
        try {
            McpSchema.CallToolResult response = client.callTool(
                            handle.tool().name(), ArgumentDigest.canonical(command.arguments()))
                    .block(properties.requestTimeout());
            if (response == null) {
                throw new McpException(McpException.INVALID, false, "mcp tool returned no response");
            }
            if (Boolean.TRUE.equals(response.isError())) {
                throw new McpException(McpException.INVALID, false, "mcp tool returned an error result");
            }
            String text = textOf(response);
            long sizeBytes = text.getBytes(StandardCharsets.UTF_8).length;
            if (sizeBytes > properties.maxResultBytes()) {
                throw new McpException(McpException.INVALID, false, "mcp tool result exceeds size budget");
            }
            return new McpCallResult(text, Digests.sha256Hex(text), sizeBytes, 0);
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
     * 写入失败终态与审计；审计失败会覆盖原始错误，确保不静默继续。
     *
     * @param command 调用命令
     * @param handle 工具
     * @param invocationId 调用 ID
     * @param errorCode 错误码
     * @param startedAt 开始时间
     */
    private void finishFailure(
            McpInvocationCommand command, McpToolHandle handle, UUID invocationId, String errorCode, long startedAt) {
        long duration = Math.max(0, clock.millis() - startedAt);
        mcpRepository.invocationTerminated(
                command.tenantId(), invocationId, McpInvocationStatus.FAILED, errorCode, duration);
        auditService.mcpInvoked(
                command.tenantId(),
                command.traceId(),
                command.requestId(),
                handle.tool().id(),
                handle.tool().name(),
                Digests.sha256Hex(canonicalJson(command.arguments())),
                null,
                duration,
                command.approvalId(),
                AuditOutcome.FAILURE);
    }

    /**
     * 序列化入参，失败属于调用方构造错误。
     *
     * @param arguments 入参
     * @return 规范 JSON
     */
    private String canonicalJson(Map<String, Object> arguments) {
        try {
            return objectMapper.writeValueAsString(ArgumentDigest.canonical(arguments));
        } catch (JacksonException ex) {
            throw new McpException(McpException.INVALID, false, "mcp tool arguments cannot be serialized", ex);
        }
    }

    /**
     * 序列化脱敏预览。
     *
     * @param arguments 入参
     * @return JSON
     */
    private String serializePreview(Map<String, Object> arguments) {
        Map<String, Object> preview =
                new LinkedHashMap<>(ArgumentDigest.preview(arguments, properties.paramsPreviewMaxLength()));
        try {
            return objectMapper.writeValueAsString(preview);
        } catch (JacksonException ex) {
            throw new McpException(McpException.INVALID, false, "mcp tool preview cannot be serialized", ex);
        }
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
     * MCP 调用命令。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param researchTaskId 报告任务 ID，可为空
     * @param approvalId 审批 ID；M02 必须非空
     * @param toolId 工具 ID
     * @param arguments 入参
     * @param traceId 追踪 ID
     * @param requestId 请求 ID，可为空
     */
    public record McpInvocationCommand(
            TenantId tenantId,
            UUID runId,
            UUID researchTaskId,
            UUID approvalId,
            UUID toolId,
            Map<String, Object> arguments,
            String traceId,
            String requestId) {}
}
