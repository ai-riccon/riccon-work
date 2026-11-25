package com.riccon.worker.workflow.approval.service;

import com.riccon.worker.mcp.domain.Digests;
import com.riccon.worker.workflow.approval.domain.WorkflowApprovalPlanInput;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 审批计划绑定哈希（{@code plan_sha256}）。
 *
 * <p>ADR-0024 §4.7：哈希绑定“版本 + 节点配置 + 上游输出摘要”，执行前重算，漂移即
 * {@code WF_PLAN_DRIFT}。规范化形式固定为：
 *
 * <pre>{@code
 * {
 *   "schema": "workflow-approval-plan-v1",
 *   "workflowId": "...",
 *   "workflowVersionId": "...",
 *   "nodeKey": "...",
 *   "actionKind": "...",
 *   "assigneeType": "...",
 *   "assigneeRoleCode": "..."        // 用户审批省略
 *   "assigneeUserIds": ["..."],      // 排序去重
 *   "targetNodeKey": "...",          // 为空省略
 *   "targetToolId": "...",           // 为空省略
 *   "targetToolName": "...",         // 为空省略
 *   "upstream": [{"nodeKey":"a","summary":"..."}]  // 按 nodeKey 排序
 * }
 * }</pre>
 *
 * <p>空字段省略而不是写空串，避免“未配置”和“配置为空串”产生两个哈希；列表排序保证同一语义
 * 在不同 JVM / 不同请求顺序下得到相同摘要。T07b 在工具执行前必须用同一实现重算。
 */
@Component
public class WorkflowApprovalPlanHasher {

    /** 计划哈希 schema 版本；修改参与字段时必须升级并同步 T07b。 */
    public static final String PLAN_SCHEMA = "workflow-approval-plan-v1";

    /** JSON 序列化。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建计划哈希器。
     *
     * @param objectMapper JSON 序列化
     */
    public WorkflowApprovalPlanHasher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 计算计划绑定哈希。
     *
     * @param input 计划输入
     * @return 小写十六进制 SHA-256（64 字符）
     * @throws IllegalArgumentException 无法序列化（理论不可达）
     */
    public String hash(WorkflowApprovalPlanInput input) {
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("schema", PLAN_SCHEMA);
        canonical.put("workflowId", input.workflowId().toString());
        canonical.put("workflowVersionId", input.workflowVersionId().toString());
        canonical.put("nodeKey", input.nodeKey());
        canonical.put("actionKind", input.actionKind().name());
        canonical.put("assigneeType", input.assigneeType().name());
        if (input.assigneeRoleCode() != null) {
            canonical.put("assigneeRoleCode", input.assigneeRoleCode());
        }
        canonical.put("assigneeUserIds", sortedUserIds(input.assigneeUserIds()));
        if (input.targetNodeKey() != null) {
            canonical.put("targetNodeKey", input.targetNodeKey());
        }
        if (input.targetToolId() != null) {
            canonical.put("targetToolId", input.targetToolId().toString());
        }
        if (input.targetToolName() != null) {
            canonical.put("targetToolName", input.targetToolName());
        }
        List<Map<String, String>> upstream = new ArrayList<>();
        new TreeMap<>(input.upstreamSummaries()).forEach((nodeKey, summary) -> {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("nodeKey", nodeKey);
            item.put("summary", summary == null ? "" : summary);
            upstream.add(item);
        });
        canonical.put("upstream", upstream);
        try {
            return Digests.sha256Hex(objectMapper.writeValueAsString(canonical));
        } catch (JacksonException exception) {
            // 只含字符串与列表，序列化失败说明 ObjectMapper 被错误配置；失败关闭。
            throw new IllegalStateException("workflow approval plan cannot be canonicalized", exception);
        }
    }

    /**
     * 用户 ID 排序去重。
     *
     * @param userIds 用户 ID
     * @return 排序后的字符串列表
     */
    private static List<String> sortedUserIds(List<UUID> userIds) {
        return userIds.stream().map(UUID::toString).sorted().toList();
    }
}
